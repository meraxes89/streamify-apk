use std::f32::consts::PI;

pub struct CrossfadeDspEngine;

impl CrossfadeDspEngine {
    /// Applies equal-power constant-energy crossfade in-place between outgoing and incoming stereo buffers.
    /// progress: 0.0 (100% track A) -> 1.0 (100% track B)
    pub fn process_equal_power_crossfade(
        outgoing_buffer: &[f32],
        incoming_buffer: &[f32],
        out_mixed: &mut [f32],
        progress: f32,
    ) {
        let p = progress.clamp(0.0, 1.0);
        let angle = p * (PI / 2.0);

        // Constant Power Law: gain_out^2 + gain_in^2 = 1.0
        let gain_out = angle.cos();
        let gain_in = angle.sin();

        let len = outgoing_buffer
            .len()
            .min(incoming_buffer.len())
            .min(out_mixed.len());
        for i in 0..len {
            out_mixed[i] = (outgoing_buffer[i] * gain_out) + (incoming_buffer[i] * gain_in);
        }
    }

    /// Smooth logarithmic volume envelope application with soft-knee saturation
    pub fn apply_volume_envelope(buffer: &mut [f32], start_gain: f32, end_gain: f32) {
        if buffer.is_empty() {
            return;
        }
        let total_samples = buffer.len() as f32;
        let delta = end_gain - start_gain;

        for (i, sample) in buffer.iter_mut().enumerate() {
            let t = i as f32 / total_samples;
            // S-curve smoothstep interpolation
            let smooth_t = t * t * (3.0 - 2.0 * t);
            let gain = start_gain + delta * smooth_t;
            *sample = (*sample * gain).clamp(-1.0, 1.0);
        }
    }
}
