#ifndef STREAMIFY_HARDWARE_LATENCY_PROFILER_H
#define STREAMIFY_HARDWARE_LATENCY_PROFILER_H

#include <cstdint>
#include <mutex>
#include "PtpEngine.h"

namespace streamify::engine {

// ============================================================================
//  HardwareLatencyProfiler — true acoustic emission-time calibration
// ============================================================================
//
//  PROBLEM
//  -------
//  Phone speakers, USB-C DACs and Bluetooth codecs delay audio by wildly
//  different physical amounts (8 ms .. 220 ms). Two devices can share a
//  perfect PTP lock and still comb-filter badly if one is on LDAC and the
//  other on the internal speaker. The acoustic controller needs the delay
//  from "frame handed to the audio sink" to "pressure wave leaves the
//  transducer", per device, live.
//
//  MODEL (mission brief 3C)
//  ------------------------
//      T_emission = T_trackposition + Delta_PTP + L_DAC/A2DP
//
//  where, for a frame f:
//    * T_trackposition(f) — the CLOCK_MONOTONIC instant the sink reports the
//      frame having reached its presentation point, obtained from
//      AudioTrack.getTimestamp() / AAudio timestamps, mapped onto the RAW
//      (unslewed) timebase.
//    * Delta_PTP — PTP offset translating local RAW time to the session
//      master timebase.
//    * L_DAC/A2DP — the residual analog/codec/transducer latency that the
//      frame-position timestamps do NOT include (DAC pipeline, BT codec
//      buffering, amplifier group delay).
//
//  ESTIMATOR
//  ---------
//  Anchor pairs (framePosition f_i, nanoTime tau_i) arrive ~10-50 Hz. The
//  sink's *effective* render rate differs from nominal (BT stacks resample;
//  codec clocks drift), so we fit
//
//      tau_i ~= a + b * f_i        (b = ns per frame, 1/b = effective Hz)
//
//  by time-exponentially-weighted least squares over a sliding window, with
//  MAD-based outlier rejection (two refinement passes). Slope b exposes the
//  effective-rate deviation in PPM; the fit residual sigma exposes trust.
//
//  MONOTONIC vs MONOTONIC_RAW — the subtle one
//  -------------------------------------------
//  AudioTrack nanoTime is CLOCK_MONOTONIC, which Android slews and steps
//  under NTP discipline. PTP runs on CLOCK_MONOTONIC_RAW. Mixing them
//  silently injects the NTP correction (up to 500 PPM!) into the acoustic
//  loop. We therefore track the (MONOTONIC - RAW) delta continuously with a
//  gated EMA and translate every anchor before it enters the regression.
//
//  LIVE vs PRIOR FALLBACK
//  ----------------------
//  Before enough anchors exist (or after route changes), the profiler
//  falls back to conservative literature-grade priors per route/codec so
//  the controller always has a usable number.
// ============================================================================

class HardwareLatencyProfiler {
public:
    enum class Route : int32_t {
        Unknown = 0,
        BuiltInSpeaker = 1,
        WiredAnalogJack = 2,   // 3.5 mm
        WiredUsbDac = 3,
        BluetoothA2dp = 4,
        BluetoothLeAudio = 5,
    };

    enum class Codec : int32_t {
        Unknown = 0,
        Pcm = 1,
        Sbc = 2,
        Aac = 3,
        Ldac = 4,
        Aptx = 5,
        AptxAdaptive = 6,
        Lc3 = 7,
    };

    struct Config {
        // Anchor window and weighting. 128 anchors x 20 Hz = 6.4 s of
        // history: the slope (effective-rate) estimate needs several seconds
        // of span to resolve PPM-level deviations; 64/3 s under-resolved it
        // (measured: ~35 PPM scatter where ~5 PPM is achievable).
        uint32_t maxAnchors = 128;      // sliding window length
        double anchorHalfLifeSec = 6.0; // exponential weight half-life
        double staleAfterSec = 2.5;     // anchors older than this => prior mode
        uint32_t minAnchorsForFit = 8;  // below this => prior mode
        // Outlier rejection.
        double madSigmaMultiplier = 3.0;
        // Clock-bridge EMA.
        double clockBridgeAlpha = 0.20;
        double clockBridgeGateNanos = 5'000'000.0;  // 5 ms gate on step noise
        // Frames the sink has accepted but not yet presented are estimated
        // from the anchor stream if the caller cannot supply a hint.
        double nominalSampleRateHz = 48'000.0;
    };

    // Aggregate readout for the acoustic controller.
    struct PlayoutEstimate {
        int64_t  delayNanos = 0;        // write-head -> air, best estimate
        bool     live = false;          // regression-backed vs prior
        double   effectiveRatePpm = 0.0;// sink rate vs nominal (BT resampling!)
        double   residualSigmaNanos = 0.0;
        uint32_t anchorsUsed = 0;
        int64_t  priorNanos = 0;        // the fallback that would be used
    };

    // Two-ctor split instead of `= Config()` default argument: an in-class
    // default argument would need the nested NSDMIs before the end of the
    // enclosing class (complete-class context) — rejected by GCC 9+/Clang.
    HardwareLatencyProfiler();           // HardwareLatencyProfiler(Config())
    explicit HardwareLatencyProfiler(const Config& cfg);

    // ---- Inputs ------------------------------------------------------------
    // One anchor from AudioTrack.getTimestamp() (or AAudioStream_getTimestamp):
    //   framePosition : total frames the sink has presented
    //   nanoTimeMonotonic : CLOCK_MONOTONIC ns of that presentation instant
    //   framesWritten : total frames the app has written into the sink
    //   queuedFramesHint : -1 if unknown
    void submitTrackTimestamp(int64_t framePosition,
                              int64_t nanoTimeMonotonic,
                              int64_t framesWritten,
                              int64_t queuedFramesHint = -1);

    // Notify on output route / codec changes (clears live state; the new
    // route's prior applies until fresh anchors accumulate).
    void setOutputRoute(Route route, Codec codec);

    // Sample the MONOTONIC <-> RAW pairing (called automatically inside
    // submitTrackTimestamp; exposed for tests and extra sampling).
    void sampleClockBridge(int64_t nanoTimeMonotonic);

    // ---- Readouts ----------------------------------------------------------
    // Delay from "a frame written now" to "its pressure wave leaves the
    // transducer": queued-buffer time + regression-extrapolated pipeline
    // delay + residual codec/transducer prior.
    PlayoutEstimate estimatePlayoutDelay(int64_t nowRawNanos) const;

    // Master-timebase instant at which frame f is emitted into the air
    // (mission brief formula, all three terms).
    int64_t emissionTimeNanosForFrame(int64_t framePosition,
                                      const PtpEngine& ptp) const;

    // Documented conservative priors [ns] for (route, codec).
    int64_t priorLatencyNanos(Route route, Codec codec) const;

    // Introspection for tests / telemetry; kMaxAnchors keeps the .cc ring
    // constants and the in-class storage in lockstep.
    static constexpr uint32_t kMaxAnchors = 128;

    // Raw accessors for telemetry/tests.
    Route currentRoute() const { return route_; }
    Codec currentCodec() const { return codec_; }
    double monotonicMinusRawNanos() const { return monoMinusRaw_; }
    double fittedNsPerFrame() const;      // 0 when no live fit
    uint32_t anchorCount() const;

    void reset();   // clear anchors (route/codec priors and bridge stay)

private:
    struct Anchor {
        int64_t frame;
        int64_t tauRaw;      // MONOTONIC mapped to RAW
        int64_t framesWritten;
        double  weight;
        int64_t tSubmitRaw;
    };

    // Weighted least squares of tau on f, with MAD outlier rejection.
    bool fitLine(double& intercept, double& slopeNanosPerFrame,
                 double& residualSigma) const;  // caller holds mutex_

    Config cfg_;
    Route route_ = Route::Unknown;
    Codec codec_ = Codec::Unknown;

    // Control-rate threading: submitTrackTimestamp() runs on a Kotlin
    // timer/coroutine, the readouts on the sync controller. Not for the
    // audio callback (see README for the intended data flow).
    mutable std::mutex mutex_;

    Anchor anchors_[kMaxAnchors];
    uint32_t anchorCount_ = 0;      // anchors_ is a FIFO ring
    uint32_t anchorHead_ = 0;

    // Clock bridge: MONOTONIC - RAW, gated EMA (ns).
    double monoMinusRaw_ = 0.0;
    bool   bridgeInitialized_ = false;

    // Last known write-head info.
    int64_t lastFramesWritten_ = -1;
    int64_t lastQueuedHint_ = -1;
};

} // namespace streamify::engine

#endif // STREAMIFY_HARDWARE_LATENCY_PROFILER_H
