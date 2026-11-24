package com.streamify.graphics.effect

import com.streamify.graphics.governor.RenderTier
import com.streamify.graphics.visualizer.VisualizerSource

/**
 * First-class [GraphicsEffect] representation of the real-time audio visualizer shader.
 */
object VisualizerEffect : GraphicsEffect {

    override val id: String = "com.streamify.effect.visualizer"

    override val name: String = "Spectrum Visualizer"

    override val canonicalSkSl: String = VisualizerSource.SKSL

    override val minTier: RenderTier = RenderTier.Tier2_Balanced

    override val uniforms: List<EffectUniform> = listOf(
        EffectUniform("uResolution", UniformType.FLOAT2, "Canvas resolution (width, height)"),
        EffectUniform("uTime", UniformType.FLOAT, "Monotonic playback time in seconds"),
        EffectUniform("uFftTexture", UniformType.SHADER_CHILD, "1024x1 FFT frequency magnitude texture"),
        EffectUniform("uBarCount", UniformType.FLOAT, "Number of visualized frequency bins"),
        EffectUniform("uBaseColor", UniformType.FLOAT4, "Bottom gradient baseline color"),
        EffectUniform("uPeakColor", UniformType.FLOAT4, "Top gradient peak color"),
    )
}
