// ============================================================================
//  pipewire_driver.cpp — Phase 4b Linux PipeWire native process-callback
//  driver with ALSA fallback (native/audio/pipewire_driver.cpp — MP-HAL09)
// ============================================================================
//
//  A pw_stream playback driver whose process callback pulls interleaved
//  IEEE-754 f32 stereo frames DIRECTLY from the hardened `AudioSink` SPSC
//  ring — zero FFI crossings, zero quantization, zero dynamic allocation,
//  zero locks and zero system calls on the process-callback path (master
//  plan invariants 5).
//
//  BACKEND RESOLUTION (dlopen, zero dev-package build dependencies)
//  ------------------------------------------------------------------
//  The driver binds libpipewire at RUNTIME:
//
//      dlopen("libpipewire-0.3.so.0")  — the stable SONAME shared by every
//                                         pipewire 0.3.x AND 1.x release
//        └ fail → dlopen("libpipewire-0.3.so") — dev-symlink / exotic
//              └ fail → ALSA fallback: dlopen("libasound.so.2")
//                              snd_pcm_writei RW_INTERLEAVED F32_LE 48k
//                    └ fail → driver reports failure (no backend)
//
//  so the file compiles on ANY Linux toolchain without libpipewire-dev or
//  libasound-dev installed, and the PipeWire→ALSA fallback triggers
//  exactly per the directive: when the PipeWire daemon/library is not
//  active or unavailable on the host.
//
//  FROZEN EMBEDDED ABI (no headers, no breakage)
//  -----------------------------------------------
//  Instead of pipewire/spa headers, the TU embeds the minimal ABI surface
//  the driver touches — declared once in `namespace pwabi` below and
//  locked in with static_asserts against the layouts extracted from the
//  REAL headers (checked against pipewire 0.3.65 AND 1.4.2, which are
//  layout-compatible on this surface: the structs only ever APPEND
//  fields, and we only use the stable prefix):
//
//    * pw_stream_events v1 (through `command`, offset 88) — `process`
//      sits at offset 64. Events versioning is backwards/forwards
//      compatible: the library reads `version` and never touches fields
//      the caller did not declare.
//    * pw_buffer / spa_buffer / spa_data / spa_chunk — the buffer
//      exchange structs (spa_data carries `data`/`maxsize`/`chunk`).
//    * pw_time PREFIX (now/rate/ticks/delay/queued, 40 bytes) — drained
//      through pw_stream_get_time(), the compat entry point present in
//      every 0.3.x/1.x release (it internally passes sizeof(old_time) ==
//      the 40-byte prefix, so extended fields in newer libs are simply
//      not copied).
//    * The EnumFormat spa_pod for audio/raw F32_LE stereo @ 48 kHz is
//      embedded as literal BYTES (136), byte-for-byte identical to what
//      spa_pod_builder_add_object() emits on 0.3.65 and 1.4.2 — verified
//      against the real headers by test_linux_hal_driver whenever a
//      spa/pod builder is available on the host.
//
//  LIFECYCLE (canonical pw_stream_new_simple pattern)
//  ---------------------------------------------------
//      Start()  [control thread]
//        resolve the sink (shared_ptr keeps it alive) → load backend →
//        PipeWire path: pw_init → pw_thread_loop_new → lock →
//        pw_stream_new_simple(loop, events{process}, props) →
//        pw_stream_connect(OUTPUT, PW_ID_ANY, AUTOCONNECT|MAP_BUFFERS|
//        RT_PROCESS, [EnumFormat pod]) → pw_thread_loop_start → unlock →
//        spin the clock sampler thread.
//        ALSA path: snd_pcm_open("default", PLAYBACK) → snd_pcm_set_params
//        (FLOAT_LE, RW_INTERLEAVED, 2ch, 48k, soft-resample, 20 ms) →
//        spin the writer thread.
//
//      process() [graph RT thread]
//        pw_stream_dequeue_buffer → core_.FillProcessBuffer(frames,
//        spa_data.data) → chunk/size bookkeeping →
//        pw_stream_queue_buffer. Nothing else. (dequeue/queue are the
//        documented RT-safe buffer-edge calls.)
//
//      ClockSampler [control thread, 10 Hz]
//        pw_stream_get_time(&time) — a seqlock-guarded lock-free snapshot
//        (SPA_SEQ_READ in stream.c; we deliberately do NOT take the thread
//        loop lock: that would priority-invert against the RT loop) →
//        core_.PublishClockFromPwTime(ticks, rate, now).
//
//      AlsaWriter [control-side thread]
//        core_.PullWindow(256, window) → snd_pcm_writei (EINTR/EPIPE
//        safe, snd_pcm_recover) → snd_pcm_delay + CLOCK_MONOTONIC pair →
//        core_.PublishClock(presented, now).
//
//      Stop()   [control thread]
//        core_.Unbind() FIRST (an in-flight callback degrades to digital
//        silence, never a use-after-free) → join sampler/writer →
//        pw_thread_loop_stop → pw_stream_destroy →
//        pw_thread_loop_destroy → pw_deinit (or snd_pcm_close) → only
//        then drop the sink shared_ptr.
//
//  This TU compiles ONLY on Linux (CMake guards it with `if(CMAKE_SYSTEM_
//  NAME STREQUAL "Linux")`; the contents are additionally fenced with
//  __linux__).
// ============================================================================

#if defined(__linux__)

#include <dlfcn.h>
#include <time.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
#include <vector>

#include "../include/alsa_render_core.h"
#include "../include/linuxhal_embedded_abi.h"
#include "../include/pipewire_render_core.h"
#include "../include/streamify_audio_sinks.h"

namespace streamify {
namespace hal {

// ===========================================================================
// The Linux desktop audio driver: PipeWire primary, ALSA fallback.
// ===========================================================================

class LinuxAudioDriver {
public:
    enum class Backend { kPipeWire, kAlsa, kFailed };

    // Resolve the sink and start whichever backend is reachable. Returns
    // false (leaving the driver stopped) when neither backend loads.
    //
    // `alsa_device` overrides the ALSA fallback device ("default" in
    // production; the host test suite passes "null" — ALSA's built-in
    // deviceless PCM — to exercise the full fallback path headlessly).
    //
    // Ownership contract: StartPipeWire_/StartAlsa_ TAKE the dlopen handle
    // and guarantee that a false return leaves everything cleaned up
    // (library dlclosed, vtable zeroed, pw_init refcount balanced) — the
    // caller never cleans up a failed backend itself.
    bool Start(int64_t sink_handle, const char* alsa_device = "default") {
        if (running_) Stop();
        sink_ = sink::SinkRegistry::instance().resolve(sink_handle);
        if (sink_ == nullptr) return false;
        alsa_device_ = (alsa_device != nullptr) ? alsa_device : "default";

        static const char* kPwNames[] = {"libpipewire-0.3.so.0",
                                         "libpipewire-0.3.so"};
        static const char* kAlsaNames[] = {"libasound.so.2", "libasound.so"};

        void* pw_lib = nullptr;
        if (pwabi::LoadPwApi(kPwNames, 2, &pw_api_, &pw_lib) &&
            pw_lib != nullptr && StartPipeWire_(pw_lib)) {
            backend_ = Backend::kPipeWire;
            running_.store(true, std::memory_order_relaxed);
            return true;
        }

        void* alsa_lib = nullptr;
        if (pwabi::LoadAlsaApi(kAlsaNames, 2, &alsa_api_, &alsa_lib) &&
            alsa_lib != nullptr && StartAlsa_(alsa_lib)) {
            backend_ = Backend::kAlsa;
            running_.store(true, std::memory_order_relaxed);
            return true;
        }

        sink_.reset();
        backend_ = Backend::kFailed;
        return false;
    }

    // Ordered teardown (see the file header): retract the sink pointer,
    // join our threads, then tear the backend down, then drop the sink.
    void Stop() {
        if (!running_.load(std::memory_order_relaxed) &&
            backend_ == Backend::kFailed) {
            return;
        }
        running_.store(false, std::memory_order_relaxed);
        pw_core_.Unbind();
        alsa_core_.Unbind();

        if (clock_thread_.joinable()) clock_thread_.join();
        if (writer_thread_.joinable()) writer_thread_.join();

        if (backend_ == Backend::kPipeWire) {
            TeardownPipeWire_();
        } else if (backend_ == Backend::kAlsa) {
            TeardownAlsa_();
        }
        backend_ = Backend::kFailed;
        sink_.reset();  // last owner release — after Unbind, always safe
    }

    ~LinuxAudioDriver() { Stop(); }

    LinuxAudioDriver() = default;
    LinuxAudioDriver(const LinuxAudioDriver&) = delete;
    LinuxAudioDriver& operator=(const LinuxAudioDriver&) = delete;

    // ---- diagnostics ------------------------------------------------------------
    Backend backend() const { return backend_; }
    const PipeWireRenderCore& pipewire_core() const { return pw_core_; }
    const AlsaRenderCore& alsa_core() const { return alsa_core_; }
    // Last pw_stream state id observed (0 unconnected .. 3 streaming,
    // -1 error) and a bounded copy of the error string, if any.
    int last_stream_state() const {
        return last_state_.load(std::memory_order_relaxed);
    }
    // Negotiated (Format pod) rate/channels diagnostics; 0 until the
    // server confirms the format.
    int32_t negotiated_rate() const {
        return negotiated_rate_.load(std::memory_order_relaxed);
    }
    int32_t negotiated_channels() const {
        return negotiated_channels_.load(std::memory_order_relaxed);
    }
    bool negotiated_f32() const {
        return negotiated_f32_.load(std::memory_order_relaxed);
    }

    // Latest DAC presentation clock sample, unified across backends:
    // (frames, rate, host_ns) or false when nothing is published yet.
    bool LatestClock(uint64_t* out_frames, uint32_t* out_rate,
                     uint64_t* out_host_ns) const {
        if (backend_ == Backend::kPipeWire) {
            PipeWireClockSample s;
            if (!pw_core_.LatestClock(&s)) return false;
            *out_frames = s.frames;
            *out_rate = s.rate;
            *out_host_ns = s.host_ns;
            return true;
        }
        if (backend_ == Backend::kAlsa) {
            AlsaClockSample s;
            if (!alsa_core_.LatestClock(&s)) return false;
            *out_frames = s.frames;
            *out_rate = s.rate;
            *out_host_ns = s.host_ns;
            return true;
        }
        return false;
    }

private:
    // ---- PipeWire path ------------------------------------------------------
    bool StartPipeWire_(void* lib) {
        pw_lib_ = lib;  // ownership taken; every failure path cleans up
        {
            // pw_init is process-global (refcounted here: several drivers
            // may live in one process; pw_deinit runs when the last stops).
            std::lock_guard<std::mutex> guard(PwInitMutex());
            if (PwInitCount()++ == 0) {
                pw_api_.pw_init(nullptr, nullptr);
            }
        }

        loop_ = pw_api_.thread_loop_new("streamify-audio", nullptr);
        if (loop_ == nullptr) {
            TeardownPipeWire_();
            return false;
        }
        pw_api_.thread_loop_lock(loop_);

        // Events: the struct must outlive the stream (spa callbacks keep
        // the pointer) — it is a member of this heap driver.
        std::memset(&pw_events_, 0, sizeof(pw_events_));
        pw_events_.version = pwabi::kStreamEventsVersion;
        pw_events_.state_changed = &LinuxAudioDriver::OnStateChanged_;
        pw_events_.param_changed = &LinuxAudioDriver::OnParamChanged_;
        pw_events_.process = &LinuxAudioDriver::OnProcess_;
        pw_events_.drained = &LinuxAudioDriver::OnDrained_;

        // pw_stream_new_simple takes ownership of the properties (and
        // frees them on BOTH the success and failure paths).
        void* props = pw_api_.properties_new(
            "media.type", "Audio",
            "media.category", "Playback",
            "media.role", "Music",
            "node.name", "streamify",
            static_cast<const char*>(nullptr));
        stream_ = pw_api_.stream_new_simple(
            pw_api_.thread_loop_get_loop(loop_), "streamify", props,
            &pw_events_, this);

        bool ok = stream_ != nullptr;
        if (ok) {
            const void* params[1] = {pwabi::kEnumFormatPod};
            const int rc = pw_api_.stream_connect(
                stream_, pwabi::kDirectionOutput, pwabi::kIdAny,
                pwabi::kFlagAutoconnect | pwabi::kFlagMapBuffers |
                    pwabi::kFlagRtProcess,
                params, 1);
            ok = rc == 0;
        }
        if (ok && pw_api_.thread_loop_start(loop_) == 0) {
            loop_started_ = true;
        } else {
            ok = false;
        }
        if (ok) {
            // MP-HAL09 fallback rule: a PipeWire LIBRARY that loads is not
            // a PipeWire DAEMON that answers. Bound the wait for the stream
            // to reach STREAMING; a daemonless host (connection error, no
            // socket) must fall through to ALSA.
            for (int spin = 0; spin < 200; ++spin) {  // ~2 s bound
                const int st = last_state_.load(std::memory_order_relaxed);
                if (st == pwabi::kStreamStateStreaming) break;
                if (st == static_cast<int>(pwabi::kStreamStateError)) {
                    ok = false;
                    break;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
        pw_api_.thread_loop_unlock(loop_);

        if (!ok) {
            TeardownPipeWire_();
            return false;
        }

        // Arm AFTER the backend is up: the process callback (which the
        // loop may already be dispatching) now pulls real audio.
        pw_core_.Bind(sink_.get(), pwabi::kCanonicalRate);
        clock_thread_ =
            std::thread(&LinuxAudioDriver::ClockSamplerLoop_, this);
        return true;
    }

    void TeardownPipeWire_() {
        if (loop_ != nullptr && loop_started_) {
            pw_api_.thread_loop_stop(loop_);
            loop_started_ = false;
        }
        if (stream_ != nullptr) {
            pw_api_.stream_destroy(stream_);
            stream_ = nullptr;
        }
        if (loop_ != nullptr) {
            pw_api_.thread_loop_destroy(loop_);
            loop_ = nullptr;
        }
        // Process-global deinit BEFORE our library handle goes away —
        // always through a vtable that is still loaded (ours).
        {
            std::lock_guard<std::mutex> guard(PwInitMutex());
            if (PwInitCount() > 0 && --PwInitCount() == 0) {
                pw_api_.pw_deinit();
            }
        }
        if (pw_lib_ != nullptr) {
            dlclose(pw_lib_);
            pw_lib_ = nullptr;
        }
        std::memset(&pw_api_, 0, sizeof(pw_api_));
    }

    // ---- graph RT thread: the entire callback is FillProcessBuffer ----
    static void OnProcess_(void* data) {
        auto* self = static_cast<LinuxAudioDriver*>(data);
        pwabi::PwBuffer* b =
            self->pw_api_.stream_dequeue_buffer(self->stream_);
        if (b == nullptr) return;  // no free buffer this cycle: skip
        pwabi::SpaBuffer* sb = b->buffer;
        if (sb == nullptr || sb->n_datas < 1 || sb->datas == nullptr) {
            self->pw_api_.stream_queue_buffer(self->stream_, b);
            return;
        }
        pwabi::SpaData& d = sb->datas[0];
        if (d.data == nullptr || d.chunk == nullptr) {
            // Not a mappable MemPtr/MemFd window: hand the buffer back.
            self->pw_api_.stream_queue_buffer(self->stream_, b);
            return;
        }
        // Window the graph offered this cycle (maxsize bytes) and the
        // resampler's request hint: fill whichever is smaller, in FRAMES.
        uint32_t frames = d.maxsize / pwabi::kFrameBytes;
        if (b->requested != 0 && b->requested < frames) {
            frames = static_cast<uint32_t>(b->requested);
        }
        if (frames > 0) {
            // The pull cycle: everything inside is allocation/lock/syscall
            // free by construction (audited on the host lanes through the
            // shared PipeWireRenderCore).
            self->pw_core_.FillProcessBuffer(
                frames, static_cast<float*>(d.data));
        }
        d.chunk->offset = 0;
        d.chunk->stride = static_cast<int32_t>(pwabi::kFrameBytes);
        d.chunk->size = frames * pwabi::kFrameBytes;
        b->size = frames;
        self->pw_api_.stream_queue_buffer(self->stream_, b);
    }

    // ---- loop thread (not RT): stream lifecycle diagnostics ----
    static void OnStateChanged_(void* data, uint32_t /*old*/, uint32_t state,
                                const char* error) {
        auto* self = static_cast<LinuxAudioDriver*>(data);
        self->last_state_.store(static_cast<int>(state),
                                std::memory_order_relaxed);
        if (error != nullptr) {
            std::strncpy(self->last_error_, error,
                         sizeof(self->last_error_) - 1);
            self->last_error_[sizeof(self->last_error_) - 1] = '\0';
        }
    }

    // ---- loop thread (not RT): the server's negotiated Format ----
    static void OnParamChanged_(void* data, uint32_t id, const void* param) {
        auto* self = static_cast<LinuxAudioDriver*>(data);
        (void)id;
        if (param == nullptr) return;  // format cleared
        int32_t rate = 0, channels = 0, fmt = 0;
        if (pwabi::PodIntProp(param, pwabi::kKeyAudioRate, &rate)) {
            self->negotiated_rate_.store(rate, std::memory_order_relaxed);
        }
        if (pwabi::PodIntProp(param, pwabi::kKeyAudioChannels, &channels)) {
            self->negotiated_channels_.store(channels,
                                             std::memory_order_relaxed);
        }
        if (pwabi::PodIdProp(param, pwabi::kKeyAudioFormat,
                             reinterpret_cast<uint32_t*>(&fmt))) {
            self->negotiated_f32_.store(
                fmt == pwabi::kAudioFormatF32Le,
                std::memory_order_relaxed);
        }
    }

    static void OnDrained_(void* /*data*/) {}

    // ---- control-side clock sampler (10 Hz) ----
    void ClockSamplerLoop_() {
        while (running_.load(std::memory_order_relaxed)) {
            pwabi::PwTime t;
            std::memset(&t, 0, sizeof(t));
            // Lock-free seqlock snapshot (see the file header for why the
            // thread-loop lock is deliberately NOT taken here).
            if (stream_ != nullptr &&
                pw_api_.stream_get_time(stream_, &t) == 0) {
                const uint64_t now =
                    t.now > 0 ? static_cast<uint64_t>(t.now) : 0ull;
                pw_core_.PublishClockFromPwTime(t.ticks, t.rate.num,
                                                t.rate.denom, now);
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }

    // ---- ALSA fallback path ---------------------------------------------------
    bool StartAlsa_(void* lib) {
        alsa_lib_ = lib;  // ownership taken; every failure path cleans up
        if (alsa_api_.pcm_open(&pcm_, alsa_device_, pwabi::kSndStreamPlayback,
                               0 /* blocking */) != 0 ||
            pcm_ == nullptr) {
            TeardownAlsa_();
            return false;
        }
        // F32_LE interleaved stereo @ 48 kHz, allow soft resampling, ~20 ms
        // latency target. The device converts at its boundary when it does
        // not run 48k natively; the ring stays canonical (invariant 6).
        if (alsa_api_.pcm_set_params(pcm_, pwabi::kSndFormatFloatLe,
                                     pwabi::kSndAccessRwInterleaved,
                                     pwabi::kCanonicalChannels,
                                     pwabi::kCanonicalRate,
                                     1 /* soft_resample */,
                                     20000 /* latency_us */) != 0) {
            TeardownAlsa_();
            return false;
        }
        alsa_window_.assign(kAlsaWindowFrames * pwabi::kCanonicalChannels,
                            0.0f);
        total_submitted_ = 0;
        alsa_core_.Bind(sink_.get(), pwabi::kCanonicalRate);
        writer_thread_ =
            std::thread(&LinuxAudioDriver::AlsaWriterLoop_, this);
        return true;
    }

    void TeardownAlsa_() {
        if (pcm_ != nullptr) {
            alsa_api_.pcm_close(pcm_);
            pcm_ = nullptr;
        }
        if (alsa_lib_ != nullptr) {
            dlclose(alsa_lib_);
            alsa_lib_ = nullptr;
        }
        std::memset(&alsa_api_, 0, sizeof(alsa_api_));
    }

    // ---- control-side ALSA writer thread (the push edge) ----
    void AlsaWriterLoop_() {
        while (running_.load(std::memory_order_relaxed)) {
            // The pull discipline: the writer thread IS the simulated
            // callback (starved tails render as digital silence).
            const uint32_t got =
                alsa_core_.PullWindow(kAlsaWindowFrames, alsa_window_.data());
            if (got == 0) {
                // Pacing guard: real hardware paces us through writei's
                // blocking, but a deviceless PCM (the "null" double, a
                // hot-unplugged card) never blocks — a fully starved
                // window then sleeps 1 ms instead of busy-spinning. The
                // DAC is already holding silence for that window.
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
            }

            long written = 0;
            const long want = static_cast<long>(kAlsaWindowFrames);
            while (written < want &&
                   running_.load(std::memory_order_relaxed)) {
                const long r = alsa_api_.pcm_writei(
                    pcm_,
                    alsa_window_.data() +
                        static_cast<size_t>(written) *
                            pwabi::kCanonicalChannels,
                    want - written);
                if (r == -EINTR) continue;  // signal: retry
                if (r < 0) {
                    // xrun (EPIPE) / suspend (ESTRPIPE): recover and retry.
                    const int err = static_cast<int>(-r);
                    if (err == EPIPE || err == ESTRPIPE) {
                        if (alsa_api_.pcm_recover != nullptr) {
                            if (alsa_api_.pcm_recover(pcm_, err, 1) != 0) {
                                break;  // unrecoverable: drop the window
                            }
                        } else if (alsa_api_.pcm_prepare(pcm_) != 0) {
                            break;
                        }
                        continue;
                    }
                    break;  // hard error: drop the window
                }
                written += r;
                total_submitted_ += static_cast<uint64_t>(r);
            }

            // Clock sample: delay + monotonic, sampled back-to-back.
            long delay = 0;
            if (alsa_api_.pcm_delay(pcm_, &delay) == 0) {
                struct timespec ts;
                clock_gettime(CLOCK_MONOTONIC, &ts);
                const uint64_t now =
                    static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
                    static_cast<uint64_t>(ts.tv_nsec);
                const uint64_t safe_delay =
                    delay > 0 ? static_cast<uint64_t>(delay) : 0ull;
                alsa_core_.PublishClock(
                    AlsaRenderCore::PresentedFrames(total_submitted_,
                                                    safe_delay),
                    now);
            }
        }
    }

    // ---- shared pw_init/pw_deinit refcount (process-global state) -------------
    static std::mutex& PwInitMutex() {
        static std::mutex m;
        return m;
    }
    static int& PwInitCount() {
        static int count = 0;
        return count;
    }

    // ---- constants ------------------------------------------------------------
    static constexpr uint32_t kAlsaWindowFrames = 256;  // 5.33 ms @ 48 kHz

    // ---- backend state -----------------------------------------------------------
    Backend backend_ = Backend::kFailed;
    std::atomic<bool> running_{false};
    const char* alsa_device_ = "default";

    // PipeWire path.
    pwabi::PwApi pw_api_{};
    void* pw_lib_ = nullptr;
    pwabi::PwThreadLoop* loop_ = nullptr;
    pwabi::PwStream* stream_ = nullptr;
    bool loop_started_ = false;
    pwabi::PwStreamEvents pw_events_{};
    PipeWireRenderCore pw_core_;
    std::thread clock_thread_;
    std::atomic<int> last_state_{0};
    std::atomic<int32_t> negotiated_rate_{0};
    std::atomic<int32_t> negotiated_channels_{0};
    std::atomic<bool> negotiated_f32_{false};
    char last_error_[256] = {0};

    // ALSA path.
    pwabi::AlsaApi alsa_api_{};
    void* alsa_lib_ = nullptr;
    void* pcm_ = nullptr;
    AlsaRenderCore alsa_core_;
    std::vector<float> alsa_window_;
    std::thread writer_thread_;
    uint64_t total_submitted_ = 0;

    // Shared.
    std::shared_ptr<sink::AudioSink> sink_;  // lifetime anchor (see Stop)
};

}  // namespace hal
}  // namespace streamify
// ---------------------------------------------------------------------------
// Minimal C facade (mirrors the Phase 4a CoreAudio / Phase 4b WASAPI
// facades — the desktop shells/UniFFI control plane interop with C).
// ---------------------------------------------------------------------------

extern "C" {

// Start pulling for a sink registered in the SinkRegistry. Tries PipeWire,
// falls back to ALSA. Returns an opaque driver handle (> 0) or 0 when
// neither backend is available. Release with streamify_linuxhal_stop.
int64_t streamify_linuxhal_start(int64_t sink_handle) {
    auto* driver = new (std::nothrow) streamify::hal::LinuxAudioDriver();
    if (driver == nullptr) return 0;
    if (!driver->Start(sink_handle)) {
        delete driver;
        return 0;
    }
    return reinterpret_cast<int64_t>(driver);
}

// Stop and release the driver (idempotent; 0/unknown handles are no-ops).
void streamify_linuxhal_stop(int64_t driver_handle) {
    if (driver_handle == 0) return;
    delete reinterpret_cast<streamify::hal::LinuxAudioDriver*>(driver_handle);
}

// Backend the driver is running: 0 = PipeWire, 1 = ALSA fallback,
// -1 = not started.
int32_t streamify_linuxhal_backend(int64_t driver_handle) {
    if (driver_handle == 0) return -1;
    auto* driver =
        reinterpret_cast<streamify::hal::LinuxAudioDriver*>(driver_handle);
    switch (driver->backend()) {
        case streamify::hal::LinuxAudioDriver::Backend::kPipeWire:
            return 0;
        case streamify::hal::LinuxAudioDriver::Backend::kAlsa:
            return 1;
        default:
            return -1;
    }
}

// Drain the latest DAC presentation clock sample. Writes the consistent
// (frames, rate, host_ns) triple; returns 1 on success, 0 when no sample
// has been published yet (or the handle is unknown).
int32_t streamify_linuxhal_latest_clock(int64_t driver_handle,
                                        uint64_t* out_frames,
                                        uint32_t* out_rate,
                                        uint64_t* out_host_ns) {
    if (driver_handle == 0 || out_frames == nullptr || out_rate == nullptr ||
        out_host_ns == nullptr) {
        return 0;
    }
    auto* driver =
        reinterpret_cast<streamify::hal::LinuxAudioDriver*>(driver_handle);
    return driver->LatestClock(out_frames, out_rate, out_host_ns) ? 1 : 0;
}

}  // extern "C"

#endif  // __linux__
