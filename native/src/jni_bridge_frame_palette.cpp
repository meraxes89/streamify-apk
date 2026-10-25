// ============================================================================
//  jni_bridge_frame_palette.cpp — Phase-5 palette + frame pacing bridge
//  (native/src/jni_bridge_frame_palette.cpp)
// ============================================================================
//
//  JNI SURFACE — frozen by the Phase-5 directive (deliverable 3), bound to
//  com.streamify.core.native NativePalette / NativeFramePacer via app-side
//  RegisterNatives (the 'native' package segment is a Java keyword; the
//  binding class escapes it with a legal name, exactly like Phase-4's
//  NativeAudioSink):
//
//    jint     Java_..._NativePalette_nativeExtractPaletteSimd(
//                 JNIEnv*, jobject, jobject directByteBuffer, jint width,
//                 jint height, jint stride, jintArray outColors)
//    jboolean Java_..._NativePalette_nativeCheckContrast(
//                 JNIEnv*, jobject, jint colorA, jint colorB, jfloat minRatio)
//    void     Java_..._NativeFramePacer_nativeRecordFrame(
//                 JNIEnv*, jobject, jlong frameDurationNs, jlong vsyncOffsetNs)
//    void     Java_..._NativeFramePacer_nativeGetStats(
//                 JNIEnv*, jobject, jobject outStatsObject)
//    void     Java_..._NativeFramePacer_nativeReset(JNIEnv*, jobject)
//
//  CONTRACT
//  --------
//  * Zero-copy: palette pixels are read straight from the DirectByteBuffer's
//    native address (GetDirectBufferAddress) — no intermediate copy, no
//    GetPrimitiveArrayCritical pin on the pixel path. The extractor scratch
//    is thread_local (~0.56 MB once per thread, then allocation-free).
//  * outColors is pinned with GetPrimitiveArrayCritical only AFTER every
//    bound (array length, buffer capacity, dimensions, stride) has been
//    resolved, and released on every path — no JNI call inside the critical
//    section (Phase-4 discipline).
//  * nativeCheckContrast rejects non-finite thresholds via a fast-math-
//    unfoldable bit-pattern inspection (Phase-4 lesson: isfinite()/NaN
//    comparison ladders get folded away under -ffast-math LTO).
//  * nativeRecordFrame routes into the process-default monitor
//    (streamify_frame_palette.h): negative jlong durations become huge u64
//    values and land in the invalid-sample quarantine, never in the ring.
//  * nativeGetStats writes the documented 192-byte little-endian layout when
//    outStatsObject is a direct buffer with capacity >= 192; anything else
//    is a silent no-op (a telemetry probe must never crash the render path).
//  * Defensive posture: every entrypoint null-checks env/args, validates
//    ranges with overflow-safe uint64 math, and wraps the body in
//    try/catch(...) so no C++ exception can ever escape into the JVM.
//  * THREADING: nativeRecordFrame is the single writer (Choreographer/UI
//    thread); nativeGetStats is the single reader (Compose telemetry
//    poller) — the SPSC contract from frame_pacer_monitor.h.
// ============================================================================

#include <jni.h>

#include <cstdint>
#include <cstring>

#include "../include/palette_extractor_simd.h"
#include "../include/streamify_frame_palette.h"

using streamify::pacer::DefaultFramePacer;
using streamify::pacer::WriteFramePacerStatsBuffer;
using streamify::palette_simd::ContrastRatio;
using streamify::palette_simd::ExtractPaletteSimd;
using streamify::palette_simd::MeetsContrast;
using streamify::palette_simd::PaletteError;
using streamify::palette_simd::PaletteRoles;

namespace {

// DirectBuffer address + capacity validation. Returns false unless the
// object is a direct ByteBuffer with a positive capacity.
bool directBufferAccess(JNIEnv* env, jobject buffer, uint8_t** addr,
                        size_t* capacity) {
    if (env == nullptr || buffer == nullptr) return false;
    void* p = env->GetDirectBufferAddress(buffer);
    const jlong cap = (p != nullptr) ? env->GetDirectBufferCapacity(buffer) : 0;
    if (p == nullptr || cap <= 0) return false;
    *addr = static_cast<uint8_t*>(p);
    *capacity = static_cast<size_t>(cap);
    return true;
}

// Fast-math-unfoldable NaN inspection (bit pattern, Phase-4 lesson).
inline bool jfloatIsNan(jfloat f) {
    uint32_t bits = 0;
    std::memcpy(&bits, &f, sizeof(bits));
    return (bits & 0x7F800000u) == 0x7F800000u && (bits & 0x007FFFFFu) != 0;
}

// Map the core error enum to the documented jint return codes (identity:
// the enum values ARE the contract).
inline jint paletteErrorToJint(PaletteError e) {
    return static_cast<jint>(static_cast<int32_t>(e));
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_streamify_core_native_NativePalette_nativeExtractPaletteSimd(
    JNIEnv* env, jobject /* thiz */, jobject directByteBuffer, jint width,
    jint height, jint stride, jintArray outColors) {
    try {
        if (env == nullptr) return paletteErrorToJint(PaletteError::kNullArgument);
        if (directByteBuffer == nullptr || outColors == nullptr) {
            return paletteErrorToJint(PaletteError::kNullArgument);
        }
        // Resolve every bound BEFORE pinning anything.
        const jsize out_len = env->GetArrayLength(outColors);
        if (out_len < 4) {
            return paletteErrorToJint(PaletteError::kBadBufferSize);
        }
        uint8_t* addr = nullptr;
        size_t buf_cap = 0;
        if (!directBufferAccess(env, directByteBuffer, &addr, &buf_cap)) {
            return paletteErrorToJint(PaletteError::kNullArgument);
        }

        PaletteRoles roles;
        const PaletteError e = ExtractPaletteSimd(
            addr, buf_cap, width, height, stride, &roles);
        if (e != PaletteError::kOk) return paletteErrorToJint(e);

        // Critical section: 4 int stores, no JNI calls inside.
        jint* raw =
            static_cast<jint*>(env->GetPrimitiveArrayCritical(outColors, nullptr));
        if (raw == nullptr) {
            return paletteErrorToJint(PaletteError::kNullArgument);  // pin failed
        }
        raw[0] = static_cast<jint>(roles.dominant_vibrant);
        raw[1] = static_cast<jint>(roles.dark_muted);
        raw[2] = static_cast<jint>(roles.light_vibrant);
        raw[3] = static_cast<jint>(roles.text_surface);
        env->ReleasePrimitiveArrayCritical(outColors, raw, 0);
        return 4;  // roles written
    } catch (...) {
        return paletteErrorToJint(PaletteError::kNullArgument);  // never crash the JVM
    }
}

JNIEXPORT jboolean JNICALL
Java_com_streamify_core_native_NativePalette_nativeCheckContrast(
    JNIEnv* env, jobject /* thiz */, jint colorA, jint colorB,
    jfloat minRatio) {
    try {
        if (env == nullptr) return JNI_FALSE;
        if (jfloatIsNan(minRatio)) return JNI_FALSE;
        const uint32_t a = static_cast<uint32_t>(colorA);
        const uint32_t b = static_cast<uint32_t>(colorB);
        return MeetsContrast(a, b, minRatio) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_streamify_core_native_NativeFramePacer_nativeRecordFrame(
    JNIEnv* env, jobject /* thiz */, jlong frameDurationNs,
    jlong vsyncOffsetNs) {
    try {
        if (env == nullptr) return;
        // Negative jlong durations wrap to huge u64 values and are
        // quarantined as invalid samples by the monitor (documented).
        DefaultFramePacer().RecordFrame(
            static_cast<uint64_t>(frameDurationNs),
            static_cast<int64_t>(vsyncOffsetNs));
    } catch (...) {
        return;
    }
}

JNIEXPORT void JNICALL
Java_com_streamify_core_native_NativeFramePacer_nativeGetStats(
    JNIEnv* env, jobject /* thiz */, jobject outStatsObject) {
    try {
        if (env == nullptr) return;
        uint8_t* addr = nullptr;
        size_t buf_cap = 0;
        if (!directBufferAccess(env, outStatsObject, &addr, &buf_cap)) {
            return;  // silent no-op — telemetry must never crash the app
        }
        WriteFramePacerStatsBuffer(DefaultFramePacer(), addr, buf_cap);
    } catch (...) {
        return;
    }
}

JNIEXPORT void JNICALL
Java_com_streamify_core_native_NativeFramePacer_nativeReset(
    JNIEnv* env, jobject /* thiz */) {
    try {
        if (env == nullptr) return;
        DefaultFramePacer().Reset();  // quiesce writer & reader first (contract)
    } catch (...) {
        return;
    }
}

}  // extern "C"
