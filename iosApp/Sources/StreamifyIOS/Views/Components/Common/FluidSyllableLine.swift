import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.10 — FluidSyllableLine: one karaoke line, SMOOTH fill sweep.
//
// Port of ui/components/FluidSyllableLine.kt. Unlike per-syllable highlighters,
// this line fills as ONE continuous sweep across the full text: the base layer
// renders the line in textTertiary and an identically-laid-out textMain copy is
// clipped to `progress × width` (the GeometryReader mask technique — the
// native counterpart of the Android offscreen-composited clipRect + feathered
// glow edge), with the active line's subtle 1.02× GPU scale (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct FluidSyllableLine: View {

    private let text: String
    private let progress: Double
    private let active: Bool

    /// Syllable-timed variant — the line's text is the joined syllables, but
    /// the sweep stays line-granular (the smooth-fill contract).
    public init(syllables: [Syllable], progress: Double, active: Bool) {
        self.text = syllables.map(\.text).joined()
        self.progress = progress
        self.active = active
    }

    /// Plain-text variant.
    public init(text: String, progress: Double, active: Bool) {
        self.text = text
        self.progress = progress
        self.active = active
    }

    public var body: some View {
        baseLayer
            .overlay(alignment: .leading) {
                GeometryReader { geo in
                    sweepLayer
                        .frame(width: geo.size.width, alignment: .leading)
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * clampedProgress)
                        }
                }
            }
            .scaleEffect(active ? 1.02 : 1.0)
            .animation(Motion.playerTransition, value: active)
            .accessibilityLabel(text)
            .accessibilityValue(active ? "Singing" : "Idle")
    }

    private var clampedProgress: Double {
        min(max(progress, 0), 1)
    }

    private var baseLayer: some View {
        Text(text)
            .font(StreamifyType.lyricsActive)
            .foregroundStyle(StreamifyColors.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var sweepLayer: some View {
        Text(text)
            .font(StreamifyType.lyricsActive)
            .foregroundStyle(StreamifyColors.textMain)
    }
}

#Preview("Fluid syllable line") {
    FluidLineSweepDemo()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(StreamifyColors.bgPlayer)
}

private struct FluidLineSweepDemo: View {
    @State private var progress = 0.25

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            FluidSyllableLine(syllables: PreviewCatalog.syllableLine.syllables, progress: progress, active: true)
            FluidSyllableLine(text: "Meridian holding the night", progress: progress, active: true)
            FluidSyllableLine(text: "Slow start, wide horizon", progress: 0, active: false)
            Slider(value: $progress)
        }
        .padding(28)
    }
}
