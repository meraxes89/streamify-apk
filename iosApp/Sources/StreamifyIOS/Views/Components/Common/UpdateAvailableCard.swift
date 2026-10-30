import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the in-app "update available" card.
//
// Port of ui/components/UpdateAvailableCard.kt: the crimson-accented update
// surface — version badge, the notes as bullets, the "Update" primary
// action + "Later" plain dismissal, and the optional downloading state (a
// determinate progress bar that disables the actions while the bytes land).
// Fixtures: PreviewCatalog.updates.
// ─────────────────────────────────────────────────────────────────────────────

public struct UpdateAvailableCard: View {
    public let version: String
    public let notes: [String]
    /// Non-nil = an update download is in flight (0…1); renders the progress
    /// bar and disables the actions.
    public var downloadProgress: Double? = nil
    public var onUpdate: (() -> Void)? = nil
    public var onLater: (() -> Void)? = nil

    public init(
        version: String,
        notes: [String],
        downloadProgress: Double? = nil,
        onUpdate: (() -> Void)? = nil,
        onLater: (() -> Void)? = nil
    ) {
        self.version = version
        self.notes = notes
        self.downloadProgress = downloadProgress
        self.onUpdate = onUpdate
        self.onLater = onLater
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            header
            noteBullets
            if let progress = downloadProgress {
                ProgressView(value: min(max(progress, 0), 1))
                    .tint(StreamifyColors.primary)
                    .padding(.vertical, StreamifyDimens.spaceXS)
            }
            actionRow
        }
        .padding(StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgSurfaceElevated, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(
                    LinearGradient(
                        colors: [StreamifyColors.primary, StreamifyColors.primary.opacity(0.4)],
                        startPoint: .leading,
                        endPoint: .trailing
                    ),
                    lineWidth: 1
                )
        )
    }

    private var header: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            Image(systemName: StreamifyIcon.cloudDownload)
                .font(.system(size: 18))
                .foregroundStyle(StreamifyColors.primary)
            Text("New Update Available")
                .font(StreamifyType.headlineMedium)
                .foregroundStyle(StreamifyColors.textMain)
            Spacer(minLength: 0)
            Text("Build \(version)")
                .font(StreamifyType.chipText.weight(.bold))
                .monospacedDigit()
                .foregroundStyle(StreamifyColors.primary)
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(StreamifyColors.primary.opacity(0.15), in: StreamifyShapes.chip)
        }
    }

    private var noteBullets: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
            ForEach(notes, id: \.self) { note in
                HStack(alignment: .firstTextBaseline, spacing: StreamifyDimens.spaceSM) {
                    Image(systemName: StreamifyIcon.tag)
                        .font(.system(size: 9))
                        .foregroundStyle(StreamifyColors.textTertiary)
                        .padding(.top, 2)
                    Text(note)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
            }
        }
    }

    private var actionRow: some View {
        HStack {
            Button {
                onLater?()
            } label: {
                Text("Later")
                    .font(StreamifyType.bodyMedium)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .padding(.horizontal, StreamifyDimens.spaceSM)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .disabled(downloadProgress != nil)

            Spacer(minLength: 0)

            Button {
                Haptics.play(.queueDrop)
                onUpdate?()
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: StreamifyIcon.download)
                        .font(.system(size: 13, weight: .bold))
                    Text(downloadProgress != nil ? "Downloading…" : "Update")
                        .font(StreamifyType.bodyMedium.weight(.bold))
                        .lineLimit(1)
                }
                .foregroundStyle(StreamifyColors.textOnActiveChip)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(StreamifyColors.activeControl, in: StreamifyShapes.actionPill)
            }
            .buttonStyle(.pressableCard)
            .disabled(downloadProgress != nil)
        }
    }
}

#Preview {
    VStack(spacing: 20) {
        UpdateAvailableCard(
            version: PreviewCatalog.updates[0].version,
            notes: PreviewCatalog.updates[0].notes,
            onUpdate: {},
            onLater: {}
        )
        UpdateAvailableCard(
            version: PreviewCatalog.updates[1].version,
            notes: PreviewCatalog.updates[1].notes,
            downloadProgress: 0.62,
            onUpdate: {},
            onLater: {}
        )
    }
    .padding()
    .background(StreamifyColors.bgBase)
}
