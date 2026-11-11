#include "AcousticPhaseResampler.h"

#include <algorithm>
#include <cstdlib>
#include <cstring>

// Shared SIMD / alignment shims (Phase 1): streamify_fma_f32 + the
// MSVC-portable alignedAlloc/alignedFree pair.
#include "../util/NeonCompat.h"

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#define STREAMIFY_HAS_NEON 1
#include <arm_neon.h>
#endif

namespace streamify::dsp {

// ---------------------------------------------------------------------------
// Small math helpers
// ---------------------------------------------------------------------------
namespace {

// I0(x): modified Bessel function of the first kind, order 0 — the Kaiser
// window normalizer. Series form; converges to < 1e-15 rel. error within
// ~24 terms for the beta range we care about (<= 14).
inline double besselI0(double x) {
    const double h = 0.5 * x;
    double term = 1.0;   // k = 0 term
    double sum = 1.0;
    const double h2 = h * h;
    for (int k = 1; k < 32; ++k) {
        term *= h2 / static_cast<double>(k * k);
        sum += term;
        if (term < 1e-18 * sum) break;
    }
    return sum;
}

inline double sinc(double x) {
    if (std::abs(x) < 1e-12) return 1.0;
    const double px = M_PI * x;
    return std::sin(px) / px;
}

// Horizontal sum of a float32x4 (vaddvq on aarch64, VPADD chain on armv7).
#if STREAMIFY_HAS_NEON
inline float hsumF32x4(float32x4_t v) {
#if defined(__aarch64__)
    return vaddvq_f32(v);
#else
    const float32x2_t lo = vget_low_f32(v);
    const float32x2_t hi = vget_high_f32(v);
    const float32x2_t s = vpadd_f32(lo, hi);
    return vget_lane_f32(s, 0) + vget_lane_f32(s, 1);
#endif
}
#endif

constexpr int kPhaseFractionBits = 32;   // sub-sample accumulator width

// (the private posix_memalign copy is retired — allocations route through
// the shared streamify::alignedAlloc/alignedFree shim in util/NeonCompat.h,
// which carries the MSVC _aligned_malloc spelling the C4 rot guard needs)

} // namespace

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------
AcousticPhaseResampler::AcousticPhaseResampler()
    : AcousticPhaseResampler(Config()) {}

AcousticPhaseResampler::AcousticPhaseResampler(const Config& cfg)
    : cfg_(cfg), table_(nullptr, &streamify::alignedFree),
      ring_(nullptr, &streamify::alignedFree) {
    // Clamp knobs to sane ranges; see header for the design space.
    cfg_.tapsPerPhase   = std::clamp((cfg_.tapsPerPhase + 1) / 2 * 2, 8, 64);  // even
    // The phase grid MUST be a power of two: the hot path derives the phase
    // index via a shift (32 - log2(P)), not a division.
    {
        int p = std::clamp(cfg_.phaseCount, 64, 4096);
        int pow2 = 64;
        while (pow2 * 2 <= p) pow2 *= 2;
        cfg_.phaseCount = pow2;
    }
    cfg_.kaiserBeta     = std::clamp(cfg_.kaiserBeta, 4.0, 14.0);
    cfg_.sampleRateHz   = std::clamp(cfg_.sampleRateHz, 8'000.0, 192'000.0);
    cfg_.channels       = std::clamp(cfg_.channels, 1, 8);
    cfg_.maxInputFrames = std::clamp(cfg_.maxInputFrames, 64, 1 << 20);
    cfg_.slewTauSeconds = std::clamp(cfg_.slewTauSeconds, 0.005, 10.0);

    buildFilterTable();

    ringCapacity_ = 2 * cfg_.maxInputFrames + cfg_.tapsPerPhase + 16;
    const size_t ringBytes =
        static_cast<size_t>(ringCapacity_) * cfg_.channels * sizeof(float);
    ring_.reset(static_cast<float*>(
        streamify::alignedAlloc(32, ((ringBytes + 31) / 32) * 32)));

    resetStreamState();
}

AcousticPhaseResampler::~AcousticPhaseResampler() = default;

void AcousticPhaseResampler::buildFilterTable() {
    // Polyphase bank: row phi covers fractional delay phi/P in [0, 1).
    // Table[phi][k] = h(u) with u = (k + 1 - L) - phi/P, h = sinc * Kaiser.
    // Row P (= phase 1.0) is the wrap partner for linear interpolation at
    // the top of the grid. See header for the derivation and the identity
    // check (phi = 0, k = L-1 => u = 0 => unity tap => exact passthrough).
    const int T = cfg_.tapsPerPhase;
    const int L = T / 2;
    const int P = cfg_.phaseCount;
    const double beta = cfg_.kaiserBeta;
    const double i0Beta = besselI0(beta);

    const size_t rows = static_cast<size_t>(P) + 1;
    const size_t floats = rows * static_cast<size_t>(T);
    const size_t bytes = ((floats * sizeof(float) + 31) / 32) * 32;
    table_.reset(static_cast<float*>(streamify::alignedAlloc(32, bytes)));

    for (int phi = 0; phi <= P; ++phi) {
        const double frac = static_cast<double>(phi) / static_cast<double>(P);
        float* row = table_.get() + static_cast<size_t>(phi) * T;
        for (int k = 0; k < T; ++k) {
            const double u = static_cast<double>(k + 1 - L) - frac;
            const double t = u / static_cast<double>(L);   // Kaiser argument
            double w = 0.0;
            if (std::abs(t) < 1.0)
                w = besselI0(beta * std::sqrt(1.0 - t * t)) / i0Beta;
            row[k] = static_cast<float>(sinc(u) * w);
        }
    }
}

void AcousticPhaseResampler::resetStreamState() {
    ringFrames_ = 0;
    ringStart_ = 0;
    phaseAcc_ = 0;
    posInt_ = 0;
    stepFixed_ = 1ull << 32;
    totalInFrames_ = 0;
    totalOutFrames_ = 0;
    driftPpm_ = 0.0;
    slewAlpha_ = 1.0 - std::exp(-1.0 / (cfg_.sampleRateHz * cfg_.slewTauSeconds));
    // Note: the drift COMMAND is intentionally NOT cleared — a live
    // controller driving drift across a track transition should not see the
    // engine snap back to 0 PPM mid-correction.
}

bool AcousticPhaseResampler::configure(int channels, double sampleRateHz) {
    channels = std::clamp(channels, 1, 8);
    sampleRateHz = std::clamp(sampleRateHz, 8'000.0, 192'000.0);
    if (channels == cfg_.channels && sampleRateHz == cfg_.sampleRateHz)
        return false;
    cfg_.channels = channels;
    cfg_.sampleRateHz = sampleRateHz;
    ringCapacity_ = 2 * cfg_.maxInputFrames + cfg_.tapsPerPhase + 16;
    const size_t ringBytes =
        static_cast<size_t>(ringCapacity_) * cfg_.channels * sizeof(float);
    ring_.reset(static_cast<float*>(
        streamify::alignedAlloc(32, ((ringBytes + 31) / 32) * 32)));
    resetStreamState();
    return true;
}

// ---------------------------------------------------------------------------
// Drift control
// ---------------------------------------------------------------------------
void AcousticPhaseResampler::setTargetDriftNanosPerSecond(int64_t driftNanosPerSec) {
    // +-500 PPM hard clamp (mission brief 3B): 500_000 ns/s == 0.05%.
    const int64_t clamped = std::clamp(driftNanosPerSec,
                                       -kMaxDriftNanosPerSec, kMaxDriftNanosPerSec);
    targetDriftNanoPerSec_.store(clamped, std::memory_order_relaxed);
}

void AcousticPhaseResampler::setTargetDriftPpm(double ppm) {
    ppm = std::clamp(ppm, -kMaxDriftPpm, kMaxDriftPpm);
    targetDriftNanoPerSec_.store(static_cast<int64_t>(std::llround(ppm * 1000.0)),
                                 std::memory_order_relaxed);
}

int64_t AcousticPhaseResampler::suggestDriftNanosPerSecond(int64_t phaseErrorNanos) {
    // Positive error == this device is BEHIND the ensemble => speed up.
    // First-order closure with a 2 s time constant: drift = err / tau.
    // A 10 ms error commands 5 ms/s == 5 PPM -> ~2 s settle; a 500 ms error
    // saturates the clamp and catches up ~1 s per 500 ms of error.
    const int64_t d = phaseErrorNanos / 2;
    return std::clamp(d, -kMaxDriftNanosPerSec, kMaxDriftNanosPerSec);
}

void AcousticPhaseResampler::reset() {
    resetStreamState();
}

// ---------------------------------------------------------------------------
// Silent PLL (Gap #13, SINGLE_RENDER party mode)
// ---------------------------------------------------------------------------
void AcousticPhaseResampler::setSilentBypass(bool silentBypass) {
    silentBypass_.store(silentBypass, std::memory_order_relaxed);
    bypassEpoch_.fetch_add(1, std::memory_order_acq_rel);
}

// ---------------------------------------------------------------------------
// Streaming kernel
// ---------------------------------------------------------------------------
int AcousticPhaseResampler::process(const float* in, int inFrames,
                                    float* out, int outFrameCapacity) {
    // ---- Argument validation (nothing consumed on error) -------------------
    if (inFrames < 0 || outFrameCapacity < 0) return -1;
    if (inFrames == 0) return 0;
    if (in == nullptr || (out == nullptr && outFrameCapacity > 0)) return -1;
    if (inFrames > cfg_.maxInputFrames) return -2;   // split the block upstream
    if (outFrameCapacity < inFrames) return -4;      // cannot make progress;
    // a sane caller sizes out >= in (ideally in + 2% margin); anything the
    // engine cannot emit this call stays buffered internally and drains on
    // the next one.
    if (ringFrames_ + inFrames > ringCapacity_) return -5;  // backpressure:
    // drain with process(nullptr, 0, out, cap), then re-feed this block.

    // ---- Silent PLL transitions (applied on the audio thread) ------------
    const uint32_t epoch = bypassEpoch_.load(std::memory_order_acquire);
    if (epoch != seenBypassEpoch_) {
        seenBypassEpoch_ = epoch;
        // Restart the stream at the live position on ANY transition; the
        // rate estimate and the controller command survive it.
        const double keepDrift = driftPpm_;
        resetStreamState();
        driftPpm_ = keepDrift;
    }
    if (silentBypass_.load(std::memory_order_relaxed)) {
        // Bit-exact passthrough with zero synthesis. The drift state machine
        // still tracks the live command: the per-frame slew recurrence,
        // advanced over this whole block in closed form, converges to the
        // commanded rate while we render nothing.
        const double targetPpmBypass =
            static_cast<double>(targetDriftNanoPerSec_.load(std::memory_order_relaxed)) / 1000.0;
        if (slewAlpha_ > 0.0 && inFrames > 0) {
            const double decay = std::pow(1.0 - slewAlpha_, static_cast<double>(inFrames));
            driftPpm_ += (targetPpmBypass - driftPpm_) * (1.0 - decay);
        }
        if (out != nullptr && out != in) {
            std::memcpy(out, in, static_cast<size_t>(inFrames) * cfg_.channels * sizeof(float));
        }
        // (out == in: caller-aliased passthrough — nothing to copy.)
        return inFrames;
    }

    const int C = cfg_.channels;
    const int T = cfg_.tapsPerPhase;
    const int L = T / 2;
    const int P = cfg_.phaseCount;
    const int phaseShift = kPhaseFractionBits - __builtin_ctz(P);  // log2(P)
    const uint32_t phaseMask = (1u << phaseShift) - 1u;

    // ---- Append the input block to the ring (one memcpy, zero alloc) ------
    if (ringFrames_ == 0) ringStart_ = totalInFrames_;
    std::memcpy(ring_.get() + static_cast<size_t>(ringFrames_) * C,
                in, static_cast<size_t>(inFrames) * C * sizeof(float));
    ringFrames_ += inFrames;
    totalInFrames_ += inFrames;

    // Snapshot the drift command once per call: the slew trajectory stays a
    // pure function of (command history, output frame index) regardless of
    // how the caller blocks the stream.
    const double targetPpm =
        static_cast<double>(targetDriftNanoPerSec_.load(std::memory_order_relaxed)) / 1000.0;

    // Window base index of the next output frame: i0 = posInt_ - L + 1.
    // We can emit while the window's last sample (posInt_ + L) is buffered.
    const int64_t ringEnd = ringStart_ + ringFrames_;   // exclusive
    int produced = 0;

    // Lerped-tap scratch: stack only (16-byte aligned for NEON), so the
    // zero-allocation contract holds without any member scratch state.
    alignas(16) float lerped[64];

    while (produced < outFrameCapacity &&
           posInt_ + L < ringEnd) {
        // ---- Phase decomposition: integer grid + sub-phase interpolation --
        const uint32_t phaseIdx = static_cast<uint32_t>(phaseAcc_ >> phaseShift);
        const double mu = static_cast<double>(phaseAcc_ & phaseMask) /
                          static_cast<double>(1u << phaseShift);

        const float* row0 = table_.get() + static_cast<size_t>(phaseIdx) * T;
        const float* row1 = row0 + T;

        // ---- Tap interpolation: t = row0 + mu*(row1 - row0) ----------------
        // Shared across all channels — this is why interpolating taps (not
        // samples) is the right factorization for multi-channel audio.
#if STREAMIFY_HAS_NEON
        {
            const float32x4_t muV = vdupq_n_f32(static_cast<float>(mu));
            for (int j = 0; j < T; j += 4) {
                const float32x4_t a = vld1q_f32(row0 + j);
                const float32x4_t b = vld1q_f32(row1 + j);
                vst1q_f32(lerped + j, streamify_fma_f32(a, vsubq_f32(b, a), muV));
            }
        }
#else
        for (int j = 0; j < T; ++j)
            lerped[j] = row0[j] + static_cast<float>(mu) * (row1[j] - row0[j]);
#endif

        // ---- Window pointer: x[i0 .. i0+T-1], i0 = posInt_ - L + 1 ---------
        // Samples below the stream start (negative absolute index) read as
        // zero — the zero-padded pre-roll that keeps output frame 0 aligned
        // with input frame 0.
        const int64_t i0 = posInt_ - L + 1;
        const float* xw;
        int64_t leadingZeros = 0;
        if (i0 < 0) {
            leadingZeros = -i0;
            xw = ring_.get();   // partial window: first `leadingZeros` taps hit zeros
        } else {
            xw = ring_.get() + static_cast<size_t>(i0 - ringStart_) * C;
        }

        float* dst = out + static_cast<size_t>(produced) * C;

        // ---- Convolution, per channel ----------------------------------------
        // Stereo fast path uses vld2q_f32 so ONE load de-interleaves an L/R
        // sample pair quad and feeds BOTH channel accumulators — optimal
        // memory traffic for the dominant case.
#if STREAMIFY_HAS_NEON
        if (C == 2 && leadingZeros == 0) {
            float32x4_t accL = vdupq_n_f32(0.0f);
            float32x4_t accR = vdupq_n_f32(0.0f);
            for (int j = 0; j < T; j += 4) {
                const float32x4x2_t lr = vld2q_f32(xw + j * 2);
                const float32x4_t tv = vld1q_f32(lerped + j);
                accL = streamify_fma_f32(accL, tv, lr.val[0]);
                accR = streamify_fma_f32(accR, tv, lr.val[1]);
            }
            dst[0] = hsumF32x4(accL);
            dst[1] = hsumF32x4(accR);
        } else if (C == 1 && leadingZeros == 0) {
            float32x4_t acc = vdupq_n_f32(0.0f);
            for (int j = 0; j < T; j += 4)
                acc = streamify_fma_f32(acc, vld1q_f32(lerped + j), vld1q_f32(xw + j));
            dst[0] = hsumF32x4(acc);
        } else
#endif
        {
            // Portable path (host builds, C >= 3, pre-roll windows).
            // Strided scalar MACs; the tap buffer is L1-resident and the
            // loop is unrolled by the compiler — comfortably inside budget
            // even on a Cortex-A55.
            for (int c = 0; c < C; ++c) {
                float acc = 0.0f;
                for (int j = 0; j < T; ++j) {
                    const float s = (j < leadingZeros)
                                        ? 0.0f
                                        : xw[static_cast<size_t>(j - leadingZeros) * C + c];
                    acc += lerped[j] * s;
                }
                dst[c] = acc;
            }
        }

        ++produced;

        // ---- Slew the drift toward the command (per OUTPUT frame) ----------
        driftPpm_ += (targetPpm - driftPpm_) * slewAlpha_;

        // ---- Advance the read position in 32.32 fixed point -----------------
        // stepFixed_ = round(ratio * 2^32); integer carries are exact, so
        // hour-long sessions accumulate zero position error.
        stepFixed_ = static_cast<uint64_t>(std::llround(
            (1.0 + driftPpm_ * 1e-6) * 4294967296.0));
        const uint64_t next = phaseAcc_ + stepFixed_;
        posInt_ += static_cast<int64_t>(next >> 32);
        phaseAcc_ = next & 0xFFFFFFFFull;
    }

    totalOutFrames_ += produced;

    // ---- Compact the ring: drop frames the next window can no longer touch.
    const int64_t keepFrom = posInt_ - L + 1;
    if (keepFrom > ringStart_) {
        const int64_t drop = std::min<int64_t>(keepFrom - ringStart_, ringFrames_);
        const size_t moveBytes =
            static_cast<size_t>(ringFrames_ - static_cast<int>(drop)) * C * sizeof(float);
        std::memmove(ring_.get(), ring_.get() + static_cast<size_t>(drop) * C, moveBytes);
        ringStart_ += drop;
        ringFrames_ -= static_cast<int>(drop);
    }

    return produced;
}

} // namespace streamify::dsp
