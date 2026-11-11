// ============================================================================
//  test_lufs_rust_parity.cc — the C8 LUFS cross-implementation parity suite
//  (native/tests/test_lufs_rust_parity.cc; CMake target
//  lufs_parity_test_suite)
// ============================================================================
//
//  WHAT THIS PROVES. The repo's C++ BS.1770-4 loudness engine
//  (native/dsp/LufsNormalizer.cc) measures the SAME integrated gated LUFS
//  as the CANONICAL Rust implementation
//  (rust/crates/streamify-audio/src/lufs.rs — designated canonical by
//  WS-C task C8) on a shared, deterministic signal set. The golden values
//  live in tests/lufs_golden_vectors.h, GENERATED from the canonical
//  algorithm by scripts/generate_lufs_golden_vectors.py (or regenerated
//  from the canonical implementation itself via
//  `cargo run -p streamify-audio --example lufs_golden_vectors`). The
//  Rust lane re-derives the same fixture through the REAL LufsAnalyzer in
//  tests/test_lufs_golden_vectors.rs (CI drift gate, epsilon 0.01) — one
//  fixture, two implementations, three-way pin.
//
//  COVERAGE MAP:
//   A. designKFilters(48000) reproduces the ITU-R BS.1770-4 Annex 1 spec
//      literals — the coefficient-level pin that catches typos too small
//      for any LUFS-value epsilon (the C8 b2 drift was 3 f32 ulps,
//      ~3e-6 LUFS: invisible to the value gate, trivial for this one).
//   B. Per-vector parity: every fixture signal is REGENERATED here from
//      the documented recipes (identical deterministic math — the noise
//      vector is pure 64-bit integer state, the tones are f64 sin with a
//      last-ulp libm note), measured through a fresh default-constructed
//      LufsNormalizer's one-shot computeIntegratedLufs() and compared
//      against the fixture within kLufsParityEpsilon.
//
//  MONO / SILENCE ACCOMMODATIONS (fixture conventions, see the header):
//   * channels == 1 vectors are passed with num_channels = 1 and ONLY the
//     L pointer — the C++ channel weight for ch 0 is 1.0, matching the
//     canonical analyzer's L-only treatment of mono.
//   * There is NO pure-silence vector: the C++ one-shot returns a -70.0f
//     sentinel when no block survives the absolute gate (and falls back
//     to the ungated mean below 400 ms), while the canonical
//     integrated_lufs() returns -inf (LUFS_SILENCE) — different,
//     documented contracts that are deliberately outside this fixture.
//     The gated fade-in vector (silence then tone) exercises the
//     absolute gate with real survivors instead.
//
//  EPSILON. kLufsParityEpsilon comes from the generated header
//  (clamp(max(4 * max_observed_delta, 0.02), <= 0.15)); the residual
//  delta is dominated by the C++ engine's f32 BiquadCoeffs storage (the
//  canonical lane keeps f64 spec literals) plus its f32 sample API and
//  f32 return. This suite prints every measured delta — if a change to
//  the C++ engine moves them materially, re-measure and regenerate the
//  fixture's epsilon documentation (see the generator script).
// ============================================================================

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "LufsNormalizer.h"
#include "lufs_golden_vectors.h"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool ok, const char* what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("    FAIL: %s\n", what);
    }
}

// ---------------------------------------------------------------------------
// Signal regeneration — deterministic recipes mirrored from
// scripts/generate_lufs_golden_vectors.py (and the fixture header's
// generation parameters). Cross-language f64 `sin` may differ in the last
// ulp; the integrated LUFS effect over ~1e5 samples is << 1e-9, far
// inside every epsilon here. The noise vector is exact integer math and
// bit-identical everywhere.
// ---------------------------------------------------------------------------

void genSine(std::vector<float>& out, size_t frames, double freq, double amp) {
    out.resize(frames);
    for (size_t n = 0; n < frames; ++n) {
        const double x = std::sin(kLufsGoldenTau * freq *
                                  static_cast<double>(n) /
                                  static_cast<double>(kLufsGoldenRateHz)) * amp;
        out[n] = static_cast<float>(x);  // f32 quantization, as both lanes take it
    }
}

// xorshift64*: one u64 stream, L and R consuming ALTERNATE steps.
struct Xorshift64Star {
    uint64_t s;
    explicit Xorshift64Star(uint64_t seed) : s(seed) {}
    uint64_t next() {
        s ^= s >> 12;
        s ^= s << 25;  // uint64_t arithmetic wraps mod 2^64 — exact
        s ^= s >> 27;
        return s * kLufsNoiseMultiplier;
    }
    float sample() {
        const double v =
            static_cast<double>(next()) / kLufsNoiseScale - 1.0;  // [-1, 1)
        return static_cast<float>(v);
    }
};

void genNoise(std::vector<float>& l, std::vector<float>& r, size_t frames) {
    Xorshift64Star rng(kLufsNoiseSeed);
    l.resize(frames);
    r.resize(frames);
    for (size_t n = 0; n < frames; ++n) {
        l[n] = rng.sample();  // left consumes one step ...
        r[n] = rng.sample();  // ... right consumes the NEXT step
    }
}

// Build the non-interleaved channel buffers for a fixture vector. Every
// recipe name in the fixture MUST be known here — an unknown name fails
// the suite so fixture additions cannot silently skip the parity lane.
void regenerateVector(const LufsGoldenVector& v, std::vector<float>& l,
                      std::vector<float>& r) {
    const size_t n = v.sample_count;
    l.clear();
    r.clear();
    if (std::strcmp(v.name, "sine_997Hz_m23dBFS_mono_3s") == 0) {
        genSine(l, n, kLufsFreq997Hz, kLufsAmpM23Dbfs);
    } else if (std::strcmp(v.name, "sine_997Hz_m23dBFS_stereo_3s") == 0) {
        genSine(l, n, kLufsFreq997Hz, kLufsAmpM23Dbfs);
        r = l;  // L = R (dual mono)
    } else if (std::strcmp(v.name, "sine_440Hz_0dBFS_stereo_2s") == 0) {
        genSine(l, n, kLufsFreq440Hz, kLufsAmpNearFs);
        r = l;
    } else if (std::strcmp(v.name, "xorshift_noise_stereo_5s") == 0) {
        genNoise(l, r, n);
    } else if (std::strcmp(v.name,
                           "silence_1s_then_sine_m20dBFS_stereo_2s") == 0) {
        std::vector<float> tone;
        genSine(tone, n - kLufsSilenceLeadFrames, kLufsFreq440Hz,
                kLufsAmpM20Dbfs);
        l.assign(kLufsSilenceLeadFrames, 0.0f);  // exact digital silence
        l.insert(l.end(), tone.begin(), tone.end());
        r = l;
    } else if (std::strcmp(v.name, "sine_250Hz_m40dBFS_stereo_3s") == 0) {
        genSine(l, n, kLufsFreq250Hz, kLufsAmpM40Dbfs);
        r = l;
    } else {
        check(false, "unknown fixture recipe — extend the C++ regenerator");
        l.assign(n, 0.0f);
        if (v.channels == 2) r = l;
    }
}

// ---------------------------------------------------------------------------
// A. K-filter coefficient pin (the typo gate)
// ---------------------------------------------------------------------------

void test_k_filter_spec_literals() {
    std::printf("  [A] designKFilters(48000): ITU spec literals\n");

    BiquadCoeffs shelf, hp;
    LufsNormalizer::designKFilters(48000, &shelf, &hp);

    // ITU-R BS.1770-4 Annex 1 coefficient tables — the same f64 literals
    // the canonical rust/crates/streamify-audio/src/lufs.rs pins.
    struct Coeff {
        double b0, b1, b2, a1, a2;
    };
    const Coeff specShelf{1.53512485958697, -2.69169618940638,
                          1.19839281085285, -1.69065929318241,
                          0.73248077421585};
    const Coeff specRlb{1.0, -2.0, 1.0, -1.99004745483398,
                        0.99007225036621};

    // BiquadCoeffs stores f32, so the pin is against the exact f32 IMAGE
    // of each spec literal (what a correctly stored coefficient reads
    // back as when promoted to double): a correct build matches to 0,
    // comfortably inside 1e-9. The pre-C8 typo (b2 = 1.19839251015039)
    // sat 3 f32 ulps low and missed by 3.576e-07 — this check, not the
    // LUFS-value epsilon, is what catches that class of drift (its LUFS
    // effect is only ~3e-6).
    auto pinStage = [&](const char* stage, const BiquadCoeffs& got,
                        const Coeff& want) {
        const double gotC[5] = {got.b0, got.b1, got.b2, got.a1, got.a2};
        const double wantC[5] = {want.b0, want.b1, want.b2, want.a1, want.a2};
        const char* ids[5] = {"b0", "b1", "b2", "a1", "a2"};
        for (int i = 0; i < 5; ++i) {
            const double image =
                static_cast<double>(static_cast<float>(wantC[i]));
            ++g_checks;
            if (std::fabs(gotC[i] - image) > 1e-9) {
                ++g_failures;
                std::printf(
                    "    FAIL: %s %s: designed %.17g, spec f32 image %.17g"
                    " (delta %+.3e)\n",
                    stage, ids[i], gotC[i], image, gotC[i] - image);
            }
            // Storage quantization bound: |f32(literal) - literal| stays
            // within half an f32 ulp at this magnitude (< 6e-8). This
            // documents WHY the 1e-9 pin targets the f32 image rather
            // than the raw f64 literal.
            check(std::fabs(gotC[i] - wantC[i]) <= 6.0e-8,
                  "f32 storage stays within half an ulp of the spec literal");
        }
    };
    pinStage("stage1/shelf", shelf, specShelf);
    pinStage("stage2/rlb", hp, specRlb);
    std::printf("    10 coefficients pinned to the ITU spec f32 images\n");
}

// ---------------------------------------------------------------------------
// B. Per-vector parity (the value gate)
// ---------------------------------------------------------------------------

void test_parity_vectors() {
    std::printf("  [B] integrated LUFS parity vs the canonical fixture\n");

    double maxDelta = 0.0;
    for (const LufsGoldenVector& v : kLufsGoldenVectors) {
        std::vector<float> l, r;
        regenerateVector(v, l, r);

        // Geometry sanity: the regenerator must produce what the fixture
        // declares before we spend a measurement on it.
        check(l.size() == v.sample_count, "regenerated sample_count matches");
        check(v.channels == 1 || v.channels == 2, "channel count is 1 or 2");
        if (v.channels == 2) {
            check(r.size() == v.sample_count, "stereo regenerates R too");
        }

        // Non-interleaved channel pointers; mono passes ONLY the L pointer
        // with num_channels = 1 (channel weight 1.0, same as the canonical
        // analyzer's L-only treatment). Fresh default-constructed instance
        // per vector — no carried state.
        const float* chans[2] = {l.data(), r.data()};
        const size_t nch = (v.channels == 2) ? 2 : 1;
        LufsNormalizer meter;
        const float measured = meter.computeIntegratedLufs(
            chans, nch, v.sample_count, kLufsGoldenRateHz);

        const double delta =
            std::fabs(static_cast<double>(measured) - v.expected_lufs);
        if (delta > maxDelta) maxDelta = delta;
        std::printf(
            "  %-40s ch=%u n=%6zu  expected=%11.6f  measured=%11.6f"
            "  delta=%+.3e\n",
            v.name, v.channels, static_cast<size_t>(v.sample_count),
            v.expected_lufs, static_cast<double>(measured), delta);
        check(delta <= kLufsParityEpsilon,
              "|C++ measured - canonical fixture| within kLufsParityEpsilon");
    }
    std::printf("    max |delta| over the suite: %.3e LUFS"
                " (epsilon %.17g)\n",
                maxDelta, kLufsParityEpsilon);
}

}  // namespace

int main() {
    std::printf("==================================================\n");
    std::printf("  Streamify LUFS Rust-Parity Suite (C8, WS-C)\n");
    std::printf("  C++ BS.1770-4 vs canonical Rust golden vectors\n");
    std::printf("==================================================\n");
    test_k_filter_spec_literals();
    test_parity_vectors();
    std::printf("--------------------------------------------------\n");
    std::printf("  %d check(s), %d failure(s)\n", g_checks, g_failures);
    if (g_failures != 0) {
        std::printf("  LUFS RUST-PARITY SUITE: FAIL\n");
        return 1;
    }
    std::printf("  LUFS RUST-PARITY SUITE: PASS\n");
    return 0;
}
