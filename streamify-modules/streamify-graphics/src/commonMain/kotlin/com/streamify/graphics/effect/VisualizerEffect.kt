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
        EffectUniform("fftTexture", UniformType.SHADER_CHILD, "1024x1 FFT frequency magnitude texture"),
        EffectUniform("resolution", UniformType.FLOAT2, "Drawing surface size in pixels"),
        EffectUniform("time", UniformType.FLOAT, "Monotonic playback time in seconds"),
        EffectUniform("primaryColor", UniformType.FLOAT4, "Body color of the visualization"),
        EffectUniform("accentColor", UniformType.FLOAT4, "Peak / high-energy accent color"),
        EffectUniform("style", UniformType.INT, "0 = waveform, 1 = spectrum bars, 2 = glowing aura"),
    )
}
