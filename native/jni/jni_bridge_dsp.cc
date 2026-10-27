// ============================================================================
//  jni_bridge_dsp.cc — dedicated JNI bridge for the DSP / clock / latency core
// ============================================================================
//
//  FROZEN ABI (mission brief section 4 — Engineer 3 binds Kotlin against
//  these EXACT symbols; names, argument order and return types are contract):
//
//    nativePtpProcessTimestamps(t0, t1, t2, t3) -> jlong
//        Feed one completed sync exchange and get the filtered offset back.
//          t0 : LOCAL  clock, TX of request        (CLOCK_MONOTONIC_RAW ns)
//          t1 : MASTER clock, RX of request        (master timebase ns)
//          t2 : MASTER clock, TX of response       (master timebase ns)
//          t3 : LOCAL  clock, RX of response       (CLOCK_MONOTONIC_RAW ns)
//        Returns Theta = T_master - T_local [ns]; positive means the master
//        clock reads AHEAD of this device.
//
//    nativeGetSynchronizedClockNanos() -> jlong
//        Current instant on the MASTER timebase [ns], extrapolated between
//        exchanges by the two-state Kalman tracker. Wait-free (seqlock) —
//        safe to call from the audio callback thread.
//
//    nativePtpReset() -> void
//        Full filter reset (learned network RTT floor is retained).
//
//    nativeResamplerSetTargetDriftNanos(driftNanos) -> void
//        driftNanos: rate adjustment in NANOSECONDS of media-time advance
//        PER SECOND of playback (ns/s). +500_000 == +500 PPM == play 0.05%
//        fast (catch up); -500_000 == slow down. Hard-clamped to
//        +-500_000. The engine slews toward the command with tau = 250 ms,
//        so pitch steps stay below 0.001 cents per frame.
//
//    nativeResamplerProcessBuffer(in, out, frameCount, channelCount,
//                                 sampleRate) -> jint
//        Interleaved 32-bit float PCM through direct ByteBuffers.
//        REQUIREMENTS:
//          * both buffers direct, LITTLE-ENDIAN floats
//            (ByteBuffer.allocateDirect(n).order(ByteOrder.LITTLE_ENDIAN))
//          * in:  capacity >= frameCount * channelCount * 4 bytes
//          * out: capacity >= frameCount * channelCount * 4 bytes
//                 (ratio <= 1 means we can emit slightly MORE frames than
//                  input; leftovers buffer internally and drain next call)
//          * frameCount <= 16384 (split larger blocks upstream)
//        Returns frames produced (>= 0), or:
//          -1  invalid arguments (null / non-direct / bad ranges)
//          -2  block larger than the engine's max input block
//          -3  (reserved) unsupported buffer geometry
//          -4  output buffer smaller than the input block
//          -5  backpressure: drain outputs first, then re-feed this block
//
//    nativeGetHardwarePlayoutDelayNanos() -> jlong
//        Best estimate of write-head -> air latency [ns]. Live
//        regression-backed when audio timestamps are being submitted (see
//        the optional extensions below); otherwise the documented prior for
//        the currently set output route/codec.
//
//  OPTIONAL EXTENSIONS (additive, stable, NOT part of the frozen contract —
//  Engineer 3 may adopt them for live calibration and telemetry):
//
//    nativeSubmitAudioTimestamp(framePosition, nanoTimeMonotonic,
//                               framesWritten, queuedFramesHint)
//        Feed one AudioTrack.getTimestamp() sample to activate live latency
//        calibration (10-50 Hz recommended). nanoTimeMonotonic is the
//        AudioTimestamp.nanoTime value (CLOCK_MONOTONIC — the profiler
//        bridges it onto the unslewed RAW timebase internally).
//
//    nativeSetAudioOutputRoute(route, codec)
//        Route: 0 unknown, 1 speaker, 2 wired jack, 3 USB DAC, 4 BT A2DP,
//        5 BT LE Audio. Codec: 0 unknown, 1 PCM, 2 SBC, 3 AAC, 4 LDAC,
//        5 aptX, 6 aptX-Adaptive, 7 LC3. Call on every route change.
//
//    nativeGetPtpOffsetSigmaNanos() -> jlong
//    nativeGetPtpDriftPpm() -> jdouble
//    nativeGetPtpQualityScore() -> jint     (0..100, 100 = tight lock)
//    nativeResamplerGetMaxDriftNanos() -> jlong   (= 500_000, unit handshake)
//    nativeResamplerReset() -> void
//
//  THREADING: all entry points are thread-safe. The resampler process call
//  is single-producer (the audio callback); it performs zero allocations
//  and takes zero locks in steady state.
//
//  AMP-10 AUDIT (Phase 2 — Engineer Alpha, feat/alpha-phase2-uniffi-contract):
//  Invariants verified against 06bbd60 and hardened in this commit:
//    * FROZEN ABI STABILITY: the four contract symbols above are
//      byte-identical to the freeze (untouched since c7c4416) — no
//      signature, argument-order or return-type drift.
//    * ZERO-ALLOC HOT PATH: the bridge layer performs no heap allocation
//      by construction (GetDirectBufferAddress/Capacity queries and pure
//      validation only; config transitions rebuild tables on the control
//      side, steady-state configure() is a no-op). The engine side is
//      contractually allocation-free after configure() (see
//      AcousticPhaseResampler.h, PERFORMANCE CONTRACT). Enforced live by
//      test_dsp_phase_lock.cc testZeroAllocationHotPath: 2000 resampler
//      blocks + 300 PTP updates + 120 latency anchors under the armed
//      AllocGuard must observe exactly 0 allocations.
//    * ZERO-LOCK SEQLOCK: PtpEngine::getSynchronizedClockNanos() reads
//      the seqlock-published ClockSnapshot wait-free — acquire loads,
//      bounded 64-spin retry, coherent-copy fallback on pathological
//      writer pressure; never blocks, never allocates. Now additionally
//      proven at compile time in PtpEngine.h: trivially-copyable payload,
//      always-lock-free sequence counter, 64-byte cache-line isolation.
//    * PORTABLE TEST BUILDS: no -march=native anywhere in the tree (the
//      Phase 1 fix at 06bbd60 holds; the stale comment reference in
//      CMakeLists.txt is corrected in this commit). All four native test
//      targets compile with deterministic portable flags
//      (-O2 -fno-fast-math -fno-lto -fno-omit-frame-pointer).
// ============================================================================

#include <jni.h>
#include <cstdint>

#include "../engine/PtpEngine.h"
#include "../engine/HardwareLatencyProfiler.h"
#include "../dsp/AcousticPhaseResampler.h"
#include "SharedInstances.h"

using streamify::PtpEngine;
using streamify::dsp::AcousticPhaseResampler;
using streamify::engine::HardwareLatencyProfiler;

namespace {

// Process-wide engine instances. Singletons shared with the Phase-1 bridge
// (jni_bridge_native_dsp_engine.cc) via SharedInstances.h — one toggle
// (setSilentBypass) reaches both bridges' engines.
AcousticPhaseResampler& resamplerInstance() {
    return streamify::jni::sharedResampler();
}

HardwareLatencyProfiler& profilerInstance() {
    return streamify::jni::sharedProfiler();
}

} // namespace

// ============================================================================
// 1. High-Precision PTP Clock  (FROZEN ABI)
// ============================================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_nativePtpProcessTimestamps(
    JNIEnv* /* env */, jclass /* clazz */,
    jlong t0, jlong t1, jlong t2, jlong t3) {
    return static_cast<jlong>(
        PtpEngine::getInstance().processTimestamps(t0, t1, t2, t3));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_nativeGetSynchronizedClockNanos(
    JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jlong>(PtpEngine::getInstance().getSynchronizedClockNanos());
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativePtpReset(
    JNIEnv* /* env */, jclass /* clazz */) {
    PtpEngine::getInstance().reset();
}

// ============================================================================
// 2. Fractional Sinc Resampler  (FROZEN ABI)
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeResamplerSetTargetDriftNanos(
    JNIEnv* /* env */, jclass /* clazz */, jlong driftNanos) {
    resamplerInstance().setTargetDriftNanosPerSecond(driftNanos);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_nativeResamplerProcessBuffer(
    JNIEnv* env, jclass /* clazz */,
    jobject inputDirectByteBuffer,
    jobject outputDirectByteBuffer,
    jint frameCount,
    jint channelCount,
    jint sampleRate) {
    if (frameCount <= 0 || channelCount <= 0 || channelCount > 8 ||
        sampleRate < 8000 || sampleRate > 192000) {
        return -1;
    }
    if (inputDirectByteBuffer == nullptr || outputDirectByteBuffer == nullptr) {
        return -1;
    }

    auto& engine = resamplerInstance();

    // Config transitions (track change, sink renegotiation) rebuild the
    // kernel tables here; steady-state calls hit the no-op path — the hot
    // loop itself never allocates.
    engine.configure(channelCount, static_cast<double>(sampleRate));

    uint8_t* inBytes = static_cast<uint8_t*>(
        env->GetDirectBufferAddress(inputDirectByteBuffer));
    uint8_t* outBytes = static_cast<uint8_t*>(
        env->GetDirectBufferAddress(outputDirectByteBuffer));
    if (inBytes == nullptr || outBytes == nullptr) {
        return -1;   // not direct (or JVM refuses to expose the address)
    }

    const jlong inCapacityBytes = env->GetDirectBufferCapacity(inputDirectByteBuffer);
    const jlong outCapacityBytes = env->GetDirectBufferCapacity(outputDirectByteBuffer);
    const jlong inNeeded = static_cast<jlong>(frameCount) * channelCount * 4;
    if (inCapacityBytes < inNeeded || outCapacityBytes < inNeeded) {
        return -1;
    }
    if ((reinterpret_cast<uintptr_t>(inBytes) & 3u) != 0 ||
        (reinterpret_cast<uintptr_t>(outBytes) & 3u) != 0) {
        return -1;   // float alignment violated (never happens for fresh
                     // direct ByteBuffers, but the check costs 2 cycles)
    }

    const float* in = reinterpret_cast<const float*>(inBytes);
    float* out = reinterpret_cast<float*>(outBytes);
    const int outFrameCapacity =
        static_cast<int>(outCapacityBytes / (4LL * channelCount));

    return engine.process(in, frameCount, out, outFrameCapacity);
}

// ============================================================================
// 3. Hardware / Bluetooth Latency Calibration  (FROZEN ABI)
// ============================================================================
extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_nativeGetHardwarePlayoutDelayNanos(
    JNIEnv* /* env */, jclass /* clazz */) {
    const auto est = profilerInstance().estimatePlayoutDelay(streamify::nowRawNanos());
    return static_cast<jlong>(est.delayNanos);
}

// ============================================================================
// Optional extensions (documented above; additive, not part of the freeze)
// ============================================================================
extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeSubmitAudioTimestamp(
    JNIEnv* /* env */, jclass /* clazz */,
    jlong framePosition, jlong nanoTimeMonotonic,
    jlong framesWritten, jlong queuedFramesHint) {
    profilerInstance().submitTrackTimestamp(framePosition, nanoTimeMonotonic,
                                            framesWritten, queuedFramesHint);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeSetAudioOutputRoute(
    JNIEnv* /* env */, jclass /* clazz */, jint route, jint codec) {
    profilerInstance().setOutputRoute(
        static_cast<HardwareLatencyProfiler::Route>(route),
        static_cast<HardwareLatencyProfiler::Codec>(codec));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_nativeGetPtpOffsetSigmaNanos(
    JNIEnv* /* env */, jclass /* clazz */) {
    const double sigma = PtpEngine::getInstance().getOffsetSigmaNanos();
    return static_cast<jlong>(sigma < 0 ? 0 : sigma);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_streamify_app_data_NativeBridge_nativeGetPtpDriftPpm(
    JNIEnv* /* env */, jclass /* clazz */) {
    return static_cast<jdouble>(PtpEngine::getInstance().getDriftPpm());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_app_data_NativeBridge_nativeGetPtpQualityScore(
    JNIEnv* /* env */, jclass /* clazz */) {
    const auto stats = PtpEngine::getInstance().getStats();
    if (!stats.locked) return 0;
    // 100 at sigma <= 0 us, linear decay to 0 at 500 us.
    const double score = 100.0 - stats.offsetSigmaNanos / 5'000.0;
    return static_cast<jint>(score < 0.0 ? 0.0 : (score > 100.0 ? 100.0 : score));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_app_data_NativeBridge_nativeResamplerGetMaxDriftNanos(
    JNIEnv* /* env */, jclass /* clazz */) {
    return AcousticPhaseResampler::kMaxDriftNanosPerSec;   // 500_000 ns/s
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_app_data_NativeBridge_nativeResamplerReset(
    JNIEnv* /* env */, jclass /* clazz */) {
    resamplerInstance().reset();
}
