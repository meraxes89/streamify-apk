import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the community LRC lyrics editor, sheet content.
//
// Native port of ui/components/LyricsEditorDialog.kt: the "Contribute
// Lyrics" dialog as sheet CONTENT — track header, a TextEditor bound to the
// draft LRC state, the format hint footer, and Save / Discard actions wired
// to callbacks (the Android sheet posts through CommunityViewModel; here the
// host owns submission). Fixtures: PreviewCatalog.tracks.
// ─────────────────────────────────────────────────────────────────────────────

public struct LyricsEditorDialog: View {
    public let track: VirtualShelfTrack
    public var onSave: ((String) -> Void)? = nil
    public var onDiscard: (() -> Void)? = nil

    @State private var lrcText = ""

    public init(
        track: VirtualShelfTrack,
        onSave: ((String) -> Void)? = nil,
        onDiscard: (() -> Void)? = nil
    ) {
        self.track = track
        self.onSave = onSave
        self.onDiscard = onDiscard
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Contribute Lyrics")
                        .font(StreamifyType.headlineSmall)
                        .foregroundStyle(StreamifyColors.textMain)
                    Text("\(track.title) · \(track.artist)")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.primary)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: StreamifyIcon.lyrics)
                    .font(.system(size: 18))
                    .foregroundStyle(StreamifyColors.textTertiary)
            }

            TextEditor(text: $lrcText)
                .font(StreamifyType.bodyMedium.monospaced())
                .foregroundStyle(StreamifyColors.textMain)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 170)
                .padding(StreamifyDimens.spaceSM)
                .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
                .overlay(
                    StreamifyShapes.card.strokeBorder(
                        lrcText.isEmpty ? StreamifyColors.borderSubtle : StreamifyColors.primary.opacity(0.6),
                        lineWidth: 1
                    )
                )

            Text("Paste synced LRC lines — [mm:ss.xx] lyric text — one timestamped line per phrase.")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)

            HStack(spacing: StreamifyDimens.spaceMD) {
                Button {
                    Haptics.play(.scrubTick)
                    onDiscard?()
                } label: {
                    Text("Discard")
                        .font(StreamifyType.bodyMedium)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)

                Button {
                    let text = lrcText.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    Haptics.play(.queueDrop)
                    onSave?(text)
                } label: {
                    Text("Save")
                        .font(StreamifyType.bodyMedium.weight(.bold))
                        .foregroundStyle(StreamifyColors.textOnActiveChip)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                        .background(StreamifyColors.primary, in: StreamifyShapes.searchBar)
                }
                .buttonStyle(.pressableCard)
                .disabled(lrcText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(StreamifyDimens.spaceLG)
        .glassPanel()
        .frame(maxWidth: 440)
    }
}

#Preview {
    LyricsEditorDialog(
        track: PreviewCatalog.tracks[1],
        onSave: { _ in },
        onDiscard: {}
    )
    .padding(StreamifyDimens.spaceLG)
    .background(StreamifyColors.bgBase)
}
