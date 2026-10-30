import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — the motion system's headless verification gates.
//
// The gesture solver + morph geometry are PURE functions (the frozen-ABI
// port of GestureSpringSolver.kt / LiquidMorphGeometry.kt): every invariant
// is verifiable with zero device, zero rendering — the same JVM-side
// guarantees the Android originals carry, on the Swift side.
// ─────────────────────────────────────────────────────────────────────────────

final class GestureSpringSolverTests: XCTestCase {

    // ── solveSnap: the release decision ───────────────────────────────────

    func testUpwardFlingCommitsToOpen() {
        let plan = GestureSpringSolver.solveSnap(progress: 0.2, velocityPerSec: 2.0)
        XCTAssertEqual(plan.target, 1)
        XCTAssertEqual(plan.stiffness, GestureSpringSolver.flingStiffness)
        XCTAssertEqual(plan.dampingRatio, GestureSpringSolver.flingDamping)
    }

    func testDownwardFlingCommitsToClosed() {
        let plan = GestureSpringSolver.solveSnap(progress: 0.8, velocityPerSec: -2.0)
        XCTAssertEqual(plan.target, 0)
    }

    func testPositionBiasWithoutVelocityUsesLookahead() {
        // p = 0.6 is past the 0.5 bias even at rest.
        XCTAssertEqual(GestureSpringSolver.solveSnap(progress: 0.6, velocityPerSec: 0).target, 1)
        // p = 0.4 with a gentle upward drift: 0.4 + 2×0.08 = 0.56 ≥ 0.5.
        XCTAssertEqual(GestureSpringSolver.solveSnap(progress: 0.4, velocityPerSec: 2.0).target, 1)
        // Same position, dead-stopped: below the bias.
        XCTAssertEqual(GestureSpringSolver.solveSnap(progress: 0.4, velocityPerSec: 0).target, 0)
    }

    func testSettleDurationClampsToThe80To1200Window() {
        let instant = GestureSpringSolver.solveSnap(progress: 1.0, velocityPerSec: 0)
        XCTAssertEqual(instant.settleDurationMs, 0, "already at target")

        let distant = GestureSpringSolver.solveSnap(progress: 0.0, velocityPerSec: 0)
        XCTAssertTrue((80...1_200).contains(distant.settleDurationMs))
    }

    // ── step: the integrator ───────────────────────────────────────────────

    func testCriticallyDampedStepConvergesWithoutOvershoot() {
        var position = 0.0
        var velocity = 0.0
        var lastPosition = 0.0
        var overshot = false

        for _ in 0..<240 {  // 2 s at 120 Hz
            let next = GestureSpringSolver.step(
                position: position,
                velocity: velocity,
                target: 1.0,
                dtSec: 1.0 / 120.0,
                stiffness: 550,
                dampingRatio: 1.0
            )
            if next.position < lastPosition { overshot = true }
            lastPosition = next.position
            position = next.position
            velocity = next.velocity
        }

        XCTAssertFalse(overshot, "critically damped runs never reverse")
        XCTAssertTrue(
            GestureSpringSolver.isAtRest(
                position: position, velocity: velocity, target: 1.0, epsilon: 0.02
            )
        )
    }

    func testDampedAmplitudeEnvelopeDecays() {
        // ζ = 0.9, stiffness = 380: the |envelope| must shrink monotonically
        // on a coarse grid (the underdamped cosine can cross zero, so compare
        // envelope magnitudes a quarter-second apart).
        let a1 = abs(GestureSpringSolver.dampedAmplitude(
            initialAmplitude: 1, elapsedSec: 0, stiffness: 380, dampingRatio: 0.9
        ))
        let a2 = abs(GestureSpringSolver.dampedAmplitude(
            initialAmplitude: 1, elapsedSec: 0.25, stiffness: 380, dampingRatio: 0.9
        ))
        XCTAssertLessThan(a2, a1)
    }
}

final class LiquidMorphGeometryTests: XCTestCase {

    func testMiniBarAlphaEndpoints() {
        XCTAssertEqual(LiquidMorphGeometry.miniBarAlpha(0), 1)
        XCTAssertEqual(LiquidMorphGeometry.miniBarAlpha(0.7), 0)
        XCTAssertEqual(LiquidMorphGeometry.miniBarAlpha(1), 0)
    }

    func testSheetScaleAndCornerInterpolation() {
        XCTAssertEqual(LiquidMorphGeometry.sheetScale(0), 0.92, accuracy: 1e-9)
        XCTAssertEqual(LiquidMorphGeometry.sheetScale(1), 1.0, accuracy: 1e-9)
        XCTAssertEqual(LiquidMorphGeometry.coverCornerRadius(0), 8)
        XCTAssertEqual(LiquidMorphGeometry.coverCornerRadius(1), 24)
        XCTAssertEqual(LiquidMorphGeometry.coverCornerRadius(0.5), 16)
    }

    func testSharedLayerAlphaIsOpenIntervalOnly() {
        XCTAssertEqual(LiquidMorphGeometry.sharedLayerAlpha(0), 0)
        XCTAssertEqual(LiquidMorphGeometry.sharedLayerAlpha(0.5), 1)
        XCTAssertEqual(LiquidMorphGeometry.sharedLayerAlpha(1), 0)
    }

    func testDragToProgressClamps() {
        XCTAssertEqual(LiquidMorphGeometry.dragToProgress(200, 100), 1)
        XCTAssertEqual(LiquidMorphGeometry.dragToProgress(-50, 100), 0)
        XCTAssertEqual(LiquidMorphGeometry.dragToProgress(40, 0), 0, "zero travel is zero progress")
    }

    func testSwipeResistanceIsContinuousAtTheSeamAndSaturates() {
        // 1:1 inside the trigger threshold…
        XCTAssertEqual(
            LiquidMorphGeometry.swipeResistanceOffset(rawDragPt: 40, thresholdPt: 80),
            40, accuracy: 1e-9
        )
        // …C1-continuous at the seam…
        XCTAssertEqual(
            LiquidMorphGeometry.swipeResistanceOffset(rawDragPt: 80, thresholdPt: 80),
            80, accuracy: 1e-6
        )
        // …sign preserving…
        XCTAssertEqual(
            LiquidMorphGeometry.swipeResistanceOffset(rawDragPt: -40, thresholdPt: 80),
            -40, accuracy: 1e-9
        )
        // …and saturating beyond: 200 pt of raw drag must resist to under
        // the linear bound (200), yet keep moving toward the tanh ceiling
        // (threshold + 0.75×threshold = 140 asymptote).
        let saturated = LiquidMorphGeometry.swipeResistanceOffset(rawDragPt: 200, thresholdPt: 80)
        XCTAssertLessThan(saturated, 200)
        XCTAssertGreaterThan(saturated, 80)
        XCTAssertLessThan(
            LiquidMorphGeometry.swipeResistanceOffset(rawDragPt: 10_000, thresholdPt: 80),
            140.0 + 1e-6
        )
    }

    func testSwipePeekFractionDeadZoneAndRamp() {
        // Dead zone until 55 % of the threshold (80): 44 pt → 0.
        XCTAssertEqual(
            LiquidMorphGeometry.swipePeekFraction(rawDragPt: 44, thresholdPt: 80),
            0, accuracy: 1e-9
        )
        // Fully legible at 85 % (68 pt): (68 − 44) / (0.30 × 80) = 1 → smoothstep(1) = 1.
        XCTAssertEqual(
            LiquidMorphGeometry.swipePeekFraction(rawDragPt: 68, thresholdPt: 80),
            1, accuracy: 1e-9
        )
        // Monotone between.
        let mid = LiquidMorphGeometry.swipePeekFraction(rawDragPt: 56, thresholdPt: 80)
        XCTAssertTrue((0...1).contains(mid))
        XCTAssertGreaterThan(mid, 0)
    }

    func testFallbackHeroRectCapsAtHeroMax() {
        let rect = LiquidMorphGeometry.fallbackHeroRect(screenW: 800, screenH: 600, heroMaxPt: 320)
        XCTAssertEqual(rect.width, 320)
        XCTAssertEqual(rect.midX, 400, accuracy: 1e-9)
        XCTAssertEqual(rect.midY, 600 * 0.36, accuracy: 1e-9)

        let narrow = LiquidMorphGeometry.fallbackHeroRect(screenW: 300, screenH: 600, heroMaxPt: 320)
        XCTAssertEqual(narrow.width, 300 * 0.88, accuracy: 1e-9)
    }
}

final class MotionRubberBandTests: XCTestCase {

    func testIdentityInsideTheBounds() {
        XCTAssertEqual(Motion.rubberBand(50, dimension: 100), 50, accuracy: 1e-9)
        XCTAssertEqual(Motion.rubberBand(-50, dimension: 100), -50, accuracy: 1e-9)
    }

    func testResistanceBeyondTheBoundsKeepsSignAndSublinearity() {
        let far = Motion.rubberBand(300, dimension: 100)
        XCTAssertLessThan(far, 300)
        XCTAssertGreaterThan(far, 100)

        let negative = Motion.rubberBand(-300, dimension: 100)
        XCTAssertGreaterThan(negative, -300)
        XCTAssertLessThan(negative, -100)

        // Monotone in magnitude: pulling farther always renders farther.
        XCTAssertGreaterThan(
            abs(Motion.rubberBand(400, dimension: 100)),
            abs(Motion.rubberBand(300, dimension: 100))
        )
    }
}
