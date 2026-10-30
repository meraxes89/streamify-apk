import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtLyricsHeader — the lyrics screen header (Android
// ui/components/yt/YtLyricsHeader.kt: chevron-down close, "Lyrics" title,
// source line and offset nudge controls). The compact Streamify port leads
// with the track title + artist and keeps the edit/share actions; the offset
// nudgers live with the lyrics editor surface, not the header.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtLyricsHeader: View {
    public var title: String
    public var artist: String
    public var onDismiss: () -> Void = {}
    public var onEdit: (() -> Void)? = nil
    public var onShare: (() -> Void)? = nil

    public init(
        title: String,
        artist: String,
        onDismiss: @escaping () -> Void = {},
        onEdit: (() -> Void)? = nil,
        onShare: (() -> Void)? = nil
    ) {
        self.title = title
        self.artist = artist
        self.onDismiss = onDismiss
        self.onEdit = onEdit
        self.onShare = onShare
    }

    public var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Button(action: onDismiss) {
                Image(systemName: StreamifyIcon.chevronDown)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textMain)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss lyrics")

            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(artist)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: StreamifyDimens.spaceSM)

            if let onEdit {
                iconButton("pencil", label: "Edit lyrics", action: onEdit)
            }
            if let onShare {
                iconButton(StreamifyIcon.share, label: "Share lyrics", action: onShare)
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .frame(height: 56)
    }

    private func iconButton(
        _ symbol: String,
        label: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(StreamifyColors.textSecondary)
                .frame(width: 32, height: 32)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

#Preview("Lyrics header") {
    YtLyricsHeader(
        title: PreviewCatalog.tracks[0].title,
        artist: PreviewCatalog.tracks[0].artist,
        onDismiss: {},
        onEdit: {},
        onShare: {}
    )
    .frame(maxWidth: 520)
    .background(StreamifyColors.bgBase)
}
