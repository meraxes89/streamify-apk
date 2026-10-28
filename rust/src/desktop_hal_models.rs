//! Desktop HAL mock models — Phase 4b (MP-HAL08 / MP-HAL09, deliverable 3):
//! host-testable mock consumers that emulate the Windows WASAPI
//! event-driven render thread, the Linux PipeWire `pw_stream` process
//! callback and the ALSA `snd_pcm_writei` fallback writer, validating
//! their buffer-pull + `DacPresentationClock` contracts against the
//! Phase 4a [`VirtualHalSink`] adversarial double WITHOUT physical
//! Windows/Linux audio hardware.
//!
//! # What is being validated (and how)
//!
//! The native desktop adapters (`native/audio/wasapi_pull_adapter.cpp`,
//! `native/audio/pipewire_driver.cpp`) each split into a platform TU and
//! a HOST-TESTABLE render core (`WasapiRenderCore`, `PipeWireRenderCore`,
//! `AlsaRenderCore`) — the C++ suites audit those cores directly. This
//! module is the pure-Rust twin of that discipline for the sovereign
//! core's own CI lane (the `rust-core-engine` shard): each model wraps a
//! [`VirtualHalSink`] — the simulated hardware DAC with the ground-truth
//! presentation timeline — and drives it with the platform's exact PULL
//! CADENCE and CLOCK-PLANE semantics:
//!
//! * **WASAPI model** — the event-driven variable quantum
//!   (`buffer_frames - current_padding`, never a fixed period) and the
//!   `IAudioClock` clock plane: device position in
//!   `IAudioClock::GetFrequency` ticks (here 2 ticks/frame), captured
//!   with a QPC value in 100 ns units (the model round-trips the
//!   double's consistent pair through both units and back).
//! * **PipeWire model** — the `pw_buffer` window/requested-hint quantum
//!   and the `pw_time` clock plane: graph ticks at a FOREIGN tick rate
//!   (`rate = {1, 96000}` behind a 48 kHz stream — the defensive-rescale
//!   path), `now` in CLOCK_MONOTONIC ns.
//! * **ALSA model** — the fallback writer's fixed period windows and the
//!   classic presentation estimate: `presented = total_submitted -
//!   device_delay`, delay sampled back-to-back with CLOCK_MONOTONIC.
//!
//! Every model implements [`AudioHalConsumer`] (the same polymorphic
//! contract the platform shells see), so the existing
//! `DacClockInterpolator` acceptance machinery applies unchanged: the
//! <5 ms interpolation bound must hold for every desktop flavor.
//!
//! # Zero-allocation note
//!
//! The models are TEST SUPPORT, not the RT path themselves — but they are
//! still written allocation-free on the pull path (all state is
//! pre-allocated at construction; `hal_pull` borrows the caller's buffer)
//! so they can double as load-drivers in future RT audits.

use crate::contract::DacPresentationClock;
use crate::virtual_hal::{
    AudioHalConsumer, HalPullReport, VirtualHalConfig, VirtualHalSink,
};

/// Canonical desktop stream rate (Hz) — mirrors `audio_src::CANONICAL_RATE_HZ`.
pub const DESKTOP_CANONICAL_RATE_HZ: u32 = 48_000;

// ---------------------------------------------------------------------------
// Clock-plane conversion helpers — the Rust twins of the C++ render-core
// helpers. Kept free functions so the contract tests can hit them with
// exact known values (mirroring the C++ suite's helper lanes).
// ---------------------------------------------------------------------------

/// `IAudioClock` position ticks → stream FRAMES
/// (`WasapiRenderCore::ScaleDevicePosition`). Identity whenever the tick
/// frequency equals the stream rate; truncating otherwise; degenerate
/// frequencies pass ticks through.
pub fn wasapi_scale_device_position(
    position_ticks: u64,
    ticks_per_second: u64,
    stream_rate: u32,
) -> u64 {
    if ticks_per_second == 0 {
        return position_ticks; // defensive: bad frequency
    }
    if ticks_per_second == u64::from(stream_rate) {
        return position_ticks; // ticks are frames
    }
    // 128-bit intermediate on every Rust target we ship (u64:x u64 -> u128).
    let frames =
        (u128::from(position_ticks) * u128::from(stream_rate)) /
        u128::from(ticks_per_second);
    frames as u64
}

/// QPC (100 ns units) → monotonic ns (`WasapiRenderCore::Qpc100ToNs`).
/// Saturating: a QPC that would overflow ns clamps instead of wrapping.
pub fn wasapi_qpc100_to_ns(qpc_100ns: u64) -> u64 {
    qpc_100ns.saturating_mul(100)
}

/// `pw_time.ticks` → stream FRAMES (`PipeWireRenderCore::TicksToFrames`).
/// `num/denom` is the tick PERIOD fraction, canonically `{1, stream_rate}`
/// — the identity. Foreign graph clocks rescale
/// (`frames = ticks * num * rate / denom`, truncating); degenerate
/// fractions pass ticks through.
pub fn pipewire_ticks_to_frames(
    ticks: u64,
    rate_num: u32,
    rate_denom: u32,
    stream_rate: u32,
) -> u64 {
    if rate_num == 0 || rate_denom == 0 {
        return ticks; // defensive: degenerate fraction
    }
    // Identity fast path: tick period * stream_rate == the divisor, i.e.
    // the canonical {1, R} spelling with stream_rate == R.
    if u64::from(rate_num) * u64::from(stream_rate) ==
        u64::from(rate_denom)
    {
        return ticks;
    }
    let frames = (u128::from(ticks) * u128::from(rate_num) *
        u128::from(stream_rate)) /
        u128::from(rate_denom);
    frames as u64
}

/// The classic ALSA presentation estimate
/// (`AlsaRenderCore::PresentedFrames`): frames actually played at the
/// instant the delay was sampled. Saturating: a corrupt delay larger than
/// the submitted total clamps to zero (never a position regression).
pub fn alsa_presented_frames(
    total_submitted: u64,
    device_delay_frames: u64,
) -> u64 {
    total_submitted.saturating_sub(device_delay_frames)
}

/// The WASAPI event-driven quantum (`WasapiRenderCore::FramesToRender`):
/// `buffer_frames - current_padding`, saturating to a no-op cycle when
/// the padding lies.
pub fn wasapi_frames_to_render(
    buffer_frames: u32,
    current_padding: u32,
) -> u32 {
    buffer_frames.saturating_sub(current_padding)
}

// ---------------------------------------------------------------------------
// The models
// ---------------------------------------------------------------------------

/// Which desktop HAL a model emulates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopHalFlavor {
    /// Windows event-driven WASAPI render thread (MP-HAL08).
    Wasapi,
    /// Linux PipeWire `pw_stream` process callback (MP-HAL09).
    PipeWire,
    /// Linux ALSA `snd_pcm_writei` fallback writer (MP-HAL09 fallback).
    Alsa,
}

impl DesktopHalFlavor {
    /// Stable identifier for diagnostics.
    pub fn id(&self) -> &'static str {
        match self {
            DesktopHalFlavor::Wasapi => "wasapi",
            DesktopHalFlavor::PipeWire => "pipewire",
            DesktopHalFlavor::Alsa => "alsa",
        }
    }
}

/// Configuration for one desktop HAL mock model.
#[derive(Debug, Clone)]
pub struct DesktopHalModelConfig {
    /// The emulated desktop HAL.
    pub flavor: DesktopHalFlavor,
    /// Stream rate (Hz). Default: canonical 48 kHz.
    pub sample_rate: u32,
    /// Channel geometry (1 | 2). Default: 2 (canonical stereo).
    pub channels: u8,
    /// Nominal HAL quantum (256 | 512 | 1024). Default: 512.
    pub callback_frames: usize,
    /// Ring capacity in frames (pow2-rounded). Default: 8192.
    pub capacity_frames: usize,
    /// Simulated DAC ±fire-time variance (0.10 = ±10 %).
    pub jitter_fraction: f64,
    /// Reported-timestamp grid in ns (0 = exact; 1_000_000 models a 1 ms
    /// HAL timestamp grid).
    pub timestamp_quantum_ns: u64,
    /// DAC-vs-monotonic clock drift in ppm.
    pub drift_ppm: i32,
    /// SplitMix64 seed: identical seed ⇒ identical run.
    pub rng_seed: u64,

    // ---- platform-shaped knobs ------------------------------------------
    /// WASAPI endpoint buffer, in nominal quanta (the event-driven
    /// buffer whose free space paces the render thread). Default: 4.
    pub wasapi_buffer_quanta: u32,
    /// WASAPI device drain per event (frames the DAC consumes between
    /// two buffer events). Default: the nominal quantum.
    pub wasapi_device_period_frames: u32,
    /// PipeWire offered buffer window, in nominal quanta (the
    /// `spa_data.maxsize` the graph offers per cycle). Default: 2.
    pub pipewire_window_quanta: u32,
    /// ALSA fallback period window, in frames (the `snd_pcm_writei`
    /// window). Default: 256 (5.33 ms @ 48 kHz).
    pub alsa_period_frames: u32,
}

impl Default for DesktopHalModelConfig {
    fn default() -> Self {
        Self {
            flavor: DesktopHalFlavor::Wasapi,
            sample_rate: DESKTOP_CANONICAL_RATE_HZ,
            channels: 2,
            callback_frames: 512,
            capacity_frames: 8192,
            jitter_fraction: 0.10,
            timestamp_quantum_ns: 1_000_000,
            drift_ppm: 200,
            rng_seed: 0x5354_5245_414D_4946, // "STREAMIFY"
            wasapi_buffer_quanta: 4,
            wasapi_device_period_frames: 512,
            pipewire_window_quanta: 2,
            alsa_period_frames: 256,
        }
    }
}

/// One desktop HAL mock model: a [`VirtualHalSink`] (the simulated DAC
/// with the ground-truth presentation timeline) driven through one
/// platform's pull cadence and clock-plane semantics.
///
/// `hal_pull(dst)` renders the platform-shaped quantum out of `dst`
/// (WASAPI: the variable `buffer - padding` free space; PipeWire: the
/// offered window / requested hint; ALSA: the fixed period window) and
/// returns the [`HalPullReport`] whose clock is the double's CONSISTENT
/// pair expressed in the platform's clock units:
///
/// * WASAPI — device ticks at 2 ticks/frame + QPC capture (100 ns units),
/// * PipeWire — graph ticks at `{1, 96000}` (foreign clock) + `now` ns,
/// * ALSA — `presented = submitted - delay` + CLOCK_MONOTONIC ns.
///
/// The conversions deliberately ROUND-TRIP through the platform units
/// (frames → ticks/QPC → frames), so a semantics error in either
/// direction shows up as a clock-consistency failure in the tests.
#[derive(Debug)]
pub struct DesktopHalModel {
    flavor: DesktopHalFlavor,
    channels: usize,
    vhal: VirtualHalSink,

    // ---- WASAPI state (MP-HAL08) ----------------------------------------
    buffer_frames: u32, // endpoint buffer size
    padding: u32,       // frames the endpoint still holds
    device_period: u32, // DAC drain between events
    tick_frequency: u64, // IAudioClock::GetFrequency (2 * rate)
    first_event: bool,  // the initial whole-buffer render

    // ---- PipeWire state (MP-HAL09) --------------------------------------
    window_frames: u32, // spa_data.maxsize / frame_bytes
    graph_rate_num: u32, // pw_time.rate.num
    graph_rate_denom: u32, // pw_time.rate.denom (foreign: 2 * rate)
    requested_hint: u64, // pw_buffer.requested (0 = no hint this cycle)

    // ---- ALSA state (MP-HAL09 fallback) ---------------------------------
    alsa_period: u32,   // snd_pcm_writei window
    alsa_submitted: u64, // total frames handed to the device

    // ---- telemetry --------------------------------------------------------
    pulls: u64,
    frames_supplied: u64,
    starved_pulls: u64,
    frames_silenced: u64,
}

impl DesktopHalModel {
    /// Build the model (and its simulated DAC double). Fails with
    /// `EngineError::Audio { FormatUnsupported }` on out-of-envelope
    /// configuration — the same envelope the VirtualHAL double enforces.
    pub fn new(config: &DesktopHalModelConfig) -> Result<Self, crate::contract::EngineError> {
        // The double validates the shared envelope (rate / channels /
        // quantum / capacity / jitter / drift / quantization).
        let vhal = VirtualHalSink::new(VirtualHalConfig {
            sample_rate: config.sample_rate,
            channels: config.channels,
            capacity_frames: config.capacity_frames,
            callback_frames: config.callback_frames,
            modes: Default::default(),
            jitter_fraction: config.jitter_fraction,
            timestamp_quantum_ns: config.timestamp_quantum_ns,
            drift_ppm: config.drift_ppm,
            rng_seed: config.rng_seed,
        })?;

        let channels = usize::from(config.channels);
        let nominal = config.callback_frames;
        if config.wasapi_buffer_quanta == 0 ||
            config.wasapi_buffer_quanta > 64 ||
            config.pipewire_window_quanta == 0 ||
            config.pipewire_window_quanta > 64 ||
            config.alsa_period_frames == 0 ||
            config.alsa_period_frames as usize > nominal * 16 ||
            config.wasapi_device_period_frames == 0
        {
            return Err(crate::contract::EngineError::Audio {
                kind: crate::contract::AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }

        Ok(Self {
            flavor: config.flavor,
            channels,
            vhal,
            buffer_frames: config.wasapi_buffer_quanta * nominal as u32,
            padding: 0,
            device_period: config.wasapi_device_period_frames,
            tick_frequency: u64::from(config.sample_rate) * 2,
            first_event: true,
            window_frames: config.pipewire_window_quanta * nominal as u32,
            graph_rate_num: 1,
            graph_rate_denom: config.sample_rate * 2, // foreign 2x clock
            requested_hint: 0,
            alsa_period: config.alsa_period_frames,
            alsa_submitted: 0,
            pulls: 0,
            frames_supplied: 0,
            starved_pulls: 0,
            frames_silenced: 0,
        })
    }

    /// The emulated desktop flavor.
    pub fn flavor(&self) -> DesktopHalFlavor {
        self.flavor
    }

    /// Producer side: write interleaved f32 frames into the simulated
    /// hardware ring (the "app" under test). Returns the SAMPLE count
    /// consumed; a short return means the ring is full.
    pub fn write_producer_frames(&mut self, samples: &[f32]) -> usize {
        self.vhal.write_producer_frames(samples)
    }

    /// The platform-shaped quantum for the next pull, given the caller's
    /// buffer capacity (frames).
    pub fn next_quantum(&self, dst_capacity_frames: usize) -> usize {
        let cap = dst_capacity_frames as u64;
        let quantum: u64 = match self.flavor {
            DesktopHalFlavor::Wasapi => {
                // The event-driven contract: the thread renders the
                // endpoint's free space — the FIRST event drains the whole
                // buffer, later events see only the device-period drain.
                if self.first_event {
                    u64::from(self.buffer_frames)
                } else {
                    u64::from(wasapi_frames_to_render(
                        self.buffer_frames,
                        self.padding,
                    ))
                }
            }
            DesktopHalFlavor::PipeWire => {
                // The offered window (maxsize / frame_bytes), optionally
                // cut down by the resampler's requested hint.
                let window = u64::from(self.window_frames);
                if self.requested_hint != 0 && self.requested_hint < window
                {
                    self.requested_hint
                } else {
                    window
                }
            }
            DesktopHalFlavor::Alsa => u64::from(self.alsa_period),
        };
        quantum.min(cap) as usize
    }

    /// Ground-truth accessors passthrough (the contract tests drive the
    /// acceptance bound through these).
    pub fn sim_time_ns(&self) -> u64 {
        self.vhal.sim_time_ns()
    }

    pub fn true_position_frames_at(&self, now_ns: u64) -> f64 {
        self.vhal.true_position_frames_at(now_ns)
    }

    pub fn stats(&self) -> crate::virtual_hal::VirtualHalStats {
        self.vhal.stats()
    }

    /// Model telemetry (cumulative across pulls).
    pub fn model_telemetry(&self) -> DesktopHalTelemetry {
        DesktopHalTelemetry {
            pulls: self.pulls,
            frames_supplied: self.frames_supplied,
            starved_pulls: self.starved_pulls,
            frames_silenced: self.frames_silenced,
        }
    }

    /// Advance the PipeWire `pw_buffer.requested` hint (the resampler's
    /// per-cycle request). 0 = no hint (fill the whole window). Only
    /// meaningful for the PipeWire flavor; a no-op otherwise.
    pub fn set_requested_hint(&mut self, frames: u64) {
        if self.flavor == DesktopHalFlavor::PipeWire {
            self.requested_hint = frames;
        }
    }

    /// Advance the simulated WASAPI device between two buffer events
    /// (the DAC consumes `device_period` frames). Exposed so the tests
    /// can drive the event cadence explicitly; `hal_pull` also calls it
    /// automatically after each render cycle.
    pub fn wasapi_device_advance(&mut self) {
        self.padding = self.padding.saturating_sub(self.device_period);
    }
}

impl AudioHalConsumer for DesktopHalModel {
    /// One platform-shaped hardware pull. `dst` is an INTERLEAVED f32
    /// buffer owned by the caller (the HAL window); the model renders the
    /// platform quantum into it (zero-filling any starved tail — the
    /// double's semantics) and returns the pull report whose clock is the
    /// double's CONSISTENT pair expressed through the platform's clock
    /// units.
    fn hal_pull(&mut self, dst: &mut [f32]) -> HalPullReport {
        let capacity = dst.len() / self.channels;
        let quantum = self.next_quantum(capacity);
        // The caller may offer less than the platform quantum this cycle
        // (a short window is legal for every flavor): render what fits.
        let take = quantum.min(capacity);
        let window = &mut dst[..take * self.channels];

        // The simulated DAC callback (data plane + ground-truth clock).
        let callback = self.vhal.run_callback_into(window);
        let report = callback.as_report();

        // ---- platform clock-plane conversion (round-trip semantics) ----
        let truth = callback.clock;
        let clock = match self.flavor {
            DesktopHalFlavor::Wasapi => {
                // Device position ticks at tick_frequency (2x), captured
                // in QPC 100 ns units; scaled back to stream frames.
                let ticks = (u128::from(truth.dac_frame_position) *
                    u128::from(self.tick_frequency)) /
                    u128::from(truth.sample_rate);
                let qpc100 = truth.monotonic_ns / 100; // QPC granularity
                DacPresentationClock {
                    dac_frame_position: wasapi_scale_device_position(
                        ticks as u64,
                        self.tick_frequency,
                        truth.sample_rate,
                    ),
                    sample_rate: truth.sample_rate,
                    monotonic_ns: wasapi_qpc100_to_ns(qpc100),
                }
            }
            DesktopHalFlavor::PipeWire => {
                // Graph ticks at {1, 2*rate} (the foreign-clock lane),
                // `now` in CLOCK_MONOTONIC ns; rescaled on ingest.
                let graph_ticks = (u128::from(truth.dac_frame_position) *
                    u128::from(self.graph_rate_denom)) /
                    u128::from(truth.sample_rate);
                DacPresentationClock {
                    dac_frame_position: pipewire_ticks_to_frames(
                        graph_ticks as u64,
                        self.graph_rate_num,
                        self.graph_rate_denom,
                        truth.sample_rate,
                    ),
                    sample_rate: truth.sample_rate,
                    monotonic_ns: truth.monotonic_ns, // pw_time.now
                }
            }
            DesktopHalFlavor::Alsa => {
                // presented = submitted - delay; the delay is whatever the
                // device still holds (a realistic, slowly varying level).
                self.alsa_submitted += take as u64;
                let delay = self
                    .alsa_submitted
                    .saturating_sub(truth.dac_frame_position);
                DacPresentationClock {
                    dac_frame_position: alsa_presented_frames(
                        self.alsa_submitted,
                        delay,
                    ),
                    sample_rate: truth.sample_rate,
                    monotonic_ns: truth.monotonic_ns, // CLOCK_MONOTONIC
                }
            }
        };

        // ---- platform cadence bookkeeping ---------------------------------
        match self.flavor {
            DesktopHalFlavor::Wasapi => {
                self.padding =
                    (self.padding + take as u32).min(self.buffer_frames);
                self.first_event = false;
                // The DAC drains one device period before the next event.
                self.wasapi_device_advance();
            }
            DesktopHalFlavor::PipeWire => {
                // One hint per cycle: the resampler asks for the next
                // quantum's worth (0 = no hint; the window governs).
                self.requested_hint = 0;
            }
            DesktopHalFlavor::Alsa => {
                // submitted already advanced above; the writer thread's
                // pacing is the caller's business (the model is
                // cooperatively scheduled).
            }
        }

        // ---- telemetry -----------------------------------------------------
        self.pulls += 1;
        self.frames_supplied += report.frames_supplied as u64;
        if report.starved {
            self.starved_pulls += 1;
            self.frames_silenced +=
                (report.frames_requested - report.frames_supplied) as u64;
        }

        HalPullReport {
            frames_requested: take,
            frames_supplied: report.frames_supplied,
            starved: report.starved,
            clock,
        }
    }
}

/// Model telemetry (the mock twin of the native cores' counters).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct DesktopHalTelemetry {
    /// Total pull callbacks driven through the model.
    pub pulls: u64,
    /// Real audio frames supplied from the ring.
    pub frames_supplied: u64,
    /// Pulls whose starved tail was zero-filled.
    pub starved_pulls: u64,
    /// Frames the simulated DAC zero-filled (starved tails).
    pub frames_silenced: u64,
}
