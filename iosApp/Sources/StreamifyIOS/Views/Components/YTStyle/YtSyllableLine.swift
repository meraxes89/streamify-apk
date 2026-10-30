import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSyllableLine — one karaoke lyric line with a syllable-level sweep. Port of
// ui/components/yt/YtSyllableLine.kt: the Android sub-pixel Canvas sweep is
// expressed natively as per-syllable Text runs — a syllable flips to TextMain
// once its endMs is behind the sweep head (0...1 fraction across the line),
// everything before/unswept stays TextTertiary; active lines get the bouncy
// spring pop (Android scaleAnim 0.97 → 1.03 → 1.0).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSyllableLine: View {
    public var syllables: [Syllable]
    /// Sweep head as a 0...1 fraction across the line's own time span.
    public var progressMs: Double
    public var isActive: Bool
    public var onTap: () -> Void

    public init(
        syllables: [Syllable],
        progressMs: Double,
        isActive: Bool,
        onTap: @escaping () -> Void = {}
    ) {
        self.syllables = syllables
        self.progressMs = progressMs
        self.isActive = isActive
        self.onTap = onTap
    }

    /// Absolute sweep head (ms) inside the line's [first.startMs, last.endMs].
    private var headMs: Double {
        guard let first = syllables.first, let last = syllables.last else { return 0 }
        let span = Double(last.endMs - first.startMs)
        return Double(first.startMs) + min(max(progressMs, 0), 1) * span
    }

    private func isSwept(_ syllable: Syllable) -> Bool {
        guard isActive else { return false }
        return Double(syllable.endMs) <= headMs
    }

    /// One Text per syllable, concat-style, colored at syllable granularity.
    private var karaokeText: Text {
        syllables
            .map { syllable in
                Text(syllable.text)
                    .foregroundColor(isSwept(syllable) ? StreamifyColors.textMain : StreamifyColors.textTertiary)
            }
            .reduce(Text("")) { $0 + $1 }
    }

    public var body: some View {
        Button(action: onTap) {
            karaokeText
                .font(isActive ? StreamifyType.lyricsActive : StreamifyType.lyricsInactive)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .padding(.vertical, StreamifyDimens.spaceMD)
        .padding(.horizontal, StreamifyDimens.spaceXXL)
        .scaleEffect(isActive ? 1.02 : 0.98, anchor: .leading)
        .animation(Motion.bouncy, value: isActive)
    }
}

private struct SyllableSweepDemo: View {
    @State private var progress = 0.05

    var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            YtSyllableLine(
                syllables: PreviewCatalog.syllableLine.syllables,
                progressMs: progress,
                isActive: true
            )
            YtSyllableLine(
                syllables: PreviewCatalog.syllableLine.syllables,
                progressMs: 0,
                isActive: false
            )
        }
        .padding(StreamifyDimens.spaceXL)
        .background(StreamifyColors.bgBase)
        .onAppear {
            withAnimation(.linear(duration: 3.5).repeatForever(autoreverses: true)) {
                progress = 1
            }
        }
    }
}

#Preview("Karaoke syllable sweep") {
    SyllableSweepDemo()
}
