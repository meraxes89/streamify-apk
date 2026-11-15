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

    /// The flat curve attenuates slightly (the soft-knee saturation lane —
    /// tanh(0.95·s) — is applied UNCONDITIONALLY in the production EQ, so
    /// 0 dB is NOT passthrough; the golden vectors absorb exactly this);
    /// a +12 dB peaking band at 1 kHz (FREQUENCIES[5]) measurably boosts
    /// the 997 Hz tone. Malformed inputs throw.
    #[wasm_bindgen_test]
    fn eq_frame_attenuation_and_boost() {
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

        // Flat: 0 dB across all ten bands. The saturation lane attenuates
        // (0.95 gain minus the tanh curvature) — strictly below the input.
        let flat = [0.0f32; 10];
        let mut frame = pcm.clone();
        process_equalizer_frame(&mut frame, 2, &flat).expect("flat curve applies");
        let after_flat = rms(&frame);
        assert!(
            after_flat < before,
            "the saturation lane must attenuate: {after_flat} vs {before}"
        );
        assert!(
            after_flat > before * 0.75,
            "a 0.5-amplitude sine stays in the tanh's near-linear region: {after_flat} vs {before}"
        );

        // Boost: +12 dB on band 5 (the 1 kHz PEAKING band) lifts the 997 Hz
        // tone far above the flat lane.
        let mut hot = [0.0f32; 10];
        hot[5] = 12.0;
        let mut boosted = pcm.clone();
        process_equalizer_frame(&mut boosted, 2, &hot).expect("boost applies");
        assert!(rms(&boosted) > after_flat, "a +12 dB peak at 1 kHz must add energy");

        // Malformed: wrong band count / empty frame throw honestly.
        assert!(process_equalizer_frame(&mut frame, 2, &[0.0; 9]).is_err());
        assert!(process_equalizer_frame(&mut [], 2, &flat).is_err());
    }

    /// FFT: silence → zero bars; a 997 Hz tone concentrates its energy in
    /// ONE strong bar in the lower half of the panel (the power-2.2 bin
    /// mapping puts ~1 kHz around bar 10-11 of 32).
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
        let peak = bars
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.partial_cmp(b.1).unwrap())
            .map(|(i, _)| i)
            .unwrap_or(0);
        let peak_value = bars[peak];
        // One dominant bar in the lower half (the tone's home), saturated
        // near 1.0 by the dB normalization.
        assert!(peak > 0 && peak < 16, "the 997 Hz tone peaks in the lower half, got bar {peak}");
        assert!(peak_value > 0.5, "a −14 dBFS tone saturates its bar, got {peak_value}");
    }
}
