package com.streamify.app.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * GESTURE SPRING SOLVER TESTS (pure JVM)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The Kotlin-side implementation of the directive's RustGesturePhysics
 * bridge (rust/ is frozen-ABI for this phase and exposes no gesture JNI
 * symbol). These tests pin the snap-decision policy (position bias +
 * velocity lookahead + fling commit) and verify the mass-spring-damper
 * integrator itself: stability at display timesteps, convergence to the
 * target, zero overshoot for the critically-damped fling config, and sane
 * settle-duration estimates.
 */
class GestureSpringSolverTest {

    private val eps = 1e-3f

    // ── Snap decision policy ───────────────────────────────────────────────

    @Test
    fun `upward fling commits to expanded regardless of position`() {
        val plan = GestureSpringSolver.solveSnap(progress = 0.1f, velocityPerSec = 1.5f)
        assertEquals(1f, plan.target, 0f)
    }

    @Test
    fun `downward fling commits to collapsed regardless of position`() {
        val plan = GestureSpringSolver.solveSnap(progress = 0.9f, velocityPerSec = -1.5f)
        assertEquals(0f, plan.target, 0f)
    }

    @Test
    fun `still release uses the position bias threshold`() {
        assertEquals(1f, GestureSpringSolver.solveSnap(0.6f, 0f).target, 0f)
        assertEquals(0f, GestureSpringSolver.solveSnap(0.4f, 0f).target, 0f)
        assertEquals(1f, GestureSpringSolver.solveSnap(0.5f, 0f).target, 0f)   // exactly at bias
    }

    @Test
    fun `velocity look ahead pushes a mid position over the bias`() {
        // 0.45 + 1.0 * 0.08 = 0.53 >= 0.5 -> expand
        assertEquals(1f, GestureSpringSolver.solveSnap(0.45f, 1.0f).target, 0f)
        // 0.45 - 1.0 * 0.08 = 0.37 < 0.5 -> collapse
        assertEquals(0f, GestureSpringSolver.solveSnap(0.45f, -1.0f).target, 0f)
    }

    @Test
    fun `flung releases get the stiffer critically damped spring`() {
        val flung = GestureSpringSolver.solveSnap(0.2f, 2f)
        val still = GestureSpringSolver.solveSnap(0.2f, 0f)
        assertEquals(GestureSpringSolver.FLING_STIFFNESS, flung.stiffness, 0f)
        assertEquals(GestureSpringSolver.FLING_DAMPING, flung.dampingRatio, 0f)
        assertEquals(GestureSpringSolver.SETTLE_STIFFNESS, still.stiffness, 0f)
        assertEquals(GestureSpringSolver.SETTLE_DAMPING, still.dampingRatio, 0f)
    }

    @Test
    fun `progress is clamped before deciding`() {
        assertEquals(1f, GestureSpringSolver.solveSnap(7f, 0f).target, 0f)     // clamps to 1
        assertEquals(0f, GestureSpringSolver.solveSnap(-3f, 0f).target, 0f)    // clamps to 0
    }

    // ── Integrator physics ──────────────────────────────────────────────────

    @Test
    fun `integrator converges to the target from rest`() {
        var p = 0.2f
        var v = 0f
        val dt = 1f / 60f
        var steps = 0
        while (!GestureSpringSolver.isAtRest(p, v, target = 1f, epsilon = eps) && steps < 600) {
            val next = GestureSpringSolver.step(
                position = p, velocity = v, target = 1f, dtSec = dt,
                stiffness = GestureSpringSolver.SETTLE_STIFFNESS,
                dampingRatio = GestureSpringSolver.SETTLE_DAMPING
            )
            p = next[0]; v = next[1]
            steps++
        }
        assertTrue("did not settle within 10s of simulated time", steps < 600)
        assertEquals(1f, p, eps)
        assertEquals(0f, v, 50f * eps)
    }

    @Test
    fun `integrator carries initial velocity toward the target`() {
        // Released with a strong upward velocity from 0.4: should still land at 1.
        var p = 0.4f
        var v = 3f
        val dt = 1f / 60f
        var steps = 0
        while (!GestureSpringSolver.isAtRest(p, v, target = 1f, epsilon = eps) && steps < 600) {
            val next = GestureSpringSolver.step(
                position = p, velocity = v, target = 1f, dtSec = dt,
                stiffness = GestureSpringSolver.FLING_STIFFNESS,
                dampingRatio = GestureSpringSolver.FLING_DAMPING
            )
            p = next[0]; v = next[1]
            steps++
        }
        assertTrue(steps < 600)
        assertEquals(1f, p, eps)
    }

    @Test
    fun `critically damped config never overshoots the target`() {
        var p = 0f
        var v = 0f
        val dt = 1f / 60f
        var maxPosition = 0f
        for (i in 0 until 600) {
            val next = GestureSpringSolver.step(
                position = p, velocity = v, target = 1f, dtSec = dt,
                stiffness = GestureSpringSolver.FLING_STIFFNESS,
                dampingRatio = 1f   // critical
            )
            p = next[0]; v = next[1]
            if (p > maxPosition) maxPosition = p
        }
        assertTrue(
            "critically damped spring overshot: $maxPosition",
            maxPosition <= 1f + 2f * eps
        )
    }

    @Test
    fun `underdamped config settles with bounded oscillation`() {
        var p = 0f
        var v = 0f
        val dt = 1f / 60f
        var steps = 0
        while (!GestureSpringSolver.isAtRest(p, v, target = 1f, epsilon = 0.01f) && steps < 1200) {
            val next = GestureSpringSolver.step(
                position = p, velocity = v, target = 1f, dtSec = dt,
                stiffness = GestureSpringSolver.SETTLE_STIFFNESS,
                dampingRatio = 0.5f   // underdamped
            )
            p = next[0]; v = next[1]
            steps++
        }
        assertTrue(steps < 1200)
        assertEquals(1f, p, 0.01f)
    }

    @Test
    fun `step is numerically stable at a coarse 8ms timestep`() {
        var p = 0.9f
        var v = -0.4f
        for (i in 0 until 600) {
            val next = GestureSpringSolver.step(
                position = p, velocity = v, target = 0f, dtSec = 0.008f,
                stiffness = GestureSpringSolver.SETTLE_STIFFNESS,
                dampingRatio = GestureSpringSolver.SETTLE_DAMPING
            )
            p = next[0]; v = next[1]
            assertTrue(java.lang.Float.isFinite(p))
            assertTrue(java.lang.Float.isFinite(v))
            assertTrue(Math.abs(p) < 10f)   // never explodes
        }
        assertEquals(0f, p, 0.01f)
    }

    // ── Settle duration estimates ───────────────────────────────────────────

    @Test
    fun `settle duration is bounded to a perceptual window`() {
        val d = GestureSpringSolver.estimateSettleDurationMs(
            position = 0.3f, velocity = 0.5f, target = 1f,
            stiffness = GestureSpringSolver.SETTLE_STIFFNESS,
            dampingRatio = GestureSpringSolver.SETTLE_DAMPING
        )
        assertTrue(d in 80..1200)
    }

    @Test
    fun `settle duration is zero when already at rest on the target`() {
        val d = GestureSpringSolver.estimateSettleDurationMs(
            position = 1f, velocity = 0f, target = 1f,
            stiffness = GestureSpringSolver.SETTLE_STIFFNESS,
            dampingRatio = GestureSpringSolver.SETTLE_DAMPING
        )
        assertEquals(0L, d)
    }

    @Test
    fun `damped amplitude decays monotonically in magnitude envelope`() {
        val a0 = 1f
        val at05 = GestureSpringSolver.dampedAmplitude(a0, 0.5f, 380f, 1f)
        val at1 = GestureSpringSolver.dampedAmplitude(a0, 1f, 380f, 1f)
        assertTrue(Math.abs(at05) < a0)
        assertTrue(Math.abs(at1) < Math.abs(at05))
        assertEquals(0f, GestureSpringSolver.dampedAmplitude(a0, 10f, 380f, 1f), 0.05f)
    }

    @Test
    fun `snap plans always report a non negative settle duration`() {
        for (p in listOf(0f, 0.2f, 0.5f, 0.8f, 1f)) {
            for (v in listOf(-3f, -1f, 0f, 1f, 3f)) {
                val plan = GestureSpringSolver.solveSnap(p, v)
                assertTrue(plan.settleDurationMs >= 0L)
            }
        }
    }
}
