//! Phase 2 (UFFI-01) + Phase 3 (UFFI-03) + multiplatform Phase 3
//! (`MULTIPLATFORM_PLAN_27_10`): UniFFI typed contract, wired to live
//! dispatch, telemetry, push events and the panic safety boundary.
//!
//! The types and the `StreamifyEngine` methods in this module implement the
//! contract declared in [`crate`]'s `src/streamify.udl`. The scaffolding
//! generated at build time (see `build.rs`) emits the FFI trampolines and
//! `FfiConverter` glue that bind these Rust definitions to typed Kotlin /
//! Swift — replacing the raw, brittle `extern "C"` JNI edges for the
//! control-plane and metadata flows.
//!
//! Phase 3 (UFFI-03) wires the handle to live state instead of a dormant
//! shadow: `initialize()` verifies runtime environment invariants before
//! arming the one-way lifecycle transition and reports failures through the
//! typed error channel; `record_buffer_stats()` feeds real samples into the
//! engine, with malformed telemetry rejected at the boundary; and
//! `set_volume()` is NaN-safe so a garbage gain can never poison the DSP
//! chain.
//!
//! Phase 4 (UFFI-03/04) extends the contract with the Jam session surface:
//! `create_jam_session()` mints the room's local CRDT replica,
//! `apply_jam_delta()` atomically merges a batch of frozen 48-byte wire ops
//! (the `rust_48` image pinned by `fixtures/jam_golden_vectors.json`) into
//! `jam_crdt`'s state machines, and `get_jam_state_hash()` exposes the
//! deterministic BLAKE3 image of the canonical CRDT fold — the diff
//! currency the Kotlin Merkle reconciler compares root-for-root.
//!
//! Multiplatform Phase 3 (this revision) freezes the contract for the
//! universal-core era:
//!   * **MP-C01 / MP-DEC-03** — `DacPresentationClock`: the hardware HAL
//!     callback is the sole clock master; the engine ingests DAC
//!     presentation samples via `record_dac_clock()` and republishes them
//!     in every snapshot. Zero wall-clock interpolation.
//!   * **MP-C02 / MP-DEC-04** — `EngineEventListener`: coalesced push
//!     snapshots with monotonic revisions, dispatched on a dedicated
//!     background thread (never the audio thread, never re-entrantly).
//!     This is the polling-killer: UI shells subscribe instead of polling
//!     `get_playback_state()`.
//!   * **MP-C03 / MP-DEC-07** — `MediaCommand` + `on_media_command()`: the
//!     reverse control plane (headset / MPRIS / SMTC / RemoteCommand
//!     events) with the mandated 50-150 ms hardware debounce.
//!   * **MP-C04** — the structured, nested, `#[non_exhaustive]`
//!     `EngineError` taxonomy (`Network` / `Audio` / `Storage` / `Crypto` /
//!     `Internal`) with stable numeric codes (`stable_code()`).
//!   * **MP-C05** — `contract_version()` for fail-fast ABI verification.
//!   * **MP-C06** — the panic safety boundary: every FFI entrypoint is
//!     wrapped by [`ffi_shield`], which converts any Rust panic into
//!     `EngineError::Internal { error_message, backtrace }` so a panic NEVER
//!     unwinds into Kotlin or Swift (see `Cargo.toml` for the release
//!     profile's `panic = "abort"` backstop and its interaction with the
//!     shields).
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

use std::any::Any;
use std::fmt;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::{Arc, Mutex, OnceLock, RwLock};
use std::thread;
use std::thread::available_parallelism;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use streamify_jam::jam_crdt::{JamCrdtState, JamOp, JAM_OP_SIZE};

use streamify_core::media_command_key;

// SW-ENG1-03: hard bound on the engine's typed playback queue. The
// queue is an in-engine staging surface (the CRDT is the sovereign
// shared-queue currency for Jam rooms); the cap bounds the Vec's
// memory so a runaway foreign loop cannot grow it unbounded. 10 000
// rows is ~50x beyond any interactive session's working set.
const MAX_QUEUE_ROWS: usize = 10_000;

/// One staged row of the engine's typed playback queue (interior;
/// the FFI lane reads it back as [`QueueItem`] with the row's index).
#[derive(Debug, Clone)]
struct QueuedTrack {
    track: Track,
    enqueued_by: String,
}

// Re-export the contract's data types from their canonical home in
// streamify-core (Discussion #120): the UniFFI scaffolding resolves
// every UDL-declared type by bare name through these re-exports, and
// legacy `crate::contract::X` paths inside this workspace keep
// resolving unchanged.
pub use streamify_core::{
    contract_version, AudioErrorKind, AudioStreamConfig, BufferStats, CryptoErrorKind,
    DacPresentationClock, DAC_CLOCK_MAX_SAMPLE_RATE, DAC_CLOCK_MIN_SAMPLE_RATE, EngineError,
    EngineSnapshot, EqualizerBand, JamRoomState, JamSessionHandle, JamSyncResult,
    MAX_JAM_DELTA_BYTES, MAX_JAM_SESSION_NAME_BYTES, MediaCommand, MEDIA_COMMAND_DEBOUNCE_MS,
    NetworkErrorKind, PlaybackState, PlaylistSummary, QueueItem, SearchResult, StorageErrorKind,
    Track, TrackMetadata, CONTRACT_VERSION, EQUALIZER_BAND_COUNT,
};


// ============================================================================
//  MP-C06: the FFI panic shield
// ============================================================================

/// Extract a human-readable message from a `catch_unwind` payload.
///
/// The panic runtime boxes `&'static str` or `String` payloads "usually";
/// everything else degrades to a type name so diagnostics never
/// themselves panic.
fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&'static str>() {
        (*s).to_string()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        format!("non-string panic payload ({:?})", payload.type_id())
    }
}

/// Panic safety boundary for every UniFFI-exported entrypoint (MP-C06).
///
/// Wraps one method body in [`std::panic::catch_unwind`] and converts a
/// panic into [`EngineError::Internal`] carrying the panic message and a
/// captured backtrace, so a Rust panic can never unwind across the FFI
/// boundary into Kotlin or Swift. Layered defense:
///
///   1. THIS shield (primary): the method's own `Result` channel carries
///      the structured `Internal` error, so foreign code catches the typed
///      `EngineErrorException.InternalException` with error_message +
///      backtrace.
///      Active in every build configuration whose panic strategy unwinds
///      (debug, test, and any downstream binary that overrides the
///      profile).
///   2. UniFFI's own `rust_call` catch (backstop): panics outside a
///      shielded body (marshaling glue) become a generic foreign panic
///      exception instead of an unwind.
///   3. `panic = "abort"` in the release profile (fail-fast): a release
///      artifact aborts the process on panic before any of the above run
///      — the crash surfaces through the platform crash reporter instead.
///      See `rust/Cargo.toml` for the profile note; the shields remain
///      the specified contract for every unwind-built configuration and
///      for the panic-marshaling unit tests.
///
/// The captured backtrace is taken at the catch site (the shield frame),
/// which still shows the FFI entry path that faulted; it is captured with
/// `Backtrace::force_capture()` so it is populated even without
/// `RUST_BACKTRACE=1`.
pub(crate) fn ffi_shield<F, T>(op: &str, body: F) -> Result<T, EngineError>
where
    F: FnOnce() -> Result<T, EngineError>,
{
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(result) => result,
        Err(payload) => {
            let error_message = format!("panic under {op}: {}", panic_message(payload.as_ref()));
            let backtrace = std::backtrace::Backtrace::force_capture().to_string();
            Err(EngineError::Internal {
                error_message,
                backtrace: Some(backtrace),
            })
        }
    }
}

// ============================================================================
//  MP-C02: push event listener contract
// ============================================================================

/// Push event listener for coalesced engine state (MP-C02 / MP-DEC-04).
///
/// The engine dispatches [`Self::on_engine_event`] on a DEDICATED
/// background thread — never the real-time audio thread, never the
/// registration thread, never re-entrantly (a listener that mutates
/// engine state only schedules the next dispatch; it never recurses).
/// Snapshots are coalesced: bursts of state changes collapse into one
/// dispatch carrying the newest revision.
///
/// `Send + Sync` are part of the contract: implementations are invoked
/// from an arbitrary engine thread and must be internally synchronized
/// (the UniFFI scaffolding asserts `dyn EngineEventListener: Send +
/// Sync`, and the foreign callback dispatch handle maps are
/// thread-safe). A listener must not throw across the boundary: a
/// foreign exception (or a Rust panic during dispatch) is caught by the
/// engine's dispatch shield and reported; the dispatch thread survives.
pub trait EngineEventListener: Send + Sync {
    /// One coalesced snapshot with a strictly monotonic `rev`.
    fn on_engine_event(&self, snapshot: EngineSnapshot);
}

// ============================================================================
//  Interior engine state
// ============================================================================

/// Interior engine state, shared behind `Arc` so later phases can hand
/// cheap clones to DSP workers and telemetry samplers without exposing
/// the engine handle itself.
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
    // ── Multiplatform Phase 3 (MP-C01/C02/C03) ─────────────────────────
    /// Seqlock revision for [`EngineSnapshot`]: every accepted mutation
    /// stores its fields first, then bumps this (Release); snapshot
    /// readers verify stability with a double-load (Acquire).
    event_rev: AtomicU64,
    /// Latest hardware DAC presentation sample (MP-C01). None until the
    /// first `record_dac_clock`.
    dac_clock: Mutex<Option<DacPresentationClock>>,
    /// Registered push listeners (MP-C02), cloned out per dispatch pass.
    listeners: Mutex<Vec<Arc<dyn EngineEventListener>>>,
    /// Wake channel to the dispatch thread (MP-C02). `None` until the
    /// first listener registration spawns the dispatcher. Bounded
    /// capacity 1: a full channel means a wake is already pending, so
    /// mutators coalesce by construction (`try_send` failures are the
    /// coalescing signal, not an error).
    dispatch_tx: OnceLock<SyncSender<()>>,
    /// Debounce slot for the reverse media plane (MP-C03): the last
    /// ACCEPTED command's identity and acceptance instant.
    last_media_command: Mutex<Option<(u8, u64, Instant)>>,
    // ── SW-ENG1-03: the typed catalog lane's interior state ──────────
    /// The staged playback queue (typed lane). Read-snapshotted under
    /// one lock; NOT part of the pushed EngineSnapshot (that shape is
    /// frozen — see the UDL note on the typed catalog lane).
    queue: Mutex<Vec<QueuedTrack>>,
    /// The ten-band equalizer curve. Starts at the flat preset; band
    /// writes replace one row under the same lock.
    eq_bands: Mutex<Vec<EqualizerBand>>,
}

impl fmt::Debug for EngineShared {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        // Manual impl: the listener list holds `Arc<dyn EngineEventListener>`
        // (no Debug on the trait object) — report the count instead.
        f.debug_struct("EngineShared")
            .field("state", &self.state)
            .field(
                "volume",
                &f32::from_bits(self.volume_bits.load(Ordering::Relaxed)),
            )
            .field("buffered_duration_ms", &self.buffered_duration_ms)
            .field("underrun_count", &self.underrun_count)
            .field(
                "fill_ratio",
                &f32::from_bits(self.fill_ratio_bits.load(Ordering::Relaxed)),
            )
            .field("initialized", &self.initialized)
            .field("jam", &self.jam)
            .field("event_rev", &self.event_rev)
            .field("dac_clock", &self.dac_clock)
            .field(
                "listeners",
                &self.listeners.lock().map(|l| l.len()).unwrap_or(0),
            )
            .field("dispatcher_running", &self.dispatch_tx.get().is_some())
            .finish_non_exhaustive()
    }
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
            event_rev: AtomicU64::new(0),
            dac_clock: Mutex::new(None),
            listeners: Mutex::new(Vec::new()),
            dispatch_tx: OnceLock::new(),
            last_media_command: Mutex::new(None),
            queue: Mutex::new(Vec::new()),
            eq_bands: Mutex::new(EqualizerBand::default_set()),
        }
    }

    /// Publish one accepted mutation (MP-C02): bump the seqlock revision
    /// (Release — the field stores above are already visible), then wake
    /// the dispatcher. The wake is coalescing by construction: the
    /// channel holds at most one token, and a token already in flight
    /// guarantees the dispatcher will re-snapshot AFTER this bump.
    fn notify(&self) {
        self.event_rev.fetch_add(1, Ordering::Release);
        if let Some(tx) = self.dispatch_tx.get() {
            let _ = tx.try_send(());
        }
    }

    /// Build one consistent [`EngineSnapshot`] (seqlock read).
    ///
    /// Mutators store fields, then bump `event_rev` (Release). This
    /// reader loads the revision (Acquire), reads every field, then
    /// re-loads the revision: if it moved, a mutation interleaved and
    /// the read retries — the classic seqlock, so no accepted change can
    /// be permanently missed by a pushed snapshot. The retry budget
    /// bounds the pathological case (sustained mutation storm): the
    /// last read is then accepted, and the NEXT dispatch (the storm's
    /// own wake tokens are still pending) re-publishes fresh values.
    fn snapshot_seqlock(&self) -> EngineSnapshot {
        const MAX_RETRIES: usize = 64;
        let mut last = None;
        for _ in 0..MAX_RETRIES {
            let v1 = self.event_rev.load(Ordering::Acquire);
            let state = *self
                .state
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let volume = f32::from_bits(self.volume_bits.load(Ordering::Acquire));
            let buffer_stats = BufferStats {
                buffered_duration_ms: self.buffered_duration_ms.load(Ordering::Acquire),
                underrun_count: self.underrun_count.load(Ordering::Acquire),
                fill_ratio: f32::from_bits(self.fill_ratio_bits.load(Ordering::Acquire)),
            };
            let clock = *self
                .dac_clock
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let v2 = self.event_rev.load(Ordering::Acquire);
            let snapshot = EngineSnapshot {
                rev: v1,
                state,
                volume,
                buffer_stats,
                clock,
            };
            if v1 == v2 {
                return snapshot;
            }
            last = Some(snapshot);
        }
        // Sustained contention: return the newest (mixed-generation) read;
        // rev monotonicity across dispatches is still guaranteed by the
        // dispatcher's last-rev gate.
        last.expect("at least one read in the retry budget")
    }
}

// ============================================================================
//  The engine handle
// ============================================================================

/// Root handle to the Streamify core engine (UFFI-01 contract, frozen
/// for multiplatform in Phase 3 of `MULTIPLATFORM_PLAN_27_10`).
///
/// The UniFFI surface is exactly the set declared in `streamify.udl`;
/// every exported method is panic-shielded (MP-C06) and returns
/// `Result<_, EngineError>`. The additional inherent methods marked
/// Rust-side hooks are control/producer plumbing for engine phases —
/// they are not exported across the FFI.
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

    // ── MP-C02: push dispatch machinery (Rust-side) ────────────────────

    /// Spawn the dedicated dispatch thread on first listener
    /// registration (idempotent; concurrent registrations race safely —
    /// the `OnceLock` winner owns the thread, and every listener lives
    /// in shared state either way).
    fn ensure_dispatcher(shared: &Arc<EngineShared>) {
        if shared.dispatch_tx.get().is_some() {
            return; // dispatcher already armed
        }
        let (tx, rx) = sync_channel::<()>(1);
        if shared.dispatch_tx.set(tx).is_ok() {
            // The dispatcher holds a WEAK reference: the engine (and its
            // listeners) must be droppable while the thread parks on
            // recv(); when the last Arc<EngineShared> drops, the sender
            // drops, the channel closes, and the thread exits.
            let weak = Arc::downgrade(shared);
            let spawned = thread::Builder::new()
                .name("streamify-engine-dispatch".to_string())
                .spawn(move || Self::dispatch_loop(weak, rx));
            if spawned.is_err() {
                // Thread-spawn failure is resource exhaustion; the
                // registration call surfaces it through its panic shield.
                // The stale OnceLock entry means push stays dormant until
                // the process is restarted — acceptable for an OOM-class
                // fault, and recorded here for the postmortem.
                tracing::error!("engine dispatch thread failed to spawn; push events dormant");
            }
        }
    }

    /// The dedicated dispatch loop (MP-C02 / MP-DEC-04).
    ///
    /// Runs on its OWN thread for the engine's lifetime:
    ///   * **never the real-time audio thread** — this is a plain
    ///     control-plane thread; the audio data plane has no knowledge
    ///     of listeners;
    ///   * **never re-entrant** — listener callbacks run HERE; if a
    ///     callback mutates the engine, the mutation only queues a wake
    ///     token, which the loop services on its NEXT iteration. No
    ///     path dispatches from inside a dispatch;
    ///   * **coalesced** — the bounded(1) wake channel plus a drain pass
    ///     collapse bursts of mutations into a single snapshot push
    ///     carrying the newest revision;
    ///   * **rev-gated** — snapshots are only dispatched when their
    ///     revision strictly exceeds the last dispatched one, so
    ///     listeners observe a strictly monotonic `rev` sequence.
    ///
    /// A panicking or throwing listener (contract violation) is caught
    /// by the per-listener shield and reported; the loop and the
    /// remaining listeners survive.
    fn dispatch_loop(shared: WeakEngine, rx: Receiver<()>) {
        let mut last_rev: u64 = 0;
        // Park until a wake arrives; exit when the engine (and its
        // sender) is gone — the closed channel is the shutdown signal.
        while rx.recv().is_ok() {
            // Coalesce: drain every wake that queued while we were busy.
            while rx.try_recv().is_ok() {}
            // Borrow the engine for this pass only (Weak keeps the
            // engine droppable).
            let Some(shared) = shared.upgrade() else {
                break;
            };
            let snapshot = shared.snapshot_seqlock();
            if snapshot.rev == 0 || snapshot.rev <= last_rev {
                continue; // nothing new since the last dispatch
            }
            last_rev = snapshot.rev;
            // Snapshot the listener list under the lock, dispatch
            // outside it (a listener that registers more listeners — or
            // drops the engine handle — must not deadlock the loop).
            let listeners = shared
                .listeners
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .clone();
            for listener in &listeners {
                let dispatch = || listener.on_engine_event(snapshot.clone());
                if catch_unwind(AssertUnwindSafe(dispatch)).is_err() {
                    tracing::warn!(
                        rev = snapshot.rev,
                        "EngineEventListener panicked during dispatch; shielded, thread survives"
                    );
                }
            }
        }
    }

    /// Register a push event listener (MP-C02).
    ///
    /// The registration itself bumps the revision, so every listener —
    /// including the fresh one — receives a full snapshot on the
    /// dispatcher's next pass (cold-start hydration without polling).
    /// Listeners live for the engine's lifetime (removal surfaces are a
    /// later-phase contract decision; the registry is interior state).
    pub fn add_event_listener(
        &self,
        listener: Box<dyn EngineEventListener>,
    ) -> Result<u32, EngineError> {
        ffi_shield("StreamifyEngine::add_event_listener", move || {
            let arc: Arc<dyn EngineEventListener> = listener.into();
            Self::ensure_dispatcher(&self.shared);
            let count = {
                let mut listeners = self
                    .shared
                    .listeners
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                listeners.push(arc);
                listeners.len() as u32
            };
            // Bump so the dispatcher pushes a full snapshot to everyone
            // (including the fresh listener) on its next pass.
            self.shared.notify();
            Ok(count)
        })
    }

    /// One-shot coalesced snapshot (cold-start hydration companion to
    /// the push channel; MP-DEC-04 abolishes POLLING LOOPS, not reads).
    pub fn get_engine_snapshot(&self) -> Result<EngineSnapshot, EngineError> {
        ffi_shield("StreamifyEngine::get_engine_snapshot", move || {
            Ok(self.shared.snapshot_seqlock())
        })
    }

    // ── MP-C01: hardware presentation clock ingest ─────────────────────

    /// Record one hardware DAC presentation clock sample (MP-C01).
    ///
    /// The platform HAL adapter calls this from its callback context
    /// (never the engine's audio thread). The sample is validated: the
    /// DAC nominal rate must be a sane hardware rate
    /// ([`DAC_CLOCK_MIN_SAMPLE_RATE`..=DAC_CLOCK_MAX_SAMPLE_RATE`]); a
    /// malformed sample fails as [`EngineError::Audio`] with kind
    /// [`AudioErrorKind::InvalidClock`] BEFORE any state is touched.
    /// Accepted samples replace the previous one (the HAL is the clock
    /// master — the engine records, it does not smooth) and wake the
    /// dispatcher.
    pub fn record_dac_clock(&self, clock: DacPresentationClock) -> Result<(), EngineError> {
        ffi_shield("StreamifyEngine::record_dac_clock", move || {
            if !(DAC_CLOCK_MIN_SAMPLE_RATE..=DAC_CLOCK_MAX_SAMPLE_RATE).contains(&clock.sample_rate)
            {
                return Err(EngineError::Audio {
                    kind: AudioErrorKind::InvalidClock,
                    device_id: None,
                });
            }
            *self
                .shared
                .dac_clock
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(clock);
            self.shared.notify();
            Ok(())
        })
    }

    // ── MP-C03: reverse control plane ──────────────────────────────────

    /// Feed one reverse-plane media command (MP-C03).
    ///
    /// Applies the mandated 50-150 ms hardware debounce
    /// ([`MEDIA_COMMAND_DEBOUNCE_MS`]): an IDENTICAL command (same
    /// variant, same clamped payload) arriving inside the window is
    /// swallowed — the duplicate-delivery class of Bluetooth stacks and
    /// media-key ghosts. A DIFFERENT command always applies and resets
    /// the slot.
    ///
    /// Phase-3 semantics of the accepted commands (the sovereign state
    /// machine slice; full pipeline/queue wiring lands with the playback
    /// queue phase): `Play`/`Pause`/`TogglePlayPause`/`Stop` drive the
    /// lifecycle state, `SetVolume` applies the same clamp semantics as
    /// [`Self::set_volume`], and `Seek`/`Next`/`Previous` are
    /// acknowledged (rev bump) with their queue effects deferred to the
    /// queue engine integration.
    ///
    /// Returns `true` when the command was applied, `false` when the
    /// debounce swallowed it.
    pub fn on_media_command(&self, command: MediaCommand) -> Result<bool, EngineError> {
        ffi_shield("StreamifyEngine::on_media_command", move || {
            let key = media_command_key(&command);
            {
                let mut last = self
                    .shared
                    .last_media_command
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if let Some((last_kind, last_payload, accepted_at)) = *last {
                    let duplicate = last_kind == key.0
                        && last_payload == key.1
                        && accepted_at.elapsed() < Duration::from_millis(MEDIA_COMMAND_DEBOUNCE_MS);
                    if duplicate {
                        // Swallowed duplicates leave every observable
                        // (including the revision) untouched.
                        return Ok(false);
                    }
                }
                *last = Some((key.0, key.1, Instant::now()));
            }
            self.apply_media_command(command);
            self.shared.notify();
            Ok(true)
        })
    }

    /// Apply an accepted media command to the sovereign state machine.
    fn apply_media_command(&self, command: MediaCommand) {
        match command {
            MediaCommand::Play => {
                *self
                    .shared
                    .state
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner()) = PlaybackState::Playing;
            }
            MediaCommand::Pause => {
                *self
                    .shared
                    .state
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner()) = PlaybackState::Paused;
            }
            MediaCommand::TogglePlayPause => {
                let mut state = self
                    .shared
                    .state
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                *state = match *state {
                    PlaybackState::Playing => PlaybackState::Paused,
                    _ => PlaybackState::Playing,
                };
            }
            MediaCommand::Stop => {
                *self
                    .shared
                    .state
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner()) = PlaybackState::Idle;
            }
            MediaCommand::SetVolume { volume } => {
                // Same clamp semantics as set_volume (NaN -> full mute).
                let clamped = if volume.is_nan() {
                    0.0
                } else {
                    volume.clamp(0.0, 1.0)
                };
                self.shared
                    .volume_bits
                    .store(clamped.to_bits(), Ordering::Release);
            }
            // Queue-scoped intents: acknowledged (rev bump in the
            // caller), effects deferred to the queue engine phase.
            MediaCommand::Seek { .. } | MediaCommand::Next | MediaCommand::Previous => {}
        }
    }

    // ── core lifecycle + telemetry (UFFI-03) ───────────────────────────

    /// Arm the engine (UFFI-03: live dispatch wiring).
    ///
    /// Runtime environment invariants are verified on *every* call —
    /// first and idempotent repeats alike — and a failed check arms
    /// nothing: the engine stays `Idle` and the typed error crosses the
    /// FFI. On success the lifecycle advances one-way `Idle` ->
    /// `Buffering`; an already-progressed state is never pulled back and
    /// a second call is a verified no-op.
    pub fn initialize(&self) -> Result<(), EngineError> {
        ffi_shield("StreamifyEngine::initialize", move || {
            Self::verify_runtime_environment()?;
            let already = self.shared.initialized.swap(true, Ordering::AcqRel);
            if !already {
                let mut state = self
                    .shared
                    .state
                    .write()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                if *state == PlaybackState::Idle {
                    *state = PlaybackState::Buffering;
                }
                // The one-way Idle -> Buffering transition is a state
                // change: push it.
                self.shared.notify();
            }
            Ok(())
        })
    }

    /// Set the output volume. Clamped to `[0.0, 1.0]`; stored lock-free.
    ///
    /// NaN fails safe to full mute: `f32::clamp` propagates NaN, and a
    /// NaN gain stored as raw bits would poison every downstream DSP
    /// sample silently (NaN compares false against all bounds — no
    /// assertion, alarm, or UI bar would ever catch it). ±inf clamp to
    /// the rails as usual.
    pub fn set_volume(&self, volume: f32) -> Result<(), EngineError> {
        ffi_shield("StreamifyEngine::set_volume", move || {
            let clamped = if volume.is_nan() {
                0.0
            } else {
                volume.clamp(0.0, 1.0)
            };
            self.shared
                .volume_bits
                .store(clamped.to_bits(), Ordering::Release);
            self.shared.notify();
            Ok(())
        })
    }

    /// Current playback lifecycle state (one-shot read; UI shells should
    /// prefer the push channel via [`Self::add_event_listener`]).
    pub fn get_playback_state(&self) -> Result<PlaybackState, EngineError> {
        ffi_shield("StreamifyEngine::get_playback_state", move || {
            Ok(*self
                .shared
                .state
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner()))
        })
    }

    /// Current buffering health snapshot (single atomic reads, no locks).
    pub fn get_buffer_stats(&self) -> Result<BufferStats, EngineError> {
        ffi_shield("StreamifyEngine::get_buffer_stats", move || {
            Ok(BufferStats {
                buffered_duration_ms: self.shared.buffered_duration_ms.load(Ordering::Acquire),
                underrun_count: self.shared.underrun_count.load(Ordering::Acquire),
                fill_ratio: f32::from_bits(self.shared.fill_ratio_bits.load(Ordering::Acquire)),
            })
        })
    }

    // ---- Rust-side control / producer hooks (not part of the FFI surface) --

    /// Current volume in `[0.0, 1.0]` (the DSP pipeline consumes this).
    pub fn volume(&self) -> f32 {
        f32::from_bits(self.shared.volume_bits.load(Ordering::Acquire))
    }

    /// Force a lifecycle transition (control plane). Writing `Idle` does
    /// NOT un-initialize the engine — `initialize()` stays idempotent.
    /// Pushes the change to registered listeners.
    pub fn set_playback_state(&self, state: PlaybackState) {
        *self
            .shared
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = state;
        self.shared.notify();
    }

    /// Publish one live buffering sample (UFFI-03: telemetry wiring).
    ///
    /// This is the producer edge for metrics from the playback buffer
    /// and the acoustic pipeline — on the FFI surface so the app-side
    /// buffer observer feeds it directly: `buffered_duration_ms` is the
    /// media duration currently queued ahead of the read cursor,
    /// `underrun_count` the cumulative starvation events of the current
    /// telemetry epoch (a decrease signals a producer-side pipeline
    /// restart — track switch — and is accepted, not rejected), and
    /// `fill_ratio` the ring occupancy.
    ///
    /// Malformed samples never enter the snapshot: a NaN or ±inf fill
    /// ratio is rejected with [`EngineError::Internal`] *before* any
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
        ffi_shield("StreamifyEngine::record_buffer_stats", move || {
            if !fill_ratio.is_finite() {
                return Err(EngineError::Internal {
                    error_message: "malformed telemetry: non-finite fill ratio rejected at the boundary"
                        .to_string(),
                    backtrace: None,
                });
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
            self.shared.notify();
            Ok(())
        })
    }

    // ── Phase 4 (UFFI-03/04): Jam session surface ──────────────────────

    /// Mint the engine's active Jam session (UFFI-03/04).
    ///
    /// The engine is the room's local CRDT replica: creating while a
    /// session is live replaces it with a fresh, empty one (a new room
    /// means a new mirror — the previous replica is dropped wholesale,
    /// so a stale delta aimed at the old room can only ever land in the
    /// new replica's tombstone/ledger semantics, never resurrect purged
    /// state). The name is trimmed and must be non-blank and at most
    /// [`MAX_JAM_SESSION_NAME_BYTES`] bytes; violations fail as
    /// [`EngineError::Internal`] before any state is touched.
    ///
    /// Orthogonal to the playback lifecycle by design: a room can be
    /// prepared (and its queue voted on) before the engine arms.
    pub fn create_jam_session(
        &self,
        session_name: String,
    ) -> Result<JamSessionHandle, EngineError> {
        ffi_shield("StreamifyEngine::create_jam_session", move || {
            // Test-only fault injection (MP-C06 verification): a probe
            // name that panics INSIDE a real exported method, proving the
            // shield marshals it into EngineError::Internal. Never active
            // outside `cargo test`.
            #[cfg(test)]
            if session_name == "__panic_probe__" {
                panic!("injected panic in jam session mint: 0xDEADBEEF");
            }

            let trimmed = session_name.trim();
            if trimmed.is_empty() || session_name.len() > MAX_JAM_SESSION_NAME_BYTES {
                return Err(EngineError::Internal {
                    error_message: "invalid state: blank or oversized Jam session name rejected"
                        .to_string(),
                    backtrace: None,
                });
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
        })
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
    /// Errors: no live session (Internal); a length that is not a
    /// multiple of [`JAM_OP_SIZE`] or exceeds [`MAX_JAM_DELTA_BYTES`]
    /// (Internal) — both rejected before any state is touched.
    ///
    /// Vote rows merge through the legacy device-nonce identity path
    /// (`apply_op`): this is the app-side driver surface. The
    /// pubkey-verified ingest stays at the mesh wire boundary
    /// (`p2p_mesh::ingest_vote`).
    pub fn apply_jam_delta(&self, delta: Vec<u8>) -> Result<JamSyncResult, EngineError> {
        ffi_shield("StreamifyEngine::apply_jam_delta", move || {
            if delta.is_empty() || !delta.len().is_multiple_of(JAM_OP_SIZE) {
                return Err(EngineError::Internal {
                    error_message: format!(
                        "codec framing: jam delta length {} is not a multiple of the {JAM_OP_SIZE}-byte wire row",
                        delta.len()
                    ),
                    backtrace: None,
                });
            }
            if delta.len() > MAX_JAM_DELTA_BYTES {
                return Err(EngineError::Internal {
                    error_message: format!(
                        "codec framing: jam delta of {} bytes exceeds the {MAX_JAM_DELTA_BYTES}-byte batch bound",
                        delta.len()
                    ),
                    backtrace: None,
                });
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
            let session = guard.as_mut().ok_or_else(|| EngineError::Internal {
                error_message: "invalid state: no live Jam session for delta application".to_string(),
                backtrace: None,
            })?;
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
        })
    }

    /// Deterministic state hash of the active Jam session (UFFI-03/04).
    ///
    /// BLAKE3 over the canonical fold (see [`canonical_jam_state_hash`]):
    /// two replicas that merged the same op set — in ANY arrival order —
    /// derive the identical 64-hex-char string. This is the value the
    /// Kotlin Merkle reconciler compares root-for-root to decide whether
    /// a branch walk is needed. Requires a live session (Internal
    /// otherwise); never mutates state, so repeated reads are stable
    /// under concurrent delta application (each read observes some
    /// committed batch boundary).
    pub fn get_jam_state_hash(&self) -> Result<String, EngineError> {
        ffi_shield("StreamifyEngine::get_jam_state_hash", move || {
            let guard = self
                .shared
                .jam
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let session = guard.as_ref().ok_or_else(|| EngineError::Internal {
                error_message: "invalid state: no live Jam session to hash".to_string(),
                backtrace: None,
            })?;
            Ok(canonical_jam_state_hash(&session.crdt))
        })
    }

    // ── SW-ENG1-03: the typed catalog lane (queue / equalizer / jam) ──

    /// Append one track to the engine's staged playback queue.
    ///
    /// Returns the row's index (its position at insert time). A blank
    /// `enqueued_by` is normalized to `"local"`; the row cap
    /// ([`MAX_QUEUE_ROWS`]) is rejected as `Internal` BEFORE any state
    /// is touched. Lock poisoning is recovered from (house rule), so a
    /// panicked writer never takes the queue out.
    ///
    /// Deliberately does NOT bump the push revision: the frozen
    /// `EngineSnapshot` carries playback lifecycle state, not queue
    /// pages — a rev bump without queue content would be noise, and
    /// queue surfaces re-read after their own mutations (the UDL's
    /// typed-catalog-lane note).
    pub fn enqueue_track(
        &self,
        track: Track,
        enqueued_by: String,
    ) -> Result<u32, EngineError> {
        ffi_shield("StreamifyEngine::enqueue_track", move || {
            let mut queue = self
                .shared
                .queue
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if queue.len() >= MAX_QUEUE_ROWS {
                return Err(EngineError::Internal {
                    error_message: format!(
                        "invalid state: playback queue at the {MAX_QUEUE_ROWS}-row cap"
                    ),
                    backtrace: None,
                });
            }
            let by = if enqueued_by.trim().is_empty() {
                "local".to_string()
            } else {
                enqueued_by
            };
            let index = queue.len() as u32;
            queue.push(QueuedTrack { track, enqueued_by: by });
            Ok(index)
        })
    }

    /// Read the whole staged queue as one consistent page.
    ///
    /// One lock acquisition covers the whole mapping, so the returned
    /// indices are gapless 0..n and every row belongs to the same
    /// generation (a concurrent enqueue either landed before the read —
    /// visible — or after — absent; never torn).
    pub fn get_queue(&self) -> Result<Vec<QueueItem>, EngineError> {
        ffi_shield("StreamifyEngine::get_queue", move || {
            let queue = self
                .shared
                .queue
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            Ok(queue
                .iter()
                .enumerate()
                .map(|(index, row)| QueueItem {
                    index: index as u32,
                    track: row.track.clone(),
                    enqueued_by: row.enqueued_by.clone(),
                })
                .collect())
        })
    }

    /// Drop every staged queue row (idempotent; an empty queue is a
    /// success path, not an error).
    pub fn clear_queue(&self) -> Result<(), EngineError> {
        ffi_shield("StreamifyEngine::clear_queue", move || {
            self.shared
                .queue
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .clear();
            Ok(())
        })
    }

    /// The current ten-band equalizer curve.
    ///
    /// Starts at the flat preset (`EqualizerBand::default_set`: the
    /// octave-spaced centers pinned by the DSP crate, 0 dB, Butterworth
    /// Q). The returned Vec is a clone under one lock — internally
    /// consistent by construction.
    pub fn get_equalizer_bands(&self) -> Result<Vec<EqualizerBand>, EngineError> {
        ffi_shield("StreamifyEngine::get_equalizer_bands", move || {
            let bands = self
                .shared
                .eq_bands
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            Ok(bands.clone())
        })
    }

    /// Overwrite one band of the equalizer curve.
    ///
    /// Validation ([`EqualizerBand::validate`]) rejects an index outside
    /// 0..[`EQUALIZER_BAND_COUNT`], non-finite floats and non-positive Q
    /// as `Internal` before any state is touched. The stored band keeps
    /// the caller's frequency/Q (a foreign surface may tune them); the
    /// band INDEX is authoritative for which row is replaced.
    pub fn set_equalizer_band(&self, band: EqualizerBand) -> Result<(), EngineError> {
        ffi_shield("StreamifyEngine::set_equalizer_band", move || {
            band.validate()?;
            let mut bands = self
                .shared
                .eq_bands
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let slot = bands
                .iter_mut()
                .find(|b| b.band_index == band.band_index)
                // default_set() is gapless 0..10 and validate() rejected
                // out-of-range indices, so the slot always exists; the
                // fallback is belt-and-braces against future preset drift.
                .ok_or_else(|| EngineError::Internal {
                    error_message: format!(
                        "invalid state: equalizer slot {} missing from the preset",
                        band.band_index
                    ),
                    backtrace: None,
                })?;
            *slot = band;
            Ok(())
        })
    }

    /// Presentation state of the engine's active Jam room
    /// (SW-ENG1-03).
    ///
    /// `room_code` is the session name the host minted; `is_host` is
    /// true because the local replica that minted the room is by
    /// definition its host (a joined guest replica arrives with the
    /// mesh lane and will carry its own flag); `sync_offset_ms` is 0.0
    /// until the PTP Kalman-PLL offset crosses this contract;
    /// `participants` starts with the local replica's label and grows
    /// with the mesh roster. Requires a live session (`Internal`
    /// otherwise — same contract as `get_jam_state_hash`).
    pub fn get_jam_room_state(&self) -> Result<JamRoomState, EngineError> {
        ffi_shield("StreamifyEngine::get_jam_room_state", move || {
            let guard = self
                .shared
                .jam
                .read()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            let session = guard.as_ref().ok_or_else(|| EngineError::Internal {
                error_message: "invalid state: no live Jam session for room state".to_string(),
                backtrace: None,
            })?;
            Ok(JamRoomState {
                room_code: session.session_name.clone(),
                is_host: true,
                sync_offset_ms: 0.0,
                participants: vec![format!("local:{}", session.session_id)],
            })
        })
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
            return Err(EngineError::Internal {
                error_message: "environment: scheduler width unavailable".to_string(),
                backtrace: None,
            });
        }
        if SystemTime::now() <= UNIX_EPOCH {
            return Err(EngineError::Internal {
                error_message: "environment: wall clock at or before the epoch".to_string(),
                backtrace: None,
            });
        }
        Ok(())
    }
}

/// Alias keeping the dispatch-loop signature readable: the thread holds
/// the engine WEAKLY so engines are droppable while the thread parks.
type WeakEngine = std::sync::Weak<EngineShared>;

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
    use std::sync::mpsc as test_mpsc;
    use std::sync::Barrier;

    // ---- shared test listener -----------------------------------------------

    /// Channel-backed listener: every dispatch lands in a queue the test
    /// drains with timeouts. Thread-safe by construction (Mutex).
    struct ProbeListener {
        tx: Mutex<test_mpsc::Sender<EngineSnapshot>>,
    }

    impl EngineEventListener for ProbeListener {
        fn on_engine_event(&self, snapshot: EngineSnapshot) {
            // send never blocks (bounded channel below is drained or huge).
            let _ = self.tx.lock().unwrap().send(snapshot);
        }
    }

    /// Register a channel probe and return its receiver.
    fn probe(engine: &StreamifyEngine) -> test_mpsc::Receiver<EngineSnapshot> {
        let (tx, rx) = test_mpsc::channel();
        engine
            .add_event_listener(Box::new(ProbeListener { tx: Mutex::new(tx) }))
            .expect("probe listener registers");
        rx
    }

    /// Drain pending snapshots until the channel is momentarily empty.
    fn drain(rx: &test_mpsc::Receiver<EngineSnapshot>) -> Vec<EngineSnapshot> {
        let mut out = Vec::new();
        while let Ok(s) = rx.try_recv() {
            out.push(s);
        }
        out
    }

    /// Await the next snapshot with a generous CI timeout.
    fn next_snapshot(rx: &test_mpsc::Receiver<EngineSnapshot>) -> EngineSnapshot {
        rx.recv_timeout(Duration::from_secs(30))
            .expect("a snapshot must arrive within the timeout")
    }

    #[test]
    fn new_engine_is_dormant() {
        let engine = StreamifyEngine::new();
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Idle);
        assert_eq!(engine.get_buffer_stats().unwrap(), BufferStats::default());
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
        // The one-shot snapshot agrees (rev 0, no clock yet).
        let snap = engine.get_engine_snapshot().unwrap();
        assert_eq!(snap.rev, 0);
        assert_eq!(snap.state, PlaybackState::Idle);
        assert_eq!(snap.buffer_stats, BufferStats::default());
        assert!(snap.clock.is_none());
    }

    #[test]
    fn initialize_is_one_way_and_idempotent() {
        let engine = StreamifyEngine::new();
        engine
            .initialize()
            .expect("runtime invariants hold on a healthy host");
        assert_eq!(
            engine.get_playback_state().unwrap(),
            PlaybackState::Buffering
        );
        // A progressed state is never pulled back by a second initialize,
        // and the idempotent re-arm still verifies the environment.
        engine.set_playback_state(PlaybackState::Playing);
        engine
            .initialize()
            .expect("idempotent re-arm re-verifies cleanly");
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Playing);
    }

    #[test]
    fn set_volume_clamps() {
        let engine = StreamifyEngine::new();
        engine.set_volume(0.5).unwrap();
        assert!((engine.volume() - 0.5).abs() < f32::EPSILON);
        engine.set_volume(7.5).unwrap();
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
        engine.set_volume(-3.0).unwrap();
        assert!(engine.volume().abs() < f32::EPSILON);
    }

    #[test]
    fn set_volume_fails_safe_on_nan_and_infinity() {
        let engine = StreamifyEngine::new();
        // NaN must never enter the stored bits: it would poison every
        // downstream DSP sample silently. Fail safe to full mute.
        engine.set_volume(f32::NAN).unwrap();
        assert!(engine.volume().abs() < f32::EPSILON);
        assert!(engine.volume().is_finite());
        // ±inf clamp to the rails.
        engine.set_volume(f32::INFINITY).unwrap();
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
        engine.set_volume(f32::NEG_INFINITY).unwrap();
        assert!(engine.volume().abs() < f32::EPSILON);
        // A finite value still lands exactly after the garbage round-trip.
        engine.set_volume(0.25).unwrap();
        assert!((engine.volume() - 0.25).abs() < f32::EPSILON);
    }

    #[test]
    fn buffer_stats_round_trip() {
        let engine = StreamifyEngine::new();
        engine
            .record_buffer_stats(12_345, 2, 0.75)
            .expect("finite sample must be accepted");
        let stats = engine.get_buffer_stats().unwrap();
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
            let err = engine
                .record_buffer_stats(9_999, 9, garbage)
                .expect_err("non-finite fill ratio must be rejected");
            assert!(
                matches!(&err, EngineError::Internal { error_message, .. }
                    if error_message.contains("non-finite fill ratio")),
                "{garbage} must be rejected as Internal with a diagnostic, got {err:?}"
            );
        }
        // The rejected sample published nothing — not even partially.
        let stats = engine.get_buffer_stats().unwrap();
        assert_eq!(stats.buffered_duration_ms, 1_000);
        assert_eq!(stats.underrun_count, 1);
        assert!((stats.fill_ratio - 0.5).abs() < f32::EPSILON);
        // Finite overshoot clamps instead of rejecting.
        engine
            .record_buffer_stats(2_000, 3, 1.7)
            .expect("finite overshoot must clamp, not reject");
        assert!((engine.get_buffer_stats().unwrap().fill_ratio - 1.0).abs() < f32::EPSILON);
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
            let err = config.validate().expect_err("degenerate config");
            assert!(
                matches!(
                    &err,
                    EngineError::Audio {
                        kind: AudioErrorKind::FormatUnsupported,
                        ..
                    }
                ),
                "geometry faults are Audio/FormatUnsupported, got {err:?}"
            );
        }
        // The bounds are inclusive where they should be.
        assert!((AudioStreamConfig {
            sample_rate: 8_000,
            channels: 1,
            bit_depth: 16,
            buffer_frames: 1,
        })
        .validate()
        .is_ok());
    }

    #[test]
    fn error_display_is_actionable() {
        let cases = [
            (
                EngineError::Network {
                    kind: NetworkErrorKind::Timeout,
                    status: 0,
                    retry_after: 2_500,
                },
                "network",
            ),
            (
                EngineError::Audio {
                    kind: AudioErrorKind::Underrun,
                    device_id: Some("speaker_0".into()),
                },
                "audio",
            ),
            (
                EngineError::Storage {
                    kind: StorageErrorKind::Corrupt,
                },
                "storage",
            ),
            (
                EngineError::Crypto {
                    kind: CryptoErrorKind::KeyUnavailable,
                },
                "crypto",
            ),
            (
                EngineError::Internal {
                    error_message: "probe".into(),
                    backtrace: None,
                },
                "internal",
            ),
        ];
        for (err, needle) in cases {
            let text = err.to_string();
            assert!(
                text.contains(needle),
                "{err:?} display lacks '{needle}': {text}"
            );
        }
    }

    #[test]
    fn contract_version_is_pinned() {
        // MP-C05: the value is ABI-visible. Changing it is a breaking
        // contract change that must be a conscious decision.
        assert_eq!(contract_version(), CONTRACT_VERSION);
        assert_eq!(CONTRACT_VERSION, 1);
    }

    #[test]
    fn stable_error_codes_are_pinned() {
        // MP-C04: stable code space — category << 24 | kind << 16 | status.
        // These exact values are the persisted contract; renumbering any
        // of them is a breaking change.
        assert_eq!(
            EngineError::Network {
                kind: NetworkErrorKind::Timeout,
                status: 0,
                retry_after: 0
            }
            .stable_code(),
            0x0101_0000
        );
        assert_eq!(
            EngineError::Network {
                kind: NetworkErrorKind::Http,
                status: 503,
                retry_after: 1_200
            }
            .stable_code(),
            0x0105_01F7 // 503 == 0x1F7 in the status half-word
        );
        assert_eq!(
            EngineError::Audio {
                kind: AudioErrorKind::Underrun,
                device_id: None
            }
            .stable_code(),
            0x0204_0000
        );
        assert_eq!(
            EngineError::Storage {
                kind: StorageErrorKind::Full
            }
            .stable_code(),
            0x0305_0000
        );
        assert_eq!(
            EngineError::Crypto {
                kind: CryptoErrorKind::DecryptFailed
            }
            .stable_code(),
            0x0402_0000
        );
        assert_eq!(
            EngineError::Internal {
                error_message: "x".into(),
                backtrace: None
            }
            .stable_code(),
            0x0500_0000
        );
    }

    // ---- MP-C06: panic safety ------------------------------------------------

    #[test]
    fn shielded_panics_marshal_into_internal() {
        // Direct shield probe: a panicking body becomes
        // EngineError::Internal carrying the panic message + a backtrace.
        let err = ffi_shield("probe", || -> Result<(), EngineError> {
            panic!("boom 0xC0FFEE");
        })
        .expect_err("panicking body must marshal");
        match &err {
            EngineError::Internal { error_message, backtrace } => {
                assert!(
                    error_message.contains("boom 0xC0FFEE"),
                    "error_message must carry the panic text: {error_message}"
                );
                assert!(
                    error_message.contains("probe"),
                    "error_message must name the faulting operation: {error_message}"
                );
                let bt = backtrace
                    .as_ref()
                    .expect("backtrace must be captured via force_capture");
                assert!(!bt.is_empty());
            }
            other => panic!("wrong variant: {other:?}"),
        }
    }

    #[test]
    fn shielded_panics_marshal_non_string_payloads() {
        // Panic payloads that are neither &str nor String must degrade to
        // a type note, never panic the shield itself.
        let err = ffi_shield("probe", || -> Result<(), EngineError> {
            std::panic::panic_any(0x1234_u32);
        })
        .expect_err("non-string panic must marshal");
        assert!(matches!(err, EngineError::Internal { .. }));
    }

    #[test]
    fn real_method_panic_marshals_into_internal() {
        // MP-C06 end-to-end: a panic INSIDE a real exported method (the
        // cfg(test) injection in create_jam_session) crosses the shield
        // as a structured Internal error — the FFI boundary never sees
        // an unwind.
        let engine = StreamifyEngine::new();
        let err = engine
            .create_jam_session("__panic_probe__".to_string())
            .expect_err("injected panic must marshal");
        match &err {
            EngineError::Internal { error_message, backtrace } => {
                assert!(error_message.contains("0xDEADBEEF"));
                assert!(backtrace.is_some());
            }
            other => panic!("wrong variant: {other:?}"),
        }
        // The engine stays fully serviceable after the marshaled panic.
        engine
            .create_jam_session("healthy room".to_string())
            .expect("engine survives the marshaled panic");
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Idle);
    }

    // ---- MP-C01: DAC presentation clock --------------------------------------

    #[test]
    fn dac_clock_ingest_validates_and_publishes() {
        let engine = StreamifyEngine::new();
        // Out-of-envelope DAC rates are Audio/InvalidClock, rejected
        // before any state is touched.
        for bad in [0u32, 7_999, 192_001, u32::MAX] {
            let err = engine
                .record_dac_clock(DacPresentationClock {
                    dac_frame_position: 1,
                    sample_rate: bad,
                    monotonic_ns: 1,
                })
                .expect_err("out-of-envelope DAC rate");
            assert!(
                matches!(
                    &err,
                    EngineError::Audio {
                        kind: AudioErrorKind::InvalidClock,
                        ..
                    }
                ),
                "rate {bad} must be Audio/InvalidClock, got {err:?}"
            );
        }
        assert!(engine.get_engine_snapshot().unwrap().clock.is_none());

        // A healthy sample publishes into the snapshot.
        engine
            .record_dac_clock(DacPresentationClock {
                dac_frame_position: 48_000,
                sample_rate: 48_000,
                monotonic_ns: 1_000_000_000,
            })
            .expect("healthy clock sample");
        let snap = engine.get_engine_snapshot().unwrap();
        let clock = snap.clock.expect("clock present after first sample");
        assert_eq!(clock.dac_frame_position, 48_000);
        assert_eq!(clock.sample_rate, 48_000);
        assert_eq!(clock.monotonic_ns, 1_000_000_000);

        // The latest sample replaces the previous one (HAL is master).
        engine
            .record_dac_clock(DacPresentationClock {
                dac_frame_position: 96_000,
                sample_rate: 48_000,
                monotonic_ns: 2_000_000_000,
            })
            .expect("second clock sample");
        assert_eq!(
            engine
                .get_engine_snapshot()
                .unwrap()
                .clock
                .unwrap()
                .dac_frame_position,
            96_000
        );
    }

    // ---- MP-C02: push event dispatch -----------------------------------------

    #[test]
    fn listeners_receive_registration_snapshot_and_pushes() {
        let engine = StreamifyEngine::new();
        let rx = probe(&engine);

        // Registration itself publishes a snapshot (cold-start hydration).
        let first = next_snapshot(&rx);
        assert_eq!(first.rev, 1, "registration bumps the revision");
        assert_eq!(first.state, PlaybackState::Idle);
        assert!(first.clock.is_none());

        // Every accepted mutation pushes a strictly newer revision.
        engine.set_volume(0.4).unwrap();
        let second = next_snapshot(&rx);
        assert!(second.rev > first.rev);
        assert!((second.volume - 0.4).abs() < f32::EPSILON);

        engine
            .record_dac_clock(DacPresentationClock {
                dac_frame_position: 512,
                sample_rate: 48_000,
                monotonic_ns: 42,
            })
            .unwrap();
        let third = next_snapshot(&rx);
        assert!(third.rev > second.rev);
        assert_eq!(third.clock.unwrap().dac_frame_position, 512);

        engine.set_playback_state(PlaybackState::Playing);
        let fourth = next_snapshot(&rx);
        assert!(fourth.rev > third.rev);
        assert_eq!(fourth.state, PlaybackState::Playing);
    }

    #[test]
    fn pushes_carry_strictly_monotonic_revisions() {
        let engine = StreamifyEngine::new();
        let rx = probe(&engine);
        let _ = next_snapshot(&rx); // registration snapshot

        // A burst of mutations: the dispatcher coalesces, but every
        // delivered snapshot's rev must be strictly increasing and the
        // revision sequence must cover every accepted mutation.
        for k in 0u32..64 {
            engine
                .record_buffer_stats(k as u64, k, (k % 100) as f32 / 100.0)
                .unwrap();
        }
        // Convergence poll (the dispatcher wakes asynchronously): wait
        // until the authoritative snapshot carries the final revision —
        // registration (1) + 64 accepted samples.
        let expected_rev = 1u64 + 64;
        let deadline = Instant::now() + Duration::from_secs(30);
        while engine.get_engine_snapshot().unwrap().rev < expected_rev {
            if Instant::now() > deadline {
                panic!(
                    "revision did not converge to {expected_rev}: {}",
                    engine.get_engine_snapshot().unwrap().rev
                );
            }
            thread::sleep(Duration::from_millis(1));
        }
        // ...and the dispatcher has pushed it: poll the receiver until a
        // snapshot at (or beyond) the final revision lands.
        let mut last = 0u64;
        let deadline = Instant::now() + Duration::from_secs(30);
        loop {
            for snap in drain(&rx) {
                assert!(snap.rev > last, "rev must strictly increase: {snap:?}");
                last = snap.rev;
            }
            if last >= expected_rev {
                break;
            }
            assert!(Instant::now() < deadline, "final revision never pushed");
            thread::sleep(Duration::from_millis(1));
        }
        assert!(
            last >= expected_rev,
            "every accepted mutation bumps the revision"
        );
        let final_snapshot = engine.get_engine_snapshot().unwrap();
        assert_eq!(final_snapshot.rev, expected_rev);
        assert_eq!(final_snapshot.buffer_stats.underrun_count, 63);
    }

    #[test]
    fn multiple_listeners_all_receive_pushes() {
        let engine = StreamifyEngine::new();
        let rx_a = probe(&engine);
        let rx_b = probe(&engine);

        engine.set_volume(0.77).unwrap();
        for rx in [&rx_a, &rx_b] {
            // Drain to the newest: both listeners must observe the
            // volume change (registration snapshots may interleave).
            let mut saw_volume = false;
            for snap in drain(rx) {
                if (snap.volume - 0.77).abs() < f32::EPSILON {
                    saw_volume = true;
                }
            }
            if !saw_volume {
                let snap = next_snapshot(rx);
                saw_volume = (snap.volume - 0.77).abs() < f32::EPSILON;
            }
            assert!(saw_volume, "listener must observe the volume change");
        }
    }

    /// A listener that mutates the engine from INSIDE its callback: the
    /// dispatch loop must not recurse or deadlock — the mutation is
    /// serviced on the loop's next iteration.
    struct ReentrantListener {
        engine: Mutex<Option<StreamifyEngine>>,
        seen: Mutex<Vec<u64>>,
    }

    impl EngineEventListener for ReentrantListener {
        fn on_engine_event(&self, snapshot: EngineSnapshot) {
            self.seen.lock().unwrap().push(snapshot.rev);
            if let Some(engine) = self.engine.lock().unwrap().take() {
                // Mutate from the callback: schedules the NEXT dispatch,
                // never recurses.
                let _ = engine.set_volume(0.123);
            }
        }
    }

    #[test]
    fn listener_dispatch_is_never_reentrant() {
        let engine = StreamifyEngine::new();
        let rx = probe(&engine);
        let _ = next_snapshot(&rx); // registration snapshot

        let reentrant = Arc::new(ReentrantListener {
            engine: Mutex::new(Some(engine.clone())),
            seen: Mutex::new(Vec::new()),
        });
        engine
            .add_event_listener(Box::new(ReentrantProbeWrap(reentrant.clone())))
            .unwrap();
        // Exactly TWO further pushes are deterministic here: the
        // reentrant listener's registration snapshot (bumps rev), and
        // the set_volume issued from INSIDE its callback (schedules the
        // NEXT dispatch, never recurses). The loop keeps making progress
        // (no deadlock, no stack growth from recursion) while both land.
        let mut revisions = Vec::new();
        for _ in 0..2 {
            let snap = next_snapshot(&rx);
            revisions.push(snap.rev);
        }
        let seen = reentrant.seen.lock().unwrap().clone();
        assert!(!seen.is_empty());
        assert!(
            seen.windows(2).all(|w| w[0] < w[1]),
            "listener revisions strictly increase: {seen:?}"
        );
        // The re-entrant mutation took effect.
        assert!((engine.volume() - 0.123).abs() < f32::EPSILON);
        // And the channel quiesces: no further pushes arrive.
        thread::sleep(Duration::from_millis(50));
        assert!(drain(&rx).is_empty(), "exactly two pushes, then silence");
    }

    /// Wrapper giving `ReentrantListener` an owned clone-able handle.
    struct ReentrantProbeWrap(Arc<ReentrantListener>);

    impl EngineEventListener for ReentrantProbeWrap {
        fn on_engine_event(&self, snapshot: EngineSnapshot) {
            self.0.on_engine_event(snapshot);
        }
    }

    // ---- MP-C03: media commands + debounce ------------------------------------

    #[test]
    fn media_commands_drive_the_state_machine() {
        let engine = StreamifyEngine::new();
        assert!(engine.on_media_command(MediaCommand::Play).unwrap());
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Playing);
        assert!(engine.on_media_command(MediaCommand::Pause).unwrap());
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Paused);
        assert!(engine
            .on_media_command(MediaCommand::TogglePlayPause)
            .unwrap());
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Playing);
        // Second toggle: a real user's press is outside the debounce
        // window; an immediate identical toggle would be the BT duplicate
        // pattern and swallowed BY DESIGN.
        thread::sleep(Duration::from_millis(MEDIA_COMMAND_DEBOUNCE_MS + 30));
        assert!(engine
            .on_media_command(MediaCommand::TogglePlayPause)
            .unwrap());
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Paused);
        // Toggle from a non-playing state resumes. (Sleep past the
        // debounce window first: the previous media command was ALSO a
        // TogglePlayPause, and identical consecutive commands inside
        // the window are swallowed BY DESIGN — the BT double-delivery
        // contract.)
        thread::sleep(Duration::from_millis(MEDIA_COMMAND_DEBOUNCE_MS + 30));
        engine.set_playback_state(PlaybackState::Idle);
        assert!(engine
            .on_media_command(MediaCommand::TogglePlayPause)
            .unwrap());
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Playing);
        assert!(engine.on_media_command(MediaCommand::Stop).unwrap());
        assert_eq!(engine.get_playback_state().unwrap(), PlaybackState::Idle);
        // SetVolume applies the clamped scalar.
        assert!(engine
            .on_media_command(MediaCommand::SetVolume { volume: 2.5 })
            .unwrap());
        assert!((engine.volume() - 1.0).abs() < f32::EPSILON);
        assert!(engine
            .on_media_command(MediaCommand::SetVolume { volume: f32::NAN })
            .unwrap());
        assert!(engine.volume().abs() < f32::EPSILON);
        // NaN volume debounces against the SAME clamped payload (mute).
        assert!(!engine
            .on_media_command(MediaCommand::SetVolume { volume: f32::NAN })
            .unwrap());
        // Seek / Next / Previous are acknowledged.
        assert!(engine
            .on_media_command(MediaCommand::Seek {
                position_ms: 90_000
            })
            .unwrap());
        assert!(engine.on_media_command(MediaCommand::Next).unwrap());
        assert!(engine.on_media_command(MediaCommand::Previous).unwrap());
    }

    #[test]
    fn media_command_debounce_swallows_identical_duplicates() {
        let engine = StreamifyEngine::new();
        // First press applies.
        assert!(engine.on_media_command(MediaCommand::Play).unwrap());
        // Immediate duplicates (the Bluetooth double-delivery class) are
        // swallowed inside the window.
        for _ in 0..8 {
            assert!(
                !engine.on_media_command(MediaCommand::Play).unwrap(),
                "identical rapid duplicate must be debounced"
            );
        }
        // A DIFFERENT command is never debounced and resets the slot.
        assert!(engine.on_media_command(MediaCommand::Pause).unwrap());
        assert!(engine.on_media_command(MediaCommand::Pause).is_ok());
        assert!(!engine.on_media_command(MediaCommand::Pause).unwrap());
        // Different payloads are different commands: seeks scrub freely.
        assert!(engine
            .on_media_command(MediaCommand::Seek {
                position_ms: 10_000
            })
            .unwrap());
        assert!(engine
            .on_media_command(MediaCommand::Seek {
                position_ms: 20_000
            })
            .unwrap());
        assert!(!engine
            .on_media_command(MediaCommand::Seek {
                position_ms: 20_000
            })
            .unwrap());
    }

    #[test]
    fn media_command_debounce_window_expires() {
        let engine = StreamifyEngine::new();
        assert!(engine.on_media_command(MediaCommand::Play).unwrap());
        // The mandated window is 50-150 ms; our constant sits inside it.
        assert!(
            (50..=150).contains(&MEDIA_COMMAND_DEBOUNCE_MS),
            "debounce must honor the mandated 50-150ms envelope"
        );
        // Sleep past the window: the same command applies again.
        thread::sleep(Duration::from_millis(MEDIA_COMMAND_DEBOUNCE_MS + 30));
        assert!(
            engine.on_media_command(MediaCommand::Play).unwrap(),
            "identical command after the window must apply"
        );
    }

    #[test]
    fn media_command_pushes_snapshots() {
        let engine = StreamifyEngine::new();
        let rx = probe(&engine);
        let _ = next_snapshot(&rx);

        assert!(engine.on_media_command(MediaCommand::Play).unwrap());
        let snap = next_snapshot(&rx);
        assert_eq!(snap.state, PlaybackState::Playing);

        // A debounced (swallowed) command publishes NOTHING: no wake, no
        // rev bump.
        assert!(!engine.on_media_command(MediaCommand::Play).unwrap());
        assert!(drain(&rx).is_empty(), "swallowed command pushes nothing");
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

        // MP-C01/C02/C03 record shapes.
        let clock = DacPresentationClock {
            dac_frame_position: 1,
            sample_rate: 48_000,
            monotonic_ns: 2,
        };
        assert_eq!(clock.sample_rate, 48_000);
        let snapshot = EngineSnapshot {
            rev: 7,
            state: PlaybackState::Playing,
            volume: 0.5,
            buffer_stats: BufferStats::default(),
            clock: Some(clock),
        };
        assert_eq!(snapshot.rev, 7);
        assert!(matches!(
            MediaCommand::Seek { position_ms: 9 },
            MediaCommand::Seek { position_ms: 9 }
        ));
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
                    engine.set_volume((k % 101) as f32 / 100.0).unwrap();
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
                    let state = engine.get_playback_state().unwrap();
                    let stats = engine.get_buffer_stats().unwrap();
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

    #[test]
    fn concurrent_mutation_and_snapshot_seqlock_stays_consistent() {
        // The seqlock under fire: concurrent mutators + a snapshot reader
        // on another thread. Every returned snapshot must be internally
        // consistent enough to be useful (volume in range, fill in range)
        // and the reader must always make progress.
        let engine = Arc::new(StreamifyEngine::new());
        let stop = Arc::new(AtomicBool::new(false));
        let mut handles = Vec::new();
        for k in 0..4u32 {
            let engine = Arc::clone(&engine);
            let stop = Arc::clone(&stop);
            handles.push(thread::spawn(move || {
                let mut i = 0u32;
                while !stop.load(Ordering::Relaxed) {
                    engine.set_volume((i % 100) as f32 / 100.0).unwrap();
                    engine
                        .record_buffer_stats((i % 500) as u64, i, (i % 100) as f32 / 100.0)
                        .unwrap();
                    if k == 0 && i.is_multiple_of(64) {
                        let _ = engine.record_dac_clock(DacPresentationClock {
                            dac_frame_position: i as u64,
                            sample_rate: 48_000,
                            monotonic_ns: i as u64,
                        });
                    }
                    i += 1;
                }
            }));
        }
        let reader_engine = Arc::clone(&engine);
        let reader_stop = Arc::clone(&stop);
        let reader = thread::spawn(move || {
            let mut last_rev = 0u64;
            for _ in 0..50_000 {
                let snap = reader_engine.get_engine_snapshot().unwrap();
                assert!(snap.rev >= last_rev, "seqlock revision regressed");
                assert!((0.0..=1.0).contains(&snap.volume));
                assert!((0.0..=1.0).contains(&snap.buffer_stats.fill_ratio));
                last_rev = snap.rev;
                if reader_stop.load(Ordering::Relaxed) {
                    break;
                }
            }
        });
        thread::sleep(Duration::from_millis(150));
        stop.store(true, Ordering::Relaxed);
        for handle in handles {
            handle.join().expect("mutator must not panic");
        }
        reader.join().expect("snapshot reader must not panic");
    }

    #[test]
    fn engines_drop_cleanly_with_live_dispatchers() {
        // The dispatcher holds the engine WEAKLY: dropping every handle
        // must terminate the thread (no leak, no hang).
        let rx = {
            let engine = StreamifyEngine::new();
            let rx = probe(&engine);
            engine.set_volume(0.5).unwrap();
            rx
        };
        // Engine + probe dropped here; the dispatcher observes the closed
        // channel on its next wake (or immediately) and exits. Nothing to
        // assert beyond "this test completes" — a leaked dispatcher would
        // keep the process alive and hang the test binary at exit.
        drop(drain(&rx));
    }

    // ── Phase 4 (UFFI-03/04): Jam session surface ──────────────────────────

    use streamify_jam::jam_crdt::{OpType, VOTE_FLAG_UP};

    /// Jam-surface error expectations (the structured taxonomy carries
    /// payloads; the old flat-enum `assert_eq!(.., Err(InvalidState))`
    /// becomes a shape match).
    fn expect_internal_with(err: Result<impl std::fmt::Debug, EngineError>, needle: &str) {
        let err = err.expect_err("expected a typed Internal error");
        match &err {
            EngineError::Internal { error_message, .. } => assert!(
                error_message.contains(needle),
                "Internal error_message must mention '{needle}': {error_message}"
            ),
            other => panic!("expected Internal({needle}..), got {other:?}"),
        }
    }

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
        expect_internal_with(engine.get_jam_state_hash(), "no live Jam session");
        expect_internal_with(
            engine.apply_jam_delta(wire_batch(&commutative_op_set())),
            "no live Jam session",
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
            expect_internal_with(engine.create_jam_session(bad.to_string()), "session name");
        }
        let oversized = "x".repeat(MAX_JAM_SESSION_NAME_BYTES + 1);
        expect_internal_with(engine.create_jam_session(oversized), "session name");
        // Rejected creations minted nothing (checked BEFORE the at-cap
        // success, which legitimately mints a session).
        expect_internal_with(engine.get_jam_state_hash(), "no live Jam session");
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

        // Length not a multiple of the 48-byte wire row: Internal framing.
        for bad_len in [1usize, 47, 49, 95, 100] {
            expect_internal_with(engine.apply_jam_delta(vec![0u8; bad_len]), "not a multiple");
        }
        // Oversized batch: Internal framing, bounded lock hold. The
        // length must be a multiple of the wire row so the test exercises
        // the SIZE bound (not the multiple bound).
        let oversized = vec![0u8; JAM_OP_SIZE * (MAX_JAM_DELTA_BYTES / JAM_OP_SIZE + 1)];
        expect_internal_with(engine.apply_jam_delta(oversized), "exceeds");
        // None of the rejected batches touched the session: still the
        // canonical empty fold.
        let empty_hash = engine.get_jam_state_hash().unwrap();
        assert_eq!(empty_hash.len(), 64);
    }

    #[test]
    fn jam_delta_applies_and_reports_rejects() {
        let engine = StreamifyEngine::new();
        engine
            .create_jam_session("room".to_string())
            .expect("session");

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
            engine2
                .apply_jam_delta(wire_batch(&other))
                .unwrap()
                .state_hash
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
                    assert_eq!(result.ops_applied + result.ops_rejected, chunk.len() as u32);
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
