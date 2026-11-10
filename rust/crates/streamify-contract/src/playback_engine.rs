//! The playback data-plane wiring (WS-A A3/A4, issue #151): the glue
//! between the `StreamifyEngine` control plane and the
//! `streamify-playback` producer.
//!
//! Three pieces:
//!
//! * [`HttpRangeFetcher`] — the production [`RangeFetcher`] over
//!   `streamify-resolver`'s segmented-range discipline
//!   (`StreamDownloader::fetch_stream_range`: the shared async runtime,
//!   the downloader's hang-guard timeouts, `Range:` header windows).
//! * [`EngineProgressBridge`] — the [`ProgressSink`] adapter that
//!   routes the producer's stage-commit reports into the engine's
//!   telemetry atomics and wakes the EXISTING event dispatcher (MP-C02
//!   discipline: coalesced pushes, never the audio thread).
//! * [`PlaybackWiring`] — what `attach_sink` builds: the SPSC ring, the
//!   producer handle, the DAC-clock anchor for rendered-position truth
//!   and the current session's track correlation.
//!
//! The engine methods consuming this wiring live in `contract.rs`
//! (`attach_sink`, `detach_sink`, `play_track`, `buffer_progress`,
//! `playback_ring`); this module is the implementation surface.

use std::sync::atomic::Ordering;
use std::sync::{Arc, RwLock};

use streamify_playback::{
    BufferProgressCore, PlayIntent, ProducerHandle, ProgressSink, RangeFetcher, RingSink,
    SpscAudioRing, StreamProducer,
};
use streamify_resolver::downloader::StreamDownloader;
use streamify_core::{EngineError, NetworkErrorKind};

use crate::contract::EngineShared;

// ─────────────────────────────────────────────────────────────────────────────
// The production range fetcher
// ─────────────────────────────────────────────────────────────────────────────

/// The HTTP segmented-range fetcher (RFC §5.1 stage 1): a thin wrapper
/// mapping `StreamDownloader::fetch_stream_range` onto the playback
/// crate's trait — the same client, runtime and hang-guard discipline
/// `download_stream_to_file` uses, so the producer's fetch stage
/// inherits the downloader's proven network posture.
#[derive(Debug, Default)]
pub struct HttpRangeFetcher;

impl RangeFetcher for HttpRangeFetcher {
    fn fetch_range(&self, url: &str, start: u64, len: u64) -> Result<Vec<u8>, EngineError> {
        StreamDownloader::fetch_stream_range(url, start, len).map_err(|_| {
            // Status 0: the failure mode is transport-level (no HTTP
            // response reached us to quote a code from).
            EngineError::Network {
                kind: NetworkErrorKind::Http,
                status: 0,
                retry_after: 0,
            }
        })
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The progress bridge onto the engine's telemetry + dispatcher
// ─────────────────────────────────────────────────────────────────────────────

/// Routes producer progress into the engine's existing push lane
/// (MP-C02): the telemetry atomics the coalesced `EngineSnapshot`
/// carries (`buffered_duration_ms`, `underrun_count`, `fill_ratio`)
/// plus the dispatcher wake — the buffer-progress events ride the
/// EXISTING event dispatcher thread, never a new one, never the audio
/// thread.
pub struct EngineProgressBridge {
    shared: Arc<EngineShared>,
}

impl EngineProgressBridge {
    pub(crate) fn new(shared: Arc<EngineShared>) -> Self {
        Self { shared }
    }
}

impl ProgressSink for EngineProgressBridge {
    fn on_progress(&self, core: BufferProgressCore) {
        self.shared
            .buffered_duration_ms
            .store(core.buffered_ms, Ordering::Release);
        self.shared
            .underrun_count
            .store(core.underrun_events, Ordering::Release);
        self.shared
            .fill_ratio_bits
            .store(core.fill_ratio.to_bits(), Ordering::Release);
        self.shared.notify();
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// The wiring
// ─────────────────────────────────────────────────────────────────────────────

/// One attached playback data plane: the ring the producer writes and
/// the platform HAL pulls, the producer thread handle, and the
/// rendered-position anchor bookkeeping (MP-DEC-03: the DAC clock is
/// the sole position truth — the anchor converts its device-frame
/// counter into a per-session offset).
pub struct PlaybackWiring {
    /// The consumer-side ring handle (the A5 harness and, later, the
    /// C1 JNI bridge / C5 JNA shim pull from this).
    pub ring: Arc<SpscAudioRing>,
    /// The producer thread (single writer by construction).
    pub producer: ProducerHandle,
    /// The fetcher the producer was spawned with (the installed
    /// override, or the production HTTP lane).
    pub fetcher: Arc<dyn RangeFetcher>,
    /// The DAC frame counter observed when the current session's
    /// intent was emitted (None until a clock sample exists — rendered
    /// position reads 0 then).
    pub anchor_clock_frames: Option<u64>,
    /// The DAC rate the anchor was taken at.
    pub anchor_rate: u32,
    /// The current session's track id (correlation for progress reads).
    pub current_track_id: Option<String>,
}

/// Build the full wiring for one engine: the canonical-geometry ring,
/// the producer over the chosen fetcher, and the progress bridge.
pub(crate) fn attach(
    shared: &Arc<EngineShared>,
    fetcher: Arc<dyn RangeFetcher>,
    capacity_frames: usize,
) -> Result<PlaybackWiring, EngineError> {
    let (sink, ring) = RingSink::new(capacity_frames, 2, 48_000, 48_000)?;
    let bridge = Arc::new(EngineProgressBridge::new(shared.clone()));
    let producer = StreamProducer::spawn(
        Box::new(sink),
        fetcher.clone(),
        streamify_playback::ProducerConfig::default(),
        bridge,
    )?;
    Ok(PlaybackWiring {
        ring,
        producer,
        fetcher,
        anchor_clock_frames: None,
        anchor_rate: 48_000,
        current_track_id: None,
    })
}

/// Emit one play intent into the wiring (the engine's
/// `play_track`/transport post-pass): anchor the rendered-position
/// truth at the current DAC clock sample and hand the intent to the
/// producer (which coalesces over any in-flight session).
pub(crate) fn emit_intent(wiring: &mut PlaybackWiring, shared: &EngineShared, intent: PlayIntent) {
    let clock = *shared
        .dac_clock
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(clock) = clock {
        wiring.anchor_clock_frames = Some(clock.dac_frame_position);
        wiring.anchor_rate = clock.sample_rate;
    }
    wiring.current_track_id = Some(intent.track_id.clone());
    wiring.producer.play(intent);
}

/// The rendered position [ms] for the current session: the DAC clock's
/// advance since the session anchor, in the clock's own rate. 0 until
/// both a clock sample and an anchor exist (position truth is never
/// wall-clock interpolated — MP-DEC-03).
pub(crate) fn rendered_position_ms(wiring: &PlaybackWiring, shared: &EngineShared) -> u64 {
    let clock = *shared
        .dac_clock
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let (Some(clock), Some(anchor)) = (clock, wiring.anchor_clock_frames) else {
        return 0;
    };
    clock
        .dac_frame_position
        .saturating_sub(anchor)
        .saturating_mul(1000)
        / clock.sample_rate.max(1) as u64
}

/// The default fetcher when no test override is installed.
pub(crate) fn default_fetcher() -> Arc<dyn RangeFetcher> {
    Arc::new(HttpRangeFetcher)
}

/// The override slot type for the engine's test seam.
pub type FetcherSlot = RwLock<Option<Arc<dyn RangeFetcher>>>;

/// Read the effective fetcher: the installed override or the
/// production HTTP lane.
pub(crate) fn effective_fetcher(slot: &FetcherSlot) -> Arc<dyn RangeFetcher> {
    slot.read()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone()
        .unwrap_or_else(default_fetcher)
}

/// Assemble the UDL-facing [`streamify_core::BufferProgress`] record
/// from the wiring + engine state.
pub(crate) fn buffer_progress(
    wiring: &PlaybackWiring,
    shared: &EngineShared,
    target_position_ms: u64,
) -> streamify_core::BufferProgress {
    let progress = wiring.producer.progress();
    streamify_core::BufferProgress {
        buffered_ms: progress.buffered_ms.load(Ordering::Acquire),
        fill_ratio: f32::from_bits(progress.fill_ratio_bits.load(Ordering::Acquire)),
        underrun_events: progress.underruns.load(Ordering::Acquire),
        overrun_events: progress.overruns.load(Ordering::Acquire),
        target_position_ms,
        rendered_position_ms: rendered_position_ms(wiring, shared),
        track_id: wiring.current_track_id.clone(),
        producer_live: progress.live.load(Ordering::Acquire),
        eos: progress.eos.load(Ordering::Acquire),
    }
}
