#ifndef STREAMIFY_FRAME_PALETTE_H
#define STREAMIFY_FRAME_PALETTE_H
// ============================================================================
//  streamify_frame_palette.h — Phase-5 JNI bridge support: default frame
//  pacer instance + DirectByteBuffer stats serialization
//  (native/include/streamify_frame_palette.h)
// ============================================================================
//
//  Deliverable 3 support layer for native/src/jni_bridge_frame_palette.cpp.
//  Deliberately jni.h-free so the host test suite can exercise the wire
//  layout and the default instance without an NDK.
//
//  JAVA-SIDE CONTRACT (bound by app-side RegisterNatives; the frozen symbol
//  names live in jni_bridge_frame_palette.cpp — 'native' is a Java keyword,
//  so the binding class escapes the package name the same way Phase-4's
//  NativeAudioSink does):
//
//    object com.streamify.core.native_.NativePalette {
//        // RGBA8888 DirectByteBuffer (Bitmap.copyPixelsToBuffer of an
//        // ARGB_8888 bitmap), stride IN BYTES. Returns the number of roles
//        // written to outColors (4) or a negative PaletteError code.
//        external fun nativeExtractPaletteSimd(
//            pixels: ByteBuffer, width: Int, height: Int, stride: Int,
//            outColors: IntArray): Int
//        // WCAG 2.1 contrast check; ARGB ColorInts, alpha ignored.
//        external fun nativeCheckContrast(a: Int, b: Int,
//                                         minRatio: Float): Boolean
//    }
//    object com.streamify.core.native_.NativeFramePacer {
//        external fun nativeRecordFrame(frameDurationNs: Long,
//                                       vsyncOffsetNs: Long)
//        external fun nativeGetStats(statsOut: ByteBuffer)  // 192-byte layout
//        external fun nativeReset()
//    }
//
//  EXTRACT CONTRACT
//  ----------------
//  * pixels must be a DIRECT ByteBuffer with capacity >=
//    (height - 1) * stride + width * 4; row-major RGBA8888; alpha is
//    ignored by the clustering (the RGBA byte order is exactly what
//    Bitmap.copyPixelsToBuffer produces for ARGB_8888 bitmaps).
//  * outColors must have length >= 4; on success it receives
//    [dominant_vibrant, dark_muted, light_vibrant, text_surface] as ARGB
//    ColorInts (alpha 0xFF). text_surface is guaranteed >= 4.5:1 against
//    dominant_vibrant (WCAG 2.1 AA, see palette_extractor_simd.h).
//  * Return codes mirror PaletteError verbatim:
//      4 = success (roles written), -1 null argument, -2 bad dimensions,
//      -3 bad stride, -4 buffer too small, -5 out-of-memory scratch.
//
//  STATS DIRECTBYTEBUFFER LAYOUT (192 bytes, v1, little-endian)
//  ------------------------------------------------------------
//    +0    u32  layout magic 0x5FAC0001
//    +4    u32  layout bytes (192)
//    +8    u64  total_frames            (cumulative, valid frames)
//    +16   u64  janky_frames_120        (cumulative, > 8,333,333ns)
//    +24   u64  janky_frames_60         (cumulative, > 16,666,667ns)
//    +32   u64  invalid_samples         (cumulative, quarantined)
//    +40   u64  vsync_misaligned        (cumulative, |offset| > tolerance)
//    +48   u64  max_streak_janky        (cumulative worst streak)
//    +56   u64  total_max_frame_ns      (cumulative worst frame)
//    +64   u32  window_frames           (<= 512)
//    +68   u32  current_streak          (live)
//    +72   u32  flags                   (bit0 = stale snapshot)
//    +76   u32  reserved (0)
//    +80   u64  window_p50_ns
//    +88   u64  window_p90_ns
//    +96   u64  window_p95_ns
//    +104  u64  window_p99_ns
//    +112  u64  window_max_ns
//    +120  u64  window_mean_ns
//    +128  u64  window_stddev_ns
//    +136  f64  estimated_fps
//    +144  i64  vsync_mean_offset_ns
//    +152  u64  vsync_abs_mean_ns
//    +160  u64  vsync_max_abs_ns
//    +168  u64  window_last_ts_ns       (CLOCK_MONOTONIC_RAW, newest frame)
//    +176  u64  stale_reads             (cumulative, reader-side)
//    +184  u32  stats sequence          (increments on every write)
//    +188  u32  reserved (0)
// ============================================================================

#include <cstdint>
#include <cstring>

#include "frame_pacer_monitor.h"

namespace streamify {
namespace pacer {

// ---- stats wire layout ---------------------------------------------------------
inline constexpr uint32_t kFramePacerStatsOffMagic = 0;
inline constexpr uint32_t kFramePacerStatsOffLayoutBytes = 4;
inline constexpr uint32_t kFramePacerStatsOffTotalFrames = 8;
inline constexpr uint32_t kFramePacerStatsOffJanky120 = 16;
inline constexpr uint32_t kFramePacerStatsOffJanky60 = 24;
inline constexpr uint32_t kFramePacerStatsOffInvalid = 32;
inline constexpr uint32_t kFramePacerStatsOffMisaligned = 40;
inline constexpr uint32_t kFramePacerStatsOffMaxStreak = 48;
inline constexpr uint32_t kFramePacerStatsOffTotalMax = 56;
inline constexpr uint32_t kFramePacerStatsOffWindowFrames = 64;
inline constexpr uint32_t kFramePacerStatsOffCurrentStreak = 68;
inline constexpr uint32_t kFramePacerStatsOffFlags = 72;
inline constexpr uint32_t kFramePacerStatsOffReserved0 = 76;
inline constexpr uint32_t kFramePacerStatsOffP50 = 80;
inline constexpr uint32_t kFramePacerStatsOffP90 = 88;
inline constexpr uint32_t kFramePacerStatsOffP95 = 96;
inline constexpr uint32_t kFramePacerStatsOffP99 = 104;
inline constexpr uint32_t kFramePacerStatsOffWindowMax = 112;
inline constexpr uint32_t kFramePacerStatsOffWindowMean = 120;
inline constexpr uint32_t kFramePacerStatsOffWindowStddev = 128;
inline constexpr uint32_t kFramePacerStatsOffFps = 136;
inline constexpr uint32_t kFramePacerStatsOffVsyncMean = 144;
inline constexpr uint32_t kFramePacerStatsOffVsyncAbsMean = 152;
inline constexpr uint32_t kFramePacerStatsOffVsyncMaxAbs = 160;
inline constexpr uint32_t kFramePacerStatsOffLastTs = 168;
inline constexpr uint32_t kFramePacerStatsOffStaleReads = 176;
inline constexpr uint32_t kFramePacerStatsOffSequence = 184;
inline constexpr uint32_t kFramePacerStatsOffReserved1 = 188;

// ---- little-endian put helpers (all Android ABIs are LE; host tests on
// LE x86_64 verify byte-level offsets) ------------------------------------------
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

inline void PutWireI64(uint8_t* p, int64_t v) {
    PutWireU64(p, static_cast<uint64_t>(v));
}

inline void PutWireF64(uint8_t* p, double v) {
    uint64_t bits = 0;
    std::memcpy(&bits, &v, sizeof(bits));
    PutWireU64(p, bits);
}

// Serialize the monitor's current snapshot into `buf` using the 192-byte
// little-endian layout above. Returns false (buffer untouched) when `buf` is
// null or `cap` < kFramePacerStatsWireBytes — a telemetry probe must never
// take the playback/render path down. The stats sequence field increments on
// every successful write (change detector for polling consumers).
bool WriteFramePacerStatsBuffer(const FramePacerMonitor& monitor, uint8_t* buf,
                                size_t cap);

// Process-wide default monitor bound to the NativeFramePacer JNI statics.
// Function-local static: zero dynamic allocation ever (~25 KB in .bss), no
// static-initialization-order hazards, instance lives for the process
// lifetime. RecordFrame is the Choreographer thread (single writer),
// GetStats the telemetry poller (single reader) — SPSC contract from
// frame_pacer_monitor.h.
FramePacerMonitor& DefaultFramePacer();

}  // namespace pacer
}  // namespace streamify

#endif  // STREAMIFY_FRAME_PALETTE_H
