#include "LufsNormalizer.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include "../util/Finite.h"

// ============================================================================
//  ITU-R BS.1770-4 / EBU R128 loudness engine — implementation
// ============================================================================

namespace {

constexpr double kLn10 = 2.30258509299404568402;   // ln(10)
inline double luFromSum(double sum) {              // -0.691 + 10*log10(sum)
    return -0.691 + 10.0 * std::log(sum) / kLn10;
}

// Channel weights (BS.1770-4 loudness formula): L/R/C 1.0, Ls/Rs 1.41.
// Channels beyond a 7.1 layout default to 1.0.
constexpr double kChannelWeight[8] = {1.0, 1.0, 1.0, 1.41, 1.41, 1.41, 1.41, 1.0};

// Identity-below-threshold C1 tanh guard used by the legacy one-shot path.
// NaN/Inf check via bit pattern: std::isfinite folds to `true` under the
// prod -ffinite-math-only build (audit #22 item 2) — see util/Finite.h.
inline float softGuard099(float x) {
    if (!streamify::util::f32_is_finite(x)) return 0.0f;
    constexpr float T = 0.99f, D = 0.004f * T;
    if (x <= T && x >= -T) return x;
    const float a = (x > 0.0f) ? T : -T;
    const float d = (x > 0.0f) ? D : -D;
    return a + d * std::tanh((x - a) / d);
}

}  // namespace

LufsNormalizer& LufsNormalizer::getInstance() {
    static LufsNormalizer instance;
    return instance;
}

LufsNormalizer::LufsNormalizer() {
    // NOTE: cannot just call configure() here — the geometry members already
    // hold 48 kHz / 2ch defaults, so the change-detection early-return would
    // skip the filter build and leave the biquads zero. Build explicitly.
    buildKFilters(48000);
    hopFrames_ = 48000 / 10;                       // 100 ms hop
    glideAlpha_ = 1.0 - std::exp(-1.0 / (0.025 * 48000));
    reset();
}

// ---------------------------------------------------------------------------
// K-filter construction
// ---------------------------------------------------------------------------
void LufsNormalizer::designKFilters(int fs, BiquadCoeffs* stage1,
                                    BiquadCoeffs* stage2) {
    if (fs == 48000) {
        // ITU-R BS.1770-4 Annex 1, exact spec literals.
        *stage1 = BiquadCoeffs{1.53512485958697f, -2.69169618940638f,
                                1.19839251015039f, -1.69065929318241f,
                                0.73248077421585f};
        *stage2 = BiquadCoeffs{1.0f, -2.0f, 1.0f, -1.99004745483398f,
                                0.99007225036621f};
        return;
    }
    // De Man fit (validated at 48 kHz against the spec literals in tests):
    // reproduces them to <2.5e-3 and stays inside the ITU tolerance masks.
    const double pi = 3.14159265358979323846;

    // Stage 1: high shelf, f0 = 1681.974450955533 Hz, G = +3.999843853973347 dB,
    // Q = 0.7071752369554196.
    {
        const double f0 = 1681.974450955533;
        const double G = 3.999843853973347;
        const double Q = 0.7071752369554196;
        const double K = std::tan(pi * f0 / static_cast<double>(fs));
        const double Vh = std::pow(10.0, G / 20.0);
        const double Vb = std::pow(Vh, 0.4996665557690893);
        const double a0 = 1.0 + K / Q + K * K;
        *stage1 = BiquadCoeffs{
            static_cast<float>((Vh + Vb * (K / Q) + K * K) / a0),
            static_cast<float>(2.0 * (K * K - Vh) / a0),
            static_cast<float>((Vh - Vb * (K / Q) + K * K) / a0),
            static_cast<float>(2.0 * (K * K - 1.0) / a0),
            static_cast<float>((1.0 - K / Q + K * K) / a0)};
    }
    // Stage 2: RLB high pass, f0 = 38.13547087602444 Hz, Q = 0.5003270373238773.
    {
        const double f0 = 38.13547087602444;
        const double Q = 0.5003270373238773;
        const double K = std::tan(pi * f0 / static_cast<double>(fs));
        const double a0 = 1.0 + K / Q + K * K;
        *stage2 = BiquadCoeffs{
            static_cast<float>(1.0 / a0),
            static_cast<float>(-2.0 / a0),
            static_cast<float>(1.0 / a0),
            static_cast<float>(2.0 * (K * K - 1.0) / a0),
            static_cast<float>((1.0 - K / Q + K * K) / a0)};
    }
}

void LufsNormalizer::buildKFilters(int fs) {
    BiquadCoeffs shelf, hp;
    designKFilters(fs, &shelf, &hp);
    for (int c = 0; c < kMaxChannels; ++c) {
        k1_[c] = shelf;
        k2_[c] = hp;
    }
}

// ---------------------------------------------------------------------------
// Streaming engine
// ---------------------------------------------------------------------------
void LufsNormalizer::configure(int sampleRateHz, int channels) {
    sampleRateHz = std::clamp(sampleRateHz, 8000, 192000);
    channels = std::clamp(channels, 1, kMaxChannels);
    if (sampleRateHz == sampleRateHz_ && channels == channels_) return;

    sampleRateHz_ = sampleRateHz;
    channels_ = channels;
    hopFrames_ = std::max(1, sampleRateHz / 10);   // 100 ms
    glideAlpha_ = 1.0 - std::exp(-1.0 / (0.025 * sampleRateHz_));  // tau = 25 ms
    buildKFilters(sampleRateHz_);
    reset();
}

void LufsNormalizer::setTargetLufs(float targetLufs) {
    // Bit-pattern NaN guard FIRST (audit #22 item 2): clamp() is UB on
    // NaN under prod -ffinite-math-only, and the old `!(x >= lo)`
    // backstop elides — sanitize the INPUT, then clamp.
    targetLufs_ = std::clamp(
        streamify::util::f32_finite_or(targetLufs, -14.0f),
        kMinTargetLufs, kMaxTargetLufs);
}

void LufsNormalizer::setMaxCorrectionDb(float maxDb) {
    maxCorrectionDb_ = std::clamp(
        streamify::util::f32_finite_or(maxDb, 12.0f), 0.5f, 24.0f);
}

void LufsNormalizer::reset() {
    for (int c = 0; c < kMaxChannels; ++c) {
        kz1_[c] = kz2_[c] = kz3_[c] = kz4_[c] = 0.0;
        chunkEnergy_[c] = 0.0;
        for (int k = 0; k < kBlockChunks; ++k) chunkRing_[k][c] = 0.0;
        for (int k = 0; k < kShortTermChunks; ++k) shortRing_[k][c] = 0.0;
    }
    framesInChunk_ = 0;
    chunksClosed_ = 0;
    streamFrames_ = 0;
    shortValid_ = 0;
    blockCount_ = 0;
    lastBlockLufs_ = -120.0;
    cachedIntegrated_ = -120.0;
    integratedDirty_ = true;
    gainDb_ = 0.0f;
    holdGainDb_ = 0.0f;
    curGainLin_ = 1.0f;
    legZ1_ = legZ2_ = legZ3_ = legZ4_ = 0.0;
}

void LufsNormalizer::pushBlock(double sum) {
    if (blockCount_ == kBlockCapacity) {
        // Pairwise merge (energy mean): halves occupancy; integrated error
        // stays <0.02 LU (documented in the header).
        constexpr uint32_t half = kBlockCapacity / 2;
        for (uint32_t i = 0; i < half; ++i) {
            blockSum_[i] = 0.5 * (blockSum_[2 * i] + blockSum_[2 * i + 1]);
        }
        blockCount_ = half;
    }
    blockSum_[blockCount_++] = sum;
    integratedDirty_ = true;
}

double LufsNormalizer::gatedIntegrated() const {
    if (blockCount_ == 0) return -120.0;
    if (!integratedDirty_) return cachedIntegrated_;

    // Pass 1: absolute gate (-70 LUFS).
    const double absThresh = std::pow(10.0, (-70.0 + 0.691) / 10.0);
    double acc = 0.0;
    uint32_t n = 0;
    for (uint32_t i = 0; i < blockCount_; ++i) {
        if (blockSum_[i] > absThresh) { acc += blockSum_[i]; ++n; }
    }
    if (n == 0) { cachedIntegrated_ = -120.0; integratedDirty_ = false; return -120.0; }
    const double preliminary = luFromSum(acc / n);

    // Pass 2: relative gate (10 LU below the preliminary integrated).
    const double relThresh = std::pow(10.0, (preliminary - 10.0 + 0.691) / 10.0);
    double acc2 = 0.0;
    uint32_t n2 = 0;
    for (uint32_t i = 0; i < blockCount_; ++i) {
        if (blockSum_[i] > absThresh && blockSum_[i] > relThresh) {
            acc2 += blockSum_[i]; ++n2;
        }
    }
    cachedIntegrated_ = (n2 == 0) ? preliminary : luFromSum(acc2 / n2);
    integratedDirty_ = false;
    return cachedIntegrated_;
}

float LufsNormalizer::integratedLufs() const {
    return static_cast<float>(gatedIntegrated());
}

float LufsNormalizer::momentaryLufs() const {
    return static_cast<float>(lastBlockLufs_);
}

float LufsNormalizer::shortTermLufs() const {
    if (shortValid_ == 0) return -120.0f;
    const double spanFrames = static_cast<double>(shortValid_) * hopFrames_;
    double sum = 0.0;
    for (int c = 0; c < channels_; ++c) {
        double e = 0.0;
        for (int k = 0; k < kShortTermChunks; ++k) e += shortRing_[k][c];
        sum += kChannelWeight[c] * (e / spanFrames);
    }
    if (sum <= 1e-12) return -120.0f;
    return static_cast<float>(luFromSum(sum));
}

void LufsNormalizer::updateGainSetpoint() {
    const double integrated = gatedIntegrated();
    if (integrated <= -60.0) return;   // below hold threshold: freeze the glide
    const double targetDb = std::clamp(
        static_cast<double>(targetLufs_) - integrated,
        -static_cast<double>(maxCorrectionDb_),
        static_cast<double>(maxCorrectionDb_));
    // EMA in the dB domain, tau = 0.5 s at the 10 Hz chunk rate.
    // (const, not constexpr: NDK clang's libc++ marks ::exp non-constexpr.)
    const double kEmaPerHop = 1.0 - std::exp(-0.1 / 0.5);
    gainDb_ = static_cast<float>(
        static_cast<double>(gainDb_) +
        (targetDb - static_cast<double>(gainDb_)) * kEmaPerHop);
    holdGainDb_ = gainDb_;
}

void LufsNormalizer::closeChunk() {
    // Move the closed chunk into the two rings.
    const int slot = static_cast<int>(chunksClosed_ % kBlockChunks);
    const int stSlot = static_cast<int>(chunksClosed_ % kShortTermChunks);
    for (int c = 0; c < channels_; ++c) {
        chunkRing_[slot][c] = chunkEnergy_[c];
        shortRing_[stSlot][c] = chunkEnergy_[c];
        chunkEnergy_[c] = 0.0;
    }
    if (shortValid_ < kShortTermChunks) ++shortValid_;
    ++chunksClosed_;
    framesInChunk_ = 0;

    // A full block (4 consecutive chunks) is now finalizable.
    if (chunksClosed_ >= kBlockChunks) {
        const double blockFrames = 4.0 * hopFrames_;
        double sum = 0.0;
        for (int c = 0; c < channels_; ++c) {
            double e = 0.0;
            for (int k = 0; k < kBlockChunks; ++k) e += chunkRing_[k][c];
            sum += kChannelWeight[c] * (e / blockFrames);
        }
        if (sum > 1e-24) lastBlockLufs_ = luFromSum(sum);
        else lastBlockLufs_ = -120.0;
        pushBlock(sum);
    }
    updateGainSetpoint();
}

void LufsNormalizer::processStream(float* interleaved, int frames) {
    if (interleaved == nullptr || frames <= 0) return;
    const int C = channels_;
    const float targetLin = std::pow(10.0f, gainDb_ / 20.0f);
    const double a = glideAlpha_;

    float* p = interleaved;
    for (int f = 0; f < frames; ++f) {
        // Measure the *pre-gain* signal (input as received).
        for (int c = 0; c < C; ++c) {
            const double x = static_cast<double>(p[c]);
            // Stage 1 (high shelf), DF2T with double state.
            const double y1 = k1_[c].b0 * x + kz1_[c];
            kz1_[c] = k1_[c].b1 * x - k1_[c].a1 * y1 + kz2_[c];
            kz2_[c] = k1_[c].b2 * x - k1_[c].a2 * y1;
            // Stage 2 (RLB high pass).
            const double y2 = k2_[c].b0 * y1 + kz3_[c];
            kz3_[c] = k2_[c].b1 * y1 - k2_[c].a1 * y2 + kz4_[c];
            kz4_[c] = k2_[c].b2 * y1 - k2_[c].a2 * y2;
            chunkEnergy_[c] += y2 * y2;
        }
        // Apply the gliding gain (both channels share the same glide value).
        curGainLin_ += (targetLin - curGainLin_) * static_cast<float>(a);
        const float g = curGainLin_;
        for (int c = 0; c < C; ++c) p[c] *= g;

        ++streamFrames_;
        if (++framesInChunk_ >= hopFrames_) closeChunk();
        p += C;
    }
}

// ---------------------------------------------------------------------------
// Legacy API
// ---------------------------------------------------------------------------
void LufsNormalizer::processChannelSIMD(float* samples, size_t count) {
    if (samples == nullptr || count == 0) return;
    const BiquadCoeffs& s1 = k1_[0];
    const BiquadCoeffs& s2 = k2_[0];
    for (size_t i = 0; i < count; ++i) {
        const double x = static_cast<double>(samples[i]);
        const double y1 = s1.b0 * x + legZ1_;
        legZ1_ = s1.b1 * x - s1.a1 * y1 + legZ2_;
        legZ2_ = s1.b2 * x - s1.a2 * y1;
        const double y2 = s2.b0 * y1 + legZ3_;
        legZ3_ = s2.b1 * y1 - s2.a1 * y2 + legZ4_;
        legZ4_ = s2.b2 * y1 - s2.a2 * y2;
        samples[i] = static_cast<float>(y2);
    }
}

float LufsNormalizer::computeIntegratedLufs(const float* const* channel_data,
                                            size_t num_channels,
                                            size_t num_samples,
                                            int sampleRateHz) {
    if (channel_data == nullptr || num_channels == 0 || num_samples == 0) {
        return -70.0f;
    }
    num_channels = std::min(num_channels, static_cast<size_t>(kMaxChannels));

    // One-shot through the calibrated engine (interleave into a scratch —
    // this is a legacy, non-hot path).
    std::vector<float> scratch(num_channels * num_samples);
    for (size_t c = 0; c < num_channels; ++c) {
        const float* src = channel_data[c];
        if (src == nullptr) return -70.0f;
        for (size_t i = 0; i < num_samples; ++i) {
            scratch[i * num_channels + c] = src[i];
        }
    }
    LufsNormalizer meter;
    // Audit #22 item 9: design the one-shot meter at the CALLER's rate,
    // not a hardcoded 48 kHz — the K-weighting and the 100 ms hop
    // ladder are both rate-dependent.
    meter.configure(sampleRateHz, static_cast<int>(num_channels));
    // Feed in hop-sized chunks so blocks finalize exactly as in streaming.
    const int hop = meter.hopFrames_;
    size_t fed = 0;
    while (fed < num_samples) {
        const size_t n = std::min<size_t>(hop, num_samples - fed);
        meter.processStream(scratch.data() + fed * num_channels,
                            static_cast<int>(n));
        fed += n;
    }
    const float gated = meter.integratedLufs();
    if (gated > -119.0f) return gated;

    // Fewer than 400 ms of material (or silence): ungated K-weighted mean.
    double sum = 0.0;
    for (size_t c = 0; c < num_channels; ++c) {
        double e = 0.0;
        for (size_t i = 0; i < num_samples; ++i) {
            const float x = channel_data[c][i];
            if (streamify::util::f32_is_finite(x)) e += static_cast<double>(x) * static_cast<double>(x);
        }
        sum += kChannelWeight[c] * (e / static_cast<double>(num_samples));
    }
    if (sum <= 1e-12) return -70.0f;
    return static_cast<float>(luFromSum(sum));
}

float LufsNormalizer::calculateNormalizationGain(float integrated_lufs,
                                                 float target_lufs) {
    if (integrated_lufs <= -70.0f) return 1.0f;
    float gainDb = std::clamp(target_lufs - integrated_lufs, -12.0f, 12.0f);
    return std::pow(10.0f, gainDb / 20.0f);
}

void LufsNormalizer::processFloats(float* pcm, int length, float targetLufs) {
    if (pcm == nullptr || length <= 0) return;
    const float* channels[] = {pcm};
    const float lufs = computeIntegratedLufs(channels, 1,
                                             static_cast<size_t>(length),
                                             sampleRateHz_);
    const float gain = calculateNormalizationGain(lufs, targetLufs);
    for (int i = 0; i < length; ++i) {
        pcm[i] = softGuard099(pcm[i] * gain);
    }
}

void LufsNormalizer::processShorts(short* pcm, int length, float targetLufs) {
    if (pcm == nullptr || length <= 0) return;
    thread_local static std::vector<float> tlsBuf;
    if (tlsBuf.size() < static_cast<size_t>(length)) {
        tlsBuf.resize(static_cast<size_t>(length));
    }
    for (int i = 0; i < length; ++i) {
        tlsBuf[i] = static_cast<float>(pcm[i]) / 32768.0f;
    }
    processFloats(tlsBuf.data(), length, targetLufs);
    for (int i = 0; i < length; ++i) {
        const float v = std::clamp(tlsBuf[i], -1.0f, 1.0f);
        pcm[i] = static_cast<short>(v * 32767.0f);
    }
}
