//! VirtualHAL — deterministic adversarial audio test double with a
//! simulated hardware DAC presentation clock (Phase 4a / MP-HAL01 +
//! MP-HAL02, master plan `MULTIPLATFORM_PLAN_27_10` §4 Phase 4a).
//!
//! An in-memory, deterministic, platform-agnostic audio sink that emulates
//! hardware PULL callbacks and validates the [`DacPresentationClock`]
//! contract WITHOUT physical audio hardware. It runs headless on every CI
//! runner and is the reference oracle for the Phase 4a acceptance target:
//!
//! > DacPresentationClock monotonic timestamp interpolation must stay
//! > within **< 5 ms** of the simulated presentation time under adversarial
//! > callback conditions (master plan invariant 3; the MP-HAL06 hardware
//! > rehearsal).
//!
//! This module is the pure-Rust twin of `native/include/virtual_hal.h`
//! (which drives the REAL hardened `AudioSink` SPSC ring on the C++ side):
//! same clock model, same adversarial modes, same SplitMix64 schedule —
//! byte-for-byte comparable scenarios. It exists so the sovereign core's
//! own CI (and the future JVM/Swift harness lanes) can exercise the HAL
//! clock contract without linking native code.
//!
//! # Simulated DAC model (per epoch — an epoch ends at a rate reconfig)
//!
//! * The device presents exactly `rate` frames per second of monotonic
//!   time from the epoch origin: frame `F` presents at
//!   `t(F) = epoch_origin_ns + F * 1e9 / rate_true` where
//!   `rate_true = rate * (1 + drift_ppm * 1e-6)`. The mapping is computed
//!   in IEEE-754 `f64` (mul/div only — bit-deterministic on every target;
//!   no transcendentals on the clock path).
//! * Callback `k` renders quantum `[S, S + Q)`: it pulls up to `Q` frames
//!   from the in-memory ring (silence-filling any starved tail — a
//!   starving consumer never stalls the hardware clock) and reports the
//!   CONSISTENT pair `dac_frame_position = S`, `monotonic_ns = t(S)`
//!   (CoreAudio `mSampleTime` semantics: the first frame of the quantum
//!   being rendered), with the timestamp quantized onto
//!   `timestamp_quantum_ns` (the grid real HALs report on; 0 = exact).
//! * The callback FIRE time carries the ±`jitter_fraction` interval
//!   variance (a pull HAL fires ~one full period early). Fire times are
//!   diagnostics ONLY — pairing them with the frame counter is the classic
//!   broken-estimator mistake, and the suite uses them as the negative
//!   control proving the 5 ms bound is actually measurable.
//!
//! # Adversarial modes (MP-HAL02)
//!
//! * [`AdversarialModes::TIMING_JITTER`]  — ±fire-time variance per
//!   callback interval (default ±10 %).
//! * [`AdversarialModes::INJECT_UNDERRUN`] — scripted producer starvation
//!   windows; the consumer pulls starved quanta (digital silence).
//! * [`AdversarialModes::INJECT_OVERRUN`]  — scripted producer floods
//!   (writes beyond ring capacity: short writes, overrun telemetry).
//! * [`AdversarialModes::RATE_CHANGE`]     — mid-stream device rate
//!   changes; per master plan invariant 6 the stream is torn down and
//!   rebuilt: the ring is flushed, the frame epoch restarts and the clock
//!   domain moves.
//!
//! All scheduling is driven by an internal SplitMix64 PRNG seeded from the
//! config: identical seed ⇒ identical callback-for-callback run on every
//! platform (integer-only state, no `rand` dependence).
//!
//! # Determinism & threading contract
//!
//! The double is cooperatively scheduled (no internal threads): the test
//! drives producer writes and callback pulls in explicit order, so a
//! failing scenario replays exactly. Real SPSC thread contention is the
//! native ring's job (10M-frame stress in `test_audio_sink_ringbuffer`);
//! this double layers the ADVERSARIAL CLOCK semantics on a plain-cursor
//! in-memory ring.

use crate::contract::{AudioErrorKind, DacPresentationClock, EngineError};

/// Canonical pipeline rate (Hz) — mirrors `audio_src::CANONICAL_RATE_HZ`.
pub const VIRTUAL_HAL_CANONICAL_RATE_HZ: u32 = 48_000;

/// The three HAL callback chunk sizes the double models (MP-HAL02).
pub const CALLBACK_CHUNK_SIZES: [usize; 3] = [256, 512, 1024];

/// Phase 4a acceptance bound: interpolation error vs simulated
/// presentation time (master plan invariant 3 / MP-HAL06 rehearsal).
pub const INTERPOLATION_ACCEPTANCE_MS: f64 = 5.0;

/// Deterministic SplitMix64 — callback-identical on every platform.
#[derive(Debug, Clone)]
struct SplitMix64 {
    s: u64,
}

impl SplitMix64 {
    fn new(seed: u64) -> Self {
        Self { s: seed }
    }

    fn next_u64(&mut self) -> u64 {
        self.s = self.s.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.s;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Symmetric float in `[-1.0, 1.0)` — used ONLY for fire-time jitter
    /// (never for reported clock data: the clock path stays exact).
    fn next_symmetric(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 * (1.0 / 9_007_199_254_740_992.0) * 2.0 - 1.0
    }
}

// ---------------------------------------------------------------------------
// The platform-agnostic audio consumer interface
// ---------------------------------------------------------------------------

/// The pull-mode audio consumer contract every HAL adapter (CoreAudio,
/// AAudio, WASAPI, PipeWire — and the VirtualHAL test double) implements.
///
/// `dst` is an INTERLEAVED `f32` buffer owned by the HAL (the
/// `AURenderCallback` `ioData`): `dst.len()` must be a whole number of
/// frames for the sink's channel geometry. The implementation fills as
/// many frames as the ring can supply and zero-fills any starved tail
/// (digital silence — a starving consumer never stalls the DAC clock).
pub trait AudioHalConsumer: Send {
    /// One hardware pull callback. Returns the pull report carrying the
    /// CONSISTENT [`DacPresentationClock`] sample for this quantum.
    fn hal_pull(&mut self, dst: &mut [f32]) -> HalPullReport;
}

/// Lean result of one HAL pull callback.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HalPullReport {
    /// Frames the HAL asked for (the full quantum).
    pub frames_requested: usize,
    /// Real audio frames supplied from the ring.
    pub frames_supplied: usize,
    /// True when `frames_supplied < frames_requested` (starved tail was
    /// silence-filled).
    pub starved: bool,
    /// The consistent DAC presentation clock sample of this quantum.
    pub clock: DacPresentationClock,
}

// ---------------------------------------------------------------------------
// Interpolator
// ---------------------------------------------------------------------------

/// DacPresentationClock monotonic-timestamp interpolation — the canonical
/// consumer of HAL clock samples (the estimator every platform shell uses).
///
/// Given a stream of CONSISTENT `(frames, rate, time)` samples, the
/// presentation position at an arbitrary monotonic instant is linearly
/// extrapolated from the newest sample:
///
/// ```text
/// est(t) = F_last + (t - T_last) * rate_last / 1e9
/// ```
///
/// Epoch-reset aware: a device rate change or a frame-counter regression
/// (stream teardown/rebuild — master plan invariant 6) drops the stale
/// baseline instead of extrapolating across the discontinuity.
#[derive(Debug, Clone, Default)]
pub struct DacClockInterpolator {
    has_sample: bool,
    baseline_frames: u64,
    baseline_ns: u64,
    rate: u32,
}

impl DacClockInterpolator {
    /// Ingest one consistent clock sample (any thread; not RT-bound).
    pub fn update(&mut self, clock: &DacPresentationClock) {
        // Epoch-reset detection: rate change, frame regression or time
        // regression all mean the stream was rebuilt — re-baseline on the
        // incoming sample (never extrapolate across the discontinuity).
        self.has_sample = true;
        self.baseline_frames = clock.dac_frame_position;
        self.baseline_ns = clock.monotonic_ns;
        self.rate = clock.sample_rate;
    }

    /// True once at least one sample has been ingested.
    pub fn has_sample(&self) -> bool {
        self.has_sample
    }

    /// Interpolated presentation position, in device FRAMES, at monotonic
    /// instant `now_ns` (`None` before the first sample). Pure `u64`
    /// arithmetic on the ingest path (exact within the sample's own
    /// reported precision).
    pub fn position_frames_at(&self, now_ns: u64) -> Option<u64> {
        if !self.has_sample {
            return None;
        }
        let delta_ns = now_ns.saturating_sub(self.baseline_ns);
        let advanced =
            (delta_ns * self.rate as u64 + 500_000_000) / 1_000_000_000;
        Some(self.baseline_frames + advanced)
    }

    /// Same estimate expressed in milliseconds of presented audio.
    pub fn position_ms_at(&self, now_ns: u64) -> Option<f64> {
        self.position_frames_at(now_ns)
            .map(|f| f as f64 * 1000.0 / self.rate as f64)
    }

    /// Device rate of the current baseline sample.
    pub fn rate(&self) -> u32 {
        self.rate
    }
}

// ---------------------------------------------------------------------------
// Adversarial modes
// ---------------------------------------------------------------------------

/// Scripted adversarial stress modes (MP-HAL02). Combine with `|`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct AdversarialModes(pub u32);

impl AdversarialModes {
    /// No adversarial behaviour — the ideal HAL.
    pub const NONE: AdversarialModes = AdversarialModes(0);
    /// ±fire-time variance per callback interval (±10 % typical).
    pub const TIMING_JITTER: AdversarialModes = AdversarialModes(1 << 0);
    /// Injected underruns: consumer starves the producer.
    pub const INJECT_UNDERRUN: AdversarialModes = AdversarialModes(1 << 1);
    /// Injected overruns: producer floods the ringbuffer.
    pub const INJECT_OVERRUN: AdversarialModes = AdversarialModes(1 << 2);
    /// Mid-stream sample-rate reconfiguration events.
    pub const RATE_CHANGE: AdversarialModes = AdversarialModes(1 << 3);

    /// All modes at once (the full adversarial profile).
    pub const ALL: AdversarialModes = AdversarialModes(0b1111);

    /// True when every bit of `mode` is enabled.
    pub fn has(&self, mode: AdversarialModes) -> bool {
        (self.0 & mode.0) == mode.0
    }

    /// Union of two mode sets.
    pub fn union(self, other: AdversarialModes) -> AdversarialModes {
        AdversarialModes(self.0 | other.0)
    }
}

impl std::ops::BitOr for AdversarialModes {
    type Output = AdversarialModes;
    fn bitor(self, rhs: AdversarialModes) -> AdversarialModes {
        self.union(rhs)
    }
}

impl std::ops::BitOrAssign for AdversarialModes {
    fn bitor_assign(&mut self, rhs: AdversarialModes) {
        self.0 |= rhs.0;
    }
}

// ---------------------------------------------------------------------------
// Configuration & reports
// ---------------------------------------------------------------------------

/// VirtualHAL configuration.
#[derive(Debug, Clone)]
pub struct VirtualHalConfig {
    /// Device/epoch rate (Hz), 8000..=192000. Default: canonical 48 kHz.
    pub sample_rate: u32,
    /// Channel geometry (1 or 2). Default: 2 (canonical stereo).
    pub channels: u8,
    /// Ring capacity in frames (pow2-rounded). Default: 8192.
    pub capacity_frames: usize,
    /// HAL callback quantum: one of 256 / 512 / 1024. Default: 512.
    pub callback_frames: usize,
    /// Scripted adversarial modes (drives `run_adversarial_scenario` and
    /// the fire-time jitter).
    pub modes: AdversarialModes,
    /// Peak callback-INTERVAL variance fraction (0.10 = ±10 %).
    pub jitter_fraction: f64,
    /// Reported-timestamp grid in ns (0 = exact; 1_000_000 models a 1 ms
    /// HAL timestamp grid).
    pub timestamp_quantum_ns: u64,
    /// DAC-vs-monotonic clock drift in ppm (±2000 bounds).
    pub drift_ppm: i32,
    /// SplitMix64 seed: identical seed ⇒ identical run.
    pub rng_seed: u64,
}

impl Default for VirtualHalConfig {
    fn default() -> Self {
        Self {
            sample_rate: VIRTUAL_HAL_CANONICAL_RATE_HZ,
            channels: 2,
            capacity_frames: 8192,
            callback_frames: 512,
            modes: AdversarialModes::NONE,
            jitter_fraction: 0.10,
            timestamp_quantum_ns: 0,
            drift_ppm: 0,
            rng_seed: 0x5354_5245_414D_4946, // "STREAMIFY"
        }
    }
}

/// One simulated hardware pull callback result.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct VirtualHalCallback {
    /// Quantum the HAL asked for.
    pub frames_requested: usize,
    /// Real audio frames pulled from the ring.
    pub frames_supplied: usize,
    /// True when `frames_supplied < frames_requested`.
    pub starved: bool,
    /// The CONSISTENT reported clock pair.
    pub clock: DacPresentationClock,
    /// Jittered fire instant (diagnostics / negative control only).
    pub fire_time_ns: u64,
    /// Clock epoch of this callback (bumps per rate reconfiguration).
    pub epoch: u32,
}

impl VirtualHalCallback {
    /// The lean [`HalPullReport`] view of this callback.
    pub fn as_report(&self) -> HalPullReport {
        HalPullReport {
            frames_requested: self.frames_requested,
            frames_supplied: self.frames_supplied,
            starved: self.starved,
            clock: self.clock,
        }
    }
}

/// Live simulator telemetry (cumulative across epochs unless noted).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct VirtualHalStats {
    /// Producer frames accepted by the ring.
    pub frames_written: u64,
    /// Real frames consumed by callbacks.
    pub frames_pulled: u64,
    /// Starved frames the DAC zero-filled.
    pub frames_silenced: u64,
    /// Callbacks with `supplied < requested`.
    pub starved_callbacks: u64,
    /// Producer writes that returned short (floods).
    pub overrun_events: u64,
    /// Frames dropped by rate reconfigurations (teardown flush).
    pub frames_flushed: u64,
    /// Total simulated callbacks.
    pub callbacks_run: u64,
    /// Current clock epoch.
    pub epoch: u32,
    /// Live ring occupancy (frames).
    pub occupancy_frames: usize,
    /// Current ring capacity (frames).
    pub capacity_frames: usize,
}

// ---------------------------------------------------------------------------
// The in-memory deterministic ring
// ---------------------------------------------------------------------------

/// Plain-cursor pow-2 ring: the cooperatively-driven data plane of the
/// deterministic double. Free-running `u64` head/tail with masked access —
/// the same Lamport geometry as the native SPSC ring, minus the atomics
/// (there is exactly one driver thread by contract; see the module docs).
#[derive(Debug)]
struct SimRing {
    buf: Vec<f32>,
    capacity: usize,
    mask: usize,
    head: u64, // frames committed (producer)
    tail: u64, // frames consumed (consumer)
}

impl SimRing {
    fn new(capacity_frames: usize, channels: usize) -> Self {
        let capacity = capacity_frames.max(64).next_power_of_two();
        Self {
            buf: vec![0.0; capacity * channels],
            capacity,
            mask: capacity - 1,
            head: 0,
            tail: 0,
        }
    }

    fn occupancy(&self) -> usize {
        (self.head - self.tail) as usize
    }

    /// Commit whole interleaved frames; returns the SAMPLE count consumed
    /// (frame-aligned, short when the ring is full).
    fn write(&mut self, samples: &[f32], channels: usize) -> usize {
        let whole = samples.len() - samples.len() % channels;
        if whole == 0 {
            return 0;
        }
        let mut consumed = 0usize;
        while consumed < whole {
            let frames_left = (whole - consumed) / channels;
            let free = self.capacity - self.occupancy();
            if free == 0 {
                break;
            }
            let chunk_frames = frames_left.min(free);
            let start = (self.head as usize & self.mask) * channels;
            let end = start + chunk_frames * channels;
            let src = &samples[consumed..consumed + chunk_frames * channels];
            if end <= self.buf.len() {
                self.buf[start..end].copy_from_slice(src);
            } else {
                // Two-segment commit across the wraparound.
                let first = self.buf.len() - start;
                let (a, b) = src.split_at(first);
                self.buf[start..].copy_from_slice(a);
                self.buf[..b.len()].copy_from_slice(b);
            }
            self.head += chunk_frames as u64;
            consumed += chunk_frames * channels;
        }
        consumed
    }

    /// Pull up to `dst_frames` frames; returns the frame count read.
    fn read(&mut self, dst: &mut [f32], channels: usize) -> usize {
        let want = dst.len() / channels;
        let got = want.min(self.occupancy());
        if got == 0 {
            return 0;
        }
        let start = (self.tail as usize & self.mask) * channels;
        let end = start + got * channels;
        if end <= self.buf.len() {
            dst[..got * channels].copy_from_slice(&self.buf[start..end]);
        } else {
            // Two-segment read across the wraparound.
            let first = self.buf.len() - start;
            let (dst_a, dst_b) = dst[..got * channels].split_at_mut(first);
            dst_a.copy_from_slice(&self.buf[start..]);
            dst_b.copy_from_slice(&self.buf[..dst_b.len()]);
        }
        self.tail += got as u64;
        got
    }
}

// ---------------------------------------------------------------------------
// The VirtualHAL sink
// ---------------------------------------------------------------------------

/// The deterministic adversarial audio test double (MP-HAL01 / MP-HAL02).
///
/// Producer writes and simulated hardware pulls share one cooperatively
/// scheduled in-memory ring; the clock domain models the CoreAudio
/// `AudioTimeStamp` contract (consistent `(mSampleTime, mHostTime)` pairs)
/// under scripted adversarial conditions. See the module docs for the
/// full model.
#[derive(Debug)]
pub struct VirtualHalSink {
    // ---- configuration ----------------------------------------------------
    channels: usize,
    callback_frames: usize,
    capacity_frames: usize,
    modes: AdversarialModes,
    jitter_fraction: f64,
    timestamp_quantum_ns: u64,
    drift_ppm: i32,

    // ---- simulated DAC clock state (per epoch) ------------------------------
    epoch: u32,
    rate: u32,
    rate_true: f64,
    epoch_origin_ns: u64,
    cursor_frames: u64,
    last_clock: DacPresentationClock,
    rng: SplitMix64,

    // ---- the data plane ------------------------------------------------------
    ring: SimRing,
    scratch: Vec<f32>,

    // ---- cumulative cross-epoch telemetry ------------------------------------
    frames_written: u64,
    frames_pulled: u64,
    frames_silenced: u64,
    starved_callbacks: u64,
    overrun_events: u64,
    frames_flushed: u64,
    callbacks_run: u64,
}

impl VirtualHalSink {
    /// Build the double for one stream geometry. Fails with
    /// `EngineError::Audio { FormatUnsupported }` on out-of-envelope
    /// configuration (mirrors the native `AudioSink` bounds plus the
    /// canonical HAL quantum rule).
    pub fn new(config: VirtualHalConfig) -> Result<Self, EngineError> {
        let bad = || EngineError::Audio {
            kind: AudioErrorKind::FormatUnsupported,
            device_id: None,
        };
        if !(8_000..=192_000).contains(&config.sample_rate) {
            return Err(bad());
        }
        if !matches!(config.channels, 1 | 2) {
            return Err(bad());
        }
        if config.capacity_frames < 64 || config.capacity_frames > (1usize << 22) {
            return Err(bad());
        }
        if !CALLBACK_CHUNK_SIZES.contains(&config.callback_frames) {
            return Err(bad());
        }
        if !(0.0..=1.0).contains(&config.jitter_fraction)
            || !config.jitter_fraction.is_finite()
        {
            return Err(bad());
        }
        if !(-2000..=2000).contains(&config.drift_ppm) {
            return Err(bad());
        }
        if config.timestamp_quantum_ns > 100_000_000 {
            return Err(bad());
        }

        let channels = config.channels as usize;
        let rate_true = f64::from(config.sample_rate)
            * (1.0 + f64::from(config.drift_ppm) * 1e-6);
        Ok(Self {
            channels,
            callback_frames: config.callback_frames,
            capacity_frames: config.capacity_frames.max(64).next_power_of_two(),
            modes: config.modes,
            jitter_fraction: config.jitter_fraction,
            timestamp_quantum_ns: config.timestamp_quantum_ns,
            drift_ppm: config.drift_ppm,
            epoch: 0,
            rate: config.sample_rate,
            rate_true,
            epoch_origin_ns: 0,
            cursor_frames: 0,
            last_clock: DacPresentationClock {
                dac_frame_position: 0,
                sample_rate: config.sample_rate,
                monotonic_ns: 0,
            },
            rng: SplitMix64::new(config.rng_seed),
            ring: SimRing::new(config.capacity_frames, channels),
            scratch: vec![0.0; config.callback_frames * channels],
            frames_written: 0,
            frames_pulled: 0,
            frames_silenced: 0,
            starved_callbacks: 0,
            overrun_events: 0,
            frames_flushed: 0,
            callbacks_run: 0,
        })
    }

    // ---- producer side -----------------------------------------------------

    /// Write interleaved `f32` samples (the "app" under test). Returns the
    /// SAMPLE count consumed — whole frames only; a short return means the
    /// ring is full (one overrun event is counted per short write).
    pub fn write_producer_frames(&mut self, samples: &[f32]) -> usize {
        let consumed = self.ring.write(samples, self.channels);
        if consumed < samples.len() - samples.len() % self.channels {
            self.overrun_events += 1; // producer flooded the ring
        }
        self.frames_written += (consumed / self.channels) as u64;
        consumed
    }

    // ---- simulated hardware pull callback ------------------------------------

    /// Render ONE quantum into the caller-provided interleaved buffer (the
    /// HAL supplies the destination, like an `AURenderCallback` ioData).
    /// `dst.len()` must be a whole number of frames; the frame count IS
    /// the quantum. Starved tails are silence-filled and the clock
    /// advances through them (position never stalls on starvation).
    pub fn run_callback_into(&mut self, dst: &mut [f32]) -> VirtualHalCallback {
        let quantum = dst.len() / self.channels;
        let first_frame = self.cursor_frames;
        let got = if quantum > 0 {
            let got = self.ring.read(dst, self.channels);
            if got < quantum {
                // Digital silence for the starved tail.
                for s in &mut dst[got * self.channels..quantum * self.channels] {
                    *s = 0.0;
                }
            }
            got
        } else {
            0
        };

        // The CONSISTENT reported pair: (first frame of the quantum, the
        // monotonic instant it presents), quantized onto the HAL grid.
        let t_true = self.time_at_frame_ns(first_frame);
        let t_report = self.quantize_timestamp(t_true);
        let fire = self.fire_time_for(first_frame, quantum.max(1));

        let clock = DacPresentationClock {
            dac_frame_position: first_frame,
            sample_rate: self.rate,
            monotonic_ns: t_report,
        };
        self.last_clock = clock;
        self.cursor_frames += quantum as u64;
        self.frames_pulled += got as u64;
        if got < quantum {
            self.starved_callbacks += 1;
            self.frames_silenced += (quantum - got) as u64;
        }
        self.callbacks_run += 1;
        VirtualHalCallback {
            frames_requested: quantum,
            frames_supplied: got,
            starved: got < quantum,
            clock,
            fire_time_ns: fire,
            epoch: self.epoch,
        }
    }

    /// Convenience overload rendering into the internal (init-allocated)
    /// scratch: pure clock/stress driving where the audio is discarded.
    /// Zero per-callback allocation (the scratch is reused).
    pub fn run_callback(&mut self) -> VirtualHalCallback {
        let mut scratch = std::mem::take(&mut self.scratch);
        let report = self.run_callback_into(&mut scratch);
        self.scratch = scratch;
        report
    }

    // ---- mid-stream rate reconfiguration (master plan invariant 6) -----------

    /// Teardown-and-rebuild: flush the ring, restart the frame epoch at
    /// the current presentation instant and move the clock domain to the
    /// new rate. Returns false (no-op) for out-of-envelope rates.
    pub fn reconfigure_rate(&mut self, new_rate: u32) -> bool {
        if !(8_000..=192_000).contains(&new_rate) {
            return false;
        }
        let now_ns = self.sim_time_ns();
        // Teardown half: the buffered stream is accounted as flushed.
        self.frames_flushed += self.ring.occupancy() as u64;
        self.ring = SimRing::new(self.capacity_frames, self.channels);
        // Rebuild half: fresh epoch, fresh clock domain, timeline forward.
        self.epoch += 1;
        self.rate = new_rate;
        self.rate_true =
            f64::from(new_rate) * (1.0 + f64::from(self.drift_ppm) * 1e-6);
        self.epoch_origin_ns = now_ns;
        self.cursor_frames = 0;
        true
    }

    // ---- introspection --------------------------------------------------------

    /// Live telemetry snapshot.
    pub fn stats(&self) -> VirtualHalStats {
        VirtualHalStats {
            frames_written: self.frames_written,
            frames_pulled: self.frames_pulled,
            frames_silenced: self.frames_silenced,
            starved_callbacks: self.starved_callbacks,
            overrun_events: self.overrun_events,
            frames_flushed: self.frames_flushed,
            callbacks_run: self.callbacks_run,
            epoch: self.epoch,
            occupancy_frames: self.ring.occupancy(),
            capacity_frames: self.ring.capacity,
        }
    }

    /// Newest reported clock sample.
    pub fn last_clock(&self) -> DacPresentationClock {
        self.last_clock
    }

    /// Device/epoch rate (Hz).
    pub fn sample_rate(&self) -> u32 {
        self.rate
    }

    /// Channel geometry.
    pub fn channels(&self) -> u8 {
        self.channels as u8
    }

    /// Configured callback quantum.
    pub fn callback_frames(&self) -> usize {
        self.callback_frames
    }

    /// Simulated monotonic instant of the CURRENT presentation cursor
    /// (the ground-truth "now" of the DAC timeline).
    pub fn sim_time_ns(&self) -> u64 {
        self.time_at_frame_ns(self.cursor_frames)
    }

    /// Ground-truth presented frame count at an arbitrary monotonic
    /// instant (clamped to the epoch: pre-origin queries return 0).
    pub fn true_position_frames_at(&self, now_ns: u64) -> f64 {
        if now_ns <= self.epoch_origin_ns {
            return 0.0;
        }
        (now_ns - self.epoch_origin_ns) as f64 * self.rate_true / 1e9
    }

    // ---- self-driving adversarial scenario (MP-HAL02) -------------------------

    /// Run a deterministic producer/consumer schedule exercising every
    /// enabled adversarial mode, feeding a deterministic ramp signal, and
    /// validating a [`DacClockInterpolator`] against the ground-truth
    /// cursor at `profile.queries_per_callback` deterministic instants
    /// per quantum. The report carries the worst observed errors — the
    /// suite's acceptance gate is
    /// `max_interpolation_error_ms < INTERPOLATION_ACCEPTANCE_MS`.
    pub fn run_adversarial_scenario(
        &mut self,
        profile: &AdversarialProfile,
    ) -> ScenarioReport {
        let mut report = ScenarioReport::default();
        let quantum = self.callback_frames;
        let target_occupancy = profile.feed_ahead_quanta * quantum;

        // Producer feed buffer: worst of (top-up block, flood attempt).
        let feed_frames = target_occupancy.max(self.capacity_frames + quantum);
        let mut feed = vec![0.0f32; feed_frames * self.channels];
        let mut fed_frame_cursor: u64 = 0;

        // Rate-change schedule: evenly spaced, cycling the non-canonical
        // hardware rates back to canonical.
        const RATE_TABLE: [u32; 3] = [44_100, 96_000, 48_000];
        let mut rate_idx = 0usize;
        let mut rate_changes_done = 0usize;
        let rate_stride = if profile.rate_changes > 0 {
            profile.callbacks / (profile.rate_changes + 1)
        } else {
            0
        };

        let mut interp = DacClockInterpolator::default();
        let mut fire_interp = DacClockInterpolator::default();

        for k in 0..profile.callbacks {
            // ---- scheduled mid-stream rate reconfiguration ------------------
            if profile.rate_changes > 0
                && rate_stride > 0
                && rate_changes_done < profile.rate_changes
                && k > 0
                && k % rate_stride == 0
                && self.reconfigure_rate(RATE_TABLE[rate_idx % 3])
            {
                rate_idx += 1;
                rate_changes_done += 1;
            }

            // ---- scripted producer behaviour --------------------------------
            let starving = profile.starve_every > profile.starve_burst
                && (k % profile.starve_every)
                    >= (profile.starve_every - profile.starve_burst);
            let flooding =
                profile.flood_every > 0 && k > 0 && k % profile.flood_every == 0;

            if !starving {
                // Nominal feeding: top the ring up to the target occupancy.
                let occ = self.ring.occupancy();
                if occ < target_occupancy {
                    let want = (target_occupancy - occ).min(feed_frames);
                    self.fill_ramp(&mut feed, fed_frame_cursor, want);
                    let consumed =
                        self.write_producer_frames(&feed[..want * self.channels]);
                    fed_frame_cursor += (consumed / self.channels) as u64;
                }
            }
            if flooding {
                // Overrun injection: strictly more than the ring can hold.
                let attempt = self.capacity_frames + quantum;
                self.fill_ramp(&mut feed, fed_frame_cursor, attempt);
                let consumed =
                    self.write_producer_frames(&feed[..attempt * self.channels]);
                fed_frame_cursor += (consumed / self.channels) as u64;
            }

            // ---- one simulated hardware callback -----------------------------
            let cb = self.run_callback();
            interp.update(&cb.clock);
            // Negative control: the naive estimator pairing the frame
            // counter with the FIRE time instead of the presentation time.
            fire_interp.update(&DacPresentationClock {
                dac_frame_position: cb.clock.dac_frame_position,
                sample_rate: cb.clock.sample_rate,
                monotonic_ns: cb.fire_time_ns,
            });
            if cb.starved {
                report.starved_callbacks += 1;
            }

            // ---- interpolation acceptance checks ------------------------------
            // Deterministic query instants spread across the quantum and
            // just past its end (the staleness window a real reader hits).
            let quantum_ns =
                (quantum as f64 * 1e9 / self.rate_true) as u64;
            for q in 1..=profile.queries_per_callback {
                let now = cb.clock.monotonic_ns
                    + quantum_ns * q as u64 / profile.queries_per_callback as u64;
                if let Some(est) = interp.position_frames_at(now) {
                    let truth = self.true_position_frames_at(now);
                    let err_ms =
                        (est as f64 - truth).abs() * 1000.0 / self.rate_true;
                    report.max_interpolation_error_ms =
                        report.max_interpolation_error_ms.max(err_ms);
                }
                if let Some(fest) = fire_interp.position_frames_at(now) {
                    let truth = self.true_position_frames_at(now);
                    let err_ms =
                        (fest as f64 - truth).abs() * 1000.0 / self.rate_true;
                    report.max_firetime_error_ms =
                        report.max_firetime_error_ms.max(err_ms);
                }
            }
        }

        report.callbacks_run = profile.callbacks as u64;
        report.rate_changes = rate_changes_done;
        report.overrun_events = self.overrun_events;
        report.frames_written = self.frames_written;
        report.frames_pulled = self.frames_pulled;

        // ---- frame conservation across the whole run (incl. rebuilds) ----
        let st = self.stats();
        report.conservation_ok =
            self.frames_written == self.frames_pulled + st.occupancy_frames as u64
                + self.frames_flushed;
        report
    }

    // ---- internals ----------------------------------------------------------

    /// Deterministic ramp filler (phase continuity across calls).
    fn fill_ramp(&self, feed: &mut [f32], first_frame: u64, frames: usize) {
        for f in 0..frames {
            let v = (((first_frame + f as u64) % 4096) as f64 / 4095.0 * 2.0
                - 1.0) as f32;
            for c in 0..self.channels {
                feed[f * self.channels + c] = v;
            }
        }
    }

    /// Epoch presentation instant of `frame` (f64: mul/div only —
    /// bit-deterministic; no transcendentals on the clock path).
    fn time_at_frame_ns(&self, frame: u64) -> u64 {
        (self.epoch_origin_ns as f64 + frame as f64 * 1e9 / self.rate_true) as u64
    }

    /// Pull HALs fire roughly one full buffer period ahead of the
    /// deadline. `jitter_fraction` is the PEAK callback-INTERVAL variance:
    /// the lead varies by ±(jitter_fraction / 2) × period so consecutive
    /// fire intervals differ by up to ±jitter_fraction × period.
    fn fire_time_for(&mut self, first_frame: u64, quantum: usize) -> u64 {
        let mut lead_ns = quantum as f64 * 1e9 / self.rate_true;
        if self.modes.has(AdversarialModes::TIMING_JITTER) {
            lead_ns *= 1.0 + 0.5 * self.jitter_fraction * self.rng.next_symmetric();
        }
        let deadline =
            self.epoch_origin_ns as f64 + first_frame as f64 * 1e9 / self.rate_true;
        ((deadline - lead_ns).max(0.0)) as u64
    }

    /// Floor `t` onto the reported-timestamp grid.
    fn quantize_timestamp(&self, t: u64) -> u64 {
        let q = self.timestamp_quantum_ns;
        // A zero grid is the exact (passthrough) spelling; checked_div
        // keeps the zero case branch-free at the call site.
        t.checked_div(q).map_or(t, |floored| floored * q)
    }
}

impl AudioHalConsumer for VirtualHalSink {
    fn hal_pull(&mut self, dst: &mut [f32]) -> HalPullReport {
        self.run_callback_into(dst).as_report()
    }
}

// ---------------------------------------------------------------------------
// Scenario profile & report
// ---------------------------------------------------------------------------

/// Self-driving adversarial schedule (see
/// [`VirtualHalSink::run_adversarial_scenario`]).
#[derive(Debug, Clone, Copy)]
pub struct AdversarialProfile {
    /// Total simulated callbacks.
    pub callbacks: usize,
    /// 0 = off; a starve burst starts every N callbacks.
    pub starve_every: usize,
    /// Consecutive unfed callbacks per burst (must exceed the feed depth
    /// to actually starve the consumer).
    pub starve_burst: usize,
    /// 0 = off; a >capacity flood write every N callbacks.
    pub flood_every: usize,
    /// Number of rate reconfigurations (evenly spaced).
    pub rate_changes: usize,
    /// Interpolation checks per quantum (deterministic offsets).
    pub queries_per_callback: usize,
    /// Nominal ring target (quanta) when not starving.
    pub feed_ahead_quanta: usize,
}

impl Default for AdversarialProfile {
    fn default() -> Self {
        Self {
            callbacks: 600,
            starve_every: 0,
            starve_burst: 3,
            flood_every: 0,
            rate_changes: 0,
            queries_per_callback: 4,
            feed_ahead_quanta: 4,
        }
    }
}

/// Result of one adversarial scenario run.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScenarioReport {
    /// Callbacks actually simulated.
    pub callbacks_run: u64,
    /// Starved callbacks (underruns injected and observed).
    pub starved_callbacks: u64,
    /// Overrun events (flood attempts + any short writes).
    pub overrun_events: u64,
    /// Rate reconfigurations executed.
    pub rate_changes: usize,
    /// Producer frames accepted across all epochs.
    pub frames_written: u64,
    /// Real frames pulled across all epochs.
    pub frames_pulled: u64,
    /// Worst consistent-pair interpolation error (ms) — the acceptance
    /// metric (< [`INTERPOLATION_ACCEPTANCE_MS`]).
    pub max_interpolation_error_ms: f64,
    /// Worst naive fire-time error (ms) — the negative control (> 5 ms
    /// proves the acceptance bound has teeth).
    pub max_firetime_error_ms: f64,
    /// `written == pulled + live occupancy + flushed` across all epochs.
    pub conservation_ok: bool,
}

impl Default for ScenarioReport {
    fn default() -> Self {
        Self {
            callbacks_run: 0,
            starved_callbacks: 0,
            overrun_events: 0,
            rate_changes: 0,
            frames_written: 0,
            frames_pulled: 0,
            max_interpolation_error_ms: 0.0,
            max_firetime_error_ms: 0.0,
            conservation_ok: false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splitmix64_is_deterministic_and_symmetric_range() {
        let mut a = SplitMix64::new(42);
        let mut b = SplitMix64::new(42);
        for _ in 0..1000 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
        let mut rng = SplitMix64::new(7);
        for _ in 0..10_000 {
            let v = rng.next_symmetric();
            assert!((-1.0..1.0).contains(&v), "symmetric range: {v}");
        }
    }

    #[test]
    fn ring_roundtrip_and_wraparound() {
        let mut ring = SimRing::new(256, 2);
        let data: Vec<f32> = (0..600 * 2).map(|i| i as f32 * 0.5).collect();
        let mut consumed_total = 0usize;
        let mut produced = Vec::new();
        let mut off = 0usize;
        while off < data.len() {
            let n = ring.write(&data[off..], 2);
            if n == 0 {
                let mut dst = vec![0.0f32; 128 * 2];
                let got = ring.read(&mut dst, 2);
                assert!(got > 0);
                produced.extend_from_slice(&dst[..got * 2]);
                continue;
            }
            off += n;
            consumed_total += n;
        }
        // Drain the remainder.
        loop {
            let mut dst = vec![0.0f32; 128 * 2];
            let got = ring.read(&mut dst, 2);
            if got == 0 {
                break;
            }
            produced.extend_from_slice(&dst[..got * 2]);
        }
        assert_eq!(consumed_total, 1200);
        assert_eq!(produced.len(), 1200);
        assert_eq!(produced, data, "wraparound roundtrip must be exact");
        assert_eq!(ring.occupancy(), 0);
    }

    #[test]
    fn interpolator_semantics() {
        let mut interp = DacClockInterpolator::default();
        assert!(interp.position_frames_at(1000).is_none());
        interp.update(&DacPresentationClock {
            dac_frame_position: 10_000,
            sample_rate: 48_000,
            monotonic_ns: 2_000_000_000,
        });
        // 1 ms later at 48 kHz = 48 frames.
        assert_eq!(
            interp.position_frames_at(2_000_000_000 + 1_000_000),
            Some(10_048)
        );
        assert_eq!(
            interp.position_ms_at(2_000_000_000 + 1_000_000),
            Some(10_048.0 * 1000.0 / 48_000.0)
        );
        // Advancing sample re-baselines.
        interp.update(&DacPresentationClock {
            dac_frame_position: 10_048,
            sample_rate: 48_000,
            monotonic_ns: 2_000_000_000 + 1_000_000,
        });
        assert_eq!(
            interp.position_frames_at(2_000_000_000 + 1_000_000),
            Some(10_048)
        );
        // Epoch reset: frame regression + rate change drops the baseline.
        interp.update(&DacPresentationClock {
            dac_frame_position: 0,
            sample_rate: 44_100,
            monotonic_ns: 2_000_000_000 + 2_000_000,
        });
        assert_eq!(interp.rate(), 44_100);
        // 1 ms at 44.1 kHz = 44.1 frames -> 44.
        assert_eq!(
            interp.position_frames_at(2_000_000_000 + 3_000_000),
            Some(44)
        );
    }
}
