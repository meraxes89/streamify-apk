import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtActiveEqualizer — the animated now-playing EQ bars badge. Port of
// ui/components/yt/YtActiveEqualizer.kt: four capsules loop on staggered
// ease-in/out heights (Android 420/310/480 ms three-bar cycle + frozen frame
// added as the fourth bar). Pure shapes → redacted-proof; the loop settles to
// a frozen silhouette while paused, so idle surfaces cost zero frames.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtActiveEqualizer: View {
    public var isPlaying: Bool
    public var color: Color

    public init(isPlaying: Bool, color: Color = StreamifyColors.activeControl) {
        self.isPlaying = isPlaying
        self.color = color
    }

    @State private var bar0: CGFloat = 0.2
    @State private var bar1: CGFloat = 0.9
    @State private var bar2: CGFloat = 0.4
    @State private var bar3: CGFloat = 0.65

    private var heights: [CGFloat] {
        isPlaying ? [bar0, bar1, bar2, bar3] : [0.55, 0.82, 0.45, 0.7]
    }

    public var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4, id: \.self) { index in
                Capsule()
                    .fill(color)
                    .frame(width: 3, height: 4 + heights[index] * 12)
            }
        }
        .frame(width: 18, height: 16, alignment: .bottom)
        .animation(Motion.snappy, value: isPlaying)
        .onAppear { animateIfNeeded() }
        .onChange(of: isPlaying) { _, _ in animateIfNeeded() }
    }

    private func animateIfNeeded() {
        guard isPlaying else { return }
        withAnimation(.easeInOut(duration: 0.42).repeatForever(autoreverses: true)) { bar0 = 1.0 }
        withAnimation(.easeInOut(duration: 0.31).repeatForever(autoreverses: true)) { bar1 = 0.3 }
        withAnimation(.easeInOut(duration: 0.48).repeatForever(autoreverses: true)) { bar2 = 0.85 }
        withAnimation(.easeInOut(duration: 0.37).repeatForever(autoreverses: true)) { bar3 = 0.55 }
    }
}

private struct EqualizerDemo: View {
    @State private var playing = true

    var body: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            YtActiveEqualizer(isPlaying: playing)
            Text(playing ? "Playing — tap to pause" : "Paused — tap to play")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
        .padding(StreamifyDimens.spaceXL)
        .background(StreamifyColors.bgBase)
        .contentShape(Rectangle())
        .onTapGesture { playing.toggle() }
    }
}

#Preview("Active equalizer") {
    EqualizerDemo()
}
