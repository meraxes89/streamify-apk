import Foundation
import Observation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the flying "sonic token" physics producer.
//
// Swift counterpart of ui/components/QuantumSonicTokenController.kt, rebuilt
// natively: the Android controller publishes a 14-float pose over the Weft
// continuous-state plane at 120 Hz; here the SAME contract is a plain
// @MainActor @Observable value object — pure pose registers (position in
// progress units, rotation, scale, stage) plus a computed 14-float frame
// (position · rotation · scale · ten tint/glow phases). No Animatable: the
// gesture drives dragBy(dx:dy:), release runs the SAME
// GestureSpringSolver.solveSnap decision the sheet morph uses (docked-back vs
// arrival + fling/settle spring), and tick(dt:) integrates one semi-implicit
// Euler step per call through GestureSpringSolver.step — driven by the
// overlay's TimelineView or by tests.
// ─────────────────────────────────────────────────────────────────────────────

public enum TokenStage: String, Sendable {
    case docked
    case flying
    case arrived
}

@MainActor
@Observable
public final class QuantumSonicTokenController {

    /// The ABI-stable pose frame the overlay renders — 14 floats:
    /// 0 x · 1 y · 2 rotation · 3 scale · 4–13 tint/glow phases.
    public static let poseFloatCount = 14
    private static let tintRates: [Double] = [0.9, 1.3, 1.7, 2.1, 2.6, 3.1, 3.7, 4.3, 5.2, 6.1]

    // Pose registers — progress units span the surface (0…1 on each axis).
    public private(set) var x: Double
    public private(set) var y: Double
    public private(set) var rotation: Double
    public private(set) var scale: Double
    public private(set) var stage: TokenStage

    private var velocityX: Double = 0
    private var velocityY: Double = 0
    private var elapsed: Double = 0
    private var plan: GestureSpringSolver.SnapPlan?
    private var flightSpan: Double
    private let anchor: (x: Double, y: Double)

    /// The dock the token flies toward (progress units).
    public let dock: (x: Double, y: Double)

    public init(
        origin: (x: Double, y: Double) = (x: 0.5, y: 0.18),
        dock: (x: Double, y: Double) = (x: 0.5, y: 0.9)
    ) {
        anchor = origin
        self.dock = dock
        x = origin.x
        y = origin.y
        rotation = 0
        scale = 1
        stage = .docked
        flightSpan = max(hypot(dock.x - origin.x, dock.y - origin.y), 0.05)
    }

    /// The 14-float frame the overlay interpolates from (TimelineView reads).
    public var poseFrame: [Double] {
        var frame: [Double] = [x, y, rotation, scale]
        frame.reserveCapacity(Self.poseFloatCount)
        for index in 0..<Self.tintRates.count {
            frame.append(sin(elapsed * Self.tintRates[index] + Double(index) * 0.7))
        }
        return frame
    }

    /// Finger-driven delta in progress units — the finger owns the token 1:1.
    public func dragBy(dx: Double, dy: Double) {
        x = min(max(x + dx, 0), 1)
        y = min(max(y + dy, 0), 1)
        velocityX = 0
        velocityY = 0
        plan = nil
        flightSpan = max(hypot(dock.x - x, dock.y - y), 0.05)
        stage = .flying
    }

    /// Release: projects the flight onto the dock axis and lets the same
    /// solver that snaps the player sheet decide — dock back (target 0) or
    /// arrive (target 1) — pairing it with the fling/settle spring feel.
    public func endGesture(velocity: (x: Double, y: Double)) {
        velocityX = velocity.x
        velocityY = velocity.y
        let distanceToDock = hypot(dock.x - x, dock.y - y)
        let progress = 1 - min(distanceToDock / max(flightSpan, 0.05), 1)
        let closing = (velocity.x * (dock.x - x) + velocity.y * (dock.y - y))
            / max(distanceToDock, 0.001)
        plan = GestureSpringSolver.solveSnap(progress: progress, velocityPerSec: closing)
        stage = .flying
    }

    /// One 120 Hz physics step: semi-implicit integration through
    /// GestureSpringSolver.step on each axis toward the solved target,
    /// velocity-derived rotation/squash, and the rest-state stage landing.
    public func tick(dt: Double) {
        guard dt > 0 else { return }
        elapsed += dt
        guard let plan, stage == .flying else {
            rotation = 0
            scale = 1
            return
        }
        let target = plan.target >= 1 ? dock : anchor
        let steppedX = GestureSpringSolver.step(
            position: x, velocity: velocityX, target: target.x,
            dtSec: dt, stiffness: plan.stiffness, dampingRatio: plan.dampingRatio
        )
        let steppedY = GestureSpringSolver.step(
            position: y, velocity: velocityY, target: target.y,
            dtSec: dt, stiffness: plan.stiffness, dampingRatio: plan.dampingRatio
        )
        x = steppedX.position
        velocityX = steppedX.velocity
        y = steppedY.position
        velocityY = steppedY.velocity

        let speed = hypot(velocityX, velocityY)
        rotation = atan2(velocityY, velocityX)
        scale = 1 + min(0.22, speed * 0.05)

        let atRest = GestureSpringSolver.isAtRest(position: x, velocity: velocityX, target: target.x)
            && GestureSpringSolver.isAtRest(position: y, velocity: velocityY, target: target.y)
        if atRest {
            x = target.x
            y = target.y
            velocityX = 0
            velocityY = 0
            rotation = 0
            scale = 1
            stage = plan.target >= 1 ? .arrived : .docked
            self.plan = nil
        }
    }
}

#Preview {
    QuantumTokenPreviewHost(title: PreviewCatalog.tracks[0].title, artist: PreviewCatalog.tracks[0].artist)
        .background(StreamifyColors.bgBase)
}

@MainActor
private struct QuantumTokenPreviewHost: View {
    @State private var controller = QuantumSonicTokenController()
    let title: String
    let artist: String

    var body: some View {
        QuantumSonicTokenOverlay(controller: controller, title: title, artist: artist)
            .onAppear {
                // A committed release fling: the solver pairs it with the
                // stiff critically-damped run toward the dock (arrival).
                controller.endGesture(velocity: (x: 0.4, y: 2.2))
            }
    }
}
