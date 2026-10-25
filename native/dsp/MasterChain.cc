#include "MasterChain.h"

#include <algorithm>

#include "../util/Finite.h"

namespace streamify::dsp {

bool MasterChain::configure(int sampleRate, int channels) {
    sampleRate = std::clamp(sampleRate, 8000, 192000);
    channels = std::clamp(channels, 1, LufsNormalizer::kMaxChannels);
    if (sampleRate == sampleRate_ && channels == channels_) return false;
    sampleRate_ = sampleRate;
    channels_ = channels;
    lufs_.configure(sampleRate, channels);   // resets on change internally
    limiter_.setSampleRate(sampleRate);
    limiter_.reset();
    return true;
}

void MasterChain::setLufsTarget(float lufs) {
    // Bit-pattern NaN guard on the INPUT (audit #22 item 2): clamp() is
    // UB on NaN and the `!(x >= lo)` backstop elides under prod
    // -ffinite-math-only.
    lufs = std::clamp(
        util::f32_finite_or(lufs, -14.0f),
        LufsNormalizer::kMinTargetLufs, LufsNormalizer::kMaxTargetLufs);
    lufsTarget_.store(lufs, std::memory_order_relaxed);
}

void MasterChain::setLimiterCeiling(float ceilingDb) {
    ceilingDb = std::clamp(
        util::f32_finite_or(ceilingDb, -1.0f), -12.0f, 0.0f);
    ceilingDb_.store(ceilingDb, std::memory_order_relaxed);
}

void MasterChain::setMonoDownmix(bool on) {
    mono_.store(on, std::memory_order_relaxed);
}

void MasterChain::setBalance(float balance) {
    balance = std::clamp(
        util::f32_finite_or(balance, 0.0f), -1.0f, 1.0f);
    balance_.store(balance, std::memory_order_relaxed);
}

void MasterChain::setSilentBypass(bool on) {
    bypass_.store(on, std::memory_order_relaxed);
}

void MasterChain::process(float* interleaved, int frames) {
    if (interleaved == nullptr || frames <= 0) return;
    if (bypass_.load(std::memory_order_relaxed)) return;   // Gap #13: zero CPU

    // Fold the published settings into the sub-engines on the audio thread.
    lufs_.setTargetLufs(lufsTarget_.load(std::memory_order_relaxed));
    lufs_.setMaxCorrectionDb(12.0f);
    limiter_.setCeilingDb(ceilingDb_.load(std::memory_order_relaxed));
    channel_.setBalance(balance_.load(std::memory_order_relaxed));
    channel_.setMonoDownmix(mono_.load(std::memory_order_relaxed));

    // 1. Normalization gain (measures the pre-gain signal).
    lufs_.processStream(interleaved, frames);

    // 2. Mono downmix + constant-power balance (stereo geometry only).
    if (channels_ == 2) {
        channel_.processInterleaved(interleaved, static_cast<size_t>(frames));
    }

    // 3. True-peak ceiling — bounds the final mix, panning included.
    limiter_.processFrames(interleaved, frames, channels_);
}

}  // namespace streamify::dsp
