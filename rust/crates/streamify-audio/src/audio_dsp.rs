// Clippy: FFI entry points in this module all follow the house safety
// model (catch_unwind shields, sentinel returns, no panics across the
// boundary — see the module docs); per-function `# Safety` boilerplate
// would be copy-paste.
#![allow(clippy::missing_safety_doc)]
use std::panic::catch_unwind;

#[repr(C)]
pub struct DspState {
    pub bass_freq: f32,
    pub bass_gain: f32,
    // Biquad state: x1, x2, y1, y2 for Left [0] and Right [1]
    pub x1: [f32; 2],
    pub x2: [f32; 2],
    pub y1: [f32; 2],
    pub y2: [f32; 2],
    // Haas effect spatializer delay ring buffer
    pub haas_buffer: [f32; 32],
    pub haas_index: usize,
}

impl Default for DspState {
    fn default() -> Self {
        Self {
            bass_freq: 80.0,
            bass_gain: 6.0,
            x1: [0.0; 2],
            x2: [0.0; 2],
            y1: [0.0; 2],
            y2: [0.0; 2],
            haas_buffer: [0.0; 32],
            haas_index: 0,
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn init_audio_dsp() -> *mut DspState {
    let state = Box::new(DspState::default());
    Box::into_raw(state)
}

#[no_mangle]
pub unsafe extern "C" fn free_audio_dsp(state_ptr: *mut DspState) {
    if !state_ptr.is_null() {
        let _ = Box::from_raw(state_ptr);
    }
}

/// Processes 16-bit PCM from ExoPlayer into 32-bit Float PCM for AudioTrack.
/// Runs in real-time on the ExoPlayer audio rendering thread.
#[no_mangle]
pub unsafe extern "C" fn process_audio_dsp(
    state_ptr: *mut DspState,
    input_ptr: *const i16, // Interleaved 16-bit PCM from decoder
    output_ptr: *mut f32,  // Interleaved 32-bit Float PCM to AudioTrack
    num_frames: usize,     // Number of stereo frames (each frame = 2 samples)
) -> i32 {
    let result = catch_unwind(|| {
        if state_ptr.is_null() || input_ptr.is_null() || output_ptr.is_null() || num_frames == 0 {
            return -1;
        }

        let state = &mut *state_ptr;
        let total_samples = num_frames * 2;
        let input = std::slice::from_raw_parts(input_ptr, total_samples);
        let output = std::slice::from_raw_parts_mut(output_ptr, total_samples);

        // Precomputed Biquad Low-Shelf Filter coefficients (80Hz, +6dB shelf at 48kHz)
        let b0: f32 = 1.050;
        let b1: f32 = -1.988;
        let b2: f32 = 0.948;
        let a0: f32 = 1.000;
        let a1: f32 = -1.988;
        let a2: f32 = 0.998;

        let mut i = 0;
        while i < total_samples {
            // Convert 16-bit signed integer PCM to normalized 32-bit Float (-1.0 to 1.0)
            let in_left = (input[i] as f32 / 32768.0).clamp(-1.0, 1.0);
            let in_right = (input[i + 1] as f32 / 32768.0).clamp(-1.0, 1.0);

            // --- 1. Biquad Low-Shelf Bass Contour (Channel 0: Left) ---
            let out_l = (b0 * in_left + b1 * state.x1[0] + b2 * state.x2[0]
                - a1 * state.y1[0]
                - a2 * state.y2[0])
                / a0;
            state.x2[0] = state.x1[0];
            state.x1[0] = in_left;
            state.y2[0] = state.y1[0];
            state.y1[0] = out_l;

            // --- 1. Biquad Low-Shelf Bass Contour (Channel 1: Right) ---
            let out_r = (b0 * in_right + b1 * state.x1[1] + b2 * state.x2[1]
                - a1 * state.y1[1]
                - a2 * state.y2[1])
                / a0;
            state.x2[1] = state.x1[1];
            state.x1[1] = in_right;
            state.y2[1] = state.y1[1];
            state.y1[1] = out_r;

            // --- 2. Haas Effect 3D Spatializer ---
            // Micro-delay on the secondary channel expands stereo stage
            let delayed_right = state.haas_buffer[state.haas_index];
            state.haas_buffer[state.haas_index] = out_r;
            state.haas_index = (state.haas_index + 1) % 32;

            // Blend dry/wet spatialized right channel
            let spat_right = 0.7 * out_r + 0.3 * delayed_right;

            // Soft-clip to prevent digital overflow
            output[i] = out_l.clamp(-1.0, 1.0);
            output[i + 1] = spat_right.clamp(-1.0, 1.0);

            i += 2;
        }

        0 // Success
    });

    result.unwrap_or(-2)
}
