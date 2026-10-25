//! test_stress_benchmarks.rs — Phase 5 stress benchmarks & performance
//! regression guards (feat/phase5-rust-gesture-physics-waveform,
//! directive §2.1/§2.4).
//!
//! The `#[ignore]`-marked stress gates below enforce the directive's
//! performance guarantees. Run them explicitly with:
//!
//! ```text
//! cargo test --release --test test_stress_benchmarks -- --ignored --nocapture
//! ```
//!
//! (The repo `[profile.test]` also carries `opt-level = 2`, so
//! `cargo test -- --ignored` measures near-release float math as
//! well.) The two non-ignored smoke tests run in every `cargo test`
//! pass with deliberately loose ceilings — they are regression guards
//! against accidental O(n²) growth or hot-path allocations, not
//! precision instruments.
//!
//! All timings use `std::time::Instant` wall-clock averaging over
//! large trial counts; medians would be more robust but averages keep
//! the harness allocation-free and good enough for 10-1000× headroom
//! assertions.

use std::time::Instant;

use streamify_core_rs::gesture_physics::{
    fling_landing, spring_trajectory, SpringSpec, FRAME_INTERVAL_120HZ,
};
use streamify_core_rs::waveform_indexer::{
    decode_cache_view, encode_cache, generate_waveform, DEFAULT_SAMPLE_RATE_HZ,
};

/// Trial count for the trajectory micro-benchmark.
const TRAJECTORY_TRIALS: usize = 10_000;
/// Frames per trajectory (1 s of animation at 120 Hz).
const FRAMES: usize = 120;

fn sine_pcm(samples: usize, amp: f32, freq: f32, rate: f32) -> Vec<u8> {
    let mut v = Vec::with_capacity(samples * 2);
    for i in 0..samples {
        let s = (2.0 * core::f32::consts::PI * freq * i as f32 / rate).sin() * amp;
        v.extend_from_slice(&(s as i16).to_le_bytes());
    }
    v
}

// ─────────────────────── stress gates (explicit run)

#[test]
#[ignore = "stress gate: cargo test --release --test test_stress_benchmarks -- --ignored"]
fn spring_trajectory_120_frames_meets_5us_budget() {
    // Directive §2.1: solve full 120-frame trajectories in < 5 µs,
    // zero allocations on the hot path. Averaged over 10k solves that
    // cycle through all three damping regimes and realistic gesture
    // geometries.
    let specs = [
        SpringSpec::new(170.0, 0.8, 1.0).expect("spec"),
        SpringSpec::new(300.0, 1.0, 1.0).expect("spec"),
        SpringSpec::new(120.0, 1.6, 1.0).expect("spec"),
    ];
    let mut out = [0f32; FRAMES];
    let start = Instant::now();
    for i in 0..TRAJECTORY_TRIALS {
        let spec = &specs[i % specs.len()];
        spring_trajectory(
            spec,
            (i % 900) as f32,
            512.0,
            ((i % 1800) as f32) - 900.0,
            FRAME_INTERVAL_120HZ,
            &mut out,
        );
        std::hint::black_box(&out);
    }
    let per_solve_ns = start.elapsed().as_nanos() as f64 / TRAJECTORY_TRIALS as f64;
    let per_solve_us = per_solve_ns / 1000.0;
    println!(
        "120-frame trajectory: {per_solve_us:.3} µs/solve over {TRAJECTORY_TRIALS} trials \
         ({:.1}× headroom vs the 5 µs budget)",
        5.0 / per_solve_us
    );
    assert!(
        per_solve_us < 5.0,
        "trajectory solve took {per_solve_us:.3} µs — over the 5 µs budget"
    );
}

#[test]
#[ignore = "stress gate: cargo test --release --test test_stress_benchmarks -- --ignored"]
fn fling_landing_one_million_projections() {
    // 1M projections sweeping velocity 0 .. 40,000 px/s across
    // in-bounds and over-scroll geometries.
    let start = Instant::now();
    const N: usize = 1_000_000;
    let mut acc = 0.0f32;
    for i in 0..N {
        let v = (i % 40_000) as f32 - 10_000.0;
        let landing = fling_landing(4_500.0, v, -2_000.0, 8_000.0, 1.7).expect("valid");
        acc += landing;
    }
    std::hint::black_box(acc);
    let per_call_ns = start.elapsed().as_nanos() as f64 / N as f64;
    println!("fling_landing: {per_call_ns:.1} ns/projection over {N} trials");
    assert!(
        per_call_ns < 1_000.0,
        "fling projection took {per_call_ns:.1} ns"
    );
}

#[test]
#[ignore = "stress gate: cargo test --release --test test_stress_benchmarks -- --ignored"]
fn waveform_generation_10s_track_under_20ms() {
    // 10 s of 44.1 kHz PCM (882 KB) → 500 buckets, averaged over 20
    // generations: the pre-buffering cache path must stay snappy.
    let pcm = sine_pcm(441_000, 29_000.0, 432.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let start = Instant::now();
    const N: usize = 20;
    for _ in 0..N {
        let bars = generate_waveform(&pcm, 500).expect("generate");
        std::hint::black_box(&bars);
    }
    let per_call_ms = start.elapsed().as_nanos() as f64 / N as f64 / 1.0e6;
    println!("waveform generation (10 s track, 500 buckets): {per_call_ms:.2} ms/call");
    assert!(
        per_call_ms < 20.0,
        "waveform generation took {per_call_ms:.2} ms/call"
    );
}

#[test]
#[ignore = "stress gate: cargo test --release --test test_stress_benchmarks -- --ignored"]
fn cache_roundtrip_one_thousand_cycles() {
    let amps = vec![128u8; 500];
    let blob = encode_cache(180_000, &amps).expect("encode");
    let start = Instant::now();
    for _ in 0..1_000 {
        let view = decode_cache_view(&blob).expect("decode");
        std::hint::black_box(view.amplitudes);
    }
    let per_cycle_us = start.elapsed().as_nanos() as f64 / 1_000.0 / 1000.0;
    println!("cache decode view (500 buckets): {per_cycle_us:.2} µs/cycle");
    assert!(
        per_cycle_us < 100.0,
        "cache decode took {per_cycle_us:.2} µs"
    );
}

// ─────────────────────── regression smoke (every cargo test run)

#[test]
fn spring_trajectory_regression_smoke() {
    // Loose ceiling: catches O(n²) growth or accidental hot-path
    // allocation; the real gate is the 5 µs stress test above.
    let spec = SpringSpec::new(170.0, 0.8, 1.0).expect("spec");
    let mut out = [0f32; FRAMES];
    let start = Instant::now();
    for _ in 0..1_000 {
        spring_trajectory(&spec, 0.0, 512.0, 850.0, FRAME_INTERVAL_120HZ, &mut out);
    }
    std::hint::black_box(&out);
    let per_solve_us = start.elapsed().as_nanos() as f64 / 1_000.0 / 1000.0;
    assert!(
        per_solve_us < 100.0,
        "trajectory smoke ceiling exceeded: {per_solve_us:.1} µs/solve"
    );
}

#[test]
fn waveform_generation_regression_smoke() {
    // 1 s of PCM → 200 buckets in every test run.
    let pcm = sine_pcm(44_100, 29_000.0, 432.0, DEFAULT_SAMPLE_RATE_HZ as f32);
    let start = Instant::now();
    for _ in 0..10 {
        let bars = generate_waveform(&pcm, 200).expect("generate");
        std::hint::black_box(&bars);
    }
    let per_call_ms = start.elapsed().as_nanos() as f64 / 10.0 / 1.0e6;
    assert!(
        per_call_ms < 100.0,
        "waveform smoke ceiling exceeded: {per_call_ms:.1} ms/call"
    );
}
