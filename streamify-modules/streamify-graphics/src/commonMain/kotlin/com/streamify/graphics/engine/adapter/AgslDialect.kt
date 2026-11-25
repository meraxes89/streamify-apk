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
 *
 * **Precision contract (Track A, docs/gpu/ARCHITECTURE.md section 2a)** —
 * the canonical source keeps highp geometry (`float2/float3` normals,
 * tap offsets) and a mediump color path (`half3`/`half` taps, the `half fr`
 * Fresnel boundary, all-half composite). [validatePrecisionContract] is
 * the fail-fast gate over that contract: it lints the canonical source
 * (comment-stripped — the prose header is not code) and throws on a
 * regression so the Adreno 5xx / Mali Midgard-Bifrost throughput win
 * cannot be silently reverted by a future shader edit. It is a
 * *canonical-source contract*, deliberately outside [transpile] (which
 * synthetic lint fixtures must still be able to exercise), and it runs
 * in the Android startup probe
 * (`Agsl33Isolated.probeCompile` -> `AgslDialect.transpile` lane) and in
 * the desktop CI gates.
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

    /**
     * Track A precision-contract gate (docs/gpu/ARCHITECTURE.md section
     * 2a). The canonical source must keep its color path on `half`:
     *
     *  * the backdrop taps build a `half3` / `half` (never `float3` /
     *    `float`) — mediump loads feed the mix directly;
     *  * the highp -> mediump boundary crosses at the `half fr =` Fresnel
     *    declaration, the one deliberate precision gate;
     *  * no `float(...)` casts appear in the code (comments stripped
     *    first) — they would re-promote mediump values to 32-bit and
     *    silently cost the unified-ALU throughput win on Adreno 5xx /
     *    Mali Midgard-Bifrost.
     *
     * Throws [IllegalArgumentException] naming the regression so the
     * failure is reviewable in a CI log, not a silent visual drift.
     */
    fun validatePrecisionContract(source: String) {
        require(HALF3_TAPS in source) {
            "AGSL transpile: precision contract regression — the backdrop " +
                "taps must construct `half3 c = half3(...)` so the color " +
                "path stays mediump (docs/gpu/ARCHITECTURE.md section 2a)"
        }
        require(MEDIUMP_BOUNDARY_DECLARATION.containsMatchIn(source)) {
            "AGSL transpile: precision contract regression — the highp -> " +
                "mediump boundary must cross at the `half fr =` Fresnel " +
                "declaration (docs/gpu/ARCHITECTURE.md section 2a)"
        }
        require(!FLOAT_CAST_OF_COLOR.containsMatchIn(stripComments(source))) {
            "AGSL transpile: precision contract regression — `float(...)` " +
                "casts re-promote the mediump color path to highp and cost " +
                "the unified-ALU throughput win (docs/gpu/ARCHITECTURE.md " +
                "section 2a)"
        }
    }

    /**
     * The mediump boundary gate of the precision contract: the canonical
     * source must cross highp -> mediump at the `half fr =` Fresnel
     * declaration — the single deliberate precision gate.
     */
    private val MEDIUMP_BOUNDARY_DECLARATION = Regex("""(?m)^\s*half\s+fr\s*=""")

    /**
     * The mediump backdrop-tap construction the canonical source must
     * keep: `half3 c = half3(` — the exact text shape the lint pins.
     */
    private const val HALF3_TAPS = "half3 c = half3("

    /**
     * `float(...)` casts in the color path are the precision regression.
     * Linted only over comment-stripped text: the canonical source's
     * header prose legitimately mentions `float (highp)` in prose, and a
     * comment is not a cast.
     */
    private val FLOAT_CAST_OF_COLOR = Regex("""\bfloat\s*\(""")

    /**
     * Strips `// line comments` and `/* block comments */` so lints run
     * over code only. (The canonical source uses `//` comments only, but
     * both forms are handled for future edits.) Character-scan, no regex
     * flags — portable across every KMP target's RegexOption surface.
     */
    private fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                // line comment: skip to end of line (the newline stays)
                while (i < source.length && source[i] != '\n') i++
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                // block comment: skip to the closing */ (or end of source)
                i += 2
                while (i + 1 < source.length && !(source[i] == '*' && source[i + 1] == '/')) i++
                i = (i + 1).coerceAtMost(source.length - 1) + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
