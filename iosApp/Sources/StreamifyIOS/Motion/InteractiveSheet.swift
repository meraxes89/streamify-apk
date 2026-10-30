import Observation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-10 — interactive sheets: the expandable spring modal that hosts the
// full player (SW-ENG2-05).
//
// The Swift counterpart of ui/motion/LiquidMorphController.kt +
// FullPlayerSheet's collapse wiring, rebuilt the NATIVE way (SW-DEC-06): no
// Animatable/rememberCoroutineScope baggage — SwiftUI's spring system owns
// interpolation, the finger owns the gesture 1:1, and GestureSpringSolver
// (the frozen-ABI port) decides where a release lands:
//
//   • Entrance: the sheet grows from 92 % scale / 22 % below deck
//     (LiquidMorphGeometry.sheetScale / sheetTranslationFraction) with
//     Motion.playerTransition — one continuous, overshoot-free expand.
//   • Interactive dismissal: downward drag translates 1:1 with the finger,
//     resistance-blends into a saturating tanh tail (swipeResistanceOffset),
//     the sheet relaxes toward 0.92 scale, and release either springs back
//     (Motion.settle) or commits dismissal at ≥140 pt travel or ≥2400 pt/s
//     velocity — the Android FullPlayerSheet spec exactly.
//   • 120 Hz: the drag path is transform-only (no layout, no allocations).
// ─────────────────────────────────────────────────────────────────────────────

/// Modern SwiftUI morph state (SW-DEC-06): @Observable instead of Compose's
/// Animatable plumbing. Pure projections come from LiquidMorphGeometry, so
/// the same math stays JVM/Swift-testable.
@MainActor @Observable
public final class LiquidMorphController {

    /// 0 = collapsed dock, 1 = sheet fully open.
    public private(set) var progress: Double = 0
    public private(set) var isDragging = false

    public init() {}

    public func setProgress(_ value: Double) {
        progress = min(max(value, 0), 1)
    }

    public func setDragging(_ value: Bool) {
        isDragging = value
    }

    // ─ LiquidMorphGeometry projections (single source of truth) ──────────
    public var miniAlpha: Double { LiquidMorphGeometry.miniBarAlpha(progress) }
    public var miniLiftFraction: Double { LiquidMorphGeometry.miniBarLiftFraction(progress) }
    public var sheetOpacity: Double { LiquidMorphGeometry.sheetAlpha(progress) }
    public var sheetScaleFactor: Double { LiquidMorphGeometry.sheetScale(progress) }
}

/// The dismiss-gesture spec (Android FullPlayerSheet constants).
public enum SheetDismissSpec {
    public static let travelPt: Double = 140
    public static let velocityPtPerSec: Double = 2_400
}

/// An interactive spring-modal container: place `FullPlayerView` (or any
/// surface) inside; the container owns entrance choreography, rubber-band
/// drag dismissal and the snap decision. Present it with `fullScreenCover`.
public struct InteractivePlayerSheet<Content: View>: View {

    @Binding private var isPresented: Bool
    private let content: Content

    @State private var dismissDrag: Double = 0
    @State private var appeared = false

    public init(
        isPresented: Binding<Bool>,
        @ViewBuilder content: () -> Content
    ) {
        _isPresented = isPresented
        self.content = content()
    }

    public var body: some View {
        let openness = max(0, 1 - dismissDrag / SheetDismissSpec.travelPt)

        GeometryReader { proxy in
            content
                .scaleEffect(
                    appeared
                        ? LiquidMorphGeometry.sheetScale(openness)
                        : LiquidMorphGeometry.sheetScale(0)
                )
                .offset(
                    y: appeared
                        ? resistedOffset
                        : proxy.size.height * LiquidMorphGeometry.sheetTranslationFraction(0)
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .gesture(dismissGesture)
        }
        .background(StreamifyColors.bgBase.ignoresSafeArea())
        .onAppear {
            dismissDrag = 0
            // Entrance: grow from the deck (92 % scale, 22 % below deck)
            // with one continuous overshoot-free spring.
            withAnimation(Motion.playerTransition) {
                appeared = true
            }
        }
    }

    // ─ Interactive rubber-band dismissal ──────────────────────────────────

    /// 1:1 with the finger inside the travel threshold, saturating tanh
    /// resistance beyond it (swipeResistanceOffset — C1-continuous seam).
    private var resistedOffset: Double {
        dismissDrag <= 0 ? 0 : LiquidMorphGeometry.swipeResistanceOffset(
            rawDragPt: dismissDrag,
            thresholdPt: SheetDismissSpec.travelPt
        )
    }

    private var dismissGesture: some Gesture {
        DragGesture(minimumDistance: 12, coordinateSpace: .local)
            .onChanged { value in
                dismissDrag = max(0, value.translation.height)
            }
            .onEnded { value in
                let openness = max(0, 1 - min(dismissDrag, SheetDismissSpec.travelPt) / SheetDismissSpec.travelPt)

                // Predicted-end lookahead ≈ 100 ms → pt/s, normalized into
                // progress/sec the solver speaks.
                let lookaheadSec = 0.1
                let velocityPtPerSec = Double(
                    value.predictedEndTranslation.height - value.translation.height
                ) / lookaheadSec
                let opennessRate = -velocityPtPerSec / SheetDismissSpec.travelPt

                let plan = GestureSpringSolver.solveSnap(
                    progress: openness,
                    velocityPerSec: opennessRate
                )
                if plan.target <= 0.5 {
                    Haptics.play(.skipTrigger)
                    withAnimation(Motion.settle) { dismissDrag = 0 }
                    isPresented = false
                } else {
                    withAnimation(Motion.settle) { dismissDrag = 0 }
                }
            }
    }
}

// MARK: - Presentation convenience

extension View {

    /// Presents the full player as an interactive spring modal
    /// (RootShellView's only presentation path for SW-ENG2-05).
    ///
    /// `content` escapes into fullScreenCover's presentation closure — the
    /// sheet builder itself still resolves the view once, at construction.
    public func fullPlayerSheet(isPresented: Binding<Bool>, @escaping @ViewBuilder content: () -> some View) -> some View {
        fullScreenCover(isPresented: isPresented) {
            InteractivePlayerSheet(isPresented: isPresented, content: content)
        }
    }
}
