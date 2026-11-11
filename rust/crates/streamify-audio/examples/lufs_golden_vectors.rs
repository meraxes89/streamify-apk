//! lufs_golden_vectors.rs — the CANONICAL LUFS golden-vector regenerator
//! (WS-C task C8; runs the REAL `LufsAnalyzer`, not a mirror).
//!
//! ```sh
//! cargo run -p streamify-audio --example lufs_golden_vectors \
//!     > native/tests/lufs_golden_vectors.h
//! ```
//!
//! Why this exists: C8 designated `streamify-audio/src/lufs.rs` the
//! canonical BS.1770-4 implementation and pinned the C++ twin
//! (`native/dsp/LufsNormalizer.cc`) to its golden vectors. The checked-in
//! fixture (`native/tests/lufs_golden_vectors.h` +
//! `tests/lufs_golden_vectors_data.rs`) is the shared truth consumed by
//! BOTH drift gates:
//!
//!   * Rust lane: `tests/test_lufs_golden_vectors.rs` — this crate's own
//!     analyzer vs the fixture (epsilon 0.01 LUFS; only cross-libm
//!     last-ulp noise can remain).
//!   * C++ lane: `native/tests/test_lufs_rust_parity.cc` (CMake target
//!     `lufs_parity_test_suite`) — `computeIntegratedLufs` vs the same
//!     fixture (epsilon 0.02; documented f32-coefficient/f32-sample
//!     residual sources).
//!
//! The offline mirror `scripts/generate_lufs_golden_vectors.py` (Python
//! f64, op-for-op the same algorithm) produced the checked-in values on
//! hosts without a Rust toolchain; this example is the authoritative
//! regeneration path and prints the identical C++ header format (same
//! %.17g value spelling, same table padding — see `g17` below), so the
//! two producers can never silently drift apart: regenerate with this
//! command and `git diff` shows exactly the canonical truth.
//!
//! Signal recipes, generation parameters and the epsilon policy are
//! documented in the emitted header; every signal is quantized to f32
//! BEFORE measurement (exactly what both lanes receive in production).

// Full-precision literals mirror the generated fixture (same %.17g
// spelling as lufs_golden_vectors_data.rs / the C++ header) so the three
// artifacts stay diffable — the float-literal lints fight that intent.
#![allow(clippy::excessive_precision)]

use std::io::{BufWriter, Write};

use streamify_audio::lufs::LufsAnalyzer;

// ---- generation parameters (mirror of lufs_golden_vectors_data.rs) ----------

const SAMPLE_RATE_HZ: f64 = 48_000.0;
const TAU: f64 = std::f64::consts::TAU;
const FREQ_997_HZ: f64 = 997.0;
const FREQ_440_HZ: f64 = 440.0;
const FREQ_250_HZ: f64 = 250.0;
const AMP_M23_DBFS: f64 = 0.070_794_578_438_413_802; // 10^(-23/20)
const AMP_NEAR_FS: f64 = 0.999;
const AMP_M20_DBFS: f64 = 0.1;
const AMP_M40_DBFS: f64 = 0.01;
const SILENCE_LEAD_FRAMES: usize = 48_000;
const NOISE_SEED: u64 = 0x9E37_79B9_7F4A_7C15;
const NOISE_MULTIPLIER: u64 = 0x2545_F491_4F6C_DD1D;
const NOISE_SCALE: f64 = 9_223_372_036_854_775_808.0; // 2^63

/// One measurement: (recipe key, channels, frames, measured integrated).
struct Measured {
    name: &'static str,
    channels: u32,
    frames: usize,
    lufs: f64,
}

/// C's `%.17g` for the fixture's value regime (fixed notation, nonzero,
/// |x| well inside [1e-4, 1e15)): 17 significant digits with trailing
/// zeros trimmed — the exact spelling the Python generator emits, so a
/// regeneration diff is value-only.
fn g17(x: f64) -> String {
    if x == 0.0 {
        return "0".to_string();
    }
    let exp = x.abs().log10().floor() as i32;
    let decimals = (16 - exp).clamp(0, 17) as usize;
    let mut s = format!("{x:.decimals$}");
    if s.contains('.') {
        while s.ends_with('0') {
            s.pop();
        }
        if s.ends_with('.') {
            s.pop();
        }
    }
    s
}

/// The canonical measurement: f32-quantized interleaved stereo in, gated
/// integrated LUFS out. `mono` routes the signal to L with R silent
/// (the documented mono convention — a silent R adds nothing to the
/// block sum because both channel weights are 1.0).
fn measure(interleaved: &[f32]) -> f64 {
    let mut analyzer = LufsAnalyzer::new().expect("canonical 48 kHz geometry");
    analyzer.process(interleaved);
    analyzer.integrated_lufs()
}

fn sine_stereo(frames: usize, freq: f64, amp: f64, mono: bool) -> Vec<f32> {
    let mut out = Vec::with_capacity(frames * 2);
    for n in 0..frames {
        let x = (amp * (TAU * freq * n as f64 / SAMPLE_RATE_HZ).sin()) as f32;
        out.push(x);
        out.push(if mono { 0.0 } else { x });
    }
    out
}

fn silence_then_sine_stereo(
    silence_frames: usize,
    tone_frames: usize,
    freq: f64,
    amp: f64,
) -> Vec<f32> {
    let mut out = Vec::with_capacity((silence_frames + tone_frames) * 2);
    for _ in 0..silence_frames {
        out.push(0.0);
        out.push(0.0);
    }
    for n in 0..tone_frames {
        let x = (amp * (TAU * freq * n as f64 / SAMPLE_RATE_HZ).sin()) as f32;
        out.push(x);
        out.push(x);
    }
    out
}

fn xorshift_noise_stereo(frames: usize) -> Vec<f32> {
    // ONE u64 xorshift64* stream; L and R consume ALTERNATE steps
    // (bit-identical in Python/Rust/C++ — pure integer math).
    let mut s = NOISE_SEED;
    let mut next = || {
        s ^= s >> 12;
        s ^= s << 25;
        s ^= s >> 27;
        (s.wrapping_mul(NOISE_MULTIPLIER) as f64 / NOISE_SCALE - 1.0) as f32
    };
    let mut out = Vec::with_capacity(frames * 2);
    for _ in 0..frames {
        out.push(next());
        out.push(next());
    }
    out
}

fn main() {
    // ---- measure every recipe through the REAL canonical analyzer -------
    let measured: Vec<Measured> = vec![
        Measured {
            name: "sine_997Hz_m23dBFS_mono_3s",
            channels: 1,
            frames: 144_000,
            lufs: measure(&sine_stereo(144_000, FREQ_997_HZ, AMP_M23_DBFS, true)),
        },
        Measured {
            name: "sine_997Hz_m23dBFS_stereo_3s",
            channels: 2,
            frames: 144_000,
            lufs: measure(&sine_stereo(144_000, FREQ_997_HZ, AMP_M23_DBFS, false)),
        },
        Measured {
            name: "sine_440Hz_0dBFS_stereo_2s",
            channels: 2,
            frames: 96_000,
            lufs: measure(&sine_stereo(96_000, FREQ_440_HZ, AMP_NEAR_FS, false)),
        },
        Measured {
            name: "xorshift_noise_stereo_5s",
            channels: 2,
            frames: 240_000,
            lufs: measure(&xorshift_noise_stereo(240_000)),
        },
        Measured {
            name: "silence_1s_then_sine_m20dBFS_stereo_2s",
            channels: 2,
            frames: 144_000,
            lufs: measure(&silence_then_sine_stereo(
                SILENCE_LEAD_FRAMES,
                96_000,
                FREQ_440_HZ,
                AMP_M20_DBFS,
            )),
        },
        Measured {
            name: "sine_250Hz_m40dBFS_stereo_3s",
            channels: 2,
            frames: 144_000,
            lufs: measure(&sine_stereo(144_000, FREQ_250_HZ, AMP_M40_DBFS, false)),
        },
    ];

    // ---- emit the C++ header (format-matched to the checked-in fixture) --
    let stdout = std::io::stdout();
    let mut out = BufWriter::new(stdout.lock());
    // `mut`: the closure captures `out` by mutable reference (writeln! takes
    // &mut), so every call re-borrows it. The measured-row loop below must
    // route through the SAME closure (format! first, then w(&row)) — a bare
    // writeln!(out, ..) there would be a second live mutable borrow of `out`
    // while `w` is still used afterwards (E0499).
    let mut w = |s: &str| writeln!(out, "{s}");

    for line in [
        "// ============================================================================",
        "//  lufs_golden_vectors.h — GENERATED LUFS golden vectors (WS-C task C8)",
        "// ============================================================================",
        "//",
        "//  DO NOT EDIT BY HAND. Regenerate with either path:",
        "//",
        "//    python3 scripts/generate_lufs_golden_vectors.py",
        "//        The CANONICAL algorithm (rust/crates/streamify-audio/src/lufs.rs,",
        "//        designated canonical by C8) mirrored op-for-op in Python f64.",
        "//",
        "//    cargo run -p streamify-audio --example lufs_golden_vectors \\",
        "//        > native/tests/lufs_golden_vectors.h",
        "//        The canonical implementation ITSELF (the real LufsAnalyzer) on",
        "//        a host with a Rust toolchain — prints this same format.",
        "//",
        "//  The two paths agree to << 1e-9 LUFS: the only cross-libm difference",
        "//  on the sine vectors is the last ulp of f64 `sin`, whose effect on a",
        "//  ~1e5-sample integrated measurement is orders of magnitude inside",
        "//  every epsilon below. The noise vector is pure 64-bit integer math",
        "//  and bit-identical on every host.",
        "//",
        "//  CONSUMERS — the C8 three-way pin (one fixture, two implementations):",
        "//    * C++ lane:  native/tests/test_lufs_rust_parity.cc (CMake target",
        "//      lufs_parity_test_suite) regenerates every signal, measures it",
        "//      with native/dsp/LufsNormalizer.cc computeIntegratedLufs and",
        "//      asserts |measured - expected| <= kLufsParityEpsilon.",
        "//    * Rust lane: rust/crates/streamify-audio/tests/test_lufs_golden_",
        "//      vectors.rs regenerates every signal, measures it with the REAL",
        "//      canonical streamify_audio::lufs::LufsAnalyzer and asserts",
        "//      |measured - expected| <= 0.01 LUFS (CI drift gate).",
        "//",
        "//  SIGNAL SET (48 kHz; every signal is quantized to f32 BEFORE",
        "//  measurement — exactly what both lanes receive):",
        "//    sine_997Hz_m23dBFS_mono_3s             1ch  A*sin(2*pi*997*n/48000),",
        "//                                                A = 10^(-23/20), L only",
        "//    sine_997Hz_m23dBFS_stereo_3s           2ch  the mono signal above,",
        "//                                                L = R",
        "//    sine_440Hz_0dBFS_stereo_2s             2ch  A = 0.999 (just below",
        "//                                                full scale), 440 Hz",
        "//    xorshift_noise_stereo_5s               2ch  ONE xorshift64* stream",
        "//                                                (seed 0x9E3779B97F4A7C15),",
        "//                                                L and R consuming",
        "//                                                ALTERNATE steps",
        "//    silence_1s_then_sine_m20dBFS_stereo_2s 2ch  48000 exact-zero frames,",
        "//                                                then 2 s of",
        "//                                                A = 10^(-20/20) @ 440 Hz",
        "//                                                (exercises the absolute",
        "//                                                gate with real survivors)",
        "//    sine_250Hz_m40dBFS_stereo_3s           2ch  A = 10^(-40/20) @ 250 Hz",
        "//                                                (below the RLB corner)",
        "//",
        "//  EPSILON POLICY (C++ lane): kLufsParityEpsilon =",
        "//      clamp(max(4 * max_observed_delta, 0.02), <= 0.15).",
        "//  Residual delta sources: the C++ engine's f32 BiquadCoeffs storage",
        "//  (the canonical lane keeps f64 spec literals), its f32 sample API",
        "//  (float*) and its f32 return value.",
        "//  The Rust lane's epsilon is 0.01: it re-runs the SAME implementation",
        "//  the fixture was derived from, so only libm last-ulp noise remains.",
        "// ============================================================================",
        "",
        "#pragma once",
        "",
        "#include <cstddef>",
        "#include <cstdint>",
        "",
        "// ---- Generation parameters (single source for both lanes; mirrored in",
        "// rust/crates/streamify-audio/tests/lufs_golden_vectors_data.rs) ---------",
        "",
        "constexpr unsigned kLufsGoldenRateHz = 48000;             // canonical rate",
        "constexpr double kLufsGoldenTau = 6.283185307179586;      // 2*pi (f64)",
        "constexpr double kLufsFreq997Hz = 997.0;",
        "constexpr double kLufsFreq440Hz = 440.0;",
        "constexpr double kLufsFreq250Hz = 250.0;",
        "constexpr double kLufsAmpM23Dbfs = 0.070794578438413802;  // 10^(-23/20)",
        "constexpr double kLufsAmpNearFs = 0.999;                  // just below FS",
        "constexpr double kLufsAmpM20Dbfs = 0.1;                   // 10^(-20/20)",
        "constexpr double kLufsAmpM40Dbfs = 0.01;                  // 10^(-40/20)",
        "constexpr size_t kLufsSilenceLeadFrames = 48000;          // 1 s @ 48 kHz",
        "constexpr uint64_t kLufsNoiseSeed = 0x9E3779B97F4A7C15ull;",
        "constexpr uint64_t kLufsNoiseMultiplier = 0x2545F4914F6CDD1Dull;  // xrs64*",
        "constexpr double kLufsNoiseScale = 9223372036854775808.0; // 2^63",
        "",
        "// ---- The vectors ----------------------------------------------------------",
        "",
        "struct LufsGoldenVector {",
        "    const char* name;       // recipe key (see the signal set above)",
        "    unsigned channels;      // 1 = L-only mono, 2 = L/R stereo",
        "    size_t sample_count;    // frames per channel @ 48 kHz",
        "    double expected_lufs;   // canonical integrated gated LUFS (f64)",
        "};",
        "",
        "constexpr LufsGoldenVector kLufsGoldenVectors[] = {",
    ] {
        let _ = w(line);
    }
    for m in &measured {
        // Quoted name padded OUTSIDE the quotes to 40 columns, frame count
        // right-aligned to 6 — the Python generator's exact spelling:
        //     {"name..."                              , ch, frames, value},
        // (formatted before the closure call so `out` is only ever borrowed
        // through `w` — see the comment at the closure definition.)
        let row = format!(
            "    {{{:<40}, {}, {:>6}, {}}},",
            format!("\"{}\"", m.name),
            m.channels,
            m.frames,
            g17(m.lufs)
        );
        let _ = w(&row);
    }
    for line in [
        "};",
        "",
        "constexpr size_t kLufsGoldenVectorCount =",
        "    sizeof(kLufsGoldenVectors) / sizeof(kLufsGoldenVectors[0]);",
        "",
        "// C++ parity-lane comparison bound — derivation in the comment above.",
        "constexpr double kLufsParityEpsilon = 0.02;",
        "",
    ] {
        let _ = w(line);
    }

    out.flush().expect("stdout flush");
    eprintln!(
        "lufs_golden_vectors: {} vector(s) measured through the canonical \
         LufsAnalyzer; header written to stdout",
        measured.len()
    );
}
