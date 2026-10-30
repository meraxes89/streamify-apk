import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.9 — NowPlayingIndicator: the 3-bar equalizer glyph.
//
// Port of ui/components/NowPlayingIndicator.kt. [isPlaying] must reflect the
// ACTUAL playback state: when false the bars render a frozen mid-animation
// silhouette so no animation loop is scheduled while playback is paused
// (the Compose twin's own perf contract). Heights bounce on staggered
// Motion.snappy springs tinted per call site (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct NowPlayingIndicator: View {

    public var isPlaying: Bool
    public var tint: Color

    public init(isPlaying: Bool, tint: Color = StreamifyColors.primary) {
        self.isPlaying = isPlaying
        self.tint = tint
    }

    public var body: some View {
        Group {
            if isPlaying {
                AnimatedNowPlayingBars(tint: tint)
            } else {
                // Frozen mid-animation silhouette for the paused state.
                HStack(alignment: .bottom, spacing: 2) {
                    StaticBar(heightFraction: 0.62, tint: tint)
                    StaticBar(heightFraction: 0.88, tint: tint)
                    StaticBar(heightFraction: 0.50, tint: tint)
                }
            }
        }
        .frame(width: 16, height: 16, alignment: .bottom)
        .accessibilityLabel(isPlaying ? "Now playing" : "Paused")
    }
}

// MARK: - Static paused bars

private struct StaticBar: View {
    var heightFraction: CGFloat
    var tint: Color

    var body: some View {
        Capsule()
            .fill(tint)
            .frame(width: 3, height: 16 * heightFraction)
    }
}

// MARK: - Animated playing bars

private struct AnimatedNowPlayingBars: View {

    var tint: Color

    /// Per-bar low/high heights + the stagger delay (Android: 400/300/500 ms
    /// tweens, reversed) — expressed as three out-of-phase springs.
    private static let profiles: [(low: CGFloat, high: CGFloat, delay: Double)] = [
        (low: 0.30, high: 1.00, delay: 0.00),
        (low: 0.40, high: 0.88, delay: 0.12),
        (low: 0.50, high: 0.92, delay: 0.24),
    ]

    @State private var pulsing = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<3, id: \.self) { index in
                let profile = Self.profiles[index]
                Capsule()
                    .fill(tint)
                    .frame(width: 3, height: 16 * (pulsing ? profile.high : profile.low))
                    .animation(
                        Motion.snappy.repeatForever().delay(profile.delay),
                        value: pulsing
                    )
            }
        }
        .onAppear {
            // Single state flip — the repeatForever springs take it from here.
            pulsing = true
        }
    }
}

#Preview("Now playing indicator") {
    IndicatorDemo()
        .padding(40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(StreamifyColors.bgBase)
}

private struct IndicatorDemo: View {
    @State private var playing = true

    var body: some View {
        VStack(spacing: 24) {
            NowPlayingIndicator(isPlaying: playing)
            NowPlayingIndicator(isPlaying: !playing, tint: StreamifyColors.accentBlue)
            Button(playing ? "Pause" : "Play") {
                playing.toggle()
            }
            .font(StreamifyType.chipText)
        }
    }
}
