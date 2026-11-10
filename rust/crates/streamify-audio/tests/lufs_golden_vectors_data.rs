//! lufs_golden_vectors_data.rs — GENERATED LUFS golden vectors (WS-C C8).
//!
//! DO NOT EDIT BY HAND — regenerate with either path (both emit the
//! same values; see native/tests/lufs_golden_vectors.h for the full
//! provenance, signal recipes and epsilon documentation):
//!
//!   * `python3 scripts/generate_lufs_golden_vectors.py` — the canonical
//!     algorithm (`rust/crates/streamify-audio/src/lufs.rs`, designated
//!     canonical by C8) mirrored op-for-op in Python f64;
//!   * `cargo run -p streamify-audio --example lufs_golden_vectors` — the
//!     canonical implementation itself, on a host with a Rust toolchain.
//!
//! This module is the Rust side of the C8 three-way pin: the shared
//! truth consumed by `tests/test_lufs_golden_vectors.rs` (the CI drift
//! gate, epsilon 0.01 LUFS) and mirrored value-for-value in
//! `native/tests/lufs_golden_vectors.h` for the C++ lane
//! (`native/tests/test_lufs_rust_parity.cc`, CMake target
//! `lufs_parity_test_suite`).
//!
//! NOTE: cargo also compiles this file standalone as a zero-test
//! integration-test crate — harmless; it exists to be `mod`-included
//! by `test_lufs_golden_vectors.rs`.

// ---- Generation parameters (single source for both lanes; mirrored in
// native/tests/lufs_golden_vectors.h) ------------------------------------

/// Canonical measurement rate (Hz).
pub const SAMPLE_RATE_HZ: u32 = 48_000;
/// 2*pi as f64 (bit-identical to `std::f64::consts::TAU`).
pub const TWO_PI: f64 = 6.283185307179586;
/// Sine frequencies (Hz).
pub const FREQ_997_HZ: f64 = 997.0;
pub const FREQ_440_HZ: f64 = 440.0;
pub const FREQ_250_HZ: f64 = 250.0;
/// 10^(-23/20) — the BS.1770 calibration amplitude (%.17g literal).
pub const AMP_M23_DBFS: f64 = 0.070794578438413802;
/// Just below digital full scale (avoids clip debates).
pub const AMP_NEAR_FS: f64 = 0.999;
/// 10^(-20/20).
pub const AMP_M20_DBFS: f64 = 0.1;
/// 10^(-40/20) — exercises the RLB high-pass attenuation.
pub const AMP_M40_DBFS: f64 = 0.01;
/// 1 s of leading silence @ 48 kHz (the gated fade-in vector).
pub const SILENCE_LEAD_FRAMES: usize = 48_000;
/// xorshift64* stream seed (nonzero).
pub const NOISE_SEED: u64 = 0x9E37_79B9_7F4A_7C15;
/// xorshift64* multiplier.
pub const NOISE_MULTIPLIER: u64 = 0x2545_F491_4F6C_DD1D;
/// 2^63 — maps the u64 state to [-1, 1).
pub const NOISE_SCALE: f64 = 9_223_372_036_854_775_808.0;

/// The golden vectors: `(name, channels, sample_count, expected_lufs)`.
/// `channels == 1` means L-only mono — the canonical analyzer receives
/// it on L with R silent, matching the C++ lane's `num_channels = 1`.
pub const VECTORS: &[(&str, u32, usize, f64)] = &[
    ("sine_997Hz_m23dBFS_mono_3s", 1, 144000, -26.010264522411571),
    ("sine_997Hz_m23dBFS_stereo_3s", 2, 144000, -22.999964565771759),
    ("sine_440Hz_0dBFS_stereo_2s", 2, 96000, -0.69161896388678334),
    ("xorshift_noise_stereo_5s", 2, 240000, 1.3811307394272201),
    ("silence_1s_then_sine_m20dBFS_stereo_2s", 2, 144000, -21.021778828615108),
    ("sine_250Hz_m40dBFS_stereo_3s", 2, 144000, -40.844145162533991),
];
