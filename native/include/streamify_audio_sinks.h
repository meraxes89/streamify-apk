#ifndef STREAMIFY_AUDIO_SINKS_H
#define STREAMIFY_AUDIO_SINKS_H
// ============================================================================
//  streamify_audio_sinks.h — Phase-4 JNI bridge support: sink registry,
//  DirectByteBuffer stats serialization (native/include/streamify_audio_sinks.h)
// ============================================================================
//
//  Deliverable 3 support layer for native/src/jni_bridge_audio_sinks.cpp.
//  Deliberately jni.h-free so the host test suite can exercise the registry
//  and the stats wire layout without an NDK.
//
//  JAVA-SIDE CONTRACT (bound by app-side RegisterNatives; the frozen symbol
//  names live in jni_bridge_audio_sinks.cpp):
//
//    class com.streamify.core.native_.NativeAudioSink   // 'native' is a Java
//        external fun nativeCreateSink(sampleRate: Int, channels: Int,      // keyword; the
//                                      capacityFrames: Int): Long           // binding class
//        external fun nativeWriteFloat(handle: Long, data: FloatArray,      // escapes it via
//                                      offset: Int, length: Int): Int       // a legal name
//        external fun nativeReadDirect(handle: Long, buf: ByteBuffer,       // registered
//                                     capacityBytes: Int): Int              // manually.
//        external fun nativeGetStats(handle: Long, statsOut: ByteBuffer)
//        external fun nativeFlush(handle: Long)   // cooperative (AMP-10):
//                                                 // routes to RequestFlush()
//                                                 // — safe while the audio
//                                                 // callback is live; the
//                                                 // consumer thread performs
//                                                 // the cursor reset on its
//                                                 // next read
//        external fun nativeDestroy(handle: Long)
//
//  * Handles are opaque monotonically-increasing int64 ids from the registry
//    below — NEVER raw pointers, so a stale, garbage or post-destroy handle
//    resolves to nullptr and can never crash the JVM. Destruction is
//    reference-counted: a destroy racing an in-flight call just drops the
//    registry entry; the shared_ptr keeps the sink alive until the last
//    caller is done with it.
//  * statsOut must be a DirectByteBuffer with capacity >= 96 bytes; the
//    layout below is written little-endian (all Android ABIs are LE).
//
//  STATS DIRECTBYTEBUFFER LAYOUT (96 bytes, v1)
//  --------------------------------------------
//    +0    u32  layout magic 0x51AB0001
//    +4    u32  layout bytes (96)
//    +8    u32  capacity_frames
//    +12   u32  sample_rate_in (Hz)
//    +16   u32  sample_rate_out (Hz)
//    +20   u32  channels_in
//    +24   u32  channels_out
//    +28   u32  current_fullness_frames
//    +32   u64  frames_written
//    +40   u64  frames_read
//    +48   u64  underrun_events
//    +56   u64  overrun_events
//    +64   u64  overrun_dropped_frames
//    +72   u32  high_watermark_frames
//    +76   u32  low_watermark_frames
//    +80   f32  fullness_percent
//    +84   f32  estimated_latency_ms
//    +88   u32  stats sequence (increments on every write; change detector)
//    +92   u32  reserved (0)
// ============================================================================

#include <atomic>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "audio_sink_ringbuffer.h"

namespace streamify {
namespace sink {

// ---- stats wire layout --------------------------------------------------------
inline constexpr uint32_t kSinkStatsWireMagic = 0x51AB0001u;
inline constexpr uint32_t kSinkStatsWireBytes = 96;

inline constexpr uint32_t kSinkStatsOffMagic = 0;
inline constexpr uint32_t kSinkStatsOffLayoutBytes = 4;
inline constexpr uint32_t kSinkStatsOffCapacity = 8;
inline constexpr uint32_t kSinkStatsOffRateIn = 12;
inline constexpr uint32_t kSinkStatsOffRateOut = 16;
inline constexpr uint32_t kSinkStatsOffChannelsIn = 20;
inline constexpr uint32_t kSinkStatsOffChannelsOut = 24;
inline constexpr uint32_t kSinkStatsOffFullness = 28;
inline constexpr uint32_t kSinkStatsOffFramesWritten = 32;
inline constexpr uint32_t kSinkStatsOffFramesRead = 40;
inline constexpr uint32_t kSinkStatsOffUnderruns = 48;
inline constexpr uint32_t kSinkStatsOffOverruns = 56;
inline constexpr uint32_t kSinkStatsOffOverrunDropped = 64;
inline constexpr uint32_t kSinkStatsOffHighWatermark = 72;
inline constexpr uint32_t kSinkStatsOffLowWatermark = 76;
inline constexpr uint32_t kSinkStatsOffFullnessPct = 80;
inline constexpr uint32_t kSinkStatsOffLatencyMs = 84;
inline constexpr uint32_t kSinkStatsOffSequence = 88;
inline constexpr uint32_t kSinkStatsOffReserved = 92;

inline void PutWireU16(uint8_t* p, uint16_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFFu);
    p[1] = static_cast<uint8_t>(v >> 8);
}

inline void PutWireU32(uint8_t* p, uint32_t v) {
    p[0] = static_cast<uint8_t>(v & 0xFFu);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFFu);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFFu);
    p[3] = static_cast<uint8_t>((v >> 24) & 0xFFu);
}

inline void PutWireU64(uint8_t* p, uint64_t v) {
    PutWireU32(p, static_cast<uint32_t>(v & 0xFFFFFFFFu));
    PutWireU32(p + 4, static_cast<uint32_t>(v >> 32));
}

inline void PutWireF32(uint8_t* p, float v) {
    uint32_t bits = 0;
    std::memcpy(&bits, &v, sizeof(bits));  // bit-preserving
    PutWireU32(p, bits);
}

// Serialize a sink's telemetry into the documented 96-byte DirectByteBuffer
// layout. Returns false when args are invalid or the buffer is too small.
inline bool WriteSinkStatsBuffer(const AudioSink& sink, uint8_t* dst,
                                 size_t capacity) {
    if (dst == nullptr || capacity < kSinkStatsWireBytes) return false;
    SinkStats st;
    sink.GetStats(&st);
    std::memset(dst, 0, kSinkStatsWireBytes);

    PutWireU32(dst + kSinkStatsOffMagic, kSinkStatsWireMagic);
    PutWireU32(dst + kSinkStatsOffLayoutBytes, kSinkStatsWireBytes);
    PutWireU32(dst + kSinkStatsOffCapacity, st.capacity_frames);
    PutWireU32(dst + kSinkStatsOffRateIn, st.sample_rate_in);
    PutWireU32(dst + kSinkStatsOffRateOut, st.sample_rate_out);
    PutWireU32(dst + kSinkStatsOffChannelsIn, st.channels_in);
    PutWireU32(dst + kSinkStatsOffChannelsOut, st.channels_out);
    PutWireU32(dst + kSinkStatsOffFullness, st.current_fullness_frames);
    PutWireU64(dst + kSinkStatsOffFramesWritten, st.frames_written);
    PutWireU64(dst + kSinkStatsOffFramesRead, st.frames_read);
    PutWireU64(dst + kSinkStatsOffUnderruns, st.underrun_events);
    PutWireU64(dst + kSinkStatsOffOverruns, st.overrun_events);
    PutWireU64(dst + kSinkStatsOffOverrunDropped, st.overrun_dropped_frames);
    PutWireU32(dst + kSinkStatsOffHighWatermark, st.high_watermark_frames);
    PutWireU32(dst + kSinkStatsOffLowWatermark, st.low_watermark_frames);
    PutWireF32(dst + kSinkStatsOffFullnessPct, st.fullness_percent);
    PutWireF32(dst + kSinkStatsOffLatencyMs, st.estimated_latency_ms);

    // Per-write monotonic sequence so Java change detectors can cheaply see
    // that a fresh snapshot landed (function-local static: ODR-safe inline).
    static std::atomic<uint32_t> s_stats_sequence{0};
    const uint32_t seq = s_stats_sequence.fetch_add(1, std::memory_order_relaxed) + 1;
    PutWireU32(dst + kSinkStatsOffSequence, seq);
    return true;
}

// ---- sink registry --------------------------------------------------------------
// Opaque int64 handles; mutex-guarded map with shared_ptr values so destroy
// racing an in-flight resolve can never use-after-free. The lock is held
// only for the map lookup (nanoseconds), never across audio work.

class SinkRegistry {
public:
    static SinkRegistry& instance() {
        static SinkRegistry registry;  // thread-safe magic static
        return registry;
    }

    // Returns a fresh handle (> 0), or 0 on invalid config / OOM.
    int64_t create(const AudioSink::Config& cfg) {
        if (!AudioSink::ValidateConfig(cfg)) return 0;
        auto sink = std::make_shared<AudioSink>(AudioSink::NormalizeConfig(cfg));
        if (!sink->valid()) return 0;
        std::lock_guard<std::mutex> lock(mutex_);
        const int64_t handle = next_handle_++;
        sinks_.emplace(handle, std::move(sink));
        return handle;
    }

    // nullptr for unknown / already-destroyed handles. The returned
    // shared_ptr keeps the sink alive for the duration of the call.
    std::shared_ptr<AudioSink> resolve(int64_t handle) const {
        if (handle <= 0) return nullptr;
        std::lock_guard<std::mutex> lock(mutex_);
        const auto it = sinks_.find(handle);
        return it == sinks_.end() ? nullptr : it->second;
    }

    bool destroy(int64_t handle) {
        if (handle <= 0) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        return sinks_.erase(handle) != 0;
    }

    size_t liveCount() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return sinks_.size();
    }

    void destroyAll() {  // host-test helper
        std::lock_guard<std::mutex> lock(mutex_);
        sinks_.clear();
    }

private:
    SinkRegistry() = default;
    mutable std::mutex mutex_;
    std::unordered_map<int64_t, std::shared_ptr<AudioSink>> sinks_;
    int64_t next_handle_ = 1;  // monotonic; handles are never reused
};

}  // namespace sink
}  // namespace streamify

#endif  // STREAMIFY_AUDIO_SINKS_H
