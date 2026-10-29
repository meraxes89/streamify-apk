use std::f64::consts::PI;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum FilterType {
    LowPass,
    HighPass,
    BandPass,
    Notch,
    Peaking,
    LowShelf,
    HighShelf,
}

#[derive(Debug, Clone, Copy)]
pub struct BiquadFilter {
    b0: f64,
    b1: f64,
    b2: f64,
    a1: f64,
    a2: f64,
    // Direct Form II Transposed state registers (64-bit audio precision)
    s1: f64,
    s2: f64,
}

impl BiquadFilter {
    pub fn new() -> Self {
        Self {
            b0: 1.0,
            b1: 0.0,
            b2: 0.0,
            a1: 0.0,
            a2: 0.0,
            s1: 0.0,
            s2: 0.0,
        }
    }

    pub fn configure(
        &mut self,
        filter_type: FilterType,
        sample_rate: f64,
        center_freq: f64,
        q: f64,
        gain_db: f64,
    ) {
        let omega = 2.0 * PI * (center_freq / sample_rate);
        let sn = omega.sin();
        let cs = omega.cos();
        let alpha = sn / (2.0 * q);
        let a = 10.0f64.powf(gain_db / 40.0);

        let (b0, b1, b2, a0, a1, a2) = match filter_type {
            FilterType::Peaking => {
                let b0 = 1.0 + alpha * a;
                let b1 = -2.0 * cs;
                let b2 = 1.0 - alpha * a;
                let a0 = 1.0 + alpha / a;
                let a1 = -2.0 * cs;
                let a2 = 1.0 - alpha / a;
                (b0, b1, b2, a0, a1, a2)
            }
            FilterType::LowShelf => {
                let a_plus_1 = a + 1.0;
                let a_minus_1 = a - 1.0;
                let sqrt_a_2_alpha = 2.0 * a.sqrt() * alpha;

                let b0 = a * (a_plus_1 - a_minus_1 * cs + sqrt_a_2_alpha);
                let b1 = 2.0 * a * (a_minus_1 - a_plus_1 * cs);
                let b2 = a * (a_plus_1 - a_minus_1 * cs - sqrt_a_2_alpha);
                let a0 = a_plus_1 + a_minus_1 * cs + sqrt_a_2_alpha;
                let a1 = -2.0 * (a_minus_1 + a_plus_1 * cs);
                let a2 = a_plus_1 + a_minus_1 * cs - sqrt_a_2_alpha;
                (b0, b1, b2, a0, a1, a2)
            }
            FilterType::HighShelf => {
                let a_plus_1 = a + 1.0;
                let a_minus_1 = a - 1.0;
                let sqrt_a_2_alpha = 2.0 * a.sqrt() * alpha;

                let b0 = a * (a_plus_1 + a_minus_1 * cs + sqrt_a_2_alpha);
                let b1 = -2.0 * a * (a_minus_1 + a_plus_1 * cs);
                let b2 = a * (a_plus_1 + a_minus_1 * cs - sqrt_a_2_alpha);
                let a0 = a_plus_1 - a_minus_1 * cs + sqrt_a_2_alpha;
                let a1 = 2.0 * (a_minus_1 - a_plus_1 * cs);
                let a2 = a_plus_1 - a_minus_1 * cs - sqrt_a_2_alpha;
                (b0, b1, b2, a0, a1, a2)
            }
            FilterType::LowPass => {
                let b0 = (1.0 - cs) / 2.0;
                let b1 = 1.0 - cs;
                let b2 = (1.0 - cs) / 2.0;
                let a0 = 1.0 + alpha;
                let a1 = -2.0 * cs;
                let a2 = 1.0 - alpha;
                (b0, b1, b2, a0, a1, a2)
            }
            FilterType::HighPass => {
                let b0 = (1.0 + cs) / 2.0;
                let b1 = -(1.0 + cs);
                let b2 = (1.0 + cs) / 2.0;
                let a0 = 1.0 + alpha;
                let a1 = -2.0 * cs;
                let a2 = 1.0 - alpha;
                (b0, b1, b2, a0, a1, a2)
            }
            _ => (1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
        };

        // Normalize
        self.b0 = b0 / a0;
        self.b1 = b1 / a0;
        self.b2 = b2 / a0;
        self.a1 = a1 / a0;
        self.a2 = a2 / a0;
    }

    #[inline(always)]
    pub fn process_sample(&mut self, input: f64) -> f64 {
        let output = self.b0 * input + self.s1;
        self.s1 = self.b1 * input - self.a1 * output + self.s2;
        self.s2 = self.b2 * input - self.a2 * output;
        output
    }

    pub fn reset(&mut self) {
        self.s1 = 0.0;
        self.s2 = 0.0;
    }
}

impl Default for BiquadFilter {
    fn default() -> Self {
        Self::new()
    }
}

pub struct StudioEqualizer {
    filters: [BiquadFilter; 10],
    sample_rate: f64,
}

impl StudioEqualizer {
    pub const FREQUENCIES: [f64; 10] = [
        31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
    ];

    pub fn new(sample_rate: f64) -> Self {
        let mut eq = Self {
            filters: [BiquadFilter::new(); 10],
            sample_rate,
        };
        for (i, &freq) in Self::FREQUENCIES.iter().enumerate() {
            let ftype = if i == 0 {
                FilterType::LowShelf
            } else if i == 9 {
                FilterType::HighShelf
            } else {
                FilterType::Peaking
            };
            eq.filters[i].configure(ftype, sample_rate, freq, 1.414, 0.0);
        }
        eq
    }

    pub fn set_band_gain(&mut self, band_index: usize, gain_db: f64) {
        if band_index < 10 {
            let freq = Self::FREQUENCIES[band_index];
            let ftype = if band_index == 0 {
                FilterType::LowShelf
            } else if band_index == 9 {
                FilterType::HighShelf
            } else {
                FilterType::Peaking
            };
            self.filters[band_index].configure(ftype, self.sample_rate, freq, 1.414, gain_db);
        }
    }

    pub fn process_buffer_interleaved(&mut self, samples: &mut [f32], channels: usize) {
        for frame in samples.chunks_exact_mut(channels) {
            for sample in frame.iter_mut() {
                let mut s = *sample as f64;
                for filter in self.filters.iter_mut() {
                    s = filter.process_sample(s);
                }
                // Soft-knee analog saturation limiter to prevent digital clipping
                let saturated = (s * 0.95).tanh();
                *sample = saturated as f32;
            }
        }
    }
}

pub struct SpectrumVisualizer;

impl SpectrumVisualizer {
    /// Computes 64 logarithmic frequency energy bars from raw interleaved PCM floats
    pub fn compute_spectrum_bars(samples: &[f32], bar_count: usize) -> Vec<f32> {
        let n = samples.len().next_power_of_two().clamp(64, 1024);
        if samples.len() < n {
            return vec![0.0; bar_count];
        }

        // Apply Hann window
        let mut real: Vec<f64> = samples[..n]
            .iter()
            .enumerate()
            .map(|(i, &s)| {
                let w = 0.5 * (1.0 - (2.0 * PI * i as f64 / (n as f64 - 1.0)).cos());
                s as f64 * w
            })
            .collect();
        let mut imag = vec![0.0f64; n];

        // Radix-2 In-Place FFT
        Self::fft_inplace(&mut real, &mut imag);

        // Calculate power spectrum
        let half_n = n / 2;
        let mut magnitudes = Vec::with_capacity(half_n);
        for i in 0..half_n {
            let mag = (real[i] * real[i] + imag[i] * imag[i]).sqrt();
            magnitudes.push(mag as f32);
        }

        // Logarithmic frequency binning for aesthetic visualizer curves
        let mut bars = vec![0.0f32; bar_count];
        // Index math drives the log-scale bin edges — iterator form is worse.
        #[allow(clippy::needless_range_loop)]
        for i in 0..bar_count {
            let low_idx = (half_n as f32 * (i as f32 / bar_count as f32).powf(2.2)) as usize;
            let high_idx = ((half_n as f32 * ((i + 1) as f32 / bar_count as f32).powf(2.2))
                as usize)
                .max(low_idx + 1)
                .min(half_n);

            let mut sum = 0.0f32;
            let mut count = 0usize;
            #[allow(clippy::needless_range_loop)]
            for j in low_idx..high_idx {
                sum += magnitudes[j];
                count += 1;
            }
            let avg = if count > 0 { sum / count as f32 } else { 0.0 };
            // Decibel conversion with noise floor normalization
            bars[i] = (20.0 * (avg + 1e-5).log10() + 60.0).clamp(0.0, 100.0) / 100.0;
        }

        bars
    }

    fn fft_inplace(real: &mut [f64], imag: &mut [f64]) {
        let n = real.len();
        let mut j = 0;
        for i in 0..n {
            if i < j {
                real.swap(i, j);
                imag.swap(i, j);
            }
            let mut k = n / 2;
            while k <= j && k > 0 {
                j -= k;
                k /= 2;
            }
            j += k;
        }

        let mut len = 2;
        while len <= n {
            let half = len / 2;
            let angle = -2.0 * PI / len as f64;
            let w_step_real = angle.cos();
            let w_step_imag = angle.sin();

            let mut i = 0;
            while i < n {
                let mut w_real = 1.0;
                let mut w_imag = 0.0;
                for k in 0..half {
                    let u_real = real[i + k];
                    let u_imag = imag[i + k];
                    let v_real = real[i + k + half] * w_real - imag[i + k + half] * w_imag;
                    let v_imag = real[i + k + half] * w_imag + imag[i + k + half] * w_real;

                    real[i + k] = u_real + v_real;
                    imag[i + k] = u_imag + v_imag;
                    real[i + k + half] = u_real - v_real;
                    imag[i + k + half] = u_imag - v_imag;

                    let next_w_real = w_real * w_step_real - w_imag * w_step_imag;
                    let next_w_imag = w_real * w_step_imag + w_imag * w_step_real;
                    w_real = next_w_real;
                    w_imag = next_w_imag;
                }
                i += len;
            }
            len *= 2;
        }
    }
}
