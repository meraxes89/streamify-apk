package com.streamify.graphics.di

import com.streamify.graphics.governor.TierGovernor

/**
 * androidMain actual of [GraphicsEnvironmentBridge] — no-op
 * (docs/systems/S24-sovereign-graphics.md, Track A).
 *
 * Android thermal reporting stays with the app-layer
 * `ThermalGovernorManager` lane (legacy/app/...): the graphics module
 * deliberately keeps **zero** `android.os.PowerManager` references, so an
 * API 27 process (Vivo 1712 / Android 8.1) never class-links through the
 * bridge — the hard CI_TESTING_SPEC.md section 2 invariant. The app layer
 * forwards thermal events into [TierGovernor.onThermalStatusChanged].
 */
actual object GraphicsEnvironmentBridge {

    actual fun attach(governor: TierGovernor) {
        // Deliberately empty — see the class KDoc.
    }

    actual fun detach() {
        // Deliberately empty — see the class KDoc.
    }
}
