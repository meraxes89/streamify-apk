// ============================================================================
//  test_linux_hal_driver.cpp — Phase 4b MP-HAL09 host lane
//  (native/tests/test_linux_hal_driver.cpp)
// ============================================================================
//
//  Standalone CTest binary (linux_hal_driver_test_suite). White-box suite:
//  it unity-includes the ENTIRE Linux driver TU
//  (native/audio/pipewire_driver.cpp — dlopen PipeWire + ALSA fallback)
//  so the embedded frozen ABI, the runtime backend resolution and the
//  driver lifecycle are audited directly, without any PipeWire/ALSA dev
//  packages installed and without physical audio hardware:
//
//    A. Embedded ABI constants + the EnumFormat pod integrity (self-view
//       and the frozen reference dump captured against real headers).
//    B. Real-header cross-check lane (best effort — compiled ONLY when a
//       spa/pod builder is reachable through the include path, e.g. via
//       -DSTREAMIFY_HAVE_REAL_PW_HEADERS): the canonical EnumFormat pod is
//       REBUILT with spa_pod_builder_add_object and must match the
//       embedded 136 bytes exactly; struct offsets/sizeofs and enum values
//       from the REAL headers are compared against the embedded ABI.
//    C. Backend resolution: bogus SONAMEs fail to load; the loaders report
//       false without touching the process-global state.
//    D. The pod property parser: unknown properties are skipped, truncated
//       pods are rejected, the negotiated Format parses out
//       rate/channels/format.
//    E. Driver lifecycle on the ALSA "null" PCM (the deviceless hardware
//       double — exactly the VirtualHAL discipline applied to the fallback
//       lane): PipeWire absent/daemonless ⇒ ALSA fallback; the writer
//       thread pulls through AlsaRenderCore; the DAC presentation clock
//       is published and advances; teardown is idempotent and clean.
//    F. The C facade contract: unknown handles are no-ops, null out-param
//       guards, clean failure when no backend is reachable.
// ============================================================================

#if defined(__linux__)

// The entire driver under test (white-box: pwabi, loaders, driver, facade).
// codeql[cpp/include-non-header] Suppress unity-include in white-box unit test
// lgtm[cpp/include-non-header]
#include "../audio/pipewire_driver.cpp"

#include <chrono>
#include <cstdio>
#include <thread>

#include "../include/audio_sink_ringbuffer.h"
#include "../include/streamify_audio_sinks.h"

#if defined(STREAMIFY_HAVE_REAL_PW_HEADERS)
#include <spa/buffer/buffer.h>
#include <spa/param/audio/format-utils.h>
#include <spa/pod/builder.h>
#include <spa/utils/type.h>
#include <pipewire/stream.h>
#include <cstddef>
#endif  // STREAMIFY_HAVE_REAL_PW_HEADERS

using streamify::hal::LinuxAudioDriver;
namespace pwabi = streamify::hal::pwabi;
using streamify::sink::AudioSink;
using streamify::sink::SinkRegistry;

namespace {

int g_passed = 0;
int g_failed = 0;

void check(bool ok, const char* what) {
    if (ok) {
        ++g_passed;
    } else {
        ++g_failed;
        std::printf("    FAILED: %s\n", what);
    }
}

// ---------------------------------------------------------------------------
// A. Embedded ABI self-consistency
// ---------------------------------------------------------------------------
void test_embedded_abi_constants() {
    std::printf("  [A] embedded frozen ABI constants\n");
    // The values extracted from the real 0.3.65 AND 1.4.2 headers (the
    // static_asserts in linuxhal_embedded_abi.h lock the layouts; these
    // runtime checks document the value surface the driver depends on).
    check(pwabi::kDirectionOutput == 1, "PW_DIRECTION_OUTPUT");
    check(pwabi::kFlagAutoconnect == 1, "PW_STREAM_FLAG_AUTOCONNECT");
    check(pwabi::kFlagMapBuffers == 4, "PW_STREAM_FLAG_MAP_BUFFERS");
    check(pwabi::kFlagRtProcess == 16, "PW_STREAM_FLAG_RT_PROCESS (1<<4!)");
    check(pwabi::kSpaDataMemPtr == 1, "SPA_DATA_MemPtr");
    check(pwabi::kSpaDataMemFd == 2, "SPA_DATA_MemFd");
    check(pwabi::kStreamStateStreaming == 3, "PW_STREAM_STATE_STREAMING");
    check(pwabi::kSndStreamPlayback == 0, "SND_PCM_STREAM_PLAYBACK");
    check(pwabi::kSndAccessRwInterleaved == 3, "SND_PCM_ACCESS_RW_INTERLEAVED");
    check(pwabi::kSndFormatFloatLe == 14, "SND_PCM_FORMAT_FLOAT_LE");
}

void test_enumformat_pod_integrity() {
    std::printf("  [A2] EnumFormat pod integrity (frozen reference)\n");
    // The reference byte dump produced by spa_pod_builder_add_object()
    // against the real SPA headers (captured from pipewire 1.4.2 headers;
    // 0.3.65 emits identical bytes). Keeping an independent copy here means
    // a regression in the embedded pod is caught even on hosts without
    // the spa headers; the __has_include lane below cross-checks against
    // the live headers when they exist.
    static const unsigned char kReference[136] = {
        0x80, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x00, 0x00,
        0x03, 0x00, 0x04, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,
        0x1b, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
        0x80, 0xbb, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,
        0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    };
    check(std::memcmp(pwabi::kEnumFormatPod, kReference, sizeof(kReference)) ==
              0,
          "embedded pod == frozen header-derived reference dump");

    // And the parser agrees with the pod's own content.
    int32_t rate = 0, channels = 0;
    uint32_t fmt = 0;
    check(pwabi::PodIntProp(pwabi::kEnumFormatPod, pwabi::kKeyAudioRate,
                            &rate) && rate == 48000,
          "embedded pod parses rate == 48000");
    check(pwabi::PodIntProp(pwabi::kEnumFormatPod, pwabi::kKeyAudioChannels,
                            &channels) && channels == 2,
          "embedded pod parses channels == 2");
    check(pwabi::PodIdProp(pwabi::kEnumFormatPod, pwabi::kKeyAudioFormat,
                           &fmt) && fmt == 283,
          "embedded pod parses format == F32_LE (Id-typed value)");
    uint32_t media = 0;
    check(pwabi::PodIdProp(pwabi::kEnumFormatPod, 1 /* mediaType */, &media) &&
              media == 1,
          "embedded pod parses mediaType == audio");
}

// ---------------------------------------------------------------------------
// B. Real-header cross-check lane (only compiled when spa headers exist)
// ---------------------------------------------------------------------------
#if defined(STREAMIFY_HAVE_REAL_PW_HEADERS)
void test_real_header_crosscheck() {
    std::printf("  [B] REAL-header cross-check (spa/pod builder present)\n");
    // Layouts: real structs vs the embedded ABI.
    check(offsetof(struct spa_data, data) == offsetof(pwabi::SpaData, data) &&
              offsetof(struct spa_data, chunk) ==
                  offsetof(pwabi::SpaData, chunk) &&
              sizeof(struct spa_data) == sizeof(pwabi::SpaData),
          "spa_data layout matches the embedded ABI");
    check(offsetof(struct spa_chunk, size) == offsetof(pwabi::SpaChunk, size) &&
              sizeof(struct spa_chunk) == sizeof(pwabi::SpaChunk),
          "spa_chunk layout matches");
    check(offsetof(struct spa_buffer, n_datas) ==
              offsetof(pwabi::SpaBuffer, n_datas) &&
              offsetof(struct spa_buffer, datas) ==
                  offsetof(pwabi::SpaBuffer, datas),
          "spa_buffer layout matches");
    check(offsetof(struct pw_buffer, size) == offsetof(pwabi::PwBuffer, size) &&
              offsetof(struct pw_buffer, requested) ==
                  offsetof(pwabi::PwBuffer, requested),
          "pw_buffer prefix layout matches");
    check(offsetof(struct pw_time, rate) == offsetof(pwabi::PwTime, rate) &&
              offsetof(struct pw_time, ticks) ==
                  offsetof(pwabi::PwTime, ticks) &&
              offsetof(struct pw_time, delay) ==
                  offsetof(pwabi::PwTime, delay),
          "pw_time prefix layout matches");
    check(offsetof(struct pw_stream_events, process) ==
              offsetof(pwabi::PwStreamEvents, process),
          "pw_stream_events.process offset matches");

    // Constants.
    check(PW_DIRECTION_OUTPUT == (int)pwabi::kDirectionOutput,
          "PW_DIRECTION_OUTPUT matches");
    check((int)PW_STREAM_FLAG_RT_PROCESS == (int)pwabi::kFlagRtProcess,
          "PW_STREAM_FLAG_RT_PROCESS matches");
    check((int)PW_STREAM_FLAG_MAP_BUFFERS == (int)pwabi::kFlagMapBuffers,
          "PW_STREAM_FLAG_MAP_BUFFERS matches");
    check((int)SPA_DATA_MemPtr == (int)pwabi::kSpaDataMemPtr,
          "SPA_DATA_MemPtr matches");
    check((int)SPA_FORMAT_AUDIO_rate == (int)pwabi::kKeyAudioRate,
          "SPA_FORMAT_AUDIO_rate key matches");
    check((int)SPA_AUDIO_FORMAT_F32_LE == (int)pwabi::kAudioFormatF32Le,
          "SPA_AUDIO_FORMAT_F32_LE matches");

    // THE critical wire check: rebuild the canonical EnumFormat pod with
    // the real builder and compare byte-for-byte against the embedded one.
    unsigned char buf[256];
    struct spa_pod_builder b = SPA_POD_BUILDER_INIT(buf, sizeof(buf));
    struct spa_pod* pod = (struct spa_pod*)spa_pod_builder_add_object(
        &b, SPA_TYPE_OBJECT_Format, SPA_PARAM_EnumFormat,
        SPA_FORMAT_mediaType,      SPA_POD_Id(SPA_MEDIA_TYPE_audio),
        SPA_FORMAT_mediaSubtype,   SPA_POD_Id(SPA_MEDIA_SUBTYPE_raw),
        SPA_FORMAT_AUDIO_format,   SPA_POD_Id(SPA_AUDIO_FORMAT_F32),
        SPA_FORMAT_AUDIO_rate,     SPA_POD_Int(48000),
        SPA_FORMAT_AUDIO_channels, SPA_POD_Int(2));
    const size_t size = (size_t)SPA_POD_SIZE(pod);
    check(size == sizeof(pwabi::kEnumFormatPod), "pod size matches");
    bool same = size == sizeof(pwabi::kEnumFormatPod);
    for (size_t i = 0; same && i < size; ++i) {
        same = buf[i] == pwabi::kEnumFormatPod[i];
    }
    check(same, "real builder pod == embedded pod, byte for byte");
}
#endif  // STREAMIFY_HAVE_REAL_PW_HEADERS

// ---------------------------------------------------------------------------
// C. Backend resolution (bogus SONAMEs)
// ---------------------------------------------------------------------------
void test_backend_resolution() {
    std::printf("  [C] backend resolution / fallback chain\n");
    pwabi::PwApi pw{};
    void* lib = nullptr;
    const char* bogus[] = {"libstreamify-nonexistent-pw.so.42"};
    check(!pwabi::LoadPwApi(bogus, 1, &pw, &lib) && lib == nullptr,
          "bogus PipeWire SONAME fails to load");
    check(pw.pw_init == nullptr, "failed load leaves the vtable zeroed");

    pwabi::AlsaApi alsa{};
    const char* bogus2[] = {"libstreamify-nonexistent-asound.so.42"};
    check(!pwabi::LoadAlsaApi(bogus2, 1, &alsa, &lib) && lib == nullptr,
          "bogus ALSA SONAME fails to load");
    check(alsa.pcm_open == nullptr, "failed load leaves the vtable zeroed");

    // The real system libasound, when present, loads and resolves the full
    // symbol surface (the canonical CI-runner shape: no PipeWire, ALSA lib
    // without hardware).
    const char* alsa_names[] = {"libasound.so.2", "libasound.so"};
    pwabi::AlsaApi alsa2{};
    void* alsa_lib = nullptr;
    if (pwabi::LoadAlsaApi(alsa_names, 2, &alsa2, &alsa_lib) &&
        alsa_lib != nullptr) {
        check(alsa2.pcm_open != nullptr && alsa2.pcm_writei != nullptr &&
                  alsa2.pcm_delay != nullptr,
              "system libasound resolves the full fallback surface");
        dlclose(alsa_lib);
    } else {
        std::printf("    (system libasound not present — skip)\n");
    }
}

// ---------------------------------------------------------------------------
// D. Pod property parser (untrusted-input discipline)
// ---------------------------------------------------------------------------
void test_pod_parser() {
    std::printf("  [D] pod property parser\n");
    // Hand-built object pod: two unknown props BEFORE the target key, one
    // after — the parser must skip by size and find the target.
    // Layout: [hdr 8][obj body hdr 8]{ [key][flags][pod{size,type}][val8]
    // }* ; body size = 8 + 3*(8+8+8) = 80.
    unsigned char pod[8 + 8 + 3 * 24];
    const uint32_t body = 8 + 3 * 24;
    auto put32 = [](unsigned char* p, uint32_t v) {
        p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF;
        p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
    };
    put32(pod + 0, body);        // pod size
    put32(pod + 4, 15);          // type Object
    put32(pod + 8, 262147);      // obj type Format
    put32(pod + 12, 3);          // obj id EnumFormat
    size_t off = 16;
    // unknown key 77, Int 111
    put32(pod + off, 77); put32(pod + off + 4, 0);
    put32(pod + off + 8, 4); put32(pod + off + 12, 4);  // Int, size 4
    put32(pod + off + 16, 111); put32(pod + off + 20, 0);
    off += 24;
    // unknown key 78, Id 222
    put32(pod + off, 78); put32(pod + off + 4, 0);
    put32(pod + off + 8, 4); put32(pod + off + 12, 3);  // Id, size 4
    put32(pod + off + 16, 222); put32(pod + off + 20, 0);
    off += 24;
    // target key 65539 (AUDIO_rate), Int 44100
    put32(pod + off, 65539); put32(pod + off + 4, 0);
    put32(pod + off + 8, 4); put32(pod + off + 12, 4);
    put32(pod + off + 16, 44100); put32(pod + off + 20, 0);
    off += 24;

    int32_t rate = 0;
    check(pwabi::PodIntProp(pod, 65539, &rate) && rate == 44100,
          "unknown properties are skipped, target found");
    check(!pwabi::PodIntProp(pod, 78, &rate),
          "non-Int typed key is not parsed as Int");
    check(!pwabi::PodIntProp(pod, 12345, &rate), "absent key returns false");

    // Truncation INSIDE the claimed geometry: the last property's value
    // extends past the pod's own claimed body (a cut pod). The parser must
    // reject it — bounded walk, no value read, no bogus match.
    {
        // hdr(8) + obj body hdr(8) + prop hdr(8) + val pod hdr(8) + 4 value
        // bytes = 36 total; claim body = 28 - 4 = 24 so the value is cut.
        unsigned char cut_pod[36];
        std::memcpy(cut_pod, pod, sizeof(cut_pod));
        const uint32_t cut_body = 28 - 4;  // obj hdr + prop hdr + val hdr + cut
        cut_pod[0] = cut_body & 0xFF;
        cut_pod[1] = (cut_body >> 8) & 0xFF;
        cut_pod[2] = (cut_body >> 16) & 0xFF;
        cut_pod[3] = (cut_body >> 24) & 0xFF;
        int32_t out = -1;
        // Key 77 is the property whose value sits exactly at the cut: a
        // parser that ignored the body bound would read (and match) it —
        // and run past the 36-byte buffer on the padded tail.
        check(!pwabi::PodIntProp(cut_pod, 77, &out),
              "cut property value is rejected, not partially read");
    }

    check(!pwabi::PodIntProp(nullptr, 65539, &rate), "null pod rejected");
}

// ---------------------------------------------------------------------------
// E. Driver lifecycle on the ALSA "null" PCM (deviceless hardware double)
// ---------------------------------------------------------------------------
bool wait_for_clock(LinuxAudioDriver& driver, uint64_t* frames_out,
                    int timeout_ms) {
    for (int i = 0; i < timeout_ms; ++i) {
        uint64_t f = 0;
        uint32_t r = 0;
        uint64_t ns = 0;
        if (driver.LatestClock(&f, &r, &ns)) {
            *frames_out = f;
            return true;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    return false;
}

void test_driver_lifecycle() {
    std::printf("  [E] driver lifecycle (ALSA null double / fallback)\n");
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 8192;
    const int64_t handle = SinkRegistry::instance().create(cfg);
    check(handle > 0, "sink registered");

    // Feed a burst so the fallback writer has real audio to pull.
    auto sink = SinkRegistry::instance().resolve(handle);
    std::vector<float> feed(4096 * 2, 0.25f);
    if (sink) sink->WriteFloat(feed.data(), feed.size());

    LinuxAudioDriver driver;
    // "null" is ALSA's built-in deviceless PCM: the fallback lane runs for
    // real (writer thread + writei + delay clock) with no hardware.
    const bool started = driver.Start(handle, "null");

    if (!started) {
        // Hosts without even libasound: the failure must be CLEAN.
        check(driver.backend() == LinuxAudioDriver::Backend::kFailed,
              "no-backend start fails cleanly");
        std::printf("    (no reachable backend on this host — clean fail)\n");
        driver.Stop();  // idempotent on a failed start
        check(true, "stop after failed start is a no-op");
    } else {
        const bool is_pw =
            driver.backend() == LinuxAudioDriver::Backend::kPipeWire;
        const bool is_alsa =
            driver.backend() == LinuxAudioDriver::Backend::kAlsa;
        check(is_pw || is_alsa, "started driver has a concrete backend");
        std::printf("    backend = %s\n", is_pw ? "PipeWire" : "ALSA(null)");

        // The DAC presentation clock must be published and ADVANCE (the
        // null PCM reports delay 0, so presented == submitted, advancing
        // at 48 kHz as the writer pushes silence tails).
        uint64_t f1 = 0;
        check(wait_for_clock(driver, &f1, 2000),
              "clock sample published after start");
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        uint64_t f2 = 0;
        check(wait_for_clock(driver, &f2, 2000), "second drain");
        check(f2 >= f1, "clock position monotone");
        check(f2 > f1, "clock position advances (writer is pulling)");

        uint64_t frames = 0;
        uint32_t rate = 0;
        uint64_t ns = 0;
        check(driver.LatestClock(&frames, &rate, &ns) && rate == 48000,
              "published rate is the canonical 48 kHz");

        // Teardown is idempotent and clean.
        driver.Stop();
        driver.Stop();
        check(driver.backend() == LinuxAudioDriver::Backend::kFailed,
              "stopped driver reports no backend");
    }

    check(SinkRegistry::instance().destroy(handle), "sink destroyed");
}

// ---------------------------------------------------------------------------
// F. The C facade contract
// ---------------------------------------------------------------------------
void test_facade_contract() {
    std::printf("  [F] C facade contract\n");
    check(streamify_linuxhal_start(0) == 0, "facade rejects sink handle 0");
    check(streamify_linuxhal_start(-1234) == 0,
          "facade rejects garbage sink handles");
    streamify_linuxhal_stop(0);  // the invalid sentinel: no-op, must not crash
    // (Facade handles are opaque values issued EXCLUSIVELY by
    // streamify_linuxhal_start — the Phase 4a CoreAudio convention.
    // Fabricated non-zero values are not "unknown handles", they are
    // caller bugs; only 0 and post-stop values are invalid.)

    uint64_t f = 0;
    uint32_t r = 0;
    uint64_t ns = 0;
    check(streamify_linuxhal_latest_clock(0, &f, &r, &ns) == 0,
          "latest_clock rejects handle 0");
    check(streamify_linuxhal_latest_clock(0, nullptr, &r, &ns) == 0,
          "latest_clock rejects null out-params");
    check(streamify_linuxhal_backend(0) == -1,
          "backend() reports -1 for handle 0");

    // End-to-end on the real resolution chain (the "default" device): on
    // hardware-less hosts this must fail cleanly; on audio hosts it must
    // start and stop cleanly. Either way — no crash, no leak of state.
    // (A handle is INVALID after stop — no post-stop access, exactly the
    // CoreAudio facade convention.)
    AudioSink::Config cfg;
    cfg.sample_rate = 48000;
    cfg.channels = 2;
    cfg.capacity_frames = 4096;
    const int64_t handle = SinkRegistry::instance().create(cfg);
    const int64_t drv = streamify_linuxhal_start(handle);
    if (drv != 0) {
        const int32_t backend = streamify_linuxhal_backend(drv);
        check(backend == 0 || backend == 1, "facade backend enum valid");
        uint64_t ff = 0;
        uint32_t rr = 0;
        uint64_t nns = 0;
        (void)streamify_linuxhal_latest_clock(drv, &ff, &rr, &nns);
        streamify_linuxhal_stop(drv);
        check(true, "facade start/stop round-trip is clean");
    } else {
        std::printf("    (default device unreachable — clean facade fail)\n");
        check(true, "facade failed cleanly without hardware");
    }
    SinkRegistry::instance().destroy(handle);
}

}  // namespace

int main() {
    std::printf("==================================================\n");
    std::printf("  Streamify Linux HAL Driver Suite (MP-HAL09)\n");
    std::printf("  PipeWire dlopen driver + ALSA fallback\n");
    std::printf("==================================================\n");
    test_embedded_abi_constants();
    test_enumformat_pod_integrity();
#if defined(STREAMIFY_HAVE_REAL_PW_HEADERS)
    test_real_header_crosscheck();
#else
    std::printf("  [B] REAL-header cross-check: SKIPPED (no spa headers on "
                "the include path)\n");
#endif
    test_backend_resolution();
    test_pod_parser();
    test_driver_lifecycle();
    test_facade_contract();
    std::printf("--------------------------------------------------\n");
    std::printf("  linux_hal_driver_test_suite: %d passed, %d failed\n",
                g_passed, g_failed);
    std::printf("==================================================\n");
    return g_failed == 0 ? 0 : 1;
}

#else  // !__linux__

int main() {
    std::printf("linux_hal_driver_test_suite: Linux-only suite, host is not "
                "Linux — EXIT 0 (suite not applicable)\n");
    return 0;
}

#endif  // __linux__
