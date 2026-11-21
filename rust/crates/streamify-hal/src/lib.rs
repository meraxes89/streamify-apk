//! Sovereign HAL Phase 1 (D1 + D2): the unified `cpal` cross-platform
//! output engine and the hardware DAC presentation-clock shims.
//!
//! # What this crate replaces (and what it does NOT touch)
//!
//! The Phase 0 hand-rolled desktop driver *plans* (a `wasapi_pull_adapter`,
//! a `pipewire_driver`, a `CoreAudioRenderCore`) collapse into ONE Rust
//! output engine on `cpal 0.18` — WASAPI (shared) on Windows, CoreAudio on
//! macOS, ALSA-and-its-sound-server-bridges on Linux. The engine drains
//! the process-current SPSC ring through the SAME frozen C edge the
//! Phase 0 brackets used ([`ring_export`]'s
//! `streamify_ring_read_f32`) so the ring/JNI ABIs stay byte-frozen.
//!
//! # The RT discipline (non-negotiable)
//!
//! The cpal output closure is **lock-free, wait-free and zero-allocation**:
//! its only operations are the frozen ring read, the underrun zero-fill,
//! and an atomic clock-slot store. Everything that CAN block (device
//! enumeration, stream (re)build, error handling) lives on the OWNER side.
//!
//! # DAC presentation clocks (D2)
//!
//! cpal's `OutputCallbackInfo::timestamp()` is host-dependent and NOT
//! guaranteed to reflect hardware DAC presentation. The callback therefore
//! ALSO records the host-clock sample (WASAPI `IAudioClock`, CoreAudio
//! `AudioTimeStamp`, ALSA/PipeWire delay-derived) via the platform shim in
//! [`dac_clock`] when one is available, falling back to cpal's callback
//! timestamp otherwise. Both feed [`record_dac_clock`]'s slot; the
//! acceptance target stays ±100 ppm drift / <5 ms sync (Phase 4a's
//! loopback reference).
//!
//! # Linux sound-server hygiene
//!
//! [`OutputEngine::start`] prefers PipeWire/Pulse-pipe device names over
//! the raw ALSA default when a sound server is likely holding the card
//! (the `pipeironly`/`pulse` host devices appear in cpal's ALSA host
//! enumeration), and always requests deterministic
//! `BufferSize::Fixed(1024)` — `Default` can inflate latency on PipeWire.
//!
//! # The WASAPI-exclusive bracket (Windows x86_64, feature-gated)
//!
//! cpal is WASAPI-shared-only. The `wasapi_exclusive` feature adds a
//! miniaudio-backed exclusive bracket; a consumer DAC rejecting 48 kHz
//! exclusive mode steps down to cpal shared mode without interrupting
//! playback (see [`exclusive`]).

#![deny(unsafe_op_in_unsafe_fn)]

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;

use streamify_core::DacPresentationClock;
use streamify_playback::ring_export;

pub mod clock;
pub mod dac_clock;
pub mod device;
pub mod engine;
#[cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]
pub mod exclusive;

pub use clock::{validate_drift_window, ClockSample};
pub use dac_clock::host_dac_clock;
pub use device::{enumerate_outputs, host_id, OutputDevice, OutputRoute, prefer_sound_server};
pub use engine::{EngineEvent, EngineEvents, OutputEngine, RouteOption};

/// The canonical output geometry (the ring's contract): 48 kHz f32
/// interleaved stereo.
pub const CANONICAL_RATE_HZ: u32 = 48_000;
pub const CANONICAL_CHANNELS: u16 = 2;

/// Deterministic per-callback buffer size in FRAMES (the PipeWire latency
/// hygiene: `BufferSize::Default` can inflate latency on PipeWire;
/// 1024 frames ≈ 21.3 ms at 48 kHz).
pub const CALLBACK_FRAMES: u32 = 1024;

/// The RT-side DAC clock slot: written by the audio callback (and by the
/// host-clock shim), read by telemetry. One [`Arc`], atomically updated —
/// the callback NEVER allocates to publish a sample.
#[derive(Debug, Default)]
pub struct DacClockSlot {
    /// Latest presentation sample's DAC frame position.
    frames: AtomicU64,
    /// Latest sample's device rate in Hz (0 until the first sample).
    rate_hz: AtomicU64,
    /// Latest sample's OS monotonic timestamp in ns.
    monotonic_ns: AtomicU64,
    /// Sequence stamp: bumped on every publish (change detection).
    seq: AtomicU64,
}

impl DacClockSlot {
    /// Publish one sample (RT-safe: three relaxed atomic stores + one
    /// release store; no allocation, no locks).
    #[inline]
    pub fn publish(&self, sample: &DacPresentationClock) {
        self.frames.store(sample.dac_frame_position, Ordering::Relaxed);
        self.rate_hz.store(u64::from(sample.sample_rate), Ordering::Relaxed);
        self.monotonic_ns.store(sample.monotonic_ns, Ordering::Relaxed);
        self.seq.fetch_add(1, Ordering::Release);
    }

    /// Read the latest sample and its sequence stamp. `None` before the
    /// first publish.
    pub fn sample(&self) -> Option<(DacPresentationClock, u64)> {
        let seq = self.seq.load(Ordering::Acquire);
        if seq == 0 {
            return None;
        }
        let rate = self.rate_hz.load(Ordering::Relaxed);
        Some((
            DacPresentationClock {
                dac_frame_position: self.frames.load(Ordering::Relaxed),
                sample_rate: rate as u32,
                monotonic_ns: self.monotonic_ns.load(Ordering::Relaxed),
            },
            seq,
        ))
    }
}

/// The RT-side stop flag shared with the callback (owner sets, callback
/// reads; `cpal` itself owns the stream lifetime — this flag only tells
/// OUR callback to zero-fill while the owner drains). Public: the
/// zero-alloc CI guard drives the exact callback sequence, stop flag
/// included.
#[derive(Debug, Default)]
pub struct StopFlag(AtomicBool);

impl StopFlag {
    #[inline]
    pub fn set(&self) {
        self.0.store(true, Ordering::Release);
    }
    #[inline]
    pub fn get(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
    /// Arm the flag for a fresh stream (the engine's `start` after a
    /// `stop_stream`: the previous stop must not poison the new stream).
    #[inline]
    pub fn clear(&self) {
        self.0.store(false, Ordering::Release);
    }
}

/// Verify the frozen ring edge is installed (the engine's precondition).
pub fn ring_installed() -> bool {
    // The stats wire tells us whether a ring is installed without
    // touching the read edge (which is consumer-owned): the frozen C
    // reader returns 1 only when a ring is attached.
    let mut wire = [0u8; streamify_playback::SINK_STATS_WIRE_BYTES];
    // SAFETY: `wire` is a valid, sufficiently sized stack buffer for the
    // duration of the call; the slice view never escapes the callee.
    unsafe { ring_export::streamify_ring_stats_wire(wire.as_mut_ptr(), wire.len()) == 1 }
}
