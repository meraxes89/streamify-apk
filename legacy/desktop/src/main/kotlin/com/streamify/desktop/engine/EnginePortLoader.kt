package com.streamify.desktop.engine

import com.streamify.shared.engine.EnginePort

/**
 * Phase 5 (MP-UI04 / Beta): reflective loader for the desktop engine
 * gateway.
 *
 * WHY REFLECTION (a deliberate, one-place trade): the desktop shell's MAIN
 * source set must stay free of `uniffi.*` references so the whole :desktop
 * module compiles on toolchain-less hosts (-Pstreamify.skipRust=true) —
 * the Phase 4b hermetic contract, preserved structurally into Phase 5. The
 * concrete gateway (com.streamify.shared.desktop.DesktopEnginePort, in
 * :shared's conditionally-attached src/desktopEngine) reaches this
 * classpath only when the binding lane is active, so a compile-time
 * reference here would break hermetic builds. This mirrors the degradation
 * philosophy DesktopJnaEngineSmokeTest has proven since Phase 4b
 * (Class.forName probe + graceful skip).
 *
 * Runtime semantics: on any failure — class absent (hermetic host), native
 * library unresolvable, engine initialization rejected — the loader
 * returns null and the shell renders the engine-unavailable pane. Never a
 * crash, never silence.
 */
object EnginePortLoader {

    private const val FACTORY_CLASS =
        "com.streamify.shared.desktop.DesktopEnginePortFactory"

    /**
     * Loads the desktop engine gateway, or null when the lane is
     * unavailable on this host. The factory itself swallows construction
     * failures; this catch-all additionally guards linkage errors (e.g.
     * JNA missing at runtime on a stripped image).
     */
    fun load(): EnginePort? = try {
        val factory = Class.forName(FACTORY_CLASS)
        @Suppress("UNCHECKED_CAST")
        val create = factory.getMethod("create")
        create.invoke(null) as? EnginePort
    } catch (_: Throwable) {
        // ClassNotFoundException (hermetic host), InvocationTargetException
        // (factory already returned null), LinkageError (no JNA / no .so):
        // every path degrades to the unavailable pane.
        null
    }
}
