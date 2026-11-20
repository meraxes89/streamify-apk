#include "SoftKneeLimiter.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "../util/Finite.h"
#include "../util/NeonCompat.h"

// ============================================================================
//  True-peak soft-knee limiter — implementation
// ============================================================================

namespace {

// Modified Bessel function of the first kind, order 0 (series; 30 terms give
// full double precision for x <= 30 — our beta is 9).
double besselI0(double x) {
    const double h = 0.5 * x;
    double term = 1.0, sum = 1.0;
    for (int k = 1; k < 40; ++k) {
        term *= (h / static_cast<double>(k)) * (h / static_cast<double>(k));
        sum += term;
        if (term < 1e-18 * sum) break;
    }
    return sum;
}

inline double sinc(double u) {          // sin(pi u) / (pi u), sinc(0) = 1
    if (std::fabs(u) < 1e-12) return 1.0;
    const double pu = M_PI * u;
    return std::sin(pu) / pu;
}

}  // namespace

// ---------------------------------------------------------------------------
// Construction / configuration
// ---------------------------------------------------------------------------
SoftKneeLimiter::SoftKneeLimiter(float ceilingDb, float kneeDb, float ratio)
    : ceilingDb_(ceilingDb), kneeWidthDb_(kneeDb), ratio_(ratio) {
    buildKernels();
    setCeilingDb(ceilingDb);
    setKneeDb(kneeDb);
    setSampleRate(48000);
}

SoftKneeLimiter::~SoftKneeLimiter() {
    streamify::alignedFree(deque_);
    deque_ = nullptr;
}

void SoftKneeLimiter::rebuildDerived() {
    ceilLin_ = std::pow(10.0f, ceilingDb_ / 20.0f);
    kneeLin_ = ceilLin_ * std::pow(10.0f, -kneeWidthDb_ / 20.0f);
    if (kneeLin_ < ceilLin_ * 0.02f) kneeLin_ = ceilLin_ * 0.02f;  // knee floor
}

void SoftKneeLimiter::setParameters(float ceilingDb, float kneeDb) {
    setCeilingDb(ceilingDb);
    setKneeDb(kneeDb);
}

void SoftKneeLimiter::setCeilingDb(float ceilingDb) {
    // Bit-pattern NaN guard on the INPUT (audit #22 item 2): clamp() is
    // UB on NaN and the `!(x <= hi)` backstop elides under prod
    // -ffinite-math-only.
    ceilingDb_ = std::clamp(
        streamify::util::f32_finite_or(ceilingDb, -1.0f), -12.0f, 0.0f);
    rebuildDerived();
}

void SoftKneeLimiter::setKneeDb(float kneeDb) {
    kneeWidthDb_ = std::clamp(
        streamify::util::f32_finite_or(kneeDb, 4.0f), 0.5f, 12.0f);
    rebuildDerived();
}

void SoftKneeLimiter::setSampleRate(int sampleRateHz) {
    fs_ = std::clamp(sampleRateHz, 8000, 192000);
    windowFrames_ = std::clamp(static_cast<int>(std::lround(0.003 * fs_)),
                               32, 2048);
    // Ramp budget: a full 12 dB gain drop must be schedulable across the
    // window. See the schedule derivation in the header.
    rampDbPerFrame_ = 12.0f / static_cast<float>(windowFrames_);
    releaseCoef_ = static_cast<float>(1.0 - std::exp(-1.0 / (0.080 * fs_)));

    // Monotonic-deque storage lives at window + slack (hot path never allocs).
    const int cap = windowFrames_ + 8;
    if (cap != dqCap_) {
        streamify::alignedFree(deque_);
        deque_ = static_cast<GainPoint*>(
            streamify::alignedAlloc(64, sizeof(GainPoint) * cap));
        dqCap_ = cap;
    }
    dqClear();
}

void SoftKneeLimiter::reset() {
    gsDb_ = 0.0f;
    std::memset(tailHist_, 0, sizeof(tailHist_));
    dqClear();
}

// ---------------------------------------------------------------------------
// Composite oversampling kernels
// ---------------------------------------------------------------------------
// Two identical halfband interpolators (2x each) composed into the 4x grid.
// Per sub-phase p in {1,2,3} the kernel over base-grid offsets q is the
// cascade impulse response evaluated at 4x position p - 4q:
//
//   stage1 (base -> 2x): y2[n]   = sum_m h[m] * u2[n - m],  u2[2j] = x[j]
//                        => y2[even] = x[...]  exactly (halfband zeros)
//   stage2 (2x -> 4x):   y4[n]   = sum_i h[i] * u4[n - i],  u4[2k] = y2[k]
//
//   h[m]: h[0] = 1 (passthrough), h[even != 0] = 0, h[odd] = Kaiser(9) window
//   over sinc(m/2), scaled so the 2x DC gain is exactly 2 (amplitude-true).
//   31 taps (halfSpan 15) keep the passband ripple at the fs/4 cutoff to
//   ~1.5% — the dominant term of the true-peak error budget.
//   The impulse response of the cascade from a base impulse is then
//   y4_imp[n] = sum_i h[i] * u4_imp[n - i], u4_imp[m] = (m even) ? h[m/2] : 0.
void SoftKneeLimiter::buildKernels() {
    constexpr int halfSpan = 15;                 // 31 taps at the 2x grid
    double h[halfSpan * 2 + 1];                  // h[m + halfSpan]
    std::memset(h, 0, sizeof(h));

    const double i0Beta = besselI0(9.0);
    double oddSum = 0.0;
    for (int m = 1; m <= halfSpan; m += 2) {
        const double t = static_cast<double>(m) / static_cast<double>(halfSpan);
        const double w = besselI0(9.0 * std::sqrt(std::max(0.0, 1.0 - t * t))) / i0Beta;
        const double raw = w * sinc(static_cast<double>(m) * 0.5);
        h[m + halfSpan] = raw;                   // positive side; mirrored below
        oddSum += raw;
    }
    for (int m = 1; m <= halfSpan; m += 2) h[-m + halfSpan] = h[m + halfSpan];
    h[0 + halfSpan] = 1.0;                       // exact passthrough tap
    // DC gain == 2: 1 + 2 * s * oddSum == 2  (h[0] fixed at 1).
    const double scale = (oddSum > 1e-9) ? 1.0 / (2.0 * oddSum) : 1.0;
    for (int m = 1; m <= halfSpan; m += 2) {
        h[m + halfSpan] *= scale;
        h[-m + halfSpan] *= scale;
    }

    // Cascade impulse response on the 4x grid: y4_imp[n] = sum_i h[i] *
    // u4_imp[n - i] with u4_imp[m] = h[m/2] for even m, 0 for odd m.
    // Support: |n| <= 45; evaluate over [-64, 64] to cover kernel access.
    constexpr int nSpan = 64;
    double y4[2 * nSpan + 1];                    // y4[n + nSpan]
    std::memset(y4, 0, sizeof(y4));
    for (int n = -nSpan; n <= nSpan; ++n) {
        double acc = 0.0;
        for (int i = -halfSpan; i <= halfSpan; ++i) {
            const double hi = h[i + halfSpan];
            if (hi == 0.0) continue;
            const int m = n - i;                 // index into u4_imp
            if (m < -2 * halfSpan || m > 2 * halfSpan) continue;
            if ((m & 1) != 0) continue;          // u4_imp odd slots are zero
            acc += hi * h[(m >> 1) + halfSpan];
        }
        y4[n + nSpan] = acc;
    }

    // kernel_[p][q + kSideHist] = y4_imp[p - 4q]  (phase 0 is |x[t]| itself:
    // y4_imp[-4q] is exactly delta(q) because h[even != 0] = 0).
    for (int p = 0; p < 3; ++p) {
        for (int q = -kSideHist; q <= kSideHist; ++q) {
            const int n = (p + 1) - 4 * q;       // sub-phases 1..3
            double v = 0.0;
            if (n >= -nSpan && n <= nSpan) v = y4[n + nSpan];
            kernel_[p][q + kSideHist] = static_cast<float>(v);
        }
    }
}

// ---------------------------------------------------------------------------
// Curve + safety stage
// ---------------------------------------------------------------------------
float SoftKneeLimiter::curveLin(float x) const {
    const float ax = std::fabs(x);
    if (ax <= kneeLin_) return x;                // identity, sign preserved
    const float span = ceilLin_ - kneeLin_;      // > 0 (see rebuildDerived)
    const float sat = kneeLin_ + span * std::tanh((ax - kneeLin_) / span);
    return (x < 0.0f) ? -sat : sat;
}

float SoftKneeLimiter::satStage(float v) const {
    // Bit-pattern finite check (audit #22 item 2): std::isfinite folds to
    // `true` under prod -ffinite-math-only — see util/Finite.h.
    if (!streamify::util::f32_is_finite(v)) return 0.0f;
    if (v <= ceilLin_ && v >= -ceilLin_) return v;   // bit-exact identity
    const float a = (v > 0.0f) ? ceilLin_ : -ceilLin_;
    const float d = 0.004f * ceilLin_;
    const float dd = (v > 0.0f) ? d : -d;
    return a + dd * std::tanh((v - a) / dd);
}

// ---------------------------------------------------------------------------
// True peak
// ---------------------------------------------------------------------------
float SoftKneeLimiter::truePeakAt(const float* io, int frames, int j,
                                  int channels,
                                  const float (&hist)[kMaxChannels][kSideTaps]) const {
    const int C = channels;
    float peak = 0.0f;
    for (int c = 0; c < C; ++c) {
        // Phase 0: the base grid is an exact passthrough of the halfband tree.
        const float p0raw =
            (j >= 0 && j < frames) ? io[j * C + c] : 0.0f;
        float chPeak = std::fabs(p0raw);
        if (!(chPeak >= 0.0f)) chPeak = 0.0f;
        for (int p = 0; p < 3; ++p) {
            const float* k = kernel_[p];
            float dot = 0.0f;
            for (int q = -kSideHist; q <= kSideHist; ++q) {
                const int idx = j + q;
                float s;
                if (idx < 0) {
                    s = hist[c][idx + kSideTaps];        // previous call's tail
                } else if (idx < frames) {
                    s = io[idx * C + c];
                } else {
                    s = 0.0f;
                }
                dot += k[q + kSideHist] * s;
            }
            const float ad = std::fabs(dot);
            if (ad > chPeak) chPeak = ad;
        }
        if (chPeak > peak) peak = chPeak;
    }
    return peak;
}

float SoftKneeLimiter::measureTruePeak(const float* io, int frames,
                                       int channels) {
    if (io == nullptr || frames <= 0) return 0.0f;
    channels = std::clamp(channels, 1, kMaxChannels);
    static const float zeroHist[kMaxChannels][kSideTaps] = {};
    float peak = 0.0f;
    // Full sub-sample dots need the whole +-kSideHist neighborhood in
    // buffer. Frames near either edge would see a zero-padded (history-less)
    // window, which DISTORTS the interpolated values by several percent —
    // so edge frames contribute their passthrough sample only. The global
    // true peak of any real-world buffer is attained in the interior.
    const int firstFull = kSideHist;
    const int lastFull = frames - 1 - kSideHist;
    for (int j = 0; j < frames; ++j) {
        float tp = 0.0f;
        if (j >= firstFull && j <= lastFull) {
            tp = truePeakAt(io, frames, j, channels, zeroHist);
        } else {   // edge frames: only the passthrough phase is trustworthy
            for (int c = 0; c < channels; ++c) {
                const float a = std::fabs(io[j * channels + c]);
                if (a > tp) tp = a;
            }
        }
        if (!(tp >= 0.0f)) tp = 0.0f;
        if (tp > 1e30f) tp = 1e30f;
        if (tp > peak) peak = tp;
    }
    return peak;
}

// ---------------------------------------------------------------------------
// Monotonic deque
// ---------------------------------------------------------------------------
void SoftKneeLimiter::dqClear() {
    dqHead_ = 0;
    dqTail_ = 0;
}

void SoftKneeLimiter::dqPush(int64_t idx, float g) {
    while (dqHead_ != dqTail_) {
        const int last = (dqTail_ + dqCap_ - 1) % dqCap_;
        if (deque_[last].g >= g) {
            dqTail_ = last;                     // dominated entry: drop
        } else {
            break;
        }
    }
    deque_[dqTail_].idx = idx;
    deque_[dqTail_].g = g;
    dqTail_ = (dqTail_ + 1) % dqCap_;
}

// ---------------------------------------------------------------------------
// Hot path
// ---------------------------------------------------------------------------
void SoftKneeLimiter::processFrames(float* io, int frames, int channels) {
    if (io == nullptr || frames <= 0) return;
    channels = std::clamp(channels, 1, kMaxChannels);
    const int C = channels;

    // Snapshot the incoming tail BEFORE any writes (17 frames per channel).
    float newTail[kMaxChannels][kSideTaps];
    for (int c = 0; c < C; ++c) {
        if (frames >= kSideTaps) {
            const float* src = io + static_cast<size_t>(frames - kSideTaps) * C;
            for (int i = 0; i < kSideTaps; ++i) newTail[c][i] = src[i * C + c];
        } else {
            const int keep = kSideTaps - frames;
            for (int i = 0; i < keep; ++i)
                newTail[c][i] = tailHist_[c][i + frames];
            for (int i = keep; i < kSideTaps; ++i)
                newTail[c][i] = io[(i - keep) * C + c];
        }
    }

    const int lastComputable = frames - 1 - kSideHist;
    float heldTp = 0.0f;
    bool haveTp = false;
    dqClear();

    int pushed = 0;
    for (int t = 0; t < frames; ++t) {
        // Feed the sliding window: it must cover [t, t + windowFrames_].
        // Pushes read only slots >= t (windowFrames_ >= 32 > kSideHist), so
        // they never touch already-written output samples.
        // int64 window arithmetic (audit #22 item 9): removes the
        // only signed-overflow path (t + windowFrames_ near INT_MAX).
        const int64_t needUpTo =
            std::min<int64_t>(static_cast<int64_t>(t) + windowFrames_,
                              static_cast<int64_t>(frames) - 1);
        while (pushed <= needUpTo) {
            float tp = 0.0f;
            if (pushed <= lastComputable) {
                tp = truePeakAt(io, frames, pushed, C, tailHist_);
                heldTp = tp;
                haveTp = true;
            } else {
                tp = haveTp ? heldTp : 0.0f;     // conservative hold near the tail
            }
            // Bit-pattern NaN/Inf guards on the true-peak estimate
            // (audit #22 item 2): `!(tp >= 0)` elides under prod
            // -ffinite-math-only; the bit check cannot be folded.
            if (!streamify::util::f32_is_finite(tp)) tp = 0.0f;
            if (tp > 1e30f) tp = 1e30f;          // Inf guard (bit-safe above)
            float g = 1.0f;
            if (tp > 1e-9f) {
                g = curveLin(tp) / tp;
                if (!streamify::util::f32_is_finite(g) || !(g >= 0.0f)) g = 0.0f;
                if (g > 1.0f) g = 1.0f;
            }
            // Schedule form: store greq(dB) + ramp*idx. The readout then is
            //     sDb(t) = min_{j in [t, t+W]} (greqDb(j) + m*(j - t))
            // which is EXACTLY greq(j*) at the binding frame j* — zero
            // overshoot by construction — and slopes the approach at the
            // ramp budget m (musical descent instead of a step).
            float gDb = 0.0f;
            if (g < 1.0f) gDb = 20.0f * std::log10(g > 1e-9f ? g : 1e-9f);
            dqPush(pushed, gDb + rampDbPerFrame_ * static_cast<float>(pushed));
            ++pushed;
        }

        // Expire gains that slid out of the window.
        while (dqHead_ != dqTail_ && deque_[dqHead_].idx < t) {
            dqHead_ = (dqHead_ + 1) % dqCap_;
        }
        float sDb = (dqHead_ != dqTail_)
            ? deque_[dqHead_].g - rampDbPerFrame_ * static_cast<float>(t)
            : 0.0f;
        if (sDb >= -1e-3f) sDb = 0.0f;

        // Ballistics: the schedule owns the descent (exact, ramp-sloped);
        // the 80 ms one-pole only shapes the recovery upward.
        if (sDb < gsDb_) gsDb_ = sDb;
        else gsDb_ += (sDb - gsDb_) * releaseCoef_;
        if (gsDb_ >= -1e-3f) gsDb_ = 0.0f;

        const float gs = (gsDb_ >= 0.0f) ? 1.0f : std::pow(10.0f, gsDb_ * 0.05f);
        for (int c = 0; c < C; ++c) {
            const float inSamp = io[static_cast<size_t>(t) * C + c];
            io[static_cast<size_t>(t) * C + c] = (gs == 1.0f) ? satStage(inSamp) : satStage(inSamp * gs);
        }
    }

    // Commit the sidechain tail for the next call.
    for (int c = 0; c < C; ++c) {
        for (int i = 0; i < kSideTaps; ++i) tailHist_[c][i] = newTail[c][i];
    }
}

// ---------------------------------------------------------------------------
// Legacy adapters (mono semantics, identical to the pre-Phase-1 contract)
// ---------------------------------------------------------------------------
void SoftKneeLimiter::processInterleavedSIMD(float* pcm_samples,
                                             size_t total_samples) {
    if (pcm_samples == nullptr || total_samples == 0) return;
    processFrames(pcm_samples, static_cast<int>(total_samples), 1);
}

void SoftKneeLimiter::processFloats(float* buffer, int numSamples) {
    if (buffer == nullptr || numSamples <= 0) return;
    processFrames(buffer, numSamples, 1);
}

void SoftKneeLimiter::processShorts(int16_t* buffer, int numSamples) {
    if (buffer == nullptr || numSamples <= 0) return;
    thread_local static std::vector<float> tlsBuf;
    if (tlsBuf.size() < static_cast<size_t>(numSamples)) {
        tlsBuf.resize(static_cast<size_t>(numSamples));
    }
    for (int i = 0; i < numSamples; ++i) {
        tlsBuf[i] = static_cast<float>(buffer[i]) / 32768.0f;
    }
    processFrames(tlsBuf.data(), numSamples, 1);
    for (int i = 0; i < numSamples; ++i) {
        const float v = std::clamp(tlsBuf[i], -1.0f, 1.0f);
        buffer[i] = static_cast<int16_t>(v * 32767.0f);
    }
}
