package com.streamify.graphics.engine

import org.jetbrains.skiko.GraphicsApi
import org.jetbrains.skiko.SkikoProperties

/**
 * Desktop JVM actual of the live renderer-description seam
 * (S24 Track A; renderer string sources listed best-first).
 *
 * The probe answers "is this adapter a software rasterizer" without ever
 * touching a GPU context itself — a contextless `glGetString` call is
 * undefined behavior (and skiko's JNI wrapper does not null-check the
 * driver's return), so the desktop lane reads only signals that are safe
 * on any thread, headless included:
 *
 *  1. **`streamify.graphics.renderer` system property** — deterministic
 *     injection lane for CI (Job 4's `LIBGL_ALWAYS_SOFTWARE` gate) and
 *     embedders: `java -Dstreamify.graphics.renderer=llvmpipe …`.
 *  2. **Forced-software environment** — `LIBGL_ALWAYS_SOFTWARE=1` and
 *     `GALLIUM_DRIVER=llvmpipe|softpipe` are exactly how Mesa ends up on a
 *     CPU rasterizer in CI and Linux VMs; `SKIKO_RENDER_API=SOFTWARE*`
 *     forces skiko's own software backend. Each maps to the matching
 *     marker string so [CapabilityProbe][com.streamify.graphics.governor.CapabilityProbe]
 *     clamps the device to Tier 4 through the same marker path as a real
 *     `GL_RENDERER` string would.
 *  3. **Skiko graphics backend properties** — `SkikoProperties.renderApi`
 *     (public skiko API, no natives touched): a resolved software
 *     GraphicsApi means the whole pipeline rasterizes on the CPU.
 *  4. **`""`** — conservative default: an unknown adapter is classified
 *     purely by the compile probe (the pre-existing behavior), and the
 *     runtime [TierGovernor][com.streamify.graphics.governor.TierGovernor]
 *     P95 demotion remains the safety net.
 *
 * Never throws: every layer is guarded, and a headless JVM short-circuits
 * before the AWT display query.
 */
internal actual fun liveRendererDescription(): String {
    // 1. Explicit override — highest priority, deterministic for tests/CI.
    System.getProperty(RENDERER_OVERRIDE_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let {
        return it
    }

    // 2. Forced-software environment (read-only, safe on any thread).
    forcedSoftwareEnvironmentMarker()?.let { return it }

    // 3. Skiko's configured graphics API — public property access, no
    //    native library load and no GPU context required.
    if (skikoConfiguredSoftwareApi()) return SKIKO_SOFTWARE_MARKER

    // 4. Unknown adapter: conservative empty default (never "software").
    return ""
}

/** Environment signals that unambiguously force CPU rasterization. */
private fun forcedSoftwareEnvironmentMarker(): String? {
    if (System.getenv("LIBGL_ALWAYS_SOFTWARE") == "1") {
        // Mesa llvmpipe lane — the exact configuration of CI Job 4.
        return "llvmpipe (env: LIBGL_ALWAYS_SOFTWARE=1)"
    }
    when (System.getenv("GALLIUM_DRIVER")?.lowercase()) {
        "llvmpipe", "softpipe" -> return "softpipe (env: GALLIUM_DRIVER)"
    }
    when (System.getenv("SKIKO_RENDER_API")?.uppercase()) {
        "SOFTWARE", "SOFTWARE_FAST", "SOFTWARE_COMPAT", "DIRECT_SOFTWARE" ->
            return SKIKO_SOFTWARE_MARKER
    }
    return null
}

/**
 * True when skiko's own backend selection resolves to a software API.
 * [SkikoProperties.renderApi] parses `SKIKO_RENDER_API` /
 * `skiko.renderApi` and falls back to the OS default (DIRECT3D on
 * Windows, OPENGL on Linux) — an invalid explicit value throws, so the
 * read is guarded. (skiko 0.150.2's GraphicsApi has no bare SOFTWARE
 * constant — its software lanes are SOFTWARE_FAST / SOFTWARE_COMPAT.)
 */
private fun skikoConfiguredSoftwareApi(): Boolean = runCatching {
    when (SkikoProperties.renderApi) {
        GraphicsApi.SOFTWARE_FAST, GraphicsApi.SOFTWARE_COMPAT -> true
        else -> false
    }
}.getOrDefault(false)

/** Marker carrying the generic `software` substring CapabilityProbe matches. */
private const val SKIKO_SOFTWARE_MARKER = "skiko software rasterizer"

/** System property name of the explicit renderer-string override lane. */
internal const val RENDERER_OVERRIDE_PROPERTY = "streamify.graphics.renderer"
