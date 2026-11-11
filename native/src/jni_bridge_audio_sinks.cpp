// ============================================================================
//  jni_bridge_audio_sinks.cpp — the C1 JNI sink bridge over the FROZEN
//  7-symbol audio-sink contract (WS-C / AHEAD C1)
//  (native/src/jni_bridge_audio_sinks.cpp)
// ============================================================================
//
//  THE MISSING LINK. `native/include/streamify_audio_sinks.h` froze the
//  contract (7 JNI symbols, opaque registry handles, the 96-byte
//  DirectByteBuffer stats wire with magic 0x51AB0001); ENG-1 mirrored it
//  field-for-field on the Rust side (`streamify-playback/src/sink.rs` —
//  the trait the engine's `playback_ring()` consumer handle implements);
//  the app seam has been waiting (`RustAudioSinkJni.kt`,
//  `NativeAudioSink.kt`, `AudioRoutePolicy`'s degradation ladder). This
//  TU is the Android mounting bracket: the seven exports below bind the
//  Kotlin declarations onto the hardened C++ `AudioSink` SPSC ring via
//  the jni.h-free `SinkRegistry` + `WriteSinkStatsBuffer` support layer.
//
//  MOUNTING GEOMETRY. Every platform lane mounts against the SAME frozen
//  ABI shape: the Rust engine exposes it through `playback_ring()`
//  (Arc<SpscAudioRing>, the consumer handle the A5 self-test and the
//  desktop/Apple brackets pull from); Android mounts it here, through
//  the JNI symbols over the C++ registry mirror. Because both sides
//  implement the identical lock-free SPSC cursor protocol, the identical
//  cooperative RequestFlush generation handshake (AMP-10 / MP-C10) and
//  the identical 96-byte stats wire, any layout or semantics drift trips
//  in CI: `test_audio_sink_ringbuffer.cpp` (C++ ring),
//  `test_ring.rs` / `test_uniffi_contract.rs` (Rust ring),
//  `jni_sink_bridge_test_suite` (THIS bridge, through a stub JNIEnv on
//  the host sanitizer lanes) and `RustAudioSinkJniStatsDecoderTest`
//  (the Kotlin wire decoder) all pin the same numbers.
//
//  ANDROID DATA PLANE (the dogfood pull path this file lights up):
//
//      Media3 renderer (producer)  --nativeWriteFloat-->   C++ AudioSink
//      (SPSC f32 ring, registry handle)                    ^          |
//                                                           |          v
//      AudioTrackPullOutput  <--nativeReadFramesF32-- feeder thread (urgent
//      (ENCODING_PCM_FLOAT)      nativeGetStats          audio priority)
//
//  SAFETY MODEL (all seven exports):
//   * JNI_SHIELD — every body is wrapped in the house try/catch
//     (JniShield.h); a C++ exception maps onto the export's sentinel,
//     never across the JNI boundary (the Rust catch_unwind twin).
//   * Handles are registry int64s — NEVER pointers. A stale, garbage or
//     post-destroy handle resolves to nullptr and the export degrades
//     to its "closed ring" return (0 / no-op), exactly the degradation
//     `NativeFloatSinkEdge` models above the seam.
//   * No export throws, allocates on the audio path, or blocks.
//     Array pinning uses GetPrimitiveArrayCritical with every length
//     resolved BEFORE the pin (the documented Phase-4a discipline) and
//     JNI_ABORT on the write edge (no copy-back of unmodified data).
//   * `nativeCreateSink` failure sentinels are NEGATIVE (Kotlin-side
//     frozen doc: "negative sentinel on invalid configuration / registry
//     exhaustion"; the app checks `handle >= 0`). The registry's own 0
//     failure spelling is mapped here — returning 0 raw would silently
//     satisfy the app's `check(handle >= 0L)` and create a half-open
//     edge on handle 0.
//
//  THREADING: WriteFloat is the single-producer edge (Media3 renderer
//  thread); ReadFramesF32/ReadDirect are the single-consumer edge (the
//  feeder thread); GetStats/Flush/Destroy are any-thread. The registry
//  lock is held only for the map resolve (nanoseconds), never across
//  audio work. `nativeFlush` routes to the cooperative RequestFlush —
//  safe while the feeder is live; MUST be called from a producer/control
//  thread (never the consumer thread itself), the contract documented on
//  both sides of the ABI.
// ============================================================================

#include <jni.h>

#include <algorithm>
#include <cstddef>
#include <cstdint>

#include "../include/streamify_audio_sinks.h"
#include "../jni/JniShield.h"

namespace {

using streamify::sink::AudioSink;
using streamify::sink::SinkRegistry;
using streamify::sink::WriteSinkStatsBuffer;
using streamify::sink::kSinkStatsWireBytes;

// Failure sentinels for nativeCreateSink (negative per the Kotlin-side
// frozen contract; see the file header's safety model).
constexpr jlong kCreateRejectedConfig = -1;    // out-of-bounds geometry
constexpr jlong kCreateRejectedRegistry = -2;  // registry/OOM failure

// Resolves a registry handle to a live sink, or nullptr for stale /
// garbage / destroyed handles (the shared_ptr keeps the sink alive for
// the duration of the call even if a destroy races the resolve).
inline std::shared_ptr<AudioSink> resolveSink(jlong handle) {
    return SinkRegistry::instance().resolve(static_cast<int64_t>(handle));
}

}  // namespace

// ----------------------------------------------------------------------------
// 1/7 — nativeCreateSink(sampleRate, channels, capacityFrames): Long
//     One SPSC f32 sink ring. Returns the opaque monotonic registry
//     handle (> 0) or a negative sentinel on rejection.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jlong JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        JNIEnv* /* env */, jclass /* clazz */, jint sampleRate, jint channels,
        jint capacityFrames) {
    JNI_SHIELD(kCreateRejectedConfig);

    AudioSink::Config cfg;
    cfg.sample_rate = static_cast<uint32_t>(sampleRate);
    cfg.channels = static_cast<uint32_t>(channels);
    cfg.capacity_frames = static_cast<size_t>(capacityFrames);
    // Negative jints widen to huge uint32/size_t values and are rejected
    // by the same bounds check — no special-casing needed.
    if (!AudioSink::ValidateConfig(cfg)) {
        return kCreateRejectedConfig;
    }
    const int64_t handle = SinkRegistry::instance().create(cfg);
    if (handle == 0) {
        return kCreateRejectedRegistry;
    }
    return static_cast<jlong>(handle);

    JNI_SHIELD_END(kCreateRejectedConfig);
}

// ----------------------------------------------------------------------------
// 2/7 — nativeWriteFloat(handle, data, offset, length): Int
//     Producer edge: whole-frame interleaved f32 write, non-blocking.
//     Returns the SAMPLE count consumed (short return = backpressure).
//     0 on a stale handle, bad bounds or a torn pin — the closed-ring
//     contract NativeFloatSinkEdge models.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
        JNIEnv* env, jclass /* clazz */, jlong handle, jfloatArray data,
        jint offset, jint length) {
    JNI_SHIELD(0);

    auto sink = resolveSink(handle);
    if (!sink || data == nullptr || offset < 0 || length <= 0) {
        return 0;
    }
    // Resolve every length BEFORE pinning (Phase-4a discipline): a bad
    // (offset, length) window must reject without ever entering a
    // critical section.
    const jsize arrayLen = env->GetArrayLength(data);
    if (static_cast<jlong>(offset) + static_cast<jlong>(length) >
        static_cast<jlong>(arrayLen)) {
        return 0;
    }
    jfloat* pinned = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(data, nullptr));
    if (pinned == nullptr) {
        return 0;  // JVM refused the pin (GC pressure): report backpressure
    }
    const size_t consumed =
        sink->WriteFloat(pinned + offset, static_cast<size_t>(length));
    // JNI_ABORT: the ring only READS the pinned samples — never copy back.
    env->ReleasePrimitiveArrayCritical(data, pinned, JNI_ABORT);
    // WriteFloat's return is bounded by `length` (fits jint by
    // construction), but clamp defensively against any future drift.
    return static_cast<jint>(std::min<size_t>(consumed, 0x7FFFFFFFu));

    JNI_SHIELD_END(0);
}

// ----------------------------------------------------------------------------
// 3/7 — nativeReadDirect(handle, buf, capacityBytes): Int
//     LEGACY PCM16 consumer edge (the frozen DirectByteBuffer contract):
//     frame-aligned PCM16 bytes into a direct buffer, quantized AT this
//     edge from the f32 ring. Returns bytes copied (multiple of the
//     frame size), 0 when starved / handle stale / buffer not direct.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeReadDirect(
        JNIEnv* env, jclass /* clazz */, jlong handle, jobject buf,
        jint capacityBytes) {
    JNI_SHIELD(0);

    auto sink = resolveSink(handle);
    if (!sink || buf == nullptr) {
        return 0;
    }
    uint8_t* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(buf));
    if (dst == nullptr) {
        return 0;  // not a direct ByteBuffer (or torn): closed edge
    }
    const jlong bufCap = env->GetDirectBufferCapacity(buf);
    if (bufCap <= 0) {
        return 0;
    }
    // The caller's declared capacity is advisory; never trust it past
    // the buffer's real capacity, and clamp into jint range.
    jlong want = (capacityBytes > 0)
                     ? std::min<jlong>(capacityBytes, bufCap)
                     : bufCap;
    want = std::min<jlong>(want, 0x7FFFFFF0LL);
    // Frame-align down to the PCM16 output frame so ReadBytes never
    // splits a frame across calls.
    const size_t frameBytes = sink->frame_bytes_out();
    if (frameBytes > 0) {
        want -= want % static_cast<jlong>(frameBytes);
    }
    if (want <= 0) {
        return 0;
    }
    const size_t got = sink->ReadBytes(dst, static_cast<size_t>(want));
    return static_cast<jint>(std::min<size_t>(got, 0x7FFFFFFFu));

    JNI_SHIELD_END(0);
}

// ----------------------------------------------------------------------------
// 4/7 — nativeReadFramesF32(handle, dst, maxFrames): Int
//     CANONICAL f32 pull edge (MP-HAL05 additive): up to maxFrames
//     interleaved f32 output frames straight out of the float ring —
//     zero quantization between producer and DAC. Returns the FRAME
//     count read; 0 == starved (one underrun event inside the ring).
//     maxFrames is clamped to dst capacity / channels_out; never
//     allocates, never blocks, never throws.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
        JNIEnv* env, jclass /* clazz */, jlong handle, jfloatArray dst,
        jint maxFrames) {
    JNI_SHIELD(0);

    auto sink = resolveSink(handle);
    if (!sink || dst == nullptr || maxFrames <= 0) {
        return 0;
    }
    const jsize floats = env->GetArrayLength(dst);
    const uint32_t channels = sink->channels_out();
    if (channels == 0) {
        return 0;
    }
    const jlong framesByArray =
        static_cast<jlong>(floats) / static_cast<jlong>(channels);
    const jlong frames =
        std::min<jlong>(static_cast<jlong>(maxFrames), framesByArray);
    if (frames <= 0) {
        return 0;
    }
    jfloat* pinned = static_cast<jfloat*>(
        env->GetPrimitiveArrayCritical(dst, nullptr));
    if (pinned == nullptr) {
        return 0;
    }
    const size_t got =
        sink->ReadFramesF32(pinned, static_cast<size_t>(frames));
    // Mode 0: the ring WROTE into the pinned storage — copy back.
    env->ReleasePrimitiveArrayCritical(dst, pinned, 0);
    return static_cast<jint>(std::min<size_t>(got, 0x7FFFFFFFu));

    JNI_SHIELD_END(0);
}

// ----------------------------------------------------------------------------
// 5/7 — nativeGetStats(handle, statsOut): void
//     Serializes the 96-byte little-endian stats wire (magic 0x51AB0001,
//     layout v1 — WriteSinkStatsBuffer) into a DirectByteBuffer with
//     capacity >= 96. The `sequence` field at +88 increments on every
//     write: the app-side change detector (RustAudioSinkJni.decodeStats)
//     drops stale images. No-op on a stale handle / non-direct / too
//     small buffer — "no stats", never a crash.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(
        JNIEnv* env, jclass /* clazz */, jlong handle, jobject statsOut) {
    JNI_SHIELD();

    auto sink = resolveSink(handle);
    if (!sink || statsOut == nullptr) {
        return;
    }
    uint8_t* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(statsOut));
    if (dst == nullptr) {
        return;
    }
    const jlong cap = env->GetDirectBufferCapacity(statsOut);
    if (cap < static_cast<jlong>(kSinkStatsWireBytes)) {
        return;
    }
    (void)WriteSinkStatsBuffer(*sink, dst, kSinkStatsWireBytes);

    JNI_SHIELD_END();
}

// ----------------------------------------------------------------------------
// 6/7 — nativeFlush(handle): void
//     Cooperative generational flush (AMP-10 / MP-C10): bumps the flush
//     generation and bounded-waits (<= kFlushAckTimeoutMs) for the
//     CONSUMER thread's ack; the consumer performs the cursor reset at
//     the top of its next read. Safe while the feeder is live; MUST be
//     called from a producer/control thread. No-op on stale handles.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeFlush(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    JNI_SHIELD();

    auto sink = resolveSink(handle);
    if (!sink) {
        return;
    }
    sink->RequestFlush();  // noexcept by contract (atomics + clock reads)

    JNI_SHIELD_END();
}

// ----------------------------------------------------------------------------
// 7/7 — nativeDestroy(handle): void
//     Reference-counted destroy: drops the registry entry; a destroy
//     racing an in-flight call just unmaps the handle (the shared_ptr
//     keeps the sink alive until the last caller is done). Idempotent
//     for stale handles.
// ----------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(
        JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
    JNI_SHIELD();

    (void)SinkRegistry::instance().destroy(static_cast<int64_t>(handle));

    JNI_SHIELD_END();
}
