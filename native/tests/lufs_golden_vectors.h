// ============================================================================
//  lufs_golden_vectors.h — GENERATED LUFS golden vectors (WS-C task C8)
// ============================================================================
//
//  DO NOT EDIT BY HAND. Regenerate with either path:
//
//    python3 scripts/generate_lufs_golden_vectors.py
//        The CANONICAL algorithm (rust/crates/streamify-audio/src/lufs.rs,
//        designated canonical by C8) mirrored op-for-op in Python f64.
//
//    cargo run -p streamify-audio --example lufs_golden_vectors \
//        > native/tests/lufs_golden_vectors.h
//        The canonical implementation ITSELF (the real LufsAnalyzer) on
//        a host with a Rust toolchain — prints this same format.
//
//  The two paths agree to << 1e-9 LUFS: the only cross-libm difference
//  on the sine vectors is the last ulp of f64 `sin`, whose effect on a
//  ~1e5-sample integrated measurement is orders of magnitude inside
//  every epsilon below. The noise vector is pure 64-bit integer math
//  and bit-identical on every host.
//
//  CONSUMERS — the C8 three-way pin (one fixture, two implementations):
//    * C++ lane:  native/tests/test_lufs_rust_parity.cc (CMake target
//      lufs_parity_test_suite) regenerates every signal, measures it
//      with native/dsp/LufsNormalizer.cc computeIntegratedLufs and
//      asserts |measured - expected| <= kLufsParityEpsilon.
//    * Rust lane: rust/crates/streamify-audio/tests/test_lufs_golden_
//      vectors.rs regenerates every signal, measures it with the REAL
//      canonical streamify_audio::lufs::LufsAnalyzer and asserts
//      |measured - expected| <= 0.01 LUFS (CI drift gate).
//
//  SIGNAL SET (48 kHz; every signal is quantized to f32 BEFORE
//  measurement — exactly what both lanes receive):
//    sine_997Hz_m23dBFS_mono_3s             1ch  A*sin(2*pi*997*n/48000),
//                                                A = 10^(-23/20), L only
//    sine_997Hz_m23dBFS_stereo_3s           2ch  the mono signal above,
//                                                L = R
//    sine_440Hz_0dBFS_stereo_2s             2ch  A = 0.999 (just below
//                                                full scale), 440 Hz
//    xorshift_noise_stereo_5s               2ch  ONE xorshift64* stream
//                                                (seed 0x9E3779B97F4A7C15),
//                                                L and R consuming
//                                                ALTERNATE steps
//    silence_1s_then_sine_m20dBFS_stereo_2s 2ch  48000 exact-zero frames,
//                                                then 2 s of
//                                                A = 10^(-20/20) @ 440 Hz
//                                                (exercises the absolute
//                                                gate with real survivors)
//    sine_250Hz_m40dBFS_stereo_3s           2ch  A = 10^(-40/20) @ 250 Hz
//                                                (below the RLB corner)
//
//  EPSILON POLICY (C++ lane): kLufsParityEpsilon =
//      clamp(max(4 * max_observed_delta, 0.02), <= 0.15).
//  Residual delta sources: the C++ engine's f32 BiquadCoeffs storage
//  (the canonical lane keeps f64 spec literals), its f32 sample API
//  (float*) and its f32 return value.
//  Max observed on the C8 host (g++, -O2 -fno-fast-math): 2.514e-05 LUFS
//  (worst vector: sine_250Hz_m40dBFS_stereo_3s); the per-vector table lives in
//  scripts/generate_lufs_golden_vectors.py (MEASURED_CPP_DELTAS).
//  The Rust lane's epsilon is 0.01: it re-runs the SAME implementation
//  the fixture was derived from, so only libm last-ulp noise remains.
// ============================================================================

#pragma once

#include <cstddef>
#include <cstdint>

// ---- Generation parameters (single source for both lanes; mirrored in
// rust/crates/streamify-audio/tests/lufs_golden_vectors_data.rs) ---------

constexpr unsigned kLufsGoldenRateHz = 48000;             // canonical rate
constexpr double kLufsGoldenTau = 6.283185307179586;      // 2*pi (f64)
constexpr double kLufsFreq997Hz = 997.0;
constexpr double kLufsFreq440Hz = 440.0;
constexpr double kLufsFreq250Hz = 250.0;
constexpr double kLufsAmpM23Dbfs = 0.070794578438413802;  // 10^(-23/20)
constexpr double kLufsAmpNearFs = 0.999;                  // just below FS
constexpr double kLufsAmpM20Dbfs = 0.1;                   // 10^(-20/20)
constexpr double kLufsAmpM40Dbfs = 0.01;                  // 10^(-40/20)
constexpr size_t kLufsSilenceLeadFrames = 48000;          // 1 s @ 48 kHz
constexpr uint64_t kLufsNoiseSeed = 0x9E3779B97F4A7C15ull;
constexpr uint64_t kLufsNoiseMultiplier = 0x2545F4914F6CDD1Dull;  // xrs64*
constexpr double kLufsNoiseScale = 9223372036854775808.0; // 2^63

// ---- The vectors ----------------------------------------------------------

struct LufsGoldenVector {
    const char* name;       // recipe key (see the signal set above)
    unsigned channels;      // 1 = L-only mono, 2 = L/R stereo
    size_t sample_count;    // frames per channel @ 48 kHz
    double expected_lufs;   // canonical integrated gated LUFS (f64)
};

constexpr LufsGoldenVector kLufsGoldenVectors[] = {
    {"sine_997Hz_m23dBFS_mono_3s"            , 1, 144000, -26.010264522411571},
    {"sine_997Hz_m23dBFS_stereo_3s"          , 2, 144000, -22.999964565771759},
    {"sine_440Hz_0dBFS_stereo_2s"            , 2,  96000, -0.69161896388678334},
    {"xorshift_noise_stereo_5s"              , 2, 240000, 1.3811307394272201},
    {"silence_1s_then_sine_m20dBFS_stereo_2s", 2, 144000, -21.021778828615108},
    {"sine_250Hz_m40dBFS_stereo_3s"          , 2, 144000, -40.844145162533991},
};

constexpr size_t kLufsGoldenVectorCount =
    sizeof(kLufsGoldenVectors) / sizeof(kLufsGoldenVectors[0]);

// C++ parity-lane comparison bound — derivation in the comment above.
constexpr double kLufsParityEpsilon = 0.02;
