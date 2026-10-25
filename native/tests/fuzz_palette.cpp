// ============================================================================
//  fuzz_palette.cpp — Phase-5 LibFuzzer harness: arbitrary image bitstreams
//  and corrupted frame histograms (native/tests/fuzz_palette.cpp)
// ============================================================================
//
//  Phase-5 directive deliverable 5. Feeds fully hostile inputs into:
//    1. SimdPaletteExtractor::Extract — corrupt image bytes, zero/negative/
//       oversized dimensions, strides below width*4, unaligned strides,
//       strides that overrun the buffer, truncated buffers. Every rejection
//       path must stay in-bounds (any out-of-range access trips ASan); every
//       ACCEPTED input must satisfy the full role contract: alpha 0xFF,
//       text surface >= 4.5:1 WCAG ratio vs dominant, pairwise role
//       distinctness, dark-muted luminance <= 0.5, light-vibrant value
//       >= 0.78, and bit-identical determinism on re-extraction.
//    2. IngestScalar / IngestSimd hooks — the same hostile argument space
//       (bounds must be enforced identically on both paths).
//    3. WCAG contrast — arbitrary color pairs: ratio in [1, 21], symmetric,
//       matching a double-precision reference, MeetsContrast threshold
//       consistency (NaN thresholds always rejected).
//    4. FramePacerMonitor — the input bytes as a stream of (duration,
//       vsync offset) pairs (corrupted frame histograms): arbitrary u64
//       durations (0, huge, negative-jlong-wrapped) and full-range i64
//       offsets (incl. INT64_MIN). Exact-count invariants: total/janky/
//       misaligned match a locally computed reference, window <= 512,
//       percentile monotonicity, counter-group consistency, finite FPS.
//       Then Reset() (zeroed) and the 192-byte DirectByteBuffer wire
//       serializer with arbitrary capacities (undersized buffers must be
//       rejected AND left untouched).
//
//  Invariant violations call __builtin_trap() which libFuzzer reports as a
//  crash. Build modes:
//    * clang + -fsanitize=fuzzer  -> classic LibFuzzer target.
//    * -DSTREAMIFY_FUZZ_COMPOSED -> no LLVMFuzzerTestOneInput entry of its
//      own; the exported StreamifyFuzzPaletteFrame() is composed into the
//      shared CI harness fuzz/dsp_fuzzer_harness.cc (native-deep-fuzz
//      shard) so these cases run in the real LibFuzzer campaigns.
//    * -DSTREAMIFY_FUZZ_STANDALONE -> self-contained deterministic mutator
//      driver (runs under plain ASan/UBSan with any compiler; used for
//      local soak runs and sanitizer-only environments).
// ============================================================================

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <limits>

#include "../include/frame_pacer_monitor.h"
#include "../include/palette_extractor_simd.h"
#include "../include/streamify_frame_palette.h"

using streamify::pacer::FramePacerMonitor;
using streamify::pacer::FramePacerStats;
using streamify::pacer::kFrameBudget120HzNs;
using streamify::pacer::kFrameBudget60HzNs;
using streamify::pacer::kFramePacerStatsWireBytes;
using streamify::pacer::kFrameRingSlots;
using streamify::pacer::kVsyncMisalignToleranceNs;
using streamify::pacer::WriteFramePacerStatsBuffer;
using streamify::palette_simd::ContrastRatio;
using streamify::palette_simd::ExtractPaletteSimd;
using streamify::palette_simd::MeetsContrast;
using streamify::palette_simd::PaletteError;
using streamify::palette_simd::PaletteRoles;
using streamify::palette_simd::RelativeLuminance;
using streamify::palette_simd::SimdPaletteExtractor;

// LibFuzzer abort()s are reported as crashes; use this for invariants.
static inline void fuzz_check(bool ok) {
    if (!ok) __builtin_trap();
}

// ---- 1 + 2: palette extraction on arbitrary bitstreams -----------------------

static void FuzzExtract(const uint8_t* data, size_t size) {
    if (size < 8) return;
    // Dimension/stride space derived from the header: covers zero, negative
    // (via i32 interpretation), huge, and in-range values.
    const int32_t w = static_cast<int32_t>(
        static_cast<uint32_t>(data[0]) | (static_cast<uint32_t>(data[1]) << 8) |
        (static_cast<uint32_t>(data[2]) << 16));
    const int32_t h = static_cast<int32_t>(
        static_cast<uint32_t>(data[3]) | (static_cast<uint32_t>(data[4]) << 8) |
        (static_cast<uint32_t>(data[5]) << 16));
    const int32_t stride = static_cast<int32_t>(
        static_cast<uint32_t>(data[6]) | (static_cast<uint32_t>(data[7]) << 8) |
        (static_cast<uint32_t>(data[6]) << 24));  // overlaps deliberately

    const uint8_t* pixels = data + 8;
    const size_t pix_len = size - 8;

    PaletteRoles roles;
    const PaletteError e =
        ExtractPaletteSimd(pixels, pix_len, w, h, stride, &roles);
    if (e != PaletteError::kOk) {
        // Rejected inputs must be reproducibly rejected.
        PaletteRoles again;
        fuzz_check(ExtractPaletteSimd(pixels, pix_len, w, h, stride, &again) ==
                   e);
        return;
    }

    // ACCEPTED input: the full role contract holds for ANY byte pattern.
    fuzz_check((roles.dominant_vibrant >> 24) == 0xFF);
    fuzz_check((roles.dark_muted >> 24) == 0xFF);
    fuzz_check((roles.light_vibrant >> 24) == 0xFF);
    fuzz_check((roles.text_surface >> 24) == 0xFF);
    fuzz_check(ContrastRatio(roles.text_surface, roles.dominant_vibrant) >=
               4.5f);
    fuzz_check(roles.dominant_vibrant != roles.dark_muted);
    fuzz_check(roles.dominant_vibrant != roles.light_vibrant);
    fuzz_check(roles.dark_muted != roles.light_vibrant);
    fuzz_check(RelativeLuminance(roles.dark_muted) <= 0.5f);
    const uint32_t lv = roles.light_vibrant;
    const int lv_max = std::max({static_cast<int>((lv >> 16) & 0xFF),
                                 static_cast<int>((lv >> 8) & 0xFF),
                                 static_cast<int>(lv & 0xFF)});
    fuzz_check(lv_max >= 199);  // V >= 0.78 by construction

    // Determinism: same bytes -> same palette, bit for bit.
    PaletteRoles replay;
    fuzz_check(ExtractPaletteSimd(pixels, pix_len, w, h, stride, &replay) ==
               PaletteError::kOk);
    fuzz_check(std::memcmp(&roles, &replay, sizeof(roles)) == 0);

    // The ingest hooks must accept exactly the same argument space.
    SimdPaletteExtractor ex;
    fuzz_check(ex.IngestScalar(pixels, pix_len, w, h, stride) ==
               PaletteError::kOk);
    fuzz_check(ex.IngestSimd(pixels, pix_len, w, h, stride) ==
               PaletteError::kOk);
    // ...and the scalar/SIMD paths agree bit-for-bit on the histogram.
    SimdPaletteExtractor ex2;
    fuzz_check(ex2.IngestScalar(pixels, pix_len, w, h, stride) ==
               PaletteError::kOk);
    fuzz_check(std::memcmp(ex.histogram_populations(),
                           ex2.histogram_populations(),
                           streamify::palette_simd::kHistogramBins *
                               sizeof(uint32_t)) == 0);
}

// ---- 3: WCAG contrast on arbitrary color pairs --------------------------------

static void FuzzContrast(const uint8_t* data, size_t size) {
    if (size < 8) return;
    const uint32_t a = static_cast<uint32_t>(data[0]) |
                       (static_cast<uint32_t>(data[1]) << 8) |
                       (static_cast<uint32_t>(data[2]) << 16) |
                       (static_cast<uint32_t>(data[3]) << 24);
    const uint32_t b = static_cast<uint32_t>(data[4]) |
                       (static_cast<uint32_t>(data[5]) << 8) |
                       (static_cast<uint32_t>(data[6]) << 16) |
                       (static_cast<uint32_t>(data[7]) << 24);
    const uint32_t a_argb = 0xFF000000u | (a & 0xFFFFFFu);
    const uint32_t b_argb = 0xFF000000u | (b & 0xFFFFFFu);

    const float r1 = ContrastRatio(a_argb, b_argb);
    const float r2 = ContrastRatio(b_argb, a_argb);
    fuzz_check(r1 >= 0.999f && r1 <= 21.001f);
    fuzz_check(std::fabs(r1 - r2) < 1e-4f);

    // Double-precision reference (WCAG formulas) agrees closely.
    auto lin = [](uint32_t c8) {
        const double c = static_cast<double>(c8) / 255.0;
        return c <= 0.04045 ? c / 12.92
                            : std::pow((c + 0.055) / 1.055, 2.4);
    };
    const uint32_t ar = (a_argb >> 16) & 0xFF, ag = (a_argb >> 8) & 0xFF,
                   ab = a_argb & 0xFF;
    const uint32_t br = (b_argb >> 16) & 0xFF, bg = (b_argb >> 8) & 0xFF,
                   bb = b_argb & 0xFF;
    const double la =
        0.2126 * lin(ar) + 0.7152 * lin(ag) + 0.0722 * lin(ab);
    const double lb =
        0.2126 * lin(br) + 0.7152 * lin(bg) + 0.0722 * lin(bb);
    const double hi = la > lb ? la : lb;
    const double lo = la > lb ? lb : la;
    const double ref = (hi + 0.05) / (lo + 0.05);
    fuzz_check(std::fabs(static_cast<double>(r1) - ref) < 1e-3);

    // Threshold semantics: a passing threshold implies the ratio meets it.
    if (r1 >= 4.5f) fuzz_check(MeetsContrast(a_argb, b_argb, 4.5f));
    if (r1 < 4.5f) fuzz_check(!MeetsContrast(a_argb, b_argb, 4.5f));
    // NaN thresholds always rejected (fast-math-proof bit inspection).
    fuzz_check(!MeetsContrast(a_argb, b_argb,
                              std::numeric_limits<float>::quiet_NaN()));
}

// ---- 4: corrupted frame histograms through the pacer ---------------------------

static void FuzzFramePacer(const uint8_t* data, size_t size) {
    // Interpret the input as a stream of (u64 duration, i64 offset) pairs;
    // a truncated tail is simply a shorter stream (part of the fuzz space).
    const size_t pair_bytes = 16;
    const size_t pairs = size / pair_bytes;
    if (pairs == 0) return;
    const size_t use_pairs = pairs > 2048 ? 2048 : pairs;

    FramePacerMonitor m;
    uint64_t expect_total = 0, expect_j120 = 0, expect_j60 = 0,
             expect_mis = 0, expect_invalid = 0;
    uint64_t last_durs[512];
    size_t last_n = 0;
    for (size_t i = 0; i < use_pairs; ++i) {
        uint64_t d = 0;
        int64_t off = 0;
        std::memcpy(&d, data + i * pair_bytes, 8);
        std::memcpy(&off, data + i * pair_bytes + 8, 8);
        m.RecordFrame(d, off);
        if (d == 0 || d > streamify::pacer::kFrameMaxPlausibleNs) {
            ++expect_invalid;
            continue;
        }
        ++expect_total;
        if (d > kFrameBudget120HzNs) ++expect_j120;
        if (d > kFrameBudget60HzNs) ++expect_j60;
        const uint64_t a = off >= 0
            ? static_cast<uint64_t>(off)
            : ~static_cast<uint64_t>(off) + 1ull;  // |i64| as u64 (no __int128)
        if (a > static_cast<uint64_t>(kVsyncMisalignToleranceNs)) ++expect_mis;
        last_durs[last_n++ % kFrameRingSlots] = d;  // rolling last-512
    }

    FramePacerStats st;
    fuzz_check(m.GetStats(&st));
    fuzz_check(st.total_frames == expect_total);
    fuzz_check(st.janky_frames_120 == expect_j120);
    fuzz_check(st.janky_frames_60 == expect_j60);
    fuzz_check(st.vsync_misaligned == expect_mis);
    fuzz_check(st.invalid_samples == expect_invalid);
    fuzz_check(st.window_frames <= kFrameRingSlots);
    fuzz_check(st.janky_frames_120 <= st.total_frames);
    fuzz_check(st.janky_frames_60 <= st.janky_frames_120);
    fuzz_check(st.window_p50_ns <= st.window_p90_ns);
    fuzz_check(st.window_p90_ns <= st.window_p95_ns);
    fuzz_check(st.window_p95_ns <= st.window_p99_ns);
    fuzz_check(st.window_p99_ns <= st.window_max_ns);
    fuzz_check(st.window_mean_ns <= st.window_max_ns);
    fuzz_check(!std::isnan(st.estimated_fps) && st.estimated_fps >= 0.0);
    // Full window: max must equal the max of the last min(512, total) frames.
    if (expect_total >= kFrameRingSlots) {
        uint64_t mx = 0;
        for (size_t i = 0; i < kFrameRingSlots; ++i) {
            if (last_durs[i] > mx) mx = last_durs[i];
        }
        fuzz_check(st.window_max_ns == mx);
    }

    // Wire serializer with a capacity from the fuzz bytes: exact accept
    // semantics and untouched-buffer rejection.
    uint8_t wire[kFramePacerStatsWireBytes];
    std::memset(wire, 0xEE, sizeof(wire));
    const size_t cap_probe = (size_t)data[0];  // 0..255, covers <192 and >=192
    const bool wrote = WriteFramePacerStatsBuffer(m, wire, cap_probe);
    fuzz_check(wrote == (cap_probe >= kFramePacerStatsWireBytes));
    if (!wrote) {
        for (uint8_t v : wire) fuzz_check(v == 0xEE);
    }

    // Reset: everything zero, sequence survives, monitor still usable.
    m.Reset();
    fuzz_check(m.GetStats(&st));
    fuzz_check(st.total_frames == 0 && st.window_frames == 0 &&
               st.janky_frames_120 == 0 && st.invalid_samples == 0);
    m.RecordFrame(5000000, 0);
    fuzz_check(m.GetStats(&st) && st.total_frames == 1);
}

namespace streamify {
// Phase-5 fuzz entry, composed into the shared CI harness
// (fuzz/dsp_fuzzer_harness.cc) and reused verbatim by the standalone target
// and the dedicated frame_palette_fuzz_harness.
int StreamifyFuzzPaletteFrame(const uint8_t* data, size_t size) {
    FuzzExtract(data, size);
    FuzzContrast(data, size);
    FuzzFramePacer(data, size);
    return 0;
}
}  // namespace streamify

#ifndef STREAMIFY_FUZZ_COMPOSED
extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    return streamify::StreamifyFuzzPaletteFrame(data, size);
}
#endif  // STREAMIFY_FUZZ_COMPOSED

#ifdef STREAMIFY_FUZZ_STANDALONE

#include <cstdio>
#include <cstdlib>
#include <vector>

namespace {

struct StandaloneRng {
    uint64_t s;
    explicit StandaloneRng(uint64_t seed) : s(seed ? seed : 0x853C49E6748FEA9Bull) {}
    uint64_t next() {
        s ^= s << 13;
        s ^= s >> 7;
        s ^= s << 17;
        return s;
    }
    uint32_t below(uint32_t n) { return static_cast<uint32_t>(next() % n); }
};

// Valid inputs for the mutator to chew on: a small photo-like image (header
// + pixels), a solid image, and a frame histogram with plausible durations.
std::vector<std::vector<uint8_t>> MakeSeeds() {
    std::vector<std::vector<uint8_t>> seeds;

    {  // 64x48 gradient, tight stride.
        const int w = 64, h = 48;
        std::vector<uint8_t> img(8 + static_cast<size_t>(w) * h * 4);
        img[0] = w & 0xFF; img[1] = (w >> 8) & 0xFF; img[2] = (w >> 16) & 0xFF;
        img[3] = h & 0xFF; img[4] = (h >> 8) & 0xFF; img[5] = (h >> 16) & 0xFF;
        img[6] = (w * 4) & 0xFF; img[7] = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                uint8_t* p = img.data() + 8 + (static_cast<size_t>(y) * w + x) * 4;
                p[0] = static_cast<uint8_t>(x * 4);
                p[1] = static_cast<uint8_t>(y * 5);
                p[2] = static_cast<uint8_t>((x + y) * 2);
                p[3] = 0xFF;
            }
        }
        seeds.push_back(std::move(img));
    }
    {  // 16x16 solid #CC3366.
        const int w = 16, h = 16;
        std::vector<uint8_t> img(8 + static_cast<size_t>(w) * h * 4);
        img[0] = w & 0xFF; img[3] = h & 0xFF;
        img[6] = (w * 4) & 0xFF; img[7] = 0;
        for (size_t i = 8; i < img.size(); i += 4) {
            img[i] = 0xCC; img[i + 1] = 0x33; img[i + 2] = 0x66; img[i + 3] = 0xFF;
        }
        seeds.push_back(std::move(img));
    }
    {  // Frame histogram: 24 pairs of plausible 120Hz durations/offsets.
        std::vector<uint8_t> hist(16 * 24);
        for (int i = 0; i < 24; ++i) {
            uint64_t d = 4000000 + static_cast<uint64_t>(i) * 300000;
            int64_t off = (i % 7) * 400000 - 1200000;
            std::memcpy(hist.data() + 16 * i, &d, 8);
            std::memcpy(hist.data() + 16 * i + 8, &off, 8);
        }
        seeds.push_back(std::move(hist));
    }
    return seeds;
}

}  // namespace

int main(int argc, char** argv) {
    unsigned long iters = 300000;
    if (argc > 1) {
        const unsigned long n = std::strtoul(argv[1], nullptr, 10);
        if (n > 0) iters = n;
    }
    StandaloneRng rng(0x5EEDF00D5AA5ull);
    const auto seeds = MakeSeeds();
    fuzz_check(!seeds.empty());

    std::vector<uint8_t> buf;
    unsigned long survived = 0;
    for (unsigned long it = 0; it < iters; ++it) {
        buf = seeds[rng.below(static_cast<uint32_t>(seeds.size()))];
        const uint32_t edits = 1 + rng.below(8);
        for (uint32_t e = 0; e < edits; ++e) {
            if (buf.empty()) break;
            const size_t pos = rng.below(static_cast<uint32_t>(buf.size()));
            switch (rng.below(3)) {
                case 0: buf[pos] ^= static_cast<uint8_t>(1u << rng.below(8)); break;
                case 1: buf[pos] = static_cast<uint8_t>(rng.next()); break;
                default: buf[pos] = static_cast<uint8_t>(rng.below(2) ? 0x00 : 0xFF); break;
            }
        }
        switch (rng.below(4)) {
            case 0:  // truncate
                if (!buf.empty()) {
                    buf.resize(rng.below(static_cast<uint32_t>(buf.size())) + 1);
                }
                break;
            case 1:  // splice out a middle chunk
                if (buf.size() > 4) {
                    const size_t a = rng.below(static_cast<uint32_t>(buf.size() - 2));
                    const size_t b = a + 1 + rng.below(static_cast<uint32_t>(buf.size() - a - 1));
                    buf.erase(buf.begin() + static_cast<ptrdiff_t>(a),
                              buf.begin() + static_cast<ptrdiff_t>(b));
                }
                break;
            case 2:  // extend with random bytes
                for (int i = 0; i < 16; ++i) {
                    buf.push_back(static_cast<uint8_t>(rng.next()));
                }
                break;
            default:  // pure random buffer
                buf.resize(1 + rng.below(512));
                for (auto& b : buf) b = static_cast<uint8_t>(rng.next());
                break;
        }
        LLVMFuzzerTestOneInput(buf.data(), buf.size());
        ++survived;
    }
    std::printf("[fuzz-palette-frame] standalone: %lu iterations survived, "
                "0 invariant violations\n",
                survived);
    return 0;
}

#endif  // STREAMIFY_FUZZ_STANDALONE
