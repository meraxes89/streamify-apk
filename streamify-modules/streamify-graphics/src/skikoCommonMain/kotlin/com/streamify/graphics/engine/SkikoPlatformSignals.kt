package com.streamify.graphics.engine

/**
 * Skiko-target platform signal seam (desktop JVM + wasmJs + iOS shared
 * `skikoCommonMain` source set) — docs/systems/S24-sovereign-graphics.md,
 * Tracks C and D.
 *
 * These expects exist one level **below** commonMain because they are only
 * meaningful where Skia/skiko renders: the [ShaderProvider] actual uses
 * them to classify the probe environment and to calibrate the
 * TBDR-friendly dynamic resolution scaling (DRS) capture scale.
 *
 * Actuals:
 *  * **iosMain** — `UIScreen.mainScreen.scale` (Retina 2x/3x density) and
 *    an `NSProcessInfo.environment` simulator check (the iOS Simulator's
 *    GPU is the host's Metal device, paravirtualized — NOT a software
 *    rasterizer, so the string deliberately avoids the
 *    `llvmpipe`/`SwiftShader`/`Software` marker vocabulary that would pin
 *    Tier 4).
 *  * **desktopMain** — AWT `GraphicsEnvironment.isHeadless()` detection
 *    (headless CI runners render through CPU raster offscreen surfaces —
 *    again no software-raster marker: `RuntimeEffect` validates fine
 *    there, and the golden tests rely on that) and the
 *    `sun.java2d.uiScale` property for HiDPI density.
 *  * **wasmJsMain** — conservative defaults ("" / 1x) until the browser
 *    lane wires `devicePixelRatio`.
 */

/**
 * A diagnostic renderer description for the skiko [CapabilityProbe], or
 * `""` when unknown. Deliberately contains NO software-rasterizer marker
 * for environments that merely lack a display: headless JVM and the iOS
 * Simulator both still validate `RuntimeEffect` through Skia's raster
 * lane, so they stay on the full capability matrix — the
 * `llvmpipe`/`SwiftShader` markers are reserved for genuinely forced
 * software rasterization (CI_TESTING_SPEC.md Job 4).
 */
expect fun skikoRendererDescription(): String

/**
 * The display density scale of the current skiko target (2.0 / 3.0 on
 * Retina-class Apple displays, 1.0 on standard-density hosts) — the
 * second axis of [com.streamify.graphics.governor.BackdropResolutionPolicy].
 * Conservative default of 1.0 when the host cannot answer.
 */
expect fun skikoDisplayDensityScale(): Float
