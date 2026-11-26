package com.streamify.graphics.effect

import com.streamify.graphics.engine.adapter.AgslDialect
import com.streamify.graphics.governor.RenderTier

/**
 * Representation of an input uniform descriptor for a [GraphicsEffect].
 */
data class EffectUniform(
    val name: String,
    val type: UniformType,
    val description: String = "",
)

/**
 * Supported uniform types across SkSL and AGSL.
 */
enum class UniformType {
    INT,
    FLOAT,
    FLOAT2,
    FLOAT3,
    FLOAT4,
    SHADER_CHILD,
}

/**
 * Pluggable Service Provider Interface (SPI) for GPU-accelerated visual effects in the
 * Sovereign Graphics Engine.
 *
 * Each effect declares:
 *  1. Its canonical SkSL fragment shader source.
 *  2. Minimum hardware tier requirement.
 *  3. Declarative uniform metadata.
 *
 * Platform adapters (Android AGSL, Skia on Desktop/iOS/Web) consume this SPI to compile,
 * cache, and execute custom effects without modifying core engine classes.
 */
interface GraphicsEffect {
    /** Unique hierarchical identifier, e.g. "com.streamify.effect.liquid_glass". */
    val id: String

    /** Human-readable display name for logging and diagnostics. */
    val name: String

    /** Canonical SkSL shader program source. */
    val canonicalSkSl: String

    /** Minimum hardware render tier required to execute this shader program. */
    val minTier: RenderTier get() = RenderTier.Tier2_Balanced

    /** Declared uniforms expected by this effect. */
    val uniforms: List<EffectUniform>

    /**
     * Mechanical transpilation to Android AGSL dialect.
     * Default implementation utilizes [AgslDialect.transpile].
     */
    fun transpileToAgsl(): String = AgslDialect.transpile(canonicalSkSl)

    /**
     * Validates whether this effect source is compatible with the AGSL dialect.
     */
    fun validateAgslCompatibility() {
        AgslDialect.validateAgslCompatible(canonicalSkSl)
    }
}
