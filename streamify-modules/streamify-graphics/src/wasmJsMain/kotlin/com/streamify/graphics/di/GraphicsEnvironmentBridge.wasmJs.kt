package com.streamify.graphics.di

import com.streamify.graphics.governor.TierGovernor

/**
 * wasmJsMain actual of [GraphicsEnvironmentBridge] — no-op
 * (docs/systems/S24-sovereign-graphics.md, Track A).
 *
 * The browser exposes no thermal or low-power notification surface to
 * wasm; the governor consumes frame-time P95 evidence only.
 */
actual object GraphicsEnvironmentBridge {

    actual fun attach(governor: TierGovernor) {
        // Deliberately empty — see the class KDoc.
    }

    actual fun detach() {
        // Deliberately empty — see the class KDoc.
    }
}
