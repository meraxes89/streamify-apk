#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>
#include "kissfft/kiss_fftr.h"

class LyricAligner {
public:
    LyricAligner(size_t max_window_seconds = 30);
    ~LyricAligner();

    // In-place vocal formant bandpass filter (300Hz - 3.4kHz)
    void extractVocalFormants(float* samples, size_t count);

    // Downsamples PCM energy into 100 Hz envelope buckets (10ms resolution)
    std::vector<float> computeEnvelope100Hz(const float* samples, size_t count, size_t sample_rate);

    // Wiener-Khinchin FFT Cross-Correlation: computes peak drift offset (in ms)
    int calculateDriftOffset(const float* vocal_energy_100hz, size_t vocal_len,
                             const float* lyric_onsets_100hz, size_t lyric_len);

    // Backward compatibility helper
    static LyricAligner& getInstance();
    int32_t calculateDriftMs(
        const float* pcm,
        int numSamples,
        int sampleRate,
        int channelCount,
        const uint32_t* textOnsetsMs,
        int onsetCount
    );

private:
    size_t max_bins_;
    kiss_fftr_cfg fft_cfg_;
    kiss_fftr_cfg ifft_cfg_;

    // 4th-Order Butterworth Bandpass Coefficients (300Hz - 3.4kHz @ 48kHz)
    float b0_, b1_, b2_, a1_, a2_;
    float z1_1_, z2_1_, z1_2_, z2_2_;

    void applyVocalBandpass(const float* input, float* output, int count, float sampleRate);
};
