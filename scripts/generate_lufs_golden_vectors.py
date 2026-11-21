#!/usr/bin/env python3
"""
generate_lufs_golden_vectors.py — WS-C task C8: the LUFS consolidation
golden vectors (ONE fixture, TWO implementations, a three-way pin).

WHY THIS EXISTS
---------------
The repo carried two independent ITU-R BS.1770-4 loudness implementations
that had drifted apart:

  * rust/crates/streamify-audio/src/lufs.rs — CANONICAL (designated by
    C8): f64 throughout, spec-literal K-weighting, direct form I, a
    sliding 400 ms power window, 75 % overlap (100 ms hop), dual gating.
  * native/dsp/LufsNormalizer.cc — the C++ engine: f32 coefficient
    storage, DF2T biquads, chunk-accumulated block energies, the one-shot
    computeIntegratedLufs() API.

Nothing tied them together, so either side could drift silently — and one
had: the C++ stage-1 b2 literal was a typo (1.19839251015039, 3 f32 ulps
below the ITU spec's 1.19839281085285) until C8 fixed it.

THE THREE-WAY PIN
-----------------
This script is the NEUTRAL generator: it mirrors the canonical Rust
algorithm op-for-op in Python f64 (same literals, same operation order,
same block ladder, same dual gating) and freezes the integrated LUFS of a
deterministic signal set into TWO artifacts with identical values:

  * native/tests/lufs_golden_vectors.h
        (C++ lane fixture; consumed by tests/test_lufs_rust_parity.cc,
         CMake target lufs_parity_test_suite)
  * rust/crates/streamify-audio/tests/lufs_golden_vectors_data.rs
        (Rust lane fixture; consumed by tests/test_lufs_golden_vectors.rs,
         the CI drift gate run by `cargo test -p streamify-audio`)

Both lanes REGENERATE the signals locally from the documented recipes
(pure 64-bit integer math for the noise vector, f64 `sin` for the tones)
and assert their REAL implementation measures the fixture value.

REGENERATION PATHS (the fixtures are never edited by hand)
----------------------------------------------------------
  * python3 scripts/generate_lufs_golden_vectors.py
      This script: the canonical algorithm mirrored in Python f64.
  * cargo run -p streamify-audio --example lufs_golden_vectors \
        > native/tests/lufs_golden_vectors.h
      On a host with a Rust toolchain: the CANONICAL implementation
      itself (the real streamify_audio::lufs::LufsAnalyzer) prints the
      same C++ header format.

The two paths agree to << 1e-9 LUFS. The only cross-libm difference is
the last ulp of f64 `sin` on the sine vectors (and of `log10` on the
block powers), whose effect on an integrated measurement over ~1e5
samples is orders of magnitude inside every epsilon in this suite. The
noise vector is exact 64-bit integer math — bit-identical on every host,
in every language.

DRIFT GATES (what breaks, and when)
-----------------------------------
  * A coefficient typo in EITHER implementation (like the C8 b2 typo)
    trips the designKFilters literal check (C++ lane) or the 0.01 LUFS
    gate (Rust lane).
  * A structural change (block geometry, hop, gating, K-weighting math)
    moves the measured values out of the fixture epsilon in CI.
  * After an INTENTIONAL canonical change: edit lufs.rs, run this script
    (or the cargo example), and commit the fixture diff as the review
    artifact — both lanes must stay green.

EPSILON POLICY (C++ lane)
-------------------------
    kLufsParityEpsilon = clamp(max(4 * max_observed_delta, 0.02), <= 0.15)

max_observed_delta is the largest |C++ measured - fixture| over the whole
suite, measured on the C8 host (g++, -O2 -fno-fast-math) and kept in
MEASURED_CPP_DELTAS below. Residual (accepted) delta sources: the C++
engine's f32 BiquadCoeffs storage (the canonical lane keeps f64 spec
literals), its f32 sample API (computeIntegratedLufs takes float*) and
its f32 return value. Re-measure whenever native/dsp/LufsNormalizer.cc
changes materially: build + run lufs_parity_test_suite, read the
per-vector delta lines it prints, update MEASURED_CPP_DELTAS, regenerate.

THE CANONICAL ALGORITHM (mirror of lufs.rs — implemented EXACTLY below)
-----------------------------------------------------------------------
  * K-weighting per channel, direct form I, f64, ITU-R BS.1770-4 Annex 1
    spec literals:
        stage 1 high shelf:  b = [1.53512485958697, -2.69169618940638,
                                  1.19839281085285]
                             a = [-1.69065929318241, 0.73248077421585]
        stage 2 RLB HP:      b = [1.0, -2.0, 1.0]
                             a = [-1.99004745483398, 0.99007225036621]
    y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2 per stage, chained.
  * Block ladder: 400 ms blocks (19200 frames @ 48 kHz), hop 100 ms
    (4800 frames), 75 % overlap — a block completes when the 19200-frame
    sliding window first fills, then every 4800 frames.
  * Per block: z = sum over channels of G_c * mean(square(K-weighted
    channel over the block)); G_L = G_R = 1.0 (BS.1770 §2.1).
  * Block loudness = -0.691 + 10*log10(z) (z clamped to f64::MIN_POSITIVE
    before the log; exact 0 stays far below the gate).
  * Gating: the absolute gate keeps blocks louder than -70 LUFS; the
    relative gate is (loudness of the mean surviving z) - 10 LU; the
    final integrated value is the loudness of the mean z surviving BOTH
    gates.

Every signal is quantized to f32 BEFORE measurement — exactly what both
lanes receive (the Rust analyzer and the C++ one-shot both take f32).
Mono vectors ride the L channel with R silent: the canonical analyzer is
a fixed 2-channel pipeline, and the C++ lane passes num_channels = 1 with
only the L pointer (channel weight 1.0 on both sides).
"""

import math
import struct
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT_CPP = REPO / "native" / "tests" / "lufs_golden_vectors.h"
OUT_RS = (
    REPO
    / "rust"
    / "crates"
    / "streamify-audio"
    / "tests"
    / "lufs_golden_vectors_data.rs"
)

# ---- canonical constants (mirror of rust/crates/streamify-audio/src/lufs.rs)

RATE = 48_000                       # LUFS_SAMPLE_RATE_HZ
BLOCK_FRAMES = 19_200               # LUFS_BLOCK_FRAMES (400 ms @ 48 kHz)
HOP_FRAMES = 4_800                  # LUFS_HOP_FRAMES (100 ms, 75 % overlap)
ABSOLUTE_GATE_LUFS = -70.0          # LUFS_ABSOLUTE_GATE
RELATIVE_GATE_LU = -10.0            # LUFS_RELATIVE_GATE_LU
F64_MIN_POSITIVE = 2.2250738585072014e-308  # f64::MIN_POSITIVE

SHELF_B = (1.53512485958697, -2.69169618940638, 1.19839281085285)
SHELF_A = (-1.69065929318241, 0.73248077421585)
RLB_B = (1.0, -2.0, 1.0)
RLB_A = (-1.99004745483398, 0.99007225036621)

# ---- signal-set parameters (single source; mirrored into BOTH artifacts)

TWO_PI = 6.283185307179586          # f64 2*pi (== std::f64::consts::TAU)
FREQ_997 = 997.0
FREQ_440 = 440.0
FREQ_250 = 250.0
AMP_M23_DBFS = 0.070794578438413802  # 10^(-23/20), %.17g round-trip literal
AMP_NEAR_FS = 0.999                  # just below digital full scale
AMP_M20_DBFS = 0.1                   # 10^(-20/20)
AMP_M40_DBFS = 0.01                  # 10^(-40/20)
SILENCE_LEAD_FRAMES = 48_000         # 1 s @ 48 kHz
NOISE_SEED = 0x9E3779B97F4A7C15      # xorshift64* seed (nonzero)
NOISE_MULTIPLIER = 0x2545F4914F6CDD1D
NOISE_SCALE = 9_223_372_036_854_775_808.0  # 2^63 -> [-1, 1)

MASK64 = (1 << 64) - 1

# Per-vector |C++ measured - fixture| deltas in LUFS, measured on the C8
# host (g++ 12, x86-64, -O2 -fno-fast-math) by running
# lufs_parity_test_suite and reading its per-vector delta lines. These
# feed the epsilon policy documented in the module docstring and in the
# generated header. `None` = not yet measured (provisional generation).
MEASURED_CPP_DELTAS = {
    "sine_997Hz_m23dBFS_mono_3s": 8.709e-06,
    "sine_997Hz_m23dBFS_stereo_3s": 8.435e-06,
    "sine_440Hz_0dBFS_stereo_2s": 2.168e-05,
    "xorshift_noise_stereo_5s": 2.825e-07,
    "silence_1s_then_sine_m20dBFS_stereo_2s": 2.170e-05,
    "sine_250Hz_m40dBFS_stereo_3s": 2.514e-05,
}


def f32(x: float) -> float:
    """Round-to-nearest f32 quantization (the IEEE cast every lane does)."""
    return struct.unpack("<f", struct.pack("<f", x))[0]


class KWeight:
    """One K-weighting channel: the two spec biquads in direct form I —
    an op-for-op mirror of lufs.rs's KWeight (same literals, same
    left-to-right operation order, so the arithmetic is bit-identical)."""

    __slots__ = (
        "s1x1", "s1x2", "s1y1", "s1y2",
        "s2x1", "s2x2", "s2y1", "s2y2",
    )

    def __init__(self) -> None:
        self.s1x1 = self.s1x2 = self.s1y1 = self.s1y2 = 0.0
        self.s2x1 = self.s2x2 = self.s2y1 = self.s2y2 = 0.0

    def process(self, x: float) -> float:
        b0, b1, b2 = SHELF_B
        a1, a2 = SHELF_A
        y1 = b0 * x + b1 * self.s1x1 + b2 * self.s1x2 \
            - a1 * self.s1y1 - a2 * self.s1y2
        self.s1x2 = self.s1x1
        self.s1x1 = x
        self.s1y2 = self.s1y1
        self.s1y1 = y1
        b0, b1, b2 = RLB_B
        a1, a2 = RLB_A
        y2 = b0 * y1 + b1 * self.s2x1 + b2 * self.s2x2 \
            - a1 * self.s2y1 - a2 * self.s2y2
        self.s2x2 = self.s2x1
        self.s2x1 = y1
        self.s2y2 = self.s2y1
        self.s2y1 = y2
        return y2


def integrated_lufs(channels) -> float:
    """Mirror of LufsAnalyzer::process + integrated_lufs over the f32-
    quantized per-channel signals (channels[1] absent => R silent, exactly
    how the canonical 2-channel analyzer receives a mono vector and how
    the C++ lane passes num_channels = 1)."""
    left = channels[0]
    right = channels[1] if len(channels) > 1 else None
    frames = len(left)
    kw_l = KWeight()
    kw_r = KWeight()
    process_l = kw_l.process
    process_r = kw_r.process
    ring = [0.0] * BLOCK_FRAMES
    pos = 0
    power_sum = 0.0
    frames_seen = 0
    blocks = []
    push_block = blocks.append
    for i in range(frames):
        l = process_l(left[i])
        r = process_r(right[i] if right is not None else 0.0)
        p = 1.0 * l * l + 1.0 * r * r  # CHANNEL_WEIGHTS are 1.0/1.0
        power_sum -= ring[pos]
        ring[pos] = p
        power_sum += p
        pos = (pos + 1) % BLOCK_FRAMES
        frames_seen += 1
        if frames_seen >= BLOCK_FRAMES and \
                (frames_seen - BLOCK_FRAMES) % HOP_FRAMES == 0:
            push_block(power_sum / BLOCK_FRAMES)

    # ---- integrated_lufs(): dual gating, mirrored --------------------------
    if not blocks:
        return float("-inf")

    def block_loudness(z: float) -> float:
        zm = z if z > F64_MIN_POSITIVE else F64_MIN_POSITIVE
        return -0.691 + 10.0 * math.log10(zm)

    abs_gated = [z for z in blocks if block_loudness(z) > ABSOLUTE_GATE_LUFS]
    if not abs_gated:
        return float("-inf")
    abs_mean_z = sum(abs_gated) / len(abs_gated)
    rel_threshold = block_loudness(abs_mean_z) + RELATIVE_GATE_LU
    rel_gated = [z for z in abs_gated if block_loudness(z) > rel_threshold]
    if not rel_gated:
        return float("-inf")
    mean_z = sum(rel_gated) / len(rel_gated)
    return block_loudness(mean_z)


# ---------------------------------------------------------------------------
# Signal generation (deterministic, regenerable identically in Rust and C++)
# ---------------------------------------------------------------------------

def sine(frames: int, freq: float, amp: float):
    """x[n] = amp * sin(2*pi*freq*n/48000): f64 phase math, quantized to
    f32. Cross-language f64 `sin` may differ in the last ulp; the
    integrated LUFS effect over ~1e5 samples is << 1e-9 (documented in
    every artifact this generator emits)."""
    sin = math.sin
    out = [0.0] * frames
    for n in range(frames):
        out[n] = f32(sin(TWO_PI * freq * n / RATE) * amp)
    return out


def noise_stereo(frames: int):
    """Per-sample stereo noise from ONE xorshift64* stream: L and R
    consume ALTERNATE steps (L, R, L, R, ...). Exact 64-bit integer math
    (mod 2^64) -> bit-identical in Python/Rust/C++."""
    s = NOISE_SEED
    left = [0.0] * frames
    right = [0.0] * frames

    def step() -> int:
        nonlocal s
        s ^= s >> 12
        s = (s ^ ((s << 25) & MASK64)) & MASK64
        s ^= s >> 27
        return (s * NOISE_MULTIPLIER) & MASK64

    for n in range(frames):
        left[n] = f32(step() / NOISE_SCALE - 1.0)
        right[n] = f32(step() / NOISE_SCALE - 1.0)
    return left, right


def build_vectors():
    """The C8 signal set: (name, channels, frames, per-channel signals)."""
    vectors = []

    mono = sine(3 * RATE, FREQ_997, AMP_M23_DBFS)
    vectors.append(("sine_997Hz_m23dBFS_mono_3s", 1, len(mono), [mono]))

    vectors.append(
        ("sine_997Hz_m23dBFS_stereo_3s", 2, len(mono), [mono, mono])
    )

    loud = sine(2 * RATE, FREQ_440, AMP_NEAR_FS)
    vectors.append(
        ("sine_440Hz_0dBFS_stereo_2s", 2, len(loud), [loud, loud])
    )

    left, right = noise_stereo(5 * RATE)
    vectors.append(
        ("xorshift_noise_stereo_5s", 2, len(left), [left, right])
    )

    # 1 s of exact digital silence, then 2 s of tone: exercises the
    # absolute gate with real survivors (no pure-silence vector — the C++
    # one-shot's sub-400 ms / silence fallback is a different, documented
    # contract, deliberately out of this fixture's scope).
    tone = sine(2 * RATE, FREQ_440, AMP_M20_DBFS)
    gated = [0.0] * SILENCE_LEAD_FRAMES + tone
    vectors.append(
        (
            "silence_1s_then_sine_m20dBFS_stereo_2s",
            2,
            len(gated),
            [gated, gated],
        )
    )

    low = sine(3 * RATE, FREQ_250, AMP_M40_DBFS)
    vectors.append(
        ("sine_250Hz_m40dBFS_stereo_3s", 2, len(low), [low, low])
    )
    return vectors


# ---------------------------------------------------------------------------
# Epsilon policy + rendering
# ---------------------------------------------------------------------------

def cpp_epsilon_and_max_delta():
    measured = [d for d in MEASURED_CPP_DELTAS.values() if d is not None]
    if not measured:
        return 0.02, None  # provisional floor until the first measurement
    worst = max(measured)
    return min(max(4.0 * worst, 0.02), 0.15), worst


def g17(v: float) -> str:
    """C printf %.17g (round-trip-exact f64 literal for every value in
    this fixture; all values render in fixed style)."""
    return "%.17g" % v


def render_header(results, epsilon, max_delta) -> str:
    if max_delta is None:
        delta_doc = [
            "//  Max observed on the C8 host (g++, -O2 -fno-fast-math): NOT YET",
            "//  MEASURED (provisional generation) — run lufs_parity_test_suite,",
            "//  fill MEASURED_CPP_DELTAS in",
            "//  scripts/generate_lufs_golden_vectors.py and regenerate.",
        ]
    else:
        worst_name = max(
            (n for n, d in MEASURED_CPP_DELTAS.items() if d is not None),
            key=lambda n: MEASURED_CPP_DELTAS[n],
        )
        delta_doc = [
            "//  Max observed on the C8 host (g++, -O2 -fno-fast-math): %s LUFS"
            % "%.3e" % max_delta,
            "//  (worst vector: %s); the per-vector table lives in" % worst_name,
            "//  scripts/generate_lufs_golden_vectors.py (MEASURED_CPP_DELTAS).",
        ]

    lines = []
    a = lines.append
    a("// ============================================================================")
    a("//  lufs_golden_vectors.h — GENERATED LUFS golden vectors (WS-C task C8)")
    a("// ============================================================================")
    a("//")
    a("//  DO NOT EDIT BY HAND. Regenerate with either path:")
    a("//")
    a("//    python3 scripts/generate_lufs_golden_vectors.py")
    a("//        The CANONICAL algorithm (rust/crates/streamify-audio/src/lufs.rs,")
    a("//        designated canonical by C8) mirrored op-for-op in Python f64.")
    a("//")
    a("//    cargo run -p streamify-audio --example lufs_golden_vectors \\")
    a("//        > native/tests/lufs_golden_vectors.h")
    a("//        The canonical implementation ITSELF (the real LufsAnalyzer) on")
    a("//        a host with a Rust toolchain — prints this same format.")
    a("//")
    a("//  The two paths agree to << 1e-9 LUFS: the only cross-libm difference")
    a("//  on the sine vectors is the last ulp of f64 `sin`, whose effect on a")
    a("//  ~1e5-sample integrated measurement is orders of magnitude inside")
    a("//  every epsilon below. The noise vector is pure 64-bit integer math")
    a("//  and bit-identical on every host.")
    a("//")
    a("//  CONSUMERS — the C8 three-way pin (one fixture, two implementations):")
    a("//    * C++ lane:  native/tests/test_lufs_rust_parity.cc (CMake target")
    a("//      lufs_parity_test_suite) regenerates every signal, measures it")
    a("//      with native/dsp/LufsNormalizer.cc computeIntegratedLufs and")
    a("//      asserts |measured - expected| <= kLufsParityEpsilon.")
    a("//    * Rust lane: rust/crates/streamify-audio/tests/test_lufs_golden_")
    a("//      vectors.rs regenerates every signal, measures it with the REAL")
    a("//      canonical streamify_audio::lufs::LufsAnalyzer and asserts")
    a("//      |measured - expected| <= 0.01 LUFS (CI drift gate).")
    a("//")
    a("//  SIGNAL SET (48 kHz; every signal is quantized to f32 BEFORE")
    a("//  measurement — exactly what both lanes receive):")
    a("//    sine_997Hz_m23dBFS_mono_3s             1ch  A*sin(2*pi*997*n/48000),")
    a("//                                                A = 10^(-23/20), L only")
    a("//    sine_997Hz_m23dBFS_stereo_3s           2ch  the mono signal above,")
    a("//                                                L = R")
    a("//    sine_440Hz_0dBFS_stereo_2s             2ch  A = 0.999 (just below")
    a("//                                                full scale), 440 Hz")
    a("//    xorshift_noise_stereo_5s               2ch  ONE xorshift64* stream")
    a("//                                                (seed 0x9E3779B97F4A7C15),")
    a("//                                                L and R consuming")
    a("//                                                ALTERNATE steps")
    a("//    silence_1s_then_sine_m20dBFS_stereo_2s 2ch  48000 exact-zero frames,")
    a("//                                                then 2 s of")
    a("//                                                A = 10^(-20/20) @ 440 Hz")
    a("//                                                (exercises the absolute")
    a("//                                                gate with real survivors)")
    a("//    sine_250Hz_m40dBFS_stereo_3s           2ch  A = 10^(-40/20) @ 250 Hz")
    a("//                                                (below the RLB corner)")
    a("//")
    a("//  EPSILON POLICY (C++ lane): kLufsParityEpsilon =")
    a("//      clamp(max(4 * max_observed_delta, 0.02), <= 0.15).")
    a("//  Residual delta sources: the C++ engine's f32 BiquadCoeffs storage")
    a("//  (the canonical lane keeps f64 spec literals), its f32 sample API")
    a("//  (float*) and its f32 return value.")
    lines.extend(delta_doc)
    a("//  The Rust lane's epsilon is 0.01: it re-runs the SAME implementation")
    a("//  the fixture was derived from, so only libm last-ulp noise remains.")
    a("// ============================================================================")
    a("")
    a("#pragma once")
    a("")
    a("#include <cstddef>")
    a("#include <cstdint>")
    a("")
    a("// ---- Generation parameters (single source for both lanes; mirrored in")
    a("// rust/crates/streamify-audio/tests/lufs_golden_vectors_data.rs) ---------")
    a("")
    a("constexpr unsigned kLufsGoldenRateHz = 48000;             // canonical rate")
    a("constexpr double kLufsGoldenTau = 6.283185307179586;      // 2*pi (f64)")
    a("constexpr double kLufsFreq997Hz = 997.0;")
    a("constexpr double kLufsFreq440Hz = 440.0;")
    a("constexpr double kLufsFreq250Hz = 250.0;")
    a("constexpr double kLufsAmpM23Dbfs = 0.070794578438413802;  // 10^(-23/20)")
    a("constexpr double kLufsAmpNearFs = 0.999;                  // just below FS")
    a("constexpr double kLufsAmpM20Dbfs = 0.1;                   // 10^(-20/20)")
    a("constexpr double kLufsAmpM40Dbfs = 0.01;                  // 10^(-40/20)")
    a("constexpr size_t kLufsSilenceLeadFrames = 48000;          // 1 s @ 48 kHz")
    a("constexpr uint64_t kLufsNoiseSeed = 0x9E3779B97F4A7C15ull;")
    a("constexpr uint64_t kLufsNoiseMultiplier = 0x2545F4914F6CDD1Dull;  // xrs64*")
    a("constexpr double kLufsNoiseScale = 9223372036854775808.0; // 2^63")
    a("")
    a("// ---- The vectors ----------------------------------------------------------")
    a("")
    a("struct LufsGoldenVector {")
    a("    const char* name;       // recipe key (see the signal set above)")
    a("    unsigned channels;      // 1 = L-only mono, 2 = L/R stereo")
    a("    size_t sample_count;    // frames per channel @ 48 kHz")
    a("    double expected_lufs;   // canonical integrated gated LUFS (f64)")
    a("};")
    a("")
    a("constexpr LufsGoldenVector kLufsGoldenVectors[] = {")
    for name, ch, count, expected in results:
        # The quoted name padded OUTSIDE the quotes (C strcmp sees the
        # exact recipe key, no trailing padding).
        a('    {%s, %d, %6d, %s},'
          % ('%-40s' % ('"%s"' % name), ch, count, g17(expected)))
    a("};")
    a("")
    a("constexpr size_t kLufsGoldenVectorCount =")
    a("    sizeof(kLufsGoldenVectors) / sizeof(kLufsGoldenVectors[0]);")
    a("")
    a("// C++ parity-lane comparison bound — derivation in the comment above.")
    a("constexpr double kLufsParityEpsilon = %s;" % g17(epsilon))
    a("")
    return "\n".join(lines)


def render_rust_module(results) -> str:
    lines = []
    a = lines.append
    a("//! lufs_golden_vectors_data.rs — GENERATED LUFS golden vectors (WS-C C8).")
    a("//!")
    a("//! DO NOT EDIT BY HAND — regenerate with either path (both emit the")
    a("//! same values; see native/tests/lufs_golden_vectors.h for the full")
    a("//! provenance, signal recipes and epsilon documentation):")
    a("//!")
    a("//!   * `python3 scripts/generate_lufs_golden_vectors.py` — the canonical")
    a("//!     algorithm (`rust/crates/streamify-audio/src/lufs.rs`, designated")
    a("//!     canonical by C8) mirrored op-for-op in Python f64;")
    a("//!   * `cargo run -p streamify-audio --example lufs_golden_vectors` — the")
    a("//!     canonical implementation itself, on a host with a Rust toolchain.")
    a("//!")
    a("//! This module is the Rust side of the C8 three-way pin: the shared")
    a("//! truth consumed by `tests/test_lufs_golden_vectors.rs` (the CI drift")
    a("//! gate, epsilon 0.01 LUFS) and mirrored value-for-value in")
    a("//! `native/tests/lufs_golden_vectors.h` for the C++ lane")
    a("//! (`native/tests/test_lufs_rust_parity.cc`, CMake target")
    a("//! `lufs_parity_test_suite`).")
    a("//!")
    a("//! NOTE: cargo also compiles this file standalone as a zero-test")
    a("//! integration-test crate — harmless; it exists to be `mod`-included")
    a("//! by `test_lufs_golden_vectors.rs`.")
    a("")
    a("// Full-precision golden literals are the point of this fixture: the")
    a("// %.17g spellings round-trip the EXACT canonical f64 values and are")
    a("// diffed value-for-value against native/tests/lufs_golden_vectors.h.")
    a("// The lints that police hand-written float literals (approx_constant,")
    a("// excessive_precision) fight that intent on generated truth.")
    a("#![allow(clippy::approx_constant, clippy::excessive_precision)]")
    a("")
    a("// ---- Generation parameters (single source for both lanes; mirrored in")
    a("// native/tests/lufs_golden_vectors.h) ------------------------------------")
    a("")
    a("/// Canonical measurement rate (Hz).")
    a("pub const SAMPLE_RATE_HZ: u32 = 48_000;")
    a("/// 2*pi as f64 (bit-identical to `std::f64::consts::TAU`).")
    a("pub const TWO_PI: f64 = 6.283185307179586;")
    a("/// Sine frequencies (Hz).")
    a("pub const FREQ_997_HZ: f64 = 997.0;")
    a("pub const FREQ_440_HZ: f64 = 440.0;")
    a("pub const FREQ_250_HZ: f64 = 250.0;")
    a("/// 10^(-23/20) — the BS.1770 calibration amplitude (%.17g literal).")
    a("pub const AMP_M23_DBFS: f64 = 0.070794578438413802;")
    a("/// Just below digital full scale (avoids clip debates).")
    a("pub const AMP_NEAR_FS: f64 = 0.999;")
    a("/// 10^(-20/20).")
    a("pub const AMP_M20_DBFS: f64 = 0.1;")
    a("/// 10^(-40/20) — exercises the RLB high-pass attenuation.")
    a("pub const AMP_M40_DBFS: f64 = 0.01;")
    a("/// 1 s of leading silence @ 48 kHz (the gated fade-in vector).")
    a("pub const SILENCE_LEAD_FRAMES: usize = 48_000;")
    a("/// xorshift64* stream seed (nonzero).")
    a("pub const NOISE_SEED: u64 = 0x9E37_79B9_7F4A_7C15;")
    a("/// xorshift64* multiplier.")
    a("pub const NOISE_MULTIPLIER: u64 = 0x2545_F491_4F6C_DD1D;")
    a("/// 2^63 — maps the u64 state to [-1, 1).")
    a("pub const NOISE_SCALE: f64 = 9_223_372_036_854_775_808.0;")
    a("")
    a("/// The golden vectors: `(name, channels, sample_count, expected_lufs)`.")
    a("/// `channels == 1` means L-only mono — the canonical analyzer receives")
    a("/// it on L with R silent, matching the C++ lane's `num_channels = 1`.")
    a("pub const VECTORS: &[(&str, u32, usize, f64)] = &[")
    for name, ch, count, expected in results:
        a('    ("%s", %d, %d, %s),' % (name, ch, count, g17(expected)))
    a("];")
    a("")
    return "\n".join(lines)


def main() -> None:
    vectors = build_vectors()
    results = []
    for name, ch, count, chans in vectors:
        expected = integrated_lufs(chans)
        assert expected == expected and abs(expected) < 1e6, name  # finite
        results.append((name, ch, count, expected))
        print(
            "  %-40s ch=%d n=%6d  expected = %.17g LUFS"
            % (name, ch, count, expected)
        )

    epsilon, max_delta = cpp_epsilon_and_max_delta()
    header = render_header(results, epsilon, max_delta)
    module = render_rust_module(results)

    OUT_CPP.parent.mkdir(parents=True, exist_ok=True)
    OUT_RS.parent.mkdir(parents=True, exist_ok=True)
    OUT_CPP.write_text(header, encoding="utf-8")
    OUT_RS.write_text(module, encoding="utf-8")
    print()
    print("  wrote %s" % OUT_CPP.relative_to(REPO))
    print("  wrote %s" % OUT_RS.relative_to(REPO))
    print(
        "  C++ lane epsilon: %.17g (policy: clamp(max(4*max_delta, 0.02), "
        "<= 0.15); max observed delta: %s)"
        % (epsilon, "unmeasured" if max_delta is None else "%.3e" % max_delta)
    )


if __name__ == "__main__":
    main()
