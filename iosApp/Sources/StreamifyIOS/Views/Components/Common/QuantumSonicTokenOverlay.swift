import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the flying sonic token renderer (consumer).
//
// Port of ui/components/QuantumSonicTokenOverlay.kt rebuilt natively: where
// Android renders the Weft-channel pose through weftGraphicsLayer at 120 Hz,
// here the overlay drives the SAME QuantumSonicTokenController pose from a
// TimelineView(.animation) tick — interpolation by the display link, zero
// Animatable. The token is a rounded-square mini card (gradient art + glyph)
// with rotationEffect/scaleEffect/offset, trailed by three ghost copies
// derived from the pose history ring buffer (≈ t−0.02 s spacing at 120 Hz).
// Fixtures: PreviewCatalog.tracks.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct QuantumSonicTokenOverlay: View {
    public var controller: QuantumSonicTokenController
    public var title: String = ""
    public var artist: String = ""

    /// Frame bookkeeping + the ghost-trail ring buffer, both held as plain
    /// reference objects inside @State (mutated per tick WITHOUT triggering
    /// SwiftUI invalidation — the TimelineView owns the redraw schedule).
    @State private var clock = PoseClock()
    @State private var history = TokenHistory()

    public init(
        controller: QuantumSonicTokenController,
        title: String = "",
        artist: String = ""
    ) {
        self.controller = controller
        self.title = title
        self.artist = artist
    }

    public var body: some View {
        GeometryReader { proxy in
            TimelineView(
                .animation(minimumInterval: 1.0 / 120.0, paused: controller.stage != .flying)
            ) { context in
                // TimelineView's render closure is nonisolated; the token
                // controller is @MainActor (the 120 Hz pose producer).
                // SwiftUI renders TimelineView content on the main thread —
                // assume the actor and step the physics there.
                MainActor.assumeIsolated {
                    let frame = stepPose(context.date, in: proxy.size)
                    return tokenScene(frame, in: proxy.size)
                }
            }
        }
        .allowsHitTesting(false)
    }

    /// One display-link tick: integrate the pose, publish the frame, and
    /// append the mapped position to the ghost-trail ring buffer.
    private func stepPose(_ date: Date, in size: CGSize) -> [Double] {
        let rawDt = clock.lastFrame.map { date.timeIntervalSince($0) } ?? 0
        clock.lastFrame = date
        let dt = min(max(rawDt, 0), 0.05)
        if dt > 0 {
            controller.tick(dt: dt)
        }
        let frame = controller.poseFrame
        if controller.stage == .flying {
            history.push(mappedPosition(frame, in: size))
        }
        return frame
    }

    private func mappedPosition(_ frame: [Double], in size: CGSize) -> CGPoint {
        CGPoint(
            x: (frame[0] - 0.5) * size.width,
            y: (frame[1] - 0.5) * size.height
        )
    }

    @ViewBuilder
    private func tokenScene(_ frame: [Double], in size: CGSize) -> some View {
        let position = mappedPosition(frame, in: size)
        let tintPhase = frame[4]
        ZStack {
            // Trail: three ghost copies ≈ 16/33/50 ms behind the live pose
            // (a 0.02 s-lagged sampling of the history ring buffer).
            ForEach(0..<3) { index in
                TokenCardContent(title: title, artist: artist)
                    .opacity(max(0.4 - Double(index) * 0.14, 0.1))
                    .scaleEffect(1 - Double(index) * 0.06)
                    .position(history.sample(framesBack: (index + 1) * 2) ?? position)
            }
            TokenCardContent(title: title, artist: artist)
                .rotationEffect(.radians(frame[2]))
                .scaleEffect(frame[3])
                .position(position)
                .shadow(
                    color: StreamifyColors.primary.opacity(0.35),
                    radius: 8 + 4 * abs(sin(tintPhase))
                )
        }
        .opacity(controller.stage == .docked ? 0 : 1)
        .animation(Motion.snappy, value: controller.stage)
    }
}

/// The rounded-square mini card: seeded gradient art plate + glyph + captions.
private struct TokenCardContent: View {
    let title: String
    let artist: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ZStack(alignment: .bottomTrailing) {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: title)))
                Image(systemName: StreamifyIcon.wave)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Color.white.opacity(0.9))
                    .padding(4)
            }
            .frame(width: 42, height: 42)
            Text(title.isEmpty ? "Token" : title)
                .font(StreamifyType.bodySmall.weight(.bold))
                .foregroundStyle(StreamifyColors.textMain)
                .lineLimit(1)
            Text(artist.isEmpty ? "in flight" : artist)
                .font(.system(size: 10))
                .foregroundStyle(StreamifyColors.textSecondary)
                .lineLimit(1)
        }
        .padding(9)
        .frame(width: 88, height: 88, alignment: .topLeading)
        .background(
            StreamifyColors.bgSurfaceElevated,
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(StreamifyColors.glassHairline, lineWidth: 1)
        )
    }
}

/// Last-frame timestamp holder (reference type: mutation ≠ invalidation).
private final class PoseClock {
    var lastFrame: Date?
}

/// Fixed-capacity pose history ring (newest first). Sampled for the ghosts —
/// `framesBack` of 2 ≈ 0.02 s at the 120 Hz tick.
private final class TokenHistory {
    private var positions: [CGPoint] = []
    private let capacity = 12

    func push(_ position: CGPoint) {
        positions.insert(position, at: 0)
        if positions.count > capacity {
            positions.removeLast(positions.count - capacity)
        }
    }

    /// The sampled pose, or nil while the ring is still filling (the ghosts
    /// then hold the live position until history accumulates).
    func sample(framesBack: Int) -> CGPoint? {
        let clamped = min(framesBack, positions.count - 1)
        guard clamped >= 0, positions.indices.contains(clamped) else {
            return nil
        }
        return positions[clamped]
    }
}

#Preview {
    QuantumTokenOverlayHost(title: PreviewCatalog.tracks[0].title, artist: PreviewCatalog.tracks[0].artist)
        .background(StreamifyColors.bgBase)
}

@MainActor
private struct QuantumTokenOverlayHost: View {
    @State private var controller = QuantumSonicTokenController()
    let title: String
    let artist: String

    var body: some View {
        QuantumSonicTokenOverlay(controller: controller, title: title, artist: artist)
            .onAppear {
                // A committed release fling: solveSnap pairs it with the
                // stiff critically-damped run toward the dock.
                controller.endGesture(velocity: (x: 0.6, y: 2.4))
            }
    }
}
