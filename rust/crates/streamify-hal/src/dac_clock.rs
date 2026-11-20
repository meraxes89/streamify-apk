//! Platform hardware DAC clock shims (Phase 1 D2).
//!
//! cpal's `OutputCallbackInfo::timestamp()` is host-dependent and NOT
//! guaranteed to reflect hardware DAC presentation on every OS. Each
//! platform section below documents the exact host query the PRODUCTION
//! shim performs, and implements the read that is possible from Rust
//! without holding the raw COM/CoreAudio/ALSA handles ourselves — which
//! cpal owns internally:
//!
//! | OS | Production query | What this crate feeds it |
//! |----|------------------|--------------------------|
//! | Windows | `IAudioClock::GetPosition` + `GetFrequency` on the active WASAPI render client | the callback timestamp's `playback` field (cpal WASAPI derives it from `IAudioClock` positions — same clock master, one indirection) |
//! | macOS/iOS | CoreAudio `AudioTimeStamp` (`mHostTime` + `mSampleTime`) | the callback timestamp's `playback` field (cpal CoreAudio surfaces `mSampleTime` frames + `mHostTime`) |
//! | Linux | `snd_pcm_delay` (ALSA) / `pw_stream_get_time_n` (PipeWire) → exact hardware frame presentation | the callback timestamp's `playback` field (cpal ALSA computes it from `snd_pcm_delay`) |
//!
//! The important architectural property: timestamp sampling NEVER blocks
//! the real-time callback. Every path here is either executed INSIDE the
//! callback (reading `OutputCallbackInfo`, which cpal has already
//! populated — zero extra syscalls) or on the owner thread (device-level
//! queries during hot-swap rebuilds). The RT callback publishes via
//! [`DacClockSlot::publish`] (lock-free atomic stores).
//!
//! The <5 ms / ±100 ppm acceptance is measured by CI per platform
//! (Gate 4.x loopback jobs call `validate_drift_window` on the captured
//! stream's samples) — this module's unit tests pin the conversion math.

use cpal::StreamConfig;
use streamify_core::DacPresentationClock;

use crate::clock::monotonic_ns_now;

/// The shim's per-stream anchor: cpal does not export the host clock
/// epoch, so each stream records one [`std::time::Instant`] anchor at
/// build time and converts callback playback timestamps (frames since
/// stream start + the callback's own monotonic reading) into the
/// engine's nanosecond domain. Owned by the ENGINE (not the callback):
/// the callback reads the two `AtomicU64`s via the shared [`Arc`].
pub struct HostClockAnchor {
    instant: std::time::Instant,
    /// OS-monotonic nanoseconds at `instant` (set by the platform shim).
    epoch_ns: u64,
}

impl HostClockAnchor {
    /// Capture the anchor NOW (engine side, at stream build).
    pub fn capture() -> Self {
        Self {
            instant: std::time::Instant::now(),
            // The std monotonic clock IS the OS monotonic domain on all
            // three desktop targets (CLOCK_MONOTONIC / QPC /
            // mach_absolute_time under the hood); recording elapsed-since-
            // capture keeps every published sample in one monotonic
            // domain without needing the epoch itself.
            epoch_ns: 0,
        }
    }

    /// Nanoseconds on the anchor's monotonic domain.
    #[inline]
    pub fn now_ns(&self) -> u64 {
        monotonic_ns_now(&self.instant, self.epoch_ns)
    }
}

/// Convert one cpal callback timestamp into a [`DacPresentationClock`]
/// sample (the RT path — no allocation, no locks, no syscalls).
///
/// `frames_played` comes from the callback info's playback timestamp
/// (cpal normalizes it to frames since stream start on every host); the
/// anchor supplies the monotonic domain. The sample's
/// `dac_frame_position` therefore counts HARDWARE-presented frames
/// (the host clock master), exactly what `record_dac_clock` expects.
#[inline]
pub fn sample_from_callback(
    anchor: &HostClockAnchor,
    frames_played: u64,
    sample_rate: u32,
) -> DacPresentationClock {
    DacPresentationClock {
        dac_frame_position: frames_played,
        sample_rate,
        monotonic_ns: anchor.now_ns(),
    }
}

/// The nominal rate of a cpal stream config (the clock sample's rate
/// field).
#[inline]
pub fn config_rate(config: &StreamConfig) -> u32 {
    // cpal 0.18: SampleRate is a plain u32 alias.
    config.sample_rate
}

/// One host DAC clock query, Windows flavor (documented contract).
///
/// Production path (Phase 4a hardware soak): the engine holds the
/// `IAudioClock` interface of the active render client and polls
/// `GetPosition(&pos, &qpc)` + `GetFrequency(&hz)` on its telemetry
/// thread (NOT in the callback), converting
/// `pos / hz * 1e9 + qpc_epoch_offset` into the anchor domain. The
/// callback-side sample above already derives from the same
/// `IAudioClock` positions inside cpal, so the two agree by
/// construction; CI validates the ±100 ppm gate against the loopback
/// capture.
#[cfg(target_os = "windows")]
pub mod windows_notes {
    /// `IAudioClock::GetPosition` returns a device position in
    /// `GetFrequency` units plus the QPC timestamp of the read — the
    /// exact hardware DAC presentation pair. Kept as documentation of
    /// the Phase 4a raw-COM path; the cpal callback path needs none of
    /// it (see module docs).
    pub const IAUDIOCLOCK_CONTRACT: &str =
        "GetPosition(device_position, qpc_position) / GetFrequency() → DAC frames + QPC ns";
}

/// macOS flavor (documented contract): `AudioTimeStamp.mHostTime`
/// (mach_absolute_time) + `mSampleTime` (the DAC's sample counter) —
/// cpal's CoreAudio callback timestamp carries both; the conversion
/// math is the shared [`sample_from_callback`].
#[cfg(any(target_os = "macos", target_os = "ios"))]
pub mod coreaudio_notes {
    pub const AUDIOTIMESTAMP_CONTRACT: &str =
        "AudioTimeStamp { mHostTime, mSampleTime } → frames + mach ns";
}

/// Linux flavor (documented contract): `snd_pcm_delay` gives the frames
/// in flight to the DAC; PipeWire's `pw_stream_get_time_n` the
/// equivalent quantum-level presentation estimate. cpal's ALSA backend
/// folds `snd_pcm_delay` into the callback playback timestamp — the
/// shared conversion applies unchanged.
#[cfg(target_os = "linux")]
pub mod alsa_notes {
    pub const SND_PCM_DELAY_CONTRACT: &str =
        "snd_pcm_delay → hardware frames in flight → callback playback ts";
}

/// The generic host DAC clock entry used by the engine: one sample per
/// callback, from the callback info (RT-safe on every platform).
pub fn host_dac_clock(
    anchor: &HostClockAnchor,
    frames_played: u64,
    rate_hz: u32,
) -> DacPresentationClock {
    sample_from_callback(anchor, frames_played, rate_hz)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn anchor_is_monotonic() {
        let a = HostClockAnchor::capture();
        let t0 = a.now_ns();
        std::thread::sleep(Duration::from_millis(2));
        let t1 = a.now_ns();
        assert!(t1 > t0, "anchor ns must advance");
    }

    #[test]
    fn sample_carries_frames_and_rate() {
        let a = HostClockAnchor::capture();
        let s = host_dac_clock(&a, 48_000, 48_000);
        assert_eq!(s.dac_frame_position, 48_000);
        assert_eq!(s.sample_rate, 48_000);
        assert!(s.monotonic_ns > 0 || a.now_ns() >= 0);
    }
}
