//! The dedicated producer thread (RFC §5): fetch → decode → resample →
//! loudness → sink write edge, on ONE thread that is never the audio
//! callback.
//!
//! # Session lifecycle
//!
//! [`StreamProducer::spawn`] starts the thread owning exactly one
//! [`AudioSink`] (single writer — SPSC by construction). The engine
//! sends [`ProducerCommand`]s: `Play` carries a [`PlayIntent`] (the
//! queue row's resolved source URL + the transport's position target),
//! `Park`/`Resume` mirror the lifecycle state, `Stop` drops the
//! session, `Shutdown` tears the thread down deterministically.
//!
//! # Cancellation — skip-under-load (RFC §5.4)
//!
//! The transport's cursor move is the skip signal. The producer polls
//! the command channel at every stage boundary and inside every
//! write-edge park retry; a new `Play` coalesces over any in-flight
//! intent (intermediate fetches are simply abandoned — range requests
//! are idempotent), the sink's cooperative `RequestFlush` clears the
//! stale tail at the boundary (never a blocking cancel from the audio
//! side), and a fresh session re-stages from the new row's position
//! target. The 100-skip stress gate in `tests/test_producer.rs` and
//! the A5 harness pin the protocol.
//!
//! # Pause semantics
//!
//! `Park`/`Resume` are BENIGN commands: they flip the feeding gate
//! without interrupting an in-flight chunk, and the unwritten tail of
//! the current render buffer survives across the pause (the write
//! cursor persists) — a pause never punches a hole in the audio.
//! URGENT commands (`Play`/`Stop`/`Shutdown`) interrupt immediately;
//! the flushed ring makes the discarded tail inaudible by design.
//!
//! # Zero allocation on the audio edge
//!
//! All staging layers ([`FrameBuf`]s and the resample/upmix vectors)
//! are allocated at session construction and reused via
//! capacity-preserving clears; the write edge itself is the ring's
//! memcpy-shaped commit (see `sink.rs`). Fetch and decode may allocate
//! (network buffers, decoder internals) — that is the producer thread,
//! not the audio callback.

use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender, TryRecvError};
use std::sync::{Arc, Mutex};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use sha2::{Digest, Sha256};
use streamify_audio::lufs::LufsNormalizer;
use streamify_audio::{CanonicalSrc, StudioEqualizer};

use crate::dsp_controls::{DspControls, DspParams, LufsMode};
use streamify_core::{AudioErrorKind, EngineError};

use crate::decode::{DecoderGeometry, SymphoniaDecoder, TrackDecoder};
use crate::fetch::RangeFetcher;
use crate::frame_buf::FrameBuf;
use crate::opus::{OpusTier, OpusTrackDecoder};
use crate::sink::AudioSink;

/// Default segmented-range window (the buffering budget granularity —
/// RFC §5.1: ranges keep the ring's high watermark ahead of the HAL by
/// at most one segment).
pub const DEFAULT_SEGMENT_BYTES: u64 = 64 * 1024;
/// Hard cap on the accumulated compressed source (the decode session
/// accumulates the full track — symphonia's seekable-source contract —
/// so the mobile memory budget needs an explicit ceiling). 64 MiB is
/// ~70 minutes of 128 kbps AAC: every realistic track, and a loud,
/// typed error for anything beyond.
pub const MAX_SOURCE_BYTES: usize = 64 * 1024 * 1024;
/// Default loudness target (LUFS) — the streaming-norm anchor the
/// golden `lufs.rs` chain centers on.
pub const DEFAULT_TARGET_LUFS: f64 = -16.0;
/// Park quantum while the ring sits at its high watermark: the
/// producer sleeps, re-polls commands, retries the write.
const PARK_POLL_MS: u64 = 2;
/// Default bounded retries for a transient range failure.
const DEFAULT_FETCH_RETRIES: u32 = 3;

/// One play intent: the queue row's identity, its resolved source URL
/// and the transport's position target (RFC §3: "transport → producer"
/// decoupling point).
#[derive(Debug, Clone)]
pub struct PlayIntent {
    /// The staged track's id (telemetry correlation only).
    pub track_id: String,
    /// The resolved CDN URL (or fixture key) the fetcher understands.
    pub source_url: String,
    /// Position target within the track [ms] — the producer decodes
    /// through and skips the write of pre-anchor frames.
    pub start_position_ms: u64,
}

/// Commands the engine sends to the producer thread.
#[derive(Debug)]
pub enum ProducerCommand {
    /// Begin (or switch to) a track. Supersedes any in-flight session.
    Play(PlayIntent),
    /// Stop feeding the sink (engine `Paused`); the session, its ring
    /// contents and the unwritten staging tail stay intact.
    Park,
    /// Resume feeding after a park.
    Resume,
    /// Drop the session and flush the sink's stale tail.
    Stop,
    /// Phase 4 / Step 2: stage the NEXT queue track while the current
    /// one plays (gapless pre-buffering). The producer decodes the
    /// next track into its own staging (NOT the ring) and, when the
    /// current session completes, chains IMMEDIATELY — zero silent
    /// frames between tracks. A `Play` supersedes any staged advance
    /// (a manual skip wins over the queue's tail).
    QueueAdvance(PlayIntent),
    /// Tear the thread down (deterministic join).
    Shutdown,
}

/// The copy progress core pushed to the [`ProgressSink`] after every
/// stage commit — allocation-free by construction (the engine's adapter
/// routes it into its telemetry atomics and the existing event
/// dispatcher).
#[derive(Debug, Clone, Copy)]
pub struct BufferProgressCore {
    /// Ring occupancy in milliseconds of canonical audio.
    pub buffered_ms: u64,
    /// Ring fill ratio `[0.0, 1.0]`.
    pub fill_ratio: f32,
    /// Consumer-side underrun events (cumulative, this sink).
    pub underrun_events: u32,
    /// Producer-side overrun events (cumulative, this sink).
    pub overrun_events: u32,
    /// Canonical frames committed by the producer (cumulative, this
    /// sink).
    pub frames_written: u64,
    /// The play-intent generation this report belongs to.
    pub session: u64,
    /// True when the current session has decoded and committed its
    /// final frame (the ring still drains afterwards).
    pub eos: bool,
}

/// The consumer of producer progress reports (implemented by the
/// engine: telemetry atomics + the existing event dispatcher wake).
pub trait ProgressSink: Send + Sync {
    fn on_progress(&self, core: BufferProgressCore);
}

/// Live producer telemetry (lock-free reads; written by the producer
/// thread, read by the engine's typed surfaces).
#[derive(Debug, Default)]
pub struct ProgressState {
    /// Ring occupancy [ms] at the last stage commit.
    pub buffered_ms: AtomicU64,
    /// Fill ratio bits (f32 as IEEE-754).
    pub fill_ratio_bits: AtomicU32,
    /// Consumer underruns (mirrors the sink stats).
    pub underruns: AtomicU32,
    /// Producer overruns (mirrors the sink stats).
    pub overruns: AtomicU32,
    /// Canonical frames committed.
    pub frames_written: AtomicU64,
    /// Monotonic play-intent generation (bumps per `Play`).
    pub session: AtomicU64,
    /// The producer thread is alive.
    pub live: AtomicBool,
    /// The current session reached EOS.
    pub eos: AtomicBool,
    /// The error that ended the current session, if any.
    pub last_error: Mutex<Option<EngineError>>,
}

impl ProgressState {
    /// The error that ended the current session (cleared on the next
    /// `Play`).
    pub fn last_error(&self) -> Option<EngineError> {
        self.last_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }
}

/// Producer tuning (all fields carry production-safe defaults).
#[derive(Debug, Clone)]
pub struct ProducerConfig {
    /// Segmented-range window in bytes.
    pub segment_bytes: u64,
    /// Bounded retries for a transient range failure.
    pub fetch_retries: u32,
    /// Loudness target (LUFS) — the config-time default; the LIVE
    /// value rides the [`DspControls`] (Phase 4's live toggles).
    pub target_lufs: f64,
    /// Hard cap on the accumulated source bytes.
    pub max_source_bytes: usize,
    /// The Phase 4 live DSP control plane (EQ / LUFS mode / limiter /
    /// crossfade). `None` = the DspParams::default() behavior (flat
    /// EQ, normalize to `target_lufs`, −1 dBTP ceiling, no crossfade)
    /// — every pre-Phase-4 caller stays byte-identical.
    pub dsp: Option<Arc<DspControls>>,
}

impl Default for ProducerConfig {
    fn default() -> Self {
        Self {
            segment_bytes: DEFAULT_SEGMENT_BYTES,
            fetch_retries: DEFAULT_FETCH_RETRIES,
            target_lufs: DEFAULT_TARGET_LUFS,
            max_source_bytes: MAX_SOURCE_BYTES,
            dsp: None,
        }
    }
}

impl ProducerConfig {
    /// The effective DSP parameters for one render round: the live
    /// control plane's snapshot when wired, else the config defaults
    /// (allocation-free; a small stack copy).
    fn dsp_snapshot(&self) -> DspParams {
        match &self.dsp {
            Some(controls) => controls.snapshot(),
            None => DspParams {
                target_lufs: self.target_lufs,
                ..DspParams::default()
            },
        }
    }
}

/// The engine's handle onto the producer thread. Dropping it sends
/// `Shutdown` and joins — deterministic teardown, no detached threads.
#[derive(Debug)]
pub struct ProducerHandle {
    tx: Sender<ProducerCommand>,
    progress: Arc<ProgressState>,
    join: Option<JoinHandle<()>>,
}

impl ProducerHandle {
    /// Send a play intent (non-blocking; the producer coalesces).
    pub fn play(&self, intent: PlayIntent) {
        let _ = self.tx.send(ProducerCommand::Play(intent));
    }

    /// Park the producer (engine `Paused`).
    pub fn park(&self) {
        let _ = self.tx.send(ProducerCommand::Park);
    }

    /// Resume after a park.
    pub fn resume(&self) {
        let _ = self.tx.send(ProducerCommand::Resume);
    }

    /// Drop the session and flush the sink.
    /// Stage the next queue track for a gapless transition (Phase 4).
    pub fn queue_advance(&self, intent: PlayIntent) {
        let _ = self.tx.send(ProducerCommand::QueueAdvance(intent));
    }

    pub fn stop(&self) {
        let _ = self.tx.send(ProducerCommand::Stop);
    }

    /// The live telemetry state.
    pub fn progress(&self) -> &Arc<ProgressState> {
        &self.progress
    }

    /// Deterministic teardown: send `Shutdown`, join.
    pub fn shutdown(mut self) {
        let _ = self.tx.send(ProducerCommand::Shutdown);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

impl Drop for ProducerHandle {
    fn drop(&mut self) {
        let _ = self.tx.send(ProducerCommand::Shutdown);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

/// The producer itself: spawn it over one sink + one fetcher.
pub struct StreamProducer;

/// One decode/render session (built per `Play`; dropped on skip).
struct Session {
    intent: PlayIntent,
    /// The codec route for this session (Phase 3 / Step 2): Opus
    /// sources (OggS or the server's raw #225 OpusHead framing —
    /// itag 251) ride [`OpusTrackDecoder`]; everything else (AAC in
    /// ADTS/M4A, WAV fixtures — itag 140 and the self-test lanes)
    /// rides [`SymphoniaDecoder`]. Both implement [`TrackDecoder`],
    /// so the session body stays codec-agnostic.
    decoder: Box<dyn TrackDecoder>,
    /// Built lazily once the decoder's geometry is discovered.
    src: Option<CanonicalSrc>,
    /// Input geometry of the resample stage (guards rebuilds).
    src_geometry: Option<DecoderGeometry>,
    normalizer: LufsNormalizer,
    /// Phase 4: the 10-band EQ (lazily built at the first render round
    /// with canonical geometry; reconfigured per round from the live
    /// control plane — allocation-free coefficient updates).
    eq: Option<StudioEqualizer>,
    /// Phase 4: the normalizer's last applied gain (dB) — the bypass
    /// toggle's undo reference.
    last_normalizer_gain_db: f64,
    /// Phase 4: the anti-zipper correction state (dB) — how much of
    /// the normalizer's gain is currently being CANCELLED (0 = none,
    /// last_normalizer_gain_db = fully bypassed).
    bypass_cancel_db: f64,
    hasher: Sha256,
    /// Byte frontier of the fetch stage.
    fetch_offset: u64,
    source_exhausted: bool,
    finish_called: bool,
    /// Total canonical frames that entered the render stage (the seek
    /// anchor's reference frame).
    rendered_frames: u64,
    /// Canonical frames dropped as pre-anchor (seek) skips.
    skipped_frames: u64,
    /// Canonical frames committed to the sink (this session).
    emitted_frames: u64,
    // ── render staging (rebuilt per round; the unwritten tail survives
    //    a park via `write_cursor`) ────────────────────────────────────
    /// Decoded input-geometry frames.
    decoded: FrameBuf,
    /// Resampled canonical frames (Vec: CanonicalSrc appends).
    canonical: Vec<f32>,
    /// Stereo upmix staging (mono sources only).
    upmix: Vec<f32>,
    /// True when `upmix` (not `canonical`) is the render buffer.
    render_mono_upmix: bool,
    /// Write cursor into the render buffer (interleaved SAMPLES);
    /// equals the buffer length when fully committed.
    write_cursor: usize,
}

impl Session {
    fn new(intent: PlayIntent, config: &ProducerConfig) -> Result<Self, EngineError> {
        // Staging capacity: one segment's worth of decoded audio at a
        // generous geometry — reallocations happen only during the
        // first rounds, never in steady state.
        let staging_frames = (config.segment_bytes as usize / 8).max(4096);
        let decoder = Self::route_decoder(&intent.source_url)?;
        Ok(Self {
            intent,
            decoder,
            src: None,
            src_geometry: None,
            normalizer: LufsNormalizer::new(config.target_lufs)?,
            eq: None,
            last_normalizer_gain_db: 0.0,
            bypass_cancel_db: 0.0,
            hasher: Sha256::new(),
            fetch_offset: 0,
            source_exhausted: false,
            finish_called: false,
            rendered_frames: 0,
            skipped_frames: 0,
            emitted_frames: 0,
            decoded: FrameBuf::with_capacity(staging_frames, 2)?,
            canonical: Vec::with_capacity(staging_frames * 2),
            upmix: Vec::new(),
            render_mono_upmix: false,
            write_cursor: 0,
        })
    }

    /// Route the decode backend by the source's URL signature.
    ///
    /// `OpusTrackDecoder` itself sniffs the BYTE framing (OggS vs raw
    /// OpusHead) on the first pushed range, but symphonia fails CLOSED
    /// on an Opus container (no Opus codec in the default registry —
    /// the §5.2 decision record), so the producer must never hand an
    /// Opus source to the plain `SymphoniaDecoder`. The routing key is
    /// the resolver's URL grammar: itag 251/250/249 are Opus
    /// (WebM/Opus streams), everything else rides symphonia. The
    /// `sovereign-opus` fixture scheme keeps the E2E harness on the
    /// same route as production.
    fn route_decoder(source_url: &str) -> Result<Box<dyn TrackDecoder>, EngineError> {
        let lower = source_url.to_ascii_lowercase();
        let is_opus = lower.contains("itag=251")
            || lower.contains("itag=250")
            || lower.contains("itag=249")
            || lower.contains("opus");
        if is_opus {
            Ok(Box::new(OpusTrackDecoder::new(OpusTier::Primary)))
        } else {
            Ok(Box::new(SymphoniaDecoder::new()?))
        }
    }

    /// The render buffer's length in interleaved samples.
    fn render_len(&self) -> usize {
        if self.render_mono_upmix {
            self.upmix.len()
        } else {
            self.canonical.len()
        }
    }
}

/// Run ONE stage round of the live session (fetch → decode → … → write
/// edge), absorbing the outcome: completion chains a staged gapless
/// advance (Phase 4 / Step 2 — zero silent frames), suspension re-
/// stashes the session, an error records and drops it.
fn run_one_round(
    ctx: &mut ThreadCtx,
    sink: &mut dyn AudioSink,
    fetcher: &dyn RangeFetcher,
    config: &ProducerConfig,
    progress_sink: &dyn ProgressSink,
    progress: &std::sync::Arc<ProgressState>,
) {
    let Some(mut session) = ctx.session.take() else { return };
    let outcome = {
        let mut stage = StageCtx {
            sink,
            fetcher,
            config,
            progress_sink,
            progress,
        };
        run_session(&mut session, ctx.session_id, &mut stage, ctx)
    };
    match outcome {
        Ok(SessionOutcome::Completed) => {
            // Phase 4 / Step 2 — the gapless chain: a staged next
            // session takes over IMMEDIATELY (no flush, no idle
            // window — zero silent frames between tracks). A
            // QueueAdvance absorbed by poll_urgent MID-session sits
            // in staged_advance — promote it NOW (completion is the
            // chain's trigger; an advance that arrived any time
            // before completion fires).
            if ctx.next_session.is_none() {
                if let Some(intent) = ctx.take_staged_advance() {
                    ctx.next_session = match Session::new(intent, config) {
                        Ok(s) => Some(s),
                        Err(e) => {
                            eprintln!(
                                "sovereign-producer: staged advance build failed at chain-in: {e:?}"
                            );
                            None
                        }
                    };
                }
            }
            if let Some(next) = ctx.next_session.take() {
                ctx.session_id += 1;
                progress.session.store(ctx.session_id, Ordering::Release);
                progress.eos.store(false, Ordering::Release);
                ctx.session = Some(next);
            } else {
                // No staged advance: the ring drains and EOS holds
                // (the queue owner drives the next Play).
                progress.eos.store(true, Ordering::Release);
            }
        }
        Ok(SessionOutcome::Suspended) => {
            ctx.session = Some(session);
        }
        Err(e) => {
            *progress
                .last_error
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(e);
        }
    }
}

/// Why the session runner returned control to the command loop.
enum SessionOutcome {
    /// EOS reached: the final frame is committed; the ring drains.
    Completed,
    /// An urgent command arrived (cooperative interrupt) or the caller
    /// parked — the session survives with its state.
    Suspended,
}

/// The immutable stage wiring one session run needs (bundles the
/// references so the runner stays under clippy's argument budget).
struct StageCtx<'a> {
    sink: &'a mut dyn AudioSink,
    fetcher: &'a dyn RangeFetcher,
    config: &'a ProducerConfig,
    progress_sink: &'a dyn ProgressSink,
    progress: &'a ProgressState,
}

impl StreamProducer {
    /// Spawn the producer thread over one sink (single writer), one
    /// range fetcher and one progress sink.
    pub fn spawn(
        mut sink: Box<dyn AudioSink>,
        fetcher: Arc<dyn RangeFetcher>,
        config: ProducerConfig,
        progress_sink: Arc<dyn ProgressSink>,
    ) -> Result<ProducerHandle, EngineError> {
        let (tx, rx) = std::sync::mpsc::channel::<ProducerCommand>();
        let progress = Arc::new(ProgressState::default());
        progress.live.store(true, Ordering::Release);
        let thread_progress = progress.clone();
        let join = thread::Builder::new()
            .name("streamify-producer".to_string())
            .spawn(move || {
                let mut ctx = ThreadCtx {
                    rx,
                    pending: None,
                    parked: false,
                    next_session: None,
                    staged_advance: None,
                    session: None,
                    session_id: 0,
                };
                loop {
                    // Command intake: pending first, then the staged
                    // gapless advance, then the channel. A LIVE session
                    // (or a fresh chain-in) must keep cycling WITHOUT a
                    // command — the Phase 4 chain-in takes over at the
                    // previous session's completion and runs its own
                    // stage rounds; the channel wait degrades to a
                    // short poll so the stage loop below stays live.
                    let cmd = ctx
                        .take_pending()
                        .or_else(|| ctx.take_staged_advance().map(ProducerCommand::QueueAdvance))
                        .or_else(|| {
                            if ctx.session.is_some() {
                                match ctx.rx.recv_timeout(Duration::from_millis(5)) {
                                    Ok(cmd) => Some(cmd),
                                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => None,
                                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                                        Some(ProducerCommand::Shutdown)
                                    }
                                }
                            } else {
                                ctx.recv_blocking()
                            }
                        });
                    if cmd.is_none() && ctx.session.is_none() {
                        continue; // idle: back to blocking recv
                    }
                    let Some(cmd) = cmd else {
                        // No command, live session: run the stage round
                        // directly (the session parks at its high
                        // watermark; poll_urgent inside stays live).
                        run_one_round(
                            &mut ctx,
                            sink.as_mut(),
                            fetcher.as_ref(),
                            &config,
                            progress_sink.as_ref(),
                            &thread_progress,
                        );
                        continue;
                    };
                    match cmd {
                        ProducerCommand::Shutdown => break,
                        ProducerCommand::Stop => {
                            if ctx.session.take().is_some() {
                                let _ = sink.request_flush();
                                thread_progress.eos.store(false, Ordering::Release);
                            }
                        }
                        ProducerCommand::Park => ctx.parked = true,
                        ProducerCommand::Resume => ctx.parked = false,
                        ProducerCommand::QueueAdvance(intent) => {
                            // Gapless pre-buffering: build the next
                            // session NOW (fetch + decode warm-up on
                            // the producer thread). Failure is
                            // non-fatal — the chain-in just won't fire
                            // and the queue's owner learns via EOS.
                            ctx.next_session = match Session::new(intent, &config) {
                                Ok(s) => Some(s),
                                Err(e) => {
                                    eprintln!("sovereign-producer: queue_advance staging failed: {e:?}");
                                    None
                                }
                            };
                            // The ALREADY-DRAINED edge case: the live
                            // session completed BEFORE this advance
                            // arrived (the consumer outran the queue's
                            // staging). There is nothing to chain onto
                            // — start the staged session NOW (the
                            // "gapless" contract degenerates to
                            // immediate play, which is exactly what
                            // the queue owner meant).
                            if ctx.session.is_none() {
                                if let Some(next) = ctx.next_session.take() {
                                    if !thread_progress
                                        .eos
                                        .swap(false, Ordering::Release)
                                    {
                                        // EOS was already false: a
                                        // fresh sequence anyway.
                                    }
                                    ctx.session_id += 1;
                                    thread_progress
                                        .session
                                        .store(ctx.session_id, Ordering::Release);
                                    thread_progress.eos.store(false, Ordering::Release);
                                    ctx.session = Some(next);
                                }
                            }
                        }
                        ProducerCommand::Play(intent) => {
                            // A manual Play supersedes any staged advance.
                            ctx.next_session = None;
                            // The skip path: cooperative flush at the
                            // stage boundary, then re-stage.
                            if ctx.session.is_some() {
                                let _ = sink.request_flush();
                            }
                            ctx.session_id += 1;
                            thread_progress.session.store(ctx.session_id, Ordering::Release);
                            thread_progress.eos.store(false, Ordering::Release);
                            *thread_progress
                                .last_error
                                .lock()
                                .unwrap_or_else(|poisoned| poisoned.into_inner()) = None;
                            match Session::new(intent, &config) {
                                Ok(session) => ctx.session = Some(session),
                                Err(e) => {
                                    *thread_progress
                                        .last_error
                                        .lock()
                                        .unwrap_or_else(|poisoned| poisoned.into_inner()) = Some(e);
                                    ctx.session = None;
                                }
                            }
                        }
                    }
                    // ── stage loop (one session run per command pass) ──
                    if ctx.session.is_some() {
                        run_one_round(
                            &mut ctx,
                            sink.as_mut(),
                            fetcher.as_ref(),
                            &config,
                            progress_sink.as_ref(),
                            &thread_progress,
                        );
                    }
                }
                thread_progress.live.store(false, Ordering::Release);
            })
            .map_err(|e| EngineError::Audio {
                kind: AudioErrorKind::Init,
                device_id: Some(format!("producer thread spawn failed: {e}")),
            })?;
        Ok(ProducerHandle {
            tx,
            progress,
            join: Some(join),
        })
    }
}

struct ThreadCtx {
    rx: Receiver<ProducerCommand>,
    pending: Option<ProducerCommand>,
    parked: bool,
    session: Option<Session>,
    session_id: u64,
    /// Phase 4 / Step 2: the gapless chain-in candidate (staged by
    /// `QueueAdvance`, consumed when the live session completes).
    next_session: Option<Session>,
    /// A QueueAdvance observed by poll_urgent mid-session (handed to
    /// the main loop at the next command pass; never preempts).
    staged_advance: Option<PlayIntent>,
}

impl ThreadCtx {
    fn take_pending(&mut self) -> Option<ProducerCommand> {
        self.pending.take()
    }

    /// The staged gapless-advance intent (set by poll_urgent's benign
    /// absorption of QueueAdvance; consumed by the main loop).
    fn take_staged_advance(&mut self) -> Option<PlayIntent> {
        self.staged_advance.take()
    }

    fn recv_blocking(&mut self) -> Option<ProducerCommand> {
        self.rx.recv().ok()
    }

    /// Cooperative interrupt point: drains pending commands. Benign
    /// commands (`Park`/`Resume`) apply immediately (the feeding gate);
    /// urgent ones (`Play`/`Stop`/`Shutdown`) are stashed and signal an
    /// interrupt. Returns true when an urgent command is pending.
    fn poll_urgent(&mut self) -> bool {
        loop {
            let cmd = match self.rx.try_recv() {
                Ok(cmd) => cmd,
                Err(TryRecvError::Empty) => return self.pending.is_some(),
                Err(TryRecvError::Disconnected) => {
                    self.pending = Some(ProducerCommand::Shutdown);
                    return true;
                }
            };
            match cmd {
                ProducerCommand::Park => self.parked = true,
                ProducerCommand::Resume => self.parked = false,
                // BENIGN: staging the gapless advance never interrupts
                // the live session (it only fills next_session; the
                // chain-in fires at the live session's completion).
                ProducerCommand::QueueAdvance(intent) => {
                    if let Some(cmd) = self.pending.take() {
                        // Preserve the preemption contract: an urgent
                        // command stashed earlier stays stashed.
                        self.pending = Some(cmd);
                    }
                    self.staged_advance = Some(intent);
                }
                urgent @ (ProducerCommand::Play(_) | ProducerCommand::Stop | ProducerCommand::Shutdown) => {
                    self.pending = Some(urgent);
                    return true;
                }
            }
        }
    }
}

/// The session runner: alternate between committing the render tail to
/// the sink and running fetch → decode → resample → loudness for the
/// next round. Suspends on urgent commands; completes at EOS with the
/// staging drained.
fn run_session(
    session: &mut Session,
    session_id: u64,
    stage: &mut StageCtx<'_>,
    ctx: &mut ThreadCtx,
) -> Result<SessionOutcome, EngineError> {
    let StageCtx {
        sink,
        fetcher,
        config,
        progress_sink,
        progress,
    } = stage;
    loop {
        // ── phase A: commit the staged render tail to the write edge ──
        if session.write_cursor < session.render_len() {
            if ctx.parked {
                // Paused mid-chunk: the tail survives; wake on command.
                if !ctx.poll_urgent() {
                    thread::sleep(Duration::from_millis(PARK_POLL_MS));
                }
                continue;
            }
            if ctx.poll_urgent() {
                return Ok(SessionOutcome::Suspended);
            }
            // Disjoint field borrows: the render buffer and the frame
            // counters live in different fields.
            let Session {
                canonical,
                upmix,
                emitted_frames,
                write_cursor,
                render_mono_upmix,
                ..
            } = session;
            let render: &mut Vec<f32> = if *render_mono_upmix {
                upmix
            } else {
                canonical
            };
            let chunk = &render[*write_cursor..];
            let offered_frames = chunk.len() / 2;
            // Phase 5 / Pillar 1: the WRITE EDGE runs inside the
            // allocation trap (rt-alloc-trap builds); production compiles
            // the pass-through closure. This is the producer-thread RT
            // window — SpscAudioRing::write_float must never allocate.
            let accepted = crate::rt_trap::assert_no_alloc(|| sink.write_float(chunk))?;
            *write_cursor += accepted * 2;
            *emitted_frames += accepted as u64;
            if accepted < offered_frames {
                // High watermark: park briefly and retry the remainder.
                thread::sleep(Duration::from_millis(PARK_POLL_MS));
            }
            continue;
        }

        // ── phase B: the staging is drained — run the next round ─────
        if ctx.poll_urgent() {
            return Ok(SessionOutcome::Suspended);
        }
        if ctx.parked {
            thread::sleep(Duration::from_millis(PARK_POLL_MS));
            continue;
        }

        // EOS check: nothing staged, nothing more to decode.
        if session.decoder.is_eos() {
            publish(progress, *progress_sink, &**sink, session_id, true);
            return Ok(SessionOutcome::Completed);
        }

        // Stage 1: fetch one segmented range (bounded retries).
        if !session.source_exhausted {
            let mut attempts = 0u32;
            let got = loop {
                if ctx.poll_urgent() {
                    return Ok(SessionOutcome::Suspended);
                }
                attempts += 1;
                match fetcher.fetch_range(
                    &session.intent.source_url,
                    session.fetch_offset,
                    config.segment_bytes,
                ) {
                    Ok(bytes) => break bytes,
                    Err(e) => {
                        if attempts > config.fetch_retries {
                            return Err(e);
                        }
                    }
                }
            };
            if got.is_empty() {
                session.source_exhausted = true;
            } else {
                session.fetch_offset += got.len() as u64;
                session.hasher.update(&got);
                if session.fetch_offset > config.max_source_bytes as u64 {
                    return Err(EngineError::Audio {
                        kind: AudioErrorKind::FormatUnsupported,
                        device_id: Some(
                            "source exceeds the decode memory budget (max_source_bytes)"
                                .to_string(),
                        ),
                    });
                }
                // Stage 2: decode the range.
                session.decoded.clear();
                session.decoder.push_range(&got, &mut session.decoded)?;
            }
        }

        // Stage 2b: finish the source tail once exhausted.
        if session.source_exhausted && !session.decoder.is_eos() && !session.finish_called {
            session.decoded.clear();
            session.decoder.finish(&mut session.decoded)?;
            session.finish_called = true;
        }

        // Stage 3: resample to the canonical geometry.
        let geometry = session.decoder.geometry();
        if let Some(geometry) = geometry {
            if session.src.is_none() || session.src_geometry != Some(geometry) {
                session.src = Some(CanonicalSrc::new(
                    geometry.sample_rate_hz,
                    geometry.channels,
                )?);
                session.src_geometry = Some(geometry);
            }
        }
        session.canonical.clear();
        if let Some(src) = session.src.as_mut() {
            src.process(session.decoded.as_slice(), &mut session.canonical);
            if session.decoder.is_eos() {
                src.flush(&mut session.canonical);
            }
        } else if !session.decoded.is_empty() {
            // Frames without a discovered geometry — impossible by
            // construction (the decoder only emits post-probe).
            return Err(EngineError::Audio {
                kind: AudioErrorKind::Init,
                device_id: Some("decoded frames without a discovered geometry".to_string()),
            });
        }

        // Stage 4: stereo upmix (mono sources) + the loudness chain.
        let channels = geometry.map(|g| g.channels).unwrap_or(2);
        session.render_mono_upmix = channels == 1;
        let report = if session.render_mono_upmix {
            session.upmix.clear();
            session.upmix.reserve(session.canonical.len());
            for s in session.canonical.iter() {
                session.upmix.push(*s);
                session.upmix.push(*s);
            }
            session
                .normalizer
                .process_interleaved(&mut session.upmix)
        } else {
            session
                .normalizer
                .process_interleaved(&mut session.canonical)
        };
        session.last_normalizer_gain_db = report.applied_gain_db;

        // ── Phase 4 / Step 2: the LIVE DSP chain on the render buffer ──
        // Order: loudness → EQ (+limiter ceiling guard) → write edge.
        // All stages are producer-thread (never the audio callback) and
        // allocation-free in steady state (biquads reconfigure in
        // place; the buffers were allocated at session construction).
        let dsp = config.dsp_snapshot();
        let render: &mut Vec<f32> = if session.render_mono_upmix {
            &mut session.upmix
        } else {
            &mut session.canonical
        };
        if dsp.lufs_mode == LufsMode::Bypass {
            // The bypass toggle: undo the normalizer's gain with an
            // EASED inverse (the anti-zipper ramp — a hard unity reset
            // would click). The cancel state ramps from 0 (fully
            // normalized) toward the applied gain (fully bypassed) in
            // ≤6 dB rounds; the normalizer's own measurement keeps
            // running (its state stays warm for the switch back).
            session.bypass_cancel_db = DspControls::ramp_gain(
                session.bypass_cancel_db,
                session.last_normalizer_gain_db,
            );
            let correction = 10f64.powf(-session.bypass_cancel_db / 20.0);
            for s in render.iter_mut() {
                *s = (*s as f64 * correction) as f32;
            }
        } else {
            // Back on Normalize: ease the cancel back OUT the same way.
            if session.bypass_cancel_db != 0.0 {
                session.bypass_cancel_db =
                    DspControls::ramp_gain(session.bypass_cancel_db, 0.0);
                let correction = 10f64.powf(-session.bypass_cancel_db / 20.0);
                for s in render.iter_mut() {
                    *s = (*s as f64 * correction) as f32;
                }
            }
            // Normalize mode: the normalizer already applied its gain
            // toward target_lufs. If the LIVE target differs from the
            // session's constructor target, ease the delta (bounded
            // ±6 dB per round — no zipper on target changes).
            let delta_db = dsp.target_lufs - config.target_lufs;
            if delta_db != 0.0 {
                let eased = DspControls::ramp_gain(0.0, delta_db);
                let factor = 10f64.powf(eased / 20.0);
                for s in render.iter_mut() {
                    *s = (*s as f64 * factor) as f32;
                }
            }
        }
        // The EQ (+ its built-in tanh saturation guard): flat curve is
        // a structural no-op path? No — the biquads at 0 dB are unity
        // filters; running them costs a few mul-adds and keeps the
        // coefficient warm. Build lazily at the first round.
        if session.eq.is_none() {
            session.eq = Some(StudioEqualizer::new(48_000.0));
        }
        if let Some(eq) = session.eq.as_mut() {
            match &config.dsp {
                Some(controls) => controls.apply_eq(eq),
                None => {
                    // No live control plane: flat (the default curve).
                    for band in 0..10 {
                        eq.set_band_gain(band, 0.0);
                    }
                }
            }
            eq.process_buffer_interleaved(render, 2);
        }

        // Stage 5 setup: the seek anchor decides where writing starts.
        let anchor_frame = session.intent.start_position_ms.saturating_mul(48); // ms → frames (48 kHz)
        let render_frames = (session.render_len() / 2) as u64;
        let skip_here = anchor_frame.saturating_sub(session.rendered_frames).min(render_frames);
        session.skipped_frames += skip_here;
        session.rendered_frames += render_frames;
        session.write_cursor = (skip_here as usize) * 2;

        publish(progress, *progress_sink, &**sink, session_id, session.decoder.is_eos());
    }
}

/// Publish one progress report: state atomics + the engine's sink
/// callback.
fn publish(
    progress: &ProgressState,
    sink: &dyn ProgressSink,
    audio_sink: &dyn AudioSink,
    session_id: u64,
    eos: bool,
) {
    let stats = audio_sink.stats();
    let core = BufferProgressCore {
        buffered_ms: u64::from(stats.current_fullness_frames) * 1000 / 48_000,
        fill_ratio: stats.fullness_percent / 100.0,
        underrun_events: stats.underrun_events as u32,
        overrun_events: stats.overrun_events as u32,
        frames_written: stats.frames_written,
        session: session_id,
        eos,
    };
    progress.buffered_ms.store(core.buffered_ms, Ordering::Release);
    progress
        .fill_ratio_bits
        .store(core.fill_ratio.to_bits(), Ordering::Release);
    progress
        .underruns
        .store(core.underrun_events, Ordering::Release);
    progress
        .overruns
        .store(core.overrun_events, Ordering::Release);
    progress
        .frames_written
        .store(core.frames_written, Ordering::Release);
    sink.on_progress(core);
}
