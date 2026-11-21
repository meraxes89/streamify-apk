// ============================================================================
//  jni_bridge_oboe.cpp — the JNI wrappers over the Oboe bracket's C ABI
//  (Sovereign HAL Phase 2 / Step 1).
//  (native/src/jni_bridge_oboe.cpp)
// ============================================================================
//
//  BINDING GEOMETRY: `SovereignOboeJni` (sovereign-player androidMain)
//  declares the `external fun sovereign_oboe_*` members directly on the
//  object and loads libstreamify_native_core.so. The JNI symbol names
//  are the standard JVM mangling of the object's class name + member
//  (underscores in the member escape to `_1`) — spelled out LITERALLY
//  on each export below (the parity gate parses source text; comments
//  here avoid writing partial Java_ names it could mistake for
//  exports).
//
//  Every wrapper is a thin, exception-safe forward to the bracket's
//  plain C exports (oboe_output_bracket.cpp): no registry, no handles
//  beyond the process-singleton bracket, no allocation on the audio
//  path (the wrappers themselves run on Kotlin caller threads — the
//  audio thread never crosses this boundary).
//
//  ZERO-CRASH DISCIPLINE: the bracket's exports never throw; the
//  wrappers only translate C sentinels into Kotlin shapes (null for
//  "no sample yet", 0/1 for "attached/detached").
// ============================================================================

#include <jni.h>

#include <cstdint>

namespace {

// The bracket's C ABI (oboe_output_bracket.cpp).
extern "C" {
int32_t sovereign_oboe_open(int32_t audio_api);
void sovereign_oboe_close();
uint64_t sovereign_oboe_callbacks();
uint64_t sovereign_oboe_underrun_frames();
uint64_t sovereign_oboe_ring_occupancy();
int32_t sovereign_oboe_dac_clock(uint64_t* frame, uint32_t* rate, uint64_t* monotonic_ns);
int32_t sovereign_oboe_stats_wire(uint8_t* dst, int32_t dst_bytes);
int32_t sovereign_oboe_api_route();
}

}  // namespace

// The externals live on the SovereignOboeJni object itself (the parity
// gate's mangling contract: file-level class, no nested objects). The
// symbols are written LITERALLY (no macro): the parity gate parses
// Java_* export names out of the source text, and a macro-indirect
// name is invisible to it.

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1open(JNIEnv*, jobject, jint audio_api) {
    return sovereign_oboe_open(audio_api);
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1close(JNIEnv*, jobject) {
    sovereign_oboe_close();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1callbacks(JNIEnv*, jobject) {
    return static_cast<jlong>(sovereign_oboe_callbacks());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1underrun_1frames(JNIEnv*, jobject) {
    return static_cast<jlong>(sovereign_oboe_underrun_frames());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1ring_1occupancy(JNIEnv*, jobject) {
    return static_cast<jlong>(sovereign_oboe_ring_occupancy());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1dac_1clock(
    JNIEnv* env, jobject, jlongArray frame_out, jintArray rate_out, jlongArray ns_out) {
    if (!frame_out || !rate_out || !ns_out) return 0;
    uint64_t frame = 0;
    uint32_t rate = 0;
    uint64_t ns = 0;
    const int32_t got = sovereign_oboe_dac_clock(&frame, &rate, &ns);
    if (got != 1) return 0;
    jlong jf = static_cast<jlong>(frame);
    jint jr = static_cast<jint>(rate);
    jlong jn = static_cast<jlong>(ns);
    env->SetLongArrayRegion(frame_out, 0, 1, &jf);
    env->SetIntArrayRegion(rate_out, 0, 1, &jr);
    env->SetLongArrayRegion(ns_out, 0, 1, &jn);
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1stats_1wire(
    JNIEnv* env, jobject, jbyteArray dst, jint dst_bytes) {
    if (!dst) return 0;
    jsize len = env->GetArrayLength(dst);
    if (len < 96 || dst_bytes < 96) return 0;
    // The 96-byte image is plain LE bytes — no JNI critical section
    // needed for a buffer this small; GetByteArrayRegion copies once.
    uint8_t wire[96];
    const int32_t ok = sovereign_oboe_stats_wire(wire, sizeof(wire));
    if (ok != 1) return 0;
    env->SetByteArrayRegion(dst, 0, 96, reinterpret_cast<jbyte*>(wire));
    return 1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_sovereign_player_android_SovereignOboeJni_sovereign_1oboe_1api_1route(JNIEnv*, jobject) {
    return sovereign_oboe_api_route();
}
