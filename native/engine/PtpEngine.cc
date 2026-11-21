#include "PtpEngine.h"

#include <algorithm>
#include <cmath>

namespace streamify {

// ---------------------------------------------------------------------------
// Construction / singleton
// ---------------------------------------------------------------------------
PtpEngine& PtpEngine::getInstance() {
    static PtpEngine instance;  // default config, C++11 thread-safe init
    return instance;
}

PtpEngine::PtpEngine() : PtpEngine(Config()) {}

PtpEngine::PtpEngine(const Config& cfg) : cfg_(cfg) {
    // Sanitize user-supplied tunables; a mis-tuned filter is worse than no
    // filter at all, so every knob is clamped to a physically sane range.
    cfg_.sigma0Nanos             = std::max(1.0, cfg_.sigma0Nanos);
    cfg_.rttFloorNanos           = std::max(1.0, cfg_.rttFloorNanos);
    cfg_.qThetaNanos2PerSec      = std::max(0.0, cfg_.qThetaNanos2PerSec);
    cfg_.qOmegaNanos2PerSec3     = std::max(0.0, cfg_.qOmegaNanos2PerSec3);
    cfg_.innovationChiSquareGate = std::max(1.0, cfg_.innovationChiSquareGate);
    cfg_.initialOffsetSigmaNanos = std::max(1.0, cfg_.initialOffsetSigmaNanos);
    cfg_.initialDriftSigmaNanosPerSec = std::max(1.0, cfg_.initialDriftSigmaNanosPerSec);
    cfg_.maxRttPenaltyExponent   = std::clamp(cfg_.maxRttPenaltyExponent, 1.0, 40.0);
    rttWindowSamples_ = static_cast<uint32_t>(
        std::clamp(cfg_.rttWindowSamples, 4, static_cast<int>(kMaxRttWindow)));

    initialize();
}

void PtpEngine::initialize() {
    theta_ = 0.0;
    omega_ = 0.0;
    sigmaTheta_ = cfg_.initialOffsetSigmaNanos;
    p_[0][0] = cfg_.initialOffsetSigmaNanos * cfg_.initialOffsetSigmaNanos;
    p_[1][1] = cfg_.initialDriftSigmaNanosPerSec * cfg_.initialDriftSigmaNanosPerSec;
    p_[0][1] = p_[1][0] = 0.0;
    initialized_ = false;
    lastLocalTxNanos_ = 0;
    rttMinNanos_ = cfg_.rttFloorNanos;
    rttEmaNanos_ = cfg_.rttFloorNanos;
    rttHistLen_ = 0;
    rttHistPos_ = 0;
    seen_ = accepted_ = rejected_ = 0;
    consecutiveRejects_ = 0;
    bootCount_ = 0;
    measLambda_ = 1.0;
    lastRttAtomic_.store(0, std::memory_order_relaxed);
    acceptedAtomic_.store(0, std::memory_order_relaxed);
    seqAtomic_.store(0, std::memory_order_relaxed);
    snapshot_ = {};
    publishSnapshot(nowRawNanos());
}

// ---------------------------------------------------------------------------
// Seqlock snapshot plumbing
// ---------------------------------------------------------------------------
void PtpEngine::publishSnapshot(int64_t tLocalRef) {
    // Writer side: called with mutex_ held. seq becomes odd for the duration
    // of the write so lock-free readers retry instead of tearing.
    seqAtomic_.fetch_add(1, std::memory_order_relaxed);  // -> odd
    std::atomic_thread_fence(std::memory_order_release);
    snapshot_.thetaNanos       = theta_;
    snapshot_.omegaNanosPerSec = omega_;
    snapshot_.sigmaThetaNanos  = sigmaTheta_;
    snapshot_.tLocalRefNanos   = tLocalRef;
    snapshot_.acceptedCount    = accepted_;
    std::atomic_thread_fence(std::memory_order_release);
    seqAtomic_.fetch_add(1, std::memory_order_relaxed);  // -> even
}

PtpEngine::ClockSnapshot PtpEngine::readSnapshot() const {
    ClockSnapshot out;
    for (int spin = 0; spin < 64; ++spin) {   // bounded: never blocks audio
        out.seq = seqAtomic_.load(std::memory_order_acquire);
        if (out.seq & 1ull) continue;          // write in flight
        out.thetaNanos       = snapshot_.thetaNanos;
        out.omegaNanosPerSec = snapshot_.omegaNanosPerSec;
        out.sigmaThetaNanos  = snapshot_.sigmaThetaNanos;
        out.tLocalRefNanos   = snapshot_.tLocalRefNanos;
        out.acceptedCount    = snapshot_.acceptedCount;
        std::atomic_thread_fence(std::memory_order_acquire);
        if (seqAtomic_.load(std::memory_order_acquire) == out.seq) return out;
    }
    // 64 consecutive collisions means pathological writer pressure; fall back
    // to the last coherent-enough copy rather than spinning in the callback.
    return out;
}

// ---------------------------------------------------------------------------
// Core estimator
// ---------------------------------------------------------------------------
int64_t PtpEngine::processTimestamps(int64_t t0, int64_t t1, int64_t t2, int64_t t3) {
    // Sanity: timestamps must be ordered within an exchange; whatever UDP
    // delivered (duplicates, reordering, partial writes) must never reach
    // the filter.
    if (t3 < t0 || t2 < t1) return 0;

    const int64_t rtt = (t3 - t0) - (t2 - t1);
    if (rtt < 0) return 0;   // negative path delay => non-physical

    const double rttD = static_cast<double>(rtt);
    const double z = 0.5 * (static_cast<double>(t1 - t0) +
                            static_cast<double>(t2 - t3));   // Theta observation

    std::lock_guard<std::mutex> lock(mutex_);
    ++seen_;
    lastRttAtomic_.store(static_cast<uint32_t>(
        std::min<int64_t>(rtt, 0xFFFFFFF0ll)), std::memory_order_relaxed);

    // ---- RTT bookkeeping: learned minimum + EMA -----------------------------
    rttEmaNanos_ += 0.1 * (rttD - rttEmaNanos_);
    // Sliding-window minimum: insert, then rescan O(W) (control path).
    // Tracks path improvements instantly; a poisoned early minimum ages
    // out within one window. Until the first sample arrives the configured
    // floor bounds R_k from below.
    rttHist_[rttHistPos_] = rttD;
    rttHistPos_ = (rttHistPos_ + 1) % rttWindowSamples_;
    if (rttHistLen_ < rttWindowSamples_) ++rttHistLen_;
    {
        double wmin = rttHist_[0];
        for (uint32_t i = 1; i < rttHistLen_; ++i)
            wmin = std::min(wmin, rttHist_[i]);
        rttMinNanos_ = (rttHistLen_ == 0) ? cfg_.rttFloorNanos
                                          : std::max(wmin, 1.0);
    }

    // ---- Dynamic measurement variance (mission brief, section 3A) ----------
    // R_k = sigma0^2 * exp((RTT_k - RTT_min)/RTT_min). The exponent is
    // clamped to [-1, cap]: the lower bound stops a too-high learned
    // minimum from producing over-trusted (sub-sigma0) variances on links
    // that are better than the prior.
    const double exponentRaw = (rttD - rttMinNanos_) / rttMinNanos_;
    const bool   rttCapped   = exponentRaw >= cfg_.maxRttPenaltyExponent;
    const double exponent = std::clamp(exponentRaw, -1.0, cfg_.maxRttPenaltyExponent);
    const double R = cfg_.sigma0Nanos * cfg_.sigma0Nanos * std::exp(exponent);
    // chi^2 from a capped or not-yet-filled-window sample is uninformative
    // (S is dominated by R); the adapter must not learn from it.
    const bool adaptOk = !rttCapped && (rttHistLen_ >= rttWindowSamples_);

    // ---- Bootstrap: median-of-first-N robust initialization ----------------
    if (!initialized_) {
        bootZ_[bootCount_] = z;
        bootR_[bootCount_] = R;
        ++bootCount_;
        if (bootCount_ < kBootstrapSamples) {
            // Not enough evidence yet; expose a coarse running median so
            // callers still get a sane (if rough) clock while bootstrapping.
            double tmp[kBootstrapSamples];
            std::copy(bootZ_, bootZ_ + bootCount_, tmp);
            std::sort(tmp, tmp + bootCount_);
            theta_ = tmp[bootCount_ / 2];
            publishSnapshot(t0);
            return static_cast<int64_t>(theta_);
        }
        // Commit: median center, uncertainty from the spread, inflated 2.5x
        // because 5 samples estimate a scale badly.
        double zs[kBootstrapSamples];
        std::copy(bootZ_, bootZ_ + kBootstrapSamples, zs);
        std::sort(zs, zs + kBootstrapSamples);
        theta_ = zs[kBootstrapSamples / 2];
        double mad = 0.0;
        for (uint32_t i = 0; i < kBootstrapSamples; ++i)
            mad = std::max(mad, std::abs(bootZ_[i] - theta_));
        sigmaTheta_ = std::max(2.5 * mad, cfg_.sigma0Nanos);
        p_[0][0] = sigmaTheta_ * sigmaTheta_;
        p_[0][1] = p_[1][0] = 0.0;
        // Seed the adaptive-R multiplier from the observed spread so the
        // filter enters steady state correctly scaled even on noisy radio
        // links (sigma0 guess vs. reality can differ 5x between APs).
        const double sigmaObs = std::max(mad / 1.4826, cfg_.sigma0Nanos);
        measLambda_ = std::clamp((sigmaObs / cfg_.sigma0Nanos) *
                                     (sigmaObs / cfg_.sigma0Nanos),
                                 0.25, 25.0);
        // Omega prior stays wide; the Kalman updates resolve it.
        initialized_ = true;
        accepted_ = kBootstrapSamples;
        acceptedAtomic_.store(accepted_, std::memory_order_relaxed);
        consecutiveRejects_ = 0;
        publishSnapshot(t0);
        return static_cast<int64_t>(theta_);
    }

    // ---- Time-step for the process model -----------------------------------
    // dt is measured on the LOCAL clock between consecutive request TX
    // instants (t0 spacing is the only interval we fully control).
    double dt = 0.25;   // 250 ms nominal PTP cadence, used for the first step
    if (t0 > lastLocalTxNanos_)
        dt = std::min(static_cast<double>(t0 - lastLocalTxNanos_) * 1e-9, 10.0);
    lastLocalTxNanos_ = t0;

    // ---- Kalman predict: x' = F x, P' = F P F^T + Q(dt) ---------------------
    // F = [[1, dt], [0, 1]]. Expanded by hand: no Eigen in the hot path, no
    // heap, fully deterministic (this is a real-time audio-adjacent thread).
    theta_ += omega_ * dt;
    const double p00 = p_[0][0] + dt * (p_[0][1] + p_[1][0]) + dt * dt * p_[1][1];
    const double p01 = p_[0][1] + dt * p_[1][1];
    const double p11 = p_[1][1];
    // Q(dt): exact discretization of white-noise frequency ("integrated
    // random walk") plus a direct phase walk term.
    const double qO = cfg_.qOmegaNanos2PerSec3;
    const double qT = cfg_.qThetaNanos2PerSec * dt;
    p_[0][0] = p00 + qO * dt * dt * dt / 3.0 + qT;
    p_[0][1] = p01 + qO * dt * dt / 2.0;
    p_[1][0] = p_[0][1];
    p_[1][1] = p11 + qO * dt;

    // ---- Innovation gate (chi^2, 1 dof) -------------------------------------
    // S uses the adapted variance R*lambda: overconfident filters gate out
    // good samples; the IAE multiplier keeps the gate honest.
    const double Reff = R * measLambda_;
    const double nu = z - theta_;                  // innovation
    const double S  = p_[0][0] + Reff;             // innovation variance
    const double chi2 = (nu * nu) / std::max(S, 1.0);

    if (chi2 > cfg_.innovationChiSquareGate) {
        // Burst congestion / AP retransmit / duplicated datagram: reject.
        // The predict step above still ran, which is correct — uncertainty
        // keeps growing between valid observations. A rejection is still
        // measurement-scale information: feed the adapter (an over-confident
        // filter that never adapts would deadlock behind its own gate).
        ++rejected_;
        ++consecutiveRejects_;
        if (adaptOk) {
            measLambda_ += 0.1 * (chi2 - measLambda_);
            measLambda_ = std::clamp(measLambda_, 0.25, 50.0);
        }
        if (consecutiveRejects_ >= 15) {
            // The master clock may have stepped/rebooted, or our estimate is
            // irrevocably diverged. Re-enter bootstrap acquisition.
            initialized_ = false;
            bootCount_ = 0;
            consecutiveRejects_ = 0;
        }
        return static_cast<int64_t>(theta_);
    }
    consecutiveRejects_ = 0;

    // ---- Kalman update (scalar measurement, H = [1 0]) ----------------------
    const double K0 = p_[0][0] / S;
    const double K1 = p_[1][0] / S;
    theta_ += K0 * nu;
    omega_ += K1 * nu;
    const double p00_new = p_[0][0] * (1.0 - K0);
    const double p01_new = p_[0][1] * (1.0 - K0);
    const double p10_new = p_[1][0] - K1 * p_[0][0];
    const double p11_new = p_[1][1] - K1 * p_[0][1];
    p_[0][0] = p00_new; p_[0][1] = p01_new;
    p_[1][0] = p10_new; p_[1][1] = p11_new;

    // Innovation-adaptive R (IAE), applied for accepted AND rejected
    // (never capped / window-filling) samples: E[nu^2/S] == 1 for a
    // correctly scaled filter; drive lambda_ towards whatever the link
    // actually exhibits. Additive EMA — no martingale diffusion.
    if (adaptOk) {
        measLambda_ += 0.1 * (chi2 - measLambda_);
        measLambda_ = std::clamp(measLambda_, 0.25, 50.0);
    }

    // Symmetrize against accumulated FP asymmetry (Joseph form would be
    // overkill for a 2x2; explicit symmetrization is the standard cheap fix).
    p_[0][1] = p_[1][0] = 0.5 * (p_[0][1] + p_[1][0]);

    // Keep the state bounded: a multi-hour desync must not carry unbounded
    // values into the snapshot, and phone crystals never exceed +-500 PPM.
    theta_ = std::clamp(theta_, -3600.0e9, 3600.0e9);
    omega_ = std::clamp(omega_, -500.0e3, 500.0e3);

    ++accepted_;
    acceptedAtomic_.store(accepted_, std::memory_order_relaxed);
    sigmaTheta_ = std::sqrt(std::max(p_[0][0], 0.0));

    publishSnapshot(t0);
    return static_cast<int64_t>(theta_);
}

// ---------------------------------------------------------------------------
// Readouts
// ---------------------------------------------------------------------------
int64_t PtpEngine::getSynchronizedClockNanos() const {
    const int64_t now = nowRawNanos();
    const ClockSnapshot s = readSnapshot();
    // Master time = local time + Theta(t), Theta extrapolated by Omega.
    const double elapsedSec =
        static_cast<double>(now - s.tLocalRefNanos) * 1e-9;
    const double thetaNow = s.thetaNanos + s.omegaNanosPerSec * elapsedSec;
    return now + static_cast<int64_t>(thetaNow);
}

int64_t PtpEngine::convertRawToMasterNanos(int64_t rawNanos) const {
    const ClockSnapshot s = readSnapshot();
    const double elapsedSec =
        static_cast<double>(rawNanos - s.tLocalRefNanos) * 1e-9;
    const double thetaAt = s.thetaNanos + s.omegaNanosPerSec * elapsedSec;
    return rawNanos + static_cast<int64_t>(thetaAt);
}

int64_t PtpEngine::getClockOffsetNanos() const {
    const int64_t now = nowRawNanos();
    const ClockSnapshot s = readSnapshot();
    const double elapsedSec =
        static_cast<double>(now - s.tLocalRefNanos) * 1e-9;
    return static_cast<int64_t>(s.thetaNanos + s.omegaNanosPerSec * elapsedSec);
}

double PtpEngine::getDriftPpm() const {
    // Exposed convention: 1 us/s == 1 PPM (the internal state is ns/s,
    // i.e. 1 ns/s == 1 PPB — the unit most clock literature and every
    // crystal datasheet assumes). NOTE: this is d(Theta)/dt =
    // d(T_master - T_local)/dt, i.e. the NEGATIVE of the local crystal's
    // frequency error: a local crystal running +F PPM fast yields
    // drift == -F PPM.
    return readSnapshot().omegaNanosPerSec / 1000.0;
}

double PtpEngine::getOffsetSigmaNanos() const {
    return readSnapshot().sigmaThetaNanos;
}

bool PtpEngine::isLocked() const {
    const ClockSnapshot s = readSnapshot();
    return s.acceptedCount >= 8 && s.sigmaThetaNanos < 200'000.0;
}

PtpEngine::Stats PtpEngine::getStats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Stats s;
    s.exchangesSeen = seen_;
    s.exchangesAccepted = accepted_;
    s.exchangesRejected = rejected_;
    s.lastRttNanos = static_cast<int64_t>(lastRttAtomic_.load(std::memory_order_relaxed));
    s.minRttNanos = static_cast<int64_t>(rttMinNanos_);
    s.rttEmaNanos = rttEmaNanos_;
    s.offsetNanos = static_cast<int64_t>(theta_);
    s.driftPpm = omega_ / 1000.0;   // us/s convention (see getDriftPpm)
    s.offsetSigmaNanos = std::sqrt(std::max(p_[0][0], 0.0));
    s.locked = (accepted_ >= 8) && (s.offsetSigmaNanos < 200'000.0);
    return s;
}

void PtpEngine::reset() {
    std::lock_guard<std::mutex> lock(mutex_);
    // Keep the learned network state (RTT minimum, EMA and the window that
    // produced it): topology knowledge survives a session restart even
    // though the clock estimate does not.
    const double keepRttMin = rttMinNanos_;
    const double keepRttEma = rttEmaNanos_;
    double   keepHist[kMaxRttWindow];
    const uint32_t keepLen = rttHistLen_;
    const uint32_t keepPos = rttHistPos_;
    std::copy(rttHist_, rttHist_ + kMaxRttWindow, keepHist);
    initialize();
    rttMinNanos_ = keepRttMin;
    rttEmaNanos_ = keepRttEma;
    std::copy(keepHist, keepHist + kMaxRttWindow, rttHist_);
    rttHistLen_ = keepLen;
    rttHistPos_ = keepPos;
}

} // namespace streamify
