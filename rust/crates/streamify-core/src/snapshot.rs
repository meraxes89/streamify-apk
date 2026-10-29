//! Contract records declared in streamify.udl: playback lifecycle
//! state, track metadata, pipeline geometry, buffering health, the
//! hardware DAC presentation clock and the coalesced engine snapshot.
//!
//! Pure leaf data by design (Discussion #120: streamify-core owns the
//! "pure leaf types"): no engine behavior lives here, every consumer
//! from the real-time audio crate to the UniFFI contract shares these
//! definitions without pulling a single dependency.

use crate::error::{AudioErrorKind, EngineError};

// ============================================================================
//  Contract records (declared in streamify.udl; the build-time scaffolding
//  resolves these definitions by name — field/variant shapes MUST match)
// ============================================================================

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
    /// [`EngineError::Audio`] with kind
    /// [`AudioErrorKind::FormatUnsupported`] (multiplatform Phase 3 /
    /// MP-C04: the failure is an audio-geometry fault, and the taxonomy
    /// now says so instead of flattening it into `Internal`).
    pub fn validate(&self) -> Result<(), EngineError> {
        // 8 kHz telephony floor .. 768 kHz pro-audio ceiling.
        if !(8_000..=768_000).contains(&self.sample_rate) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        // Mono .. 7.1 surround.
        if !(1..=8).contains(&self.channels) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        // Widths the PCM conversion paths express.
        if !matches!(self.bit_depth, 16 | 24 | 32) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        // Non-degenerate ring; capped at 2^20 frames (~21 s @ 48 kHz) so
        // a config typo cannot request an unbounded buffer.
        if self.buffer_frames == 0 || self.buffer_frames > (1 << 20) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
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

/// Hardware DAC presentation clock sample (MP-C01 / MP-DEC-03).
///
/// Replaces the retired `RenderClock` concept: these are DAC-side
/// PRESENTATION counters reported by the hardware HAL callback — the sole
/// clock master — never render-side wall-clock estimates. The platform
/// adapter records one sample per HAL callback via
/// [`StreamifyEngine::record_dac_clock`]; the engine republishes the
/// latest sample inside every pushed [`EngineSnapshot`]. Acceptance
/// target: `< 5 ms` position error vs a loopback reference per platform
/// HAL (verified on hardware in Phase 4a / MP-HAL06).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DacPresentationClock {
    /// Hardware-consumed frame counter (monotonic per device stream).
    pub dac_frame_position: u64,
    /// Device DAC nominal rate in Hz (e.g. 48000).
    pub sample_rate: u32,
    /// OS monotonic presentation timestamp in nanoseconds.
    pub monotonic_ns: u64,
}

/// Supported DAC rate envelope for clock ingest (mirrors the native
/// `AudioSink` validation bounds: `kSinkMinSampleRate..kSinkMaxSampleRate`).
pub const DAC_CLOCK_MIN_SAMPLE_RATE: u32 = 8_000;
pub const DAC_CLOCK_MAX_SAMPLE_RATE: u32 = 192_000;

/// Coalesced engine state snapshot pushed to listeners (MP-C02 / MP-DEC-04).
///
/// `rev` is the monotonic revision of the engine's accepted-mutation
/// sequence: every accepted mutation (state transition, volume change,
/// telemetry sample, DAC clock sample, accepted media command, listener
/// registration) bumps the revision BEFORE the dispatcher is woken, and
/// the dispatcher only ever publishes snapshots with strictly increasing
/// revisions. Listeners use it to drop stale snapshots, deduplicate
/// re-deliveries and order events without wall-clock reasoning.
#[derive(Debug, Clone, PartialEq)]
pub struct EngineSnapshot {
    /// Monotonic revision; strictly increases across dispatched snapshots.
    pub rev: u64,
    /// Playback lifecycle state at snapshot time.
    pub state: PlaybackState,
    /// Output volume scalar in `[0.0, 1.0]`.
    pub volume: f32,
    /// Buffering health at snapshot time.
    pub buffer_stats: BufferStats,
    /// Latest hardware DAC presentation sample; absent until the first
    /// `record_dac_clock` of this engine instance.
    pub clock: Option<DacPresentationClock>,
}

