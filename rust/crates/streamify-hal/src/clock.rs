//! The RT-side clock plumbing (Phase 1 D2): the callback's lock-free
//! snapshot path and the drift-window validator used by CI.
//!
//! The audio callback publishes [`DacPresentationClock`] samples into the
//! shared [`DacClockSlot`] (three relaxed stores + one release store —
//! zero allocation, zero locks). Telemetry and the CI ±100 ppm gate read
//! coherent sample pairs back out and validate drift against the DAC's
//! nominal rate.

use std::time::Instant;

use streamify_core::DacPresentationClock;

use crate::DacClockSlot;

/// One published clock sample plus the local receive-side reading (the
/// sample's `monotonic_ns` is the HOST clock the shim read — WASAPI
/// QPC / CoreAudio mach_absolute_time / ALSA CLOCK_MONOTONIC — which the
/// shims normalize to the same OS monotonic domain as [`Instant`] within
/// each platform's guarantees).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ClockSample {
    /// The DAC presentation sample.
    pub dac: DacPresentationClock,
    /// Monotonic sequence of this sample within the slot.
    pub seq: u64,
}

/// Validate a clock stream for the ±100 ppm acceptance gate: between the
/// first and last sample, the DAC frame delta divided by the nominal
/// rate must track the OS-monotonic delta within ±100 ppm AND <5 ms
/// absolute sync error at the final sample (the directive's dual bound).
///
/// Returns the measured drift in ppm (positive = DAC fast vs host clock)
/// and the final absolute sync error in nanoseconds, or `Err(why)` when
/// the window is too short / non-monotonic to judge (CI fails on both).
pub fn validate_drift_window(samples: &[DacPresentationClock]) -> Result<(f64, u64), String> {
    if samples.len() < 2 {
        return Err("drift window needs ≥2 samples".to_string());
    }
    let first = &samples[0];
    let last = &samples[samples.len() - 1];
    let df = last.dac_frame_position.saturating_sub(first.dac_frame_position);
    let dt = last.monotonic_ns.saturating_sub(first.monotonic_ns);
    if dt == 0 || df == 0 {
        return Err("degenerate window (zero elapsed frames or time)".to_string());
    }
    if last.dac_frame_position < first.dac_frame_position {
        return Err("non-monotonic DAC frame position".to_string());
    }
    let rate = f64::from(last.sample_rate);
    let expected_ns = df as f64 / rate * 1e9;
    let drift_ppm = ((expected_ns - dt as f64) / dt as f64) * 1e6;
    // Absolute sync error at the final sample: how far the DAC's frame
    // position is from the host-monotonic prediction anchored at the
    // FIRST sample.
    let predicted_frames = dt as f64 / 1e9 * rate;
    let sync_err_ns = ((df as f64 - predicted_frames).abs() / rate * 1e9) as u64;
    Ok((drift_ppm, sync_err_ns))
}

/// Monotonic OS nanoseconds for the current instant (the shims' shared
/// normalization helper; std `Instant` cannot be exported across FFI, so
/// every shim carries its own epoch anchor and converts via this).
#[inline]
pub fn monotonic_ns_now(anchor: &Instant, anchor_ns: u64) -> u64 {
    anchor_ns.saturating_add(anchor.elapsed().as_nanos() as u64)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synth(first_frame: u64, df: u64, dt_ns: u64) -> Vec<DacPresentationClock> {
        // A perfectly-on-rate stream: df frames over dt_ns at 48 kHz.
        vec![
            DacPresentationClock {
                dac_frame_position: first_frame,
                sample_rate: 48_000,
                monotonic_ns: 1_000_000_000,
            },
            DacPresentationClock {
                dac_frame_position: first_frame + df,
                sample_rate: 48_000,
                monotonic_ns: 1_000_000_000 + dt_ns,
            },
        ]
    }

    #[test]
    fn perfect_clock_is_within_gate() {
        // 48000 frames over 1 s exactly: 0 ppm drift, 0 sync error.
        let (ppm, sync) = validate_drift_window(&synth(0, 48_000, 1_000_000_000)).unwrap();
        assert!(ppm.abs() <= 100.0, "ppm {ppm}");
        assert!(sync < 5_000_000, "sync {sync}");
    }

    #[test]
    fn drifted_clock_is_out_of_gate() {
        // 250 ppm fast (120 frames extra over 1 s): must be reported.
        let (ppm, _) = validate_drift_window(&synth(0, 48_120, 1_000_000_000)).unwrap();
        assert!(ppm > 100.0, "ppm {ppm} should exceed the gate");
    }

    #[test]
    fn non_monotonic_rejected() {
        let mut s = synth(0, 48_000, 1_000_000_000);
        s[1].dac_frame_position = s[0].dac_frame_position - 1;
        assert!(validate_drift_window(&s).is_err());
    }

    #[test]
    fn slot_roundtrip_preserves_sample() {
        let slot = DacClockSlot::default();
        assert!(slot.sample().is_none());
        slot.publish(&DacPresentationClock {
            dac_frame_position: 777,
            sample_rate: 48_000,
            monotonic_ns: 42,
        });
        let (s, seq) = slot.sample().unwrap();
        assert_eq!(seq, 1);
        assert_eq!(s.dac_frame_position, 777);
        assert_eq!(s.sample_rate, 48_000);
        assert_eq!(s.monotonic_ns, 42);
    }
}
