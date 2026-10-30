import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtTopResultCard — the search "Top result" card. Port of
// ui/components/yt/YtTopResultCard.kt: 80 pt art, bold title, uploader + type
// badge with the verified checkmark, and the ActiveControl play affordance.
// Artwork uses the deterministic preset gradient (fixtures ship no URLs).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtTopResultCard: View {
    public var result: OnlineSearchResult
    public var onOpen: () -> Void

    public init(result: OnlineSearchResult, onOpen: @escaping () -> Void) {
        self.result = result
        self.onOpen = onOpen
    }

    public var body: some View {
        Button(action: onOpen) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                artwork
                VStack(alignment: .leading, spacing: 3) {
                    typeRow
                    Text(result.title)
                        .font(StreamifyType.headlineMedium)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    Text(result.uploader)
                        .font(StreamifyType.songArtist)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
                Spacer(minLength: StreamifyDimens.spaceMD)
                playBadge
            }
            .padding(14)
            .background(StreamifyColors.bgSurfaceElevated, in: StreamifyShapes.thumbnailLarge)
        }
        .buttonStyle(.pressableCard)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    private var typeRow: some View {
        HStack(spacing: StreamifyDimens.spaceXXS) {
            Text(result.type.label.uppercased())
                .font(StreamifyType.caption.weight(.bold))
                .tracking(0.8)
                .foregroundStyle(StreamifyColors.textSecondary)
            if result.isVerified {
                Image(systemName: StreamifyIcon.checkmarkCircle)
                    .font(.system(size: 12))
                    .foregroundStyle(StreamifyColors.accentBlue)
            }
        }
    }

    private var artwork: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: result.title)))
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: 26))
                .foregroundStyle(Color.white.opacity(0.85))
        }
        .frame(width: 80, height: 80)
    }

    private var playBadge: some View {
        ZStack {
            Circle().fill(StreamifyColors.activeControl)
            Image(systemName: StreamifyIcon.play)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(StreamifyColors.textOnActiveChip)
        }
        .frame(width: 46, height: 46)
    }
}

#Preview("Top result") {
    ScrollView {
        if let top = PreviewCatalog.onlineResults.first {
            YtTopResultCard(result: top, onOpen: {})
                .padding(.top, StreamifyDimens.spaceLG)
        }
    }
    .background(StreamifyColors.bgBase)
}
