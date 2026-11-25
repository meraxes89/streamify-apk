package com.streamify.graphics.di

import com.streamify.graphics.governor.TierGovernor

/**
 * desktopMain actual of [GraphicsEnvironmentBridge] — no-op
 * (docs/systems/S24-sovereign-graphics.md, Track A).
 *
 * The desktop JVM has no `NSProcessInfo`-equivalent thermal API: thermal
 * and Low Power Mode observation is an Apple-lane capability, and the
 * desktop governor consumes only frame-time P95 evidence. [attach] stays
 * cheap and side-effect free so the shared Koin wiring never pays for a
 * platform it does not have.
 */
actual object GraphicsEnvironmentBridge {

    actual fun attach(governor: TierGovernor) {
        // Deliberately empty — see the class KDoc.
    }

    actual fun detach() {
        // Deliberately empty — see the class KDoc.
    }
}
