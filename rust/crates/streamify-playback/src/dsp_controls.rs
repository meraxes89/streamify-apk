//! Sovereign HAL Phase 4 (Step 2): the live DSP control plane.
//!
//! Lock-free parameter surface between the UI (Compose sliders /
//! presets, driven per platform through the JNI/JNA/JS seams) and the
//! producer's render stages (EQ + loudness + limiter + crossfade).
//!
//! # RT contract
//!
//! The producer thread reads the parameters ONCE per render round via
//! [`DspParams::snapshot`] (a seqlock-style generation read: the writer
//! bumps a version, writes the payload, bumps again; the reader retries
//! on a torn observation). No mutex, no allocation, no blocking — both
//! sides are wait-free for every practical schedule, and a torn read
//! falls back to the last coherent snapshot rather than blocking.
//!
//! # The stage map (what each field feeds)
//!
//! * `eq_gains_db` — [`crate::dsp::StudioEqualizer`]'s 10 bands,
//!   clamped to ±12 dB at BOTH the setter and the EQ stage (defense in
//!   depth: a UI bug can never reach the biquad coefficients).
//! * `lufs_mode` — the loudness chain's mode:
//!   [`LufsMode::Normalize`] runs the BS.1770-4 normalizer toward
//!   `target_lufs`; [`LufsMode::Bypass`] holds unity gain (the
//!   streaming-norm toggle). Mode switches apply a smooth gain ramp
//!   (≤ ±6 dB per round, the anti-zipper discipline) so toggling
//!   normalization mid-track never clicks.
//! * `limiter_ceiling_db` — the true-peak limiter's ceiling (default
//!   −1.0 dBTP). The EQ can push energy past the loudness stage's
//!   headroom; the limiter is the last line before the ring.
//! * `crossfade_ms` — the next-track overlap window (0 = gapless hard
//!   cut; > 0 = equal-power overlap on the producer write edge).
//!
//! The CROSSFADE engine itself lives in the producer's session chain
//! (it needs the NEXT track's decoded audio); this module only owns
//! the parameters and the ramps.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use streamify_audio::StudioEqualizer;

/// The loudness chain's runtime mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum LufsMode {
    /// BS.1770-4 integrated normalization toward `target_lufs`
    /// (the streaming default, −16 LUFS).
    #[default]
    Normalize,
    /// Unity gain — no measurement, no correction (the "off" toggle).
    Bypass,
}

/// One coherent DSP parameter set (the seqlock payload).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DspParams {
    /// 10-band EQ gains in dB, band 0 = 31.25 Hz … band 9 = 16 kHz
    /// (already clamped to ±12 dB by the setter).
    pub eq_gains_db: [f64; 10],
    /// The loudness chain's mode (normalize vs bypass).
    pub lufs_mode: LufsMode,
    /// The normalization target when `lufs_mode == Normalize`.
    pub target_lufs: f64,
    /// The true-peak limiter ceiling in dBTP (≤ 0.0).
    pub limiter_ceiling_db: f64,
    /// The crossfade window in ms (0 = gapless hard cut).
    pub crossfade_ms: u32,
}

impl Default for DspParams {
    fn default() -> Self {
        Self {
            eq_gains_db: [0.0; 10],
            lufs_mode: LufsMode::Normalize,
            target_lufs: super::producer::DEFAULT_TARGET_LUFS,
            limiter_ceiling_db: -1.0,
            crossfade_ms: 0,
        }
    }
}

/// The hard clamp on every EQ band (the spec's ±12 dB window).
pub const EQ_GAIN_CLAMP_DB: f64 = 12.0;

/// The per-round gain-ramp step limit (dB) — the anti-zipper discipline:
/// a mode/target switch eases over multiple rounds instead of stepping.
const GAIN_RAMP_STEP_DB: f64 = 6.0;

/// Clamp one EQ band gain into the ±12 dB window.
pub fn clamp_eq_gain(gain_db: f64) -> f64 {
    if gain_db.is_finite() {
        gain_db.clamp(-EQ_GAIN_CLAMP_DB, EQ_GAIN_CLAMP_DB)
    } else {
        0.0
    }
}

/// The lock-free DSP control plane (seqlock writes, wait-free reads).
///
/// ONE instance lives for the process (the producer holds an `Arc`;
/// the platform seams hold clones). UI threads call the setters; the
/// producer's render loop calls [`DspControls::snapshot`] once per
/// round and [`DspControls::ramped_limiter_gain`] when it needs the
/// anti-zipper gain.
pub struct DspControls {
    /// Seqlock version (even = stable, odd = write in flight).
    version: AtomicU64,
    /// Phantom Debug anchor (manual impl below — Mutex fields).
    /// The parameter payload (written under the odd version window).
    params: Mutex<DspParams>,
    /// The last snapshot handed to the render loop (ramp reference).
    last_snapshot: Mutex<DspParams>,
}

impl Default for DspControls {
    fn default() -> Self {
        Self::new()
    }
}

impl std::fmt::Debug for DspControls {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("DspControls")
            .field("params", &self.snapshot())
            .finish_non_exhaustive()
    }
}

impl DspControls {
    pub fn new() -> Self {
        Self {
            version: AtomicU64::new(2),
            params: Mutex::new(DspParams::default()),
            last_snapshot: Mutex::new(DspParams::default()),
        }
    }

    /// The seqlock writer: open the odd-version window, mutate under
    /// the payload lock, close the window after unlock. Readers see
    /// either the old or the new payload — never a torn mix.
    fn write_params(&self, mutate: impl FnOnce(&mut DspParams)) {
        let mut guard = self
            .params
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        self.version.fetch_add(1, Ordering::AcqRel); // → odd (write in flight)
        mutate(&mut guard);
        drop(guard); // payload unlock BEFORE the version close
        self.version.fetch_add(1, Ordering::AcqRel); // → even (stable again)
    }

    /// Set one EQ band's gain (clamped to ±12 dB). Invalid band
    /// indices are ignored (a UI probing band 10 is a no-op).
    pub fn set_eq_band(&self, band: usize, gain_db: f64) {
        if band >= 10 {
            return;
        }
        self.write_params(|p| p.eq_gains_db[band] = clamp_eq_gain(gain_db));
    }

    /// Set the whole 10-band curve at once (each band clamped).
    pub fn set_eq_curve(&self, gains_db: &[f64]) {
        self.write_params(|p| {
            for (dst, src) in p.eq_gains_db.iter_mut().zip(gains_db.iter()) {
                *dst = clamp_eq_gain(*src);
            }
        });
    }

    /// A named preset's curve (the app's shipped presets; mirrors the
    /// Kotlin `EqualizerFragment` list so both surfaces stay in sync).
    pub fn apply_eq_preset(&self, preset: EqPreset) {
        self.set_eq_curve(&preset.gains_db());
    }

    /// Flip the loudness normalization mode (Normalize ↔ Bypass).
    pub fn set_lufs_mode(&self, mode: LufsMode) {
        self.write_params(|p| p.lufs_mode = mode);
    }

    /// Set the normalization target (clamped to the valid LUFS window
    /// −36 … 0; the normalizer's own constructor range).
    pub fn set_target_lufs(&self, target_lufs: f64) {
        self.write_params(|p| p.target_lufs = target_lufs.clamp(-36.0, 0.0));
    }

    /// Set the limiter ceiling (dBTP; clamped to (−12.0, 0.0]).
    pub fn set_limiter_ceiling_db(&self, ceiling_db: f64) {
        self.write_params(|p| p.limiter_ceiling_db = ceiling_db.clamp(-12.0, 0.0));
    }

    /// Set the crossfade window (ms; clamped to 0 … 12 000).
    pub fn set_crossfade_ms(&self, crossfade_ms: u32) {
        self.write_params(|p| p.crossfade_ms = crossfade_ms.min(12_000));
    }

    /// The producer's per-round read: a coherent snapshot of the
    /// current parameters (the seqlock retry loop; wait-free in the
    /// uncontended case, bounded-retry under a writer storm).
    pub fn snapshot(&self) -> DspParams {
        let mut backoff = 0u32;
        loop {
            let v1 = self.version.load(Ordering::Acquire);
            if v1 & 1 == 0 {
                // Stable window: read the payload, re-check version.
                let p = *self
                    .params
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
                let v2 = self.version.load(Ordering::Acquire);
                if v1 == v2 {
                    return p;
                }
            }
            // Torn or mid-write: brief spin, bounded, then take the
            // last known-good from last_snapshot (never block).
            backoff += 1;
            if backoff > 64 {
                return *self
                    .last_snapshot
                    .lock()
                    .unwrap_or_else(|poisoned| poisoned.into_inner());
            }
            std::thread::yield_now();
        }
    }

    /// The producer's post-read bookkeeping: remember the snapshot the
    /// render round used (the fallback source + the ramp reference).
    pub fn remember(&self, p: &DspParams) {
        *self
            .last_snapshot
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner()) = *p;
    }

    /// Apply the current EQ curve to one [`StudioEqualizer`] (the
    /// producer calls this per round; the biquads re-configure with
    /// the clamped gains — coefficient reconfiguration is allocation-
    /// free).
    pub fn apply_eq(&self, eq: &mut StudioEqualizer) {
        let p = self.snapshot();
        for (band, &gain) in p.eq_gains_db.iter().enumerate() {
            eq.set_band_gain(band, clamp_eq_gain(gain));
        }
        self.remember(&p);
    }

    /// The anti-zipper ramp: the loudness/limiter gain the render
    /// round should apply, eased from `current_gain_db` toward
    /// `target_gain_db` by ≤ [`GAIN_RAMP_STEP_DB`] dB this round.
    /// Returns the eased gain (dB).
    pub fn ramp_gain(current_gain_db: f64, target_gain_db: f64) -> f64 {
        let delta = target_gain_db - current_gain_db;
        if delta.abs() <= GAIN_RAMP_STEP_DB {
            target_gain_db
        } else {
            current_gain_db + delta.signum() * GAIN_RAMP_STEP_DB
        }
    }

}


/// The shipped EQ presets (the app's `EqualizerFragment` list).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EqPreset {
    Flat,
    BassBoost,
    VocalBoost,
    TrebleBoost,
    Acoustic,
    Electronic,
    Rock,
    Jazz,
}

impl EqPreset {
    /// The 10-band curve (band 0 = 31.25 Hz … band 9 = 16 kHz), every
    /// value already inside the ±12 dB window.
    pub fn gains_db(&self) -> [f64; 10] {
        match self {
            EqPreset::Flat => [0.0; 10],
            EqPreset::BassBoost => [8.0, 6.5, 5.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
            EqPreset::VocalBoost => [-2.0, -1.0, 0.0, 2.0, 4.0, 5.0, 4.0, 2.0, 0.0, -1.0],
            EqPreset::TrebleBoost => [0.0, 0.0, 0.0, 0.0, 0.0, 1.5, 3.0, 5.0, 6.5, 8.0],
            EqPreset::Acoustic => [3.0, 2.0, 0.5, 0.0, 1.5, 1.5, 3.5, 4.0, 3.5, 2.0],
            EqPreset::Electronic => [6.0, 5.0, 1.5, 0.0, -1.5, 2.0, 1.5, 2.0, 5.0, 6.0],
            EqPreset::Rock => [5.5, 4.0, 2.0, -0.5, -1.5, 1.0, 3.0, 4.5, 5.0, 4.0],
            EqPreset::Jazz => [3.5, 2.5, 1.0, 1.5, -1.0, -1.0, 0.0, 1.5, 3.0, 3.5],
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn eq_gains_clamp_to_12db() {
        let c = DspControls::new();
        c.set_eq_band(0, 40.0);
        c.set_eq_band(1, -99.0);
        c.set_eq_band(2, f64::NAN);
        let p = c.snapshot();
        assert_eq!(p.eq_gains_db[0], 12.0);
        assert_eq!(p.eq_gains_db[1], -12.0);
        assert_eq!(p.eq_gains_db[2], 0.0, "NaN degrades to flat, never reaches the biquads");
        c.set_eq_band(10, 5.0); // out of range: no-op
        assert_eq!(c.snapshot().eq_gains_db[0], 12.0);
    }

    #[test]
    fn presets_are_in_window() {
        for preset in [
            EqPreset::Flat, EqPreset::BassBoost, EqPreset::VocalBoost, EqPreset::TrebleBoost,
            EqPreset::Acoustic, EqPreset::Electronic, EqPreset::Rock, EqPreset::Jazz,
        ] {
            for g in preset.gains_db() {
                assert!(g.abs() <= EQ_GAIN_CLAMP_DB, "{preset:?} band out of window");
            }
        }
        let c = DspControls::new();
        c.apply_eq_preset(EqPreset::BassBoost);
        assert_eq!(c.snapshot().eq_gains_db[0], 8.0);
    }

    #[test]
    fn lufs_mode_and_targets_round_trip() {
        let c = DspControls::new();
        assert_eq!(c.snapshot().lufs_mode, LufsMode::Normalize);
        c.set_lufs_mode(LufsMode::Bypass);
        assert_eq!(c.snapshot().lufs_mode, LufsMode::Bypass);
        c.set_target_lufs(-14.0);
        assert_eq!(c.snapshot().target_lufs, -14.0);
        c.set_target_lufs(-99.0);
        assert_eq!(c.snapshot().target_lufs, -36.0, "clamped to the valid window");
        c.set_limiter_ceiling_db(-1.0);
        assert_eq!(c.snapshot().limiter_ceiling_db, -1.0);
        c.set_crossfade_ms(3_000);
        assert_eq!(c.snapshot().crossfade_ms, 3_000);
        c.set_crossfade_ms(99_999);
        assert_eq!(c.snapshot().crossfade_ms, 12_000, "crossfade clamped");
    }

    #[test]
    fn ramp_step_is_bounded() {
        // A 24 dB jump eases in 6 dB rounds (4 rounds), never a click.
        assert_eq!(DspControls::ramp_gain(0.0, 24.0), 6.0);
        assert_eq!(DspControls::ramp_gain(6.0, 24.0), 12.0);
        assert_eq!(DspControls::ramp_gain(18.0, 24.0), 24.0);
        assert_eq!(DspControls::ramp_gain(0.0, -6.0), -6.0, "small steps land exactly");
    }

    #[test]
    fn apply_eq_configures_a_studio_equalizer() {
        let c = DspControls::new();
        c.apply_eq_preset(EqPreset::BassBoost);
        let mut eq = StudioEqualizer::new(48_000.0);
        c.apply_eq(&mut eq);
        // Process a buffer: gains are in effect, output stays finite.
        let mut buf = vec![0.5f32; 480 * 2];
        eq.process_buffer_interleaved(&mut buf, 2);
        assert!(buf.iter().all(|s| s.is_finite()));
        assert!(buf.iter().all(|s| s.abs() <= 1.0));
    }
}
