// ============================================================================
//  streamify_frame_palette.cpp — Phase-5 JNI support: stats wire
//  serialization + process-default frame pacer
//  (native/src/streamify_frame_palette.cpp, host-testable, jni.h-free)
// ============================================================================

#include "../include/streamify_frame_palette.h"

namespace streamify {
namespace pacer {

bool WriteFramePacerStatsBuffer(const FramePacerMonitor& monitor, uint8_t* buf,
                                size_t cap) {
    if (buf == nullptr || cap < kFramePacerStatsWireBytes) return false;

    FramePacerStats st;
    if (!monitor.GetStats(&st)) return false;

    PutWireU32(buf + kFramePacerStatsOffMagic, st.layout_magic);
    PutWireU32(buf + kFramePacerStatsOffLayoutBytes, st.layout_bytes);
    PutWireU64(buf + kFramePacerStatsOffTotalFrames, st.total_frames);
    PutWireU64(buf + kFramePacerStatsOffJanky120, st.janky_frames_120);
    PutWireU64(buf + kFramePacerStatsOffJanky60, st.janky_frames_60);
    PutWireU64(buf + kFramePacerStatsOffInvalid, st.invalid_samples);
    PutWireU64(buf + kFramePacerStatsOffMisaligned, st.vsync_misaligned);
    PutWireU64(buf + kFramePacerStatsOffMaxStreak, st.max_streak_janky);
    PutWireU64(buf + kFramePacerStatsOffTotalMax, st.total_max_frame_ns);
    PutWireU32(buf + kFramePacerStatsOffWindowFrames, st.window_frames);
    PutWireU32(buf + kFramePacerStatsOffCurrentStreak, st.current_streak);
    PutWireU32(buf + kFramePacerStatsOffFlags, st.flags);
    PutWireU32(buf + kFramePacerStatsOffReserved0, 0);
    PutWireU64(buf + kFramePacerStatsOffP50, st.window_p50_ns);
    PutWireU64(buf + kFramePacerStatsOffP90, st.window_p90_ns);
    PutWireU64(buf + kFramePacerStatsOffP95, st.window_p95_ns);
    PutWireU64(buf + kFramePacerStatsOffP99, st.window_p99_ns);
    PutWireU64(buf + kFramePacerStatsOffWindowMax, st.window_max_ns);
    PutWireU64(buf + kFramePacerStatsOffWindowMean, st.window_mean_ns);
    PutWireU64(buf + kFramePacerStatsOffWindowStddev, st.window_stddev_ns);
    PutWireF64(buf + kFramePacerStatsOffFps, st.estimated_fps);
    PutWireI64(buf + kFramePacerStatsOffVsyncMean, st.vsync_mean_offset_ns);
    PutWireU64(buf + kFramePacerStatsOffVsyncAbsMean, st.vsync_abs_mean_ns);
    PutWireU64(buf + kFramePacerStatsOffVsyncMaxAbs, st.vsync_max_abs_ns);
    PutWireU64(buf + kFramePacerStatsOffLastTs, st.window_last_ts_ns);
    PutWireU64(buf + kFramePacerStatsOffStaleReads, st.stale_reads);
    PutWireU32(buf + kFramePacerStatsOffSequence, st.sequence);
    PutWireU32(buf + kFramePacerStatsOffReserved1, 0);
    return true;
}

FramePacerMonitor& DefaultFramePacer() {
    static FramePacerMonitor monitor;  // ~25 KB in .bss, process lifetime
    return monitor;
}

}  // namespace pacer
}  // namespace streamify
