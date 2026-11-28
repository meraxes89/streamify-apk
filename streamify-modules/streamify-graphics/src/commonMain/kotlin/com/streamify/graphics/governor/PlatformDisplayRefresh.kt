package com.streamify.graphics.governor

/**
 * Platform display refresh rate seam (S24 Track C — monitor refresh rate
 * dynamic budgeting).
 *
 * Feeds [TierGovernor.forRefreshRate] with the **live** refresh rate of
 * the display the engine renders to, so high-refresh panels (120 / 144 /
 * 165 / 240 / 360 Hz) get proportionally scaled frame budgets: struggling
 * iGPUs demote promptly against their real panel target instead of the
 * hardcoded 60 Hz bar, and dGPUs that sustain the tighter budget promote
 * to Tier 1 exactly as documented.
 *
 * Actuals:
 *  * **desktop JVM** — the default screen device's AWT display mode
 *    (headless / unknown → 60.0, never throws).
 *  * **Android** — 60.0 conservative default: the Android lane keeps its
 *    own display-listener wiring point; the S24 desktop-spectrum scope
 *    does not change mobile budgeting.
 *  * **wasmJs / iOS** — 60.0 conservative default.
 */
internal expect fun platformDisplayRefreshRateHz(): Double
