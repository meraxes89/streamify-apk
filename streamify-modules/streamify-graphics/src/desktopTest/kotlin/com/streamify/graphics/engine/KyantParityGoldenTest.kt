package com.streamify.graphics.engine

import com.streamify.graphics.engine.adapter.LiquidGlassSource
import com.streamify.graphics.tokens.PrimitiveTokens
import com.streamify.graphics.tokens.SemanticTokens
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Kyant optical parity golden gate (S24 Track E).
 *
 * Renders the upgraded `LiquidGlassCore` through the production Skia
 * runtime-effect pipeline side-by-side with an independent,
 * straight-float Kotlin reference implementation of the five Kyant optical
 * pillars — the exact math pinned by
 * `composeApp/.../ui/component/LiquidGlassContainer.kt`:
 *
 *  1. directional 45° specular rim — `L = normalize(float2(-1, -1))`,
 *     cosine falloff on the lit boundary, 25% ambient rim opposite,
 *  2. vibrancy — luminance-preserving `1.5x` saturation before the tint,
 *  3. edge-only curvature — refraction strictly inside the `lensRadius`
 *     band, flat core elsewhere,
 *  4. the adaptive "đục đen" scrim — `lerp(0.12, 0.50, clamp((luma - 0.3) / 0.5))`,
 *  5. (the press spring & glow are facade-level; covered by the token
 *     constants they read).
 *
 * Both pipelines sample the SAME continuous gradient backdrop (the blur
 * pass cancels — Kyant samples a pre-blurred layer, and the S24 shader
 * contract documents `backdrop` as pre-blurred too), bind the SAME
 * token-derived uniforms and the SAME CPU-evaluated `darkenAmount`.
 *
 * ## Equivalence metric
 *
 * Mean Squared Error over every pixel's RGB channels (normalized 0..1)
 * must stay under [PARITY_MSE_LIMIT]. The residual sources are
 * quantization only: fp16 (`half`) intermediates inside SkSL, the 8888
 * premul store + unpremul read-back round trip, and Skia's gradient
 * dither — all bounded well below 1/255 per channel, i.e. orders of
 * magnitude below any real optical regression (a lost pillar measures
 * ~1e-2 MSE on at least one backdrop; the limit is 5e-4).
 *
 * ## Side-by-side artifacts
 *
 * [sideBySideGoldenBitmapsAreRenderedAndDumped] composes the
 * Kyant-reference | S24 | amplified-delta triptych per backdrop and — when
 * `STREAMIFY_GOLDEN_DUMP` is set (CI does this) — writes 24-bit BMPs under
 * `build/kyant-parity/` for the PR's visual comparison evidence. The
 * pre-rendered PNG twins committed under `docs/gpu/parity/` come from the
 * pure-Python port of the same model (`scripts/kyant_parity_preview.py`).
 */
class KyantParityGoldenTest {

    // ------------------------------------------------------------------
    // Parity gates — one per backdrop class
    // ------------------------------------------------------------------

    @Test
    fun lightBackdropMatchesKyantReference() {
        assertParity(BackdropClass.LIGHT)
    }

    @Test
    fun darkBackdropMatchesKyantReference() {
        assertParity(BackdropClass.DARK)
    }

    @Test
    fun saturatedBackdropMatchesKyantReference() {
        assertParity(BackdropClass.SATURATED)
    }

    // ------------------------------------------------------------------
    // The "đục đen" ramp itself — the Track B curve, pinned exactly
    // ------------------------------------------------------------------

    @Test
    fun adaptiveScrimRampMatchesTheKyantCurve() {
        // darken = lerp(0.12, 0.50, clamp((luminance - 0.3) / 0.5, 0, 1))
        assertEquals(0.12f, AdaptiveLuminanceScrim.alphaFor(0.0f), 1e-6f, "floor: black content")
        assertEquals(0.12f, AdaptiveLuminanceScrim.alphaFor(0.3f), 1e-6f, "ramp start")
        assertEquals(0.31f, AdaptiveLuminanceScrim.alphaFor(0.55f), 1e-4f, "ramp midpoint")
        assertEquals(0.50f, AdaptiveLuminanceScrim.alphaFor(0.8f), 1e-6f, "ramp saturated")
        assertEquals(0.50f, AdaptiveLuminanceScrim.alphaFor(1.0f), 1e-6f, "ceiling: white content")
    }

    // ------------------------------------------------------------------
    // Side-by-side golden bitmaps (render + optional CI dump)
    // ------------------------------------------------------------------

    @Test
    fun sideBySideGoldenBitmapsAreRenderedAndDumped() {
        for (backdropClass in BackdropClass.entries) {
            val scene = renderScene(backdropClass)
            val composite = composeSideBySide(scene)

            // The triptych really carries three rendered tiles — the middle
            // tile is the production shader output, not a blank.
            val middle = scene.s24.getColor(WIDTH / 2, HEIGHT / 2)
            assertTrue(middle != 0, "the S24 tile must carry evaluated pixels")

            if (dumpRequested()) {
                runCatching { dumpBmp(backdropClass, composite) }
                    .onFailure { println("kyant-parity: dump skipped (${it.message})") }
            }
        }
    }

    // ------------------------------------------------------------------
    // Parity machinery
    // ------------------------------------------------------------------

    /** All uniform inputs + both rendered outputs for one backdrop class. */
    private class ParityScene(
        val kyantReference: IntArray,
        val s24: Bitmap,
        val darkenAmount: Float,
        val backdropLuminance: Float,
    )

    private enum class BackdropClass(val from: FloatArray, val to: FloatArray) {
        LIGHT(floatArrayOf(0.95f, 0.95f, 0.96f, 1f), floatArrayOf(1.00f, 1.00f, 1.00f, 1f)),
        DARK(floatArrayOf(0.03f, 0.04f, 0.06f, 1f), floatArrayOf(0.08f, 0.09f, 0.12f, 1f)),
        SATURATED(floatArrayOf(0.85f, 0.12f, 0.18f, 1f), floatArrayOf(0.10f, 0.55f, 0.95f, 1f)),
    }

    private fun assertParity(backdropClass: BackdropClass) {
        val scene = renderScene(backdropClass)

        var squaredErrorSum = 0.0
        var maxChannelDelta = 0.0
        var maxAlphaDelta = 0
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val reference = scene.kyantReference[y * WIDTH + x]
                val s24 = scene.s24.getColor(x, y)
                for (channel in CHANNEL_SHIFTS) {
                    val r = (reference shr channel) and 0xFF
                    val s = (s24 shr channel) and 0xFF
                    val delta = r - s
                    squaredErrorSum += (delta * delta).toDouble()
                    if (abs(delta) > maxChannelDelta) maxChannelDelta = abs(delta).toDouble()
                }
                val alphaDelta = abs(((reference shr 24) and 0xFF) - ((s24 shr 24) and 0xFF))
                if (alphaDelta > maxAlphaDelta) maxAlphaDelta = alphaDelta
            }
        }

        val channelCount = (WIDTH * HEIGHT * CHANNEL_SHIFTS.size).toDouble()
        val mse = squaredErrorSum / (channelCount * LSB_SQUARED)
        val message = "Kyant parity on ${backdropClass.name} backdrop: MSE=$mse " +
            "(limit $PARITY_MSE_LIMIT), max channel delta=$maxChannelDelta/255 LSB, " +
            "max alpha delta=$maxAlphaDelta/255 LSB, darken=${scene.darkenAmount}, " +
            "backdropLuminance=${scene.backdropLuminance}"
        assertTrue(
            mse < PARITY_MSE_LIMIT,
            "$message — the S24 shader diverged from the Kyant optical model",
        )

        // Alpha must survive the composite within quantization noise.
        assertTrue(
            maxAlphaDelta <= ALPHA_TOLERANCE_LSB,
            "$message — alpha diverged beyond quantization noise",
        )
    }

    /** Renders both pipelines with identical inputs. */
    private fun renderScene(backdropClass: BackdropClass): ParityScene {
        // The luminance the engine would sample from the backdrop (caller
        // contract): a 17x17 average over the continuous gradient.
        val luminance = averageBackdropLuminance(backdropClass)
        val darkenAmount = AdaptiveLuminanceScrim.alphaFor(luminance)

        val s24 = renderS24(backdropShader(backdropClass), darkenAmount)
        val reference = renderKyantReference(backdropClass, darkenAmount)
        return ParityScene(reference, s24, darkenAmount, luminance)
    }

    /** The production pipeline: canonical SkSL through Skia RuntimeEffect. */
    private fun renderS24(backdrop: Shader, darkenAmount: Float): Bitmap {
        val effect = RuntimeEffect.makeForShader(LiquidGlassSource.SKSL)
        assertNotNull(effect, "LiquidGlassCore must compile on headless Skia")

        val builder = RuntimeShaderBuilder(effect)
        builder.child("backdrop", backdrop)
        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("lensRadius", PrimitiveTokens.lensCurvatureStandard)
        builder.uniform("refractionIndex", PrimitiveTokens.refractionIndexDefault)
        builder.uniform("chromaticSpread", PrimitiveTokens.chromaticSpreadDefault)
        builder.uniform(
            "tintColor",
            SemanticTokens.surfaceGlassMuted.red,
            SemanticTokens.surfaceGlassMuted.green,
            SemanticTokens.surfaceGlassMuted.blue,
            SemanticTokens.surfaceGlassMuted.alpha,
        )
        builder.uniform("vibrancy", PrimitiveTokens.vibrancySaturation)
        builder.uniform("darkenAmount", darkenAmount)
        builder.uniform("specularIntensity", PrimitiveTokens.directionalSpecularIntensity)

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = builder.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        return Bitmap.makeFromImage(surface.makeImageSnapshot())
    }

    /**
     * The independent Kyant reference: a straight-float transcription of
     * the five-pillar math, evaluated per pixel on the same continuous
     * gradient the Skia child shader samples. Returns packed ARGB pixels.
     */
    private fun renderKyantReference(backdropClass: BackdropClass, darkenAmount: Float): IntArray {
        val reference = KyantParityReference(
            width = WIDTH,
            height = HEIGHT,
            lensRadius = PrimitiveTokens.lensCurvatureStandard,
            refractionIndex = PrimitiveTokens.refractionIndexDefault,
            chromaticSpread = PrimitiveTokens.chromaticSpreadDefault,
            tintRed = SemanticTokens.surfaceGlassMuted.red,
            tintGreen = SemanticTokens.surfaceGlassMuted.green,
            tintBlue = SemanticTokens.surfaceGlassMuted.blue,
            tintAlpha = SemanticTokens.surfaceGlassMuted.alpha,
            vibrancy = PrimitiveTokens.vibrancySaturation,
            darkenAmount = darkenAmount,
            specularIntensity = PrimitiveTokens.directionalSpecularIntensity,
            backdrop = gradientSampler(backdropClass),
        )

        val pixels = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                pixels[y * WIDTH + x] = reference.argb(x, y)
            }
        }
        return pixels
    }

    /**
     * Pure-Kotlin twin of the LiquidGlassCore math — the Kyant reference
     * model. Every line mirrors one statement of the canonical SkSL so a
     * drift on either side fails the MSE gate.
     */
    private class KyantParityReference(
        private val width: Int,
        private val height: Int,
        private val lensRadius: Float,
        private val refractionIndex: Float,
        private val chromaticSpread: Float,
        private val tintRed: Float,
        private val tintGreen: Float,
        private val tintBlue: Float,
        private val tintAlpha: Float,
        private val vibrancy: Float,
        private val darkenAmount: Float,
        private val specularIntensity: Float,
        private val backdrop: (Float, Float) -> FloatArray,
    ) {
        private val invSqrt2 = 1f / sqrt(2f)

        fun argb(x: Int, y: Int): Int {
            val coordX = x + 0.5f
            val coordY = y + 0.5f

            // half4 main(float2 coord) — lens SDF, edge-only curvature.
            val px = coordX / width - 0.5f
            val py = coordY / height - 0.5f
            val d = sqrt(px * px + py * py)
            var nx = 0f
            var ny = 0f
            var nz = 1f
            if (d > 0.5f - lensRadius) {
                val e = (d - (0.5f - lensRadius)) / lensRadius
                val vx = px * e
                val vy = py * e
                val vz = 1f - e
                val inv = 1f / sqrt(vx * vx + vy * vy + vz * vz)
                nx = vx * inv
                ny = vy * inv
                nz = vz * inv
            }

            val offX = nx * (refractionIndex - 1f) * 0.05f * width
            val offY = ny * (refractionIndex - 1f) * 0.05f * height

            // 3-tap chromatic dispersion (Tier 1 semantics).
            val rC = backdrop(coordX + offX * (1f + chromaticSpread), coordY + offY * (1f + chromaticSpread))[0]
            val gC = backdrop(coordX + offX, coordY + offY)[1]
            val bC = backdrop(coordX + offX * (1f - chromaticSpread), coordY + offY * (1f - chromaticSpread))[2]
            val aC = backdrop(coordX + offX, coordY + offY)[3]

            // Vibrancy — luminance-preserving saturation before the tint.
            val luma = LUMA_R * rC + LUMA_G * gC + LUMA_B * bC
            val vr = (luma + (rC - luma) * vibrancy).coerceIn(0f, 1f)
            val vg = (luma + (gC - luma) * vibrancy).coerceIn(0f, 1f)
            val vb = (luma + (bC - luma) * vibrancy).coerceIn(0f, 1f)

            // Directional 45° specular rim: Schlick envelope x cosine falloff.
            val fresnelBase = 1f - max(nz, 0f)
            val fr = fresnelBase * fresnelBase * fresnelBase
            val invD = 1f / max(d, 1e-6f)
            val lit = (-invSqrt2 * (px * invD) - invSqrt2 * (py * invD)).coerceIn(0f, 1f)
            val rim = fr * (lit + 0.25f * (1f - lit)) * specularIntensity

            // Tint mix, then the adaptive scrim over the composite.
            val cr = vr + (tintRed - vr) * tintAlpha
            val cg = vg + (tintGreen - vg) * tintAlpha
            val cb = vb + (tintBlue - vb) * tintAlpha
            val ca = aC + (tintAlpha - aC) * tintAlpha

            val outR = (cr + rim) * (1f - darkenAmount)
            val outG = (cg + rim) * (1f - darkenAmount)
            val outB = (cb + rim) * (1f - darkenAmount)

            return (toByte(ca) shl 24) or (toByte(outR) shl 16) or (toByte(outG) shl 8) or toByte(outB)
        }

        private fun toByte(v: Float): Int = (v * 255f).roundToInt().coerceIn(0, 255)
    }

    // ------------------------------------------------------------------
    // Backdrop plumbing — the SAME continuous gradient on both sides
    // ------------------------------------------------------------------

    /** Skia-side child shader: the linear gradient (0,0)->(W,H), CLAMP. */
    private fun backdropShader(backdropClass: BackdropClass): Shader =
        Shader.makeLinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            HEIGHT.toFloat(),
            Gradient(
                Gradient.Colors(
                    arrayOf(
                        Color4f(
                            backdropClass.from[0],
                            backdropClass.from[1],
                            backdropClass.from[2],
                            backdropClass.from[3],
                        ),
                        Color4f(
                            backdropClass.to[0],
                            backdropClass.to[1],
                            backdropClass.to[2],
                            backdropClass.to[3],
                        ),
                    ),
                    null,
                    FilterTileMode.CLAMP,
                ),
            ),
        )

    /**
     * Kotlin-side twin of the same gradient: t = (x*W + y*H) / (W^2 + H^2),
     * CLAMPed to [0, 1], straight (unpremultiplied) sRGB interpolation —
     * identical to the Skia shader for opaque stops.
     */
    private fun gradientSampler(backdropClass: BackdropClass): (Float, Float) -> FloatArray {
        val from = backdropClass.from
        val to = backdropClass.to
        return { x, y ->
            val t = ((x * WIDTH + y * HEIGHT) / (WIDTH * WIDTH + HEIGHT * HEIGHT)).coerceIn(0f, 1f)
            floatArrayOf(
                from[0] + (to[0] - from[0]) * t,
                from[1] + (to[1] - from[1]) * t,
                from[2] + (to[2] - from[2]) * t,
                1f,
            )
        }
    }

    /** Caller-style luminance estimate: 17x17 mean over the gradient. */
    private fun averageBackdropLuminance(backdropClass: BackdropClass): Float {
        val sampler = gradientSampler(backdropClass)
        var sum = 0f
        var samples = 0
        for (gy in 0 until LUMA_GRID) {
            for (gx in 0 until LUMA_GRID) {
                val x = (gx + 0.5f) * WIDTH / LUMA_GRID
                val y = (gy + 0.5f) * HEIGHT / LUMA_GRID
                val c = sampler(x, y)
                sum += LUMA_R * c[0] + LUMA_G * c[1] + LUMA_B * c[2]
                samples++
            }
        }
        return sum / samples
    }

    // ------------------------------------------------------------------
    // Side-by-side composite + BMP dump (CI evidence artifacts)
    // ------------------------------------------------------------------

    /** Kyant reference | S24 | amplified delta — 3 tiles in a row. */
    private fun composeSideBySide(scene: ParityScene): IntArray {
        val composite = IntArray(WIDTH * 3 * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val reference = scene.kyantReference[y * WIDTH + x]
                val s24 = scene.s24.getColor(x, y)
                composite[y * WIDTH * 3 + x] = reference
                composite[y * WIDTH * 3 + WIDTH + x] = s24
                composite[y * WIDTH * 3 + WIDTH * 2 + x] = amplifiedDelta(reference, s24)
            }
        }
        return composite
    }

    /** |delta| x 8, clamped — near-black when the pipelines are twins. */
    private fun amplifiedDelta(reference: Int, s24: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        for (channel in CHANNEL_SHIFTS) {
            val delta = abs(((reference shr channel) and 0xFF) - ((s24 shr channel) and 0xFF))
            val amplified = (delta * DELTA_GAIN).coerceAtMost(0xFF)
            when (channel) {
                16 -> r = amplified
                8 -> g = amplified
                else -> b = amplified
            }
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun dumpRequested(): Boolean =
        System.getenv("STREAMIFY_GOLDEN_DUMP")?.let { it.isNotEmpty() && it != "0" && it != "false" } == true

    /** Minimal 24-bit BMP writer — stdlib only, never fails the test. */
    private fun dumpBmp(backdropClass: BackdropClass, argb: IntArray) {
        val width = WIDTH * 3
        val height = HEIGHT
        val rowPadded = (width * 3 + 3) and 3.inv()
        val dataSize = rowPadded * height
        val fileSize = 54 + dataSize

        val outFile = File(DUMP_DIR).apply { mkdirs() }
            .resolve("kyant_parity_${backdropClass.name.lowercase()}.bmp")

        outFile.outputStream().use { stream ->
            // OutputStream.write(Int) — every field below is a low byte.
            fun u16(v: Int) {
                stream.write(v and 0xFF)
                stream.write((v shr 8) and 0xFF)
            }
            fun u32(v: Int) {
                stream.write(v and 0xFF)
                stream.write((v shr 8) and 0xFF)
                stream.write((v shr 16) and 0xFF)
                stream.write((v shr 24) and 0xFF)
            }
            stream.write('B'.code)
            stream.write('M'.code)
            u32(fileSize)
            u32(0)
            u32(54)
            u32(40)
            u32(width)
            u32(height)
            u16(1)
            u16(24)
            u32(0)
            u32(dataSize)
            u32(2835)
            u32(2835)
            u32(0)
            u32(0)
            // BMP rows are bottom-up, BGR, each row padded to 4 bytes.
            val row = ByteArray(rowPadded)
            for (y in height - 1 downTo 0) {
                java.util.Arrays.fill(row, 0)
                for (x in 0 until width) {
                    val pixel = argb[y * width + x]
                    row[x * 3] = (pixel and 0xFF).toByte() // B
                    row[x * 3 + 1] = ((pixel shr 8) and 0xFF).toByte() // G
                    row[x * 3 + 2] = ((pixel shr 16) and 0xFF).toByte() // R
                }
                stream.write(row)
            }
        }
        println("kyant-parity: dumped ${outFile.absolutePath}")
    }

    private companion object {
        private const val WIDTH = 128
        private const val HEIGHT = 128

        /** MSE limit over normalized 0..1 RGB — see class KDoc for budget. */
        private const val PARITY_MSE_LIMIT = 5e-4

        /** Alpha tolerance in 8-bit LSB after the premul round trip. */
        private const val ALPHA_TOLERANCE_LSB = 3

        private const val DELTA_GAIN = 8
        private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
        private const val LSB_SQUARED = 255f * 255f

        private const val LUMA_R = 0.2126f
        private const val LUMA_G = 0.7152f
        private const val LUMA_B = 0.0722f
        private const val LUMA_GRID = 17

        private const val DUMP_DIR = "build/kyant-parity"
    }
}
