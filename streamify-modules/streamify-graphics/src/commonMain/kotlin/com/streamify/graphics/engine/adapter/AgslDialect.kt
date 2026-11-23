package com.streamify.graphics.engine.adapter

/**
 * Pure SkSL -> AGSL dialect shim for the canonical `LiquidGlassCore`
 * source (docs/gpu/ARCHITECTURE.md section 2: "The small dialect variances
 * ... are handled inside the platform-specific `ShaderProvider`
 * implementations").
 *
 * The transform is mechanical, total and idempotent — no parsing beyond
 * targeted line-level rewrites, so the AGSL text handed to Android's
 * `RuntimeShader` on API 33+ is exactly the canonical source plus the one
 * documented delta below. The Skia side of the equivalence is enforced in
 * CI: `AgslDialectEquivalenceTest` compiles the transpiled text through
 * `RuntimeEffect.makeForShader()` and renders it pixel-identical to the
 * canonical source.
 *
 * Delta applied (AGSL is an SkSL dialect; this is the only one this shader
 * needs):
 *
 *  1. **`uniform half4` promotion** — `uniform half4 tintColor` becomes
 *     `uniform float4`. AGSL treats `half` as a float alias and its
 *     uniform setters are float-based, and the Android documentation
 *     recommends declaring shader uniforms as `float` for exactly this
 *     reason, so the promoted declaration guarantees `setFloatUniform`
 *     compatibility on every API 33+ build. Skia treats the two
 *     identically, which is exactly what the pixel-equivalence gate pins.
 *
 * **No entry-point rewrite** — AGSL accepts any `main` parameter name; the
 * canonical `half4 main(float2 coord)` signature shape compiles as-is on
 * both dialects. Renaming the parameter (e.g. to the docs' `fragCoord`
 * convention) would force a whole-body identifier rewrite, and a
 * signature-only rename is a compile error — the first iteration of this
 * shim did exactly that and was caught by the equivalence gate, which is
 * the gate's job.
 */
object AgslDialect {

    private val ENTRY_POINT = Regex("""half4\s+main\s*\(\s*float2\s+\w+\s*\)""")

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
