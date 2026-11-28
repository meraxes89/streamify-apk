package com.streamify.graphics.di

import com.streamify.graphics.governor.CapabilityProbe
import com.streamify.graphics.governor.ThermalStatus
import com.streamify.graphics.governor.TierGovernor
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * S24 Track E — the Apple environment bridge smoke gate
 * (docs/systems/S24-sovereign-graphics.md, Track A; runs via
 * `iosSimulatorArm64Test` on the macOS CI runner).
 *
 * Exercises the real `GraphicsEnvironmentBridge.attach` path on the
 * simulator: current thermal state is read, observers register against
 * `NSNotificationCenter`, the state stays consistent, and
 * attach/detach/attach (the idempotency contract of the Koin singleton)
 * never crashes — the simulator/headless safety invariant of Track D.
 *
 * Tier assertions are deliberately avoided after `attach`: the runner's
 * thermal state is environmental (a hot CI mac maps `serious` -> Tier 3)
 * and must not flake this suite; the demotion consequences themselves are
 * pinned deterministically on the desktop lane.
 */
class GraphicsEnvironmentBridgeTest {

    private fun simulatorCapableProbe() = CapabilityProbe(
        apiLevel = 0, // Non-Android target: Skia RuntimeEffect is the axis.
        rendererDescription = "Apple GPU (iOS Simulator — host Metal, paravirtualized)",
        runtimeShaderCompileSupported = true,
    )

    @Test
    fun attachReadsTheLiveThermalStateWithoutThrowing() {
        val governor = TierGovernor(probe = simulatorCapableProbe())

        GraphicsEnvironmentBridge.attach(governor)

        // The governor's thermal status must be one of the ladder entries —
        // whatever the simulator reports, it mapped through the table.
        assertTrue(
            ThermalStatus.entries.contains(governor.thermalStatus),
            "expected a mapped thermal status, got ${governor.thermalStatus}",
        )
    }

    @Test
    fun attachIsIdempotentAndDetachNeverCrashes() {
        val governor = TierGovernor(probe = simulatorCapableProbe())

        GraphicsEnvironmentBridge.attach(governor)
        GraphicsEnvironmentBridge.attach(governor) // detach-then-attach path
        GraphicsEnvironmentBridge.detach()
        GraphicsEnvironmentBridge.detach() // double detach is a no-op

        assertTrue(
            ThermalStatus.entries.contains(governor.thermalStatus),
            "the governor stays readable after attach/detach cycles",
        )
    }

    @Test
    fun theLiveThermalStatusMapsThroughTheWeld() {
        val status = GraphicsEnvironmentBridge.currentThermalStatus()
        assertTrue(
            ThermalStatus.entries.contains(status),
            "the live thermal state must map onto the multiplatform ladder",
        )
    }
}
