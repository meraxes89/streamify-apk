//! VirtualHAL integration suite — Phase 4a / MP-HAL01 + MP-HAL02
//! (rust/tests/test_virtual_hal.rs).
//!
//! Verifies the deterministic adversarial audio test double end-to-end:
//!
//! * config validation (chunk/rate/channel/jitter bounds),
//! * the three canonical chunk sizes (256/512/1024) with quantum-exact
//!   pulls and EXACT clock advancement (frames AND nanoseconds),
//! * steady-stream ramp integrity and frame conservation,
//! * underrun injection (digital-silence tails, position advances
//!   through silence, telemetry accounting),
//! * overrun injection (flood short-writes, overrun counters,
//!   conservation, zero dropped frames),
//! * mid-stream rate reconfiguration (teardown/rebuild epoch semantics),
//! * the DacPresentationClock interpolation acceptance gate (< 5 ms)
//!   under ±10 % jitter, a 1 ms timestamp grid and 200 ppm drift — plus
//!   the fire-time NEGATIVE CONTROL proving the bound has teeth,
//! * the full adversarial scenario (every mode, every chunk size),
//! * the platform-agnostic [`AudioHalConsumer`] interface polymorphism,
//! * seed determinism (identical run on identical seed).
//!
//! The pure-Rust twin of native/tests/test_virtual_hal.cpp — both suites
//! drive the same clock model against their respective data planes.

use streamify_core::DacPresentationClock;
use streamify_audio::virtual_hal::{
    AdversarialModes, AdversarialProfile, AudioHalConsumer, DacClockInterpolator,
    VirtualHalConfig, VirtualHalSink, INTERPOLATION_ACCEPTANCE_MS,
};

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

/// Deterministic stereo ramp: value depends only on the absolute frame
/// index, so sequence integrity is checkable after any pull pattern.
fn ramp(frame: u64) -> f32 {
    ((frame % 4096) as f64 / 4095.0 * 2.0 - 1.0) as f32
}

fn fill_ramp(buf: &mut Vec<f32>, first_frame: u64, frames: usize) {
    buf.resize(frames * 2, 0.0);
    for f in 0..frames {
        buf[f * 2] = ramp(first_frame + f as u64);
        buf[f * 2 + 1] = -ramp(first_frame + f as u64);
    }
}

fn config(chunk: usize) -> VirtualHalConfig {
    VirtualHalConfig {
        callback_frames: chunk,
        ..VirtualHalConfig::default()
    }
}

// ---------------------------------------------------------------------------
// A. Config validation
// ---------------------------------------------------------------------------

#[test]
fn config_validation_rejects_garbage() {
    for chunk in [0usize, 128, 768, 2048] {
        assert!(
            VirtualHalSink::new(config(chunk)).is_err(),
            "non-canonical chunk {chunk} rejected"
        );
    }
    for chunk in [256usize, 512, 1024] {
        assert!(
            VirtualHalSink::new(config(chunk)).is_ok(),
            "canonical chunk {chunk} accepted"
        );
    }
    for rate in [0u32, 7_999, 192_001] {
        let mut cfg = config(512);
        cfg.sample_rate = rate;
        assert!(VirtualHalSink::new(cfg).is_err(), "rate {rate} rejected");
    }
    for rate in [8_000u32, 44_100, 192_000] {
        let mut cfg = config(512);
        cfg.sample_rate = rate;
        assert!(VirtualHalSink::new(cfg).is_ok(), "rate {rate} accepted");
    }
    let mut cfg = config(512);
    cfg.channels = 3;
    assert!(VirtualHalSink::new(cfg).is_err(), "3 channels rejected");

    let mut cfg = config(512);
    cfg.capacity_frames = 16;
    assert!(VirtualHalSink::new(cfg).is_err(), "tiny capacity rejected");

    let mut cfg = config(512);
    cfg.jitter_fraction = 1.5;
    assert!(VirtualHalSink::new(cfg).is_err(), "jitter > 1.0 rejected");

    let mut cfg = config(512);
    cfg.jitter_fraction = f64::NAN;
    assert!(VirtualHalSink::new(cfg).is_err(), "NaN jitter rejected");

    let mut cfg = config(512);
    cfg.drift_ppm = 5000;
    assert!(VirtualHalSink::new(cfg).is_err(), "wild drift rejected");

    let mut cfg = config(512);
    cfg.timestamp_quantum_ns = 1_000_000_000;
    assert!(VirtualHalSink::new(cfg).is_err(), "huge timestamp grid rejected");
}

// ---------------------------------------------------------------------------
// B. Chunk sweep: quantum-exact pulls + exact clock advancement
// ---------------------------------------------------------------------------

#[test]
fn chunk_sweep_256_512_1024_exact_clock_and_data() {
    for chunk in [256usize, 512, 1024] {
        let mut sink = VirtualHalSink::new(VirtualHalConfig {
            callback_frames: chunk,
            capacity_frames: 16_384,
            ..VirtualHalConfig::default()
        })
        .unwrap();

        let mut feed = Vec::new();
        fill_ramp(&mut feed, 0, chunk * 8);
        let consumed = sink.write_producer_frames(&feed);
        assert_eq!(consumed, feed.len(), "feed fully consumed");

        let mut dst = vec![1.0f32; chunk * 2];
        let mut expect_frames = 0u64;
        for _ in 0..8 {
            let cb = sink.run_callback_into(&mut dst);
            assert_eq!(cb.frames_requested, chunk);
            assert_eq!(cb.frames_supplied, chunk);
            assert!(!cb.starved);
            assert_eq!(cb.clock.dac_frame_position, expect_frames);
            // EXACT timestamp advancement: chunk frames @ 48 kHz.
            assert_eq!(cb.clock.monotonic_ns, expect_frames * 1_000_000_000 / 48_000);
            expect_frames += chunk as u64;
        }

        // Data integrity: the LAST quantum's ramp came through untouched.
        for f in 0..chunk {
            let want_l = ramp((chunk * 7) as u64 + f as u64);
            let want_r = -want_l;
            assert_eq!(dst[f * 2], want_l, "chunk {chunk} L frame {f}");
            assert_eq!(dst[f * 2 + 1], want_r, "chunk {chunk} R frame {f}");
        }

        let st = sink.stats();
        assert_eq!(st.frames_written, (chunk * 8) as u64);
        assert_eq!(st.frames_pulled, (chunk * 8) as u64);
        assert_eq!(st.overrun_events, 0);
        assert_eq!(st.starved_callbacks, 0);
    }
}

// ---------------------------------------------------------------------------
// C. Steady stream: ramp integrity + conservation
// ---------------------------------------------------------------------------

#[test]
fn steady_stream_conserves_frames_and_signal() {
    let mut sink = VirtualHalSink::new(config(512)).unwrap();

    // Quantum-aligned total (90 x 512) and a matched 512-in/512-out
    // cadence after a half-capacity prefill: no overruns, no starvation.
    const TOTAL: usize = 46_080;
    let mut feed = Vec::new();
    fill_ramp(&mut feed, 0, 4096);
    let mut fed = 4096usize;
    assert_eq!(sink.write_producer_frames(&feed), feed.len());
    let mut pulled = 0u64;
    let mut dst = vec![0.0f32; 512 * 2];

    while (pulled as usize) < TOTAL {
        if fed < TOTAL {
            let n = (TOTAL - fed).min(512);
            fill_ramp(&mut feed, fed as u64, n);
            let consumed = sink.write_producer_frames(&feed[..n * 2]);
            assert_eq!(consumed, n * 2, "steady write always fits");
            fed += consumed / 2;
        }
        let cb = sink.run_callback_into(&mut dst);
        for f in 0..cb.frames_supplied {
            let idx = pulled + f as u64;
            assert_eq!(dst[f * 2], ramp(idx), "L intact at {idx}");
            assert_eq!(dst[f * 2 + 1], -ramp(idx), "R intact at {idx}");
        }
        pulled += cb.frames_supplied as u64;
    }

    let st = sink.stats();
    assert_eq!(pulled, st.frames_pulled);
    assert_eq!(st.frames_written, TOTAL as u64);
    assert_eq!(st.starved_callbacks, 0, "no starvation in steady state");
    assert_eq!(st.overrun_events, 0, "no overruns in steady state");
    assert_eq!(st.occupancy_frames, 0, "ring drained exactly");
    // Conservation: written == pulled + live + flushed (nothing dropped).
    assert_eq!(
        st.frames_written,
        st.frames_pulled + st.occupancy_frames as u64 + st.frames_flushed
    );
}

// ---------------------------------------------------------------------------
// D. Underrun injection
// ---------------------------------------------------------------------------

#[test]
fn underrun_injection_starves_consumer_with_digital_silence() {
    let mut sink = VirtualHalSink::new(config(512)).unwrap();

    let mut feed = Vec::new();
    fill_ramp(&mut feed, 0, 1024);
    sink.write_producer_frames(&feed);

    let mut dst = vec![1.0f32; 512 * 2];
    sink.run_callback_into(&mut dst);
    sink.run_callback_into(&mut dst);

    // Starve: three callbacks with an empty ring.
    let clock_before = sink.last_clock().dac_frame_position;
    for _ in 0..3 {
        let cb = sink.run_callback_into(&mut dst);
        assert!(cb.starved);
        assert_eq!(cb.frames_supplied, 0);
        assert!(dst.iter().all(|s| *s == 0.0), "starved quantum is silence");
    }
    // The DAC presents through the silence: position advanced 3 quanta.
    assert_eq!(
        sink.last_clock().dac_frame_position,
        clock_before + 3 * 512
    );

    let st = sink.stats();
    assert_eq!(st.starved_callbacks, 3);
    assert_eq!(st.frames_silenced, 3 * 512);

    // Producer resumes: the sequence continues exactly where it stopped.
    fill_ramp(&mut feed, 1024, 512);
    sink.write_producer_frames(&feed);
    let cb = sink.run_callback_into(&mut dst);
    assert!(!cb.starved);
    assert_eq!(dst[0], ramp(1024));
    assert_eq!(dst[1], -ramp(1024));
}

// ---------------------------------------------------------------------------
// E. Overrun injection
// ---------------------------------------------------------------------------

#[test]
fn overrun_injection_floods_ringbuffer_with_short_writes() {
    let mut sink = VirtualHalSink::new(VirtualHalConfig {
        capacity_frames: 4096,
        ..config(512)
    })
    .unwrap();

    let mut feed = Vec::new();
    fill_ramp(&mut feed, 0, 4096);
    let consumed = sink.write_producer_frames(&feed);
    assert_eq!(consumed, feed.len(), "ring filled to capacity");

    // Flood: strictly more than the ring can hold.
    fill_ramp(&mut feed, 4096, 4096 + 512);
    let consumed = sink.write_producer_frames(&feed);
    assert_eq!(consumed, 0, "flood fully rejected (ring full)");

    let st = sink.stats();
    assert_eq!(st.overrun_events, 1, "flood counted as overrun");
    assert_eq!(st.occupancy_frames, 4096, "occupancy pinned at capacity");

    // Drain one callback, then the next flood partially commits.
    let mut dst = vec![0.0f32; 512 * 2];
    sink.run_callback_into(&mut dst);
    fill_ramp(&mut feed, 0, 2048);
    let consumed = sink.write_producer_frames(&feed);
    assert_eq!(consumed, 512 * 2, "partial commit after draining");

    let st = sink.stats();
    assert_eq!(st.overrun_events, 2, "second short write counted");
    assert_eq!(
        st.frames_written,
        st.frames_pulled + st.occupancy_frames as u64 + st.frames_flushed,
        "conservation holds under overrun"
    );
}

// ---------------------------------------------------------------------------
// F. Rate reconfiguration
// ---------------------------------------------------------------------------

#[test]
fn rate_reconfiguration_resets_epoch_and_flushes() {
    let mut sink = VirtualHalSink::new(config(512)).unwrap();

    let mut feed = Vec::new();
    fill_ramp(&mut feed, 0, 2048);
    sink.write_producer_frames(&feed);
    let mut dst = vec![0.0f32; 512 * 2];
    sink.run_callback_into(&mut dst);
    let epoch_before = sink.stats().epoch;
    let time_before = sink.sim_time_ns();

    assert!(sink.reconfigure_rate(44_100));
    let st = sink.stats();
    assert_eq!(st.epoch, epoch_before + 1, "epoch advanced");
    assert_eq!(sink.sample_rate(), 44_100);
    assert_eq!(sink.sim_time_ns(), time_before, "timeline continues");
    assert_eq!(st.occupancy_frames, 0, "ring flushed by teardown/rebuild");
    assert_eq!(st.frames_flushed, 2048 - 512, "flushed accounting exact");

    // The next callback reports the new-rate epoch from frame 0.
    fill_ramp(&mut feed, 0, 512);
    sink.write_producer_frames(&feed);
    let cb = sink.run_callback_into(&mut dst);
    assert_eq!(cb.epoch, epoch_before + 1);
    assert_eq!(cb.clock.dac_frame_position, 0, "frame counter restarted");
    assert_eq!(cb.clock.sample_rate, 44_100, "clock sample carries new rate");
    assert!(cb.clock.monotonic_ns >= time_before);
    // 512 frames @44.1k = 11.61 ms > the 48k quantum (10.67 ms).
    assert!(sink.sim_time_ns() - time_before > 11_000_000);

    assert!(!sink.reconfigure_rate(500), "garbage rate rejected");
    assert!(sink.reconfigure_rate(96_000), "sane rate accepted");

    let st = sink.stats();
    assert_eq!(
        st.frames_written,
        st.frames_pulled + st.occupancy_frames as u64 + st.frames_flushed,
        "conservation across epochs"
    );
}

// ---------------------------------------------------------------------------
// G. Interpolation acceptance (<5 ms) + fire-time negative control
// ---------------------------------------------------------------------------

/// Drive `callbacks` quanta (2-quanta feed-ahead) and measure both the
/// consistent-pair estimator and the naive fire-time control.
fn drive_and_measure(
    sink: &mut VirtualHalSink,
    callbacks: usize,
) -> (f64, f64) {
    let chunk = sink.callback_frames();
    let mut interp = DacClockInterpolator::default();
    let mut fire_interp = DacClockInterpolator::default();
    let mut feed = Vec::new();
    let mut dst = vec![0.0f32; chunk * 2];
    let mut next_feed: u64 = 0;
    let (mut max_err, mut max_fire_err) = (0.0f64, 0.0f64);
    for _ in 0..callbacks {
        fill_ramp(&mut feed, next_feed, chunk * 2);
        next_feed += (sink.write_producer_frames(&feed) / 2) as u64;
        let cb = sink.run_callback_into(&mut dst);
        interp.update(&cb.clock);
        fire_interp.update(&DacPresentationClock {
            dac_frame_position: cb.clock.dac_frame_position,
            sample_rate: cb.clock.sample_rate,
            monotonic_ns: cb.fire_time_ns,
        });
        // Deterministic query instants across the quantum + staleness tail.
        let quantum_ns = 1_000_000_000u64 * chunk as u64 / cb.clock.sample_rate as u64;
        for q in 1..=4u64 {
            let now = cb.clock.monotonic_ns + quantum_ns * q / 4;
            if let Some(est) = interp.position_frames_at(now) {
                let truth = sink.true_position_frames_at(now);
                let err = (est as f64 - truth).abs() * 1000.0
                    / f64::from(cb.clock.sample_rate);
                max_err = max_err.max(err);
            }
            if let Some(fest) = fire_interp.position_frames_at(now) {
                let truth = sink.true_position_frames_at(now);
                let err = (fest as f64 - truth).abs() * 1000.0
                    / f64::from(cb.clock.sample_rate);
                max_fire_err = max_fire_err.max(err);
            }
        }
    }
    (max_err, max_fire_err)
}

#[test]
fn interpolation_acceptance_under_jitter_drift_and_quantization() {
    // G1: exact timestamps + jitter — the consistent-pair estimator is
    // sub-frame accurate; the naive fire-time one is NOT.
    {
        let mut sink = VirtualHalSink::new(VirtualHalConfig {
            modes: AdversarialModes::TIMING_JITTER,
            ..config(512)
        })
        .unwrap();
        let (err, fire_err) = drive_and_measure(&mut sink, 400);
        assert!(
            err < 1.0,
            "jitter alone: consistent-pair error {err:.4} ms < 1 ms"
        );
        assert!(
            fire_err > INTERPOLATION_ACCEPTANCE_MS,
            "negative control: fire-time error {fire_err:.4} ms > 5 ms"
        );
    }

    // G2: 1 ms timestamp grid + 200 ppm drift + jitter, all chunk sizes.
    for chunk in [256usize, 512, 1024] {
        let mut sink = VirtualHalSink::new(VirtualHalConfig {
            modes: AdversarialModes::TIMING_JITTER,
            timestamp_quantum_ns: 1_000_000,
            drift_ppm: 200,
            ..config(chunk)
        })
        .unwrap();
        let (err, fire_err) = drive_and_measure(&mut sink, 400);
        assert!(
            err < INTERPOLATION_ACCEPTANCE_MS,
            "chunk {chunk}: quantized+drift+jitter error {err:.4} ms < 5 ms"
        );
        assert!(
            fire_err > INTERPOLATION_ACCEPTANCE_MS,
            "chunk {chunk}: negative control {fire_err:.4} ms > 5 ms"
        );
    }
}

// ---------------------------------------------------------------------------
// H. Full adversarial scenario
// ---------------------------------------------------------------------------

#[test]
fn full_adversarial_scenario_all_modes_all_chunks() {
    for chunk in [256usize, 512, 1024] {
        let mut sink = VirtualHalSink::new(VirtualHalConfig {
            callback_frames: chunk,
            capacity_frames: 8192,
            modes: AdversarialModes::ALL,
            jitter_fraction: 0.10,           // ±10 % interval variance
            timestamp_quantum_ns: 1_000_000, // 1 ms HAL ts grid
            drift_ppm: 200,
            ..VirtualHalConfig::default()
        })
        .unwrap();

        let profile = AdversarialProfile {
            callbacks: 600,
            starve_every: 50,
            starve_burst: 5, // > feed depth: guarantees real starvation
            flood_every: 97,
            rate_changes: 3, // 48k -> 44.1k -> 96k -> 48k
            queries_per_callback: 4,
            feed_ahead_quanta: 2,
        };
        let report = sink.run_adversarial_scenario(&profile);

        assert!(
            report.max_interpolation_error_ms < INTERPOLATION_ACCEPTANCE_MS,
            "chunk {chunk}: interpolation error {:.4} ms < 5 ms",
            report.max_interpolation_error_ms
        );
        assert!(
            report.max_firetime_error_ms > INTERPOLATION_ACCEPTANCE_MS,
            "chunk {chunk}: fire-time control {:.4} ms > 5 ms",
            report.max_firetime_error_ms
        );
        assert!(report.conservation_ok, "chunk {chunk}: conservation");
        assert!(report.starved_callbacks > 0, "chunk {chunk}: underruns injected");
        assert!(report.overrun_events > 0, "chunk {chunk}: overruns injected");
        assert_eq!(report.rate_changes, 3, "chunk {chunk}: rate changes");
        assert_eq!(report.callbacks_run, 600, "chunk {chunk}: all callbacks ran");
        assert_eq!(sink.stats().epoch, 3, "chunk {chunk}: epoch per reconfig");
    }
}

// ---------------------------------------------------------------------------
// I. Consumer-interface polymorphism
// ---------------------------------------------------------------------------

#[test]
fn sink_implements_the_audio_consumer_interface() {
    let mut sink = VirtualHalSink::new(config(512)).unwrap();
    let mut feed = Vec::new();
    fill_ramp(&mut feed, 0, 1024);
    sink.write_producer_frames(&feed);

    // The trait object drives the SAME sink through the canonical
    // consumer edge (what the AAudio / CoreAudio adapters call).
    let mut hal: Box<dyn AudioHalConsumer> = Box::new(sink);
    let mut dst = vec![0.0f32; 512 * 2];
    let report = hal.hal_pull(&mut dst);
    assert_eq!(report.frames_requested, 512);
    assert_eq!(report.frames_supplied, 512);
    assert!(!report.starved);
    assert_eq!(report.clock.dac_frame_position, 0);
    assert_eq!(report.clock.sample_rate, 48_000);
    assert_eq!(dst[0], ramp(0));

    // A starved pull through the trait object returns digital silence
    // and the clock still advances.
    let first = hal.hal_pull(&mut dst);
    let starved = hal.hal_pull(&mut dst);
    assert!(starved.starved);
    assert!(dst.iter().all(|s| *s == 0.0));
    assert!(first.clock.dac_frame_position < starved.clock.dac_frame_position);
}

// ---------------------------------------------------------------------------
// J. Seed determinism
// ---------------------------------------------------------------------------

#[test]
fn identical_seed_replays_identically() {
    let profile = AdversarialProfile {
        callbacks: 300,
        starve_every: 40,
        starve_burst: 4,
        flood_every: 61,
        rate_changes: 2,
        queries_per_callback: 3,
        feed_ahead_quanta: 2,
    };
    let build = || {
        VirtualHalSink::new(VirtualHalConfig {
            modes: AdversarialModes::ALL,
            timestamp_quantum_ns: 1_000_000,
            drift_ppm: 137,
            rng_seed: 0xDEAD_BEEF_CAFE_F00D,
            ..config(512)
        })
        .unwrap()
    };
    let mut a = build();
    let mut b = build();
    let ra = a.run_adversarial_scenario(&profile);
    let rb = b.run_adversarial_scenario(&profile);
    assert_eq!(
        format!("{ra:?}"),
        format!("{rb:?}"),
        "identical seed => identical scenario report"
    );
    assert_eq!(a.stats(), b.stats());
    assert_eq!(a.last_clock(), b.last_clock());
}
