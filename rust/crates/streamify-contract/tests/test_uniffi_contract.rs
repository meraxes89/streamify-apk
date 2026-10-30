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

use streamify_contract::{
    AudioErrorKind, AudioStreamConfig, BufferStats, EngineError, PlaybackState, StreamifyEngine,
};

/// Shape assertion helper (multiplatform Phase 3: EngineError carries
/// payloads, so the old flat `assert_eq!(.., Err(EngineError::X))`
/// becomes a variant match).
fn assert_internal(err: Result<impl std::fmt::Debug, EngineError>) {
    assert!(
        matches!(err, Err(EngineError::Internal { .. })),
        "expected EngineError::Internal"
    );
}

/// A fresh engine is dormant: Idle lifecycle, empty buffers, unity gain.
#[test]
fn new_engine_is_dormant() {
    let engine = StreamifyEngine::new();
    assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Idle);
    assert_eq!(engine.get_buffer_stats().unwrap(), BufferStats::default());
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
    assert_eq!(
        engine.get_playback_state().unwrap(),
        PlaybackState::Buffering
    );
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
    assert_eq!(
        engine.get_playback_state().unwrap(),
        PlaybackState::Buffering
    );

    engine.set_playback_state(PlaybackState::Playing);
    for _ in 0..3 {
        engine
            .initialize()
            .expect("idempotent re-arm after progression");
    }
    assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Playing);

    // Even a forced Error state is terminal for the lifecycle logic:
    // initialize() must not "revive" it (recovery is a control-plane
    // decision, not an arming side effect).
    engine.set_playback_state(PlaybackState::Error);
    engine
        .initialize()
        .expect("re-arm on a faulted engine still verifies the environment");
    assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Error);
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
    engine.initialize().expect("healthy host arms cleanly");
    assert_eq!(
        engine.get_playback_state().unwrap(),
        PlaybackState::Buffering
    );
    // Walk the control-plane legs; feed telemetry along the way.
    for (state, buffered) in transitions {
        engine.set_playback_state(state);
        if let Some(ms) = buffered {
            engine
                .record_buffer_stats(ms, 1, 0.5)
                .expect("finite telemetry must be accepted");
        }
        assert_eq!(engine.get_playback_state().unwrap(), state);
        let stats = engine.get_buffer_stats().unwrap();
        assert!(stats.fill_ratio.is_finite());
        assert!((0.0..=1.0).contains(&stats.fill_ratio));
    }
    // Terminal state still answers reads: a faulted engine must keep
    // serving its last-known telemetry to diagnostics surfaces.
    assert_eq!(engine.get_buffer_stats().unwrap().buffered_duration_ms, 0);
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
    let stats = engine.get_buffer_stats().unwrap();
    assert_eq!(stats.buffered_duration_ms, 5_000);
    assert_eq!(stats.underrun_count, 0);
    assert!((stats.fill_ratio - 0.42).abs() < f32::EPSILON);

    // Malformed samples are rejected before ANY store runs.
    for garbage in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        // Multiplatform Phase 3 / MP-C04: the rejection is the structured
        // Internal variant (non-finite telemetry at the boundary).
        assert_internal(engine.record_buffer_stats(9_999, 9, garbage));
    }
    let stats = engine.get_buffer_stats().unwrap();
    assert_eq!(stats.buffered_duration_ms, 5_000);
    assert_eq!(stats.underrun_count, 0);
    assert!((stats.fill_ratio - 0.42).abs() < f32::EPSILON);

    // Finite overshoot clamps (watermark transitions legitimately overshoot).
    engine
        .record_buffer_stats(7_500, 2, 1.3)
        .expect("finite overshoot clamps, never rejects");
    assert!((engine.get_buffer_stats().unwrap().fill_ratio - 1.0).abs() < f32::EPSILON);

    // A producer-side pipeline restart (underrun epoch reset) is accepted.
    engine
        .record_buffer_stats(120, 0, 0.05)
        .expect("epoch reset must be accepted");
    assert_eq!(engine.get_buffer_stats().unwrap().underrun_count, 0);
}

/// set_volume() applies a clamped gain scalar; NaN garbage fails safe to
/// full mute instead of poisoning the DSP chain with NaN bits.
#[test]
fn volume_scalar_clamps_and_fails_safe() {
    let engine = StreamifyEngine::new();
    engine.set_volume(0.6).unwrap();
    assert!((engine.volume() - 0.6).abs() < f32::EPSILON);
    engine.set_volume(42.0).unwrap();
    assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
    engine.set_volume(-0.5).unwrap();
    assert!(engine.volume().abs() < f32::EPSILON);
    engine.set_volume(f32::NAN).unwrap();
    assert!(engine.volume().abs() < f32::EPSILON);
    assert!(engine.volume().is_finite());
    engine.set_volume(0.9).unwrap();
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
        assert!(
            matches!(
                degenerate.validate(),
                Err(EngineError::Audio {
                    kind: AudioErrorKind::FormatUnsupported,
                    ..
                })
            ),
            "geometry faults are Audio/FormatUnsupported"
        );
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
    engine.initialize().expect("healthy host arms cleanly");
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
                assert_internal(engine.record_buffer_stats(u64::MAX, u32::MAX, garbage));
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
                engine.set_volume((k % 101) as f32 / 100.0).unwrap();
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
                let state = engine.get_playback_state().unwrap();
                let stats = engine.get_buffer_stats().unwrap();
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
    let stats = engine.get_buffer_stats().unwrap();
    assert!(stats.fill_ratio.is_finite());
    assert!((0.0..=1.0).contains(&stats.fill_ratio));
    assert_ne!(stats.buffered_duration_ms, u64::MAX);
    assert_ne!(stats.underrun_count, u32::MAX);
}

// ═════════════════════════════════════════════════════════════════════
// Phase 4 (UFFI-03/04): Jam CRDT delta surface — golden-vector pinned
// ═══════════════════════════════════════════════════════════════════

fn jam_fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../fixtures/jam_golden_vectors.json")
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
    assert!(
        rows.len() >= 5,
        "fixture must carry the five frozen vectors"
    );
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
        engine
            .create_jam_session("sensitivity".to_string())
            .unwrap();
        engine
            .apply_jam_delta(full_delta.clone())
            .unwrap()
            .state_hash
    };

    // Different session NAME + same content => same hash (content-only).
    let renamed = {
        let engine = StreamifyEngine::new();
        engine
            .create_jam_session("a-completely-other-room".to_string())
            .unwrap();
        engine
            .apply_jam_delta(full_delta.clone())
            .unwrap()
            .state_hash
    };
    assert_eq!(
        base, renamed,
        "the hash covers content, not session identity"
    );

    // Dropping the last op changes the converged state.
    let short_delta: Vec<u8> = rows[..rows.len() - 1]
        .iter()
        .flat_map(|(_, r)| r.iter().copied())
        .collect();
    let short = {
        let engine = StreamifyEngine::new();
        engine
            .create_jam_session("sensitivity".to_string())
            .unwrap();
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
        engine
            .create_jam_session("sensitivity".to_string())
            .unwrap();
        let result = engine.apply_jam_delta(corrupted).unwrap();
        assert!(result.ops_rejected >= 1, "corrupt row must be rejected");
        result.state_hash
    };
    assert_ne!(base, rejected, "a rejected row changes the converged fold");
}

// ═════════════════════════════════════════════════════════════════
// Phase 4 (AMP-10) + multiplatform Phase 3 (MP-C10): SPSC generational
// flush — protocol mirror stress
// ═════════════════════════════════════════════════════════════════

/// Faithful Rust mirror of the GENERATIONAL consumer-cooperative flush
/// protocol shipped in native/include/audio_sink_ringbuffer.h (Phase 3
/// / MP-C10): producer-owned head (release store on commit),
/// consumer-owned tail (only EVER written by the consumer thread —
/// normal reads advance it, the flush pass resets it to the committed
/// head), a requester-owned flush_generation (fetch_add bump) and a
/// consumer-owned flush_ack_generation (release store of the serviced
/// generation). The capacity-bounded ring slots and the f32 payload are
/// deliberately abstracted away: the invariants under test are the
/// cursor discipline and the generation handshake, not the memcpy.
struct SpscFlushMirror {
    head: AtomicU64,
    tail: AtomicU64,
    /// Requester-owned: bumped by request_flush, never reset.
    flush_generation: AtomicU64,
    /// Consumer-owned: the newest generation the consumer has serviced.
    flush_ack_generation: AtomicU64,
}

impl SpscFlushMirror {
    fn new() -> Self {
        Self {
            head: AtomicU64::new(0),
            tail: AtomicU64::new(0),
            flush_generation: AtomicU64::new(0),
            flush_ack_generation: AtomicU64::new(0),
        }
    }

    /// Control/UI thread: bump the generation, then bounded-wait for the
    /// consumer's ack (mirrors RequestFlush + kFlushAckTimeoutMs on the
    /// C++ side; the wait must never be called from the consumer thread
    /// itself — self-ack is impossible and the wait would burn the
    /// deadline).
    fn request_flush(&self) {
        let gen = self.flush_generation.fetch_add(1, Ordering::Relaxed) + 1;
        let deadline = std::time::Instant::now() + std::time::Duration::from_millis(100);
        while self.flush_ack_generation.load(Ordering::Acquire) < gen {
            if std::time::Instant::now() >= deadline {
                break; // consumer not servicing — flush applies lazily
            }
            std::thread::yield_now();
        }
    }

    /// Producer thread: commit `frames` (writeFloats_' head publication).
    fn produce(&self, frames: u64) {
        let h = self.head.load(Ordering::Relaxed);
        self.head.store(h + frames, Ordering::Release);
    }

    /// Consumer thread (readFloats_ edge): returns frames served. A
    /// GENERATION CHANGE triggers the flush pass — snap tail to the
    /// committed head, publish the ack, serve silence (0) for that call.
    /// Level-triggered and coalescing: N bumps before the next read
    /// collapse into one pass, and the consumer acks the newest observed
    /// generation in a single step.
    fn consume(&self, want: u64) -> u64 {
        let gen = self.flush_generation.load(Ordering::Acquire);
        if gen != self.flush_ack_generation.load(Ordering::Relaxed) {
            self.tail
                .store(self.head.load(Ordering::Acquire), Ordering::Release);
            self.flush_ack_generation.store(gen, Ordering::Release);
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
    ///
    /// NOTE: Must read `tail` BEFORE `head`. If `head` is read first, the
    /// producer and consumer can advance between the two loads, causing
    /// `tail` to appear greater than the previously sampled `head`. Reading
    /// `tail` first guarantees `t <= head(T_tail) <= head(T_head)`.
    fn occupancy_sane(&self) -> bool {
        let t = self.tail.load(Ordering::Acquire);
        let h = self.head.load(Ordering::Acquire);
        t <= h
    }
}

/// The exact production race AMP-10 closes, mirrored in Rust under the
/// Phase 3 generational protocol (MP-C10): a control thread hammers
/// request_flush() while the consumer reads and the producer commits
/// continuously. The pre-hardening hazard — the control thread writing
/// tail_ directly — would tear the cursor pair (tail > head, uint64
/// occupancy underflow). With the generational handshake, occupancy
/// stays sane on every observation, reads never exceed their request,
/// and the consumer acks every serviced generation. The C++ side pins
/// the same invariants against the real ring
/// (native/tests/test_audio_sink_ringbuffer.cpp ::
/// test_cooperative_flush_concurrent_stress, 8507-check suite).
#[test]
fn spsc_cooperative_flush_survives_concurrent_read_stress() {
    const FLUSH_REQUESTS: u64 = 50_000;
    const CONSUME_ITERS: u64 = 500_000;

    let ring = Arc::new(SpscFlushMirror::new());
    let violations = Arc::new(AtomicU64::new(0));
    let stop = Arc::new(AtomicBool::new(false));
    // MP-C10: the generational handshake makes request_flush() WAIT for
    // the consumer's ack, so the consumer must stay live until every
    // request has been issued and serviced (a consumerless requester
    // would burn the 100 ms deadline per call).
    let requests_done = Arc::new(AtomicBool::new(false));
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
        let requests_done = Arc::clone(&requests_done);
        handles.push(thread::spawn(move || {
            barrier.wait();
            for i in 0..FLUSH_REQUESTS {
                ring.request_flush(); // noexcept + bounded on the C++ side
                if i % 256 == 0 {
                    std::thread::yield_now();
                }
            }
            requests_done.store(true, Ordering::Release);
        }));
    }

    // Consumer: the audio-callback-shaped loop (bounded lane, with the
    // MP-C10 epoch extension — keep servicing until the control lane's
    // requests have all drained).
    {
        let ring = Arc::clone(&ring);
        let violations = Arc::clone(&violations);
        let barrier = Arc::clone(&barrier);
        let requests_done = Arc::clone(&requests_done);
        handles.push(thread::spawn(move || {
            barrier.wait();
            let mut k: u64 = 0x9E37_79B9_7F4A_7C15;
            let mut i: u64 = 0;
            while i < CONSUME_ITERS {
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
                i += 1;
                if i == CONSUME_ITERS && !requests_done.load(Ordering::Acquire) {
                    i = 0; // fresh accounting epoch; keep acking requests
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
    engine.initialize().expect("healthy host arms cleanly");

    // Healthy epoch: well-buffered stereo pipeline.
    engine
        .record_buffer_stats(18_000, 0, 0.92)
        .expect("healthy sample publishes");
    let stats = engine.get_buffer_stats().unwrap();
    assert_eq!(stats.buffered_duration_ms, 18_000);
    assert_eq!(stats.underrun_count, 0);
    assert!((stats.fill_ratio - 0.92).abs() < f32::EPSILON);

    // Starvation timeline: three degrading samples.
    let timeline = [(12_000u64, 1u32, 0.55f32), (4_000, 3, 0.18), (250, 7, 0.01)];
    for (ms, underruns, fill) in timeline {
        engine
            .record_buffer_stats(ms, underruns, fill)
            .expect("finite starving sample publishes");
        let stats = engine.get_buffer_stats().unwrap();
        assert_eq!(stats.buffered_duration_ms, ms);
        assert_eq!(stats.underrun_count, underruns);
        assert!((stats.fill_ratio - fill).abs() < f32::EPSILON);
        assert!(stats.fill_ratio.is_finite());
    }

    // Mid-starvation garbage: rejected with the typed error, and the
    // last good epoch stays intact (no partial publication).
    for garbage in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
        assert_internal(engine.record_buffer_stats(u64::MAX, u32::MAX, garbage));
    }
    let stats = engine.get_buffer_stats().unwrap();
    assert_eq!(stats.buffered_duration_ms, 250);
    assert_eq!(stats.underrun_count, 7);
    assert!((stats.fill_ratio - 0.01).abs() < f32::EPSILON);

    // Bottoming out: the empty-buffer epoch (fill exactly 0.0).
    engine
        .record_buffer_stats(0, 9, 0.0)
        .expect("empty-buffer epoch publishes");
    let stats = engine.get_buffer_stats().unwrap();
    assert_eq!(stats.buffered_duration_ms, 0);
    assert_eq!(stats.underrun_count, 9);
    assert!(stats.fill_ratio.abs() < f32::EPSILON);

    // Producer-side pipeline restart (track switch): the underrun epoch
    // resets to zero and the snapshot reflects the fresh pipeline.
    engine
        .record_buffer_stats(9_000, 0, 0.75)
        .expect("epoch reset publishes");
    let stats = engine.get_buffer_stats().unwrap();
    assert_eq!(stats.underrun_count, 0);
    assert_eq!(stats.buffered_duration_ms, 9_000);
    assert!((stats.fill_ratio - 0.75).abs() < f32::EPSILON);

    // The underrun taxonomy itself stays distinct from the boundary
    // rejection: Audio/Underrun is the playback-fault variant the
    // control plane maps starvation to, Internal is what a malformed
    // sample gets (multiplatform Phase 3 / MP-C04 taxonomy). Both cross
    // the same typed channel with different stable codes.
    let underrun = EngineError::Audio {
        kind: AudioErrorKind::Underrun,
        device_id: None,
    };
    let internal = EngineError::Internal {
        error_message: "boundary rejection".to_string(),
        backtrace: None,
    };
    assert_ne!(underrun.stable_code(), internal.stable_code());
}

// ═══════════════════════════════════════════════════════════════════════════
//  SW-ENG1-03: the typed catalog lane (queue / equalizer / jam room state),
//  exercised through the same public surface the UniFFI trampolines
//  dispatch to — hermetic by construction (no network, no fixtures).
// ═══════════════════════════════════════════════════════════════════════════

use streamify_contract::{
    search_tracks, self_test_search_result, EqualizerBand, JamRoomState, PlaylistSummary,
    QueueItem, SearchResult, Track, EQUALIZER_BAND_COUNT,
};

/// A fully populated typed track (the shape the Swift lane round-trips).
fn typed_track(n: u32) -> Track {
    Track {
        id: format!("typed_{n:02}_abcdefghij"),
        title: format!("Typed Track {n:02}"),
        artist: "Fixture Artist".to_string(),
        album: "Typed Lane LP".to_string(),
        duration_ms: 180_000 + n as u64 * 1_000,
        thumbnail_url: Some(format!("https://i.ytimg.com/vi/typed_{n:02}/mqdefault.jpg")),
        // Alternating so the queue-lifecycle spot-check has both flag
        // values crossing the lane (index 2 is explicit, index 1 is not).
        is_explicit: n.is_multiple_of(2),
        resolved_stream_url: None,
    }
}

/// Queue lifecycle: enqueue returns gapless indices, the page reads back
/// with the enqueued_by attribution intact, and clear is idempotent.
#[test]
fn typed_queue_lifecycle_round_trips() {
    let engine = StreamifyEngine::new();

    // Blank attribution normalizes to "local" (the house default).
    let i0 = engine.enqueue_track(typed_track(0), "   ".to_string()).unwrap();
    let i1 = engine.enqueue_track(typed_track(1), "jam:peer-7".to_string()).unwrap();
    let i2 = engine.enqueue_track(typed_track(2), "local".to_string()).unwrap();
    assert_eq!((i0, i1, i2), (0, 1, 2));

    let queue: Vec<QueueItem> = engine.get_queue().unwrap();
    assert_eq!(queue.len(), 3);
    assert_eq!(queue[0].index, 0);
    assert_eq!(queue[0].enqueued_by, "local");
    assert_eq!(queue[1].enqueued_by, "jam:peer-7");
    // The typed Track crosses the lane field-for-field.
    assert_eq!(queue[2].track, typed_track(2));
    assert_eq!(queue[2].track.duration_ms, 182_000);
    assert!(queue[2].track.is_explicit);

    engine.clear_queue().unwrap();
    engine.clear_queue().unwrap(); // idempotent
    assert!(engine.get_queue().unwrap().is_empty());
}

/// Equalizer curve: the default set matches the DSP band plan, one band
/// write lands exactly, and hostile bands are rejected at the boundary.
#[test]
fn typed_equalizer_curve_reads_and_writes() {
    let engine = StreamifyEngine::new();

    let flat = engine.get_equalizer_bands().unwrap();
    assert_eq!(flat.len(), EQUALIZER_BAND_COUNT);
    for (i, band) in flat.iter().enumerate() {
        assert_eq!(band.band_index as usize, i);
        assert_eq!(band.gain_db, 0.0);
        assert_eq!(band.q_factor, EqualizerBand::DEFAULT_Q);
    }
    // The band centers are the DSP crate's octave plan (31.25 Hz … 16 kHz).
    assert_eq!(flat[0].frequency_hz, 31.25);
    assert_eq!(flat[9].frequency_hz, 16_000.0);

    let boosted = EqualizerBand {
        band_index: 3,
        frequency_hz: 250.0,
        gain_db: -4.5,
        q_factor: 0.8,
    };
    engine.set_equalizer_band(boosted).unwrap();
    let after = engine.get_equalizer_bands().unwrap();
    assert_eq!(after.len(), EQUALIZER_BAND_COUNT);
    assert_eq!(after[3], boosted);
    assert_eq!(after[2].gain_db, 0.0, "untouched bands stay flat");

    // Hostile input: out-of-range index, NaN gain, non-positive Q.
    let out_of_range = EqualizerBand {
        band_index: EQUALIZER_BAND_COUNT as u8,
        ..EqualizerBand::flat(0)
    };
    assert_internal(engine.set_equalizer_band(out_of_range));
    let nan_gain = EqualizerBand {
        gain_db: f32::NAN,
        ..EqualizerBand::flat(1)
    };
    assert_internal(engine.set_equalizer_band(nan_gain));
    let zero_q = EqualizerBand {
        q_factor: 0.0,
        ..EqualizerBand::flat(1)
    };
    assert_internal(engine.set_equalizer_band(zero_q));
    // Rejection left the curve untouched.
    assert_eq!(engine.get_equalizer_bands().unwrap()[1].q_factor, EqualizerBand::DEFAULT_Q);
}

/// Jam room state mirrors the active session and errors identically to
/// the hash surface when no session is live.
#[test]
fn typed_jam_room_state_mirrors_the_active_session() {
    let engine = StreamifyEngine::new();
    assert_internal(engine.get_jam_room_state());

    let handle = engine
        .create_jam_session("Studio Listening Room".to_string())
        .unwrap();
    let room: JamRoomState = engine.get_jam_room_state().unwrap();
    assert_eq!(room.room_code, "Studio Listening Room");
    assert!(room.is_host);
    assert_eq!(room.sync_offset_ms, 0.0);
    assert_eq!(room.participants.len(), 1);
    // The local replica's label carries the session id — the mesh
    // roster grows this list as peers join.
    assert_eq!(room.participants[0], format!("local:{}", handle.session_id));
}

/// The deterministic self-test fixture populates every SearchResult
/// field (the cross-FFI deserialization surface the Swift
/// UniFFIContractTests pin from the other side of the boundary).
#[test]
fn self_test_fixture_populates_every_field() {
    let page: SearchResult = self_test_search_result(5).unwrap();
    assert_eq!(page.tracks.len(), 5);
    assert_eq!(page.playlists.len(), 1);
    assert_eq!(page.continuation_token.as_deref(), Some("selftest-continuation-token"));

    let first = &page.tracks[0];
    assert!(first.id.starts_with("selftest_00_"));
    assert_eq!(first.duration_ms, 60_000);
    assert!(first.thumbnail_url.is_some());
    // Explicit flags vary across the fixture (every 5th row: 0, 5, 10…).
    assert!(page.tracks[0].is_explicit);
    assert!(!page.tracks[1].is_explicit);
    assert!(!page.tracks[4].is_explicit);
    assert!(page.tracks.iter().all(|t| t.resolved_stream_url.is_none()));

    let playlist: &PlaylistSummary = &page.playlists[0];
    assert_eq!(playlist.track_count, 12);
    assert!(playlist.thumbnail_url.is_some());

    // The clamp: a hostile count is bounded, never a huge allocation.
    let clamped = self_test_search_result(10_000).unwrap();
    assert_eq!(clamped.tracks.len(), 64);
}

/// The typed search lane is a pure mapping over the resolver pipeline:
/// a blank query surfaces the resolver's own blank-argument Internal
/// error through the typed channel (hermetic — no network involved).
#[test]
fn typed_search_lane_rejects_blank_queries_through_the_channel() {
    assert_internal(search_tracks("   ".to_string(), 10));
}
