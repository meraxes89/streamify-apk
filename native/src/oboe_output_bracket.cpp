// ============================================================================
//  oboe_output_bracket.cpp — the Sovereign HAL Phase 2 Android output
//  bracket: Google Oboe 1.11.0 draining the FROZEN Rust ring edge.
//  (native/src/oboe_output_bracket.cpp)
// ============================================================================
//
//  THE MOUNTING GEOMETRY (the plan doc's Layer 3, applied to Android):
//
//      streamify-playback producer (Rust, libstreamify_core_rs.so)
//          writes f32 into SpscAudioRing ---- ring_export::install()
//                                                    |
//                                                    v  the FROZEN C edge
//      Oboe AudioStream::onAudioReady (SCHED_FIFO audio thread)
//          calls streamify_ring_read_f32(dst, frames)   (wait-free,
//          zero-alloc — the same discipline the cpal engine's callback
//          and the browser worklet's pull hold)
//
//  OBOE'S NEGOTIATION (why Oboe and not raw AAudio/OpenSL): Oboe picks
//  AAudio on API 27+ and OpenSL ES on API 26 / OEM-quirk fallbacks
//  (Google's in-tree device database — Samsung/Xiaomi/Oppo/Motorola
//  workarounds), requests MMAP low-latency when the device allows it,
//  and recovers routes (Bluetooth / USB-DAC / speaker hot-swap) through
//  onErrorAfterClose + the stream-reopen ladder. We hold NO audio
//  platform code of our own: the bracket is Oboe's builder settings
//  plus the frozen ring read.
//
//  THE RT CONTRACT (hard-realtime audio thread discipline):
//   * onAudioReady allocates NOTHING, locks NOTHING, calls NOTHING
//     beyond the frozen read edge (which is a memcpy + two atomic
//     stores on the happy path);
//   * underruns zero-fill cleanly (the ring's own underrun_events
//     counter already records the starvation — no side-channel);
//   * the 96-byte stats_wire is a plain memcpy snapshot, refreshed
//     only when the owner thread asks (never inside the callback);
//   * the DAC presentation clock rides a SEPARATE controller thread
//     polling AudioStream::getTimestamp(CLOCK_MONOTONIC) at 10 Hz —
//     getTimestamp performs binder work on some HALs and must never
//     sit inside the audio callback.
//
//  FALLBACK LADDER (the plan doc §3.2, Android arm):
//      Oboe/AAudio  →  Oboe/OpenSL ES  →  media3 DefaultAudioSink
//  The bracket's `sovereign_oboe_open` returns a structured verdict;
//  the caller (the CI player binary / AndroidSovereignLane) walks the
//  ladder on failure. media3 stays the shipped default (the
//  zero-deletion policy) — this bracket engages only when the
//  Sovereign HAL flag is on.
//
//  LOADER DISCIPLINE: the frozen edge lives in libstreamify_core_rs.so
//  (the Rust cdylib the app already packages). We dlopen it lazily and
//  resolve the 4 symbols once; a missing library degrades to
//  SOVEREIGN_OBOE_ERR_NO_RING (never a link-time dependency, never a
//  crash) — the same seam discipline jni_bridge_audio_sinks.cpp holds
//  against its registry.
// ============================================================================

#include <android/log.h>
#include <dlfcn.h>

#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <thread>

#include "oboe/Oboe.h"

#define LOG_TAG "sovereign-oboe"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── The frozen C edge (ring_export.rs — byte-frozen since Phase 0) ─────────
// Signature discipline mirrors streamify_audio_sinks.h's ABI rules: the
// read edge returns FRAMES (not samples); occupancy/channels report the
// process-current ring; stats_wire serializes the 96-byte image.
extern "C" {
typedef size_t (*streamify_ring_read_f32_t)(float* dst, size_t max_frames);
typedef uint64_t (*streamify_ring_occupancy_frames_t)();
typedef uint32_t (*streamify_ring_channels_t)();
typedef uint32_t (*streamify_ring_stats_wire_t)(uint8_t* dst, uint32_t dst_bytes);
}

#define SINK_STATS_WIRE_BYTES 96

namespace {

// Resolved once at open; the audio callback dereferences plain function
// pointers (a data race is impossible — set before the stream starts).
struct FrozenEdge {
    streamify_ring_read_f32_t read = nullptr;
    streamify_ring_occupancy_frames_t occupancy = nullptr;
    streamify_ring_channels_t channels = nullptr;
    streamify_ring_stats_wire_t stats_wire = nullptr;
    void* lib = nullptr;

    bool resolve() {
        if (read) return true;  // already resolved
        lib = dlopen("libstreamify_core_rs.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) {
            ALOGE("frozen edge: libstreamify_core_rs.so not loadable: %s", dlerror());
            return false;
        }
        read = reinterpret_cast<streamify_ring_read_f32_t>(
            dlsym(lib, "streamify_ring_read_f32"));
        occupancy = reinterpret_cast<streamify_ring_occupancy_frames_t>(
            dlsym(lib, "streamify_ring_occupancy_frames"));
        channels = reinterpret_cast<streamify_ring_channels_t>(
            dlsym(lib, "streamify_ring_channels"));
        stats_wire = reinterpret_cast<streamify_ring_stats_wire_t>(
            dlsym(lib, "streamify_ring_stats_wire"));
        if (!read || !occupancy || !channels || !stats_wire) {
            ALOGE("frozen edge: incomplete symbol set (read=%p occ=%p ch=%p stats=%p)",
                  read, occupancy, channels, stats_wire);
            return false;
        }
        return true;
    }
};

FrozenEdge g_edge;

// ── The DAC presentation-clock mailbox (callback-free polling) ─────────────
// The controller thread polls Oboe's getTimestamp and publishes one
// sample per poll; the owner (Kotlin lane / CI verifier) reads the
// latest snapshot. Same release/acquire discipline as DacClockSlot.
struct ClockMailbox {
    std::atomic<uint64_t> frame_position{0};
    std::atomic<uint32_t> sample_rate{0};
    std::atomic<uint64_t> monotonic_ns{0};
    std::atomic<uint64_t> seq{0};  // 0 = no sample yet (sample() contract)
};
ClockMailbox g_clock;

// ── The RT callback state (per-stream, plain atomics) ──────────────────────
struct RtState {
    std::atomic<uint64_t> callbacks{0};
    std::atomic<uint64_t> underrun_frames{0};
    std::atomic<bool> first_callback_done{false};
};
RtState g_rt;



// ── The Oboe data callback (the SCHED_FIFO audio thread) ───────────────────
class SovereignOboeCallback : public oboe::AudioStreamDataCallback {
public:
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
                                          void* audioData,
                                          int32_t numFrames) override {
        // ZERO-ALLOC DISCIPLINE: no log, no lock, no allocation. The
        // counters are relaxed atomics on cached lines (the Phase 1
        // eprintln-stall lesson: nothing but the read + the copy runs
        // on this thread).
        g_rt.callbacks.fetch_add(1, std::memory_order_relaxed);
        bool expected = false;
        if (g_rt.first_callback_done.compare_exchange_strong(expected, true,
                                                             std::memory_order_relaxed)) {
            // first callback marker only (main thread polls this)
        }
        auto* dst = static_cast<float*>(audioData);
        const size_t channels = 2;  // the canonical geometry (48k stereo)
        const size_t want = static_cast<size_t>(numFrames);

        if (!g_edge.read) {
            // No ring installed (producer never attached): silence.
            std::memset(dst, 0, want * channels * sizeof(float));
            g_rt.underrun_frames.fetch_add(want, std::memory_order_relaxed);
            return oboe::DataCallbackResult::Continue;
        }

        const size_t served = g_edge.read(dst, want);
        if (served < want) {
            // Clean zero-fill on underrun (the ring's own counter also
            // records it — no side-channel accounting here).
            g_rt.underrun_frames.fetch_add(want - served, std::memory_order_relaxed);
            std::memset(dst + served * channels, 0,
                        (want - served) * channels * sizeof(float));
        }
        return oboe::DataCallbackResult::Continue;
    }
};

// ── The error callback: route change / device loss → reopen ladder ─────────
class SovereignOboeErrorCallback : public oboe::AudioStreamErrorCallback {
public:
    explicit SovereignOboeErrorCallback(std::atomic<bool>* reopen_flag)
        : reopen_(reopen_flag) {}

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        // Bluetooth connect/disconnect, USB-DAC hot-swap, HAL death:
        // Oboe already closed the stream; signal the controller thread
        // to rebuild it (the automatic recovery ladder). The audio
        // thread itself never blocks here — this runs on Oboe's
        // callback thread AFTER the stream is dead.
        ALOGW("stream error after close: %s — signalling reopen",
              oboe::convertToText(error));
        reopen_->store(true, std::memory_order_release);
    }

private:
    std::atomic<bool>* reopen_;
};

}  // namespace

// The ownership shape mirrors the desktop engine's: open → (run) →
// close, with the clock + stats + counters queryable while running.
struct SovereignOboeState {
    std::atomic<bool> running{false};
    std::atomic<bool> reopen{false};
    std::thread controller;
    std::shared_ptr<oboe::AudioStream> stream;
    SovereignOboeCallback data_cb;
    // Kept alive by the stream; the pointer stays valid because the
    // state outlives close (we join the controller first).
    std::unique_ptr<SovereignOboeErrorCallback> error_cb;

    ~SovereignOboeState() { close(); }

    void close() {
        running.store(false, std::memory_order_release);
        if (controller.joinable()) controller.join();
        if (stream) {
            stream->requestStop();
            stream->close();
            stream.reset();
        }
    }
};

static SovereignOboeState* g_state = nullptr;

// Builds + starts one stream. audio_api selects the Oboe backend hint
// (1 = let Oboe pick AAudio first, 2 = force OpenSL ES — the ladder).
static oboe::Result open_stream(SovereignOboeState* st, int audio_api) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(48000)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
        ->setDataCallback(&st->data_cb)
        ->setErrorCallback(st->error_cb.get());
    if (audio_api == 2) {
        // The ladder's second rung: OpenSL ES (API 26 + the OEM-quirk
        // fallbacks). Oboe implements this as its legacy backend.
        builder.setAudioApi(oboe::AudioApi::OpenSLES);
    } else {
        builder.setAudioApi(oboe::AudioApi::Unspecified);  // AAudio first
    }
    oboe::Result r = builder.openStream(st->stream);
    if (r != oboe::Result::OK) return r;
    r = st->stream->requestStart();
    if (r != oboe::Result::OK) {
        st->stream->close();
        st->stream.reset();
        return r;
    }
    return oboe::Result::OK;
}

// The controller thread: clock polling + the reopen ladder. Owns NO
// audio data — getTimestamp does binder work on some HALs and is
// therefore banned from the audio thread (the RT contract above).
static void controller_loop(SovereignOboeState* st, int audio_api,
                            std::atomic<uint64_t>* clock_samples) {
    int backoff_ms = 50;
    while (st->running.load(std::memory_order_acquire)) {
        if (st->reopen.exchange(false, std::memory_order_acq_rel)) {
            ALOGI("controller: reopening stream (audio_api=%d)", audio_api);
            if (st->stream) {
                st->stream->requestStop();
                st->stream->close();
                st->stream.reset();
            }
            oboe::Result r = open_stream(st, audio_api);
            ALOGI("controller: reopen -> %s", oboe::convertToText(r));
        }
        auto s = st->stream;
        if (s) {
            int64_t frame = 0;
            int64_t ns = 0;
            if (s->getTimestamp(CLOCK_MONOTONIC, &frame, &ns) ==
                oboe::Result::OK) {
                g_clock.frame_position.store(static_cast<uint64_t>(frame),
                                             std::memory_order_release);
                g_clock.monotonic_ns.store(static_cast<uint64_t>(ns),
                                           std::memory_order_release);
                g_clock.sample_rate.store(
                    static_cast<uint32_t>(s->getSampleRate()),
                    std::memory_order_release);
                g_clock.seq.fetch_add(1, std::memory_order_release);
                if (clock_samples) clock_samples->fetch_add(1, std::memory_order_relaxed);
            }
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(backoff_ms));
    }
}

// ── C exports (extern "C"): the CI player binary + JNI lane drive these ────
extern "C" {

// Opens the bracket against the installed frozen ring. audio_api: 1 =
// AAudio-first (Oboe negotiates), 2 = force OpenSL ES. Returns 0 on
// success; negative = the error code (the ladder walks on failure).
int32_t sovereign_oboe_open(int32_t audio_api) {
    if (g_state) return 0;  // idempotent
    if (!g_edge.resolve()) return -1;  // SOVEREIGN_OBOE_ERR_NO_RING
    auto* st = new SovereignOboeState();
    st->error_cb = std::make_unique<SovereignOboeErrorCallback>(&st->reopen);
    oboe::Result r = open_stream(st, audio_api);
    if (r != oboe::Result::OK) {
        ALOGE("open_stream(api=%d) failed: %s", audio_api, oboe::convertToText(r));
        delete st;
        return -2;  // SOVEREIGN_OBOE_ERR_OPEN
    }
    g_state = st;
    st->running.store(true, std::memory_order_release);
    st->controller = std::thread(controller_loop, st, audio_api, nullptr);
    ALOGI("sovereign oboe bracket open (api=%d, frames/callback burst=%d)",
          audio_api, g_state->stream->getFramesPerBurst());
    return 0;
}

// Closes + joins (the ownership discipline: the caller drives lifecycle).
void sovereign_oboe_close() {
    if (!g_state) return;
    SovereignOboeState* st = g_state;
    g_state = nullptr;
    st->close();
    delete st;
    ALOGI("sovereign oboe bracket closed");
}

// The live counters (callback count / underrun frames / ring occupancy).
uint64_t sovereign_oboe_callbacks() {
    return g_state ? g_rt.callbacks.load(std::memory_order_relaxed) : 0;
}
uint64_t sovereign_oboe_underrun_frames() {
    return g_state ? g_rt.underrun_frames.load(std::memory_order_relaxed) : 0;
}
uint64_t sovereign_oboe_ring_occupancy() {
    return g_edge.occupancy ? g_edge.occupancy() : 0;
}

// The latest DAC clock sample (the record_dac_clock bridge). Returns 0
// while no sample exists; writes the three fields + seq otherwise.
int32_t sovereign_oboe_dac_clock(uint64_t* frame, uint32_t* rate,
                                 uint64_t* monotonic_ns) {
    const uint64_t seq = g_clock.seq.load(std::memory_order_acquire);
    if (seq == 0) return 0;
    *frame = g_clock.frame_position.load(std::memory_order_acquire);
    *rate = g_clock.sample_rate.load(std::memory_order_acquire);
    *monotonic_ns = g_clock.monotonic_ns.load(std::memory_order_acquire);
    return 1;
}

// Serializes the ring's 96-byte stats wire (the frozen image; returns
// 1 when a ring is attached, 0 otherwise — ring_export semantics).
int32_t sovereign_oboe_stats_wire(uint8_t* dst, int32_t dst_bytes) {
    if (!g_edge.stats_wire || dst_bytes < SINK_STATS_WIRE_BYTES) return 0;
    return static_cast<int32_t>(g_edge.stats_wire(dst, SINK_STATS_WIRE_BYTES));
}

// The stream's Oboe backend actually in use (1 = AAudio, 2 = OpenSL
// ES, 0 = none) — the route telemetry the SettingScreen surfaces.
int32_t sovereign_oboe_api_route() {
    if (!g_state || !g_state->stream) return 0;
    return g_state->stream->getAudioApi() == oboe::AudioApi::AAudio ? 1 : 2;
}

}  // extern "C"
