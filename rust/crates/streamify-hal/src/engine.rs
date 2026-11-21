//! The unified cpal output engine (Phase 1 D1).
//!
//! ONE engine, three OSes: WASAPI (shared) on Windows, CoreAudio on
//! macOS, ALSA/PipeWire/Pulse on Linux. The RT callback discipline is
//! absolute — the closure handed to cpal performs ONLY:
//!
//! 1. the frozen ring read (`streamify_ring_read_f32` — lock-free by
//!    construction, the same C edge every Phase 0 bracket used),
//! 2. the zero-fill of unserved frames (clean underrun),
//! 3. the lock-free DAC-clock slot publish,
//! 4. an underrun-counter bump via the ring's own stats accounting.
//!
//! No mutexes, no channels, no syscalls, no allocation — verifiable by
//! the zero-alloc CI gate (`tests/test_zero_alloc_callback.rs`).
//!
//! # Stream lifecycle & hot-swap
//!
//! The engine OWNER thread (never the callback) handles stream errors:
//! cpal's `play()`/`pause()` and the error callback flip an
//! [`EngineEvent`], and the route layer (desktop `AudioRoutePolicy`)
//! rebuilds the stream on device invalidation (USB DAC / BT unplug) or
//! falls back to libmpv when no endpoint exists.

use std::sync::atomic::{AtomicBool, AtomicU64};
use std::sync::mpsc::{Receiver, SyncSender, TrySendError};
use std::sync::Arc;

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{BufferSize, Stream, StreamConfig};
use streamify_playback::ring_export;

use crate::dac_clock::HostClockAnchor;
use crate::device::{enumerate_outputs, prefer_sound_server, OutputRoute};
#[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
use crate::exclusive::{open_exclusive, ExclusiveGuard, ExclusiveRt};
use crate::{DacClockSlot, StopFlag, CANONICAL_CHANNELS, CANONICAL_RATE_HZ, CALLBACK_FRAMES};
use streamify_core::DacPresentationClock;

/// Which output route the engine should open.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub enum RouteOption {
    /// The hygiene-ranked default (sound-server pipes preferred on
    /// Linux, then the default device, then any capable endpoint).
    #[default]
    Auto,
    /// A named device (the output-sheet picker's choice).
    Named(String),
    /// The host's default output device, directly (the CI loopback
    /// legs' contract: the virtual loopback device IS the default).
    Default,
    /// The Windows x86_64 WASAPI-EXCLUSIVE bracket (miniaudio; steps
    /// down to cpal shared on rejection — see [`crate::exclusive`]).
    #[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
    WasapiExclusive,
}

/// Engine events surfaced to the route layer (a small bounded channel —
/// the callback NEVER blocks on it; the error callback runs on cpal's
/// thread and drops the event when the mailbox is full).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EngineEvent {
    /// The stream errored / the device vanished (hot-swap: rebuild).
    StreamInvalidated(String),
    /// The stream started cleanly (after [re]build).
    Started { device: String },
    /// The exclusive bracket stepped down to shared (closed-failure).
    SteppedDownToShared { reason: String },
}

/// The bounded event mailbox (SYNCHRONOUS small-capacity: an overflowing
/// mailbox drops with a counter bump instead of ever blocking a caller).
pub struct EngineEvents {
    rx: Receiver<EngineEvent>,
    dropped: Arc<AtomicU64>,
}

impl EngineEvents {
    /// Drain pending events (non-blocking; the owner polls).
    pub fn drain(&self) -> Vec<EngineEvent> {
        let mut out = Vec::new();
        while let Ok(ev) = self.rx.try_recv() {
            out.push(ev);
        }
        out
    }

    /// Events dropped by a full mailbox (telemetry).
    pub fn dropped(&self) -> u64 {
        self.dropped.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// The engine's RT-shared state (one `Arc` cloned into the callback;
/// the exclusive bracket mirrors it as `ExclusiveRt`).
pub(crate) struct EngineRt {
    clock: Arc<DacClockSlot>,
    anchor: HostClockAnchor,
    /// RT-side callback underrun count (frames the ring could not
    /// serve), distinct from the ring's own accounting which the stats
    /// wire already carries.
    underrun_frames: AtomicU64,
    /// Frames the callback has been asked to render since stream start
    /// (the presented-frame counter feeding the DAC clock).
    frames_counter: AtomicU64,
    first_callback_done: AtomicBool,
    callback_count: AtomicU64,
    exit_zero_frames: AtomicU64,
    exit_stop: AtomicU64,
    exit_full_path: AtomicU64,
    stop: StopFlag,
}

/// The unified output engine.
pub struct OutputEngine {
    rt: Arc<EngineRt>,
    stream: Option<Stream>,
    #[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
    exclusive: Option<ExclusiveGuard>,
    active_device: Option<String>,
    event_tx: SyncSender<EngineEvent>,
    dropped: Arc<AtomicU64>,
    /// Set when the exclusive bracket stepped down (closed-failure).
    stepped_down: bool,
}

impl OutputEngine {
    /// Build the engine without opening a device yet (the owner then
    /// calls [`start`](Self::start)). `mailbox` bounds the event queue.
    pub fn new(mailbox: usize) -> (Self, EngineEvents) {
        let (tx, rx) = std::sync::mpsc::sync_channel(mailbox.max(4));
        let dropped = Arc::new(AtomicU64::new(0));
        let events = EngineEvents {
            rx,
            dropped: Arc::clone(&dropped),
        };
        let engine = Self {
            rt: Arc::new(EngineRt {
                clock: Arc::new(DacClockSlot::default()),
                anchor: HostClockAnchor::capture(),
                underrun_frames: AtomicU64::new(0),
                frames_counter: AtomicU64::new(0),
            first_callback_done: AtomicBool::new(false),
            callback_count: AtomicU64::new(0),
            exit_zero_frames: AtomicU64::new(0),
            exit_stop: AtomicU64::new(0),
            exit_full_path: AtomicU64::new(0),
                stop: StopFlag::default(),
            }),
            stream: None,
            #[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
            exclusive: None,
            active_device: None,
            event_tx: tx,
            dropped,
            stepped_down: false,
        };
        (engine, events)
    }

    /// The shared DAC clock slot (telemetry + the JVM stats bridge).
    /// Whether the output callback has fired at least once (the
    /// player's diagnostics poll this — the RT callback itself never
    /// prints).
    /// Total output-callback invocations (diagnostics: distinguishes
    /// 'the host stopped calling' from 'the clock publish failed').
    /// Callback exit-path diagnostics (RT atomics, main-thread reads).
    pub fn callback_exits(&self) -> (u64, u64, u64) {
        (
            self.rt.exit_zero_frames.load(std::sync::atomic::Ordering::Relaxed),
            self.rt.exit_stop.load(std::sync::atomic::Ordering::Relaxed),
            self.rt.exit_full_path.load(std::sync::atomic::Ordering::Relaxed),
        )
    }

    pub fn callback_count(&self) -> u64 {
        self.rt
            .callback_count
            .load(std::sync::atomic::Ordering::Relaxed)
    }

    pub fn callback_fired(&self) -> bool {
        self.rt
            .first_callback_done
            .load(std::sync::atomic::Ordering::Relaxed)
    }

    pub fn clock_slot(&self) -> Arc<DacClockSlot> {
        Arc::clone(&self.rt.clock)
    }

    /// The engine's own underrun-frame counter (RT-published).
    pub fn underrun_frames(&self) -> u64 {
        self.rt.underrun_frames.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// The active output device name, if a stream is live.
    pub fn active_device(&self) -> Option<&str> {
        self.active_device.as_deref()
    }

    /// Open the output route and start pulling. Fails with a plain
    /// message (the route layer's cue to fall back to libmpv).
    pub fn start(&mut self, route: &RouteOption) -> Result<(), String> {
        self.stop_stream();
        // Re-arm the RT state for the NEW stream. stop_stream set the
        // stop flag to drain the OLD stream's callbacks; the flag lives
        // in the engine-lifetime EngineRt, so without this clear every
        // callback of the new stream would zero-fill forever (the
        // round-38/39 CI signature: callbacks fire, ring never drains,
        // clock never publishes). The per-stream diagnostics reset with
        // it (they describe THIS stream, not the engine).
        self.rt.stop.clear();
        self.rt
            .first_callback_done
            .store(false, std::sync::atomic::Ordering::Relaxed);
        self.rt
            .callback_count
            .store(0, std::sync::atomic::Ordering::Relaxed);
        self.rt
            .exit_zero_frames
            .store(0, std::sync::atomic::Ordering::Relaxed);
        self.rt
            .exit_stop
            .store(0, std::sync::atomic::Ordering::Relaxed);
        self.rt
            .exit_full_path
            .store(0, std::sync::atomic::Ordering::Relaxed);
        // Exclusive bracket first (Windows x86_64 + feature).
        #[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
        if matches!(route, RouteOption::WasapiExclusive) {
            // The exclusive bracket gets the same clock slot + a fresh
            // anchor (its own device stream = its own clock master).
            let xrt = Arc::new(ExclusiveRt::new(
                Arc::clone(&self.rt.clock),
                HostClockAnchor::capture(),
            ));
            match open_exclusive(&xrt) {
                Ok(guard) => {
                    self.exclusive = Some(guard);
                    self.stepped_down = false;
                    let _ = self.event_tx.try_send(EngineEvent::Started {
                        device: "WASAPI (exclusive)".into(),
                    });
                    self.active_device = Some("WASAPI (exclusive)".into());
                    return Ok(());
                }
                Err(why) => {
                    // Closed-failure step-down: report, then open cpal
                    // shared below WITHOUT interrupting playback.
                    self.stepped_down = true;
                    let _ = self.event_tx.try_send(EngineEvent::SteppedDownToShared {
                        reason: why.clone(),
                    });
                }
            }
        }
        let host = cpal::default_host();
        let device = match route {
            RouteOption::Named(name) => {
                let mut devices = host
                    .output_devices()
                    .map_err(|e| e.to_string())?;
                devices
                .find(|d| {
                    cpal::traits::DeviceTrait::description(d)
                        .map(|desc| {
                            // Exact first; substring fallback (the CI's
                            // virtual device names may carry suffixes).
                            let n = desc.name();
                            n == name.as_str() || n.contains(name.as_str())
                        })
                        .unwrap_or(false)
                })
                .ok_or_else(|| format!("output device not found: {name}"))?
            }
            RouteOption::Default => {
                // The CI contract: open the host's default output device.
                // cpal's default_host() picks ONE backend (PipeWire first
                // on Linux); when that backend's registry hiccups (the
                // headless runner's pipewire context races the daemon),
                // fall through to the OTHER available hosts (pulse,
                // alsa) rather than failing the leg.
                if let Some(d) = host.default_output_device() {
                    d
                } else {
                    let mut found = None;
                    for id in cpal::available_hosts() {
                        if let Some(h) = cpal::host_from_id(id).ok() {
                            if let Some(d) = h.default_output_device() {
                                found = Some(d);
                                break;
                            }
                        }
                    }
                    found.ok_or("no default output device on any host")?
                }
            }
            _ => match resolve_auto_route() {
                OutputRoute::Device(d) => {
                    let mut devices = host
                        .output_devices()
                        .map_err(|e| e.to_string())?;
                    devices
                    .find(|dev| {
                        cpal::traits::DeviceTrait::description(dev)
                            .map(|desc| desc.name() == d.name)
                            .unwrap_or(false)
                    })
                    .ok_or_else(|| format!("auto route device vanished: {}", d.name))?
                }
                OutputRoute::NoEndpoint => {
                    return Err("no 48 kHz stereo output endpoint available".into())
                }
            },
        };
        let name = cpal::traits::DeviceTrait::description(&device)
            .map_err(|e| e.to_string())?
            .name()
            .to_string();
        // Deterministic sizing (PipeWire latency hygiene): request
        // Fixed(1024) when the device range contains it, else the
        // device's default config (still 48 kHz stereo when supported).
        let mut configs = cpal::traits::DeviceTrait::supported_output_configs(&device)
            .map_err(|e| e.to_string())?;
        // The canonical request: 48 kHz stereo. Sizing: Default —
        // cpal 0.18.2's Fixed(N) output paths die on virtual devices
        // (RustAudio/cpal#487 'output stream dies with small buffer
        // size', #1422 'ring three periods for BufferSize::Fixed': the
        // ALSA host rings exactly three periods for a Fixed request and
        // the CoreAudio host keeps stale fixed sizes — the CI loopback
        // legs' virtual devices deliver exactly ONE callback then go
        // silent). The deterministic Fixed(1024) preference returns
        // with a fixed cpal (0.19+) or a PipeWire-native path.
        let buffer = BufferSize::Default;
        let _ = configs; // consumed by the capability audit below
        // Device sanity: ANY output config means the endpoint is usable.
        // (Windows RDP endpoints sometimes report no 48 kHz RANGE while
        // still accepting a 48 kHz stream — the shared-mode mix format
        // resamples; Default sizing rides that path.)
        let _ = cpal::traits::DeviceTrait::default_output_config(&device)
            .map_err(|e| format!("device {name} has no output config: {e}"))?;
        let config = StreamConfig {
            channels: CANONICAL_CHANNELS,
            sample_rate: CANONICAL_RATE_HZ, // cpal 0.18: plain u32
            buffer_size: buffer,
        };
        eprintln!(
            "engine: device {name} -> 48kHz stereo, buffer {:?}",
            config.buffer_size
        );
        let stream = self.build_stream(&device, &config, &name)?;
        stream.play().map_err(|e| e.to_string())?;
        self.stream = Some(stream);
        self.active_device = Some(name.clone());
        self.stepped_down = false;
        let _ = self.event_tx.try_send(EngineEvent::Started { device: name });
        Ok(())
    }

    /// Build the cpal stream with THE disciplined callback (the one
    /// place the frozen ring edge is called).
    fn build_stream(
        &self,
        device: &cpal::Device,
        config: &StreamConfig,
        name: &str,
    ) -> Result<Stream, String> {
        let rt = Arc::clone(&self.rt);
        let rate = config.sample_rate; // cpal 0.18: plain u32
        let err_name = name.to_string();
        let err_tx = self.event_tx.clone();
        let err_dropped = Arc::clone(&self.dropped);
        device
            .build_output_stream::<f32, _, _>(
                (*config).clone(), // cpal takes the config by value
                move |data: &mut [f32], info: &cpal::OutputCallbackInfo| {
                    // ── THE RT PATH (zero alloc / lock / syscall) ──
                    // Phase 5 / Pillar 1: the whole body runs inside the
                    // allocation trap when the crate under test is built
                    // with `rt-alloc-trap` (assert_no_alloc — any
                    // Box::new/Vec::push/format!/malloc on the audio
                    // thread aborts the test); production builds compile
                    // the pass-through closure (zero cost, zero deps).
                    streamify_playback::rt_trap::assert_no_alloc(|| {
                    // First-callback mark ONLY (an atomic swap — the
                    // round-34 eprintln inside the callback stalled the
                    // stream: stderr's lock on the audio thread blocked
                    // the host's IO loop; the cpal-only probe without it
                    // sustained 283 callbacks / 3 s on the same device).
                    rt.callback_count.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    let _ = rt
                        .first_callback_done
                        .swap(true, std::sync::atomic::Ordering::Relaxed);
                    let _ = info;
                    let frames = data.len() / CANONICAL_CHANNELS as usize;
                    if frames == 0 {
                        rt.exit_zero_frames.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        return;
                    }
                    if rt.stop.get() {
                        rt.exit_stop.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        data.fill(0.0);
                        return;
                    }
                    rt.exit_full_path.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                    // 1. The frozen C edge (SPSC consumer side).
                    let served = unsafe {
                        ring_export::streamify_ring_read_f32(data.as_mut_ptr(), frames)
                    };
                    let served_samples = served * CANONICAL_CHANNELS as usize;
                    // 2. Clean underrun zero-fill + counter.
                    if served_samples < data.len() {
                        rt.underrun_frames
                            .fetch_add(((data.len() - served_samples) / 2) as u64, std::sync::atomic::Ordering::Relaxed);
                        for s in data[served_samples..].iter_mut() {
                            *s = 0.0;
                        }
                    }
                    // 3. DAC presentation clock publish (lock-free).
                    // cpal 0.18's StreamInstant exposes the host
                    // timestamp in nanoseconds (`as_nanos` — the
                    // WASAPI QPC / CoreAudio host-time / ALSA
                    // monotonic domain). The DAC frame counter derives
                    // from the callback's own frame accounting: the
                    // frames the stream has REQUESTED since start (the
                    // hardware-presented count the clock master tracks;
                    // cpal's playback instant carries no frame field).
                    rt.frames_counter.fetch_add(frames as u64, std::sync::atomic::Ordering::Relaxed);
                    let presented = rt
                        .frames_counter
                        .load(std::sync::atomic::Ordering::Relaxed);
                    // Host clock: the callback's playback instant when the
                    // host reports one (WASAPI QPC / ALSA monotonic), else
                    // the engine's anchor domain (CoreAudio reports the
                    // playback instant only after first delivery — 0 during
                    // the stream's first callbacks).
                    let host_ns = info.timestamp().playback.as_nanos() as u64;
                    let monotonic_ns = if host_ns > 0 { host_ns } else { rt.anchor.now_ns() };
                    let sample = DacPresentationClock {
                        dac_frame_position: presented,
                        sample_rate: rate,
                        monotonic_ns,
                    };
                    rt.clock.publish(&sample);
                    }) // rt_trap::assert_no_alloc — end of the guarded RT window
                },
                move |err| {
                    // Runs on cpal's event thread, NOT the RT callback:
                    // surface via the bounded mailbox (drop-on-full with
                    // a counter bump — never blocks).
                    let ev = EngineEvent::StreamInvalidated(format!("{err_name}: {err}"));
                    match err_tx.try_send(ev) {
                        Ok(()) => {}
                        Err(TrySendError::Full(_)) => {
                            err_dropped.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        }
                        Err(TrySendError::Disconnected(_)) => {}
                    }
                },
                None,
            )
            .map_err(|e| e.to_string())
    }

    /// Pause (the ring keeps filling; cpal stops pulling).
    pub fn pause(&self) -> Result<(), String> {
        if let Some(s) = &self.stream {
            s.pause().map_err(|e| e.to_string())
        } else {
            Ok(())
        }
    }

    /// Resume pulling.
    pub fn resume(&self) -> Result<(), String> {
        if let Some(s) = &self.stream {
            s.play().map_err(|e| e.to_string())
        } else {
            Ok(())
        }
    }

    /// Stop and drop the stream (owner side; the callback's Arc frees
    /// when cpal releases it).
    pub fn stop_stream(&mut self) {
        self.rt.stop.set();
        self.stream = None; // Drop closes the cpal stream.
        #[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
        if let Some(guard) = self.exclusive.take() {
            guard.stop();
        }
        self.active_device = None;
    }

    /// Whether the exclusive bracket stepped down to shared (CI + route
    /// telemetry).
    pub fn stepped_down(&self) -> bool {
        self.stepped_down
    }
}

/// Resolve the auto route (sound-server hygiene on Linux).
fn resolve_auto_route() -> OutputRoute {
    prefer_sound_server(&enumerate_outputs())
}

// The channel-count export the JVM bridge reads (kept public for the
// stats telemetry's geometry assertion).
pub use crate::CANONICAL_CHANNELS as ENGINE_CHANNELS;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_builds_without_device() {
        // On a machine with no audio host devices (headless CI), the
        // engine still constructs and the clock slot stays empty.
        let (mut engine, events) = OutputEngine::new(8);
        assert!(engine.active_device().is_none());
        assert!(engine.clock_slot().sample().is_none());
        assert_eq!(engine.underrun_frames(), 0);
        assert!(events.drain().is_empty());
    }

    #[test]
    fn auto_route_resolves_or_reports_no_endpoint() {
        // Headless CI boxes may expose zero devices — both outcomes are
        // valid; the contract is that it never panics.
        let _ = resolve_auto_route();
    }
}
