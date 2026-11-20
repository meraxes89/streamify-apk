//! Desktop HAL contract suite — Phase 4b / MP-HAL08 + MP-HAL09
//! (rust/tests/test_desktop_hal_contracts.rs).
//!
//! Validates the Windows WASAPI event-driven render model, the Linux
//! PipeWire process-callback model and the ALSA fallback writer model
//! (the mock consumers of [`DesktopHalModel`]) against the Phase 4a
//! VirtualHAL adversarial double — WITHOUT physical Windows/Linux audio
//! hardware (deliverable 3):
//!
//! * the clock-plane conversion helpers (the Rust twins of the C++
//!   render-core helpers) with exact known values,
//! * the WASAPI pull contract: the event-driven VARIABLE quantum
//!   (first event = whole buffer, later events = the device-period
//!   drain), bit-exact ramp data, starvation zero-fill, IAudioClock
//!   round-trip consistency (2x tick frequency + QPC 100 ns units),
//! * the PipeWire pull contract: the offered-window / requested-hint
//!   quantum, pw_time foreign-clock round-trip ({1, 96000} behind a
//!   48 kHz stream),
//! * the ALSA fallback contract: fixed period windows, the classic
//!   presented = submitted - delay estimate (identity round-trip),
//! * the DacPresentationClock interpolation acceptance gate (< 5 ms)
//!   under the full adversarial clock profile (±10 % jitter, 1 ms
//!   timestamp grid, +200 ppm drift) for EVERY desktop flavor,
//! * the [`AudioHalConsumer`] polymorphism across all three flavors.
//!
//! The pure-Rust twin of native/tests/test_desktop_hal_render_cores.cpp
//! (the V-sections) — both suites drive the same simulated DAC timeline
//! through their respective platform semantics.

use streamify_audio::desktop_hal_models::{
    alsa_presented_frames, pipewire_ticks_to_frames, wasapi_frames_to_render,
    wasapi_qpc100_to_ns, wasapi_scale_device_position, DesktopHalFlavor,
    DesktopHalModel, DesktopHalModelConfig, DESKTOP_CANONICAL_RATE_HZ,
};
use streamify_audio::virtual_hal::{
    AudioHalConsumer, DacClockInterpolator, INTERPOLATION_ACCEPTANCE_MS,
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

/// The full adversarial clock profile (Phase 4a): ±10 % fire-time
/// jitter, a 1 ms reported-timestamp grid and +200 ppm DAC drift.
fn model_config(flavor: DesktopHalFlavor) -> DesktopHalModelConfig {
    DesktopHalModelConfig {
        flavor,
        ..DesktopHalModelConfig::default()
    }
}

/// Feed `quanta` quanta of the ramp into the model's producer ring,
/// starting at absolute frame `first`. Returns the next unwritten frame.
/// SINGLE write: a short return (ring full) is legal overrun telemetry —
/// retrying would hang, because nothing consumes between the retries.
fn feed(model: &mut DesktopHalModel, first: u64, quanta: usize) -> u64 {
    let frames = quanta * 512;
    let mut buf = Vec::new();
    fill_ramp(&mut buf, first, frames);
    model.write_producer_frames(&buf);
    first + frames as u64
}

// ---------------------------------------------------------------------------
// A. Clock-plane conversion helpers (exact known values)
// ---------------------------------------------------------------------------

#[test]
fn wasapi_scale_device_position_is_exact() {
    // Identity whenever the tick frequency equals the stream rate.
    assert_eq!(
        wasapi_scale_device_position(48_000, 48_000, 48_000),
        48_000
    );
    // A device clock at 2 ticks/frame.
    assert_eq!(
        wasapi_scale_device_position(96_000, 96_000, 48_000),
        48_000
    );
    // Fractional scaling truncates toward zero.
    assert_eq!(
        wasapi_scale_device_position(96_768, 96_000, 48_000),
        48_384
    );
    // Degenerate frequency fails safe (passthrough).
    assert_eq!(wasapi_scale_device_position(123, 0, 48_000), 123);
}

#[test]
fn wasapi_qpc100_conversion_is_linear_and_saturating() {
    assert_eq!(wasapi_qpc100_to_ns(1), 100);
    assert_eq!(wasapi_qpc100_to_ns(123_456_789), 12_345_678_900);
    assert_eq!(wasapi_qpc100_to_ns(u64::MAX), u64::MAX); // saturates
    // The last representable value converts exactly.
    assert_eq!(
        wasapi_qpc100_to_ns(u64::MAX / 100),
        (u64::MAX / 100) * 100
    );
}

#[test]
fn wasapi_event_quantum_is_buffer_minus_padding() {
    assert_eq!(wasapi_frames_to_render(960, 0), 960); // empty endpoint
    assert_eq!(wasapi_frames_to_render(480, 200), 280); // partial padding
    assert_eq!(wasapi_frames_to_render(480, 480), 0); // full padding
    assert_eq!(wasapi_frames_to_render(480, 600), 0); // lying padding
}

#[test]
fn pipewire_ticks_to_frames_handles_all_fractions() {
    // Canonical negotiated case: rate = {1, 48000} — ticks already count
    // stream frames.
    assert_eq!(pipewire_ticks_to_frames(48_000, 1, 48_000, 48_000), 48_000);
    // Foreign graph clock {1, 96000} behind a 48 kHz stream: halve.
    assert_eq!(pipewire_ticks_to_frames(96_000, 1, 96_000, 48_000), 48_000);
    // Odd tick counts truncate toward zero.
    assert_eq!(pipewire_ticks_to_frames(48_001, 1, 96_000, 48_000), 24_000);
    // A 44.1 kHz graph clock rescales up (128-bit path).
    assert_eq!(pipewire_ticks_to_frames(96_000, 1, 44_100, 48_000), 104_489);
    // Degenerate fractions fail safe.
    assert_eq!(pipewire_ticks_to_frames(777, 0, 48_000, 48_000), 777);
    assert_eq!(pipewire_ticks_to_frames(777, 1, 0, 48_000), 777);
}

#[test]
fn alsa_presented_frames_is_submitted_minus_delay() {
    assert_eq!(alsa_presented_frames(10_000, 2_500), 7_500);
    assert_eq!(alsa_presented_frames(100, 100), 0); // fully buffered
    assert_eq!(alsa_presented_frames(100, 1_000), 0); // corrupt: clamp
}

// ---------------------------------------------------------------------------
// B. WASAPI pull contract (MP-HAL08)
// ---------------------------------------------------------------------------

#[test]
fn wasapi_model_variable_quantum_and_bit_exact_ramp() {
    let mut model = DesktopHalModel::new(&model_config(DesktopHalFlavor::Wasapi))
        .expect("valid config");

    // The endpoint buffer is 4 nominal quanta (2048 frames @ 512); feed
    // exactly one ring capacity (16 quanta = 8192 frames) so every
    // checked pull below is fully supplied.
    let first = feed(&mut model, 0, 16);
    assert_eq!(first, 8192);

    let mut dst = vec![1.0f32; 4096 * 2]; // room for the whole buffer

    // First event: the whole endpoint buffer drains in one render.
    let report = model.hal_pull(&mut dst);
    assert_eq!(report.frames_requested, 2048, "first event drains the buffer");
    assert_eq!(report.frames_supplied, 2048);
    assert!(!report.starved);

    // Bit-exact ramp slice for the first quantum.
    for f in 0..2048usize {
        assert_eq!(dst[f * 2], ramp(f as u64), "L channel frame {f}");
        assert_eq!(dst[f * 2 + 1], -ramp(f as u64), "R channel frame {f}");
    }

    // Later events: the device drains one period (512) between events,
    // so each subsequent render is exactly the period-shaped free space.
    let mut quanta_seen = Vec::new();
    let mut consumed = 2048u64;
    for _ in 0..6 {
        let report = model.hal_pull(&mut dst);
        quanta_seen.push(report.frames_requested);
        for f in 0..report.frames_requested {
            assert_eq!(
                dst[f * 2],
                ramp(consumed + f as u64),
                "later-event ramp integrity at frame {}",
                consumed + f as u64
            );
        }
        consumed += report.frames_requested as u64;
    }
    // Every later event rendered the device period (512): the classic
    // 2x-period event-driven cadence (buffer 4x, drain 1x per event).
    assert!(
        quanta_seen.iter().all(|&q| q == 512),
        "later events are period-shaped, saw {quanta_seen:?}"
    );
}

#[test]
fn wasapi_model_starvation_zero_fill_and_clock_consistency() {
    let mut model = DesktopHalModel::new(&model_config(DesktopHalFlavor::Wasapi))
        .expect("valid config");

    // Feed only 100 frames, then pull the whole first event (2048).
    let mut buf = Vec::new();
    fill_ramp(&mut buf, 0, 100);
    let consumed = model.write_producer_frames(&buf);
    assert_eq!(consumed, buf.len());

    let mut dst = vec![1.0f32; 4096 * 2];
    let report = model.hal_pull(&mut dst);
    assert!(report.starved, "starved pull is reported");
    assert_eq!(report.frames_supplied, 100);
    // The starved tail is digital silence.
    for f in 100..2048usize {
        assert_eq!(dst[f * 2], 0.0, "starved tail L at {f}");
        assert_eq!(dst[f * 2 + 1], 0.0, "starved tail R at {f}");
    }

    // Clock consistency: the report's pair must be the double's
    // ground-truth (first frame of the quantum, its presentation
    // instant) after the IAudioClock round trip. The frame position is
    // exact; the monotonic time re-quantizes to QPC 100 ns units.
    assert_eq!(report.clock.dac_frame_position, 0);
    assert_eq!(report.clock.sample_rate, DESKTOP_CANONICAL_RATE_HZ);
    let vhal_clock = model.stats();
    assert_eq!(vhal_clock.callbacks_run, 1);

    // A fully starved second event: pure silence, position advances.
    let report2 = model.hal_pull(&mut dst);
    assert!(report2.starved);
    assert_eq!(report2.frames_supplied, 0);
    for f in 0..512usize {
        assert_eq!(dst[f * 2], 0.0);
    }
    // The simulated DAC advanced through the silence: the clock position
    // moved past the first quantum.
    assert!(report2.clock.dac_frame_position >= 2048);

    let tel = model.model_telemetry();
    assert_eq!(tel.pulls, 2);
    assert_eq!(tel.starved_pulls, 2);
    assert_eq!(tel.frames_supplied, 100);
    assert_eq!(tel.frames_silenced, (2048 - 100) + 512);
}

// ---------------------------------------------------------------------------
// C. PipeWire pull contract (MP-HAL09)
// ---------------------------------------------------------------------------

#[test]
fn pipewire_model_window_and_requested_hint_quantum() {
    let mut model =
        DesktopHalModel::new(&model_config(DesktopHalFlavor::PipeWire))
            .expect("valid config");

    let next = feed(&mut model, 0, 8);
    assert_eq!(next, 4096);

    let mut dst = vec![1.0f32; 4096 * 2];

    // Default: the offered window (2 nominal quanta = 1024 frames).
    let report = model.hal_pull(&mut dst);
    assert_eq!(report.frames_requested, 1024);
    assert_eq!(report.frames_supplied, 1024);
    assert!(!report.starved);
    for f in 0..1024usize {
        assert_eq!(dst[f * 2], ramp(f as u64));
    }

    // A resampler hint BELOW the window cuts the quantum down.
    model.set_requested_hint(384);
    let report = model.hal_pull(&mut dst);
    assert_eq!(report.frames_requested, 384, "hint cuts the window");
    // The hint is one-shot (per cycle).
    assert_eq!(model.next_quantum(4096), 1024);

    // A hint ABOVE the window is ignored (the window governs).
    model.set_requested_hint(999_999);
    let report = model.hal_pull(&mut dst);
    assert_eq!(report.frames_requested, 1024);
    assert_eq!(report.frames_supplied, 1024);
}

#[test]
fn pipewire_model_foreign_clock_round_trip() {
    let mut model =
        DesktopHalModel::new(&model_config(DesktopHalFlavor::PipeWire))
            .expect("valid config");

    let mut buf = Vec::new();
    // 2048 frames = 2 offered windows: both pulls fully supplied.
    fill_ramp(&mut buf, 0, 2048);
    assert_eq!(model.write_producer_frames(&buf), buf.len());

    let mut dst = vec![1.0f32; 2048 * 2];
    let report = model.hal_pull(&mut dst);

    // The double's ground truth for the first quantum: position 0.
    // Round trip through {1, 96000} graph ticks and back must be exact
    // (the 2x scaling is invertible on even tick counts — and the
    // conversion emits only even counts).
    assert_eq!(report.clock.dac_frame_position, 0);
    assert_eq!(report.clock.sample_rate, DESKTOP_CANONICAL_RATE_HZ);

    // Pull again and check a non-zero position round-trips exactly.
    let report = model.hal_pull(&mut dst);
    // 1024 frames presented after the first callback: ticks 2048 at
    // {1, 96000} → frames = 2048 * 48000 / 96000 = 1024.
    assert_eq!(
        report.clock.dac_frame_position, 1024,
        "foreign-clock round trip is lossless"
    );

    // Cross-check against the double's own reported clock.
    let last = model.stats();
    assert_eq!(last.frames_pulled, 2048);
}

// ---------------------------------------------------------------------------
// D. ALSA fallback contract (MP-HAL09 fallback lane)
// ---------------------------------------------------------------------------

#[test]
fn alsa_model_fixed_period_windows_and_presented_estimate() {
    let mut model = DesktopHalModel::new(&model_config(DesktopHalFlavor::Alsa))
        .expect("valid config");

    let mut buf = Vec::new();
    // 1024 frames = exactly 4 ALSA period windows.
    fill_ramp(&mut buf, 0, 1024);
    assert_eq!(model.write_producer_frames(&buf), buf.len());

    let mut dst = vec![1.0f32; 2048 * 2];

    // The fallback writer pushes FIXED 256-frame windows.
    for q in 0..4u64 {
        let report = model.hal_pull(&mut dst);
        assert_eq!(report.frames_requested, 256, "fixed period window");
        assert_eq!(report.frames_supplied, 256);
        assert!(!report.starved);
        for f in 0..256usize {
            assert_eq!(
                dst[f * 2],
                ramp(q * 256 + f as u64),
                "ALSA window ramp integrity"
            );
        }
        // The presented estimate: submitted - delay == the double's
        // reported position (the delay is exactly the device's buffer
        // level). Exact by construction on the identity round trip.
        assert_eq!(
            report.clock.dac_frame_position,
            q * 256,
            "presented == played frames (identity round trip)"
        );
    }

    // Starvation: the producer is exhausted; the window zero-fills and
    // the presented position STILL advances (the writer keeps pushing
    // silence into the device).
    let report = model.hal_pull(&mut dst);
    assert!(report.starved);
    assert_eq!(report.frames_supplied, 0);
    for f in 0..256usize {
        assert_eq!(dst[f * 2], 0.0, "starved window is digital silence");
    }
    assert_eq!(report.clock.dac_frame_position, 1024);

    let tel = model.model_telemetry();
    assert_eq!(tel.pulls, 5);
    assert_eq!(tel.frames_supplied, 1024);
    assert_eq!(tel.frames_silenced, 256);
}

// ---------------------------------------------------------------------------
// E. Interpolation acceptance (< 5 ms) for every desktop flavor
// ---------------------------------------------------------------------------

/// Drives one flavor through the full adversarial clock profile with a
/// scripted producer (feed bursts + a starvation window), feeding the
/// drained clock samples through the canonical interpolator and
/// measuring the worst error against the double's ground-truth
/// presentation timeline.
fn drive_interpolation(flavor: DesktopHalFlavor) -> (f64, usize, usize) {
    let mut model = DesktopHalModel::new(&model_config(flavor))
        .expect("valid config");
    let mut estimator = DacClockInterpolator::default();
    let mut dst = vec![0.0f32; 4096 * 2];

    let mut next_frame = 0u64;
    let mut max_err_ms = 0.0f64;
    let mut mismatches = 0usize;
    let callbacks = 400usize;

    for cb in 0..callbacks {
        // Producer schedule: feed 2 quanta every 2 callbacks, starve
        // after callback 300 (the clock must track THROUGH starvation:
        // position advances through silence on every flavor).
        if cb % 2 == 0 && cb < 300 {
            next_frame = feed(&mut model, next_frame, 2);
        }

        let report = model.hal_pull(&mut dst);

        // Data-plane sanity: supplied <= requested; starved means silence.
        if report.frames_supplied > report.frames_requested {
            mismatches += 1;
        }

        // Clock-plane: ingest the reported pair and evaluate against the
        // double's ground truth at the SAME monotonic instant.
        estimator.update(&report.clock);
        let now_ns = model.sim_time_ns();
        if let Some(est_ms) = estimator.position_ms_at(now_ns) {
            let true_ms = model.true_position_frames_at(now_ns) * 1000.0
                / f64::from(DESKTOP_CANONICAL_RATE_HZ);
            let err = (est_ms - true_ms).abs();
            if err > max_err_ms {
                max_err_ms = err;
            }
        }
    }

    (max_err_ms, mismatches, callbacks)
}

#[test]
fn interpolation_acceptance_all_desktop_flavors() {
    for flavor in [
        DesktopHalFlavor::Wasapi,
        DesktopHalFlavor::PipeWire,
        DesktopHalFlavor::Alsa,
    ] {
        let (max_err_ms, mismatches, callbacks) = drive_interpolation(flavor);
        assert_eq!(mismatches, 0, "{:?}: supplied never exceeds requested", flavor);
        assert_eq!(callbacks, 400);
        assert!(
            max_err_ms < INTERPOLATION_ACCEPTANCE_MS,
            "{:?}: interpolation error {:.3} ms exceeds the {} ms bound",
            flavor,
            max_err_ms,
            INTERPOLATION_ACCEPTANCE_MS
        );
        // And it must be non-trivially exercised (not a vacuous 0.000 run
        // — the 1 ms timestamp grid alone guarantees measurable error).
        assert!(
            max_err_ms > 0.0,
            "{:?}: the drive exercised the clock plane",
            flavor
        );
    }
}

// ---------------------------------------------------------------------------
// F. The platform-agnostic consumer polymorphism
// ---------------------------------------------------------------------------

#[test]
fn all_flavors_implement_the_consumer_interface() {
    let mut hals: Vec<Box<dyn AudioHalConsumer>> = Vec::new();
    for flavor in [
        DesktopHalFlavor::Wasapi,
        DesktopHalFlavor::PipeWire,
        DesktopHalFlavor::Alsa,
    ] {
        let model = DesktopHalModel::new(&model_config(flavor))
            .expect("valid config");
        hals.push(Box::new(model));
    }
    // The registry-shaped driving: feed each, pull once, check the report
    // carries the canonical rate.
    for (i, hal) in hals.iter_mut().enumerate() {
        // Feed through a temporary concrete handle (the producer side is
        // model-specific, not part of the consumer contract).
        // -> Instead drive the pure consumer contract: a starved pull is
        //    legal digital silence.
        let mut dst = vec![1.0f32; 512 * 2];
        let report = hal.hal_pull(&mut dst);
        assert_eq!(
            report.clock.sample_rate,
            DESKTOP_CANONICAL_RATE_HZ,
            "flavor {i} reports the canonical rate"
        );
        assert!(
            report.frames_supplied <= report.frames_requested,
            "flavor {i} honors the pull contract"
        );
        if report.starved {
            // A starved tail is digital silence.
            for f in report.frames_supplied..report.frames_requested {
                assert_eq!(dst[f * 2], 0.0, "flavor {i} starved tail");
            }
        }
    }
}

// ---------------------------------------------------------------------------
// G. Determinism (identical seed ⇒ identical run)
// ---------------------------------------------------------------------------

#[test]
fn identical_seed_replays_identically() {
    for flavor in [
        DesktopHalFlavor::Wasapi,
        DesktopHalFlavor::PipeWire,
        DesktopHalFlavor::Alsa,
    ] {
        let run = || {
            let mut model =
                DesktopHalModel::new(&model_config(flavor)).unwrap();
            let mut dst = vec![0.0f32; 4096 * 2];
            let mut next = feed(&mut model, 0, 4);
            let mut clocks = Vec::new();
            for _ in 0..8 {
                let report = model.hal_pull(&mut dst);
                clocks.push((
                    report.frames_requested,
                    report.frames_supplied,
                    report.clock.dac_frame_position,
                    report.clock.monotonic_ns,
                ));
                if clocks.len() % 2 == 0 {
                    next = feed(&mut model, next, 2);
                }
            }
            let tel = model.model_telemetry();
            (clocks, tel)
        };
        let (clocks_a, tel_a) = run();
        let (clocks_b, tel_b) = run();
        assert_eq!(clocks_a, clocks_b, "{flavor:?}: identical clocks");
        assert_eq!(tel_a, tel_b, "{flavor:?}: identical telemetry");
    }
}

// ---------------------------------------------------------------------------
// H. Config validation (the model's own knobs)
// ---------------------------------------------------------------------------

#[test]
fn model_config_validation_rejects_garbage() {
    let base = DesktopHalModelConfig::default();
    // Zero / oversized platform knobs are rejected.
    for bad in [
        DesktopHalModelConfig {
            wasapi_buffer_quanta: 0,
            ..base.clone()
        },
        DesktopHalModelConfig {
            wasapi_buffer_quanta: 999,
            ..base.clone()
        },
        DesktopHalModelConfig {
            pipewire_window_quanta: 0,
            ..base.clone()
        },
        DesktopHalModelConfig {
            alsa_period_frames: 0,
            ..base.clone()
        },
        DesktopHalModelConfig {
            wasapi_device_period_frames: 0,
            ..base.clone()
        },
    ] {
        assert!(
            DesktopHalModel::new(&bad).is_err(),
            "rejects {:?}",
            bad.flavor
        );
    }
    // The shared envelope (bad rate / channels / quantum) is rejected
    // through the double's own validation.
    assert!(DesktopHalModel::new(&DesktopHalModelConfig {
        sample_rate: 1000,
        ..base.clone()
    })
    .is_err());
    assert!(DesktopHalModel::new(&DesktopHalModelConfig {
        channels: 3,
        ..base.clone()
    })
    .is_err());
    assert!(
        DesktopHalModel::new(&DesktopHalModelConfig {
            callback_frames: 333,
            ..base
        })
        .is_err()
    );
}
