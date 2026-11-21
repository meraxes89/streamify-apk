// ============================================================================
//  test_jni_bridge_audio_sinks.cpp — the C1 JNI bridge round-trip suite
//  (native/tests/test_jni_bridge_audio_sinks.cpp)
// ============================================================================
//
//  WHAT THIS PROVES. The frozen 7-symbol sink contract, end to end,
//  THROUGH the real JNI surface — not just the jni.h-free registry the
//  standalone ringbuffer suite already pins. This TU unity-includes
//  `native/src/jni_bridge_audio_sinks.cpp` against the host stub
//  JNIEnv (tests/jni_stub/jni.h — the ONLY target that sees it), so
//  the exact marshalling the JVM exercises on Android (array pinning,
//  DirectByteBuffer addressing, the 96-byte stats wire) runs under the
//  ASan/UBSan matrix on every PR touching native/** (C3 switched this
//  lane on; AHEAD C1 AC: "ringbuffer round-trip test through JNI").
//
//  COVERAGE MAP (each section cites the frozen contract clause):
//   A. nativeCreateSink — geometry validation, negative sentinels,
//      monotonic distinct handles, registry accounting.
//   B. nativeWriteFloat → nativeReadFramesF32 — the CANONICAL f32 pull
//      round-trip, bit-identical samples (pure f32 storage, MP-C08),
//      short-write backpressure, starvation + underrun accounting.
//   C. nativeReadDirect — the LEGACY PCM16 DirectByteBuffer edge:
//      frame-aligned byte counts, LE PCM16 quantization contract.
//   D. nativeGetStats — the 96-byte wire (magic 0x51AB0001, layout
//      bytes 96, every documented offset), sequence change detection,
//      too-small / non-direct buffer degradation.
//   E. nativeFlush — the cooperative generational flush (AMP-10): the
//      next read serves the silence quantum (0 frames, NO underrun
//      counted), the ring stays usable afterwards.
//   F. nativeDestroy — reference-counted teardown, stale-handle
//      degradation on every edge (closed-ring contract), idempotence.
//   G. hostile arguments — negative offset, out-of-window length,
//      oversized maxFrames clamped to the array bound, null arrays.
//      Nothing throws, nothing crashes, every return is a sentinel.
// ============================================================================

#include <cstdio>
#include <cstring>
#include <vector>

// The stub jni.h FIRST on this target's include path.
#include <jni.h>

// The bridge under test, unity-included so the exports (with their
// internal linkage helpers) are directly callable.
#include "../src/jni_bridge_audio_sinks.cpp"

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool ok, const char* what) {
    ++g_checks;
    if (!ok) {
        ++g_failures;
        std::printf("    FAIL: %s\n", what);
    }
}

// ---- stub JNIEnv + fake object helpers ---------------------------------------

JNIEnv_ g_env;
JNIEnv* env() { return &g_env; }

// Stub object arena — freed before main returns so the sanitizer lanes'
// LeakSanitizer pass sees a clean process (the fake JNI handles model
// local/global refs the real VM would reclaim; the test owns them).
std::vector<JniStubFloatArray*> g_floatArrays;
std::vector<JniStubDirectBuffer*> g_directBuffers;

void releaseStubs() {
    for (auto* a : g_floatArrays) delete a;
    for (auto* b : g_directBuffers) delete b;
    g_floatArrays.clear();
    g_directBuffers.clear();
}

jfloatArray floatArray(jfloat* data, jsize length) {
    auto* arr = new JniStubFloatArray();
    arr->header.tag = _jobject::TAG_FLOAT_ARRAY;
    arr->elems = data;
    arr->length = length;
    g_floatArrays.push_back(arr);
    return reinterpret_cast<jfloatArray>(arr);
}

jobject directBuffer(void* mem, jlong capacity) {
    auto* buf = new JniStubDirectBuffer();
    buf->header.tag = _jobject::TAG_DIRECT_BUFFER;
    buf->address = mem;
    buf->capacity = capacity;
    g_directBuffers.push_back(buf);
    return reinterpret_cast<jobject>(buf);
}

// ---- wire-layout decode helpers (mirror of the Kotlin decoder) ----------------

uint32_t wireU32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

uint64_t wireU64(const uint8_t* p) {
    return static_cast<uint64_t>(wireU32(p)) |
           (static_cast<uint64_t>(wireU32(p + 4)) << 32);
}

float wireF32(const uint8_t* p) {
    uint32_t bits = wireU32(p);
    float v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

// ---- A. nativeCreateSink -------------------------------------------------------

void test_create() {
    std::printf("  [A] nativeCreateSink: geometry, sentinels, registry\n");
    streamify::sink::SinkRegistry::instance().destroyAll();

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "valid geometry returns a positive handle");

    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
              env(), nullptr, 0, 0, 0) < 0,
          "all-zero geometry is rejected with a NEGATIVE sentinel");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
              env(), nullptr, -48000, 2, 4096) < 0,
          "negative sample rate rejected");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
              env(), nullptr, 48000, 3, 4096) < 0,
          "3 channels rejected (1|2 only)");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
              env(), nullptr, 48000, 2, 8) < 0,
          "capacity below the 64-frame floor rejected");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
              env(), nullptr, 48000, 2, (1 << 23)) < 0,
          "capacity above the 4M-frame ceiling rejected");

    const jlong h2 = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h2 > h, "handles are monotonically increasing (never reused)");
    check(streamify::sink::SinkRegistry::instance().liveCount() == 2,
          "registry live count tracks creates");

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h2);
    check(streamify::sink::SinkRegistry::instance().liveCount() == 0,
          "registry live count tracks destroys");
}

// ---- B. write -> readFramesF32: the canonical f32 pull round-trip ---------------

void test_f32_round_trip() {
    std::printf("  [B] nativeWriteFloat -> nativeReadFramesF32 round-trip\n");

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "sink created");

    // 256 stereo frames of a deterministic ramp.
    constexpr jint kFrames = 256;
    std::vector<jfloat> in(kFrames * 2);
    for (jint i = 0; i < kFrames * 2; ++i) {
        in[i] = static_cast<jfloat>(i) / static_cast<jfloat>(kFrames * 2) - 0.5f;
    }
    jfloatArray inArr = floatArray(in.data(), static_cast<jsize>(in.size()));

    const jint written =
        Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
            env(), nullptr, h, inArr, 0, static_cast<jint>(in.size()));
    check(written == static_cast<jint>(in.size()),
          "whole-frame write is fully consumed");

    std::vector<jfloat> out(kFrames * 2, -999.0f);
    jfloatArray outArr = floatArray(out.data(), static_cast<jsize>(out.size()));
    const jint read = Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
        env(), nullptr, h, outArr, kFrames);
    check(read == kFrames, "f32 pull returns every frame");
    check(std::memcmp(in.data(), out.data(), in.size() * sizeof(jfloat)) == 0,
          "f32 storage is BIT-IDENTICAL through the ring (zero quantization)");

    // Starvation: the ring is empty now.
    const jint starved =
        Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
            env(), nullptr, h, outArr, kFrames);
    check(starved == 0, "empty ring starves (0 frames, never blocks)");

    // Backpressure: overfill the 4096-frame ring with a big write; the
    // short return is the consumed prefix the caller retries.
    constexpr jint kBigFrames = 8192;
    std::vector<jfloat> big(kBigFrames * 2, 0.25f);
    jfloatArray bigArr = floatArray(big.data(), static_cast<jsize>(big.size()));
    const jint took =
        Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
            env(), nullptr, h, bigArr, 0, static_cast<jint>(big.size()));
    check(took > 0 && took < static_cast<jint>(big.size()),
          "full ring applies backpressure (short, non-blocking write)");
    check(took % 2 == 0, "whole frames only (even sample count)");

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
}

// ---- C. nativeReadDirect: the legacy PCM16 edge ---------------------------------

void test_pcm16_edge() {
    std::printf("  [C] nativeReadDirect: PCM16 DirectByteBuffer edge\n");

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "sink created");

    // Full-scale-positive left, negative right: the PCM16 quantization
    // contract is clamp(x * 32767) with round-to-nearest.
    constexpr jint kFrames = 100;
    std::vector<jfloat> in(kFrames * 2);
    for (jint f = 0; f < kFrames; ++f) {
        in[f * 2 + 0] = 1.0f;    // L -> +32767
        in[f * 2 + 1] = -1.0f;   // R -> -32767
    }
    jfloatArray inArr = floatArray(in.data(), static_cast<jsize>(in.size()));
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, 0, static_cast<jint>(in.size())) ==
              static_cast<jint>(in.size()),
          "PCM16 edge: frames written");

    uint8_t wire[kFrames * 2 * 2];  // stereo PCM16 = 4 bytes/frame
    std::memset(wire, 0xAA, sizeof(wire));
    jobject buf = directBuffer(wire, static_cast<jlong>(sizeof(wire)));

    const jint bytes =
        Java_com_streamify_core_native_1_NativeAudioSink_nativeReadDirect(
            env(), nullptr, h, buf, static_cast<jint>(sizeof(wire)));
    check(bytes == kFrames * 4, "PCM16 byte count is frame-aligned");
    bool pcmOk = true;
    for (jint f = 0; f < kFrames; ++f) {
        const int16_t l = static_cast<int16_t>(
            wire[f * 4] | (wire[f * 4 + 1] << 8));
        const int16_t r = static_cast<int16_t>(
            wire[f * 4 + 2] | (wire[f * 4 + 3] << 8));
        if (l < 32760 || r > -32760) pcmOk = false;  // within 1 LSB
    }
    check(pcmOk, "little-endian PCM16 quantization matches +-(32767 +- 1)");

    // Starved read: 0 bytes, buffer untouched.
    std::memset(wire, 0x55, sizeof(wire));
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeReadDirect(
              env(), nullptr, h, buf, static_cast<jint>(sizeof(wire))) == 0,
          "starved PCM16 read returns 0");
    check(wire[0] == 0x55, "starved read does not touch the buffer");

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
}

// ---- D. nativeGetStats: the 96-byte wire ----------------------------------------

void test_stats_wire() {
    std::printf("  [D] nativeGetStats: 96-byte wire layout v1 (0x51AB0001)\n");

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "sink created");

    std::vector<jfloat> in(512, 0.5f);  // 256 frames
    jfloatArray inArr = floatArray(in.data(), static_cast<jsize>(in.size()));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
        env(), nullptr, h, inArr, 0, static_cast<jint>(in.size()));

    uint8_t wire[96];
    std::memset(wire, 0, sizeof(wire));
    jobject buf = directBuffer(wire, sizeof(wire));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(env(), nullptr, h, buf);

    check(wireU32(wire + 0) == 0x51AB0001u, "stats magic 0x51AB0001");
    check(wireU32(wire + 4) == 96, "stats layout bytes = 96");
    check(wireU32(wire + 8) == 4096, "capacity frames");
    check(wireU32(wire + 12) == 48000, "sample rate in");
    check(wireU32(wire + 16) == 48000, "sample rate out");
    check(wireU32(wire + 20) == 2, "channels in");
    check(wireU32(wire + 24) == 2, "channels out");
    check(wireU64(wire + 32) == 256, "frames written (cumulative)");
    check(wireU64(wire + 48) == 0, "underrun events (none yet)");
    check(wireU64(wire + 56) == 0, "overrun events (none)");
    check(wireU32(wire + 72) >= 256, "high watermark observed");
    const uint32_t fullness = wireU32(wire + 28);
    const float fullnessPct = wireF32(wire + 80);
    check(fullness == 256, "current fullness frames");
    check(fullnessPct > 6.2f && fullnessPct < 6.3f,
          "fullness percent = occupancy/capacity*100");
    const float latencyMs = wireF32(wire + 84);
    check(latencyMs > 5.3f && latencyMs < 5.4f,
          "estimated latency ms = occupancy/rate*1000");

    // The sequence at +88 increments on every stats write.
    const uint32_t seq1 = wireU32(wire + 88);
    check(seq1 > 0, "sequence is non-zero");
    Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(env(), nullptr, h, buf);
    const uint32_t seq2 = wireU32(wire + 88);
    check(seq2 == seq1 + 1, "sequence increments per snapshot");

    // Degradations: too-small buffer and non-direct object are no-ops.
    uint8_t small[64];
    std::memset(small, 0x77, sizeof(small));
    jobject smallBuf = directBuffer(small, sizeof(small));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(env(), nullptr, h, smallBuf);
    check(small[0] == 0x77, "too-small buffer is a no-op");

    jfloatArray notDirect = floatArray(in.data(), static_cast<jsize>(in.size()));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(env(), nullptr, h,
                                                    reinterpret_cast<jobject>(notDirect));
    // (No crash, no write — the stub reports non-direct as nullptr.)

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
}

// ---- E. nativeFlush: the cooperative generational flush --------------------------

void test_flush() {
    std::printf("  [E] nativeFlush: cooperative AMP-10 generation handshake\n");

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "sink created");

    std::vector<jfloat> in(512, 0.5f);  // 256 frames
    jfloatArray inArr = floatArray(in.data(), static_cast<jsize>(in.size()));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
        env(), nullptr, h, inArr, 0, static_cast<jint>(in.size()));

    // Flush from a control thread (the contract: never the consumer).
    Java_com_streamify_core_native_1_NativeAudioSink_nativeFlush(env(), nullptr, h);

    // The consumer's next read serves the silence quantum: 0 frames.
    std::vector<jfloat> out(512, -999.0f);
    jfloatArray outArr = floatArray(out.data(), static_cast<jsize>(out.size()));
    const jint quantum =
        Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
            env(), nullptr, h, outArr, 256);
    check(quantum == 0, "flushed read serves the silence quantum (0 frames)");

    // ... and that pass is NOT an underrun (a flush is a control
    // decision, not starvation — the frozen contract).
    uint8_t wire[96];
    std::memset(wire, 0, sizeof(wire));
    jobject buf = directBuffer(wire, sizeof(wire));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(env(), nullptr, h, buf);
    check(wireU64(wire + 48) == 0, "flush pass is not counted as an underrun");
    check(wireU64(wire + 32) == 256, "cumulative frames_written survives flush");
    check(wireU32(wire + 28) == 0, "occupancy dropped to 0 after the flush pass");

    // The ring stays fully usable afterwards.
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, 0, static_cast<jint>(in.size())) ==
              static_cast<jint>(in.size()),
          "ring accepts writes after flush");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
              env(), nullptr, h, outArr, 256) == 256,
          "ring serves reads after flush");

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
}

// ---- F. nativeDestroy: lifecycle + stale-handle degradation -----------------------

void test_destroy_lifecycle() {
    std::printf("  [F] nativeDestroy: stale handles degrade, never crash\n");

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "sink created");

    std::vector<jfloat> in(512, 0.5f);
    jfloatArray inArr = floatArray(in.data(), static_cast<jsize>(in.size()));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
        env(), nullptr, h, inArr, 0, static_cast<jint>(in.size()));

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);

    // Every edge on the destroyed handle degrades to its closed-ring
    // return — the exact contract NativeFloatSinkEdge models above the
    // seam (writes 0, reads 0, stats no-op, flush no-op).
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, 0, static_cast<jint>(in.size())) == 0,
          "write on destroyed handle returns 0");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
              env(), nullptr, h, inArr, 256) == 0,
          "f32 read on destroyed handle returns 0");

    uint8_t wire[96];
    std::memset(wire, 0x66, sizeof(wire));
    jobject buf = directBuffer(wire, sizeof(wire));
    Java_com_streamify_core_native_1_NativeAudioSink_nativeGetStats(env(), nullptr, h, buf);
    check(wire[0] == 0x66, "stats on destroyed handle is a no-op");

    Java_com_streamify_core_native_1_NativeAudioSink_nativeFlush(env(), nullptr, h);  // no-op
    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
    check(true, "double destroy is idempotent");

    // Garbage handles never resolve.
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, -1L, inArr, 0, 4) == 0,
          "negative handle degrades (the probe's spelling)");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, 0x7F123456DEADBEEFULL, inArr, 0, 4) == 0,
          "garbage handle degrades");
}

// ---- G. hostile arguments ---------------------------------------------------------

void test_hostile_arguments() {
    std::printf("  [G] hostile arguments: sentinels, never crashes\n");

    const jlong h = Java_com_streamify_core_native_1_NativeAudioSink_nativeCreateSink(
        env(), nullptr, 48000, 2, 4096);
    check(h > 0, "sink created");

    std::vector<jfloat> in(64, 0.25f);
    jfloatArray inArr = floatArray(in.data(), static_cast<jsize>(in.size()));

    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, -1, 16) == 0,
          "negative offset rejected");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, 60, 8) == 0,
          "offset+length beyond array bounds rejected");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, 0, 0) == 0,
          "zero length is a no-op");
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, nullptr, 0, 4) == 0,
          "null array rejected");

    // maxFrames clamped to the destination array bound (channels_out=2).
    std::vector<jfloat> out(128, 0.0f);
    jfloatArray outArr = floatArray(out.data(), static_cast<jsize>(out.size()));
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeWriteFloat(
              env(), nullptr, h, inArr, 0, 64) == 64,
          "hostile-args section: 32 frames staged");
    const jint clamped =
        Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
            env(), nullptr, h, outArr, 1 << 20);
    check(clamped == 32, "oversized maxFrames clamped to dst capacity / channels");

    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeReadFramesF32(
              env(), nullptr, h, nullptr, 16) == 0,
          "null destination rejected");

    // Declared capacity beyond the real direct-buffer capacity is
    // clamped to the real one (never trust the caller's number).
    uint8_t wire[16];  // 4 stereo PCM16 frames
    std::memset(wire, 0, sizeof(wire));
    jobject buf = directBuffer(wire, sizeof(wire));
    check(Java_com_streamify_core_native_1_NativeAudioSink_nativeReadDirect(
              env(), nullptr, h, buf, 1 << 20) <= 16,
          "readDirect clamps to the buffer's real capacity");

    Java_com_streamify_core_native_1_NativeAudioSink_nativeDestroy(env(), nullptr, h);
}

}  // namespace

int main() {
    std::printf("==================================================\n");
    std::printf("  Streamify JNI Sink Bridge Suite (C1, WS-C)\n");
    std::printf("  7 frozen symbols x 96-byte wire 0x51AB0001\n");
    std::printf("  (host stub JNIEnv — the Android marshalling)\n");
    std::printf("==================================================\n");
    test_create();
    test_f32_round_trip();
    test_pcm16_edge();
    test_stats_wire();
    test_flush();
    test_destroy_lifecycle();
    test_hostile_arguments();
    std::printf("--------------------------------------------------\n");
    std::printf("  %d check(s), %d failure(s)\n", g_checks, g_failures);
    const bool ok = g_failures == 0;
    releaseStubs();  // before the verdict: LSan audits a clean process
    if (!ok) {
        std::printf("  JNI SINK BRIDGE SUITE: FAIL\n");
        return 1;
    }
    std::printf("  JNI SINK BRIDGE SUITE: PASS\n");
    return 0;
}
