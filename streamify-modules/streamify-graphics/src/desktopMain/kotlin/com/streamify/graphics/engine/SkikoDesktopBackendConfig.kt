package com.streamify.graphics.engine

import java.awt.GraphicsEnvironment

/**
 * Desktop Skiko backend tuning for the Sovereign GPU Engine
 * (S24 Track D — Windows D3D11 safety & display refresh integration).
 *
 * Install once at JVM startup, before the first Compose window opens:
 * ```kotlin
 * fun main(args: Array<String>) {
 *     SkikoDesktopBackendConfig.install()
 *     runDesktopApp(args)
 * }
 * ```
 *
 *  1. **Windows Direct3D 11 priority** — [ensureWindowsDirect3DDefault]
 *     pins skiko to `DIRECT3D` unless the user explicitly chose an API
 *     via `skiko.renderApi` / `SKIKO_RENDER_API`. Skiko already defaults
 *     to D3D11 on Windows (`SkikoProperties.bestRenderApiForCurrentOS`),
 *     but pinning makes the default robust against inherited environment
 *     overrides, and D3D11 is the lane that bypasses the broken legacy
 *     Intel OpenGL driver stack (`ig4icd64.dll`) that produces black
 *     windows and crashes on HD 2000–3000-era parts.
 *  2. **Monitor refresh rate budgeting** — [currentDisplayRefreshRateHz]
 *     reads the default screen device's live AWT display mode; the
 *     `platformDisplayRefreshRateHz` actual feeds it into
 *     [TierGovernor.forRefreshRate][com.streamify.graphics.governor.TierGovernor.Companion.forRefreshRate]
 *     so the governor's frame budgets calibrate to the real panel
 *     (120 / 144 / 165 / 240 / 360 Hz) instead of the 60 Hz assumption.
 *
 * Every query is guarded: headless JVMs (CI) and uninitialized graphics
 * environments fall back to safe defaults instead of throwing.
 */
object SkikoDesktopBackendConfig {

    private var installed = false

    /**
     * Applies the full desktop tuning set. Idempotent and non-throwing:
     * safe to call from any `main()` regardless of OS or graphics
     * environment state.
     */
    fun install() {
        if (installed) return
        installed = true
        ensureWindowsDirect3DDefault()
    }

    /**
     * Pins skiko's Windows rendering API to Direct3D 11 (via the public
     * `skiko.renderApi` system property, consumed by
     * `SkikoProperties.renderApi`) unless the user explicitly forced an
     * API through `skiko.renderApi` or `SKIKO_RENDER_API`. Non-Windows
     * hosts are untouched.
     *
     * Property names verified against skiko 0.150.2
     * (`org.jetbrains.skiko.SkikoProperties`: `renderApi` reads the
     * `SKIKO_RENDER_API` env, then the `skiko.renderApi` system property;
     * `DIRECT3D` throws on non-Windows hosts, hence the OS guard).
     */
    fun ensureWindowsDirect3DDefault() {
        if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) return
        if (System.getProperty("skiko.renderApi") != null) return // explicit user choice
        if (System.getenv("SKIKO_RENDER_API") != null) return // explicit user choice
        System.setProperty("skiko.renderApi", "DIRECT3D")
    }

    /**
     * Live refresh rate of the default screen device (Hz). Returns 60.0 on
     * headless hosts, unknown display modes (0 / −1 is AWT's "unknown"),
     * or any AWT failure — the 60 Hz calibration is the safe floor, never
     * a crash.
     */
    fun currentDisplayRefreshRateHz(): Double = runCatching {
        if (GraphicsEnvironment.isHeadless()) return@runCatching DEFAULT_REFRESH_HZ
        val mode = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .displayMode
        val rate = mode?.refreshRate ?: 0
        if (rate <= 0) DEFAULT_REFRESH_HZ else rate.toDouble()
    }.getOrDefault(DEFAULT_REFRESH_HZ)

    private const val DEFAULT_REFRESH_HZ = 60.0
}
