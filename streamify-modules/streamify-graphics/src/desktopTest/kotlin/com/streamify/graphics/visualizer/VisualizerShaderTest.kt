package com.streamify.graphics.visualizer

import com.streamify.graphics.engine.adapter.AgslDialect
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Phase 3, Track A — the zero-allocation audio visualizer pipeline gate
 * (docs/gpu/ARCHITECTURE.md section 4, docs/gpu/PHASED_PLAN.md Phase 3).
 *
 * Six enforcement layers over the two Track A deliverables:
 *
 *  1. **Compile** — the canonical `VisualizerShader.sksl` transpiles into
 *     a Skia [RuntimeEffect] without a single GLSL parser or type-checker
 *     error, and a malformed source still fails (the gate cannot pass
 *     vacuously).
 *  2. **Weld** — the embedded [VisualizerSource] constant equals the
 *     canonical `.sksl` resource byte-for-byte, so the machine-consumed
 *     form can never drift from the human-reviewed source of truth (the
 *     LiquidGlassSource / AgslDialectEquivalenceTest pattern).
 *  3. **AGSL dialect** — the Phase 2 shim's `half4 -> float4` promotion
 *     keeps the source compiling AND rendering pixel-identical on the
 *     Skia stand-in for Android's AGSL lane.
 *  4. **Bindings** — all six uniforms (including the int `style` uniform
 *     and the `fftTexture` child) bind through [RuntimeShaderBuilder]
 *     without parser or layout errors, and `style` rebinds across all
 *     three lanes on a live builder.
 *  5. **Data-driven pixels** — the data-texture upload path
 *     ([FftDataTexture.payload] -> `Bitmap.installPixels` -> image
 *     shader -> `child("fftTexture", ...)`) is exercised end-to-end on a
 *     headless raster surface: a single impulse bin raises exactly its
 *     spectrum bar, content changes propagate to every style, and the
 *     `style` uniform selects three visually distinct synthesizations.
 *  6. **FftDataTexture invariants** — the section 4 budget pins: the
 *     4096-byte (1024x1 RGBA_8888) payload shape, the exact magnitude
 *     encoding contract, a sub-5-microsecond median stage cost, and zero
 *     per-frame heap allocation on the audio render path (measured with
 *     the HotSpot per-thread allocation counter).
 */
class VisualizerShaderTest {

    // ------------------------------------------------------------------
    // 1. Compile — the canonical source transpiles into a Skia runtime
    //    effect. A returned live native handle IS the compile-success
    //    signal (same contract as the Phase 1-A DialectTranspileTest).
    // ------------------------------------------------------------------

    @Test
    fun visualizerShaderCompilesIntoSkiaRuntimeEffect() {
        val effect = RuntimeEffect.makeForShader(loadCanonicalShader())
        RuntimeShaderBuilder(effect)
    }

    // Negative control: the gate really does fail on invalid SkSL.
    @Test
    fun invalidSkslFailsCompilationInsteadOfPassingThrough() {
        assertFailsWith<RuntimeException> {
            RuntimeEffect.makeForShader(
                "uniform shader fftTexture; half4 main(float2 c) { return oops; }",
            )
        }
    }

    // ------------------------------------------------------------------
    // 2. Weld — embedded constant == canonical resource
    // ------------------------------------------------------------------

    @Test
    fun embeddedSourceIsWeldedToTheCanonicalSkslFile() {
        assertEquals(VisualizerSource.SKSL.trim(), loadCanonicalShader().trim())
    }

    // ------------------------------------------------------------------
    // 3. AGSL dialect — the half4 color uniforms promote to float4 and
    //    the result stays both compilable and a semantic no-op.
    // ------------------------------------------------------------------

    @Test
    fun agslDialectTranspileOfTheVisualizerStillCompiles() {
        RuntimeEffect.makeForShader(AgslDialect.transpile(VisualizerSource.SKSL))
    }

    @Test
    fun agslTranspiledSourceRendersPixelEquivalentToCanonical() {
        val frame = impulseFrame()
        val canonical = sampleGrid(render(VisualizerSource.SKSL, FftDataTexture.STYLE_SPECTRUM_BARS, frame))
        val transpiled =
            sampleGrid(render(AgslDialect.transpile(VisualizerSource.SKSL), FftDataTexture.STYLE_SPECTRUM_BARS, frame))

        assertEquals(canonical.size, transpiled.size)
        for (i in canonical.indices) {
            assertEquals(
                canonical[i],
                transpiled[i],
                "sample #$i diverges between the canonical and AGSL-dialect texts " +
                    "— the dialect delta is no longer a semantic no-op for the visualizer",
            )
        }
    }

    // ------------------------------------------------------------------
    // 4. Uniform & child bindings — all six uniforms accept values with
    //    no parser or layout errors; the int `style` uniform rebinds
    //    across all three lanes on the same live builder (the rebind
    //    shape the Phase 1 tier-toggle test established).
    // ------------------------------------------------------------------

    @Test
    fun uniformsAndFftChildBindWithoutParserErrors() {
        val effect = RuntimeEffect.makeForShader(loadCanonicalShader())
        val builder = RuntimeShaderBuilder(effect)

        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("time", 0.0f)
        builder.uniform("primaryColor", PRIMARY_R, PRIMARY_G, PRIMARY_B, PRIMARY_A)
        builder.uniform("accentColor", ACCENT_R, ACCENT_G, ACCENT_B, ACCENT_A)
        builder.uniform("style", FftDataTexture.STYLE_WAVEFORM)
        builder.child("fftTexture", fftTextureShader(FftDataTexture()))

        // makeShader() runs the final uniform-layout validation pass.
        builder.makeShader()

        builder.uniform("style", FftDataTexture.STYLE_SPECTRUM_BARS)
        builder.makeShader()
        builder.uniform("style", FftDataTexture.STYLE_GLOWING_AURA)
        builder.makeShader()
    }

    // ------------------------------------------------------------------
    // 5. Data-driven pixels — the render path is the exact skiko lane a
    //    production adapter will use: FftDataTexture payload ->
    //    Bitmap.installPixels -> CLAMP/CLAMP+LINEAR image shader ->
    //    RuntimeShaderBuilder child -> raster surface.
    // ------------------------------------------------------------------

    /**
     * The airtight data-flow gate: bin 8 is bar 0's exact center bin
     * (bar column 0 samples bin (0 + 0.5) * 16 = 8), so a lone impulse
     * there must raise bar 0 to full height while its neighbors stay at
     * the 3% silence floor — and a silent frame must flatten it back.
     */
    @Test
    fun spectrumBarsRenderFromActualDataTextureContent() {
        val loud = render(VisualizerSource.SKSL, FftDataTexture.STYLE_SPECTRUM_BARS, impulseFrame())
        val silent = render(VisualizerSource.SKSL, FftDataTexture.STYLE_SPECTRUM_BARS, silenceFrame())

        val loudBar0 = loud.getColor(PROBE_BAR0_X, PROBE_Y)
        val loudBar1 = loud.getColor(PROBE_BAR1_X, PROBE_Y)
        val silentBar0 = silent.getColor(PROBE_BAR0_X, PROBE_Y)
        val silentBar1 = silent.getColor(PROBE_BAR1_X, PROBE_Y)

        assertTrue(
            loudBar0 != loudBar1,
            "bar 0 (impulse bin $IMPULSE_BIN = 1.0) did not rise above its silent neighbor " +
                "— the fragment is not reading the data texture",
        )
        assertEquals(
            silentBar0,
            silentBar1,
            "silent-frame bars differ — the render is not purely data-driven",
        )
        assertTrue(
            loudBar0 != silentBar0,
            "the impulse bin changed nothing — texture content does not reach the fragment",
        )
    }

    /** Styles 0 and 2 must also react to texture content (aggregate gate). */
    @Test
    fun dataTextureChangesPropagateToTheWaveformAndAura() {
        val loudFrame = FloatArray(FftDataTexture.FFT_BIN_COUNT) { 1.0f }
        for (style in intArrayOf(FftDataTexture.STYLE_WAVEFORM, FftDataTexture.STYLE_GLOWING_AURA)) {
            val loudPixels = sampleGrid(render(VisualizerSource.SKSL, style, loudFrame))
            val silentPixels = sampleGrid(render(VisualizerSource.SKSL, style, silenceFrame()))
            assertTrue(
                countDiffs(loudPixels, silentPixels) >= MIN_DIFFERING_SAMPLES,
                "style $style output did not react to FFT content — " +
                    "the data texture is not reaching the fragment",
            )
        }
    }

    /** The int `style` uniform selects three distinct synthesizations. */
    @Test
    fun styleUniformSelectsDistinctVisualizations() {
        val loudFrame = FloatArray(FftDataTexture.FFT_BIN_COUNT) { 1.0f }
        val waveform = sampleGrid(render(VisualizerSource.SKSL, FftDataTexture.STYLE_WAVEFORM, loudFrame))
        val bars = sampleGrid(render(VisualizerSource.SKSL, FftDataTexture.STYLE_SPECTRUM_BARS, loudFrame))
        val aura = sampleGrid(render(VisualizerSource.SKSL, FftDataTexture.STYLE_GLOWING_AURA, loudFrame))

        assertTrue(
            countDiffs(waveform, bars) >= MIN_DIFFERING_SAMPLES,
            "styles 0 and 1 render the same output — the style branch is not evaluating",
        )
        assertTrue(
            countDiffs(bars, aura) >= MIN_DIFFERING_SAMPLES,
            "styles 1 and 2 render the same output — the style branch is not evaluating",
        )
    }

    /** Every style branch executes cleanly: spatially varying, alpha intact. */
    @Test
    fun allThreeStylesRenderSpatiallyVaryingCleanOutput() {
        val frame = FloatArray(FftDataTexture.FFT_BIN_COUNT) { (it % 32) / 31.0f }
        for (style in intArrayOf(
                FftDataTexture.STYLE_WAVEFORM,
                FftDataTexture.STYLE_SPECTRUM_BARS,
                FftDataTexture.STYLE_GLOWING_AURA,
            )) {
            val pixels = render(VisualizerSource.SKSL, style, frame, time = 1.0f)
            val quarter = pixels.getColor(WIDTH / 4, HEIGHT / 4)
            val threeQuarter = pixels.getColor(WIDTH * 3 / 4, HEIGHT / 4)
            val center = pixels.getColor(WIDTH / 2, HEIGHT / 2)

            assertTrue(
                quarter != threeQuarter || quarter != center,
                "style $style renders flat output — the fragment math is not evaluating",
            )
            assertTrue(
                (center ushr 24) != 0,
                "style $style alpha collapsed to zero — the composite is not clean",
            )
        }
    }

    // ------------------------------------------------------------------
    // 6. FftDataTexture invariants — the section 4 budgets, pinned.
    // ------------------------------------------------------------------

    /** The 1024x1 RGBA_8888 staging shape: exactly 4096 bytes, tight rows. */
    @Test
    fun fftDataTextureStagesTheCanonicalPayloadShape() {
        val texture = FftDataTexture(FftDataTexture.FFT_BIN_COUNT)

        assertEquals(1024, texture.binCount)
        assertEquals(FftDataTexture.TEXTURE_WIDTH, texture.textureWidth)
        assertEquals(FftDataTexture.TEXTURE_HEIGHT, texture.textureHeight)
        assertEquals(FftDataTexture.PAYLOAD_BYTES, texture.payload.size)
        assertEquals(FftDataTexture.PAYLOAD_BYTES, texture.rowBytes)
        assertEquals(0L, texture.frameStamp)

        texture.update(FloatArray(FftDataTexture.FFT_BIN_COUNT))
        assertEquals(1L, texture.frameStamp)
    }

    /** The encoding contract: texel = (R = magnitude, G = 0, B = 0, A = 255). */
    @Test
    fun fftDataTextureEncodesMagnitudesIntoThePayloadContract() {
        val texture = FftDataTexture()
        val frame = FloatArray(FftDataTexture.FFT_BIN_COUNT)
        frame[0] = 0.0f        // silence         -> R = 0
        frame[1] = 1.0f        // full scale      -> R = 255
        frame[2] = 0.5f        // mid scale       -> R = 128
        frame[3] = -0.7f       // negative clamp  -> R = 0
        frame[4] = 2.3f        // overflow clamp  -> R = 255
        frame[5] = Float.NaN   // non-finite      -> R = 0 (silence)
        texture.update(frame)

        // Texel 0 (bin 0): R=0, G=0, B=0, A=255.
        assertEquals(0, texture.payload[0].toInt() and 0xFF)
        assertEquals(0, texture.payload[1].toInt() and 0xFF)
        assertEquals(0, texture.payload[2].toInt() and 0xFF)
        assertEquals(255, texture.payload[3].toInt() and 0xFF)
        // Texels 1..5 (bins 1..5): R carries the clamped quantized value.
        assertEquals(255, texture.payload[4].toInt() and 0xFF)
        assertEquals(128, texture.payload[8].toInt() and 0xFF)
        assertEquals(0, texture.payload[12].toInt() and 0xFF)
        assertEquals(255, texture.payload[16].toInt() and 0xFF)
        assertEquals(0, texture.payload[20].toInt() and 0xFF)
        // G/B stay reserved-zero and A stays opaque on a live bin too.
        assertEquals(0, texture.payload[5].toInt() and 0xFF)
        assertEquals(255, texture.payload[7].toInt() and 0xFF)
        // The CPU mirror round-trips exactly what the GPU lane sampled.
        assertEquals(0.0f, texture.magnitude(0))
        assertEquals(1.0f, texture.magnitude(1))
        assertEquals(128.0f / 255.0f, texture.magnitude(2))
        assertEquals(0.0f, texture.magnitude(5))
        // clear() stages silence with the alpha contract intact.
        texture.clear()
        assertEquals(0, texture.payload[0].toInt() and 0xFF)
        assertEquals(255, texture.payload[3].toInt() and 0xFF)
        assertEquals(0.0f, texture.magnitude(1))
    }

    /**
     * The sub-5-microsecond stage budget of ARCHITECTURE.md section 4,
     * median-gated (the median ignores CI runner jitter; the loop is the
     * entire per-frame CPU work on the audio render path).
     */
    @Test
    fun fftDataTextureStageRunsUnderTheFiveMicrosecondBudget() {
        val texture = FftDataTexture()
        val frame = FloatArray(FftDataTexture.FFT_BIN_COUNT) { (it % 64) / 63.0f }

        repeat(TIMING_WARMUP_FRAMES) { texture.update(frame) }
        val samples = LongArray(TIMING_SAMPLE_FRAMES)
        for (i in samples.indices) {
            val start = System.nanoTime()
            texture.update(frame)
            samples[i] = System.nanoTime() - start
        }
        samples.sort()
        val medianNanos = samples[samples.size / 2]

        assertTrue(
            medianNanos < FIVE_MICROSECONDS_NANOS,
            "median stage time ${medianNanos}ns exceeds the 5us budget of " +
                "ARCHITECTURE.md section 4 (the audio render path's entire per-frame CPU cost)",
        )
    }

    /**
     * The zero-allocation invariant: [FftDataTexture.update] runs at ZERO
     * heap allocation per frame, measured with the HotSpot per-thread
     * allocation counter (`com.sun.management.ThreadMXBean`) — an empty
     * baseline loop of identical shape is subtracted so only the update
     * path is measured. The 64-byte tolerance covers measurement
     * scaffolding only: any real per-frame leak at [ALLOCATION_SAMPLE_FRAMES]
     * frames would dwarf it by orders of magnitude. Non-HotSpot hosts
     * pass through (CI pins temurin 21, CI_TESTING_SPEC section 3).
     */
    @Test
    fun fftDataTextureUpdateRunsAtZeroPerFrameHeapAllocation() {
        val hotspotBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            ?: return // allocation counters unavailable on this JVM — the CI lane measures
        if (!hotspotBean.isThreadAllocatedMemorySupported) return
        if (!hotspotBean.isThreadAllocatedMemoryEnabled) {
            hotspotBean.setThreadAllocatedMemoryEnabled(true)
        }

        val texture = FftDataTexture()
        val frame = FloatArray(FftDataTexture.FFT_BIN_COUNT)
        repeat(ALLOCATION_WARMUP_FRAMES) { texture.update(frame) }

        val threadId = Thread.currentThread().threadId()

        // Baseline: the empty measurement loop itself.
        val baselineStart = hotspotBean.getThreadAllocatedBytes(threadId)
        for (i in 0 until ALLOCATION_SAMPLE_FRAMES) {
        }
        val baselineEnd = hotspotBean.getThreadAllocatedBytes(threadId)

        // Measured: the same loop shape with one update() per iteration.
        val measuredStart = hotspotBean.getThreadAllocatedBytes(threadId)
        for (i in 0 until ALLOCATION_SAMPLE_FRAMES) {
            texture.update(frame)
        }
        val measuredEnd = hotspotBean.getThreadAllocatedBytes(threadId)

        val baselineAllocated = baselineEnd - baselineStart
        val measuredAllocated = measuredEnd - measuredStart
        assertTrue(
            measuredAllocated <= baselineAllocated + ALLOCATION_TOLERANCE_BYTES,
            "update() allocated ${measuredAllocated - baselineAllocated} bytes across " +
                "$ALLOCATION_SAMPLE_FRAMES frames — the audio render path must be allocation-" +
                "free (reusable pre-allocated buffers, ARCHITECTURE.md section 4)",
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Loads the canonical shader source from the module resources — the
     * same classpath + working-directory fallback the Phase 1-A
     * DialectTranspileTest uses.
     */
    private fun loadCanonicalShader(): String {
        val resource = javaClass.classLoader.getResource(SHADER_RESOURCE)
        if (resource != null) return resource.readText()

        val fallback = java.io.File("src/commonMain/resources/$SHADER_RESOURCE")
        check(fallback.isFile) {
            "$SHADER_RESOURCE not found on the classpath or at ${fallback.absolutePath}"
        }
        return fallback.readText()
    }

    /**
     * Binds a [FftDataTexture]'s staged payload as the shader's
     * `fftTexture` child — the exact skiko upload lane a production
     * adapter holds: explicit RGBA_8888 (channel semantics stay
     * platform-independent of the host's N32 order), no color space
     * (raw data), CLAMP/CLAMP tiling, LINEAR sampling so continuous
     * bin-axis reads interpolate neighboring bins.
     */
    private fun fftTextureShader(texture: FftDataTexture): Shader {
        val info = ImageInfo(
            texture.textureWidth,
            texture.textureHeight,
            ColorType.RGBA_8888,
            ColorAlphaType.OPAQUE,
        )
        val bitmap = Bitmap()
        check(bitmap.installPixels(info, texture.payload, texture.rowBytes)) {
            "installPixels rejected the ${texture.textureWidth}x${texture.textureHeight} " +
                "RGBA_8888 data texture payload"
        }
        return bitmap.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null)
    }

    /**
     * Renders `source` at the given style with the FFT frame staged
     * through a real [FftDataTexture] — compile, bind, upload and raster
     * in one pass, on the headless surface the CI gates use.
     */
    private fun render(source: String, style: Int, fft: FloatArray, time: Float = 0.0f): Bitmap {
        val texture = FftDataTexture()
        texture.update(fft)

        val effect = RuntimeEffect.makeForShader(source)
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("time", time)
        builder.uniform("primaryColor", PRIMARY_R, PRIMARY_G, PRIMARY_B, PRIMARY_A)
        builder.uniform("accentColor", ACCENT_R, ACCENT_G, ACCENT_B, ACCENT_A)
        builder.uniform("style", style)
        builder.child("fftTexture", fftTextureShader(texture))

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = builder.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        return Bitmap.makeFromImage(surface.makeImageSnapshot())
    }

    /**
     * Stride-sampled pixel grid (65x65 = 4225 samples covering corners,
     * center and interior) — the AgslDialectEquivalenceTest sampling
     * shape: airtight for a regression gate, trivial JNI cost.
     */
    private fun sampleGrid(pixels: Bitmap): IntArray {
        val samples = IntArray(SAMPLE_GRID * SAMPLE_GRID)
        var i = 0
        for (y in 0 until SAMPLE_GRID) {
            for (x in 0 until SAMPLE_GRID) {
                samples[i++] = pixels.getColor(
                    x * (WIDTH - 1) / (SAMPLE_GRID - 1),
                    y * (HEIGHT - 1) / (SAMPLE_GRID - 1),
                )
            }
        }
        return samples
    }

    private fun countDiffs(a: IntArray, b: IntArray): Int {
        var diffs = 0
        for (i in a.indices) {
            if (a[i] != b[i]) diffs++
        }
        return diffs
    }

    /** A lone full-scale impulse at bin 8 — bar 0's exact center bin. */
    private fun impulseFrame(): FloatArray =
        FloatArray(FftDataTexture.FFT_BIN_COUNT).also { it[IMPULSE_BIN] = 1.0f }

    private fun silenceFrame(): FloatArray = FloatArray(FftDataTexture.FFT_BIN_COUNT)

    private companion object {
        private const val SHADER_RESOURCE = "shaders/VisualizerShader.sksl"

        private const val WIDTH = 256
        private const val HEIGHT = 256

        /** Sample grid stride (65x65 samples over the 256x256 surface). */
        private const val SAMPLE_GRID = 65

        /**
         * Aggregate-diff floor: loud-vs-silent and cross-style renders must
         * differ at no fewer samples than this (empirically they differ at
         * hundreds; 8 keeps the gate robust to rasterizer AA nuance).
         */
        private const val MIN_DIFFERING_SAMPLES = 8

        /** Bar 0's center bin: (column + 0.5) * (1024 / 64) = 8. */
        private const val IMPULSE_BIN = 8

        /** Pixel probe at bar 0's horizontal center (x = WIDTH * 0.5 / 64). */
        private const val PROBE_BAR0_X = WIDTH / 128

        /** Pixel probe at bar 1's horizontal center (x = WIDTH * 1.5 / 64). */
        private const val PROBE_BAR1_X = WIDTH * 3 / 128

        /** Mid-height probe: inside a full 0.95 bar, far above a 0.03 floor. */
        private const val PROBE_Y = HEIGHT / 2

        private const val PRIMARY_R = 0.08f
        private const val PRIMARY_G = 0.78f
        private const val PRIMARY_B = 0.55f
        private const val PRIMARY_A = 1.00f

        private const val ACCENT_R = 0.95f
        private const val ACCENT_G = 0.32f
        private const val ACCENT_B = 0.85f
        private const val ACCENT_A = 1.00f

        private const val TIMING_WARMUP_FRAMES = 4096
        private const val TIMING_SAMPLE_FRAMES = 4096
        private const val FIVE_MICROSECONDS_NANOS = 5_000L

        private const val ALLOCATION_WARMUP_FRAMES = 2048
        private const val ALLOCATION_SAMPLE_FRAMES = 2048

        /** Measurement-scaffolding allowance — never a per-frame budget. */
        private const val ALLOCATION_TOLERANCE_BYTES = 64L
    }
}
