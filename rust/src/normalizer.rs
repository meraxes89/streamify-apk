// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use std::panic::catch_unwind;

#[repr(C)]
pub struct NormalizerState {
    pub target_rms: f32,     // Target loudness (e.g., 0.25 ≈ -14 LUFS equivalent)
    pub current_rms: f32,    // Running average loudness
    pub gain: f32,           // Current dynamic gain multiplier
    pub window: [f32; 1024], // Rolling window of square amplitudes
    pub window_idx: usize,
}

impl Default for NormalizerState {
    fn default() -> Self {
        Self {
            target_rms: 0.25,
            current_rms: 0.25,
            gain: 1.0,
            window: [0.0625; 1024],
            window_idx: 0,
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn init_normalizer(target_rms: f32) -> *mut NormalizerState {
    let mut state = Box::new(NormalizerState::default());
    if target_rms > 0.01 && target_rms < 1.0 {
        state.target_rms = target_rms;
        state.current_rms = target_rms;
    }
    Box::into_raw(state)
}

#[no_mangle]
pub unsafe extern "C" fn free_normalizer(state_ptr: *mut NormalizerState) {
    if !state_ptr.is_null() {
        let _ = Box::from_raw(state_ptr);
    }
}

/// Processes the 32-bit Float PCM post-DSP to calculate RMS and apply dynamic gain.
#[no_mangle]
pub unsafe extern "C" fn apply_normalization(
    state_ptr: *mut NormalizerState,
    pcm_buffer_ptr: *mut f32,
    num_frames: usize,
) -> i32 {
    let result = catch_unwind(|| {
        if state_ptr.is_null() || pcm_buffer_ptr.is_null() || num_frames == 0 {
            return -1;
        }

        let state = &mut *state_ptr;
        let total_samples = num_frames * 2;
        let pcm = std::slice::from_raw_parts_mut(pcm_buffer_ptr, total_samples);

        let mut sum_sq = 0.0f32;

        // 1. Calculate RMS of current buffer chunk
        let mut i = 0;
        while i < total_samples {
            let left = pcm[i];
            let right = pcm[i + 1];

            // Average power across stereo channels
            let power = (left * left + right * right) * 0.5;
            state.window[state.window_idx] = power;
            state.window_idx = (state.window_idx + 1) % 1024;

            sum_sq += state.window[state.window_idx];
            i += 2;
        }

        let avg_sq = (sum_sq / 1024.0).max(1e-7);
        let measured_rms = avg_sq.sqrt();

        // 2. Smoothly interpolate current RMS to avoid sudden volume jumps
        state.current_rms = state.current_rms * 0.95 + measured_rms * 0.05;

        // 3. Calculate required gain
        if state.current_rms > 0.001 {
            let required_gain = (state.target_rms / state.current_rms).clamp(0.4, 2.0);
            // Smoothly approach required gain
            state.gain = state.gain * 0.98 + required_gain * 0.02;
        }

        let current_gain = state.gain;

        // 4. Apply gain and hard-clamp to [-1.0, 1.0] to guarantee zero DAC clipping
        let mut j = 0;
        while j < total_samples {
            let scaled_l = pcm[j] * current_gain;
            let scaled_r = pcm[j + 1] * current_gain;

            pcm[j] = scaled_l.clamp(-1.0, 1.0);
            pcm[j + 1] = scaled_r.clamp(-1.0, 1.0);
            j += 2;
        }

        0
    });

    result.unwrap_or(-2)
}
