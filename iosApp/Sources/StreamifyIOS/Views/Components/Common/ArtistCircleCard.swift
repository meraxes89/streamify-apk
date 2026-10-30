import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.6 — ArtistCircleCard: circular artist avatar card.
//
// Port of ui/components/ArtistCircleCard.kt (avatar circle + name below, the
// artist-rail card). The Android original leaned on Coil or an empty clip; the
// native port renders the deterministic seed-gradient avatar with the artist's
// initial while pixels load, plus the optional verified checkmark medallion
// (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct ArtistCircleCard: View {

    public let name: String
    public let imageUrl: String?
    public var size: CGFloat
    public var isVerified: Bool
    public let onOpen: () -> Void

    public init(
        name: String,
        imageUrl: String? = nil,
        size: CGFloat = 96,
        isVerified: Bool = false,
        onOpen: @escaping () -> Void
    ) {
        self.name = name
        self.imageUrl = imageUrl
        self.size = size
        self.isVerified = isVerified
        self.onOpen = onOpen
    }

    public var body: some View {
        Button(action: onOpen) {
            VStack(spacing: StreamifyDimens.spaceSM) {
                avatar
                    .frame(width: size, height: size)
                    .clipShape(Circle())
                    .overlay(Circle().strokeBorder(StreamifyColors.borderSubtle, lineWidth: 1))
                    .overlay(alignment: .bottomTrailing) {
                        if isVerified {
                            verifiedBadge
                        }
                    }
                    .accessibilityLabel("Artist \(name)")

                Text(name)
                    .font(StreamifyType.cardTitle)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                    .multilineTextAlignment(.center)
            }
            .frame(width: size)
        }
        .buttonStyle(.pressableCard)
    }

    // MARK: Pieces

    /// AsyncImage when a URL exists; the seed-gradient + initial avatar is
    /// the loading AND failure layer.
    @ViewBuilder
    private var avatar: some View {
        if let urlString = imageUrl, let url = URL(string: urlString) {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image
                        .resizable()
                        .scaledToFill()
                } else {
                    gradientInitial
                }
            }
        } else {
            gradientInitial
        }
    }

    private var gradientInitial: some View {
        ZStack {
            Circle().fill(
                LinearGradient(
                    colors: StreamifyGradients.preset(forSeed: name),
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            Text(String(name.prefix(1)).uppercased())
                .font(.system(size: max(16, size * 0.34), weight: .bold))
                .foregroundStyle(.white.opacity(0.92))
        }
    }

    private var verifiedBadge: some View {
        let badgeSize = max(18, size * 0.28)
        return ZStack {
            Circle()
                .fill(StreamifyColors.bgBase)
                .frame(width: badgeSize + 4, height: badgeSize + 4)
            Circle()
                .fill(StreamifyColors.accentBlue)
                .frame(width: badgeSize, height: badgeSize)
            Image(systemName: StreamifyIcon.checkmark)
                .font(.system(size: badgeSize * 0.5, weight: .bold))
                .foregroundStyle(.white)
        }
        .accessibilityLabel("Verified")
    }
}

#Preview("Artist circle card") {
    HStack(spacing: 20) {
        ArtistCircleCard(name: "Aurora Nine", isVerified: true, onOpen: {})
        ArtistCircleCard(name: PreviewCatalog.tracks[3].artist, onOpen: {})
        ArtistCircleCard(name: "Vetra", isVerified: true, onOpen: {})
    }
    .padding(24)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
