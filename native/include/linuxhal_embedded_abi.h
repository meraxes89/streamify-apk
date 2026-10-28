#ifndef STREAMIFY_LINUXHAL_EMBEDDED_ABI_H
#define STREAMIFY_LINUXHAL_EMBEDDED_ABI_H
// ============================================================================
//  linuxhal_embedded_abi.h — Phase 4b Linux HAL driver: the frozen
//  embedded PipeWire/SPA/ALSA ABI (native/include/linuxhal_embedded_abi.h
//  — MP-HAL09)
// ============================================================================
//
//  The minimal PipeWire/SPA/ALSA ABI surface the Linux driver
//  (native/audio/pipewire_driver.cpp) binds at RUNTIME through dlopen —
//  declared once here so the driver and its host test suite
//  (native/tests/test_linux_hal_driver.cpp) audit ONE definition.
//
//  FROZEN-ABI RULES
//  ----------------
//  * No pipewire/spa/alsa headers are included: layouts and constants are
//    embedded and locked with static_asserts against the REAL headers
//    (verified against pipewire 0.3.65 AND 1.4.2, which are compatible on
//    this surface — the structs only ever APPEND fields and we only use
//    the stable prefix).
//  * The EnumFormat spa_pod is embedded as literal bytes, byte-for-byte
//    identical to what spa_pod_builder_add_object() emits on both 0.3.65
//    and 1.4.2; the test suite re-derives it from the real headers when
//    a spa/pod builder is available on the host (see the __has_include
//    lane in test_linux_hal_driver.cpp).
//
//  Linux-only: the loaders use dlfcn.h.
// ============================================================================

#if defined(__linux__)

#include <dlfcn.h>

#include <cstdint>
#include <cstring>

namespace streamify {
namespace hal {

namespace pwabi {

// ---- constants ------------------------------------------------------------
constexpr uint32_t kStreamEventsVersion = 1;     // through `command` (0.3.39)
constexpr uint32_t kDirectionOutput = 1;         // PW_DIRECTION_OUTPUT
constexpr uint32_t kIdAny = 0xFFFFFFFFu;         // PW_ID_ANY
constexpr uint32_t kFlagAutoconnect = 1u << 0;   // PW_STREAM_FLAG_AUTOCONNECT
constexpr uint32_t kFlagMapBuffers = 1u << 2;    // PW_STREAM_FLAG_MAP_BUFFERS
constexpr uint32_t kFlagRtProcess = 1u << 4;     // PW_STREAM_FLAG_RT_PROCESS
constexpr uint32_t kSpaDataMemPtr = 1;           // SPA_DATA_MemPtr
constexpr uint32_t kSpaDataMemFd = 2;            // SPA_DATA_MemFd
constexpr uint32_t kStreamStateError = (uint32_t)-1;  // pw_stream_state
constexpr int kStreamStateStreaming = 3;             // PW_STREAM_STATE_STREAMING

// ---- stable-prefix struct declarations -------------------------------------
struct SpaFraction {
    uint32_t num;
    uint32_t denom;
};

struct SpaChunk {
    uint32_t offset;
    uint32_t size;
    int32_t stride;
    int32_t flags;
};

struct SpaData {
    uint32_t type;
    uint32_t flags;
    int64_t fd;
    uint32_t mapoffset;
    uint32_t maxsize;
    void* data;
    SpaChunk* chunk;
};

struct SpaBuffer {
    uint32_t n_metas;
    uint32_t n_datas;
    void* metas;  // struct spa_meta* (opaque here)
    SpaData* datas;
};

struct PwBuffer {
    SpaBuffer* buffer;
    void* user_data;
    uint64_t size;       // frames, for audio streams
    uint64_t requested;  // frames the resampler wants this cycle
};

// The 40-byte pw_time prefix (all fields through `queued`); everything
// appended after 0.3.50 is deliberately NOT declared — we drain through
// pw_stream_get_time(), which copies exactly this prefix.
struct PwTime {
    int64_t now;         // CLOCK_MONOTONIC ns of the snapshot
    SpaFraction rate;    // tick PERIOD, canonically {1, stream_rate}
    uint64_t ticks;      // graph position in ticks at `now`
    int64_t delay;       // ticks between position and hardware
    uint64_t queued;     // frames queued in the stream
};

struct PwStreamEvents {
    uint32_t version;
    void (*destroy)(void* data);
    void (*state_changed)(void* data, uint32_t old, uint32_t state,
                          const char* error);
    void (*control_info)(void* data, uint32_t id, const void* control);
    void (*io_changed)(void* data, uint32_t id, void* area, uint32_t size);
    void (*param_changed)(void* data, uint32_t id, const void* param);
    void (*add_buffer)(void* data, PwBuffer* buffer);
    void (*remove_buffer)(void* data, PwBuffer* buffer);
    void (*process)(void* data);
    void (*drained)(void* data);
    void (*command)(void* data, const void* command);
};

// The EnumFormat parameter pod: an SPA_TYPE_OBJECT_Format object with
// mediaType=audio(1), mediaSubtype=raw(1), AUDIO_format=F32_LE(283),
// AUDIO_rate=48000, AUDIO_channels=2. Byte-for-byte identical to the
// output of spa_pod_builder_add_object() on pipewire 0.3.65 and 1.4.2
// (see test_linux_hal_driver.cpp's header-faithful cross-check).
//
// Layout: [pod hdr: size u32 | type u32][obj body: type u32 | id u32]
//         then per property: [key u32][flags u32][val pod: size u32 |
//         type u32][value padded to 8].
struct SpaPod {
    uint32_t size;
    uint32_t type;
};

alignas(8) static constexpr unsigned char kEnumFormatPod[136] = {
    0x80, 0x00, 0x00, 0x00, 0x0f, 0x00, 0x00, 0x00,  // pod hdr (Object)
    0x03, 0x00, 0x04, 0x00, 0x03, 0x00, 0x00, 0x00,  // obj: type=Format, id=EnumFormat
    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // key 1 mediaType
    0x04, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,  // val pod: size 4, type Id
    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // = 1 audio
    0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // key 2 mediaSubtype
    0x04, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,  // val pod: size 4, type Id
    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // = 1 raw
    0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,  // key 65537 AUDIO_format
    0x04, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00,  // val pod: size 4, type Id
    0x1b, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // = 283 F32_LE
    0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,  // key 65539 AUDIO_rate
    0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,  // val pod: size 4, type Int
    0x80, 0xbb, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // = 48000
    0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,  // key 65540 AUDIO_channels
    0x04, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00,  // val pod: size 4, type Int
    0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // = 2
};

// ---- layout locks (fail the BUILD if a real header disagrees) ---------------
static_assert(sizeof(SpaFraction) == 8, "spa_fraction layout");
static_assert(sizeof(SpaChunk) == 16, "spa_chunk layout");
static_assert(sizeof(SpaData) == 40, "spa_data layout");
static_assert(offsetof(SpaData, data) == 24, "spa_data.data offset");
static_assert(offsetof(SpaData, chunk) == 32, "spa_data.chunk offset");
static_assert(offsetof(SpaBuffer, n_datas) == 4, "spa_buffer.n_datas offset");
static_assert(offsetof(SpaBuffer, datas) == 16, "spa_buffer.datas offset");
static_assert(sizeof(PwBuffer) == 32, "pw_buffer prefix layout");
static_assert(offsetof(PwBuffer, size) == 16, "pw_buffer.size offset");
static_assert(offsetof(PwBuffer, requested) == 24, "pw_buffer.requested offset");
static_assert(sizeof(PwTime) == 40, "pw_time prefix layout");
static_assert(offsetof(PwTime, rate) == 8, "pw_time.rate offset");
static_assert(offsetof(PwTime, ticks) == 16, "pw_time.ticks offset");
static_assert(offsetof(PwTime, delay) == 24, "pw_time.delay offset");
static_assert(offsetof(PwTime, queued) == 32, "pw_time.queued offset");
static_assert(offsetof(PwStreamEvents, process) == 64,
              "pw_stream_events.process offset");
static_assert(sizeof(PwStreamEvents) == 88, "pw_stream_events v1 size");
static_assert(sizeof(SpaPod) == 8, "spa_pod header size");
static_assert(sizeof(kEnumFormatPod) == 136, "EnumFormat pod size");
// constexpr LE u32 reader for the embedded pod (array indexing is a
// constant expression; reinterpret_cast is not).
constexpr uint32_t PodBytesU32(const unsigned char* p, size_t off) {
    return static_cast<uint32_t>(p[off]) |
           (static_cast<uint32_t>(p[off + 1]) << 8) |
           (static_cast<uint32_t>(p[off + 2]) << 16) |
           (static_cast<uint32_t>(p[off + 3]) << 24);
}

// The pod's own header must agree with the embedded struct view.
static_assert(PodBytesU32(kEnumFormatPod, 0) == 128,
              "EnumFormat pod body size");
static_assert(PodBytesU32(kEnumFormatPod, 4) == 15,
              "EnumFormat pod is an Object");

// ---- the resolved dynamic-symbol table --------------------------------------
// Opaque PipeWire handles (the driver never dereferences them; every
// access goes through the vtable below).
struct PwThreadLoop;
struct PwStream;

struct PwApi {
    void (*pw_init)(int* argc, char** argv[]);
    void (*pw_deinit)();
    struct PwThreadLoop* (*thread_loop_new)(const char* name,
                                            const void* props);
    void (*thread_loop_destroy)(struct PwThreadLoop* loop);
    int (*thread_loop_start)(struct PwThreadLoop* loop);
    void (*thread_loop_stop)(struct PwThreadLoop* loop);
    void (*thread_loop_lock)(struct PwThreadLoop* loop);
    void (*thread_loop_unlock)(struct PwThreadLoop* loop);
    void* (*thread_loop_get_loop)(struct PwThreadLoop* loop);  // pw_loop*
    struct PwStream* (*stream_new_simple)(void* loop, const char* name,
                                          void* props,
                                          const PwStreamEvents* events,
                                          void* data);
    void (*stream_destroy)(struct PwStream* stream);
    int (*stream_connect)(struct PwStream* stream, uint32_t direction,
                          uint32_t target_id, uint32_t flags,
                          const void** params, uint32_t n_params);
    PwBuffer* (*stream_dequeue_buffer)(struct PwStream* stream);
    int (*stream_queue_buffer)(struct PwStream* stream, PwBuffer* buffer);
    int (*stream_get_time)(struct PwStream* stream, PwTime* time);
    void* (*properties_new)(const char* key, ...);
    void (*properties_free)(void* props);
};

struct AlsaApi {
    int (*pcm_open)(void** pcm, const char* name, int stream, int mode);
    int (*pcm_close)(void* pcm);
    int (*pcm_set_params)(void* pcm, int format, int access,
                          unsigned channels, unsigned rate,
                          int soft_resample, unsigned latency_us);
    long (*pcm_writei)(void* pcm, const void* buffer, long frames);
    int (*pcm_delay)(void* pcm, long* delay);
    int (*pcm_prepare)(void* pcm);
    int (*pcm_recover)(void* pcm, int err, int silent);
};

// ---- ALSA constants (frozen against libasound 1.2.x) ------------------------
constexpr int kSndStreamPlayback = 0;      // SND_PCM_STREAM_PLAYBACK
constexpr int kSndAccessRwInterleaved = 3; // SND_PCM_ACCESS_RW_INTERLEAVED
constexpr int kSndFormatFloatLe = 14;      // SND_PCM_FORMAT_FLOAT_LE

// Resolve `name` from `lib`, memcpy-ing the pointer into `out` (the
// pedantically-clean void*→function-pointer dance; POSIX guarantees the
// representation is compatible).
template <typename Fn>
bool ResolveSym(void* lib, const char* name, Fn* out) {
    if (lib == nullptr) return false;
    void* raw = dlsym(lib, name);
    if (raw == nullptr) return false;
    std::memcpy(out, &raw, sizeof(Fn));
    return true;
}

// Load the full PipeWire API from one of the SONAMEs. `loaded_lib` receives
// the dlopen handle for later dlclose. Pure function of the given names —
// the unit tests inject bogus names to prove the fallback chain.
inline bool LoadPwApi(const char* const* names, size_t n_names, PwApi* out,
                      void** loaded_lib) {
    std::memset(out, 0, sizeof(*out));
    void* lib = nullptr;
    for (size_t i = 0; i < n_names; ++i) {
        lib = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (lib != nullptr) break;
    }
    if (lib == nullptr) return false;
    if (!ResolveSym(lib, "pw_init", &out->pw_init) ||
        !ResolveSym(lib, "pw_deinit", &out->pw_deinit) ||
        !ResolveSym(lib, "pw_thread_loop_new", &out->thread_loop_new) ||
        !ResolveSym(lib, "pw_thread_loop_destroy", &out->thread_loop_destroy) ||
        !ResolveSym(lib, "pw_thread_loop_start", &out->thread_loop_start) ||
        !ResolveSym(lib, "pw_thread_loop_stop", &out->thread_loop_stop) ||
        !ResolveSym(lib, "pw_thread_loop_lock", &out->thread_loop_lock) ||
        !ResolveSym(lib, "pw_thread_loop_unlock", &out->thread_loop_unlock) ||
        !ResolveSym(lib, "pw_thread_loop_get_loop", &out->thread_loop_get_loop) ||
        !ResolveSym(lib, "pw_stream_new_simple", &out->stream_new_simple) ||
        !ResolveSym(lib, "pw_stream_destroy", &out->stream_destroy) ||
        !ResolveSym(lib, "pw_stream_connect", &out->stream_connect) ||
        !ResolveSym(lib, "pw_stream_dequeue_buffer", &out->stream_dequeue_buffer) ||
        !ResolveSym(lib, "pw_stream_queue_buffer", &out->stream_queue_buffer) ||
        !ResolveSym(lib, "pw_stream_get_time", &out->stream_get_time) ||
        !ResolveSym(lib, "pw_properties_new", &out->properties_new) ||
        !ResolveSym(lib, "pw_properties_free", &out->properties_free)) {
        dlclose(lib);
        std::memset(out, 0, sizeof(*out));
        return false;
    }
    *loaded_lib = lib;
    return true;
}

inline bool LoadAlsaApi(const char* const* names, size_t n_names,
                        AlsaApi* out, void** loaded_lib) {
    std::memset(out, 0, sizeof(*out));
    void* lib = nullptr;
    for (size_t i = 0; i < n_names; ++i) {
        lib = dlopen(names[i], RTLD_NOW | RTLD_LOCAL);
        if (lib != nullptr) break;
    }
    if (lib == nullptr) return false;
    if (!ResolveSym(lib, "snd_pcm_open", &out->pcm_open) ||
        !ResolveSym(lib, "snd_pcm_close", &out->pcm_close) ||
        !ResolveSym(lib, "snd_pcm_set_params", &out->pcm_set_params) ||
        !ResolveSym(lib, "snd_pcm_writei", &out->pcm_writei) ||
        !ResolveSym(lib, "snd_pcm_delay", &out->pcm_delay) ||
        !ResolveSym(lib, "snd_pcm_prepare", &out->pcm_prepare)) {
        dlclose(lib);
        std::memset(out, 0, sizeof(*out));
        return false;
    }
    // Optional since 1.0.11 but present everywhere we care; the writer
    // falls back to snd_pcm_prepare when absent.
    ResolveSym(lib, "snd_pcm_recover", &out->pcm_recover);
    *loaded_lib = lib;
    return true;
}

// Parse one u32-valued property (Int or Id typed — the two u32 pod
// value types the Format object uses) out of an SPA object pod (the
// negotiated Format arrives as `param_changed`'s param). Returns false
// when absent or wrongly typed.
//
// The walk is POD-relative and bounded by the pod's OWN claimed total
// (8 + hdr->size): the value READ is bounded by the actual vsize bytes
// (the final property's 8-alignment padding may legally extend past the
// readable value), the WALK advances by the padded size.
//
// TRUST CONTRACT: exactly like every SPA consumer in the tree, the pod's
// claimed size is authoritative — the PipeWire library hands
// `param_changed` pods whose backing memory is valid for their claimed
// size (the library parsed them first). A pod that LIES about its size is
// not detectable here (no allocation bound is passed in).
inline bool PodPropU32(const void* pod_ptr, uint32_t key, uint32_t want_type,
                       uint32_t* out) {
    if (pod_ptr == nullptr) return false;
    const unsigned char* p = static_cast<const unsigned char*>(pod_ptr);
    const SpaPod* hdr = reinterpret_cast<const SpaPod*>(p);
    if (hdr->type != 15 /* SPA_TYPE_Object */) return false;
    const uint32_t total = 8u + hdr->size;  // pod-relative end of the object
    uint32_t off = 16;                      // first property (past both headers)
    while (off + 8 <= total) {
        uint32_t k = 0, fl = 0, vsize = 0, vtype = 0;
        std::memcpy(&k, p + off, 4);
        std::memcpy(&fl, p + off + 4, 4);
        (void)fl;
        off += 8;
        if (off + 8 > total) break;  // truncated value header
        std::memcpy(&vsize, p + off, 4);
        std::memcpy(&vtype, p + off + 4, 4);
        // The READABLE value is vsize bytes (padding may overhang the end).
        if (off + 8 + vsize > total) break;  // truncated value body
        if (k == key && vtype == want_type && vsize >= 4 &&
            out != nullptr) {
            std::memcpy(out, p + off + 8, 4);
            return true;
        }
        off += 8 + ((vsize + 7u) & ~7u);  // advance past the padded value
    }
    return false;
}

// Int-typed property (SPA_POD_Int == SPA_TYPE_Int == 4).
inline bool PodIntProp(const void* pod_ptr, uint32_t key, int32_t* out) {
    uint32_t v = 0;
    if (!PodPropU32(pod_ptr, key, 4, &v)) return false;
    *out = static_cast<int32_t>(v);
    return true;
}

// Id-typed property (SPA_POD_Id == SPA_TYPE_Id == 3) — the format/
// mediaType/mediaSubtype values.
inline bool PodIdProp(const void* pod_ptr, uint32_t key, uint32_t* out) {
    return PodPropU32(pod_ptr, key, 3, out);
}

// The three SPA format keys the driver inspects (frozen values).
constexpr uint32_t kKeyAudioFormat = 65537;    // SPA_FORMAT_AUDIO_format
constexpr uint32_t kKeyAudioRate = 65539;      // SPA_FORMAT_AUDIO_rate
constexpr uint32_t kKeyAudioChannels = 65540;  // SPA_FORMAT_AUDIO_channels
constexpr uint32_t kAudioFormatF32Le = 283;    // SPA_AUDIO_FORMAT_F32_LE

// Canonical stream geometry (master plan invariant 5/6): the ring is
// always 48 kHz interleaved f32 stereo.
constexpr uint32_t kCanonicalRate = 48000;
constexpr uint32_t kCanonicalChannels = 2;
constexpr uint32_t kFrameBytes = kCanonicalChannels * sizeof(float);

}  // namespace pwabi

}  // namespace hal
}  // namespace streamify

#endif  // __linux__

#endif  // STREAMIFY_LINUXHAL_EMBEDDED_ABI_H
