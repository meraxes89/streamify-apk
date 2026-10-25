// ============================================================================
//  jni_bridge_native_dsp_engine.cc — Phase-1 audiophile DSP bridge (Gap #39,
//  #41, #13) for com.streamify.app.audio.NativeDspEngine (Engineer 3 binds
//  Kotlin against these EXACT symbols).
// ============================================================================
//
//  FROZEN JNI ABI (Phase-1 directive §5 — names, order, and types contract):
//
//    setLufsTarget(targetLufs: Float)                    // [-23, -11] clamped
//    setLimiterCeiling(ceilingDb: Float)                 // [-12, 0] clamped
//    setMonoDownmix(enabled: Boolean)
//    setBalance(balance: Float)                          // [-1, +1] clamped
//    setSilentBypass(silentBypass: Boolean)              // Gap #13
//    processFloatPcm(buffer: FloatArray, offset: Int, length: Int)
//
//  processFloatPcm CONTRACT
//  -----------------------
//  * `buffer` holds INTERLEAVED STEREO float PCM.
//  * `offset` is the index of the first float (sample), `length` is the
//    number of FLOATS to process; frames = length / 2 (an odd tail sample is
//    left untouched). The buffer is processed IN PLACE through:
//        LUFS glide gain -> mono downmix + balance -> true-peak limiter
//  * Zero-copy access via GetPrimitiveArrayCritical; zero allocation and
//    zero locks on the hot path.
//  * In silent bypass the call is a no-op (no sample is touched) while the
//    PTP/Kalman clock filter on the network thread keeps the device
//    phase-locked — instant host takeover (SINGLE_RENDER party mode).
//    setSilentBypass ALSO drives the shared AcousticPhaseResampler
//    (AcousticPhaseResampler::SetSilentBypass) used by the NativeBridge PLL
//    path — one toggle, both engines.
//
//  ADDITIVE EXTENSIONS (stable, not part of the freeze):
//    getIntegratedLufs(): Float    // gated running integrated, LUFS
//    getAppliedGainDb(): Float     // current normalization glide setpoint
//    configure(sampleRate: Int, channels: Int): Boolean
//      Rebuilds K-filters + limiter time constants for the ACTUAL output
//      geometry (audit #22 item 9 — the chain was pinned at 48 kHz
//      stereo; 44.1 kHz content got wrong K-weighting). Idempotent:
//      returns false when the geometry did not change.
//
//  THREADING: setters are control-thread; processFloatPcm is the audio
//  callback. All settings publish through atomics (see MasterChain).
// ============================================================================

#include <jni.h>

#include "../dsp/MasterChain.h"
#include "SharedInstances.h"

using streamify::dsp::MasterChain;
using streamify::jni::sharedResampler;

namespace {

MasterChain& chainInstance() {
    static MasterChain chain;   // 48 kHz stereo defaults
    return chain;
}

}  // namespace

extern "C" {

// ---- FROZEN ABI -------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeDspEngine_setLufsTarget(
    JNIEnv* /* env */, jclass /* clazz */, jfloat targetLufs) {
    chainInstance().setLufsTarget(targetLufs);
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeDspEngine_setLimiterCeiling(
    JNIEnv* /* env */, jclass /* clazz */, jfloat ceilingDb) {
    chainInstance().setLimiterCeiling(ceilingDb);
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeDspEngine_setMonoDownmix(
    JNIEnv* /* env */, jclass /* clazz */, jboolean enabled) {
    chainInstance().setMonoDownmix(enabled != JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeDspEngine_setBalance(
    JNIEnv* /* env */, jclass /* clazz */, jfloat balance) {
    chainInstance().setBalance(balance);
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeDspEngine_setSilentBypass(
    JNIEnv* /* env */, jclass /* clazz */, jboolean silentBypass) {
    const bool on = silentBypass != JNI_FALSE;
    chainInstance().setSilentBypass(on);          // audio path: zero CPU
    sharedResampler().SetSilentBypass(on);        // PLL resampler passthrough
}

JNIEXPORT void JNICALL
Java_com_streamify_app_audio_NativeDspEngine_processFloatPcm(
    JNIEnv* env, jclass /* clazz */, jfloatArray buffer, jint offset,
    jint length) {
    if (env == nullptr || buffer == nullptr) return;
    if (offset < 0 || length < 2) return;

    const jsize arrayLen = env->GetArrayLength(buffer);
    if (offset >= arrayLen) return;
    if (static_cast<jsize>(offset + length) > arrayLen) {
        length = arrayLen - offset;               // defensive clamp
    }
    if (length < 2) return;

    jfloat* p = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(buffer, nullptr));
    if (p == nullptr) return;

    // frames = float count / 2; an odd tail float is left untouched.
    chainInstance().process(p + offset, length / 2);

    env->ReleasePrimitiveArrayCritical(buffer, p, 0);   // 0 = copy back
}

// ---- Additive extensions (documented above; not part of the freeze) --------
JNIEXPORT jfloat JNICALL
Java_com_streamify_app_audio_NativeDspEngine_getIntegratedLufs(
    JNIEnv* /* env */, jclass /* clazz */) {
    return chainInstance().integratedLufs();
}

JNIEXPORT jfloat JNICALL
Java_com_streamify_app_audio_NativeDspEngine_getAppliedGainDb(
    JNIEnv* /* env */, jclass /* clazz */) {
    return chainInstance().appliedGainDb();
}

JNIEXPORT jboolean JNICALL
Java_com_streamify_app_audio_NativeDspEngine_configure(
    JNIEnv* /* env */, jclass /* clazz */, jint sampleRate, jint channels) {
    // Audit #22 item 9: let the audio path tell the chain its real
    // geometry (clamps + change-detection live in MasterChain).
    return chainInstance().configure(sampleRate, channels) ? JNI_TRUE
                                                           : JNI_FALSE;
}

}  // extern "C"
