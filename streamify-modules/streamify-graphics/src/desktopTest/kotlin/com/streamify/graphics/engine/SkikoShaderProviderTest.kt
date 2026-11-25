package com.streamify.graphics.engine

import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.governor.ShaderCapability
import com.streamify.graphics.governor.TierGovernor
import com.streamify.graphics.tokens.SemanticTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color4f
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Gradient
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface

/**
 * Skiko ShaderProvider gate (Phase 2-A Track A; docs/gpu/CI_TESTING_SPEC.md
 * section 2 — the desktop half of the adapter matrix).
 *
 * Runs against the REAL skiko backend on this host (the same lane the
 * Phase 1-A DialectTranspileTest and CI's ubuntu runners exercise):
 *
 *  * **probe** — the capability axis on non-Android targets is a live Skia
 *    `RuntimeEffect` compile of the canonical source; a healthy host
 *    classifies RUNTIME_SHADER with a Tier 1 ceiling.
 *  * **tier mapping** — engineFor covers all four tiers with the ladder's
 *    engines and never throws.
 *  * **clean render** — the production program (`liquidGlassProgram`, the
 *    exact pipeline the engine holds) binds the backdrop child + all five
 *    uniforms and renders to a raster Skia canvas without error, producing
 *    spatially varying, alpha-preserving output in both the Tier 1
 *    (dispersion) and Tier 2 (single-tap) configurations.
 */
class SkikoShaderProviderTest {

    @Test
    fun probeValidatesRuntimeShaderCompilationOnThisHost() {
        val probe = ShaderProvider.probe()
        assertTrue(
            probe.runtimeShaderCompileSupported,
            "the canonical LiquidGlassCore source must compile through " +
                "RuntimeEffect.makeForShader() on a healthy skiko host",
        )
        assertEquals(ShaderCapability.RUNTIME_SHADER, probe.shaderCapability)
        assertEquals(RenderTier.Tier1_Ultra, probe.hardwareCeiling)
    }

    @Test
    fun engineForCoversEveryTierWithoutThrowing() {
        for (tier in RenderTier.entries) {
            assertNotNull(ShaderProvider.engineFor(tier), tier.toString())
        }
    }

    @Test
    fun engineForMapsTiersToTheSkikoLadderEngines() {
        assertEquals(LegacyRedirectGlassEngine, ShaderProvider.engineFor(RenderTier.Tier4_Safe))
        assertEquals(AcrylicScrimGlassEngine, ShaderProvider.engineFor(RenderTier.Tier3_Legacy))
        assertTrue(ShaderProvider.engineFor(RenderTier.Tier1_Ultra) is SkikoRuntimeShaderEngine)
        assertTrue(ShaderProvider.engineFor(RenderTier.Tier2_Balanced) is SkikoRuntimeShaderEngine)
    }

    @Test
    fun liquidGlassProgramRendersCleanlyToTheSkiaCanvasTier1Dispersion() {
        val pixels = renderThroughProgram(CHROMATIC_SPREAD_TIER1)
        assertCleanRender(pixels)
    }

    @Test
    fun liquidGlassProgramRendersCleanlyToTheSkiaCanvasTier2SingleTap() {
        val pixels = renderThroughProgram(CHROMATIC_SPREAD_TIER2)
        assertCleanRender(pixels)
    }

    // ------------------------------------------------------------------
    // S24 Track A — desktop software-rasterizer clamping matrix
    // (CI_TESTING_SPEC.md Job 4: llvmpipe / softpipe / swrast / WARP /
    // Basic Render Driver / rdpbus clamp IMMEDIATELY to Tier 4 Safe)
    // ------------------------------------------------------------------

    @Test
    fun softwareRasterizerMarkersClampImmediatelyToTier4Safe() {
        // Every renderer string that identifies a CPU rasterizer across
        // the desktop spectrum, from live driver reports to the strings
        // the CI injection lane (streamify.graphics.renderer) feeds.
        val softwareRenderers = listOf(
            "llvmpipe (LLVM 15.0.7, 256 bits)", // Mesa llvmpipe — headless CI / VMs / WSL2
            "llvmpipe (LLVM 17.0.6, 256 bits)",
            "softpipe (llvm-17.0.6)", // Mesa softpipe
            "swrast", // Mesa classic software rasterizer
            "Microsoft Basic Render Driver", // Windows driver-less / RDP display adapter
            "Microsoft Basic Render Driver (WARP)", // DXGI WARP device description
            "WARP", // Direct3D 11 CPU rasterizer
            "rdpbus", // Remote Desktop virtual display adapter
            "SwiftShader (Google)", // headless / emulator GL
            "Software Adapter v0",
        )
        for (renderer in softwareRenderers) {
            // SkSL compiles fine on CPU rasterizers — that is exactly the
            // black-hole trap: the compile axis says RUNTIME_SHADER while
            // axis 2 (raster kind) must dominate and pin Tier 4.
            val probe = CapabilityProbe(
                apiLevel = 0, // desktop lane
                rendererDescription = renderer,
                runtimeShaderCompileSupported = true,
            )
            assertTrue(probe.isSoftwareRasterizer, "renderer=\"$renderer\" must classify as a software rasterizer")
            assertEquals(ShaderCapability.FIXED_FUNCTION, probe.shaderCapability, "renderer=$renderer")
            assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling, "renderer=$renderer")

            // The clamp is immediate: the cold-start tier is pinned before
            // any frame is ever rendered, and the flat solid-tint engine
            // serves the lane.
            assertEquals(
                TierGovernor(probe).currentTier,
                RenderTier.Tier4_Safe,
                "renderer=$renderer",
            )
            assertEquals(LegacyRedirectGlassEngine, ShaderProvider.engineFor(RenderTier.Tier4_Safe))
        }
    }

    @Test
    fun hardwareRendererStringsDoNotTripTheSoftwareMarkers() {
        // Marker matching must stay a substring scan over software-raster
        // identifiers — never a vendor whitelist. Real dGPU / iGPU adapter
        // descriptions (including the marketing names the architecture
        // forbids tiering on) classify through the compile axis.
        val hardwareRenderers = listOf(
            "NVIDIA GeForce RTX 4070",
            "AMD Radeon RX 7900 XTX",
            "Intel(R) UHD Graphics 620",
            "Intel(R) Iris(R) Xe Graphics",
            "AMD Radeon(R) Graphics (Vega 8)",
            "Apple M3",
            "ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0)",
        )
        for (renderer in hardwareRenderers) {
            val probe = CapabilityProbe(
                apiLevel = 0,
                rendererDescription = renderer,
                runtimeShaderCompileSupported = true,
            )
            assertFalse(probe.isSoftwareRasterizer, "renderer=\"$renderer\" must not classify as software")
            assertEquals(ShaderCapability.RUNTIME_SHADER, probe.shaderCapability, "renderer=$renderer")
            assertEquals(RenderTier.Tier1_Ultra, probe.hardwareCeiling, "renderer=$renderer")
        }
    }

    // ------------------------------------------------------------------
    // S24 Track A — live renderer-description seam (desktop actual)
    // ------------------------------------------------------------------

    @Test
    fun rendererOverridePropertyIsHonoredByTheLiveSeam() {
        val previous = System.getProperty(RENDERER_OVERRIDE_PROPERTY)
        try {
            System.setProperty(RENDERER_OVERRIDE_PROPERTY, "llvmpipe (LLVM 15.0.7, 256 bits)")
            assertEquals("llvmpipe (LLVM 15.0.7, 256 bits)", liveRendererDescription())

            val probe = CapabilityProbe(
                apiLevel = 0,
                rendererDescription = liveRendererDescription(),
                runtimeShaderCompileSupported = true,
            )
            assertEquals(RenderTier.Tier4_Safe, probe.hardwareCeiling)
        } finally {
            if (previous == null) {
                System.clearProperty(RENDERER_OVERRIDE_PROPERTY)
            } else {
                System.setProperty(RENDERER_OVERRIDE_PROPERTY, previous)
            }
        }
    }

    @Test
    fun liveSeamFailsGracefullyWhenUnset() {
        // No override, no forced-software environment (the CI unit lane):
        // the seam must never throw, and it may only ever answer with the
        // conservative empty default or a string that genuinely classifies
        // as a software rasterizer (when the environment legitimately
        // forces one, e.g. Job 4's LIBGL_ALWAYS_SOFTWARE lane).
        val previous = System.getProperty(RENDERER_OVERRIDE_PROPERTY)
        try {
            System.clearProperty(RENDERER_OVERRIDE_PROPERTY)
            val description = liveRendererDescription()
            if (description.isNotEmpty()) {
                val probe = CapabilityProbe(
                    apiLevel = 0,
                    rendererDescription = description,
                    runtimeShaderCompileSupported = true,
                )
                assertTrue(
                    probe.isSoftwareRasterizer,
                    "a non-empty live description must carry a software marker, got \"$description\"",
                )
            }
        } finally {
            if (previous != null) System.setProperty(RENDERER_OVERRIDE_PROPERTY, previous)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Renders through the exact production pipeline: the provider's live
     * [SkikoLiquidGlassProgram] with the backdrop child shader bound and
     * all five non-child uniforms set from the token layer.
     */
    private fun renderThroughProgram(chromaticSpread: Float): Bitmap {
        val program = assertNotNull(
            liquidGlassProgram,
            "the canonical program failed to compile on this host — the lane " +
                "would degrade to the scrim engine",
        )

        program.bindBackdrop(backdropGradient())
        program.bindUniforms(
            width = WIDTH.toFloat(),
            height = HEIGHT.toFloat(),
            chromaticSpread = chromaticSpread,
            tintColorR = SemanticTokens.surfaceGlassMuted.red,
            tintColorG = SemanticTokens.surfaceGlassMuted.green,
            tintColorB = SemanticTokens.surfaceGlassMuted.blue,
            tintColorA = SemanticTokens.surfaceGlassMuted.alpha,
        )

        val surface = Surface.makeRasterN32Premul(WIDTH, HEIGHT)
        val paint = Paint().apply { shader = program.makeShader() }
        surface.canvas.drawRect(Rect.makeWH(WIDTH.toFloat(), HEIGHT.toFloat()), paint)
        return Bitmap.makeFromImage(surface.makeImageSnapshot())
    }

    /**
     * "Clean" = the render completed, the lens math is spatially varying
     * (rim refraction differs from the undisplaced center) and the alpha
     * channel survived the composite.
     */
    private fun assertCleanRender(pixels: Bitmap) {
        val center = pixels.getColor(WIDTH / 2, HEIGHT / 2)
        val rim = pixels.getColor(RIM_X, RIM_Y)
        assertTrue(center != rim, "center and rim pixels are identical — the lens math is not evaluating")
        assertTrue((center ushr 24) != 0, "alpha collapsed to zero — the composite lost its backdrop")
    }

    /** A horizontal gradient backdrop: distinct colors across x, so the
     * refraction offset shows up as a visible pixel delta. */
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
        private const val WIDTH = 256
        private const val HEIGHT = 256

        private const val CHROMATIC_SPREAD_TIER1 = 0.02f
        private const val CHROMATIC_SPREAD_TIER2 = 0.0f

        /** Pixel roughly on the diagonal rim, well past the 0.38 falloff. */
        private const val RIM_X = 12
        private const val RIM_Y = 12
    }
}
