package com.streamify.graphics.engine.adapter

/**
 * Pure SkSL -> AGSL dialect shim for the canonical `LiquidGlassCore`
 * source (docs/gpu/ARCHITECTURE.md section 2: "The small dialect variances
 * ... are handled inside the platform-specific `ShaderProvider`
 * implementations").
 *
 * The transform is mechanical, total and idempotent — no parsing beyond
 * targeted line-level rewrites, so the AGSL text handed to Android's
 * `RuntimeShader` on API 33+ is exactly the canonical source plus the two
 * documented deltas below. The Skia side of the equivalence is enforced in
 * CI: `AgslDialectEquivalenceTest` compiles the transpiled text through
 * `RuntimeEffect.makeForShader()` and renders it pixel-identical to the
 * canonical source.
 *
 * Deltas applied (AGSL is an SkSL dialect; only these differ for this
 * shader):
 *
 *  1. **Entry-point parameter rename** — `main(float2 coord)` becomes
 *     `main(float2 fragCoord)`, the parameter name every AGSL example in
 *     the platform documentation uses. Cosmetic, but keeps the generated
 *     text aligned with the documented entry-point contract.
 *  2. **`uniform half4` promotion** — `uniform half4 tintColor` becomes
 *     `uniform float4`. AGSL uniform setters are float-based
 *     (`RuntimeShader.setFloatUniform` binds `floatN` uniforms on every
 *     API 33 build, while early OEM AGSL implementations mishandled
 *     half-precision uniform bindings), so declaring shader uniforms as
 *     `float4` guarantees `setFloatUniform` compatibility across every
 *     API 33+ device. Skia treats the two identically, which is exactly
 *     what the pixel-equivalence gate pins.
 */
object AgslDialect {

    private val ENTRY_POINT = Regex("""half4\s+main\s*\(\s*float2\s+\w+\s*\)""")

    private val ENTRY_PARAMETER = Regex("""(half4\s+main\s*\(\s*float2\s+)(\w+)(\s*\))""")

    private val HALF4_UNIFORM = Regex("""(?m)^(\s*uniform\s+)half4(\s+)""")

    /** SkSL-only fragment coordinate — AGSL exposes it as `fragCoord`. */
    private const val SKSL_ONLY_FRAGCOORD = "sk_FragCoord"

    /** GLSL built-ins are absent from both AGSL and runtime SkSL. */
    private val GL_BUILTIN = Regex("""\bgl_[A-Za-z]\w*""")

    /**
     * Transpiles the canonical SkSL into AGSL-consumable text.
     *
     * @throws IllegalArgumentException (via [validateAgslCompatible]) when
     * the source uses SkSL-only constructs AGSL cannot compile — the
     * Android adapter catches this and degrades the lane, per the Shader
     * Compilation Failure Fallback contract.
     */
    fun transpile(canonicalSksl: String): String {
        validateAgslCompatible(canonicalSksl)
        return canonicalSksl
            .replace(HALF4_UNIFORM) { match ->
                match.groupValues[1] + "float4" + match.groupValues[2]
            }
            .replace(ENTRY_PARAMETER) { match ->
                match.groupValues[1] + "fragCoord" + match.groupValues[3]
            }
    }

    /**
     * Fail-fast lint over the SkSL subset AGSL accepts. Throws
     * [IllegalArgumentException] with a human-readable reason on the first
     * violation so the Android adapter's degradation path (not a driver
     * crash) reports the problem.
     */
    fun validateAgslCompatible(source: String) {
        require(ENTRY_POINT.containsMatchIn(source)) {
            "AGSL transpile: no `half4 main(float2 ...)` entry point found — " +
                "the canonical LiquidGlassCore source must stay intact"
        }
        require(SKSL_ONLY_FRAGCOORD !in source) {
            "AGSL transpile: `sk_FragCoord` is SkSL-only; AGSL exposes the " +
                "fragment coordinate as the `fragCoord` entry parameter"
        }
        require(!GL_BUILTIN.containsMatchIn(source)) {
            "AGSL transpile: GLSL built-in identifiers (gl_*) are absent " +
                "from both AGSL and runtime SkSL"
        }
    }
}
