//! Phase 3 (UFFI-03): integration tests for the wired `StreamifyEngine`.
//!
//! These exercise the engine the way its consumers do — through the
//! crate's public surface (the same types and methods the UniFFI
//! trampolines dispatch to), from *outside* the crate like the Kotlin
//! bindings will: cross-thread access patterns, idempotency of the
//! one-way lifecycle, telemetry validation at the boundary, and the full
//! state-transition walk.
//!
//! The audio real-time data-plane is out of scope by design (AMP-10: the
//! frozen JNI contract in jni_bridge_dsp.cc); everything proven here is
//! control-plane behavior.

use std::sync::{Arc, Barrier};
use std::thread;

use streamify_core_rs::{
    AudioStreamConfig, BufferStats, EngineError, PlaybackState, StreamifyEngine,
};

/// A fresh engine is dormant: Idle lifecycle, empty buffers, unity gain.
#[test]
fn new_engine_is_dormant() {
    let engine = StreamifyEngine::new();
    assert_eq!(engine.get_playback_state(), PlaybackState::Idle);
    assert_eq!(engine.get_buffer_stats(), BufferStats::default());
    assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
}

/// initialize() arms the engine only after the runtime environment
/// invariants verify, and reports through the typed error channel.
#[test]
fn initialize_verifies_environment_then_arms() {
    let engine = StreamifyEngine::new();
    // On any healthy host (CI runner, device, dev box) the invariants
    // hold: reference pipeline geometry sane, scheduler width reported,
    // wall clock past the epoch.
    engine
        .initialize()
        .expect("healthy host must pass runtime environment verification");
    assert_eq!(engine.get_playback_state(), PlaybackState::Buffering);
}

/// The one-way lifecycle never regresses: repeated initialize() calls are
/// verified no-ops, and a progressed state survives them untouched.
#[test]
fn initialize_is_idempotent_and_never_regresses() {
    let engine = StreamifyEngine::new();
    for _ in 0..3 {
        engine
            .initialize()
            .expect("re-arm must re-verify cleanly on a healthy host");
    }
    assert_eq!(engine.get_playback_state(), PlaybackState::Buffering);

    engine.set_playback_state(PlaybackState::Playing);
    for _ in 0..3 {
        engine
            .initialize()
            .expect("idempotent re-arm after progression");
    }
    assert_eq!(engine.get_playback_state(), PlaybackState::Playing);

    // Even a forced Error state is terminal for the lifecycle logic:
    // initialize() must not "revive" it (recovery is a control-plane
    // decision, not an arming side effect).
    engine.set_playback_state(PlaybackState::Error);
    engine
        .initialize()
        .expect("re-arm on a faulted engine still verifies the environment");
    assert_eq!(engine.get_playback_state(), PlaybackState::Error);
}

/// The control plane walks the full documented lifecycle, and telemetry
/// stays readable in every state — including Error.
#[test]
fn state_transitions_walk_the_full_lifecycle() {
    let engine = StreamifyEngine::new();
    let transitions = [
        (PlaybackState::Buffering, None),
        (PlaybackState::Playing, Some(1_250)),
        (PlaybackState::Paused, Some(4_000)),
        (PlaybackState::Playing, Some(6_800)),
        (PlaybackState::Error, Some(0)),
    ];
    // First leg: arming drives Idle -> Buffering.
    engine
        .initialize()
        .expect("healthy host arms cleanly");
    assert_eq!(engine.get_playback_state(), PlaybackState::Buffering);
    // Walk the control-plane legs; feed telemetry along the way.
    for (state, buffered) in transitions {
        engine.set_playback_state(state);
        if let Some(ms) = buffered {
            engine
                .record_buffer_stats(ms, 1, 0.5)
                .expect("finite telemetry must be accepted");
        }
        assert_eq!(engine.get_playback_state(), state);
        let stats = engine.get_buffer_stats();
        assert!(stats.fill_ratio.is_finite());
        assert!((0.0..=1.0).contains(&stats.fill_ratio));
    }
    // Terminal state still answers reads: a faulted engine must keep
    // serving its last-known telemetry to diagnostics surfaces.
    assert_eq!(engine.get_buffer_stats().buffered_duration_ms, 0);
}

/// record_buffer_stats() is the live producer edge: finite samples
/// publish atomically-observable state, malformed samples are rejected
/// whole, and finite overshoot clamps.
#[test]
fn telemetry_feed_validates_and_publishes() {
    let engine = StreamifyEngine::new();
    // Pre-arm feeding is legal: pipelines pre-buffer before the engine
    // arms (record_buffer_stats carries no state gate by design).
    engine
        .record_buffer_stats(5_000, 0, 0.42)
        .expect("finite pre-arm sample must be accepted");
    let stats = engine.get_buffer_stats();
    assert_eq!(stats.buffered_duration_ms, 5_000);
    assert_eq!(stats.underrun_count, 0);
    assert!((stats.fill_ratio - 0.42).abs() < f32::EPSILON);

    // Malformed samples are rejected before ANY store runs.
    for garbage in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        assert_eq!(
            engine.record_buffer_stats(9_999, 9, garbage),
            Err(EngineError::InternalError),
            "{garbage} must never enter the snapshot"
        );
    }
    let stats = engine.get_buffer_stats();
    assert_eq!(stats.buffered_duration_ms, 5_000);
    assert_eq!(stats.underrun_count, 0);
    assert!((stats.fill_ratio - 0.42).abs() < f32::EPSILON);

    // Finite overshoot clamps (watermark transitions legitimately overshoot).
    engine
        .record_buffer_stats(7_500, 2, 1.3)
        .expect("finite overshoot clamps, never rejects");
    assert!((engine.get_buffer_stats().fill_ratio - 1.0).abs() < f32::EPSILON);

    // A producer-side pipeline restart (underrun epoch reset) is accepted.
    engine
        .record_buffer_stats(120, 0, 0.05)
        .expect("epoch reset must be accepted");
    assert_eq!(engine.get_buffer_stats().underrun_count, 0);
}

/// set_volume() applies a clamped gain scalar; NaN garbage fails safe to
/// full mute instead of poisoning the DSP chain with NaN bits.
#[test]
fn volume_scalar_clamps_and_fails_safe() {
    let engine = StreamifyEngine::new();
    engine.set_volume(0.6);
    assert!((engine.volume() - 0.6).abs() < f32::EPSILON);
    engine.set_volume(42.0);
    assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
    engine.set_volume(-0.5);
    assert!(engine.volume().abs() < f32::EPSILON);
    engine.set_volume(f32::NAN);
    assert!(engine.volume().abs() < f32::EPSILON);
    assert!(engine.volume().is_finite());
    engine.set_volume(0.9);
    assert!((engine.volume() - 0.9).abs() < f32::EPSILON);
}

/// The reference pipeline geometry enforces its physical contract.
#[test]
fn audio_config_geometry_contract_holds() {
    assert!(AudioStreamConfig::default().validate().is_ok());
    for degenerate in [
        AudioStreamConfig {
            sample_rate: 0,
            ..AudioStreamConfig::default()
        },
        AudioStreamConfig {
            channels: 0,
            ..AudioStreamConfig::default()
        },
        AudioStreamConfig {
            bit_depth: 8,
            ..AudioStreamConfig::default()
        },
        AudioStreamConfig {
            buffer_frames: 0,
            ..AudioStreamConfig::default()
        },
    ] {
        assert_eq!(degenerate.validate(), Err(EngineError::InternalError));
    }
}

/// Cross-thread access: an Arc-shared engine hammered by concurrent
/// producers (volume, telemetry, lifecycle churn, re-arm attempts) and
/// readers never deadlocks, never panics, and every observed value
/// respects its declared range. Malformed telemetry injected under
/// contention is rejected every single time.
#[test]
fn cross_thread_access_stays_lock_free_and_consistent() {
    const READERS: usize = 3;
    const TELEMETRY_PRODUCERS: usize = 3;
    const GARBAGE_PRODUCERS: usize = 2;
    const LIFECYCLE_CHURNERS: usize = 2;
    const ITERS: usize = 10_000;

    let engine = Arc::new(StreamifyEngine::new());
    engine
        .initialize()
        .expect("healthy host arms cleanly");
    let barrier = Arc::new(Barrier::new(
        READERS + TELEMETRY_PRODUCERS + GARBAGE_PRODUCERS + LIFECYCLE_CHURNERS + 1,
    ));

    let mut handles = Vec::new();

    // Live telemetry producers: the playback buffer + acoustic pipeline
    // edge, publishing finite samples.
    for i in 0..TELEMETRY_PRODUCERS {
        let engine = Arc::clone(&engine);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            for k in 0..ITERS {
                let sample = (k % 1000) as f32 / 1000.0;
                engine
                    .record_buffer_stats((k % 30_000) as u64, (k % 13) as u32, sample)
                    .expect("finite sample must publish under contention");
                let _ = i;
            }
        }));
    }

    // Garbage producers: malformed samples must be rejected EVERY time,
    // under contention, with the typed error — never a panic, never a
    // partial publication.
    for i in 0..GARBAGE_PRODUCERS {
        let engine = Arc::clone(&engine);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            for k in 0..ITERS {
                let garbage = if k % 2 == 0 { f32::NAN } else { f32::INFINITY };
                assert_eq!(
                    engine.record_buffer_stats(u64::MAX, u32::MAX, garbage),
                    Err(EngineError::InternalError)
                );
                let _ = i;
            }
        }));
    }

    // Lifecycle churners: control-plane transitions + idempotent re-arms.
    for i in 0..LIFECYCLE_CHURNERS {
        let engine = Arc::clone(&engine);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            for k in 0..ITERS {
                engine.set_volume((k % 101) as f32 / 100.0);
                engine.set_playback_state(match k % 4 {
                    0 => PlaybackState::Playing,
                    1 => PlaybackState::Paused,
                    2 => PlaybackState::Buffering,
                    _ => PlaybackState::Error,
                });
                let _ = engine.initialize();
                let _ = i;
            }
        }));
    }

    // Readers: telemetry surfaces sampling the engine concurrently.
    for _ in 0..READERS {
        let engine = Arc::clone(&engine);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            for _ in 0..ITERS {
                let state = engine.get_playback_state();
                let stats = engine.get_buffer_stats();
                let volume = engine.volume();
                // Every observed value respects its declared range, no
                // matter the interleaving: the snapshot is either the
                // initial empty one or a clamped published sample.
                assert!(volume.is_finite());
                assert!((0.0..=1.0).contains(&volume));
                assert!(stats.fill_ratio.is_finite());
                assert!((0.0..=1.0).contains(&stats.fill_ratio));
                // Never regressed below the arming floor.
                assert_ne!(state, PlaybackState::Idle);
            }
        }));
    }

    barrier.wait();
    for (idx, handle) in handles.into_iter().enumerate() {
        handle
            .join()
            .unwrap_or_else(|_| panic!("worker thread {idx} must not panic"));
    }

    // Post-join: the last published sample is finite and clamped, and the
    // garbage lanes never managed to inject anything.
    let stats = engine.get_buffer_stats();
    assert!(stats.fill_ratio.is_finite());
    assert!((0.0..=1.0).contains(&stats.fill_ratio));
    assert_ne!(stats.buffered_duration_ms, u64::MAX);
    assert_ne!(stats.underrun_count, u32::MAX);
}
