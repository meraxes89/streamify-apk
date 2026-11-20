#include "LyricAligner.h"
#include <cmath>
#include <algorithm>
#include <cstring>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

LyricAligner& LyricAligner::getInstance() {
    static LyricAligner instance(30);
    return instance;
}

LyricAligner::LyricAligner(size_t max_window_seconds) {
    max_bins_ = max_window_seconds * 100; // 100 Hz = 3000 bins for 30s
    // Find next power of 2 for FFT
    size_t nfft = 1;
    while (nfft < max_bins_ * 2) nfft <<= 1;
    max_bins_ = nfft;

    fft_cfg_ = kiss_fftr_alloc(static_cast<int>(max_bins_), 0, nullptr, nullptr);
    ifft_cfg_ = kiss_fftr_alloc(static_cast<int>(max_bins_), 1, nullptr, nullptr);

    // Biquad Bandpass Coeffs (fc1 = 300Hz, fc2 = 3400Hz at fs = 48000Hz)
    b0_ = 0.1804f; b1_ = 0.0f; b2_ = -0.1804f;
    a1_ = -1.5830f; a2_ = 0.6392f;
    z1_1_ = z2_1_ = z1_2_ = z2_2_ = 0.0f;
}

LyricAligner::~LyricAligner() {
    if (fft_cfg_) kiss_fftr_free(fft_cfg_);
    if (ifft_cfg_) kiss_fftr_free(ifft_cfg_);
}

void LyricAligner::extractVocalFormants(float* samples, size_t count) {
    // 2-pass biquad cascade (4th order total)
    for (size_t i = 0; i < count; ++i) {
        float in = samples[i];
        // Stage 1
        float out1 = b0_ * in + z1_1_;
        z1_1_ = b1_ * in - a1_ * out1 + z2_1_;
        z2_1_ = b2_ * in - a2_ * out1;

        // Stage 2
        float out2 = b0_ * out1 + z1_2_;
        z1_2_ = b1_ * out1 - a1_ * out2 + z2_2_;
        z2_2_ = b2_ * out1 - a2_ * out2;

        samples[i] = out2;
    }
}

std::vector<float> LyricAligner::computeEnvelope100Hz(const float* samples, size_t count, size_t sample_rate) {
    if (sample_rate == 0) sample_rate = 48000;
    size_t samples_per_bucket = sample_rate / 100; // 480 samples for 48kHz
    if (samples_per_bucket == 0) samples_per_bucket = 1;
    size_t bucket_count = count / samples_per_bucket;
    std::vector<float> envelope(bucket_count, 0.0f);

    for (size_t b = 0; b < bucket_count; ++b) {
        float sum_sq = 0.0f;
        size_t start = b * samples_per_bucket;
        for (size_t i = 0; i < samples_per_bucket; ++i) {
            float s = samples[start + i];
            sum_sq += s * s;
        }
        envelope[b] = std::sqrt(sum_sq / static_cast<float>(samples_per_bucket));
    }
    return envelope;
}

int LyricAligner::calculateDriftOffset(const float* vocal_energy_100hz, size_t vocal_len,
                                       const float* lyric_onsets_100hz, size_t lyric_len) {
    if (!vocal_energy_100hz || !lyric_onsets_100hz || vocal_len == 0 || lyric_len == 0 || !fft_cfg_ || !ifft_cfg_) {
        return 0;
    }

    size_t n = max_bins_;
    std::vector<float> in_vocal(n, 0.0f);
    std::vector<float> in_lyric(n, 0.0f);

    std::memcpy(in_vocal.data(), vocal_energy_100hz, std::min(vocal_len, n) * sizeof(float));
    std::memcpy(in_lyric.data(), lyric_onsets_100hz, std::min(lyric_len, n) * sizeof(float));

    std::vector<kiss_fft_cpx> freq_vocal(n / 2 + 1);
    std::vector<kiss_fft_cpx> freq_lyric(n / 2 + 1);
    std::vector<kiss_fft_cpx> freq_cross(n / 2 + 1);

    // Forward Real FFTs
    kiss_fftr(fft_cfg_, in_vocal.data(), freq_vocal.data());
    kiss_fftr(fft_cfg_, in_lyric.data(), freq_lyric.data());

    // Spectral Multiply: Z[k] = Vocal[k] * Conj(Lyric[k])
    for (size_t k = 0; k <= n / 2; ++k) {
        float a = freq_vocal[k].r;
        float b = freq_vocal[k].i;
        float c = freq_lyric[k].r;
        float d = -freq_lyric[k].i; // Complex conjugate

        freq_cross[k].r = (a * c - b * d) / static_cast<float>(n);
        freq_cross[k].i = (a * d + b * c) / static_cast<float>(n);
    }

    // Inverse Real FFT
    std::vector<float> cross_corr(n);
    kiss_fftri(ifft_cfg_, freq_cross.data(), cross_corr.data());

    // Find peak lag index
    size_t peak_idx = 0;
    float max_val = -1e9f;
    for (size_t i = 0; i < n; ++i) {
        if (cross_corr[i] > max_val) {
            max_val = cross_corr[i];
            peak_idx = i;
        }
    }

    // Convert circular lag to signed shift (10ms resolution)
    int shift_bins = (peak_idx > n / 2) ? static_cast<int>(peak_idx) - static_cast<int>(n)
                                        : static_cast<int>(peak_idx);

    return shift_bins * 10; // Return offset in milliseconds
}

void LyricAligner::applyVocalBandpass(const float* input, float* output, int count, float sampleRate) {
    if (count <= 0 || sampleRate <= 0.0f) return;

    // 1. Highpass at 300 Hz, Q = 0.707
    const float f_hp = 300.0f;
    const float q_hp = 0.7071f;
    const float w0_hp = 2.0f * static_cast<float>(M_PI) * f_hp / sampleRate;
    const float alpha_hp = sinf(w0_hp) / (2.0f * q_hp);
    const float cos_w0_hp = cosf(w0_hp);

    const float b0_hp = (1.0f + cos_w0_hp) / 2.0f;
    const float b1_hp = -(1.0f + cos_w0_hp);
    const float b2_hp = (1.0f + cos_w0_hp) / 2.0f;
    const float a0_hp = 1.0f + alpha_hp;
    const float a1_hp = -2.0f * cos_w0_hp;
    const float a2_hp = 1.0f - alpha_hp;

    // 2. Lowpass at 3400 Hz, Q = 0.707
    const float f_lp = 3400.0f;
    const float q_lp = 0.7071f;
    const float w0_lp = 2.0f * static_cast<float>(M_PI) * f_lp / sampleRate;
    const float alpha_lp = sinf(w0_lp) / (2.0f * q_lp);
    const float cos_w0_lp = cosf(w0_lp);

    const float b0_lp = (1.0f - cos_w0_lp) / 2.0f;
    const float b1_lp = 1.0f - cos_w0_lp;
    const float b2_lp = (1.0f - cos_w0_lp) / 2.0f;
    const float a0_lp = 1.0f + alpha_lp;
    const float a1_lp = -2.0f * cos_w0_lp;
    const float a2_lp = 1.0f - alpha_lp;

    float hp_x1 = 0.0f, hp_x2 = 0.0f, hp_y1 = 0.0f, hp_y2 = 0.0f;
    float lp_x1 = 0.0f, lp_x2 = 0.0f, lp_y1 = 0.0f, lp_y2 = 0.0f;

    for (int i = 0; i < count; ++i) {
        float x = input[i];

        // Highpass stage
        float y_hp = (b0_hp * x + b1_hp * hp_x1 + b2_hp * hp_x2 - a1_hp * hp_y1 - a2_hp * hp_y2) / a0_hp;
        hp_x2 = hp_x1;
        hp_x1 = x;
        hp_y2 = hp_y1;
        hp_y1 = y_hp;

        // Lowpass stage
        float y_lp = (b0_lp * y_hp + b1_lp * lp_x1 + b2_lp * lp_x2 - a1_lp * lp_y1 - a2_lp * lp_y2) / a0_lp;
        lp_x2 = lp_x1;
        lp_x1 = y_hp;
        lp_y2 = lp_y1;
        lp_y1 = y_lp;

        output[i] = y_lp;
    }
}

int32_t LyricAligner::calculateDriftMs(
    const float* pcm,
    int numSamples,
    int sampleRate,
    int channelCount,
    const uint32_t* textOnsetsMs,
    int onsetCount
) {
    if (!pcm || numSamples <= 0 || sampleRate <= 0 || !textOnsetsMs || onsetCount <= 0) {
        return 0;
    }

    int monoSampleCount = (channelCount > 1) ? (numSamples / channelCount) : numSamples;
    std::vector<float> monoPcm(monoSampleCount);

    if (channelCount > 1) {
        for (int i = 0; i < monoSampleCount; ++i) {
            float sum = 0.0f;
            for (int c = 0; c < channelCount; ++c) {
                sum += pcm[i * channelCount + c];
            }
            monoPcm[i] = sum / static_cast<float>(channelCount);
        }
    } else {
        std::memcpy(monoPcm.data(), pcm, monoSampleCount * sizeof(float));
    }

    std::vector<float> vocalPcm(monoSampleCount);
    applyVocalBandpass(monoPcm.data(), vocalPcm.data(), monoSampleCount, static_cast<float>(sampleRate));

    std::vector<float> audioEnergy = computeEnvelope100Hz(vocalPcm.data(), monoSampleCount, sampleRate);
    if (audioEnergy.empty()) return 0;

    std::vector<float> textImpulses(audioEnergy.size(), 0.0f);
    for (int i = 0; i < onsetCount; ++i) {
        uint32_t onsetMs = textOnsetsMs[i];
        size_t bucketIdx = static_cast<size_t>(onsetMs / 10);
        if (bucketIdx < textImpulses.size()) {
            textImpulses[bucketIdx] += 1.0f;
            if (bucketIdx > 0) textImpulses[bucketIdx - 1] += 0.5f;
            if (bucketIdx + 1 < textImpulses.size()) textImpulses[bucketIdx + 1] += 0.5f;
        }
    }

    return calculateDriftOffset(audioEnergy.data(), audioEnergy.size(), textImpulses.data(), textImpulses.size());
}
