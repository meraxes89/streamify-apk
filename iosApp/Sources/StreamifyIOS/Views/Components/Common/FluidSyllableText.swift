import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.11 — FluidSyllableText: syllable-highlighted karaoke text.
//
// Port of ui/components/FluidSyllableText.kt (the draw-phase Canvas sweep used
// on the karaoke screen). Two overloads, one component:
//   (a) syllables + per-syllable times + headMs — DISCRETE per-syllable
//       highlighting driven by positionMs (AttributedString runs);
//   (b) plain text + progress 0…1 — a SMOOTH line sweep (inline private mask
//       variant; the line-granular sibling lives in FluidSyllableLine.swift).
// The native draw-phase contract (playhead read only at render time) is kept
// by passing positionMs as a plain value (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct FluidSyllableText: View {

    private enum Content {
        case syllables([Syllable], headMs: Int, positionMs: Double)
        case sweep(text: String, progress: Double, active: Bool)
    }

    private let content: Content

    /// (a) Syllable-timed overload: each syllable lights once the playhead
    /// reaches its start; `headMs` anchors the line's own window (past the
    /// last end ⇒ fully sung, before it ⇒ fully dimmed).
    public init(syllables: [Syllable], headMs: Int, positionMs: Double) {
        self.content = .syllables(syllables, headMs: headMs, positionMs: positionMs)
    }

    /// (b) Plain-text overload: one smooth 0…1 fill sweep across the line.
    public init(text: String, progress: Double, active: Bool) {
        self.content = .sweep(text: text, progress: progress, active: active)
    }

    public var body: some View {
        switch content {
        case let .syllables(syllables, headMs, positionMs):
            syllableHighlighted(syllables, headMs: headMs, positionMs: positionMs)
        case let .sweep(text, progress, active):
            smoothSweep(text, progress: progress, active: active)
        }
    }

    // MARK: (a) Discrete per-syllable runs

    private func syllableHighlighted(
        _ syllables: [Syllable],
        headMs: Int,
        positionMs: Double
    ) -> some View {
        let lineEndMs = syllables.last?.endMs ?? headMs
        let isPast = positionMs > Double(lineEndMs)
        let isActive = !isPast && positionMs >= Double(headMs)

        var attributed = AttributedString()
        for syllable in syllables {
            var run = AttributedString(syllable.text)
            let sung = isPast || (isActive && positionMs >= Double(syllable.startMs))
            run.foregroundColor = sung ? StreamifyColors.textMain : StreamifyColors.textTertiary
            attributed += run
        }

        return Text(attributed)
            .font(StreamifyType.lyricsActive)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: (b) Inline smooth sweep

    private func smoothSweep(_ text: String, progress: Double, active: Bool) -> some View {
        let clamped = min(max(progress, 0), 1)
        return Text(text)
            .font(StreamifyType.lyricsActive)
            .foregroundStyle(StreamifyColors.textTertiary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .leading) {
                GeometryReader { geo in
                    Text(text)
                        .font(StreamifyType.lyricsActive)
                        .foregroundStyle(StreamifyColors.textMain)
                        .frame(width: geo.size.width, alignment: .leading)
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * clamped)
                        }
                }
            }
            .scaleEffect(active ? 1.02 : 1.0)
            .animation(Motion.playerTransition, value: active)
    }
}

#Preview("Fluid syllable text") {
    KaraokeTextDemo()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(StreamifyColors.bgPlayer)
}

private struct KaraokeTextDemo: View {
    @State private var progress = 0.4

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            // (a) discrete syllable runs — playhead mid-"Bit-perfect rain"
            FluidSyllableText(
                syllables: PreviewCatalog.syllableLine.syllables,
                headMs: PreviewCatalog.syllableLine.timeMs,
                positionMs: 12_760
            )
            FluidSyllableText(
                syllables: PreviewCatalog.syllableLine.syllables,
                headMs: PreviewCatalog.syllableLine.timeMs,
                positionMs: 11_000
            )
            // (b) smooth sweep
            FluidSyllableText(text: "Static in the vellum light", progress: progress, active: true)
            FluidSyllableText(text: "Slow start, wide horizon", progress: 0, active: false)
            Slider(value: $progress)
        }
        .padding(28)
    }
}
