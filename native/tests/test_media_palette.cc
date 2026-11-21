// ============================================================================
//  test_media_palette.cc — Phase 3 verification: SIMD bitmap palette
//  extraction for custom covers (BEHIND.md #57)
// ============================================================================
//
//  Covers the Phase-3 directive deliverable 3:
//    A. Role correctness — primary/secondary/text/ambient on synthetic art
//       (solid, split, gradient, photo-like noise).
//    B. WCAG text contrast — black/white pick + independent recomputation
//       + the theoretical floor.
//    C. Formats & degenerate sizes — RGBA8888 / ARGB8888 / RGB565, 1x1,
//       tiny strips, the malformed-input ladder.
//    D. Determinism — bit-identical results across instances and runs.
//    E. Zero-allocation hot path + performance smoke (report-only).
//
//  Linked into dsp_test_suite (native-dsp CI shard); entry point
//  run_media_palette_tests() is called from test_dsp.cc's main().
// ============================================================================

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "../palette/BitmapPalette.h"

#include "AllocGuard.h"

using streamify::palette::BitmapPaletteExtractor;
using streamify::palette::PaletteResult;
using streamify::palette::PaletteStatus;
using streamify::palette::PixelFormat;
using streamify::palette::relativeLuminance;

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
};

std::vector<uint8_t> solidRgba(int w, int h, uint8_t r, uint8_t g, uint8_t b) {
    std::vector<uint8_t> p(static_cast<size_t>(w) * static_cast<size_t>(h) * 4);
    for (size_t i = 0; i < p.size(); i += 4) {
        p[i] = r;
        p[i + 1] = g;
        p[i + 2] = b;
        p[i + 3] = 255;
    }
    return p;
}

}  // namespace

int run_media_palette_tests() {
    std::printf("[phase3] bitmap palette extractor\n");

    // ------------------------------------------------------------------
    // A. Role correctness
    // ------------------------------------------------------------------
    std::printf("  [palette] role colors on synthetic art\n");
    {
        BitmapPaletteExtractor ex;
        // Solid saturated red: primary == the art color, white text (WCAG:
        // saturated red L~0.157 -> white 5.06 vs black 4.15), dark red glow.
        auto red = solidRgba(64, 64, 220, 20, 30);
        PaletteResult r;
        check(ex.extract(red.data(), red.size(), 64, 64,
                         PixelFormat::kRgba8888, &r) == PaletteStatus::kOk,
              "solid red extracts");
        check(r.primaryArgb == 0xFFDC141E, "solid red primary exact");
        check(r.textForegroundArgb == 0xFFFFFFFF, "red art -> white text");
        check(r.foregroundContrastRatio > 5.0f, "red/white contrast > 5.0");
        const uint32_t amb = r.ambientGlowArgb;
        check((amb >> 24) == 0xFF, "ambient alpha");
        check(((amb >> 16) & 0xFF) > 40 &&
                  ((amb >> 16) & 0xFF) > ((amb >> 8) & 0xFF),
              "ambient is a red-dominant glow");
        check(relativeLuminance(amb & 0xFFFFFF) <
                  relativeLuminance(0xDC141E),
              "ambient darker than primary");
        check(r.swatchCount == 1 && r.dominantPopulation == 64 * 64,
              "solid art single swatch, full population");

        // Split blue | orange: the two roles must carry both hues.
        const int w = 128, h = 64;
        std::vector<uint8_t> split(static_cast<size_t>(w) * h * 4);
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                const size_t i = (static_cast<size_t>(y) * w + x) * 4;
                if (x < w / 2) {
                    split[i] = 30; split[i + 1] = 60; split[i + 2] = 230;
                } else {
                    split[i] = 240; split[i + 1] = 140; split[i + 2] = 20;
                }
                split[i + 3] = 255;
            }
        }
        check(ex.extract(split.data(), split.size(), w, h,
                         PixelFormat::kRgba8888, &r) == PaletteStatus::kOk,
              "split art extracts");
        const uint32_t p = r.primaryArgb & 0xFFFFFF;
        const uint32_t s = r.secondaryArgb & 0xFFFFFF;
        check(p != s, "primary != secondary");
        const bool blueP = ((p >> 16) < 100) && ((p & 0xFF) > 180);
        const bool orangeS =
            ((s >> 16) > 180) && ((s >> 8) > 90) && ((s & 0xFF) < 100);
        const bool orangeP =
            ((p >> 16) > 180) && ((p >> 8) > 90) && ((p & 0xFF) < 100);
        const bool blueS = ((s >> 16) < 100) && ((s & 0xFF) > 180);
        check((blueP && orangeS) || (orangeP && blueS),
              "split art roles carry both hues");
        check(r.swatchCount >= 2, "split art at least two swatches");

        // Dark desaturated art (charcoal): text must flip to white and the
        // roles must stay finite/valid.
        auto charcoal = solidRgba(48, 52, 40, 40, 44);
        check(ex.extract(charcoal.data(), charcoal.size(), 48, 52,
                         PixelFormat::kRgba8888, &r) == PaletteStatus::kOk,
              "charcoal art extracts");
        check(r.textForegroundArgb == 0xFFFFFFFF, "dark art -> white text");
        check(r.foregroundContrastRatio > 4.5f, "dark art contrast >= 4.5");
    }

    // ------------------------------------------------------------------
    // B. WCAG math
    // ------------------------------------------------------------------
    std::printf("  [palette] WCAG contrast discipline\n");
    {
        BitmapPaletteExtractor ex;
        // Worst case for black/white selection: mid gray. The pick must
        // still be >= 4.5 (theoretical minimum of max(white,black) is
        // ~4.58 at L ~= 0.179).
        auto gray = solidRgba(32, 32, 128, 128, 128);
        PaletteResult r;
        check(ex.extract(gray.data(), gray.size(), 32, 32,
                         PixelFormat::kRgba8888, &r) == PaletteStatus::kOk,
              "mid gray extracts");
        check(r.foregroundContrastRatio >= 4.5f, "mid-gray contrast >= 4.5");
        const float indep = streamify::palette::contrastRatio(
            r.primaryArgb & 0xFFFFFF, r.textForegroundArgb & 0xFFFFFF);
        check(std::fabs(indep - r.foregroundContrastRatio) < 1e-4f,
              "reported ratio matches independent recomputation");
        // Known WCAG anchors.
        check(std::fabs(streamify::palette::contrastRatio(0xFFFFFF, 0x000000) -
                        21.0f) < 0.01f,
              "white/black ratio 21:1");
        check(std::fabs(streamify::palette::contrastRatio(0xFFFFFF, 0xFFFFFF) -
                        1.0f) < 1e-6f,
              "identical colors ratio 1:1");
    }

    // ------------------------------------------------------------------
    // C. Formats & degenerate sizes
    // ------------------------------------------------------------------
    std::printf("  [palette] formats, sizes, malformed ladder\n");
    {
        BitmapPaletteExtractor ex;
        PaletteResult r;
        // 1x1 exact colors in all three formats.
        const uint8_t rgba[4] = {10, 200, 90, 255};
        check(ex.extract(rgba, 4, 1, 1, PixelFormat::kRgba8888, &r) ==
                  PaletteStatus::kOk,
              "1x1 rgba extracts");
        check(r.primaryArgb == 0xFF0AC85A, "1x1 rgba color exact");
        const uint8_t argb[4] = {255, 10, 200, 90};
        check(ex.extract(argb, 4, 1, 1, PixelFormat::kArgb8888, &r) ==
                  PaletteStatus::kOk,
              "1x1 argb extracts");
        check(r.primaryArgb == 0xFF0AC85A, "1x1 argb color exact (A,R,G,B)");
        std::vector<uint8_t> p565(2 * 256, 0);
        for (size_t i = 0; i < 256; ++i) {
            p565[i * 2] = 0x00;
            p565[i * 2 + 1] = 0xF8;  // pure red 565
        }
        check(ex.extract(p565.data(), p565.size(), 16, 16, PixelFormat::kRgb565,
                         &r) == PaletteStatus::kOk,
              "565 extracts");
        check(r.primaryArgb == 0xFFFF0000, "565 bit-replication exact");
        // Thin strip 1000x1.
        auto strip = solidRgba(1000, 1, 0, 128, 255);
        check(ex.extract(strip.data(), strip.size(), 1000, 1,
                         PixelFormat::kRgba8888, &r) == PaletteStatus::kOk,
              "1000x1 strip extracts");
        check(r.primaryArgb == 0xFF0080FF, "strip color exact");
        // Malformed ladder.
        std::vector<uint8_t> small(100);
        check(ex.extract(nullptr, 0, 8, 8, PixelFormat::kRgba8888, &r) ==
                  PaletteStatus::kInvalidArgument,
              "null pixels rejected");
        check(ex.extract(small.data(), small.size(), 64, 64,
                         PixelFormat::kRgba8888, &r) ==
                  PaletteStatus::kBadBufferSize,
              "short buffer rejected");
        check(ex.extract(small.data(), small.size(), 0, 10,
                         PixelFormat::kRgba8888, &r) ==
                  PaletteStatus::kBadDimensions,
              "zero width rejected");
        check(ex.extract(small.data(), small.size(), 10, -1,
                         PixelFormat::kRgba8888, &r) ==
                  PaletteStatus::kBadDimensions,
              "negative height rejected");
        check(ex.extract(small.data(), 64 * 64 * 4, 8, 8,
                         static_cast<PixelFormat>(99), &r) ==
                  PaletteStatus::kInvalidArgument,
              "unknown format rejected");
        check(ex.extract(nullptr, 0, 0, 0, PixelFormat::kRgba8888, nullptr) ==
                  PaletteStatus::kInvalidArgument,
              "null result rejected");
        // Oversized total pixel count (dimensions themselves within the
        // 32768 per-side cap, so the pixel-count guard is what fires).
        check(ex.extract(small.data(), small.size(), 20000, 20000,
                         PixelFormat::kRgba8888, &r) ==
                  PaletteStatus::kTooManyPixels,
              "oversized bitmap rejected");
        // bytesPerPixel helper.
        check(BitmapPaletteExtractor::bytesPerPixel(PixelFormat::kRgba8888) ==
                  4 &&
                  BitmapPaletteExtractor::bytesPerPixel(PixelFormat::kArgb8888) ==
                      4 &&
                  BitmapPaletteExtractor::bytesPerPixel(PixelFormat::kRgb565) ==
                      2,
              "bytes per pixel table");
    }

    // ------------------------------------------------------------------
    // D. Determinism
    // ------------------------------------------------------------------
    std::printf("  [palette] determinism\n");
    {
        // Photo-like noise: same bytes -> identical PaletteResult bits,
        // across fresh extractor instances and repeated runs.
        std::vector<uint8_t> px(static_cast<size_t>(200) * 150 * 4);
        Rng rng(88172645463325252ull);
        for (size_t i = 0; i < px.size(); i += 4) {
            px[i] = static_cast<uint8_t>(rng.next() >> 16);
            px[i + 1] = static_cast<uint8_t>(rng.next() >> 24);
            px[i + 2] = static_cast<uint8_t>(rng.next() >> 8);
            px[i + 3] = 255;
        }
        PaletteResult a, b, c;
        BitmapPaletteExtractor e1, e2;
        check(e1.extract(px.data(), px.size(), 200, 150,
                         PixelFormat::kRgba8888, &a) == PaletteStatus::kOk,
              "noise extract 1");
        check(e2.extract(px.data(), px.size(), 200, 150,
                         PixelFormat::kRgba8888, &b) == PaletteStatus::kOk,
              "noise extract 2");
        check(e1.extract(px.data(), px.size(), 200, 150,
                         PixelFormat::kRgba8888, &c) == PaletteStatus::kOk,
              "noise extract 3 (reuse)");
        check(std::memcmp(&a, &b, sizeof(a)) == 0,
              "bit-identical across instances");
        check(std::memcmp(&a, &c, sizeof(a)) == 0,
              "bit-identical on instance reuse");
        // A one-byte change must stay deterministic too (no UB, no drift).
        px[7] ^= 0x55;
        PaletteResult d;
        check(e1.extract(px.data(), px.size(), 200, 150,
                         PixelFormat::kRgba8888, &d) == PaletteStatus::kOk,
              "perturbed extract");
        PaletteResult d2;
        check(e2.extract(px.data(), px.size(), 200, 150,
                         PixelFormat::kRgba8888, &d2) == PaletteStatus::kOk,
              "perturbed extract 2");
        check(std::memcmp(&d, &d2, sizeof(d)) == 0,
              "perturbed result also deterministic");
    }

    // ------------------------------------------------------------------
    // E. Zero allocation + perf smoke
    // ------------------------------------------------------------------
    std::printf("  [palette] zero-alloc hot path + perf smoke\n");
    {
        std::vector<uint8_t> px(static_cast<size_t>(256) * 256 * 4);
        Rng rng(0xC0FFEE);
        for (auto& b : px) b = static_cast<uint8_t>(rng.next());
        for (size_t i = 3; i < px.size(); i += 4) px[i] = 255;
        BitmapPaletteExtractor ex;
        PaletteResult r;
        // Warm-up (first call only initializes member scratch).
        (void)ex.extract(px.data(), px.size(), 256, 256,
                         PixelFormat::kRgba8888, &r);
        {
            streamify_test::AllocGuard guard;
            const PaletteStatus st = ex.extract(px.data(), px.size(), 256, 256,
                                                PixelFormat::kRgba8888, &r);
            check(st == PaletteStatus::kOk && guard.count() == 0,
                  "extract(): zero allocations");
        }
        // Perf smoke (report-only — CI runners vary).
        std::vector<uint8_t> big(static_cast<size_t>(1024) * 1024 * 4);
        Rng rng2(0xFEED);
        for (auto& b : big) b = static_cast<uint8_t>(rng2.next());
        BitmapPaletteExtractor perf;
        PaletteResult pr;
        (void)perf.extract(big.data(), big.size(), 1024, 1024,
                           PixelFormat::kRgba8888, &pr);
        const auto t0 = std::chrono::steady_clock::now();
        const int reps = 10;
        for (int i = 0; i < reps; ++i) {
            (void)perf.extract(big.data(), big.size(), 1024, 1024,
                               PixelFormat::kRgba8888, &pr);
        }
        const auto t1 = std::chrono::steady_clock::now();
        const double ms =
            std::chrono::duration<double, std::milli>(t1 - t0).count() / reps;
        std::printf("    perf: 1024x1024 RGBA in %.2f ms\n", ms);
    }

    std::printf("  [palette] fuzz-regression: median-cut termination\n");
    {
        // Regression (soak trap, section 13): an RGB565 noise image whose
        // majority mass sits at the TOP coordinate (G nibble = 15) of the
        // max-variance axis made the weighted median land on splitAfter=15;
        // the right-side guard `while (splitAfter < 15)` could not walk
        // DOWN from 15, so the split degenerated and the retry loop spun
        // forever. The unit-level essence of the failing input: 46 pixels
        // at one high-G bin + a dust of lower-G singletons.
        std::vector<uint16_t> px(10 * 16);
        for (size_t i = 0; i < px.size(); ++i) {
            px[i] = 0x7FF0;  // R=15, G=31->nibble 15, B=0..15-ish
        }
        // Dust: five distinct lower-G colors.
        px[3] = 0xFC08;
        px[7] = 0xCB10;
        px[11] = 0xC708;
        px[15] = 0x8D08;
        px[19] = 0xB708;
        std::vector<uint8_t> bytes(px.size() * 2);
        std::memcpy(bytes.data(), px.data(), bytes.size());
        BitmapPaletteExtractor ex;
        PaletteResult pr;
        const auto st = ex.extract(bytes.data(), bytes.size(), 10, 16,
                                   PixelFormat::kRgb565, &pr);
        check(st == PaletteStatus::kOk,
              "median-cut: majority-at-top-G image extracts");
        check((pr.primaryArgb >> 24) == 0xFF &&
                  pr.swatchCount >= 1 && pr.swatchCount <= 24,
              "median-cut: degenerate-median image yields valid swatches");
        // Determinism twin on the same input.
        BitmapPaletteExtractor ex2;
        PaletteResult pr2;
        check(ex2.extract(bytes.data(), bytes.size(), 10, 16,
                          PixelFormat::kRgb565, &pr2) == PaletteStatus::kOk &&
                  std::memcmp(&pr, &pr2, sizeof(pr)) == 0,
              "median-cut: degenerate-median extraction deterministic");
    }

    std::printf("[phase3] palette: %d passed, %d failed\n", g_passed,
                g_failed);
    return g_failed == 0 ? 0 : 1;
}
