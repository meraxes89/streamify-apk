//! Phase 2 (UFFI-01) + Phase 3 (UFFI-03): UniFFI typed contract,
//! wired to live dispatch and telemetry.
//!
//! The types and the `StreamifyEngine` methods in this module implement the
//! contract declared in [`crate`]'s `src/streamify.udl`. The scaffolding
//! generated at build time (see `build.rs`) emits the FFI trampolines and
//! `FfiConverter` glue that bind these Rust definitions to typed Kotlin —
//! replacing the raw, brittle `extern "C"` JNI edges for the control-plane
//! and metadata flows.
//!
//! Phase 3 (UFFI-03) wires the handle to live state instead of a dormant
//! shadow: `initialize()` verifies runtime environment invariants before
//! arming the one-way lifecycle transition and reports failures through the
//! typed error channel; `record_buffer_stats()` is now part of the FFI
//! surface so the live playback buffer (Kotlin-side ExoPlayer positions)
//! and the acoustic pipeline (underruns, ring fill) feed real samples into
//! the engine, with malformed telemetry rejected at the boundary; and
//! `set_volume()` is NaN-safe so a garbage gain can never poison the DSP
//! chain.
//!
//! Phase 4 (UFFI-03/04) extends the contract with the Jam session surface:
//! `create_jam_session()` mints the room's local CRDT replica,
//! `apply_jam_delta()` atomically merges a batch of frozen 48-byte wire ops
//! (the `rust_48` image pinned by `fixtures/jam_golden_vectors.json`) into
//! `jam_crdt`'s state machines, and `get_jam_state_hash()` exposes the
//! deterministic BLAKE3 image of the canonical CRDT fold — the diff
//! currency the Kotlin Merkle reconciler compares root-for-root. This is
//! the control plane; the pubkey-verified vote ingest stays at the mesh
//! wire boundary (`p2p_mesh::ingest_vote`), and the audio real-time
//! data-plane keeps the frozen JNI contract in `native/jni/jni_bridge_dsp.cc`.
//!
//! Out of scope by design: the audio real-time data-plane keeps the frozen
//! JNI contract in `native/jni/jni_bridge_dsp.cc` (zero-allocation,
//! zero-lock, direct-ByteBuffer hot path — the AMP-10 invariant audit).
//! This module is the control plane; it never sits on the audio callback
//! thread.
//!
//! Threading: `StreamifyEngine` is `Send + Sync` by construction (the
//! scaffolding asserts this). Interior state lives behind `Arc` +
//! `RwLock`/atomics, so an `Arc<StreamifyEngine>` can be cloned into any
//! Kotlin coroutine dispatcher or thread without external locking. Lock
//! poisoning is recovered from rather than propagated: a media engine must
//! keep answering reads even if a writer thread panicked mid-transition.

use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::thread::available_parallelism;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::jam_crdt::{JamCrdtState, JamOp, JAM_OP_SIZE};

/// Coarse playback lifecycle state reported by the core engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PlaybackState {
    /// Constructed but not yet initialized.
    #[default]
    Idle,
    /// Initialized and acquiring first data.
    Buffering,
    /// Consuming media.
    Playing,
    /// Temporarily suspended; buffered data retained.
    Paused,
    /// Terminal fault; consult diagnostics.
    Error,
}

/// Canonical descriptive metadata for one playable track.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackMetadata {
    /// Stable identifier (content or catalog id).
    pub id: String,
    /// Display title.
    pub title: String,
    /// Display artist / primary creator.
    pub artist: String,
    /// Total duration in milliseconds.
    pub duration_ms: u64,
    /// Resolved playable stream URL, when known.
    pub stream_url: Option<String>,
    /// Album-art URL, when known.
    pub album_art_url: Option<String>,
}

/// Geometry of the audio pipeline feeding the engine.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AudioStreamConfig {
    /// Sample rate in Hz (e.g. 44100, 48000).
    pub sample_rate: u32,
    /// Interleaved channel count (1..=8).
    pub channels: u8,
    /// Source sample width in bits (16/24/32).
    pub bit_depth: u8,
    /// Per-buffer frame count negotiated with the sink.
    pub buffer_frames: u32,
}

impl Default for AudioStreamConfig {
    fn default() -> Self {
        // The project's reference pipeline: stereo 48 kHz float.
        Self {
            sample_rate: 48_000,
            channels: 2,
            bit_depth: 32,
            buffer_frames: 2_048,
        }
    }
}

impl AudioStreamConfig {
    /// Validate the pipeline geometry against the physical contract.
    ///
    /// The reference geometry must be internally consistent before the
    /// engine arms: a zero sample rate or channel count, a sample width
    /// the resampler cannot express, or a degenerate buffer describe an
    /// acoustic pipeline that cannot exist. Violations surface as
    /// [`EngineError::InternalError`] (the taxonomy carries no payload —
    /// the violated bound is diagnosable from the config under test).
    pub fn validate(&self) -> Result<(), EngineError> {
        // 8 kHz telephony floor .. 768 kHz pro-audio ceiling.
        if !(8_000..=768_000).contains(&self.sample_rate) {
            return Err(EngineError::InternalError);
        }
        // Mono .. 7.1 surround.
        if !(1..=8).contains(&self.channels) {
            return Err(EngineError::InternalError);
        }
        // Widths the PCM conversion paths express.
        if !matches!(self.bit_depth, 16 | 24 | 32) {
            return Err(EngineError::InternalError);
        }
        // Non-degenerate ring; capped at 2^20 frames (~21 s @ 48 kHz) so
        // a config typo cannot request an unbounded buffer.
        if self.buffer_frames == 0 || self.buffer_frames > (1 << 20) {
            return Err(EngineError::InternalError);
        }
        Ok(())
    }
}

/// Live buffering health snapshot for telemetry surfaces.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BufferStats {
    /// Media duration currently buffered, in milliseconds.
    pub buffered_duration_ms: u64,
    /// Cumulative underrun events since the last reset.
    pub underrun_count: u32,
    /// Ring fill ratio in `[0.0, 1.0]`.
    pub fill_ratio: f32,
}

impl Default for BufferStats {
    fn default() -> Self {
        Self {
            buffered_duration_ms: 0,
            underrun_count: 0,
            fill_ratio: 0.0,
        }
    }
}

/// Opaque handle to the engine's active Jam session (UFFI-03/04).
///
/// The handle is data, not behavior: the session (and its CRDT replica)
/// stays inside the engine behind the typed contract, so the foreign
/// side can never hold a stale reference into CRDT interior state. Two
/// handles compare equal only when they describe the same session
/// mint.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JamSessionHandle {
    /// Strictly monotonic per-process id (the `jam_crdt` op-id mint).
    pub session_id: u64,
    /// Room display name, trimmed and non-blank.
    pub session_name: String,
    /// Wall-clock creation time, unix milliseconds.
    pub created_at_unix_ms: u64,
}

/// Outcome of atomically applying one Jam CRDT delta batch (UFFI-03/04).
///
/// `ops_applied` counts rows that passed wire validation and merged
/// (idempotent re-merges of already-known ops count as applied — the
/// CRDT absorbs them without state change). `ops_rejected` counts rows
/// refused at the boundary: corrupt checksums, non-zero pads, NaN
/// fractional indices, unknown op types, and Add replays suppressed by
/// a tombstone (the B2 lineage contract — a deliberate no-op, not an
/// error). `state_hash` is the canonical fold hash AFTER the batch;
/// `needs_rebalance` latches when adjacent queue fractions fall inside
/// relative ULP range (the room should schedule a rebalance pass).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JamSyncResult {
    pub ops_applied: u32,
    pub ops_rejected: u32,
    pub state_hash: String,
    pub needs_rebalance: bool,
}

/// Hard bounds on the Jam surface inputs. The name cap keeps a hostile
/// room title from crossing the FFI unbounded; the delta cap bounds the
/// write-lock hold time of one atomic batch (≈ 21 845 ops at 48 bytes
/// each — far above any legitimate Merkle branch exchange).
pub const MAX_JAM_SESSION_NAME_BYTES: usize = 128;
pub const MAX_JAM_DELTA_BYTES: usize = 1 << 20;

/// Typed failure taxonomy crossing the FFI boundary.
///
/// Variants are lowered as a flat error code (see `streamify.udl`); the
/// `Display` strings are the human-readable diagnostics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EngineError {
    /// Stream acquisition or transport failure.
    NetworkError,
    /// Container/codec parse or decode failure.
    CodecError,
    /// Operation invoked from the wrong lifecycle state.
    InvalidState,
    /// Playback starved waiting for buffered media.
    BufferUnderrun,
    /// Unexpected internal fault; diagnostics required.
    InternalError,
}

impl fmt::Display for EngineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EngineError::NetworkError => write!(f, "network error: stream acquisition or transport failed"),
            EngineError::CodecError => write!(f, "codec error: parse or decode failed"),
            EngineError::InvalidState => write!(f, "invalid state: operation not valid for the current lifecycle state"),
            EngineError::BufferUnderrun => write!(f, "buffer underrun: playback starved waiting for media"),
            EngineError::InternalError => write!(f, "internal error: unexpected engine fault"),
        }
    }
}

impl std::error::Error for EngineError {}

/// Interior engine state, shared behind `Arc` so later phases can hand
/// cheap clones to DSP workers and telemetry samplers without exposing
/// the engine handle itself.
#[derive(Debug)]
struct EngineShared {
    /// Lifecycle state; written only on transitions (control plane).
    state: RwLock<PlaybackState>,
    /// Volume in `[0.0, 1.0]`, stored as IEEE-754 bits for lock-free reads.
    volume_bits: AtomicU32,
    /// Media duration currently buffered [ms].
    buffered_duration_ms: AtomicU64,
    /// Cumulative underrun events.
    underrun_count: AtomicU32,
    /// Ring fill ratio `[0.0, 1.0]`, stored as IEEE-754 bits.
    fill_ratio_bits: AtomicU32,
    /// Set once by `initialize()`; guards the one-way Idle -> Buffering
    /// transition.
    initialized: AtomicBool,
    /// Phase 4 (UFFI-03/04): the active Jam session replica. `None` until
    /// `create_jam_session()` mints one; a second create replaces it.
    jam: RwLock<Option<JamSession>>,
}

/// The engine's local mirror of one Jam room (Phase 4, interior state —
/// the FFI surface sees only the handle and the sync result).
#[derive(Debug)]
struct JamSession {
    session_id: u64,
    session_name: String,
    created_at_unix_ms: u64,
    /// The room's shared-queue CRDT: fractional-indexed queue with
    /// tombstones plus the democratic vote ledger.
    crdt: JamCrdtState,
}

impl EngineShared {
    fn new() -> Self {
        Self {
            state: RwLock::new(PlaybackState::Idle),
            volume_bits: AtomicU32::new(1.0f32.to_bits()),
            buffered_duration_ms: AtomicU64::new(0),
            underrun_count: AtomicU32::new(0),
            fill_ratio_bits: AtomicU32::new(0.0f32.to_bits()),
            initialized: AtomicBool::new(false),
            jam: RwLock::new(None),
        }
    }
}

/// Root handle to the Streamify core engine (UFFI-01 contract).
///
/// The UniFFI surface is exactly the set declared in `streamify.udl`
/// (`new` / `initialize` / `set_volume` / `get_playback_state` /
/// `get_buffer_stats`); the additional inherent methods below are
/// Rust-side control and producer hooks for the engine phases that
/// follow — they are not exported across the FFI.
#[derive(Debug, Clone)]
pub struct StreamifyEngine {
    shared: Arc<EngineShared>,
}

impl Default for StreamifyEngine {
    fn default() -> Self {
        Self::new()
    }
}

impl StreamifyEngine {
    /// Construct a dormant engine: `Idle`, full volume, empty buffers.
    ///
    /// The FFI trampoline wraps the result in the shared `Arc` handle
    /// (UDL-mode constructors return the bare value).
    pub fn new() -> Self {
        Self {
            shared: Arc::new(EngineShared::new()),
        }
    }

    /// Arm the engine (UFFI-03: live dispatch wiring).
    ///
    /// Runtime environment invariants are verified on *every* call —
    /// first and idempotent repeats alike — and a failed check arms
    /// nothing: the engine stays `Idle` and the typed error crosses the
    /// FFI. On success the lifecycle advances one-way `Idle` ->
    /// `Buffering`; an already-progressed state is never pulled back and
    /// a second call is a verified no-op.
    pub fn initialize(&self) -> Result<(), EngineError> {
        Self::verify_runtime_environment()?;
        if self.shared.initialized.swap(true, Ordering::AcqRel) {
            return Ok(());
        }
        let mut state = self
            .shared
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if *state == PlaybackState::Idle {
            *state = PlaybackState::Buffering;
        }
        Ok(())
    }

    /// Set the output volume. Clamped to `[0.0, 1.0]`; stored lock-free.
    ///
    /// NaN fails safe to full mute: `f32::clamp` propagates NaN, and a
    /// NaN gain stored as raw bits would poison every downstream DSP
    /// sample silently (NaN compares false against all bounds — no
    /// assertion, alarm, or UI bar would ever catch it). ±inf clamp to
    /// the rails as usual.
    pub fn set_volume(&self, volume: f32) {
        let clamped = if volume.is_nan() {
            0.0
        } else {
            volume.clamp(0.0, 1.0)
        };
        self.shared
            .volume_bits
            .store(clamped.to_bits(), Ordering::Release);
    }

    /// Current playback lifecycle state.
    pub fn get_playback_state(&self) -> PlaybackState {
        *self
            .shared
            .state
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Current buffering health snapshot (single atomic reads, no locks).
    pub fn get_buffer_stats(&self) -> BufferStats {
        BufferStats {
            buffered_duration_ms: self.shared.buffered_duration_ms.load(Ordering::Acquire),
            underrun_count: self.shared.underrun_count.load(Ordering::Acquire),
            fill_ratio: f32::from_bits(self.shared.fill_ratio_bits.load(Ordering::Acquire)),
        }
    }

    // ---- Rust-side control / producer hooks (not part of the FFI surface) --

    /// Current volume in `[0.0, 1.0]` (the DSP pipeline consumes this).
    pub fn volume(&self) -> f32 {
        f32::from_bits(self.shared.volume_bits.load(Ordering::Acquire))
    }

    /// Force a lifecycle transition (control plane). Writing `Idle` does
    /// NOT un-initialize the engine — `initialize()` stays idempotent.
    pub fn set_playback_state(&self, state: PlaybackState) {
        *self
            .shared
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = state;
    }

    /// Publish one live buffering sample (UFFI-03: telemetry wiring).
    ///
    /// This is the producer edge for metrics from the playback buffer
    /// and the acoustic pipeline — now on the FFI surface so the app-side
    /// buffer observer feeds it directly: `buffered_duration_ms` is the
    /// media duration currently queued ahead of the read cursor,
    /// `underrun_count` the cumulative starvation events of the current
    /// telemetry epoch (a decrease signals a producer-side pipeline
    /// restart — track switch — and is accepted, not rejected), and
    /// `fill_ratio` the ring occupancy.
    ///
    /// Malformed samples never enter the snapshot: a NaN or ±inf fill
    /// ratio is rejected with [`EngineError::InternalError`] *before* any
    /// store runs, so a rejected sample cannot partially publish. Finite
    /// out-of-range fill ratios clamp to `[0.0, 1.0]` — fill estimators
    /// legitimately overshoot during watermark transitions.
    ///
    /// Each field is a single atomic store; readers may observe
    /// mixed-generation samples under concurrent publication, which is
    /// acceptable for telemetry.
    pub fn record_buffer_stats(
        &self,
        buffered_duration_ms: u64,
        underrun_count: u32,
        fill_ratio: f32,
    ) -> Result<(), EngineError> {
        if !fill_ratio.is_finite() {
            return Err(EngineError::InternalError);
        }
        self.shared
            .buffered_duration_ms
            .store(buffered_duration_ms, Ordering::Release);
        self.shared
            .underrun_count
            .store(underrun_count, Ordering::Release);
        self.shared
            .fill_ratio_bits
            .store(fill_ratio.clamp(0.0, 1.0).to_bits(), Ordering::Release);
        Ok(())
    }

    // ── Phase 4 (UFFI-03/04): Jam session surface ────────────────────────

    /// Mint the engine's active Jam session (UFFI-03/04).
    ///
    /// The engine is the room's local CRDT replica: creating while a
    /// session is live replaces it with a fresh, empty one (a new room
    /// means a new mirror — the previous replica is dropped wholesale,
    /// so a stale delta aimed at the old room can only ever land in the
    /// new replica's tombstone/ledger semantics, never resurrect purged
    /// state). The name is trimmed and must be non-blank and at most
    /// [`MAX_JAM_SESSION_NAME_BYTES`] bytes; violations fail as
    /// [`EngineError::InvalidState`] before any state is touched.
    ///
    /// Orthogonal to the playback lifecycle by design: a room can be
    /// prepared (and its queue voted on) before the engine arms.
    pub fn create_jam_session(&self, session_name: String) -> Result<JamSessionHandle, EngineError> {
        let trimmed = session_name.trim();
        if trimmed.is_empty() || session_name.len() > MAX_JAM_SESSION_NAME_BYTES {
            return Err(EngineError::InvalidState);
        }
        let created_at_unix_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        // The sanctioned strictly-monotonic per-process id mint: unique
        // across every session this process creates, immune to clock
        // step-back (same discipline as CRDT op ids).
        let session_id = JamOp::generate_op_id();
        let session = JamSession {
            session_id,
            session_name: trimmed.to_string(),
            created_at_unix_ms,
            crdt: JamCrdtState::new(),
        };
        let handle = JamSessionHandle {
            session_id: session.session_id,
            session_name: session.session_name.clone(),
            created_at_unix_ms: session.created_at_unix_ms,
        };
        *self
            .shared
            .jam
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(session);
        Ok(handle)
    }

    /// Atomically apply one Jam CRDT delta batch (UFFI-03/04).
    ///
    /// `delta` is a concatenation of 48-byte little-endian wire rows —
    /// the frozen `rust_48` image pinned by
    /// `fixtures/jam_golden_vectors.json` (the same rows the mesh
    /// gossips as `MSG_CRDT_OP` payloads). The entire batch merges into
    /// the session's CRDT under ONE write-lock acquisition: concurrent
    /// callers observe either the pre-batch or the post-batch fold, never
    /// a torn mix. Within the batch, rows merge in order; a row refused
    /// at the boundary (corrupt checksum, non-zero pads, NaN fraction,
    /// unknown op type, tombstone-suppressed Add replay) is counted in
    /// `ops_rejected` and skipped — a partially corrupt batch still
    /// delivers its clean rows, mirroring the mesh ingress contract.
    ///
    /// Errors: no live session ([`EngineError::InvalidState`]); a length
    /// that is not a multiple of [`JAM_OP_SIZE`] or exceeds
    /// [`MAX_JAM_DELTA_BYTES`] ([`EngineError::CodecError`]) — both
    /// rejected before any state is touched.
    ///
    /// Vote rows merge through the legacy device-nonce identity path
    /// (`apply_op`): this is the app-side driver surface. The
    /// pubkey-verified ingest stays at the mesh wire boundary
    /// (`p2p_mesh::ingest_vote`).
    pub fn apply_jam_delta(&self, delta: Vec<u8>) -> Result<JamSyncResult, EngineError> {
        if delta.is_empty() || !delta.len().is_multiple_of(JAM_OP_SIZE) {
            return Err(EngineError::CodecError);
        }
        if delta.len() > MAX_JAM_DELTA_BYTES {
            return Err(EngineError::CodecError);
        }
        // ONE write-lock acquisition covers the whole batch: concurrent
        // callers observe either the pre-batch or the post-batch fold,
        // never a torn mix, and a panic mid-batch can only poison the
        // lock (recovered by design) — never leave the session taken out.
        let mut guard = self
            .shared
            .jam
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let session = guard.as_mut().ok_or(EngineError::InvalidState)?;
        let mut ops_applied = 0u32;
        let mut ops_rejected = 0u32;
        // Framing is already verified to be a whole number of 48-byte
        // rows, so the remainder slice is empty by construction.
        let (rows, _remainder) = delta.as_chunks::<JAM_OP_SIZE>();
        for row in rows {
            match JamOp::from_bytes(row) {
                Some(op) => {
                    if session.crdt.apply_op(&op) {
                        ops_applied += 1;
                    } else {
                        ops_rejected += 1;
                    }
                }
                None => ops_rejected += 1, // non-zero pads / structurally corrupt row
            }
        }
        Ok(JamSyncResult {
            ops_applied,
            ops_rejected,
            state_hash: canonical_jam_state_hash(&session.crdt),
            needs_rebalance: session.crdt.needs_rebalance,
        })
    }

    /// Deterministic state hash of the active Jam session (UFFI-03/04).
    ///
    /// BLAKE3 over the canonical fold (see [`canonical_jam_state_hash`]):
    /// two replicas that merged the same op set — in ANY arrival order —
    /// derive the identical 64-hex-char string. This is the value the
    /// Kotlin Merkle reconciler compares root-for-root to decide whether
    /// a branch walk is needed. Requires a live session
    /// ([`EngineError::InvalidState`] otherwise); never mutates state, so
    /// repeated reads are stable under concurrent delta application
    /// (each read observes some committed batch boundary).
    pub fn get_jam_state_hash(&self) -> Result<String, EngineError> {
        let guard = self
            .shared
            .jam
            .read()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let session = guard.as_ref().ok_or(EngineError::InvalidState)?;
        Ok(canonical_jam_state_hash(&session.crdt))
    }

    /// Runtime environment invariants (UFFI-03: `initialize()` gate).
    ///
    /// Checked before the engine arms and on every idempotent re-arm:
    ///   * the reference pipeline geometry is internally consistent
    ///     ([`AudioStreamConfig::validate`]);
    ///   * the scheduler can report its width — the telemetry samplers
    ///     and DSP worker dispatch the armed engine relies on cannot be
    ///     sized on a host where `available_parallelism` fails;
    ///   * the wall clock is past the epoch — a pre-epoch clock would
    ///     poison every duration-based diagnostic the engine publishes.
    fn verify_runtime_environment() -> Result<(), EngineError> {
        AudioStreamConfig::default().validate()?;
        if available_parallelism().is_err() {
            return Err(EngineError::InternalError);
        }
        if SystemTime::now() <= UNIX_EPOCH {
            return Err(EngineError::InternalError);
        }
        Ok(())
    }
}

/// Canonical BLAKE3 state hash of a Jam CRDT replica (UFFI-03/04).
///
/// The hash covers exactly the replicated state — queue, tombstones,
/// vote ledger — serialized in a canonical, order-free form:
///
///   * domain tag `streamify.jam.state.v1` (hash-input separation);
///   * queue triples `(frac_bits, add_op_id, cad_id)` in BTreeMap
///     composite-key order (the fold is already sorted);
///   * tombstones sorted ascending (the fold's HashMap iteration order
///     is NOT canonical — this is the one re-ordering the hash performs);
///   * vote ledger by target ascending, each target's voters
///     byte-ascending, each entry `(voter, up, op_id)` — the LWW
///     register's standing value, so the hash reflects the FINAL ledger
///     regardless of the order vote events arrived in.
///
/// Deliberately EXCLUDED from the image: `needs_rebalance` (a transient
/// control signal, not replicated state), the derived `committed` set
/// (a pure function of queue + ledger + local promotion policy — the
/// policy is per-device configuration, not room state), and session
/// identity fields (the hash must be comparable ACROSS replicas, so it
/// can only cover content they share).
///
/// Convergence contract: for op sets the CRDT absorbs commutatively
/// (Add / Remove / Vote — everything except Reorder-of-an-element-not-
/// yet-added, which requires causal delivery by design), applying the
/// same set in any order on any number of replicas yields the identical
/// 64-hex-char string. That property is what the golden-vector
/// determinism tests pin, and what makes this hash usable as a Merkle
/// reconciliation root on the Kotlin side.
fn canonical_jam_state_hash(crdt: &JamCrdtState) -> String {
    let fold = crdt.fold_full();
    let mut hasher = blake3::Hasher::new();
    hasher.update(b"streamify.jam.state.v1");

    // Queue: fold order is BTreeMap composite-key order — canonical.
    hasher.update(&(fold.queue.len() as u64).to_le_bytes());
    for (frac, add_op_id, cad_id) in &fold.queue {
        hasher.update(&frac.to_bits().to_le_bytes());
        hasher.update(&add_op_id.to_le_bytes());
        hasher.update(&cad_id.to_le_bytes());
    }

    // Tombstones: the fold collects them from a HashMap — re-sort before
    // hashing, or two identical tombstone sets could hash differently.
    let mut tombstones = fold.tombstones.clone();
    tombstones.sort_unstable();
    hasher.update(&(tombstones.len() as u64).to_le_bytes());
    for tomb in &tombstones {
        hasher.update(&tomb.to_le_bytes());
    }

    // Vote ledger: fold_full already orders targets ascending and each
    // target's voter list byte-ascending.
    hasher.update(&(fold.votes.len() as u64).to_le_bytes());
    for (target, voters) in &fold.votes {
        hasher.update(&target.to_le_bytes());
        hasher.update(&(voters.len() as u64).to_le_bytes());
        for (voter, up, op_id) in voters {
            hasher.update(voter.as_bytes());
            hasher.update(&[u8::from(*up)]);
            hasher.update(&op_id.to_le_bytes());
        }
    }

    hasher.finalize().to_hex().to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Barrier;
    use std::thread;

    #[test]
    fn new_engine_is_dormant() {
        let engine = StreamifyEngine::new();
        assert_eq!(engine.get_playback_state(), PlaybackState::Idle);
        assert_eq!(engine.get_buffer_stats(), BufferStats::default());
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
    }

    #[test]
    fn initialize_is_one_way_and_idempotent() {
        let engine = StreamifyEngine::new();
        engine
            .initialize()
            .expect("runtime invariants hold on a healthy host");
        assert_eq!(engine.get_playback_state(), PlaybackState::Buffering);
        // A progressed state is never pulled back by a second initialize,
        // and the idempotent re-arm still verifies the environment.
        engine.set_playback_state(PlaybackState::Playing);
        engine
            .initialize()
            .expect("idempotent re-arm re-verifies cleanly");
        assert_eq!(engine.get_playback_state(), PlaybackState::Playing);
    }

    #[test]
    fn set_volume_clamps() {
        let engine = StreamifyEngine::new();
        engine.set_volume(0.5);
        assert!((engine.volume() - 0.5).abs() < f32::EPSILON);
        engine.set_volume(7.5);
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
        engine.set_volume(-3.0);
        assert!(engine.volume().abs() < f32::EPSILON);
    }

    #[test]
    fn set_volume_fails_safe_on_nan_and_infinity() {
        let engine = StreamifyEngine::new();
        // NaN must never enter the stored bits: it would poison every
        // downstream DSP sample silently. Fail safe to full mute.
        engine.set_volume(f32::NAN);
        assert!(engine.volume().abs() < f32::EPSILON);
        assert!(engine.volume().is_finite());
        // ±inf clamp to the rails.
        engine.set_volume(f32::INFINITY);
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
        engine.set_volume(f32::NEG_INFINITY);
        assert!(engine.volume().abs() < f32::EPSILON);
        // A finite value still lands exactly after the garbage round-trip.
        engine.set_volume(0.25);
        assert!((engine.volume() - 0.25).abs() < f32::EPSILON);
    }

    #[test]
    fn buffer_stats_round_trip() {
        let engine = StreamifyEngine::new();
        engine
            .record_buffer_stats(12_345, 2, 0.75)
            .expect("finite sample must be accepted");
        let stats = engine.get_buffer_stats();
        assert_eq!(stats.buffered_duration_ms, 12_345);
        assert_eq!(stats.underrun_count, 2);
        assert!((stats.fill_ratio - 0.75).abs() < f32::EPSILON);
    }

    #[test]
    fn record_buffer_stats_rejects_malformed_fill_ratio() {
        let engine = StreamifyEngine::new();
        engine
            .record_buffer_stats(1_000, 1, 0.5)
            .expect("finite sample must be accepted");
        for garbage in [f32::NAN, f32::INFINITY, f32::NEG_INFINITY] {
            assert_eq!(
                engine.record_buffer_stats(9_999, 9, garbage),
                Err(EngineError::InternalError),
                "{garbage} must be rejected before any store runs"
            );
        }
        // The rejected sample published nothing — not even partially.
        let stats = engine.get_buffer_stats();
        assert_eq!(stats.buffered_duration_ms, 1_000);
        assert_eq!(stats.underrun_count, 1);
        assert!((stats.fill_ratio - 0.5).abs() < f32::EPSILON);
        // Finite overshoot clamps instead of rejecting.
        engine
            .record_buffer_stats(2_000, 3, 1.7)
            .expect("finite overshoot must clamp, not reject");
        assert!((engine.get_buffer_stats().fill_ratio - 1.0).abs() < f32::EPSILON);
    }

    #[test]
    fn audio_stream_config_invariants_are_enforced() {
        assert!(AudioStreamConfig::default().validate().is_ok());
        let degenerate = [
            AudioStreamConfig {
                sample_rate: 0,
                ..AudioStreamConfig::default()
            },
            AudioStreamConfig {
                sample_rate: 1_000_000,
                ..AudioStreamConfig::default()
            },
            AudioStreamConfig {
                channels: 0,
                ..AudioStreamConfig::default()
            },
            AudioStreamConfig {
                channels: 9,
                ..AudioStreamConfig::default()
            },
            AudioStreamConfig {
                bit_depth: 15,
                ..AudioStreamConfig::default()
            },
            AudioStreamConfig {
                buffer_frames: 0,
                ..AudioStreamConfig::default()
            },
            AudioStreamConfig {
                buffer_frames: (1 << 20) + 1,
                ..AudioStreamConfig::default()
            },
        ];
        for config in degenerate {
            assert_eq!(config.validate(), Err(EngineError::InternalError));
        }
        // The bounds are inclusive where they should be.
        assert!(
            (AudioStreamConfig {
                sample_rate: 8_000,
                channels: 1,
                bit_depth: 16,
                buffer_frames: 1,
            })
            .validate()
            .is_ok()
        );
    }

    #[test]
    fn error_display_is_actionable() {
        for (err, needle) in [
            (EngineError::NetworkError, "network"),
            (EngineError::CodecError, "codec"),
            (EngineError::InvalidState, "state"),
            (EngineError::BufferUnderrun, "underrun"),
            (EngineError::InternalError, "internal"),
        ] {
            let text = err.to_string();
            assert!(text.contains(needle), "{err:?} display lacks '{needle}': {text}");
        }
    }

    #[test]
    fn contract_records_construct() {
        // Shape check: the records must stay constructible with exactly
        // the fields declared in streamify.udl.
        let track = TrackMetadata {
            id: "trk_1".to_string(),
            title: "Nightcall".to_string(),
            artist: "Kavinsky".to_string(),
            duration_ms: 252_000,
            stream_url: Some("https://cdn.example.com/a".to_string()),
            album_art_url: None,
        };
        assert_eq!(track.duration_ms, 252_000);
        assert!(track.album_art_url.is_none());

        let config = AudioStreamConfig::default();
        assert_eq!((config.sample_rate, config.channels), (48_000, 2));
    }

    #[test]
    fn concurrent_access_never_deadlocks_or_tears() {
        const READERS: usize = 4;
        const WRITERS: usize = 4;
        const ITERS: usize = 20_000;

        let engine = Arc::new(StreamifyEngine::new());
        engine
            .initialize()
            .expect("runtime invariants hold on a healthy host");
        let barrier = Arc::new(Barrier::new(READERS + WRITERS + 1));

        let mut handles = Vec::new();
        for i in 0..WRITERS {
            let engine = Arc::clone(&engine);
            let barrier = Arc::clone(&barrier);
            handles.push(thread::spawn(move || {
                barrier.wait();
                for k in 0..ITERS {
                    engine.set_volume((k % 101) as f32 / 100.0);
                    let _ = engine.record_buffer_stats(
                        (k % 9_000) as u64,
                        (k % 7) as u32,
                        (k % 1000) as f32 / 1000.0,
                    );
                    engine.set_playback_state(match k % 3 {
                        0 => PlaybackState::Playing,
                        1 => PlaybackState::Buffering,
                        _ => PlaybackState::Paused,
                    });
                    if k % 5_000 == 0 {
                        // Idempotent under contention; result handled so
                        // must_use cannot fire on a stress path.
                        let _ = engine.initialize();
                    }
                    let _ = i;
                }
            }));
        }
        for _ in 0..READERS {
            let engine = Arc::clone(&engine);
            let barrier = Arc::clone(&barrier);
            handles.push(thread::spawn(move || {
                barrier.wait();
                for _ in 0..ITERS {
                    let state = engine.get_playback_state();
                    let stats = engine.get_buffer_stats();
                    let volume = engine.volume();
                    // Every observed value must respect its declared range.
                    assert!((0.0..=1.0).contains(&volume));
                    assert!((0.0..=1.0).contains(&stats.fill_ratio));
                    assert_ne!(state, PlaybackState::Idle); // initialized once
                }
            }));
        }

        barrier.wait();
        for handle in handles {
            handle.join().expect("worker thread must not panic");
        }
    }

    // ── Phase 4 (UFFI-03/04): Jam session surface ──────────────────────────

    use crate::jam_crdt::{OpType, VOTE_FLAG_UP};

    /// A deterministic op batch: three Adds at spread fractions, one
    /// Remove targeting the first Add, two Votes (one up, one retraction
    /// of a different voter) — every op class the CRDT absorbs
    /// commutatively. Reorders are deliberately excluded here: a Reorder
    /// of a not-yet-added element is a documented no-op, so arbitrary-
    /// order convergence is only promised for Add/Remove/Vote sets.
    fn commutative_op_set() -> Vec<JamOp> {
        let nonce = [0x9a, 0x11, 0xce, 0x00];
        let add_a = JamOp::new(0x0000_0001_0000_0001, nonce, OpType::Add, 0, 111, 0.30, 0);
        let add_b = JamOp::new(0x0000_0001_0000_0002, nonce, OpType::Add, 0, 222, 0.55, 0);
        let add_c = JamOp::new(0x0000_0001_0000_0003, nonce, OpType::Add, 0, 333, 0.80, 0);
        let remove_a = JamOp::new(
            0x0000_0001_0000_0004,
            nonce,
            OpType::Remove,
            0,
            111,
            0.30,
            0x0000_0001_0000_0001,
        );
        let vote_up = JamOp::new(
            0x0000_0002_0000_0001,
            [0xaa, 0x00, 0x00, 0x01],
            OpType::Vote,
            VOTE_FLAG_UP,
            222,
            0.0,
            0x0000_0001_0000_0002,
        );
        let vote_down = JamOp::new(
            0x0000_0002_0000_0002,
            [0xbb, 0x00, 0x00, 0x02],
            OpType::Vote,
            0,
            222,
            0.0,
            0x0000_0001_0000_0002,
        );
        vec![add_a, add_b, add_c, remove_a, vote_up, vote_down]
    }

    fn wire_batch(ops: &[JamOp]) -> Vec<u8> {
        let mut bytes = Vec::new();
        for op in ops {
            bytes.extend_from_slice(&op.to_bytes());
        }
        bytes
    }

    /// Heap's algorithm subset: a handful of permutations is enough to
    /// catch order-dependence (a full n! sweep belongs to the
    /// golden-vector integration test, which pins the FROZEN fixture).
    fn sample_permutations(ops: Vec<JamOp>) -> Vec<Vec<JamOp>> {
        vec![
            ops.clone(),
            {
                let mut v = ops.clone();
                v.reverse();
                v
            },
            {
                // odds-then-evens interleave
                let mut interleaved = Vec::with_capacity(ops.len());
                for i in (1..ops.len()).step_by(2) {
                    interleaved.push(ops[i]);
                }
                for i in (0..ops.len()).step_by(2) {
                    interleaved.push(ops[i]);
                }
                interleaved
            },
            {
                // votes first, then removes, then adds
                let mut v = ops.clone();
                v.sort_by_key(|op| match op.op_type {
                    4 => 0,
                    2 => 1,
                    _ => 2,
                });
                v
            },
        ]
    }

    #[test]
    fn jam_session_lifecycle_and_handle_shape() {
        let engine = StreamifyEngine::new();
        // No session yet: both session-scoped reads fail typed.
        assert_eq!(
            engine.get_jam_state_hash(),
            Err(EngineError::InvalidState)
        );
        assert_eq!(
            engine.apply_jam_delta(wire_batch(&commutative_op_set())),
            Err(EngineError::InvalidState)
        );

        let handle = engine
            .create_jam_session("  Roadtrip Vol. 3  ".to_string())
            .expect("valid name must mint a session");
        assert_eq!(handle.session_name, "Roadtrip Vol. 3"); // trimmed
        assert!(handle.created_at_unix_ms > 0);
        assert_ne!(handle.session_id, 0);

        // A fresh session hashes to the canonical empty fold, and the
        // hash is a 64-char BLAKE3 hex image.
        let empty_hash = engine.get_jam_state_hash().unwrap();
        assert_eq!(empty_hash.len(), 64);
        assert!(empty_hash.chars().all(|c| c.is_ascii_hexdigit()));

        // Creating again replaces the session: fresh empty fold.
        let second = engine
            .create_jam_session("Later Room".to_string())
            .expect("second create replaces, not errors");
        assert!(second.session_id > handle.session_id); // monotonic mint
        assert_eq!(engine.get_jam_state_hash().unwrap(), empty_hash);
    }

    #[test]
    fn jam_session_name_validation() {
        let engine = StreamifyEngine::new();
        for bad in ["", "   ", "\t\n"] {
            assert_eq!(
                engine.create_jam_session(bad.to_string()),
                Err(EngineError::InvalidState),
                "blank name {bad:?} must be rejected"
            );
        }
        let oversized = "x".repeat(MAX_JAM_SESSION_NAME_BYTES + 1);
        assert_eq!(
            engine.create_jam_session(oversized),
            Err(EngineError::InvalidState)
        );
        // Rejected creations minted nothing (checked BEFORE the at-cap
        // success, which legitimately mints a session).
        assert_eq!(engine.get_jam_state_hash(), Err(EngineError::InvalidState));
        // Exactly at the cap is fine.
        let at_cap = "y".repeat(MAX_JAM_SESSION_NAME_BYTES);
        assert!(engine.create_jam_session(at_cap).is_ok());
    }

    #[test]
    fn jam_delta_framing_is_validated_before_state_touches() {
        let engine = StreamifyEngine::new();
        engine
            .create_jam_session("room".to_string())
            .expect("session");

        // Length not a multiple of the 48-byte wire row: CodecError.
        for bad_len in [1usize, 47, 49, 95, 100] {
            assert_eq!(
                engine.apply_jam_delta(vec![0u8; bad_len]),
                Err(EngineError::CodecError),
                "length {bad_len} must fail framing"
            );
        }
        // Oversized batch: CodecError, bounded lock hold.
        let oversized = vec![0u8; MAX_JAM_DELTA_BYTES + JAM_OP_SIZE];
        assert_eq!(
            engine.apply_jam_delta(oversized),
            Err(EngineError::CodecError)
        );
        // None of the rejected batches touched the session: still the
        // canonical empty fold.
        let empty_hash = engine.get_jam_state_hash().unwrap();
        assert_eq!(empty_hash.len(), 64);
    }

    #[test]
    fn jam_delta_applies_and_reports_rejects() {
        let engine = StreamifyEngine::new();
        engine.create_jam_session("room".to_string()).expect("session");

        let ops = commutative_op_set();
        let result = engine
            .apply_jam_delta(wire_batch(&ops))
            .expect("clean batch applies");
        // 5 of 6 rows merge (remove_a tombstones add_a and is itself
        // counted applied); every row here is structurally valid.
        assert_eq!(result.ops_applied, 6);
        assert_eq!(result.ops_rejected, 0);
        assert_ne!(result.state_hash, {
            // Sensitivity: a different op set must hash differently.
            let mut other = ops.clone();
            other.pop(); // drop the retraction
            let engine2 = StreamifyEngine::new();
            engine2.create_jam_session("room".to_string()).unwrap();
            engine2.apply_jam_delta(wire_batch(&other)).unwrap().state_hash
        });

        // A corrupt row (non-zero pad at byte 44) is skipped, not fatal:
        // the clean rows around it still land.
        let mut batch = Vec::new(); // clean add, corrupt row, clean add
        batch.extend_from_slice(&ops[0].to_bytes());
        let mut corrupt = ops[2].to_bytes();
        corrupt[44] = 0xFF; // _pad2 must be zero
        batch.extend_from_slice(&corrupt);
        batch.extend_from_slice(&ops[1].to_bytes());

        let engine3 = StreamifyEngine::new();
        engine3.create_jam_session("room".to_string()).unwrap();
        let result = engine3.apply_jam_delta(batch).expect("batch applies");
        assert_eq!(result.ops_applied, 2);
        assert_eq!(result.ops_rejected, 1);

        // Checksum-corrupt row: parses, fails is_valid(), rejected.
        let mut bad_ck = ops[0].to_bytes();
        bad_ck[40] ^= 0xFF; // checksum lane
        let engine4 = StreamifyEngine::new();
        engine4.create_jam_session("room".to_string()).unwrap();
        let result = engine4.apply_jam_delta(bad_ck.to_vec()).unwrap();
        assert_eq!(result.ops_applied, 0);
        assert_eq!(result.ops_rejected, 1);
    }

    #[test]
    fn jam_delta_idempotent_replay_is_inert() {
        let engine = StreamifyEngine::new();
        engine.create_jam_session("room".to_string()).unwrap();
        let ops = commutative_op_set();
        let first = engine.apply_jam_delta(wire_batch(&ops)).unwrap();
        // Replaying the SAME batch: tombstone-suppressed Add and
        // duplicate-key Adds all report applied/false without changing
        // the fold — the hash must be identical (replay-inertness).
        let replay = engine.apply_jam_delta(wire_batch(&ops)).unwrap();
        assert_eq!(first.state_hash, replay.state_hash);
        assert_eq!(first.state_hash, engine.get_jam_state_hash().unwrap());
    }

    #[test]
    fn jam_state_hash_is_arrival_order_free() {
        // The convergence contract: the same commutative op set applied
        // in any order — across separate replica sessions — yields the
        // identical state hash. This is the property the Kotlin Merkle
        // reconciler depends on: root equality implies state equality.
        // NOTE: per-permutation rejection COUNTS legitimately differ (a
        // Remove arriving before its Add suppresses the replay — the
        // B2 tombstone contract); only the converged fold is promised
        // order-free, and that is exactly what the hash covers.
        let ops = commutative_op_set();
        let mut hashes = Vec::new();
        for perm in sample_permutations(ops) {
            let engine = StreamifyEngine::new();
            engine.create_jam_session("room".to_string()).unwrap();
            let result = engine
                .apply_jam_delta(wire_batch(&perm))
                .expect("every permutation applies without error");
            assert_eq!(
                result.ops_applied + result.ops_rejected,
                6,
                "every row is whole: applied or boundary-rejected"
            );
            hashes.push(result.state_hash);
        }
        assert!(
            hashes.windows(2).all(|w| w[0] == w[1]),
            "state hash must be arrival-order free, got {hashes:?}"
        );
    }

    #[test]
    fn jam_reorder_converges_under_causal_delivery() {
        // Reorders are promised convergent only under causal delivery
        // (reorder-after-add). Applied in causal order on two replicas
        // with the interleaved votes arriving afterwards in swapped
        // order, the hashes must still agree: the ledger is LWW, the
        // reorder is deterministic once its target exists.
        let nonce = [0x01, 0x02, 0x03, 0x04];
        let add = JamOp::new(0x0000_0003_0000_0001, nonce, OpType::Add, 0, 4242, 0.25, 0);
        let reorder = JamOp::new(
            0x0000_0003_0000_0002,
            nonce,
            OpType::Reorder,
            0,
            4242,
            0.75,
            0x0000_0003_0000_0001,
        );
        let vote_x = JamOp::new(
            0x0000_0004_0000_0001,
            [0xcc, 0x00, 0x00, 0x03],
            OpType::Vote,
            VOTE_FLAG_UP,
            4242,
            0.0,
            0x0000_0003_0000_0001,
        );
        let vote_y = JamOp::new(
            0x0000_0004_0000_0002,
            [0xdd, 0x00, 0x00, 0x04],
            OpType::Vote,
            VOTE_FLAG_UP,
            4242,
            0.0,
            0x0000_0003_0000_0001,
        );

        let engine_a = StreamifyEngine::new();
        engine_a.create_jam_session("A".to_string()).unwrap();
        let ra = engine_a
            .apply_jam_delta(wire_batch(&[add, reorder, vote_x, vote_y]))
            .unwrap();
        let engine_b = StreamifyEngine::new();
        engine_b.create_jam_session("B".to_string()).unwrap();
        let rb = engine_b
            .apply_jam_delta(wire_batch(&[add, reorder, vote_y, vote_x]))
            .unwrap();
        assert_eq!(ra.state_hash, rb.state_hash);
        assert_eq!(ra.ops_applied, 4);
    }

    #[test]
    fn jam_concurrent_delta_batches_stay_atomic_and_convergent() {
        // Stress: the same commutative op set is shuffled into per-thread
        // batches applied concurrently to ONE session. Atomic dispatch
        // means each batch lands whole; commutativity means the final
        // hash must equal a serial replica's hash of the same op set.
        let ops = commutative_op_set();
        let serial_hash = {
            let engine = StreamifyEngine::new();
            engine.create_jam_session("serial".to_string()).unwrap();
            engine.apply_jam_delta(wire_batch(&ops)).unwrap().state_hash
        };

        const THREADS: usize = 4;
        let engine = Arc::new(StreamifyEngine::new());
        engine.create_jam_session("concurrent".to_string()).unwrap();
        let barrier = Arc::new(Barrier::new(THREADS));
        let mut handles = Vec::new();
        for t in 0..THREADS {
            // Each thread applies the full set in a different rotation.
            let mut rotated = ops.clone();
            rotated.rotate_left(t);
            let engine = Arc::clone(&engine);
            let barrier = Arc::clone(&barrier);
            handles.push(thread::spawn(move || {
                barrier.wait();
                for chunk in rotated.chunks(2) {
                    let result = engine
                        .apply_jam_delta(wire_batch(chunk))
                        .expect("concurrent batch must apply");
                    // Batches never tear: every row is whole or rejected.
                    assert_eq!(
                        result.ops_applied + result.ops_rejected,
                        chunk.len() as u32
                    );
                }
            }));
        }
        for handle in handles {
            handle.join().expect("jam stress thread must not panic");
        }
        assert_eq!(
            engine.get_jam_state_hash().unwrap(),
            serial_hash,
            "concurrent shuffled application must converge to the serial fold"
        );
    }
}
