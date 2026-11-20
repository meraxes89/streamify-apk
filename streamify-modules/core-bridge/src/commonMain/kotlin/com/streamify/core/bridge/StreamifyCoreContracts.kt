package com.streamify.core.bridge

/**
 * Universal playback states emitted by Streamify Native Engine.
 */
enum class NativePlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

/**
 * Core engine configuration invariants.
 */
object StreamifyEngineConfig {
    const val CANONICAL_SAMPLE_RATE = 48000
    const val CANONICAL_CHANNELS = 2
    const val RINGBUFFER_CAPACITY_FRAMES = 32768
    const val TARGET_LUFS_DEFAULT = -14.0f
    const val TRUE_PEAK_CEILING_DEFAULT = -1.0f
}
