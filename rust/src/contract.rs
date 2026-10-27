//! Phase 2 (UFFI-01): UniFFI typed contract implementation.
//!
//! The types and the `StreamifyEngine` methods in this module implement the
//! contract declared in [`crate`]'s `src/streamify.udl`. The scaffolding
//! generated at build time (see `build.rs`) emits the FFI trampolines and
//! `FfiConverter` glue that bind these Rust definitions to typed Kotlin —
//! replacing the raw, brittle `extern "C"` JNI edges for the control-plane
//! and metadata flows.
//!
//! Out of scope by design: the audio real-time data-plane keeps the frozen
//! JNI contract in `native/jni/jni_bridge_dsp.cc` (zero-allocation,
//! zero-lock, direct-ByteBuffer hot path — the AMP-10 invariant audit).
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

    /// Arm the engine. One-way, idempotent: an `Idle` engine advances to
    /// `Buffering` (initialized and acquiring first data); any already
    /// progressed state is left untouched and a second call is a no-op.
    pub fn initialize(&self) {
        if self.shared.initialized.swap(true, Ordering::AcqRel) {
            return;
        }
        let mut state = self
            .shared
            .state
            .write()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if *state == PlaybackState::Idle {
            *state = PlaybackState::Buffering;
        }
    }

    /// Set the output volume. Clamped to `[0.0, 1.0]`; stored lock-free.
    pub fn set_volume(&self, volume: f32) {
        let clamped = volume.clamp(0.0, 1.0);
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

    /// Publish a buffering health sample (producer side). Each field is a
    /// single atomic store; readers may observe mixed-generation samples
    /// under concurrent publication, which is acceptable for telemetry.
    pub fn record_buffer_stats(
        &self,
        buffered_duration_ms: u64,
        underrun_count: u32,
        fill_ratio: f32,
    ) {
        self.shared
            .buffered_duration_ms
            .store(buffered_duration_ms, Ordering::Release);
        self.shared
            .underrun_count
            .store(underrun_count, Ordering::Release);
        self.shared
            .fill_ratio_bits
            .store(fill_ratio.clamp(0.0, 1.0).to_bits(), Ordering::Release);
    }
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
        engine.initialize();
        assert_eq!(engine.get_playback_state(), PlaybackState::Buffering);
        // A progressed state is never pulled back by a second initialize.
        engine.set_playback_state(PlaybackState::Playing);
        engine.initialize();
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
    fn buffer_stats_round_trip() {
        let engine = StreamifyEngine::new();
        engine.record_buffer_stats(12_345, 2, 0.75);
        let stats = engine.get_buffer_stats();
        assert_eq!(stats.buffered_duration_ms, 12_345);
        assert_eq!(stats.underrun_count, 2);
        assert!((stats.fill_ratio - 0.75).abs() < f32::EPSILON);
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
        engine.initialize();
        let barrier = Arc::new(Barrier::new(READERS + WRITERS + 1));

        let mut handles = Vec::new();
        for i in 0..WRITERS {
            let engine = Arc::clone(&engine);
            let barrier = Arc::clone(&barrier);
            handles.push(thread::spawn(move || {
                barrier.wait();
                for k in 0..ITERS {
                    engine.set_volume((k % 101) as f32 / 100.0);
                    engine.record_buffer_stats(
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
                        engine.initialize(); // idempotent under contention
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
}
