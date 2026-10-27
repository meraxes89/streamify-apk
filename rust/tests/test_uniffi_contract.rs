//! Phase 3 (UFFI-03) + Phase 4 (UFFI-03/04, AMP-10): integration tests
//! for the wired `StreamifyEngine`.
//!
//! These exercise the engine the way its consumers do — through the
//! crate's public surface (the same types and methods the UniFFI
//! trampolines dispatch to), from *outside* the crate like the Kotlin
//! bindings will: cross-thread access patterns, idempotency of the
//! one-way lifecycle, telemetry validation at the boundary, the full
//! state-transition walk, and (Phase 4) the Jam CRDT delta surface
//! validated against the frozen golden vectors, the cooperative-flush
//! SPSC protocol under concurrent stress, and underrun telemetry
//! simulation.
//!
//! The audio real-time data-plane is out of scope by design (AMP-10: the
//! frozen JNI contract in jni_bridge_dsp.cc); everything proven here is
//! control-plane behavior. The one deliberate exception is the SPSC
//! protocol mirror below: it re-implements the exact atomic protocol
//! shipped in native's audio_sink_ringbuffer (RequestFlush release /
//! consumer acquire-exchange / tail-forward reset) so the protocol's
//! invariants are pinned on the Rust side too, cross-referencing the
//! native stress suite that pins them on the C++ side.

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
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

// ═════════════════════════════════════════════════════════════════════
// Phase 4 (UFFI-03/04): Jam CRDT delta surface — golden-vector pinned
// ═══════════════════════════════════════════════════════════════════

fn jam_fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/jam_golden_vectors.json")
}

/// The frozen `rust_48` wire rows from the golden fixture — the exact
/// bytes apply_jam_delta consumes on the FFI edge.
fn golden_rust48_rows() -> Vec<(String, Vec<u8>)> {
    let raw = std::fs::read(jam_fixture_path()).unwrap_or_else(|e| {
        panic!(
            "fixture missing/unreadable ({}): {e}",
            jam_fixture_path().display()
        )
    });
    let doc: serde_json::Value =
        serde_json::from_slice(&raw).unwrap_or_else(|e| panic!("fixture is not valid JSON: {e}"));
    doc["vectors"]
        .as_array()
        .expect("fixture: vectors array")
        .iter()
        .map(|v| {
            (
                v["name"].as_str().unwrap_or("<unnamed>").to_string(),
                hex::decode(v["rust_48"].as_str().expect("rust_48 hex"))
                    .unwrap_or_else(|e| panic!("vector {}: bad hex: {e}", v["name"])),
            )
        })
        .collect()
}

/// Heap's algorithm (iterative): every permutation of `items`, exactly
/// once — 5 golden ops means a full 120-order sweep, no sampling.
fn all_permutations<T: Clone>(items: &[T]) -> Vec<Vec<T>> {
    let n = items.len();
    let mut state = vec![0usize; n];
    let mut current = items.to_vec();
    let mut out = vec![current.clone()];
    let mut i = 0;
    while i < n {
        if state[i] < i {
            let j = if i % 2 == 0 { 0 } else { state[i] };
            current.swap(i, j);
            out.push(current.clone());
            state[i] += 1;
            i = 0;
        } else {
            state[i] = 0;
            i += 1;
        }
    }
    out
}

/// THE convergence contract (directive Task 1): applying the golden op
/// set as one delta — in EVERY arrival order — resolves to the identical
/// deterministic state hash. Two replicas (fresh engines, fresh sessions)
/// that merged the same 48-byte rows in any order derive the same root,
/// which is precisely the property the Kotlin Merkle reconciler needs
/// from get_jam_state_hash().
#[test]
fn jam_golden_vector_deltas_converge_in_every_order() {
    let rows = golden_rust48_rows();
    assert!(rows.len() >= 5, "fixture must carry the five frozen vectors");
    for (_, row) in &rows {
        assert_eq!(row.len(), 48, "golden rust_48 rows are 48 bytes");
    }

    let permutations = all_permutations(&rows);
    assert_eq!(permutations.len(), 120, "5 items must yield 5! orders");

    let mut reference: Option<String> = None;
    for (nth, perm) in permutations.into_iter().enumerate() {
        let engine = StreamifyEngine::new();
        engine
            .create_jam_session("golden-room".to_string())
            .expect("session mints");
        let mut delta = Vec::with_capacity(perm.len() * 48);
        for (_, row) in &perm {
            delta.extend_from_slice(row);
        }
        let result = engine
            .apply_jam_delta(delta)
            .unwrap_or_else(|e| panic!("permutation {nth} must apply: {e:?}"));
        // Every row is structurally valid (frozen fixture) — but rows may
        // legitimately land as boundary-rejects depending on order (the
        // reorder targets a non-add op id; a remove suppresses a replay).
        assert_eq!(
            result.ops_applied + result.ops_rejected,
            5,
            "permutation {nth}: every row is accounted for"
        );
        let hash = engine.get_jam_state_hash().expect("session live");
        assert_eq!(hash.len(), 64, "BLAKE3 hex image");
        match &reference {
            None => reference = Some(hash),
            Some(want) => assert_eq!(
                &hash, want,
                "permutation {nth}: state hash must be arrival-order free"
            ),
        }
    }
}

/// Hash sensitivity: the converged golden hash must actually track
/// content — dropping one op, or corrupting one row, changes the image.
#[test]
fn jam_state_hash_tracks_content_not_identity() {
    let rows = golden_rust48_rows();
    let full_delta: Vec<u8> = rows.iter().flat_map(|(_, r)| r.iter().copied()).collect();
    let base = {
        let engine = StreamifyEngine::new();
        engine.create_jam_session("sensitivity".to_string()).unwrap();
        engine.apply_jam_delta(full_delta.clone()).unwrap().state_hash
    };

    // Different session NAME + same content => same hash (content-only).
    let renamed = {
        let engine = StreamifyEngine::new();
        engine.create_jam_session("a-completely-other-room".to_string()).unwrap();
        engine.apply_jam_delta(full_delta.clone()).unwrap().state_hash
    };
    assert_eq!(base, renamed, "the hash covers content, not session identity");

    // Dropping the last op changes the converged state.
    let short_delta: Vec<u8> = rows[..rows.len() - 1]
        .iter()
        .flat_map(|(_, r)| r.iter().copied())
        .collect();
    let short = {
        let engine = StreamifyEngine::new();
        engine.create_jam_session("sensitivity".to_string()).unwrap();
        engine.apply_jam_delta(short_delta).unwrap().state_hash
    };
    assert_ne!(base, short, "removing an op must change the state hash");

    // A single corrupted byte in one row lands as a boundary reject and
    // therefore ALSO changes the converged fold (one fewer applied op).
    let mut corrupted = full_delta.clone();
    let last_row_start = corrupted.len() - 48;
    corrupted[last_row_start + 40] ^= 0xFF; // checksum lane
    let rejected = {
        let engine = StreamifyEngine::new();
        engine.create_jam_session("sensitivity".to_string()).unwrap();
        let result = engine.apply_jam_delta(corrupted).unwrap();
        assert!(result.ops_rejected >= 1, "corrupt row must be rejected");
        result.state_hash
    };
    assert_ne!(base, rejected, "a rejected row changes the converged fold");
}

// ═════════════════════════════════════════════════════════════════
// Phase 4 (AMP-10): SPSC cooperative flush — protocol mirror stress
// ═════════════════════════════════════════════════════════════════

/// Faithful Rust mirror of the consumer-cooperative flush protocol
/// shipped in native/include/audio_sink_ringbuffer.h: producer-owned
/// head (release store on commit), consumer-owned tail (only EVER
/// written by the consumer thread — normal reads advance it, the flush
/// pass resets it to the committed head), and a cross-thread
/// flush_requested flag (control thread release-stores, consumer
/// acquire-exchanges). The capacity-bounded ring slots and the PCM16
/// payload are deliberately abstracted away: the invariants under test
/// are the cursor discipline, not the memcpy.
struct SpscFlushMirror {
    head: AtomicU64,
    tail: AtomicU64,
    flush_requested: AtomicBool,
}

impl SpscFlushMirror {
    fn new() -> Self {
        Self {
            head: AtomicU64::new(0),
            tail: AtomicU64::new(0),
            flush_requested: AtomicBool::new(false),
        }
    }

    /// Control/UI thread: the ONLY thing it may do (RequestFlush).
    fn request_flush(&self) {
        self.flush_requested.store(true, Ordering::Release);
    }

    /// Producer thread: commit `frames` (writePcm_'s head publication).
    fn produce(&self, frames: u64) {
        let h = self.head.load(Ordering::Relaxed);
        self.head.store(h + frames, Ordering::Release);
    }

    /// Consumer thread (readPcm_): returns frames served; a flush pass
    /// consumes the request, snaps tail to head, and serves silence (0).
    fn consume(&self, want: u64) -> u64 {
        if self.flush_requested.swap(false, Ordering::Acquire) {
            self.tail
                .store(self.head.load(Ordering::Acquire), Ordering::Release);
            return 0;
        }
        let t = self.tail.load(Ordering::Relaxed);
        let h = self.head.load(Ordering::Acquire);
        let n = want.min(h - t);
        self.tail.store(t + n, Ordering::Release);
        n
    }

    /// Any thread: the occupancy invariant 0 <= head - tail must hold at
    /// every observation (GetStats' cursor math).
    fn occupancy_sane(&self) -> bool {
        let h = self.head.load(Ordering::Acquire);
        let t = self.tail.load(Ordering::Acquire);
        t <= h
    }
}

/// The exact production race AMP-10 closes, mirrored in Rust: a control
/// thread hammers request_flush() while the consumer reads and the
/// producer commits continuously. The pre-hardening hazard — the
/// control thread writing tail_ directly — would tear the cursor pair
/// (tail > head, uint64 occupancy underflow). With the cooperative
/// protocol, occupancy stays sane on every observation and reads never
/// exceed their request. The C++ side pins the same invariants against
/// the real ring (native/tests/test_audio_sink_ringbuffer.cpp ::
/// test_cooperative_flush_concurrent_stress, 8466-check suite).
#[test]
fn spsc_cooperative_flush_survives_concurrent_read_stress() {
    const FLUSH_REQUESTS: u64 = 50_000;
    const CONSUME_ITERS: u64 = 500_000;

    let ring = Arc::new(SpscFlushMirror::new());
    let violations = Arc::new(AtomicU64::new(0));
    let stop = Arc::new(AtomicBool::new(false));
    // 4 workers + the coordinating main thread.
    let barrier = Arc::new(Barrier::new(5));
    let mut handles = Vec::new();

    // Producer: random-ish chunk commits (writePcm_ cadence). Runs until
    // the bounded lanes (control + consumer) finish.
    {
        let ring = Arc::clone(&ring);
        let stop = Arc::clone(&stop);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            let mut k: u64 = 0;
            while !stop.load(Ordering::Relaxed) {
                k = k
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                ring.produce(1 + (k % 97));
                if k.is_multiple_of(64) {
                    std::thread::yield_now();
                }
            }
        }));
    }

    // Control/UI thread: RequestFlush() hammer (bounded lane).
    {
        let ring = Arc::clone(&ring);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            for i in 0..FLUSH_REQUESTS {
                ring.request_flush(); // noexcept on the C++ side
                if i % 256 == 0 {
                    std::thread::yield_now();
                }
            }
        }));
    }

    // Consumer: the audio-callback-shaped loop (bounded lane).
    {
        let ring = Arc::clone(&ring);
        let violations = Arc::clone(&violations);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            let mut k: u64 = 0x9E37_79B9_7F4A_7C15;
            for _ in 0..CONSUME_ITERS {
                k = k
                    .wrapping_mul(6_364_136_223_846_793_005)
                    .wrapping_add(1_442_695_040_888_963_407);
                let want = 1 + (k % 128);
                let served = ring.consume(want);
                // A read NEVER exceeds its request (torn-cursor detector).
                if served > want {
                    violations.fetch_add(1, Ordering::Relaxed);
                }
                // Sampled occupancy invariant.
                if k.is_multiple_of(1024) && !ring.occupancy_sane() {
                    violations.fetch_add(1, Ordering::Relaxed);
                }
            }
        }));
    }

    // Monitor: occupancy must be sane on EVERY external observation too
    // (the GetStats path from arbitrary threads).
    {
        let ring = Arc::clone(&ring);
        let violations = Arc::clone(&violations);
        let stop = Arc::clone(&stop);
        let barrier = Arc::clone(&barrier);
        handles.push(thread::spawn(move || {
            barrier.wait();
            let mut checks = 0u64;
            while !stop.load(Ordering::Relaxed) {
                if !ring.occupancy_sane() {
                    violations.fetch_add(1, Ordering::Relaxed);
                }
                checks += 1;
                if checks.is_multiple_of(1000) {
                    std::thread::yield_now();
                }
            }
        }));
    }

    // Everyone rendezvouses, then the bounded lanes run to completion.
    barrier.wait();
    // Spawn order is [producer, control, consumer, monitor]. The control
    // and consumer lanes are bounded (fixed iteration counts) and finish
    // on their own; only then is `stop` raised to release the producer
    // and monitor lanes, which are joined last.
    let bounded = [handles.swap_remove(2), handles.swap_remove(1)]; // consumer, control
    for (idx, handle) in bounded.into_iter().enumerate() {
        handle
            .join()
            .unwrap_or_else(|_| panic!("spsc bounded lane {idx} must not panic"));
    }
    stop.store(true, Ordering::Relaxed);
    for (idx, handle) in handles.into_iter().enumerate() {
        handle
            .join()
            .unwrap_or_else(|_| panic!("spsc unbounded lane {idx} must not panic"));
    }

    assert_eq!(
        violations.load(Ordering::Relaxed),
        0,
        "cooperative protocol must never tear the cursor pair"
    );
}

// ═════════════════════════════════════════════════════════════════
// Phase 4: buffer-stats telemetry under underrun simulation
// ═════════════════════════════════════════════════════════════════

/// A starving playback timeline, as the live producer edge would feed
/// it: fill drains toward zero, underruns escalate, buffered duration
/// collapses — and mid-starvation garbage stays rejected while every
/// finite sample publishes whole. The snapshot the UI renders must
/// track the LAST accepted epoch, never a torn or rejected one.
#[test]
fn buffer_stats_report_under_underrun_simulation() {
    let engine = StreamifyEngine::new();
    engine
        .initialize()
        .expect("healthy host arms cleanly");

    // Healthy epoch: well-buffered stereo pipeline.
    engine
        .record_buffer_stats(18_000, 0, 0.92)
        .expect("healthy sample publishes");
    let stats = engine.get_buffer_stats();
    assert_eq!(stats.buffered_duration_ms, 18_000);
    assert_eq!(stats.underrun_count, 0);
    assert!((stats.fill_ratio - 0.92).abs() < f32::EPSILON);

    // Starvation timeline: three degrading samples.
    let timeline = [
        (12_000u64, 1u32, 0.55f32),
        (4_000, 3, 0.18),
        (250, 7, 0.01),
    ];
    for (ms, underruns, fill) in timeline {
        engine
            .record_buffer_stats(ms, underruns, fill)
            .expect("finite starving sample publishes");
        let stats = engine.get_buffer_stats();
        assert_eq!(stats.buffered_duration_ms, ms);
        assert_eq!(stats.underrun_count, underruns);
        assert!((stats.fill_ratio - fill).abs() < f32::EPSILON);
        assert!(stats.fill_ratio.is_finite());
    }

    // Mid-starvation garbage: rejected with the typed error, and the
    // last good epoch stays intact (no partial publication).
    for garbage in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        assert_eq!(
            engine.record_buffer_stats(u64::MAX, u32::MAX, garbage),
            Err(EngineError::InternalError),
            "garbage during starvation is rejected at the boundary"
        );
    }
    let stats = engine.get_buffer_stats();
    assert_eq!(stats.buffered_duration_ms, 250);
    assert_eq!(stats.underrun_count, 7);
    assert!((stats.fill_ratio - 0.01).abs() < f32::EPSILON);

    // Bottoming out: the empty-buffer epoch (fill exactly 0.0).
    engine
        .record_buffer_stats(0, 9, 0.0)
        .expect("empty-buffer epoch publishes");
    let stats = engine.get_buffer_stats();
    assert_eq!(stats.buffered_duration_ms, 0);
    assert_eq!(stats.underrun_count, 9);
    assert!(stats.fill_ratio.abs() < f32::EPSILON);

    // Producer-side pipeline restart (track switch): the underrun epoch
    // resets to zero and the snapshot reflects the fresh pipeline.
    engine
        .record_buffer_stats(9_000, 0, 0.75)
        .expect("epoch reset publishes");
    let stats = engine.get_buffer_stats();
    assert_eq!(stats.underrun_count, 0);
    assert_eq!(stats.buffered_duration_ms, 9_000);
    assert!((stats.fill_ratio - 0.75).abs() < f32::EPSILON);

    // The underrun taxonomy itself stays distinct from the boundary
    // rejection: BufferUnderrun is the playback-fault variant the
    // control plane maps starvation to, InternalError is what a
    // malformed sample gets. Both cross the same typed channel.
    assert_ne!(EngineError::BufferUnderrun, EngineError::InternalError);
}
