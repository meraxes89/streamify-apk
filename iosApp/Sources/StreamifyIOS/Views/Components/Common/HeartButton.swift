import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.8 — HeartButton: the animated like/unlike heart.
//
// Port of ui/components/HeartButton.kt (scale pop + rotation shake + particle
// ring + haptic). The motion is composed from the Wave-A motion system: the
// 1.2× pop beat via .heartPop(isLiked:), the 12-heart particle ring via
// HeartBurstView, and the likeHeartbeat haptic on the like edge (SW-DEC-06:
// no manual Animatable plumbing — SwiftUI's springs own the interpolation).
// ─────────────────────────────────────────────────────────────────────────────

public struct HeartButton: View {

    public var isLiked: Bool
    public var onToggle: ((Bool) -> Void)?

    /// One burst per like — HeartBurstView fires on increment.
    @State private var burstTrigger = 0

    public init(isLiked: Bool, onToggle: ((Bool) -> Void)? = nil) {
        self.isLiked = isLiked
        self.onToggle = onToggle
    }

    public var body: some View {
        Button {
            let newValue = !isLiked
            if newValue {
                // Like edge: heartbeat haptic + one particle burst.
                Haptics.play(.likeHeartbeat)
                burstTrigger += 1
            }
            onToggle?(newValue)
        } label: {
            ZStack {
                Image(systemName: StreamifyIcon.heart)
                    .foregroundStyle(isLiked ? Color.clear : StreamifyColors.textSecondary)

                if isLiked {
                    Image(systemName: StreamifyIcon.heartFill)
                        .foregroundStyle(StreamifyColors.primary)
                        .transition(.scale(scale: 0.35).combined(with: .opacity))
                }
            }
            .font(.system(size: 22, weight: .semibold))
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .heartPop(isLiked: isLiked)
            .overlay {
                // Roomy canvas so the particle ring flies past the glyph.
                HeartBurstView(trigger: burstTrigger)
                    .frame(width: 108, height: 108)
            }
        }
        .buttonStyle(.plain)
        .animation(Motion.snappy, value: isLiked)
        .accessibilityLabel(isLiked ? "Unlike" : "Like")
    }
}

#Preview("Heart button") {
    HeartButtonDemo()
        .padding(60)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(StreamifyColors.bgBase)
}

private struct HeartButtonDemo: View {
    @State private var liked = true

    var body: some View {
        VStack(spacing: 24) {
            HeartButton(isLiked: liked) { liked = $0 }
            Text(liked ? "In your likes" : "Not saved")
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }
}
