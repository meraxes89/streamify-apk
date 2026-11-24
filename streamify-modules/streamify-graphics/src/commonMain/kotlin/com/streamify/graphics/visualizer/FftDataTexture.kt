package com.streamify.graphics.visualizer

/**
 * Zero-allocation staging core of the Phase 3-A audio visualizer data
 * texture (docs/gpu/ARCHITECTURE.md section 4, docs/gpu/PHASED_PLAN.md
 * Phase 3 Track A).
 *
 * Passing FFT frames through Compose state recomposes the tree on every
 * frame; instead, the DSP producer's 1024-bin float frame is encoded
 * once into this pre-allocated RGBA_8888 staging payload, and the platform
 * adapter transfers it to the GPU as a 1024x1 offscreen texture that the
 * canonical `VisualizerShader.sksl` samples inside the draw phase. The
 * whole per-frame CPU cost is [update] — a clamped 8-bit quantization
 * loop over 1024 bins — and the whole per-frame payload is
 * [PAYLOAD_BYTES] (4096 bytes, the section 4 envelope).
 *
 * ## Texture encoding contract (consumed by VisualizerShader.sksl)
 *
 *  * Layout: 1024x1 RGBA_8888, row-major, [rowBytes] = 4096 — texel k is
 *    the four bytes `payload[4k .. 4k+3]` (R, G, B, A in byte order).
 *  * R = quantized magnitude of FFT bin k (0..255 ↔ 0.0..1.0); G and B
 *    are reserved and stay 0; A = 255 (opaque — the payload is raw data,
 *    never a color, so no color space must be attached at bind time).
 *  * Single-channel encoding is deliberate: magnitudes live in exactly
 *    one channel, so LINEAR filtering interpolates neighboring bins
 *    correctly (a multi-byte split across channels would break under
 *    interpolation), and 8 bits match every runtime-shader lane
 *    (Skia child shader, AGSL `setInputShader`) with no swizzle variance.
 *  * Child-shader coordinates are image pixels: texel k's center sits at
 *    `(k + 0.5, 0.5)` — the shader's `magnitudeAtBin()` relies on it.
 *
 * ## Platform binding seams
 *
 *  * skiko (desktop JVM / wasmJs): `Bitmap.installPixels(ImageInfo(w, 1,
 *    ColorType.RGBA_8888, ColorAlphaType.OPAQUE), texture.payload,
 *    texture.rowBytes)` then `Bitmap.makeShader(CLAMP, CLAMP, LINEAR)`
 *    bound as the shader's `fftTexture` child — the exact path the
 *    `VisualizerShaderTest` render gates exercise.
 *  * Android (API 33+): the same payload bytes back a
 *    `Bitmap.Config.RGBA_8888` bitmap feeding an AGSL
 *    `RuntimeShader.setInputShader("fftTexture", BitmapShader)`.
 *  * [payload] is the SAME pre-allocated instance for the lifetime of
 *    this texture — adapters may wrap/pin it once and still observe
 *    every subsequent [update].
 *
 * ## Invariants (pinned by the desktop test suite)
 *
 *  * **Zero heap allocations per frame** — every buffer is allocated once
 *    at construction; [update] is a primitive-only loop over those
 *    buffers (verified per-thread via `com.sun.management.ThreadMXBean`).
 *  * **Payload <= 4 KB** — fixed [PAYLOAD_BYTES] = 4096, no growth path.
 *  * **Stage cost < 5 us** — the quantize+store loop is the entire
 *    per-frame CPU work on the audio render path (median-gated in CI).
 *  * [magnitude] reads back the quantized value (q / 255), so a Tier 3
 *    legacy Canvas fallback renders bit-identically to the GPU lane.
 *
 * Threading: single-writer by contract — [update] runs on the frame path
 * right before the adapter upload, exactly as section 4 stages it; the
 * DSP-side handoff into that path is the producer ringbuffer's own
 * synchronization, not this class's.
 *
 * @param binCount FFT bin count of the frames this texture stages;
 * defaults to the canonical 1024 of ARCHITECTURE.md section 4.
 */
class FftDataTexture(val binCount: Int = FFT_BIN_COUNT) : GpuDataTexture {

    init {
        require(binCount > 0) { "binCount must be positive (got $binCount)" }
    }

    /** Quantized magnitudes of the last staged frame (q / 255, 0.0..1.0). */
    private val magnitudes: FloatArray = FloatArray(binCount)

    /** The 1024x1 RGBA_8888 staging payload — allocated once, reused forever. */
    private val payloadBytes: ByteArray = ByteArray(binCount * BYTES_PER_TEXEL)

    /** Monotonic per-update counter; adapters skip redundant GPU uploads when unchanged. */
    private var stamp: Long = 0L

    /** Texture width in texels — one texel per FFT bin. */
    override val textureWidth: Int
        get() = binCount

    /** Texture height in texels — the single row of the 1024x1 layout. */
    override val textureHeight: Int
        get() = 1

    /** Row stride in bytes (tight packing — width x 4 bytes per texel). */
    override val rowBytes: Int
        get() = binCount * BYTES_PER_TEXEL

    /**
     * The pre-allocated RGBA_8888 staging payload — the SAME instance for
     * the lifetime of this texture. Texel k occupies
     * `payload[4k .. 4k+3]` as (R, G, B, A); see the class KDoc for the
     * full encoding contract.
     */
    override val payload: ByteArray
        get() = payloadBytes

    /** Monotonic stamp of the last [update] / [clear]; starts at 0. */
    override val frameStamp: Long
        get() = stamp

    /**
     * Stages one FFT frame into the data texture payload.
     *
     * Per bin: clamps the magnitude to [0, 1] (negative / > 1 / non-finite
     * values encode as silence or saturation — never as garbage bytes),
     * quantizes to the R channel byte, and mirrors the quantized value
     * into the CPU-side [magnitudes] mirror. Allocation-free by
     * construction: primitive arithmetic and in-place stores only.
     *
     * @param fft the DSP frame — must carry exactly [binCount] floats,
     * already normalized to 0.0..1.0 by the producer (dB/log scaling is
     * the DSP lane's job; this side only clamps defensively).
     */
    fun update(fft: FloatArray) {
        require(fft.size == binCount) {
            "FFT frame must carry exactly $binCount bins (got ${fft.size})"
        }
        var offset = 0
        for (bin in 0 until binCount) {
            val v = fft[bin]
            val clamped = if (v < 0.0f) 0.0f else if (v > 1.0f) 1.0f else v
            // NaN fails both comparisons above and quantizes to 0 (silence).
            val quantized = (clamped * 255.0f + 0.5f).toInt()
            magnitudes[bin] = quantized / 255.0f
            payloadBytes[offset] = quantized.toByte()      // R — magnitude
            payloadBytes[offset + 1] = 0                    // G — reserved
            payloadBytes[offset + 2] = 0                    // B — reserved
            payloadBytes[offset + 3] = 255.toByte()         // A — opaque
            offset += BYTES_PER_TEXEL
        }
        stamp += 1L
    }

    /**
     * CPU-side read-back of the quantized magnitude of bin `bin` from the
     * last staged frame — the Tier 3 legacy fallback lane draws from this
     * mirror and stays bit-identical to what the GPU lane sampled.
     */
    fun magnitude(bin: Int): Float {
        require(bin in 0 until binCount) { "bin index $bin out of range [0, $binCount)" }
        return magnitudes[bin]
    }

    /**
     * Stages a silence frame (every bin at 0, opaque alpha intact) without
     * touching the caller's arrays — the pause / stop state. Allocation-
     * free: in-place fills plus the stamp bump.
     */
    override fun clear() {
        magnitudes.fill(0.0f)
        var offset = 0
        for (bin in 0 until binCount) {
            payloadBytes[offset] = 0
            payloadBytes[offset + 1] = 0
            payloadBytes[offset + 2] = 0
            payloadBytes[offset + 3] = 255.toByte()
            offset += BYTES_PER_TEXEL
        }
        stamp += 1L
    }

    companion object {
        /** Canonical FFT bin count of ARCHITECTURE.md section 4. */
        const val FFT_BIN_COUNT: Int = 1024

        /** RGBA_8888 — bytes per texel. */
        const val BYTES_PER_TEXEL: Int = 4

        /** Texture width of the canonical layout (one texel per bin). */
        const val TEXTURE_WIDTH: Int = FFT_BIN_COUNT

        /** Texture height of the canonical 1024x1 layout. */
        const val TEXTURE_HEIGHT: Int = 1

        /** Total upload payload — the section 4 "< 4 KB" envelope. */
        const val PAYLOAD_BYTES: Int = FFT_BIN_COUNT * BYTES_PER_TEXEL

        /** `style` uniform: dual-harmonic waveform fluid line. */
        const val STYLE_WAVEFORM: Int = 0

        /** `style` uniform: 64-bar spectrum with accent peak caps. */
        const val STYLE_SPECTRUM_BARS: Int = 1

        /** `style` uniform: bass-weighted radial glow. */
        const val STYLE_GLOWING_AURA: Int = 2
    }
}
