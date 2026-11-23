package com.streamify.graphics.engine.adapter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * Equivalence gate for the AGSL dialect shim (Phase 2-A Track A;
 * docs/gpu/ARCHITECTURE.md section 2 — "the small dialect variances ... are
 * handled inside the platform-specific ShaderProvider implementations").
 *
 * Three layers of enforcement:
 *  1. **Weld** — the embedded [LiquidGlassSource] constant must equal the
 *     canonical `.sksl` resource byte-for-byte, so the machine-consumed
 *     form can never drift from the human-reviewed source of truth.
 *  2. **Compile** — the transpiled AGSL text must still compile through
 *     Skia `RuntimeEffect.makeForShader()` (AGSL is an SkSL dialect; the
 *     Skia compiler is the machine-checkable stand-in for the AGSL one).
 *  3. **Pixels** — the transpiled text must render sample-for-sample
 *     identical to the canonical source on a raster surface, in both the
 *     Tier 1 (3-tap dispersion) and Tier 2 (single-tap) configurations —
 *     the dialect deltas are provably semantic no-ops.
 *
 * Plus unit cases for the transpiler mechanics (rename, promotion,
 * idempotence) and the fail-fast lint (SkSL-only constructs rejected with
 * `IllegalArgumentException` — the Android adapter's degradation path).
 */
class AgslDialectEquivalenceTest {

    // ------------------------------------------------------------------
    // 1. Weld — embedded constant == canonical resource
    // ------------------------------------------------------------------

    @Test
    fun embeddedSourceIsWeldedToTheCanonicalSkslFile() {
        assertEquals(LiquidGlassSource.SKSL.trim(), loadCanonicalShader().trim())
    }

    // ------------------------------------------------------------------
    // 2. Compile — the AGSL text stays a valid Skia runtime effect
    // ------------------------------------------------------------------

    @Test
    fun transpiledSourceStillCompilesAsSkiaRuntimeEffect() {
        RuntimeEffect.makeForShader(AgslDialect.transpile(LiquidGlassSource.SKSL))
    }

    // ------------------------------------------------------------------
    // 3. Pixels — semantic equivalence on the raster backend
    // ------------------------------------------------------------------

    @Test
    fun transpiledSourceRendersPixelEquivalentToTheCanonicalSource() {
        val canonical = render(LiquidGlassSource.SKSL, CHROMATIC_SPREAD_TIER1)
        val transpiled = render(AgslDialect.transpile(LiquidGlassSource.SKSL), CHROMATIC_SPREAD_TIER1)
        assertPixelEquivalent(canonical, transpiled)
    }

    @Test
    fun tier2SingleTapTranspileIsAlsoPixelEquivalent() {
        val canonical = render(LiquidGlassSource.SKSL, CHROMATIC_SPREAD_TIER2)
        val transpiled = render(AgslDialect.transpile(LiquidGlassSource.SKSL), CHROMATIC_SPREAD_TIER2)
        assertPixelEquivalent(canonical, transpiled)
    }

    // ------------------------------------------------------------------
    // Transpiler mechanics
    // ------------------------------------------------------------------

    @Test
    fun transpileIsIdempotent() {
        val once = AgslDialect.transpile(LiquidGlassSource.SKSL)
        assertEquals(once, AgslDialect.transpile(once))
    }

    @Test
    fun entryParameterIsRenamedToFragCoord() {
        val transpiled = AgslDialect.transpile(LiquidGlassSource.SKSL)
        assertTrue("half4 main(float2 fragCoord)" in transpiled)
        assertTrue("float2 coord)" !in transpiled)
    }

    @Test
    fun half4UniformIsPromotedToFloat4() {
        val transpiled = AgslDialect.transpile(LiquidGlassSource.SKSL)
        assertTrue("uniform float4  tintColor;" in transpiled)
        assertTrue("uniform half4" !in transpiled)
    }

    // ------------------------------------------------------------------
    // Fail-fast lint (degradation path, CI_TESTING_SPEC Job 5 shape)
    // ------------------------------------------------------------------

    @Test
    fun skslOnlyFragCoordIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AgslDialect.transpile("half4 main(float2 c) { return sk_FragCoord; }")
        }
    }

    @Test
    fun glBuiltinIdentifiersAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            AgslDialect.transpile("half4 main(float2 c) { return gl_FragCoord; }")
        }
    }

    @Test
    fun missingEntryPointIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AgslDialect.transpile("half4 notMain(float2 c) { return half4(0.0); }")
        }
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
     * Renders `source` with the given dispersion setting and returns a
     * stride-sampled pixel grid (65x65 = 4225 samples covering corners,
     * center, rim and interior) — airtight for a regression gate while
     * keeping the JNI cost trivial.
     */
    private fun render(source: String, chromaticSpread: Float): IntArray {
        val effect = RuntimeEffect.makeForShader(source)
        val builder = RuntimeShaderBuilder(effect)

        builder.uniform("resolution", WIDTH.toFloat(), HEIGHT.toFloat())
        builder.uniform("lensRadius", LENS_RADIUS)
        builder.uniform("refractionIndex", REFRACTIVE_INDEX)
        builder.uniform("chromaticSpread", chromaticSpread)
        builder.uniform("tintColor", TINT_R, TINT_G, TINT_B, TINT_A)
        builder.child("backdrop", backdropGradient())

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = builder.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        val pixels = Bitmap.makeFromImage(surface.makeImageSnapshot())

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

    private fun assertPixelEquivalent(canonical: IntArray, transpiled: IntArray) {
        assertEquals(canonical.size, transpiled.size)
        for (i in canonical.indices) {
            assertEquals(
                canonical[i],
                transpiled[i],
                "sample #$i diverges between the canonical and AGSL-dialect texts " +
                    "— the dialect delta is no longer a semantic no-op",
            )
        }
    }

    /** A horizontal gradient backdrop: distinct colors across x, so any
     * divergence in the refraction math shows up as a pixel delta. */
    private fun backdropGradient(): Shader =
        Shader.makeLinearGradient(
            0f,
            0f,
            WIDTH.toFloat(),
            0f,
            Gradient(
                Gradient.Colors(
                    arrayOf(
                        Color4f(0.05f, 0.25f, 0.15f, 1.0f),
                        Color4f(0.55f, 0.15f, 0.35f, 1.0f),
                    ),
                    null,
                    FilterTileMode.CLAMP,
                ),
            ),
        )

    private companion object {
        private const val SHADER_RESOURCE = "shaders/LiquidGlassCore.sksl"

        private const val WIDTH = 256
        private const val HEIGHT = 256

        /** Sample grid stride (65x65 samples over the 256x256 surface). */
        private const val SAMPLE_GRID = 65

        /** Curvature boundary: the rim branch engages at d > 0.5 - 0.12 = 0.38. */
        private const val LENS_RADIUS = 0.12f
        private const val REFRACTIVE_INDEX = 1.25f
        private const val CHROMATIC_SPREAD_TIER1 = 0.02f
        private const val CHROMATIC_SPREAD_TIER2 = 0.0f

        private const val TINT_R = 0.95f
        private const val TINT_G = 0.97f
        private const val TINT_B = 1.00f
        private const val TINT_A = 0.08f
    }
}
