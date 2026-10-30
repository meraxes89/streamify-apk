import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-05 — the LyricsView: the full-screen karaoke surface
// (the LyricsScreen port).
//
// YtLyricsHeader (dismiss + track + offset-edit trigger) over the
// LyricsCanvas karaoke list (syllable sweep + auto-scroll driven by the
// DAC-paced position). The offset editor (the global ±ms nudge) lowers
// through the shell's callback — the engine owns the lyrics truth.
// ─────────────────────────────────────────────────────────────────────────────

public struct LyricsView: View {

    public var lyrics: LyricsData
    public var trackTitle: String
    public var trackArtist: String
    public var positionMs: Double
    public var onClose: () -> Void
    public var onEditOffset: (() -> Void)? = nil

    public init(
        lyrics: LyricsData,
        trackTitle: String,
        trackArtist: String,
        positionMs: Double,
        onClose: @escaping () -> Void,
        onEditOffset: (() -> Void)? = nil
    ) {
        self.lyrics = lyrics
        self.trackTitle = trackTitle
        self.trackArtist = trackArtist
        self.positionMs = positionMs
        self.onClose = onClose
        self.onEditOffset = onEditOffset
    }

    public var body: some View {
        VStack(spacing: 0) {
            YtLyricsHeader(
                title: trackTitle,
                artist: trackArtist,
                onDismiss: onClose,
                onEdit: onEditOffset
            )
            .padding(.horizontal, StreamifyDimens.spaceLG)

            if lyrics.lines.isEmpty {
                EmptyStateView(
                    symbol: StreamifyIcon.lyrics,
                    title: "No lyrics",
                    message: lyrics.isSynced
                        ? "Synced lyrics exist but carry no lines."
                        : "This track has no lyrics companion yet."
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                LyricsCanvas(lyrics: lyrics, positionMs: positionMs)
            }

            if lyrics.isSynced {
                Text("Syllable-synced · offset \(lyrics.globalOffsetMs) ms")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
                    .padding(.bottom, StreamifyDimens.spaceMD)
            }
        }
        .background(StreamifyColors.bgBase)
    }
}

#Preview {
    LyricsView(
        lyrics: PreviewCatalog.lyrics,
        trackTitle: "Obsidian Sunrise",
        trackArtist: "Sireen",
        positionMs: 9_800,
        onClose: {}
    )
    .preferredColorScheme(.dark)
}
