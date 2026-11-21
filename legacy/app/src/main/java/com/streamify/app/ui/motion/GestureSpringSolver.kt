package com.streamify.app.ui.motion

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Spring-damper snap solver for gesture release — the Kotlin-side
 * implementation of the directive's `RustGesturePhysics` bridge.
 *
 * The `rust/` module is under a strict frozen-ABI isolation for this phase
 * and exposes no gesture-physics JNI symbol, so the identical
 * mass-spring-damper math (unit mass: `a = -k(x - target) - c*v`) is
 * integrated here in pure Kotlin: JVM-testable, allocation-light on the UI
 * thread, and with zero JNI boundary cost. Compose's [androidx.compose.animation.core.Animatable]
 * consumes the solved target/spec/velocity tuple on release.
 */
object GestureSpringSolver {

    /**
     * Solved release plan: where the sheet lands, with which spring feel,
     * and how long the settle is expected to run.
     */
    data class SnapPlan(
        val target: Float,
        val dampingRatio: Float,
        val stiffness: Float,
        val settleDurationMs: Long
    )

    // ── Tuned constants (sheet-weight UI mass) ────────────────────────────

    /** Committed fling: velocity (progress/sec) beyond this always commits. */
    const val COMMIT_VELOCITY_PER_SEC = 1.2f

    /** Position commit threshold without meaningful velocity. */
    const val POSITION_BIAS = 0.5f

    /** Velocity look-ahead horizon (sec) for the position decision. */
    const val VELOCITY_LOOKAHEAD_SEC = 0.08f

    const val SETTLE_STIFFNESS = 380f
    const val SETTLE_DAMPING = 0.9f
    const val FLING_STIFFNESS = 550f
    const val FLING_DAMPING = 1.0f

    // ── Decision core ─────────────────────────────────────────────────────

    /**
     * Decides the snap target from release position + velocity, and pairs it
     * with spring parameters: a deliberate near-settled release gets a
     * softly damped settle; a committed fling gets a stiffer critically
     * damped run so the landing reads as one continuous motion.
     */
    fun solveSnap(progress: Float, velocityPerSec: Float): SnapPlan {
        val p = progress.coerceIn(0f, 1f)
        val v = velocityPerSec

        val flungUp = v >= COMMIT_VELOCITY_PER_SEC
        val flungDown = v <= -COMMIT_VELOCITY_PER_SEC
        val lookahead = p + v * VELOCITY_LOOKAHEAD_SEC

        val target = when {
            flungUp -> 1f
            flungDown -> 0f
            else -> if (lookahead >= POSITION_BIAS) 1f else 0f
        }

        val flung = flungUp || flungDown
        val stiffness = if (flung) FLING_STIFFNESS else SETTLE_STIFFNESS
        val damping = if (flung) FLING_DAMPING else SETTLE_DAMPING

        return SnapPlan(
            target = target,
            dampingRatio = damping,
            stiffness = stiffness,
            settleDurationMs = estimateSettleDurationMs(
                position = p,
                velocity = v,
                target = target,
                stiffness = stiffness,
                dampingRatio = damping
            )
        )
    }

    // ── Physics core (pure integrator, unit mass) ─────────────────────────

    /**
     * One semi-implicit Euler step of the damped spring toward [target].
     * Semi-implicit (velocity first) is unconditionally more stable than
     * explicit Euler for stiff springs at display timesteps.
     */
    fun step(
        position: Float,
        velocity: Float,
        target: Float,
        dtSec: Float,
        stiffness: Float,
        dampingRatio: Float
    ): FloatArray {
        val omega = sqrt(stiffness)               // natural frequency (mass = 1)
        val dampingCoef = 2f * dampingRatio * omega
        val accel = -stiffness * (position - target) - dampingCoef * velocity
        val newVelocity = velocity + accel * dtSec
        val newPosition = position + newVelocity * dtSec
        return floatArrayOf(newPosition, newVelocity)
    }

    /** At-rest check for the integrator loop. */
    fun isAtRest(position: Float, velocity: Float, target: Float, epsilon: Float = 1e-3f): Boolean =
        abs(position - target) < epsilon && abs(velocity) < epsilon

    /**
     * Settle duration via closed-form underdamped envelope: the oscillation
     * amplitude decays as exp(-zeta*omega*t); solving for epsilon gives the
     * time to visually rest. Overdamped cases use the slow-root decay.
     */
    fun estimateSettleDurationMs(
        position: Float,
        velocity: Float,
        target: Float,
        stiffness: Float,
        dampingRatio: Float,
        epsilon: Float = 1e-3f
    ): Long {
        val omega = sqrt(stiffness)
        val distance = abs(position - target) + abs(velocity) / omega
        if (distance <= epsilon) return 0L
        val zeta = dampingRatio
        val decayRate = if (zeta < 1f) {
            zeta * omega                                  // underdamped envelope
        } else {
            omega * (zeta - sqrt(zeta * zeta - 1f)).coerceAtLeast(0.05f) // slow root
        }
        if (decayRate <= 0f) return 1_000L
        val t = (ln(distance / epsilon)) / decayRate
        return (t * 1000f).toLong().coerceIn(80L, 1_200L)
    }

    private fun ln(x: Float): Float = kotlin.math.ln(x.coerceAtLeast(1e-9f))

    /**
     * Amplitude of the damped oscillation for a given elapsed time — used by
     * tests to assert critically-damped configs never overshoot past epsilon.
     */
    fun dampedAmplitude(
        initialAmplitude: Float,
        elapsedSec: Float,
        stiffness: Float,
        dampingRatio: Float
    ): Float {
        val omega = sqrt(stiffness)
        val zeta = dampingRatio
        return if (zeta < 1f) {
            initialAmplitude * exp(-zeta * omega * elapsedSec) * cos(omega * sqrt(1f - zeta * zeta) * elapsedSec)
        } else {
            // Critically/over-damped: monotone decay via the repeated root.
            initialAmplitude * exp(-omega * elapsedSec) * (1f + omega * elapsedSec)
        }
    }
}
