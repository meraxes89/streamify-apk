//! Sovereign HAL Phase 4 (Step 2b): the DSP control plane's C ABI —
//! the platform brackets' seam (Android JNI, Desktop JNA, wasm bindgen).
//!
//! ADDITIVE ONLY: the frozen 4-symbol ring edge (`ring_export.rs`) and
//! the 7-symbol JNI header (`streamify_audio_sinks.h`) are untouched.
//! This module adds a process-global [`DspControls`] instance and a
//! small flat C surface over it:
//!
//! ```c
//! void     streamify_dsp_set_eq_band(int32_t band, double gain_db);
//! void     streamify_dsp_set_eq_curve(const double* gains_db /* 10 */);
//! void     streamify_dsp_set_lufs_mode(int32_t mode /* 0=normalize 1=bypass */);
//! void     streamify_dsp_set_target_lufs(double target_lufs);
//! void     streamify_dsp_set_limiter_ceiling_db(double ceiling_db);
//! void     streamify_dsp_set_crossfade_ms(int32_t crossfade_ms);
//! int32_t  streamify_dsp_apply_preset(int32_t preset /* EqPreset ordinal */);
//! double   streamify_dsp_get_eq_band(int32_t band);   // telemetry echo
//! int32_t  streamify_dsp_get_lufs_mode(void);
//! ```
//!
//! The producer's [`crate::producer::ProducerConfig::dsp`] closes over
//! THIS instance's `Arc` (the platform seams and the producer share
//! one control plane per process). Every setter is wait-free (the
//! seqlock protocol in [`crate::dsp_controls`]); every symbol is
//! degenerate-input safe (null pointers, out-of-range bands/modes are
//! clamped or ignored — never UB).

use std::sync::Arc;

use crate::dsp_controls::{DspControls, EqPreset, LufsMode};

/// The process-global control plane (the platform seam and the
/// producer's `ProducerConfig::dsp` both hold this `Arc`).
static CONTROLS: std::sync::OnceLock<Arc<DspControls>> = std::sync::OnceLock::new();

/// The shared handle (the producer's config builder calls this).
pub fn shared() -> Arc<DspControls> {
    CONTROLS.get_or_init(|| Arc::new(DspControls::new())).clone()
}

/// Set one EQ band's gain (±12 dB clamped; invalid bands ignored).
#[no_mangle]
pub extern "C" fn streamify_dsp_set_eq_band(band: i32, gain_db: f64) {
    shared().set_eq_band(band.max(0) as usize, gain_db);
}

/// Set the whole 10-band curve (each ±12 dB clamped; null is a no-op).
#[no_mangle]
pub unsafe extern "C" fn streamify_dsp_set_eq_curve(gains_db: *const f64, count: usize) {
    if gains_db.is_null() || count == 0 {
        return;
    }
    let len = count.min(10);
    // SAFETY: caller guarantees `count` readable doubles; the view is
    // bounds-capped to the 10-band curve and never escapes.
    let view = unsafe { std::slice::from_raw_parts(gains_db, len) };
    shared().set_eq_curve(view);
}

/// Flip the loudness mode (0 = Normalize, 1 = Bypass; else ignored).
#[no_mangle]
pub extern "C" fn streamify_dsp_set_lufs_mode(mode: i32) {
    let mode = match mode {
        0 => LufsMode::Normalize,
        1 => LufsMode::Bypass,
        _ => return,
    };
    shared().set_lufs_mode(mode);
}

/// Set the normalization target (clamped −36 … 0 LUFS).
#[no_mangle]
pub extern "C" fn streamify_dsp_set_target_lufs(target_lufs: f64) {
    shared().set_target_lufs(target_lufs);
}

/// Set the limiter ceiling (dBTP, clamped −12 … 0).
#[no_mangle]
pub extern "C" fn streamify_dsp_set_limiter_ceiling_db(ceiling_db: f64) {
    shared().set_limiter_ceiling_db(ceiling_db);
}

/// Set the crossfade window (ms, clamped 0 … 12 000).
#[no_mangle]
pub extern "C" fn streamify_dsp_set_crossfade_ms(crossfade_ms: i32) {
    shared().set_crossfade_ms(crossfade_ms.max(0) as u32);
}

/// Apply a named preset by ordinal (the [`EqPreset`] declaration
/// order). Unknown ordinals are a no-op returning -1; success 0.
#[no_mangle]
pub extern "C" fn streamify_dsp_apply_preset(preset: i32) -> i32 {
    let preset = match preset {
        0 => EqPreset::Flat,
        1 => EqPreset::BassBoost,
        2 => EqPreset::VocalBoost,
        3 => EqPreset::TrebleBoost,
        4 => EqPreset::Acoustic,
        5 => EqPreset::Electronic,
        6 => EqPreset::Rock,
        7 => EqPreset::Jazz,
        _ => return -1,
    };
    shared().apply_eq_preset(preset);
    0
}

/// Telemetry echo: one band's clamped gain (invalid bands return 0).
#[no_mangle]
pub extern "C" fn streamify_dsp_get_eq_band(band: i32) -> f64 {
    let band = if (0..10).contains(&band) { band as usize } else { return 0.0 };
    shared().snapshot().eq_gains_db[band]
}

/// Telemetry echo: the current loudness mode (0 = Normalize, 1 = Bypass).
#[no_mangle]
pub extern "C" fn streamify_dsp_get_lufs_mode() -> i32 {
    match shared().snapshot().lufs_mode {
        LufsMode::Normalize => 0,
        LufsMode::Bypass => 1,
    }
}
