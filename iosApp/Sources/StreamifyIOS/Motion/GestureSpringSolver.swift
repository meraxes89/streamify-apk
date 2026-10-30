import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — the gesture snap solver, ported 1:1 from
// ui/motion/GestureSpringSolver.kt (the Kotlin-side implementation of the
// directive's RustGesturePhysics bridge: unit mass, a = -k(x - target) - c·v).
//
// The Rust core stays behind its frozen-ABI isolation for gesture physics,
// so the identical mass-spring-damper math runs here in pure Swift —
// XCTest-verifiable, allocation-light, zero FFI boundary cost. On release,
// `solveSnap` hands SwiftUI the target + spring feel; `step` exists for the
// tests to integrate the same trajectory the solver predicts.
// ─────────────────────────────────────────────────────────────────────────────

public enum GestureSpringSolver {

    /// Solved release plan: where the surface lands, with which spring
    /// feel, and the settle duration the solver expects.
    public struct SnapPlan: Equatable, Sendable {
        /// 0 = collapsed dock, 1 = fully open sheet.
        public let target: Double
        public let dampingRatio: Double
        public let stiffness: Double
        public let settleDurationMs: Double

        public init(target: Double, dampingRatio: Double, stiffness: Double, settleDurationMs: Double) {
            self.target = target
            self.dampingRatio = dampingRatio
            self.stiffness = stiffness
            self.settleDurationMs = settleDurationMs
        }
    }

    // ─ Tuned constants (sheet-weight UI mass) — Android 1:1 ──────────────

    /// Committed fling: velocity (progress/sec) beyond this always commits.
    public static let commitVelocityPerSec: Double = 1.2
    /// Position commit threshold without meaningful velocity.
    public static let positionBias: Double = 0.5
    /// Velocity look-ahead horizon (sec) for the position decision.
    public static let velocityLookaheadSec: Double = 0.08

    public static let settleStiffness: Double = 380
    public static let settleDamping: Double = 0.9
    public static let flingStiffness: Double = 550
    public static let flingDamping: Double = 1.0

    // ─ Decision core ──────────────────────────────────────────────────────

    /// Decides the snap target from release position + velocity, and pairs
    /// it with spring parameters: a deliberate near-settled release gets a
    /// softly damped settle; a committed fling gets a stiffer critically
    /// damped run so the landing reads as one continuous motion.
    public static func solveSnap(progress: Double, velocityPerSec: Double) -> SnapPlan {
        let p = min(max(progress, 0), 1)
        let v = velocityPerSec

        let flungUp = v >= commitVelocityPerSec
        let flungDown = v <= -commitVelocityPerSec
        let lookahead = p + v * velocityLookaheadSec

        let target: Double
        if flungUp {
            target = 1
        } else if flungDown {
            target = 0
        } else {
            target = lookahead >= positionBias ? 1 : 0
        }

        let flung = flungUp || flungDown
        let stiffness = flung ? flingStiffness : settleStiffness
        let damping = flung ? flingDamping : settleDamping

        return SnapPlan(
            target: target,
            dampingRatio: damping,
            stiffness: stiffness,
            settleDurationMs: estimateSettleDurationMs(
                position: p,
                velocity: v,
                target: target,
                stiffness: stiffness,
                dampingRatio: damping
            )
        )
    }

    // ─ Physics core (pure integrator, unit mass) ─────────────────────────

    /// One semi-implicit Euler step of the damped spring toward `target`.
    /// Semi-implicit (velocity first) is unconditionally more stable than
    /// explicit Euler for stiff springs at display timesteps.
    public static func step(
        position: Double,
        velocity: Double,
        target: Double,
        dtSec: Double,
        stiffness: Double,
        dampingRatio: Double
    ) -> (position: Double, velocity: Double) {
        let omega = sqrt(stiffness)                  // natural frequency (mass = 1)
        let dampingCoef = 2.0 * dampingRatio * omega
        let accel = -stiffness * (position - target) - dampingCoef * velocity
        let newVelocity = velocity + accel * dtSec
        let newPosition = position + newVelocity * dtSec
        return (newPosition, newVelocity)
    }

    /// At-rest check for the integrator loop.
    public static func isAtRest(
        position: Double,
        velocity: Double,
        target: Double,
        epsilon: Double = 1e-3
    ) -> Bool {
        abs(position - target) < epsilon && abs(velocity) < epsilon
    }

    /// Settle duration via the closed-form underdamped envelope: the
    /// oscillation amplitude decays as exp(-ζω·t); solving for ε gives the
    /// time to visually rest. Overdamped cases use the slow-root decay.
    public static func estimateSettleDurationMs(
        position: Double,
        velocity: Double,
        target: Double,
        stiffness: Double,
        dampingRatio: Double,
        epsilon: Double = 1e-3
    ) -> Double {
        let omega = sqrt(stiffness)
        let distance = abs(position - target) + abs(velocity) / omega
        if distance <= epsilon { return 0 }

        let zeta = dampingRatio
        let decayRate: Double
        if zeta < 1 {
            decayRate = zeta * omega                        // underdamped envelope
        } else {
            decayRate = omega * max(zeta - sqrt(zeta * zeta - 1), 0.05)  // slow root
        }
        if decayRate <= 0 { return 1_000 }

        let t = log(max(distance / epsilon, 1e-9)) / decayRate
        return min(max(t * 1_000, 80), 1_200)
    }

    /// Amplitude of the damped oscillation for a given elapsed time — the
    /// tests assert critically-damped configs never overshoot past ε.
    public static func dampedAmplitude(
        initialAmplitude: Double,
        elapsedSec: Double,
        stiffness: Double,
        dampingRatio: Double
    ) -> Double {
        let omega = sqrt(stiffness)
        let zeta = dampingRatio
        if zeta < 1 {
            return initialAmplitude
                * exp(-zeta * omega * elapsedSec)
                * cos(omega * sqrt(1 - zeta * zeta) * elapsedSec)
        }
        // Critically/over-damped: monotone decay via the repeated root.
        return initialAmplitude * exp(-omega * elapsedSec) * (1 + omega * elapsedSec)
    }
}
