// ============================================================================
//  tests/jni_stub/jni.h — MINIMAL host-side JNI stub for the C1 bridge
//  round-trip suite (native/tests/jni_stub/jni.h)
// ============================================================================
//
//  WHY THIS EXISTS. `native/src/jni_bridge_audio_sinks.cpp` includes the
//  real <jni.h> (NDK) when it compiles into libstreamify_native_core.so
//  for Android. The C1 acceptance criterion — "ringbuffer round-trip
//  test through JNI" — must run on the HOST sanitizer lanes (C3's
//  matrix), where no NDK exists. This directory is added FIRST in the
//  include path of `jni_sink_bridge_test_suite` ONLY, so <jni.h> (and
//  <JniShield.h>'s transitive expectations) resolve to this stub inside
//  that one target; every other lane (Android .so, host test suites,
//  fuzzers) never sees it.
//
//  The stub implements EXACTLY the JNIEnv surface the sink bridge uses
//  (GetArrayLength, GetPrimitiveArrayCritical / Release…Critical,
//  GetDirectBufferAddress, GetDirectBufferCapacity) as C++ member
//  wrappers over payload structs the test constructs — the same
//  function-table indirection shape as the real jni.h, so the bridge TU
//  compiles unmodified against both.
//
//  Fidelity notes:
//   * Fake arrays/buffers are plain structs whose first member is the
//     _jobject header (pointer-interconvertible via reinterpret_cast —
//     the standard test-double idiom for opaque JNI handles).
//   * GetDirectBufferAddress on a non-direct-tagged object returns
//     nullptr, mirroring the real VM: the bridge's "not a direct
//     buffer" degradation is exercisable.
//   * ReleasePrimitiveArrayCritical honors the mode argument
//     informationally only (the stub never copies).
// ============================================================================
#ifndef STREAMIFY_TEST_JNI_STUB_H_
#define STREAMIFY_TEST_JNI_STUB_H_

#include <stddef.h>
#include <stdint.h>

// ---- primitive JNI types ----------------------------------------------------
typedef uint8_t jboolean;
typedef int8_t jbyte;
typedef int16_t jshort;
typedef int32_t jint;
typedef int64_t jlong;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;

#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_OK 0
#define JNI_ERR (-1)
#define JNI_ABORT 2
#define JNI_COMMIT 1
#define JNI_VERSION_1_6 0x00010006

// Host-export spellings (irrelevant on a test binary, kept so JNIEXPORT
// usage compiles verbatim).
#if defined(_WIN32)
#define JNIEXPORT __declspec(dllexport)
#else
#define JNIEXPORT __attribute__((visibility("default")))
#endif
#define JNICALL

// ---- opaque handle types -----------------------------------------------------
struct _jobject {
    enum Tag {
        TAG_GENERIC = 0,
        TAG_FLOAT_ARRAY,
        TAG_BYTE_ARRAY,
        TAG_DIRECT_BUFFER,
    } tag;
};
typedef _jobject* jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jthrowable;
typedef jobject jarray;
typedef jarray jfloatArray;
typedef jarray jbyteArray;
typedef jarray jintArray;
typedef jarray jlongArray;
typedef jarray jobjectArray;

// ---- test-double payloads (construct in the test, cast to the handle) --------
struct JniStubFloatArray {
    _jobject header;  // TAG_FLOAT_ARRAY
    jfloat* elems;
    jsize length;
};

struct JniStubDirectBuffer {
    _jobject header;  // TAG_DIRECT_BUFFER
    void* address;
    jlong capacity;
};

// ---- the stub function table + JNIEnv ----------------------------------------
typedef struct JNINativeInterface_ JNINativeInterface;

struct JNIEnv_ {
    const JNINativeInterface* functions;  // unused by the stub bodies

    jsize GetArrayLength(jarray array) {
        if (array == nullptr || array->tag != _jobject::TAG_FLOAT_ARRAY) {
            return 0;
        }
        return reinterpret_cast<JniStubFloatArray*>(array)->length;
    }

    void* GetPrimitiveArrayCritical(jarray array, jboolean* /* isCopy */) {
        if (array == nullptr || array->tag != _jobject::TAG_FLOAT_ARRAY) {
            return nullptr;
        }
        return reinterpret_cast<JniStubFloatArray*>(array)->elems;
    }

    void ReleasePrimitiveArrayCritical(jarray /* array */, void* /* elems */,
                                       jint /* mode */) {
        // The stub never copies; nothing to undo.
    }

    void* GetDirectBufferAddress(jobject buf) {
        if (buf == nullptr || buf->tag != _jobject::TAG_DIRECT_BUFFER) {
            return nullptr;  // not a direct buffer — the real VM contract
        }
        return reinterpret_cast<JniStubDirectBuffer*>(buf)->address;
    }

    jlong GetDirectBufferCapacity(jobject buf) {
        if (buf == nullptr || buf->tag != _jobject::TAG_DIRECT_BUFFER) {
            return -1;
        }
        return reinterpret_cast<JniStubDirectBuffer*>(buf)->capacity;
    }
};

typedef struct JNIEnv_ JNIEnv;

#endif  // STREAMIFY_TEST_JNI_STUB_H_
