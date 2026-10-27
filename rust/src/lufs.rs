//! EBU R128 / ITU-R BS.1770-4 loudness measurement and normalization
//! (Phase 4a / MP-HAL03 — the 48 kHz golden-vector DSP suite's loudness
//! stage).
//!
//! Implements the ITU-R BS.1770-4 integrated-loudness measurement on the
//! CANONICAL 48 kHz `f32` stereo pipeline (post-`CanonicalSrc`, master
//! plan invariant 6 — the K-weighting coefficients below are the spec's
//! 48 kHz tables, so any other rate is rejected as `FormatUnsupported`;
//! producers resample to 48 kHz BEFORE this stage):
//!
//! 1. **K-weighting** — the two spec biquads in `f64` (bit-deterministic
//!    arithmetic: no transcendentals, no FMA contraction in Rust):
//!    * stage 1: high-shelf pre-filter (head-shadow EQ),
//!    * stage 2: RLB-weighted high-pass (low-frequency roll-off).
//! 2. **Gated block measurement** — 400 ms blocks with 75 % overlap
//!    (100 ms hop): block mean-square `z` over both channels, block
//!    loudness `-0.691 + 10·log10(Σz)`.
//! 3. **Gating** — absolute gate at −70 LUFS, then relative gate at
//!    (gated mean − 10) LU; the integrated loudness is the mean square
//!    over the surviving blocks.
//!
//! The [`LufsNormalizer`] measures a window and applies the linear gain
//! that centres it on a target (streaming loudness targets: −16 LUFS
//! typical, −14 Spotify, −23 EBU broadcast). Gain is clamped to ±20 dB;
//! digital silence (no gated blocks) normalizes to 0 dB — nothing to
//! amplify. The applied gain is NOT soft-knee limited: true-peak limiting
//! belongs to the limiter stage downstream (native `SoftKneeLimiter`),
//! and the golden vectors pin the pure linear behaviour.
//!
//! Determinism: all filtering is `+ - * /` on `f64`; the only libm call
//! is `log10` on the accumulated power sums (a single scalar per block),
//! whose last-ulp platform differences move the reported LUFS by ~1e-15
//! and the applied gain by ~1e-16 — far inside the golden-vector epsilon
//! (1e-5). Runs on the decode/control thread only, never the audio
//! callback (measurement clones its input; see `process_interleaved`).

use crate::contract::{AudioErrorKind, EngineError};

/// The measurement's fixed geometry (BS.1770-4 at the canonical rate).
pub const LUFS_SAMPLE_RATE_HZ: u32 = 48_000;
/// Gated block size: 400 ms @ 48 kHz.
pub const LUFS_BLOCK_FRAMES: usize = 19_200;
/// Block hop (75 % overlap): 100 ms @ 48 kHz.
pub const LUFS_HOP_FRAMES: usize = 4_800;
/// Absolute gating threshold (LUFS).
pub const LUFS_ABSOLUTE_GATE: f64 = -70.0;
/// Relative gating offset below the gated mean (LU).
pub const LUFS_RELATIVE_GATE_LU: f64 = -10.0;
/// Channel weight for the canonical stereo pair (BS.1770 §2.1 G=1.0).
const CHANNEL_WEIGHTS: [f64; 2] = [1.0, 1.0];
/// Sentinel for "no gated blocks" (digital silence).
pub const LUFS_SILENCE: f64 = f64::NEG_INFINITY;

// ---- K-weighting: the BS.1770-4 48 kHz coefficient tables ----------------
// Stage 1 (high shelf): H(z) numerator/denominator as published.
const SHELF_B: [f64; 3] = [
    1.535_124_859_586_97,
    -2.691_696_189_406_38,
    1.198_392_810_852_85,
];
const SHELF_A: [f64; 2] = [-1.690_659_293_182_41, 0.732_480_774_215_85];
// Stage 2 (RLB high-pass).
const RLB_B: [f64; 3] = [1.0, -2.0, 1.0];
const RLB_A: [f64; 2] = [-1.990_047_454_833_98, 0.990_072_250_366_21];

/// One K-weighting channel: the two spec biquads in direct form I.
#[derive(Debug, Clone, Copy)]
struct KWeight {
    s1_x: [f64; 2], // shelf input history  (x1, x2)
    s1_y: [f64; 2], // shelf output history (y1, y2)
    s2_x: [f64; 2],
    s2_y: [f64; 2],
}

impl KWeight {
    fn new() -> Self {
        Self {
            s1_x: [0.0; 2],
            s1_y: [0.0; 2],
            s2_x: [0.0; 2],
            s2_y: [0.0; 2],
        }
    }

    /// Process one sample through both biquads (direct form I).
    #[inline]
    fn process(&mut self, x: f64) -> f64 {
        // Stage 1: high shelf.
        let y1 = SHELF_B[0] * x
            + SHELF_B[1] * self.s1_x[0]
            + SHELF_B[2] * self.s1_x[1]
            - SHELF_A[0] * self.s1_y[0]
            - SHELF_A[1] * self.s1_y[1];
        self.s1_x[1] = self.s1_x[0];
        self.s1_x[0] = x;
        self.s1_y[1] = self.s1_y[0];
        self.s1_y[0] = y1;

        // Stage 2: RLB high-pass.
        let y2 = RLB_B[0] * y1
            + RLB_B[1] * self.s2_x[0]
            + RLB_B[2] * self.s2_x[1]
            - RLB_A[0] * self.s2_y[0]
            - RLB_A[1] * self.s2_y[1];
        self.s2_x[1] = self.s2_x[0];
        self.s2_x[0] = y1;
        self.s2_y[1] = self.s2_y[0];
        self.s2_y[0] = y2;
        y2
    }

    fn reset(&mut self) {
        *self = KWeight::new();
    }
}

/// Live measurement state for the gated integrated loudness.
#[derive(Debug, Clone)]
pub struct LufsAnalyzer {
    channels: [KWeight; 2],
    /// Sliding 400 ms power window: per-frame weighted mean square.
    power_ring: Vec<f64>,
    power_pos: usize,
    power_sum: f64,
    frames_seen: u64,
    /// Mean-square `z` of every completed (overlapping) block.
    blocks: Vec<f64>,
}

impl LufsAnalyzer {
    /// Build the analyzer for the canonical 48 kHz stereo geometry.
    pub fn new() -> Result<Self, EngineError> {
        // The K-weighting tables above are the spec's 48 kHz coefficients:
        // other rates require re-derived tables (out of scope by design —
        // the pipeline resamples to canonical BEFORE loudness).
        if LUFS_SAMPLE_RATE_HZ != crate::audio_src::CANONICAL_RATE_HZ {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::Init,
                device_id: None,
            });
        }
        Ok(Self {
            channels: [KWeight::new(), KWeight::new()],
            power_ring: vec![0.0; LUFS_BLOCK_FRAMES],
            power_pos: 0,
            power_sum: 0.0,
            frames_seen: 0,
            blocks: Vec::new(),
        })
    }

    /// Number of completed gated blocks so far.
    pub fn block_count(&self) -> usize {
        self.blocks.len()
    }

    /// Feed interleaved stereo `f32` frames (advances the K-weighting
    /// filters and the block windows). Never touches the audio thread.
    pub fn process(&mut self, interleaved: &[f32]) {
        // Whole stereo frames only; a trailing odd sample is a partial
        // frame the producer contract never emits.
        for frame in interleaved.as_chunks::<2>().0 {
            let l = self.channels[0].process(f64::from(frame[0]));
            let r = self.channels[1].process(f64::from(frame[1]));
            let p = CHANNEL_WEIGHTS[0] * l * l + CHANNEL_WEIGHTS[1] * r * r;

            // Sliding window insert.
            self.power_sum -= self.power_ring[self.power_pos];
            self.power_ring[self.power_pos] = p;
            self.power_sum += p;
            self.power_pos = (self.power_pos + 1) % LUFS_BLOCK_FRAMES;
            self.frames_seen += 1;

            // A block completes every hop once the window is full.
            if self.frames_seen >= LUFS_BLOCK_FRAMES as u64
                && (self.frames_seen - LUFS_BLOCK_FRAMES as u64)
                    .is_multiple_of(LUFS_HOP_FRAMES as u64)
            {
                self.blocks.push(self.power_sum / LUFS_BLOCK_FRAMES as f64);
            }
        }
    }

    /// Integrated gated loudness (LUFS), or [`LUFS_SILENCE`] when no block
    /// survived the absolute gate (digital silence / startup window).
    pub fn integrated_lufs(&self) -> f64 {
        if self.blocks.is_empty() {
            return LUFS_SILENCE;
        }
        // Absolute gate: block loudness must exceed -70 LUFS.
        let block_loudness =
            |z: f64| -0.691 + 10.0 * (z.max(f64::MIN_POSITIVE)).log10();
        let abs_gated: Vec<f64> = self
            .blocks
            .iter()
            .copied()
            .filter(|&z| block_loudness(z) > LUFS_ABSOLUTE_GATE)
            .collect();
        if abs_gated.is_empty() {
            return LUFS_SILENCE;
        }
        // Relative gate: -10 LU below the absolute-gated mean loudness.
        let abs_mean_z = abs_gated.iter().sum::<f64>() / abs_gated.len() as f64;
        let rel_threshold_lufs = block_loudness(abs_mean_z) + LUFS_RELATIVE_GATE_LU;
        let rel_gated: Vec<f64> = abs_gated
            .iter()
            .copied()
            .filter(|&z| block_loudness(z) > rel_threshold_lufs)
            .collect();
        if rel_gated.is_empty() {
            return LUFS_SILENCE;
        }
        let mean_z = rel_gated.iter().sum::<f64>() / rel_gated.len() as f64;
        block_loudness(mean_z)
    }

    /// Drop all measurement state (track switch / seek).
    pub fn reset(&mut self) {
        self.channels[0].reset();
        self.channels[1].reset();
        self.power_ring.iter_mut().for_each(|p| *p = 0.0);
        self.power_pos = 0;
        self.power_sum = 0.0;
        self.frames_seen = 0;
        self.blocks.clear();
    }
}

impl Default for LufsAnalyzer {
    fn default() -> Self {
        // The geometry is compile-time pinned to the canonical rate; the
        // constructor error is only reachable if that pin ever changes.
        Self::new().expect("canonical-rate pinned at compile time")
    }
}

/// Report of one normalization pass.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LufsReport {
    /// Integrated gated loudness of the measured window (LUFS; -inf for
    /// digital silence).
    pub measured_lufs: f64,
    /// Gain actually applied (dB) — clamped to ±20 dB; 0 dB for silence.
    pub applied_gain_db: f64,
    /// Completed gated blocks in the measurement.
    pub gated_blocks: usize,
}

/// EBU R128 / ITU-R BS.1770-4 loudness NORMALIZER: measure a window, then
/// apply the linear gain centreing it on the target loudness.
///
/// `process_interleaved` advances the analyzer's K-weighting filters on
/// the window's ORIGINAL (pre-gain) samples, then applies the resulting
/// gain to the same buffer in place (the filters must never observe the
/// already-gained output). Feeding a whole track in one call yields the
/// textbook single-pass normalization (the golden-vector path); chunked
/// feeding yields an evolving gain as the measurement window converges —
/// deterministic for a fixed chunking.
#[derive(Debug, Clone)]
pub struct LufsNormalizer {
    analyzer: LufsAnalyzer,
    target_lufs: f64,
    max_gain_db: f64,
}

impl LufsNormalizer {
    /// Build a normalizer targeting `target_lufs` (typical: -16.0 for
    /// streaming, -14.0 Spotify, -23.0 EBU broadcast).
    pub fn new(target_lufs: f64) -> Result<Self, EngineError> {
        if !target_lufs.is_finite() || !(-60.0..=-0.0).contains(&target_lufs) {
            return Err(EngineError::Audio {
                kind: AudioErrorKind::FormatUnsupported,
                device_id: None,
            });
        }
        Ok(Self {
            analyzer: LufsAnalyzer::new()?,
            target_lufs,
            max_gain_db: 20.0,
        })
    }

    /// Configured target loudness (LUFS).
    pub fn target_lufs(&self) -> f64 {
        self.target_lufs
    }

    /// Measure + normalize one interleaved stereo window IN PLACE.
    /// Returns the measurement report (decode/control thread only —
    /// never the audio callback).
    pub fn process_interleaved(&mut self, samples: &mut [f32]) -> LufsReport {
        // Advance the K-weighting filters on the PRE-GAIN samples of this
        // window, then apply the resulting gain in place.
        self.analyzer.process(samples);
        let measured = self.analyzer.integrated_lufs();
        let report = LufsReport {
            measured_lufs: measured,
            applied_gain_db: 0.0,
            gated_blocks: self.analyzer.block_count(),
        };
        if samples.is_empty() {
            return report;
        }
        // Digital silence: no gated blocks => nothing to normalize.
        let gain_db = if measured == LUFS_SILENCE {
            0.0
        } else {
            (self.target_lufs - measured).clamp(-self.max_gain_db, self.max_gain_db)
        };
        let gain = 10.0f64.powf(gain_db / 20.0);
        for s in samples.iter_mut() {
            *s = (*s as f64 * gain) as f32;
        }
        LufsReport {
            applied_gain_db: gain_db,
            ..report
        }
    }

    /// Reset the measurement state (track switch / seek).
    pub fn reset(&mut self) {
        self.analyzer.reset();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Reference sine generator (f64 phase accumulation).
    fn sine(frames: usize, freq: f64, amp: f64) -> Vec<f32> {
        let mut out = Vec::with_capacity(frames * 2);
        let mut phase = 0.0f64;
        for _ in 0..frames {
            let s = (std::f64::consts::TAU * phase).sin() * amp;
            out.push(s as f32);
            out.push(s as f32);
            phase += freq / LUFS_SAMPLE_RATE_HZ as f64;
        }
        out
    }

    #[test]
    fn full_scale_997hz_stereo_sine_is_0_lufs() {
        // BS.1770-4 sanity anchor: the K-weighting carries exactly
        // +0.691 dB gain at ~1 kHz, cancelling the formula's -0.691
        // offset — so a full-scale STEREO 997 Hz sine measures 0.0 LUFS
        // (and a MONO one -3.01, the spec's annex anchor). Verified
        // independently against the spec's coefficient tables.
        let mut an = LufsAnalyzer::new().unwrap();
        an.process(&sine(LUFS_SAMPLE_RATE_HZ as usize, 997.0, 1.0));
        let lufs = an.integrated_lufs();
        assert!(
            (-0.5..=0.5).contains(&lufs),
            "997 Hz FS stereo sine: {lufs} LUFS (expected ~ 0.0)"
        );
    }

    #[test]
    fn silence_measures_negative_infinity_and_gains_zero_db() {
        let mut norm = LufsNormalizer::new(-16.0).unwrap();
        let mut silence = vec![0.0f32; LUFS_SAMPLE_RATE_HZ as usize * 2];
        let report = norm.process_interleaved(&mut silence);
        assert_eq!(report.measured_lufs, LUFS_SILENCE);
        assert_eq!(report.applied_gain_db, 0.0);
        assert!(silence.iter().all(|s| *s == 0.0));
    }

    #[test]
    fn normalizer_centres_a_quiet_tone_on_the_target() {
        // -24 dBFS stereo sine => ~ -24.0 LUFS (FS reads 0.0; see the
        // anchor above); normalize to -16 target => ~ +8 dB gain.
        let mut norm = LufsNormalizer::new(-16.0).unwrap();
        let mut samples = sine(LUFS_SAMPLE_RATE_HZ as usize, 997.0, 0.063);
        let report = norm.process_interleaved(&mut samples);
        assert!(
            (report.measured_lufs - (-24.0)).abs() < 0.7,
            "quiet tone measured {report:?}"
        );
        assert!(
            (report.applied_gain_db - 8.0).abs() < 0.8,
            "gain to target: {report:?}"
        );
        let mut an2 = LufsAnalyzer::new().unwrap();
        an2.process(&samples);
        let after = an2.integrated_lufs();
        assert!(
            (after - (-16.0)).abs() < 0.5,
            "normalized output centres on target: {after} LUFS"
        );
    }

    #[test]
    fn measurement_is_deterministic_and_chunking_stable() {
        let whole = sine(LUFS_SAMPLE_RATE_HZ as usize * 2, 440.0, 0.5);
        let mut a = LufsAnalyzer::new().unwrap();
        a.process(&whole);
        let mut b = LufsAnalyzer::new().unwrap();
        for chunk in whole.chunks(735 * 2) {
            b.process(chunk);
        }
        assert_eq!(a.integrated_lufs(), b.integrated_lufs());
        assert_eq!(a.block_count(), b.block_count());
        // And a rerun of the same input is identical.
        let mut c = LufsAnalyzer::new().unwrap();
        c.process(&whole);
        assert_eq!(a.integrated_lufs(), c.integrated_lufs());
    }

    #[test]
    fn target_bounds_are_validated() {
        assert!(LufsNormalizer::new(-16.0).is_ok());
        assert!(LufsNormalizer::new(0.0).is_ok());
        for bad in [-70.0, 5.0, f64::NAN] {
            assert!(LufsNormalizer::new(bad).is_err(), "target {bad}");
        }
    }
}
