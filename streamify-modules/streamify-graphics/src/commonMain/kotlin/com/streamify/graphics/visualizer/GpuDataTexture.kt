package com.streamify.graphics.visualizer

/**
 * Universal zero-allocation staging interface for GPU data textures.
 *
 * Transfers streaming sensor, DSP, audio, or color data into offscreen
 * textures for zero-recomposition fragment shader consumption.
 */
interface GpuDataTexture {
    /** Texture width in texels. */
    val textureWidth: Int

    /** Texture height in texels. */
    val textureHeight: Int

    /** Row stride in bytes (tight packing: width * bytesPerTexel). */
    val rowBytes: Int

    /** Pre-allocated byte payload backing the GPU texture upload. */
    val payload: ByteArray

    /** Monotonic update counter for skipping redundant GPU texture uploads. */
    val frameStamp: Long

    /** Clears texture payload to silence/empty state. */
    fun clear()
}
