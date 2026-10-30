import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.17 — LyricsCanvas: the karaoke lyrics list.
//
// Port of ui/components/LyricsCanvas.kt (the frame-ticker Canvas with the
// LowBouncy scroll spring). The native expression keeps the semantics and
// drops the paint plumbing: a ScrollViewReader auto-scrolls to the active
// LyricsLine id (withAnimation Motion.playerTransition) driven by positionMs;
// each line renders via the inline row — active lines get lyricsActive +
// textMain with the progress-driven sweep (the text-based mask technique),
// inactive lines lyricsInactive + textTertiary (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct LyricsCanvas: View {

    public let lyrics: LyricsData
    public let positionMs: Double

    public init(lyrics: LyricsData, positionMs: Double) {
        self.lyrics = lyrics
        self.positionMs = positionMs
    }

    /// Playhead with the track's global offset applied.
    private var playheadMs: Double {
        positionMs - Double(lyrics.globalOffsetMs)
    }

    /// The last line whose window has begun — the karaoke focus line.
    private var activeLineId: Int? {
        var active: LyricsLine?
        for line in lyrics.lines where Double(line.timeMs) <= playheadMs {
            active = line
        }
        return active?.id
    }

    public var body: some View {
        if !lyrics.lines.isEmpty {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                        ForEach(lyrics.lines) { line in
                            LyricsRow(
                                line: line,
                                isActive: line.id == activeLineId,
                                progress: lineProgress(line)
                            )
                            .id(line.id)
                        }
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                    .padding(.vertical, StreamifyDimens.spaceGiant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .onAppear {
                    // Initial placement without animation (mid-song entry).
                    if let activeLineId {
                        proxy.scrollTo(activeLineId, anchor: .center)
                    }
                }
                .onChange(of: activeLineId) { _, newId in
                    guard let newId else { return }
                    withAnimation(Motion.playerTransition) {
                        proxy.scrollTo(newId, anchor: .center)
                    }
                }
            }
        }
    }

    /// 0…1 fill of the active line; 0 for every other row.
    private func lineProgress(_ line: LyricsLine) -> Double {
        guard line.id == activeLineId else { return 0 }
        let span = max(line.durationMs, 1)
        let fraction = (playheadMs - Double(line.timeMs)) / Double(span)
        return min(max(fraction, 0), 1)
    }
}

// MARK: - Inline lyrics row

private struct LyricsRow: View {

    let line: LyricsLine
    let isActive: Bool
    let progress: Double

    var body: some View {
        Group {
            if isActive {
                activeRow
            } else {
                Text(line.text)
                    .font(StreamifyType.lyricsInactive)
                    .foregroundStyle(StreamifyColors.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .animation(Motion.playerTransition, value: isActive)
    }

    /// The active line: dimmed lyricsActive base with the clipped textMain
    /// sweep riding the line progress (GeometryReader mask technique).
    private var activeRow: some View {
        Text(line.text)
            .font(StreamifyType.lyricsActive)
            .foregroundStyle(StreamifyColors.textMain.opacity(0.40))
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(alignment: .leading) {
                GeometryReader { geo in
                    Text(line.text)
                        .font(StreamifyType.lyricsActive)
                        .foregroundStyle(StreamifyColors.textMain)
                        .frame(width: geo.size.width, alignment: .leading)
                        .mask(alignment: .leading) {
                            Rectangle()
                                .frame(width: geo.size.width * min(max(progress, 0), 1))
                        }
                }
            }
    }
}

#Preview("Lyrics canvas") {
    LyricsCanvasDemo()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(StreamifyColors.bgPlayer)
}

private struct LyricsCanvasDemo: View {
    @State private var positionMs = 9_500.0

    var body: some View {
        VStack(spacing: 0) {
            LyricsCanvas(lyrics: PreviewCatalog.lyrics, positionMs: positionMs)
            Slider(value: $positionMs, in: 0...30_000)
                .padding(StreamifyDimens.spaceLG)
        }
    }
}
