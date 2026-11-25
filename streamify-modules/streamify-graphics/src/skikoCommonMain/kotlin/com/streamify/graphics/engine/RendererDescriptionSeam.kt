package com.streamify.graphics.engine

/**
 * Live renderer-description seam for the skiko targets
 * (S24 Track A: "Software Rasterizer Markers & Live Desktop Adapter
 * Probing"; docs/gpu/CI_TESTING_SPEC.md, Job 4).
 *
 * The [CapabilityProbe][com.streamify.graphics.governor.CapabilityProbe]
 * stays a pure classifier by contract — its platform adapters feed it the
 * renderer identification string. This seam is where the skiko actual of
 * [ShaderProvider] sources that string:
 *
 *  * **desktop JVM** ([RendererDescription.desktop.kt]) — layered live
 *    detection: an explicit `streamify.graphics.renderer` system property
 *    (CI / embedder injection lane), then real forced-software signals
 *    (`LIBGL_ALWAYS_SOFTWARE`, `GALLIUM_DRIVER`, `SKIKO_RENDER_API` /
 *    Skiko's `skiko.renderApi` resolving to a software GraphicsApi), then
 *    the conservative empty default. Headless or uninitialized contexts
 *    fail gracefully to the safe default without throwing.
 *  * **wasmJs / iOS** — the browser and Metal layers never expose a
 *    renderer string to application code, so these actuals return the
 *    empty conservative default.
 *
 * Returns `""` when the renderer is unknown — [CapabilityProbe] then
 * classifies purely on the compile-probe axis, exactly the pre-existing
 * conservative behavior.
 */
internal expect fun liveRendererDescription(): String
