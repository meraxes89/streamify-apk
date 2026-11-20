//! The Windows WASAPI-EXCLUSIVE bracket (Phase 1 D1, feature
//! `wasapi_exclusive`, x86_64-pc-windows-msvc only).
//!
//! cpal supports WASAPI **shared** mode only. Consumer DACs that need
//! exclusive 48 kHz access (bit-perfect chains) get this miniaudio
//! bracket instead; the closed-failure contract: any rejection (format
//! refused, device busy, driver error) returns `Err` and the engine
//! opens cpal shared mode WITHOUT interrupting playback (the engine's
//! step-down arm + the `SteppedDownToShared` event).
//!
//! # RT discipline
//!
//! The miniaudio data callback runs the SAME disciplined path as the
//! cpal callback: the frozen ring read, the underrun zero-fill and the
//! atomic clock publish — nothing else (no allocation, no locks, no
//! syscalls). The callback is `Clone`-bounded by miniaudio (each device
//! clones its own), so the shared state rides an `Arc`.

#![cfg(all(feature = "wasapi_exclusive", target_os = "windows", target_arch = "x86_64"))]

use std::sync::Arc;

use crate::dac_clock::{host_dac_clock, HostClockAnchor};
use crate::{DacClockSlot, StopFlag, CANONICAL_CHANNELS, CANONICAL_RATE_HZ};
use streamify_playback::ring_export;

/// The exclusive bracket's RT state (the mirror of the engine's
/// cpal-side state; the callback owns a clone of the `Arc`).
pub(crate) struct ExclusiveRt {
    clock: Arc<DacClockSlot>,
    anchor: HostClockAnchor,
    underrun_frames: std::sync::atomic::AtomicU64,
    stop: StopFlag,
    /// Monotonic frames SERVED on this device stream (the clock master
    /// counter — miniaudio hands no callback timestamp, so the callback
    /// itself accumulates the presented-frame count atomically).
    frames_presented: std::sync::atomic::AtomicU64,
}

impl ExclusiveRt {
    pub(crate) fn new(clock: Arc<DacClockSlot>, anchor: HostClockAnchor) -> Self {
        Self {
            clock,
            anchor,
            underrun_frames: std::sync::atomic::AtomicU64::new(0),
            stop: StopFlag::default(),
            frames_presented: std::sync::atomic::AtomicU64::new(0),
        }
    }
}

/// The guard keeps the miniaudio device alive for the stream's lifetime
/// (dropping it stops and releases the exclusive endpoint).
pub struct ExclusiveGuard {
    device: miniaudio::Device,
}

impl ExclusiveGuard {
    /// Stop the exclusive device (idempotent).
    pub fn stop(&self) {
        let _ = self.device.stop();
    }
}

/// Open the exclusive bracket: DEFAULT output device, 48 kHz stereo
/// f32, `ShareMode::Exclusive`. `Err(reason)` triggers the documented
/// step-down to cpal shared (the caller reports
/// `EngineEvent::SteppedDownToShared`).
pub(crate) fn open_exclusive(
    rt: &Arc<ExclusiveRt>,
) -> Result<ExclusiveGuard, String> {
    let mut config = miniaudio::DeviceConfig::new(miniaudio::DeviceType::Playback);
    config.playback_mut().set_format(miniaudio::Format::F32);
    config.playback_mut().set_channels(u32::from(CANONICAL_CHANNELS));
    config.playback_mut().set_share_mode(miniaudio::ShareMode::Exclusive);
    config.set_sample_rate(i32::from(CANONICAL_RATE_HZ));

    // The disciplined RT callback (clone-bounded; the Arc carries the
    // shared state into every clone).
    let rt_cb = Arc::clone(rt);
    config.set_data_callback(move |_device, output: &mut miniaudio::FramesMut<'_>, _input| {
        // ── THE RT PATH (zero alloc / lock / syscall) ──
        let data: &mut [f32] = output;
        if data.is_empty() {
            return;
        }
        if rt_cb.stop.get() {
            data.fill(0.0);
            return;
        }
        let frames = data.len() / CANONICAL_CHANNELS as usize;
        // SAFETY: `data` is the callback's live output buffer, valid for
        // `frames × channels` f32 writes for the duration of the call;
        // single consumer thread by miniaudio construction.
        let served = unsafe { ring_export::streamify_ring_read_f32(data.as_mut_ptr(), frames) };
        let served_samples = served * CANONICAL_CHANNELS as usize;
        if served_samples < data.len() {
            rt_cb.underrun_frames.fetch_add(
                ((data.len() - served_samples) / 2) as u64,
                std::sync::atomic::Ordering::Relaxed,
            );
            for s in data[served_samples..].iter_mut() {
                *s = 0.0;
            }
        }
        // Clock publish: frames this stream has PRESENTED (miniaudio
        // gives no timestamp — the counter is the clock master).
        let presented = rt_cb
            .frames_presented
            .fetch_add(frames as u64, std::sync::atomic::Ordering::Relaxed)
            + frames as u64;
        let sample = host_dac_clock(&rt_cb.anchor, presented, CANONICAL_RATE_HZ);
        rt_cb.clock.publish(&sample);
    });

    // Device::new(None, &config) — the implicit context; any failure
    // here (exclusive refused by the DAC/driver, busy endpoint) fails
    // CLOSED into the Err arm = the step-down contract.
    let device = miniaudio::Device::new(None, &config)
        .map_err(|e| format!("miniaudio WASAPI-exclusive open failed: {e}"))?;
    device
        .start()
        .map_err(|e| format!("miniaudio WASAPI-exclusive start failed: {e}"))?;
    Ok(ExclusiveGuard { device })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rt_state_constructs_clean() {
        let rt = ExclusiveRt::new(
            Arc::new(DacClockSlot::default()),
            HostClockAnchor::capture(),
        );
        assert_eq!(
            rt.underrun_frames.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
        assert_eq!(
            rt.frames_presented.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
    }
}
