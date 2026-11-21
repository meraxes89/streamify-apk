// ============================================================================
//  test_palette_simd.cpp — Phase-5 verification: SIMD palette clustering &
//  WCAG 2.1 contrast engine (native-dsp CI shard)
// ============================================================================
//
//  Covers the Phase-5 directive deliverable 1:
//    A. WCAG 2.1 math — reference vectors (black/white = 21, the #767676 /
//       #777777 AA boundary), pure-channel luminances, symmetry, ratio
//       bounds, MeetsContrast edge cases (NaN via bit patterns, thresholds
//       <= 1, exact 4.5 boundary).
//    B. Solid-color exactness — the exact-centroid contract: a solid image
//       yields EXACTLY its color as dominant (structured + 500 random
//       solids, random alpha bytes prove alpha independence).
//    C. Role contracts on every image — text surface >= 4.5:1 vs dominant
//       (the sqrt(21) guarantee), pairwise role distinctness, alpha 0xFF,
//       dark-muted luminance <= 0.5, light-vibrant value >= 0.78.
//    D. Dominance semantics — majority wins with equal saturation; a
//       vibrant minority beats a muted majority for the Vibrant role.
//    E. Determinism — identical bit-for-bit palettes across repeated runs.
//    F. Stride/padding — padded stride bit-identical to tight packing.
//    G. SIMD vs scalar ingest — BIT-IDENTICAL histograms (population and
//       channel-sum planes) across random images including odd widths.
//    H. Subsampling path — 1.08M-pixel solid image: exact dominant,
//       deterministic repeat, sample budget respected.
//    I. Degenerate 1x1 / validation ladder / 16384-dimension caps.
//    J. Zero-allocation audit (AllocGuard) on the warmed hot path.
//    K. Performance smoke — 1080x1080 in << 1 ms locally, CI-safe bound
//       (measured time printed for the shard log).
//
//  Linked into dsp_test_suite; entry point run_palette_simd_tests() is
//  called from test_dsp.cc's main().
// ============================================================================

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#include "../include/palette_extractor_simd.h"

#include "AllocGuard.h"

using streamify::palette_simd::ContrastRatio;
using streamify::palette_simd::ExtractPaletteSimd;
using streamify::palette_simd::kHistogramBins;
using streamify::palette_simd::kPaletteMaxDim;
using streamify::palette_simd::kPaletteMaxSamples;
using streamify::palette_simd::MeetsContrast;
using streamify::palette_simd::PaletteError;
using streamify::palette_simd::PaletteRoles;
using streamify::palette_simd::RelativeLuminance;
using streamify::palette_simd::SimdPaletteExtractor;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

// Deterministic RNG (SplitMix64) — same family as the other suites.
struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed) {}
    uint64_t next() {
        s += 0x9E3779B97F4A7C15ull;
        uint64_t z = s;
        z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ull;
        z = (z ^ (z >> 27)) * 0x94D049BB133111EBull;
        return z ^ (z >> 31);
    }
    uint8_t byte8() { return static_cast<uint8_t>(next() & 0xFF); }
};

void FillSolid(std::vector<uint8_t>* img, int w, int h, uint8_t r, uint8_t g,
               uint8_t b, bool random_alpha, Rng* rng) {
    img->assign(static_cast<size_t>(w) * h * 4, 0xFF);
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            uint8_t* p = img->data() + (static_cast<size_t>(y) * w + x) * 4;
            p[0] = r;
            p[1] = g;
            p[2] = b;
            p[3] = random_alpha ? rng->byte8() : 0xFF;
        }
    }
}

// Full role-contract audit for one extraction result.
void CheckRoleContracts(const PaletteRoles& roles, const char* tag) {
    char buf[128];
    std::snprintf(buf, sizeof buf, "%s: text >= 4.5:1 vs dominant", tag);
    check(ContrastRatio(roles.text_surface, roles.dominant_vibrant) >= 4.5f, buf);
    std::snprintf(buf, sizeof buf, "%s: roles pairwise distinct", tag);
    check(roles.dominant_vibrant != roles.dark_muted &&
              roles.dominant_vibrant != roles.light_vibrant &&
              roles.dark_muted != roles.light_vibrant,
          buf);
    std::snprintf(buf, sizeof buf, "%s: alpha 0xFF on all roles", tag);
    check((roles.dominant_vibrant >> 24) == 0xFF &&
              (roles.dark_muted >> 24) == 0xFF &&
              (roles.light_vibrant >> 24) == 0xFF &&
              (roles.text_surface >> 24) == 0xFF,
          buf);
    std::snprintf(buf, sizeof buf, "%s: dark muted luminance <= 0.5", tag);
    check(RelativeLuminance(roles.dark_muted) <= 0.5f, buf);
    std::snprintf(buf, sizeof buf, "%s: light vibrant value >= 0.78", tag);
    {
        const uint32_t c = roles.light_vibrant;
        const int mx = std::max((c >> 16) & 0xFF, std::max((c >> 8) & 0xFF, c & 0xFF));
        check(mx >= 199, buf);  // 0.78 * 255 = 198.9
    }
}

}  // namespace

int run_palette_simd_tests() {
    std::printf("  [Phase-5] Palette SIMD & WCAG contrast engine\n");

    // ---- A. WCAG 2.1 reference math -----------------------------------------
    {
        const float bw = ContrastRatio(0xFF000000, 0xFFFFFFFF);
        check(bw > 20.9f && bw < 21.1f, "WCAG: black/white ratio = 21");
        check(ContrastRatio(0xFF767676, 0xFFFFFFFF) >= 4.5f,
              "WCAG: #767676 vs white passes AA (lightest passing gray)");
        check(ContrastRatio(0xFF777777, 0xFFFFFFFF) < 4.5f,
              "WCAG: #777777 vs white fails AA");
        check(ContrastRatio(0xFF0000FF, 0xFFFFFF00) > 6.0f,
              "WCAG: blue vs yellow high contrast");
        check(ContrastRatio(0xFF123456, 0xFF123456) == 1.0f,
              "WCAG: identical colors ratio = 1");
        const float ab_lum = RelativeLuminance(0xFFFFFFFF);
        check(ab_lum > 0.999f && ab_lum < 1.001f, "WCAG: white luminance = 1");
        const float r_lum = RelativeLuminance(0xFFFF0000);
        check(r_lum > 0.2124f && r_lum < 0.2128f,
              "WCAG: pure red luminance = 0.2126");
        const float g_lum = RelativeLuminance(0xFF00FF00);
        check(g_lum > 0.7151f && g_lum < 0.7153f,
              "WCAG: pure green luminance = 0.7152");
        const float b_lum = RelativeLuminance(0xFF0000FF);
        check(b_lum > 0.0721f && b_lum < 0.0723f,
              "WCAG: pure blue luminance = 0.0722");
        // Symmetry + bounds over a color sweep.
        Rng rng(11);
        for (int i = 0; i < 500; ++i) {
            const uint32_t a = 0xFF000000u | (rng.next() & 0xFFFFFFu);
            const uint32_t b = 0xFF000000u | (rng.next() & 0xFFFFFFu);
            const float r1 = ContrastRatio(a, b);
            const float r2 = ContrastRatio(b, a);
            check(r1 >= 1.0f && r1 <= 21.0f + 1e-4f, "WCAG: ratio bounds");
            check(std::fabs(r1 - r2) < 1e-5f, "WCAG: ratio symmetric");
        }
        // MeetsContrast edges.
        check(!MeetsContrast(0xFF000000, 0xFFFFFFFF,
                             std::numeric_limits<float>::quiet_NaN()),
              "MeetsContrast: NaN threshold rejected");
        check(MeetsContrast(0xFF123456, 0xFF123456, 1.0f),
              "MeetsContrast: threshold 1.0 passes on equal colors");
        check(MeetsContrast(0xFF000000, 0xFFFFFFFF, -3.0f),
              "MeetsContrast: negative threshold trivially passes");
        // 1.05f/0.05f rounds to 20.999981 in float — the exact-21.0
        // threshold would be a coin flip on ULPs; assert just below it.
        check(MeetsContrast(0xFF000000, 0xFFFFFFFF, 20.99f),
              "MeetsContrast: ~21 max ratio passes");
        check(!MeetsContrast(0xFF000000, 0xFFFFFFFF, 21.5f),
              "MeetsContrast: beyond-max threshold fails");
        // Exhaustive gray ramp: monotone in luminance, boundary exact.
        uint32_t last_passing = 0;
        for (int v = 0; v <= 255; ++v) {
            const uint32_t gray = 0xFF000000u | (v << 16) | (v << 8) | v;
            if (ContrastRatio(gray, 0xFFFFFFFF) >= 4.5f) last_passing = v;
        }
        check(last_passing == 0x76, "WCAG: exhaustive gray ramp boundary #767676");
    }

    // ---- B/C. Solid-color exactness + role contracts -------------------------
    {
        SimdPaletteExtractor ex;
        check(ex.valid(), "extractor valid scratch");
        std::vector<uint8_t> img;
        Rng rng(2024);

        const uint32_t solids[] = {
            0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
            0xFF808080, 0xFF123456, 0xFFEFEFEF, 0xFF0A0A0A, 0xFFCC3366,
            0xFF003366, 0xFFFEDCBA, 0xFF010101, 0xFF7F7F7F, 0xFF800000,
        };
        for (uint32_t ref : solids) {
            const uint8_t r = (ref >> 16) & 0xFF, g = (ref >> 8) & 0xFF,
                          b = ref & 0xFF;
            FillSolid(&img, 64, 64, r, g, b, false, &rng);
            PaletteRoles roles;
            check(ex.Extract(img.data(), img.size(), 64, 64, 64 * 4, &roles) ==
                      PaletteError::kOk,
                  "solid: extract ok");
            char buf[96];
            std::snprintf(buf, sizeof buf,
                          "solid #%06X dominant exact (got #%06X)", ref & 0xFFFFFF,
                          roles.dominant_vibrant & 0xFFFFFF);
            check(roles.dominant_vibrant == ref, buf);
            CheckRoleContracts(roles, "solid");
        }

        // Random solids with RANDOM ALPHA — proves alpha is ignored.
        for (int t = 0; t < 500; ++t) {
            const uint8_t r = rng.byte8(), g = rng.byte8(), b = rng.byte8();
            const int w = 1 + static_cast<int>(rng.next() % 64);
            const int h = 1 + static_cast<int>(rng.next() % 64);
            FillSolid(&img, w, h, r, g, b, true, &rng);
            PaletteRoles roles;
            check(ex.Extract(img.data(), img.size(), w, h, w * 4, &roles) ==
                      PaletteError::kOk,
                  "random solid: extract ok");
            const uint32_t expect = 0xFF000000u | (r << 16) | (g << 8) | b;
            char buf[96];
            std::snprintf(buf, sizeof buf,
                          "random solid #%06X dominant exact (w=%d h=%d)",
                          expect & 0xFFFFFF, w, h);
            check(roles.dominant_vibrant == expect, buf);
            CheckRoleContracts(roles, "random solid");
        }
    }

    // ---- D. Dominance semantics ----------------------------------------------
    {
        SimdPaletteExtractor ex;
        std::vector<uint8_t> img;
        // 75% muted dark red vs 25% saturated bright red: the vibrant minority
        // wins the Dominant Vibrant role (Android-Palette-style semantics).
        const int w = 40, h = 40;
        img.assign(static_cast<size_t>(w) * h * 4, 0);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                uint8_t* p = &img[(static_cast<size_t>(y) * w + x) * 4];
                if ((y * w + x) % 4 == 0) {
                    p[0] = 0xFF; p[1] = 0x30; p[2] = 0x30;  // vibrant minority
                } else {
                    p[0] = 0x40; p[1] = 0x10; p[2] = 0x10;  // muted majority
                }
                p[3] = 0xFF;
            }
        }
        PaletteRoles roles;
        check(ex.Extract(img.data(), img.size(), w, h, w * 4, &roles) ==
                  PaletteError::kOk,
              "vibrant minority: extract ok");
        check(roles.dominant_vibrant == 0xFFFF3030u,
              "vibrant minority wins dominant role");
        CheckRoleContracts(roles, "vibrant minority");

        // Two saturated colors, 75/25 split: majority wins.
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                uint8_t* p = &img[(static_cast<size_t>(y) * w + x) * 4];
                if ((y * w + x) % 4 != 0) {
                    p[0] = 0xD0; p[1] = 0x30; p[2] = 0x30;  // 75%
                } else {
                    p[0] = 0x30; p[1] = 0x30; p[2] = 0xD0;  // 25%
                }
            }
        }
        check(ex.Extract(img.data(), img.size(), w, h, w * 4, &roles) ==
                  PaletteError::kOk,
              "majority: extract ok");
        check(roles.dominant_vibrant == 0xFFD03030u,
              "saturated majority wins dominant role");
        CheckRoleContracts(roles, "majority");
    }

    // ---- E/F. Determinism + stride/padding equivalence ------------------------
    {
        SimdPaletteExtractor ex;
        const int w = 67, h = 41;  // odd sizes on purpose
        std::vector<uint8_t> tight(static_cast<size_t>(w) * h * 4);
        const int pad = 12;  // keeps stride 4-aligned (contract)
        std::vector<uint8_t> padded(
            static_cast<size_t>(h) * (w * 4 + pad) + 7, 0xAB);
        Rng rng(77);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                uint8_t* t = &tight[(static_cast<size_t>(y) * w + x) * 4];
                t[0] = rng.byte8(); t[1] = rng.byte8(); t[2] = rng.byte8();
                t[3] = 0xFF;
                uint8_t* p =
                    padded.data() + static_cast<size_t>(y) * (w * 4 + pad) + x * 4;
                p[0] = t[0]; p[1] = t[1]; p[2] = t[2]; p[3] = 0xFF;
            }
        }
        PaletteRoles a, b, c;
        check(ex.Extract(tight.data(), tight.size(), w, h, w * 4, &a) ==
                  PaletteError::kOk,
              "determinism: first run");
        check(ex.Extract(tight.data(), tight.size(), w, h, w * 4, &b) ==
                  PaletteError::kOk,
              "determinism: second run");
        check(a.dominant_vibrant == b.dominant_vibrant &&
                  a.dark_muted == b.dark_muted &&
                  a.light_vibrant == b.light_vibrant &&
                  a.text_surface == b.text_surface,
              "determinism: bit-identical palettes");
        check(ex.Extract(padded.data(), padded.size(), w, h, w * 4 + pad, &c) ==
                  PaletteError::kOk,
              "stride: padded extract ok");
        check(a.dominant_vibrant == c.dominant_vibrant &&
                  a.dark_muted == c.dark_muted &&
                  a.light_vibrant == c.light_vibrant &&
                  a.text_surface == c.text_surface,
              "stride: padded == tight bit-identical");
        CheckRoleContracts(a, "random image");
    }

    // ---- G. SIMD vs scalar ingest bit-exactness --------------------------------
    {
        SimdPaletteExtractor ex;
        Rng rng(909);
        for (int t = 0; t < 60; ++t) {
            const int w = 1 + static_cast<int>(rng.next() % 131);
            const int h = 1 + static_cast<int>(rng.next() % 71);
            std::vector<uint8_t> img(static_cast<size_t>(w) * h * 4);
            for (auto& b : img) b = rng.byte8();
            check(ex.IngestScalar(img.data(), img.size(), w, h, w * 4) ==
                      PaletteError::kOk,
                  "crossval: scalar ingest ok");
            std::vector<uint32_t> pop(ex.histogram_populations(),
                                      ex.histogram_populations() + kHistogramBins);
            std::vector<uint32_t> sr(ex.histogram_sum_r(),
                                     ex.histogram_sum_r() + kHistogramBins);
            std::vector<uint32_t> sg(ex.histogram_sum_g(),
                                     ex.histogram_sum_g() + kHistogramBins);
            std::vector<uint32_t> sb(ex.histogram_sum_b(),
                                     ex.histogram_sum_b() + kHistogramBins);
            check(ex.IngestSimd(img.data(), img.size(), w, h, w * 4) ==
                      PaletteError::kOk,
                  "crossval: SIMD ingest ok");
            char buf[80];
            std::snprintf(buf, sizeof buf, "crossval %d: pop bit-exact", t);
            check(std::memcmp(pop.data(), ex.histogram_populations(),
                              kHistogramBins * sizeof(uint32_t)) == 0,
                  buf);
            std::snprintf(buf, sizeof buf, "crossval %d: sumR bit-exact", t);
            check(std::memcmp(sr.data(), ex.histogram_sum_r(),
                              kHistogramBins * sizeof(uint32_t)) == 0,
                  buf);
            std::snprintf(buf, sizeof buf, "crossval %d: sumG bit-exact", t);
            check(std::memcmp(sg.data(), ex.histogram_sum_g(),
                              kHistogramBins * sizeof(uint32_t)) == 0,
                  buf);
            std::snprintf(buf, sizeof buf, "crossval %d: sumB bit-exact", t);
            check(std::memcmp(sb.data(), ex.histogram_sum_b(),
                              kHistogramBins * sizeof(uint32_t)) == 0,
                  buf);
        }
        // Hostile byte patterns: 0x00 / 0xFF fill and alternating.
        {
            const int w = 128, h = 32;
            std::vector<uint8_t> img(static_cast<size_t>(w) * h * 4);
            for (size_t i = 0; i < img.size(); ++i) {
                img[i] = (i & 1) ? 0xFF : 0x00;
            }
            check(ex.IngestScalar(img.data(), img.size(), w, h, w * 4) ==
                      PaletteError::kOk,
                  "crossval: pattern scalar ok");
            std::vector<uint32_t> pop(ex.histogram_populations(),
                                      ex.histogram_populations() + kHistogramBins);
            check(ex.IngestSimd(img.data(), img.size(), w, h, w * 4) ==
                      PaletteError::kOk,
                  "crossval: pattern SIMD ok");
            check(std::memcmp(pop.data(), ex.histogram_populations(),
                              kHistogramBins * sizeof(uint32_t)) == 0,
                  "crossval: pattern bit-exact");
        }
    }

    // ---- H. Subsampling path ----------------------------------------------------
    {
        SimdPaletteExtractor ex;
        const int w = 1200, h = 900;  // 1.08M px > 65536 budget
        std::vector<uint8_t> big(static_cast<size_t>(w) * h * 4);
        for (size_t i = 0; i < static_cast<size_t>(w) * h; ++i) {
            big[i * 4] = 0x40; big[i * 4 + 1] = 0x80; big[i * 4 + 2] = 0xC0;
            big[i * 4 + 3] = 0xFF;
        }
        PaletteRoles r1, r2;
        check(ex.Extract(big.data(), big.size(), w, h, w * 4, &r1) ==
                  PaletteError::kOk,
              "subsample: extract ok");
        check(ex.last_sample_count() <= kPaletteMaxSamples + 16384,
              "subsample: budget respected");
        check(ex.last_sample_count() > kPaletteMaxSamples / 2,
              "subsample: budget substantially used");
        check(r1.dominant_vibrant == 0xFF4080C0u,
              "subsample: solid dominant exact");
        check(ex.Extract(big.data(), big.size(), w, h, w * 4, &r2) ==
                  PaletteError::kOk,
              "subsample: repeat ok");
        check(r1.dominant_vibrant == r2.dominant_vibrant &&
                  r1.dark_muted == r2.dark_muted &&
                  r1.light_vibrant == r2.light_vibrant &&
                  r1.text_surface == r2.text_surface,
              "subsample: deterministic");
        CheckRoleContracts(r1, "subsample");
    }

    // ---- I. Degenerate + validation ladder --------------------------------------
    {
        SimdPaletteExtractor ex;
        std::vector<uint8_t> one(4, 0x00);
        one[0] = 0x9C; one[1] = 0x6B; one[2] = 0x2F; one[3] = 0xFF;
        PaletteRoles roles;
        check(ex.Extract(one.data(), one.size(), 1, 1, 4, &roles) ==
                  PaletteError::kOk,
              "1x1: extract ok");
        check(roles.dominant_vibrant == 0xFF9C6B2Fu, "1x1: exact dominant");
        CheckRoleContracts(roles, "1x1");

        // Row/column extremes.
        std::vector<uint8_t> tall(4 * 512);
        for (int i = 0; i < 512; ++i) {
            tall[i * 4] = 0x11; tall[i * 4 + 1] = 0x22; tall[i * 4 + 2] = 0x33;
            tall[i * 4 + 3] = 0xFF;
        }
        check(ex.Extract(tall.data(), tall.size(), 1, 512, 4, &roles) ==
                  PaletteError::kOk,
              "1xN: extract ok");
        check(roles.dominant_vibrant == 0xFF112233u, "1xN: exact dominant");
        check(ex.Extract(tall.data(), tall.size(), 512, 1, 512 * 4, &roles) ==
                  PaletteError::kOk,
              "Nx1: extract ok");
        check(roles.dominant_vibrant == 0xFF112233u, "Nx1: exact dominant");

        // Validation ladder + hostile bounds.
        check(ex.Extract(nullptr, 100, 4, 4, 16, &roles) ==
                  PaletteError::kNullArgument,
              "validate: null pixels");
        check(ex.Extract(tall.data(), tall.size(), 4, 4, 16, nullptr) ==
                  PaletteError::kNullArgument,
              "validate: null out");
        check(ex.Extract(tall.data(), tall.size(), 0, 4, 16, &roles) ==
                  PaletteError::kBadDimensions,
              "validate: zero width");
        check(ex.Extract(tall.data(), tall.size(), 4, -1, 16, &roles) ==
                  PaletteError::kBadDimensions,
              "validate: negative height");
        check(ex.Extract(tall.data(), tall.size(), kPaletteMaxDim + 1, 4, 4,
                         &roles) == PaletteError::kBadDimensions,
              "validate: width > 16384");
        check(ex.Extract(tall.data(), tall.size(), 4, kPaletteMaxDim + 1, 16,
                         &roles) == PaletteError::kBadDimensions,
              "validate: height > 16384");
        check(ex.Extract(tall.data(), tall.size(), 4, 4, 12, &roles) ==
                  PaletteError::kBadStride,
              "validate: stride < 4*w");
        check(ex.Extract(tall.data(), tall.size(), 4, 4, 15, &roles) ==
                  PaletteError::kBadStride,
              "validate: unaligned stride");
        check(ex.Extract(tall.data(), 15, 4, 4, 16, &roles) ==
                  PaletteError::kBadBufferSize,
              "validate: short buffer (15 < 16)");
        check(ex.Extract(tall.data(), 75, 4, 4, 20, &roles) ==
                  PaletteError::kBadBufferSize,
              "validate: stride overruns buffer (needed 76 > 75)");
        check(ex.Extract(tall.data(), tall.size(), 4, 4, 1000, &roles) ==
                  PaletteError::kBadBufferSize,
              "validate: huge stride overruns buffer");
        // The ingest hooks enforce the same bounds.
        check(ex.IngestScalar(nullptr, 100, 4, 4, 16) ==
                  PaletteError::kNullArgument,
              "hook: scalar null");
        check(ex.IngestSimd(tall.data(), 15, 4, 4, 16) ==
                  PaletteError::kBadBufferSize,
              "hook: simd short buffer");
        {
            // 512 x 513 = 262,656 px > the 2^18 full-image hook cap; the
            // buffer itself must be valid so the CAP is what trips.
            const int hw = 512, hh = 513;
            std::vector<uint8_t> big(static_cast<size_t>(hw) * hh * 4, 0);
            check(ex.IngestScalar(big.data(), big.size(), hw, hh, hw * 4) ==
                      PaletteError::kBadDimensions,
                  "hook: full-image size cap");
            check(ex.IngestSimd(big.data(), big.size(), hw, hh, hw * 4) ==
                      PaletteError::kBadDimensions,
                  "hook: simd full-image size cap");
        }
    }

    // ---- J. Zero-allocation audit (warmed hot path) ------------------------------
    {
        SimdPaletteExtractor ex;
        const int w = 96, h = 96;
        std::vector<uint8_t> img(static_cast<size_t>(w) * h * 4);
        Rng rng(31337);
        for (auto& b : img) b = rng.byte8();
        PaletteRoles roles;
        // Warm-up (first call may touch thread_local/allocator internals).
        ex.Extract(img.data(), img.size(), w, h, w * 4, &roles);
        {
            streamify_test::AllocGuard guard;
            const PaletteError e =
                ex.Extract(img.data(), img.size(), w, h, w * 4, &roles);
            check(e == PaletteError::kOk, "allocguard: extract ok");
            check(guard.count() == 0, "allocguard: zero allocations on Extract");
        }
        // Free-function path (thread_local extractor, warmed).
        ExtractPaletteSimd(img.data(), img.size(), w, h, w * 4, &roles);
        {
            streamify_test::AllocGuard guard;
            const PaletteError e =
                ExtractPaletteSimd(img.data(), img.size(), w, h, w * 4, &roles);
            check(e == PaletteError::kOk, "allocguard: free fn ok");
            check(guard.count() == 0,
                  "allocguard: zero allocations on thread_local path");
        }
    }

    // ---- K. Performance smoke -----------------------------------------------------
    {
        SimdPaletteExtractor ex;
        const int w = 1080, h = 1080;
        std::vector<uint8_t> big(static_cast<size_t>(w) * h * 4);
        Rng rng(555);
        for (size_t i = 0; i < static_cast<size_t>(w) * h; ++i) {
            big[i * 4] = rng.byte8();        // full-noise worst case
            big[i * 4 + 1] = rng.byte8();
            big[i * 4 + 2] = rng.byte8();
            big[i * 4 + 3] = 0xFF;
        }
        PaletteRoles roles;
        ex.Extract(big.data(), big.size(), w, h, w * 4, &roles);  // warm
        double best_ms = 1e9;
        for (int r = 0; r < 3; ++r) {
            const auto t0 = std::chrono::steady_clock::now();
            ex.Extract(big.data(), big.size(), w, h, w * 4, &roles);
            const auto t1 = std::chrono::steady_clock::now();
            best_ms = std::min(
                best_ms,
                std::chrono::duration<double, std::milli>(t1 - t0).count());
        }
        std::printf("    perf: 1080x1080 worst-case (full noise) best-of-3: %.3f ms\n",
                    best_ms);
        // CI-safe bound (directive target is < 1 ms; sanitizers and shared
        // runners get 10x slack, the logged number carries the real value).
        check(best_ms < 10.0, "perf: 1080x1080 within CI-safe budget");
    }

    std::printf("  [Phase-5] palette suite: %d checks passed, %d failed\n",
                g_passed, g_failed);
    return g_failed == 0 ? 0 : 1;
}
