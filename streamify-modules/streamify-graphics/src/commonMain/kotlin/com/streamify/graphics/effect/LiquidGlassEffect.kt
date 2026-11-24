package com.streamify.graphics.effect

import com.streamify.graphics.engine.adapter.LiquidGlassSource
import com.streamify.graphics.governor.RenderTier

/**
 * First-class [GraphicsEffect] representation of the canonical LiquidGlassCore shader.
 */
object LiquidGlassEffect : GraphicsEffect {

    override val id: String = "com.streamify.effect.liquid_glass"

    override val name: String = "Liquid Glass Refraction"

    override val canonicalSkSl: String = LiquidGlassSource.SKSL

    override val minTier: RenderTier = RenderTier.Tier2_Balanced

    override val uniforms: List<EffectUniform> = listOf(
        EffectUniform("backdrop", UniformType.SHADER_CHILD, "Captured blurred backdrop texture"),
        EffectUniform("resolution", UniformType.FLOAT2, "Surface resolution (width, height)"),
        EffectUniform("lensRadius", UniformType.FLOAT, "Edge curvature lens boundary"),
        EffectUniform("refractionIndex", UniformType.FLOAT, "Index of refraction (IOR)"),
        EffectUniform("chromaticSpread", UniformType.FLOAT, "Chromatic dispersion delta (Tier 1 only)"),
        EffectUniform("tintColor", UniformType.FLOAT4, "Surface tint overlay color"),
    )
}
