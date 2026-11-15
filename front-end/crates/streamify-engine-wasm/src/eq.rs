//! eq.rs — the DSP frame ops on the engine facade (WS-W engine facade
//! breadth, work item 4).
//!
//! Mirrors `rust_process_equalizer_frame` + `rust_compute_fft_spectrum`
//! (rust/crates/streamify-ffi) onto wasm, from the SAME production DSP the
//! golden vectors pin (rust/crates/streamify-audio — path-depended
//! read-only, the streamify-golden-wasm precedent; that suite proves the
//! wasm build computes the same audio the native engine does).
//!
//! Both ops are FRAME functions (the data plane): callers hand the
//! AudioWorklet's PCM buffers in, get the processed buffer back. The EQ
//! keeps its filter STATE across calls in a module-global StudioEqualizer
//! (44.1 kHz, exactly like the FFI's GLOBAL_STUDIO_EQ) — the web data
//! plane is the AudioWorklet ring; this surface is the engine-quality
//! lane the opt-in spine and the upcoming EqualizerScreen consume.
//!
//! What is deliberately NOT here: the EqualizerScreen + DspSettingsSection
//! UI (the next track's consumers — the screens need the band-state
//! management surface first); LUFS normalization stays in the golden
//! pipeline (W1.7), not a per-frame JS call.

use std::sync::{Mutex, OnceLock};

use wasm_bindgen::prelude::*;

use streamify_audio::dsp::{StudioEqualizer, SpectrumVisualizer};

/// The house wasm clock of DSP truth: 44.1 kHz, matching the FFI's
/// GLOBAL_STUDIO_EQ (the Android app's frame lane).
const EQ_SAMPLE_RATE: f64 = 44_100.0;

fn global_eq() -> &'static Mutex<StudioEqualizer> {
    static EQ: OnceLock<Mutex<StudioEqualizer>> = OnceLock::new();
    EQ.get_or_init(|| Mutex::new(StudioEqualizer::new(EQ_SAMPLE_RATE)))
}

/// `rust_process_equalizer_frame` mirrored: apply the 10-band parametric EQ
/// to one interleaved PCM buffer IN PLACE. `gains` is the 10-band curve in
/// dB (applied before processing, exactly like the FFI's set_band_gain
/// loop); `channels` preserves the interleaving. Errors: empty PCM, zero
/// channels, or a gains curve that is not exactly 10 bands.
#[wasm_bindgen]
pub fn process_equalizer_frame(
    pcm: &mut [f32],
    channels: usize,
    gains: &[f32],
) -> Result<(), JsValue> {
    if pcm.is_empty() || channels == 0 {
        return Err(JsValue::from_str("dsp: empty PCM frame or zero channels"));
    }
    if gains.len() != 10 {
        return Err(JsValue::from_str("dsp: the EQ curve is exactly 10 bands (dB)"));
    }
    let mut guard = global_eq()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    for (idx, &gain) in gains.iter().enumerate() {
        guard.set_band_gain(idx, f64::from(gain));
    }
    guard.process_buffer_interleaved(pcm, channels);
    Ok(())
}

/// `rust_compute_fft_spectrum` mirrored: the spectrum bars for a
/// visualization frame. `bar_count` is clamped to >= 1; silence yields a
/// zero vector of the requested length.
#[wasm_bindgen]
pub fn compute_fft_spectrum(pcm: &[f32], bar_count: usize) -> Vec<f32> {
    let bars = bar_count.max(1);
    if pcm.is_empty() {
        return vec![0.0; bars];
    }
    SpectrumVisualizer::compute_spectrum_bars(pcm, bars)
}

#[cfg(all(target_arch = "wasm32", test))]
mod tests {
    use super::*;
    use wasm_bindgen_test::wasm_bindgen_test;

    /// A 0 dB curve is the identity lane (RMS preserved); a +12 dB low
    /// shelf measurably boosts the low band. The exact DSP is pinned by
    /// the golden vectors (W1.7) — this is the facade contract smoke.
    #[wasm_bindgen_test]
    fn eq_frame_identity_and_boost() {
        // 997 Hz stereo sine, one second — the golden suite's own vector.
        let frames = 44_100;
        let mut pcm: Vec<f32> = Vec::with_capacity(frames * 2);
        let mut phase = 0.0f64;
        for _ in 0..frames {
            let s = (std::f64::consts::TAU * phase).sin() * 0.5;
            pcm.push(s as f32);
            pcm.push(s as f32);
            phase += 997.0 / 44_100.0;
        }
        let rms = |x: &[f32]| -> f64 {
            (x.iter().map(|s| f64::from(*s) * f64::from(*s)).sum::<f64>() / x.len() as f64).sqrt()
        };
        let before = rms(&pcm);

        // Identity: 0 dB across all ten bands.
        let flat = [0.0f32; 10];
        let mut frame = pcm.clone();
        process_equalizer_frame(&mut frame, 2, &flat).expect("flat curve applies");
        let after_flat = rms(&frame);
        assert!(
            (after_flat - before).abs() <= 1e-4,
            "0 dB must be the identity lane: {after_flat} vs {before}"
        );

        // Boost: +12 dB on band 0 (the 31 Hz shelf) lifts the curve.
        let mut hot = [0.0f32; 10];
        hot[0] = 12.0;
        let mut boosted = pcm.clone();
        process_equalizer_frame(&mut boosted, 2, &hot).expect("boost applies");
        assert!(rms(&boosted) > before, "a +12 dB shelf must add energy");

        // Malformed: wrong band count / empty frame throw honestly.
        assert!(process_equalizer_frame(&mut frame, 2, &[0.0; 9]).is_err());
        assert!(process_equalizer_frame(&mut [], 2, &flat).is_err());
    }

    /// FFT: silence → zero bars; a 997 Hz tone concentrates energy in the
    /// bar containing 997 Hz (of a 44.1 kHz frame).
    #[wasm_bindgen_test]
    fn fft_spectrum_bars_contract() {
        let silence: Vec<f32> = vec![0.0; 2048];
        assert!(compute_fft_spectrum(&silence, 24).iter().all(|b| *b == 0.0));

        let mut tone: Vec<f32> = Vec::with_capacity(44_100 * 2);
        let mut phase = 0.0f64;
        for _ in 0..44_100 {
            let s = (std::f64::consts::TAU * phase).sin() * 0.5;
            tone.push(s as f32);
            tone.push(s as f32);
            phase += 997.0 / 44_100.0;
        }
        let bars = compute_fft_spectrum(&tone, 32);
        assert_eq!(bars.len(), 32);
        // The 997 Hz bin lives in bar floor(997 / (44100/2 / 32)) = 1.
        let peak = bars
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
            .map(|(i, _)| i)
            .unwrap_or(0);
        assert!(peak <= 2, "the 997 Hz tone must concentrate near the low bars, got {peak}");
        assert!(bars[1] > 0.0, "bar 1 carries the 997 Hz energy");
    }
}
