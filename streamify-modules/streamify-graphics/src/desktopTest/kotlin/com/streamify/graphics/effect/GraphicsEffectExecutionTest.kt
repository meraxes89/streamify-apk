package com.streamify.graphics.effect

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.streamify.graphics.api.EffectRenderRequest
import com.streamify.graphics.api.EffectUniformSlot
import com.streamify.graphics.engine.LegacyRedirectGlassEngine
import com.streamify.graphics.engine.ShaderProvider
import com.streamify.graphics.governor.RenderTier
import org.jetbrains.skia.RuntimeEffect
import kotlin.test.Test
import kotlin.test.assertTrue

class GraphicsEffectExecutionTest {

    @Test
    fun allRegisteredEffectsAreAgslAndSkiaCompatible() {
        val effects = GraphicsEffectRegistry.all()
        assertTrue(effects.isNotEmpty(), "Expected at least built-in effects in registry")
        for (effect in effects) {
            effect.validateAgslCompatibility()
            val agsl = effect.transpileToAgsl()
            assertTrue(agsl.isNotBlank(), "AGSL transpile for ${effect.id} was blank")
            RuntimeEffect.makeForShader(effect.canonicalSkSl).close()
        }
    }

    @Test
    fun applyEffectRendersCustomShaderAndDegradesSafelyOnInvalidOrMissingEffect() {
        val customId = "test.execution.gradient"
        val customEffect = object : GraphicsEffect {
            override val id: String = customId
            override val name: String = "Test Gradient"
            override val canonicalSkSl: String = """
                uniform float2 iResolution;
                uniform float uTime;
                uniform half4 uColor;
                half4 main(float2 coord) {
                    float2 uv = coord / max(iResolution, float2(1.0));
                    return half4(uv.x, uv.y, fract(uTime), uColor.a);
                }
            """.trimIndent()
            override val minTier: RenderTier = RenderTier.Tier2_Balanced
            override val uniforms: List<EffectUniform> = listOf(
                EffectUniform("iResolution", UniformType.FLOAT2),
                EffectUniform("uTime", UniformType.FLOAT),
                EffectUniform("uColor", UniformType.FLOAT4),
            )
        }

        val brokenId = "test.execution.broken"
        val brokenEffect = object : GraphicsEffect {
            override val id: String = brokenId
            override val name: String = "Broken Shader"
            override val canonicalSkSl: String = "this is not valid sksl ;;;"
            override val minTier: RenderTier = RenderTier.Tier2_Balanced
            override val uniforms: List<EffectUniform> = emptyList()
        }

        GraphicsEffectRegistry.register(customEffect)
        GraphicsEffectRegistry.register(brokenEffect)
        try {
            val bitmap = ImageBitmap(32, 32)
            val canvas = Canvas(bitmap)
            val drawScope = CanvasDrawScope()
            val engine = ShaderProvider.engineFor(RenderTier.Tier1_Ultra)

            drawScope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(32f, 32f)) {
                engine.applyEffect(
                    this,
                    EffectRenderRequest(
                        effectId = customId,
                        fallbackColor = Color.Red,
                        uniforms = listOf(
                            EffectUniformSlot.Float1("uTime") { 0.25f },
                            EffectUniformSlot.Color4("uColor") { Color.Cyan },
                        ),
                    ),
                )
                engine.applyEffect(
                    this,
                    EffectRenderRequest(
                        effectId = brokenId,
                        fallbackColor = Color.Magenta,
                    ),
                )
                engine.applyEffect(
                    this,
                    EffectRenderRequest(
                        effectId = "non.existent.effect",
                        fallbackColor = Color.Blue,
                    ),
                )
                LegacyRedirectGlassEngine.applyEffect(
                    this,
                    EffectRenderRequest(
                        effectId = customId,
                        fallbackColor = Color.Green,
                    ),
                )
            }
        } finally {
            GraphicsEffectRegistry.unregister(customId)
            GraphicsEffectRegistry.unregister(brokenId)
        }
    }
}
