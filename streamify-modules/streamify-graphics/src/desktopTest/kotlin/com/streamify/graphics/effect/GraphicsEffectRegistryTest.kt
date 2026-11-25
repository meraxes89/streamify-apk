package com.streamify.graphics.effect

import com.streamify.graphics.governor.RenderTier
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GraphicsEffectRegistryTest {

    @Test
    fun builtInEffectsAreRegisteredByDefault() {
        assertNotNull(GraphicsEffectRegistry.get(LiquidGlassEffect.id))
        assertNotNull(GraphicsEffectRegistry.get(VisualizerEffect.id))
        assertTrue(GraphicsEffectRegistry.contains("com.streamify.effect.liquid_glass"))
        assertTrue(GraphicsEffectRegistry.contains("com.streamify.effect.visualizer"))
    }

    @Test
    fun customEffectCanBeRegisteredAndRetrieved() {
        val customEffect = object : GraphicsEffect {
            override val id: String = "test.custom.effect"
            override val name: String = "Test Effect"
            override val canonicalSkSl: String = """
                uniform float2 uResolution;
                half4 main(float2 coord) {
                    return half4(1.0, 0.0, 0.0, 1.0);
                }
            """.trimIndent()
            override val minTier: RenderTier = RenderTier.Tier2_Balanced
            override val uniforms: List<EffectUniform> = listOf(
                EffectUniform("uResolution", UniformType.FLOAT2, "Resolution")
            )
        }

        GraphicsEffectRegistry.register(customEffect)
        try {
            val retrieved = GraphicsEffectRegistry.get("test.custom.effect")
            assertNotNull(retrieved)
            assertEquals("Test Effect", retrieved.name)
            assertEquals(1, retrieved.uniforms.size)

            // Test Skia compilation on custom effect
            val effect = RuntimeEffect.makeForShader(retrieved.canonicalSkSl)
            assertNotNull(effect)
        } finally {
            GraphicsEffectRegistry.unregister("test.custom.effect")
        }
    }

    @Test
    fun liquidGlassEffectCompilesAndTranspilesCleanly() {
        val effect = LiquidGlassEffect
        assertEquals("com.streamify.effect.liquid_glass", effect.id)
        assertEquals(6, effect.uniforms.size)

        // Skia compilation verification
        val skiaEffect = RuntimeEffect.makeForShader(effect.canonicalSkSl)
        assertNotNull(skiaEffect)

        // AGSL transpilation and validation. Whitespace-robust check: the
        // shim's `uniform half4` promotion preserves the canonical source's
        // alignment, so the promoted declaration reads `uniform float4
        // <tintColor>` with the original (multi-)spacing intact.
        val agsl = effect.transpileToAgsl()
        assertTrue(
            Regex("""uniform\s+float4\s+tintColor""").containsMatchIn(agsl),
            "the transpiled AGSL must declare tintColor as (promoted) float4",
        )
        effect.validateAgslCompatibility()
    }

    @Test
    fun visualizerEffectCompilesCleanly() {
        val effect = VisualizerEffect
        assertEquals("com.streamify.effect.visualizer", effect.id)
        assertEquals(6, effect.uniforms.size)

        // Skia compilation verification
        val skiaEffect = RuntimeEffect.makeForShader(effect.canonicalSkSl)
        assertNotNull(skiaEffect)
    }
}
