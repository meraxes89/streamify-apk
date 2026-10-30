import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Splash wave) — SplashView: the prismatic launch choreography.
//
// Native port of ui/screens/PrismaticSplashScreen.kt's brand lockup: on a
// full-bleed obsidian stage the "STREAMIFY" wordmark keeps its solid white
// base pass at ALL times (legibility never depends on the sweep — Android
// invariant #2) while a spectral rainbow band rides across the glyphs through
// a fixed text mask (GeometryReader band offset driven by a TimelineView
// phase, one pass per loop); the DEVELOPED BY SIREEN badge fades in with
// Motion.reveal; four equalizer capsules pulse at the foot. The surface
// auto-dismisses after 2 s via .task.
// ─────────────────────────────────────────────────────────────────────────────

public struct SplashView: View {

    public var onFinished: () -> Void

    @State private var badgeRevealed = false

    public init(onFinished: @escaping () -> Void = {}) {
        self.onFinished = onFinished
    }

    public var body: some View {
        ZStack {
            StreamifyColors.bgBase.ignoresSafeArea()
            VStack(spacing: StreamifyDimens.spaceLG) {
                Spacer(minLength: 0)
                prismaticWordmark
                SireenBrandingBadge()
                    .opacity(badgeRevealed ? 1 : 0)
                    .offset(y: badgeRevealed ? 0 : 12)
                Spacer(minLength: 0)
                SplashEqualizer()
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .onAppear {
            withAnimation(Motion.reveal(1)) { badgeRevealed = true }
        }
        .task {
            try? await Task.sleep(for: .seconds(2))
            onFinished()
        }
    }

    // MARK: - Wordmark + prismatic sweep (base pass + masked rainbow band)

    private var prismaticWordmark: some View {
        GeometryReader { geo in
            TimelineView(.animation(minimumInterval: 1.0 / 60.0)) { timeline in
                let phase = timeline.date.timeIntervalSinceReferenceDate
                    .truncatingRemainder(dividingBy: 1.6) / 1.6
                ZStack {
                    SplashWordmark()
                        .foregroundStyle(Color.white.opacity(0.96))
                    ZStack {
                        LinearGradient(
                            colors: SplashSpectrum.colors,
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                        .frame(width: geo.size.width * 0.6)
                        .offset(x: geo.size.width * CGFloat(2.4 * phase - 1.2))
                    }
                    .frame(width: geo.size.width, height: geo.size.height)
                    .mask {
                        SplashWordmark()
                    }
                }
            }
        }
        .frame(height: 44)
    }
}

// MARK: - The wordmark (one source of truth for base pass AND mask)

private struct SplashWordmark: View {

    var body: some View {
        Text("STREAMIFY")
            .font(StreamifyType.displayLarge)
            .tracking(8)
            .fixedSize()
    }
}

/// Spectral hue cycle riding the sweep band (prismatic dispersion palette).
private enum SplashSpectrum {
    static let colors: [Color] = [
        Color(streamifyHex: 0xFF0000),
        Color(streamifyHex: 0xFF7A00),
        Color(streamifyHex: 0xFFD700),
        Color(streamifyHex: 0x22C55E),
        Color(streamifyHex: 0x06B6D4),
        Color(streamifyHex: 0x3B82F6),
        Color(streamifyHex: 0xA855F7),
    ]
}

// MARK: - Equalizer capsules (NowPlayingIndicator's bar physics, ×4)

private struct SplashEqualizer: View {

    private static let bars: [(low: CGFloat, high: CGFloat, delay: Double)] = [
        (low: 0.32, high: 1.00, delay: 0.00),
        (low: 0.42, high: 0.82, delay: 0.10),
        (low: 0.30, high: 0.92, delay: 0.20),
        (low: 0.50, high: 0.74, delay: 0.30),
    ]

    @State private var pulsing = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(0..<4, id: \.self) { index in
                let bar = Self.bars[index]
                Capsule()
                    .fill(StreamifyColors.primary)
                    .frame(width: 4, height: 26 * (pulsing ? bar.high : bar.low))
                    .animation(Motion.snappy.repeatForever().delay(bar.delay), value: pulsing)
            }
        }
        .onAppear { pulsing = true }
        .accessibilityLabel("Launching")
    }
}

#Preview("Prismatic splash") {
    SplashView()
}
