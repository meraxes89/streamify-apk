#include "HardwareLatencyProfiler.h"

#include <algorithm>
#include <cmath>
#include <ctime>
#include <mutex>

namespace streamify::engine {

namespace {

inline int64_t nowMonotonicNanos() {
    struct timespec ts;
    ::clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL +
           static_cast<int64_t>(ts.tv_nsec);
}

constexpr uint32_t kMaxAnchorRing = HardwareLatencyProfiler::kMaxAnchors;

} // namespace

// ---------------------------------------------------------------------------
// Construction / state
// ---------------------------------------------------------------------------
HardwareLatencyProfiler::HardwareLatencyProfiler()
    : HardwareLatencyProfiler(Config()) {}

HardwareLatencyProfiler::HardwareLatencyProfiler(const Config& cfg) : cfg_(cfg) {
    cfg_.maxAnchors = std::clamp<uint32_t>(cfg_.maxAnchors, 4, kMaxAnchorRing);
    cfg_.anchorHalfLifeSec = std::clamp(cfg_.anchorHalfLifeSec, 0.1, 120.0);
    cfg_.staleAfterSec     = std::clamp(cfg_.staleAfterSec, 0.2, 60.0);
    cfg_.minAnchorsForFit  = std::clamp<uint32_t>(cfg_.minAnchorsForFit, 4, cfg_.maxAnchors);
    cfg_.madSigmaMultiplier= std::clamp(cfg_.madSigmaMultiplier, 1.5, 6.0);
    cfg_.clockBridgeAlpha  = std::clamp(cfg_.clockBridgeAlpha, 0.02, 1.0);
    cfg_.clockBridgeGateNanos = std::max(cfg_.clockBridgeGateNanos, 100'000.0);
    cfg_.nominalSampleRateHz  = std::clamp(cfg_.nominalSampleRateHz, 8'000.0, 192'000.0);
    reset();
}

void HardwareLatencyProfiler::reset() {
    anchorCount_ = 0;
    anchorHead_ = 0;
    lastFramesWritten_ = -1;
    lastQueuedHint_ = -1;
    // Route/codec and the clock bridge survive: they describe the machine,
    // not the measurement session.
}

// ---------------------------------------------------------------------------
// Priors — conservative, literature/field-grade center values
// ---------------------------------------------------------------------------
int64_t HardwareLatencyProfiler::priorLatencyNanos(Route route, Codec codec) const {
    // Sources: Android audio latency field measurements (superpowered/
    // mobiledevfreq aggregates, AOSP AudioTrack docs), Bluetooth SIG
    // codec budget tables. Values are the *center* of the observed range;
    // the controller treats +-50% as the uncertainty envelope.
    switch (route) {
        case Route::BuiltInSpeaker:
            // Deep-buffer path 10-40 ms, fast-track 4-12 ms -> center 12 ms.
            return 12'000'000;
        case Route::WiredAnalogJack:
            // Internal DAC + amp: 1-8 ms -> center 4 ms.
            return 4'000'000;
        case Route::WiredUsbDac:
            // USB feedback-mode DACs: one frame period + buffering -> 10 ms.
            return 10'000'000;
        case Route::BluetoothA2dp:
            switch (codec) {
                case Codec::Ldac:         return 100'000'000;  // 70-160 ms
                case Codec::AptxAdaptive: return  80'000'000;  // 50-120 ms
                case Codec::Aptx:         return 120'000'000;  // 100-150 ms
                case Codec::Aac:          return 180'000'000;  // 140-250 ms
                case Codec::Sbc:          return 200'000'000;  // 170-280 ms
                default:                  return 180'000'000;
            }
        case Route::BluetoothLeAudio:
            // LC3 isochronous streams target 20-40 ms.
            return 40'000'000;
        case Route::Unknown:
        default:
            return 15'000'000;
    }
}

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------
void HardwareLatencyProfiler::sampleClockBridge(int64_t /*nanoTimeMonotonic*/) {
    // Pair MONOTONIC and MONOTONIC_RAW with two back-to-back reads; the
    // residual skew between the calls is ~100-500 ns, three orders below
    // the NTP slew we are trying to observe. (The caller-supplied mono
    // timestamp is intentionally unused here: it was captured a binder
    // round-trip ago and would pollute the pairing with call latency.)
    const int64_t mono = nowMonotonicNanos();
    const int64_t raw  = nowRawNanos();
    const double delta = static_cast<double>(mono - raw);

    if (!bridgeInitialized_) {
        monoMinusRaw_ = delta;
        bridgeInitialized_ = true;
        return;
    }
    // Gate: a binder hiccup or suspend/resume step larger than the gate
    // updates the EMA only partially (steps are real — NTP *steps* do
    // happen — but a single wild sample must not drag the bridge).
    const double diff = delta - monoMinusRaw_;
    const double gated = (std::abs(diff) > cfg_.clockBridgeGateNanos)
                             ? monoMinusRaw_ + 0.05 * diff
                             : monoMinusRaw_ + cfg_.clockBridgeAlpha * diff;
    monoMinusRaw_ = gated;
}

void HardwareLatencyProfiler::setOutputRoute(Route route, Codec codec) {
    std::lock_guard<std::mutex> lock(mutex_);
    route_ = route;
    codec_ = codec;
    // A different sink invalidates every anchor: different buffering,
    // different clock, different codec delay.
    reset();
}

void HardwareLatencyProfiler::submitTrackTimestamp(int64_t framePosition,
                                                   int64_t nanoTimeMonotonic,
                                                   int64_t framesWritten,
                                                   int64_t queuedFramesHint) {
    if (framePosition < 0 || nanoTimeMonotonic <= 0) return;
    std::lock_guard<std::mutex> lock(mutex_);

    // Refresh the MONOTONIC<->RAW bridge on every anchor: it drifts slowly
    // (NTP slews at PPM rates) and anchors arrive at 10-50 Hz.
    sampleClockBridge(nanoTimeMonotonic);

    const int64_t submitRaw = nowRawNanos();

    Anchor a;
    a.frame = framePosition;
    a.tauRaw = nanoTimeMonotonic - static_cast<int64_t>(monoMinusRaw_);
    a.framesWritten = framesWritten;
    a.tSubmitRaw = submitRaw;
    a.weight = 1.0;
    lastFramesWritten_ = framesWritten;
    lastQueuedHint_ = queuedFramesHint;

    // FIFO ring.
    if (anchorCount_ < cfg_.maxAnchors) {
        anchors_[(anchorHead_ + anchorCount_) % kMaxAnchorRing] = a;
        ++anchorCount_;
    } else {
        anchors_[anchorHead_] = a;
        anchorHead_ = (anchorHead_ + 1) % kMaxAnchorRing;
    }
}

// ---------------------------------------------------------------------------
// Weighted robust regression: tauRaw ~= intercept + slope * frame
// ---------------------------------------------------------------------------
bool HardwareLatencyProfiler::fitLine(double& intercept,
                                      double& slopeNanosPerFrame,
                                      double& residualSigma) const {
    if (anchorCount_ < cfg_.minAnchorsForFit) return false;

    bool used[kMaxAnchorRing];
    for (uint32_t i = 0; i < kMaxAnchorRing; ++i) used[i] = false;

    double a = 0.0, b = 0.0;
    for (int pass = 0; pass < 3; ++pass) {
        // Weighted means with age-based exponential decay.
        const int64_t now = nowRawNanos();
        double sw = 0.0, sx = 0.0, sy = 0.0;
        for (uint32_t i = 0; i < anchorCount_; ++i) {
            if (used[i]) continue;
            const Anchor& an = anchors_[i];
            const double ageSec = std::max(0.0, static_cast<double>(now - an.tSubmitRaw) * 1e-9);
            const double w = std::pow(0.5, ageSec / cfg_.anchorHalfLifeSec);
            sw += w;
            sx += w * static_cast<double>(an.frame);
            sy += w * static_cast<double>(an.tauRaw);
        }
        if (sw <= 0.0) return false;
        const double mx = sx / sw, my = sy / sw;

        double sxx = 0.0, sxy = 0.0;
        for (uint32_t i = 0; i < anchorCount_; ++i) {
            if (used[i]) continue;
            const Anchor& an = anchors_[i];
            const double ageSec = std::max(0.0, static_cast<double>(now - an.tSubmitRaw) * 1e-9);
            const double w = std::pow(0.5, ageSec / cfg_.anchorHalfLifeSec);
            const double dx = static_cast<double>(an.frame) - mx;
            const double dy = static_cast<double>(an.tauRaw) - my;
            sxx += w * dx * dx;
            sxy += w * dx * dy;
        }
        if (sxx <= 0.0) return false;   // no frame spread => no slope info
        b = sxy / sxx;
        a = my - b * mx;

        // Residual scale via MAD (median absolute deviation), robust to the
        // 10-20% garbage anchors that BT stacks occasionally emit.
        double residuals[kMaxAnchorRing];
        uint32_t n = 0;
        for (uint32_t i = 0; i < anchorCount_; ++i) {
            if (used[i]) continue;
            residuals[n++] = std::abs(static_cast<double>(anchors_[i].tauRaw) -
                                      (a + b * static_cast<double>(anchors_[i].frame)));
        }
        std::sort(residuals, residuals + n);
        const double mad = (n & 1) ? residuals[n / 2]
                                   : 0.5 * (residuals[n / 2 - 1] + residuals[n / 2]);
        const double sigma = 1.4826 * mad;
        residualSigma = sigma;

        if (pass == 2 || sigma <= 0.0) break;

        // Reject outliers for the next pass.
        bool rejectedAny = false;
        for (uint32_t i = 0; i < anchorCount_; ++i) {
            if (used[i]) continue;
            const double r = std::abs(static_cast<double>(anchors_[i].tauRaw) -
                                      (a + b * static_cast<double>(anchors_[i].frame)));
            if (r > cfg_.madSigmaMultiplier * sigma) {
                used[i] = true;
                rejectedAny = true;
            }
        }
        if (!rejectedAny) break;
    }

    // Physical sanity: the effective rate may deviate from nominal by up to
    // 1% (BT stacks resample; codec clocks drift) but never more.
    const double expectedNsPerFrame = 1e9 / cfg_.nominalSampleRateHz;
    const double relDev = std::abs(b - expectedNsPerFrame) / expectedNsPerFrame;
    if (relDev > 0.01) return false;

    // Coverage: the fit needs at least 300 ms of frame span for a stable
    // slope, otherwise the intercept (latency) is under-determined.
    int64_t fMin = INT64_MAX, fMax = INT64_MIN;
    for (uint32_t i = 0; i < anchorCount_; ++i) {
        if (used[i]) continue;
        fMin = std::min(fMin, anchors_[i].frame);
        fMax = std::max(fMax, anchors_[i].frame);
    }
    if (fMax == fMin || static_cast<double>(fMax - fMin) * b < 3.0e8) return false;

    intercept = a;
    slopeNanosPerFrame = b;
    return true;
}

// ---------------------------------------------------------------------------
// Readouts
// ---------------------------------------------------------------------------
uint32_t HardwareLatencyProfiler::anchorCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return anchorCount_;
}

double HardwareLatencyProfiler::fittedNsPerFrame() const {
    std::lock_guard<std::mutex> lock(mutex_);
    double a, b, s;
    if (!fitLine(a, b, s)) return 0.0;
    return b;
}

HardwareLatencyProfiler::PlayoutEstimate
HardwareLatencyProfiler::estimatePlayoutDelay(int64_t nowRawNanos) const {
    std::lock_guard<std::mutex> lock(mutex_);
    PlayoutEstimate est;
    est.priorNanos = priorLatencyNanos(route_, codec_);

    // Anchors must be fresh: a paused player stops producing them, and a
    // stale regression is worse than an honest prior.
    double a = 0.0, b = 0.0, sigma = 0.0;
    bool live = false;
    if (anchorCount_ >= cfg_.minAnchorsForFit) {
        const Anchor& newest = anchors_[(anchorHead_ + anchorCount_ - 1) % kMaxAnchorRing];
        const double ageSec = std::max(0.0, static_cast<double>(nowRawNanos - newest.tSubmitRaw) * 1e-9);
        if (ageSec <= cfg_.staleAfterSec && fitLine(a, b, sigma)) {
            live = true;
            // Sign convention: positive = sink renders FASTER than nominal.
            est.effectiveRatePpm =
                (1e9 / b - cfg_.nominalSampleRateHz) * 1e6 / cfg_.nominalSampleRateHz;
        }
    }

    est.live = live;
    est.anchorsUsed = anchorCount_;
    est.residualSigmaNanos = sigma;

    if (!live) {
        est.delayNanos = est.priorNanos;
        return est;
    }

    // Frames sitting in the sink pipeline: either the caller's hint, or the
    // write-head minus presented-position from the freshest anchor.
    const Anchor& newest = anchors_[(anchorHead_ + anchorCount_ - 1) % kMaxAnchorRing];
    int64_t queued = lastQueuedHint_;
    if (queued < 0 && lastFramesWritten_ >= 0)
        queued = std::max<int64_t>(0, lastFramesWritten_ - newest.frame);
    if (queued < 0) queued = 0;

    // Air time of the write head, relative to now:
    //   tau(f_head) = a + b * f_head  (RAW ns)
    //   delay = tau(f_head) - now + L_codec
    const int64_t fHead = newest.frame + queued;
    const double tauHead = a + b * static_cast<double>(fHead);
    const double delay = (tauHead - static_cast<double>(nowRawNanos)) +
                         static_cast<double>(est.priorNanos);
    est.delayNanos = static_cast<int64_t>(std::clamp(delay, 0.0, 1.0e9));
    return est;
}

int64_t HardwareLatencyProfiler::emissionTimeNanosForFrame(
    int64_t framePosition, const PtpEngine& ptp) const {
    std::lock_guard<std::mutex> lock(mutex_);
    // T_emission = T_trackposition + Delta_PTP + L_DAC/A2DP   (mission 3C)
    double a = 0.0, b = 0.0, sigma = 0.0;
    if (anchorCount_ >= cfg_.minAnchorsForFit && fitLine(a, b, sigma)) {
        const int64_t tauRaw =
            static_cast<int64_t>(a + b * static_cast<double>(framePosition));
        return ptp.convertRawToMasterNanos(tauRaw) +
               priorLatencyNanos(route_, codec_);
    }
    // Degraded path: newest anchor + nominal-rate extrapolation.
    if (anchorCount_ > 0) {
        const Anchor& newest = anchors_[(anchorHead_ + anchorCount_ - 1) % kMaxAnchorRing];
        const double nsPerFrame = 1e9 / cfg_.nominalSampleRateHz;
        const int64_t tauRaw = newest.tauRaw +
            static_cast<int64_t>(static_cast<double>(framePosition - newest.frame) * nsPerFrame);
        return ptp.convertRawToMasterNanos(tauRaw) +
               priorLatencyNanos(route_, codec_);
    }
    // No anchors at all: assume the frame emits one prior-latency from now.
    return ptp.convertRawToMasterNanos(nowRawNanos()) +
           priorLatencyNanos(route_, codec_);
}

} // namespace streamify::engine
