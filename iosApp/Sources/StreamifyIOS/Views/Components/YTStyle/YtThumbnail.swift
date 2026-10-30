import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtThumbnail — the async rounded artwork thumbnail (Android
// ui/components/yt/YtThumbnail.kt). Keeps the original's list-optimized
// contract: the deterministic gradient + music-note placeholder is a
// permanent layer UNDER the pixels, so nothing is swapped in composition while
// loading (SwiftUI's AsyncImage gets plain EmptyView phases — no subview churn
// per cell), and every failure path falls back to the seeded gradient.
// Sizes/rounding come from the StreamifyShapes thumbnail tokens.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtThumbnail: View {
    public enum Size: CaseIterable {
        case small
        case medium
        case large

        var side: CGFloat {
            switch self {
            case .small: return StreamifyDimens.trackRowArt
            case .medium: return 96
            case .large: return StreamifyDimens.cardArtSize
            }
        }

        var shape: RoundedRectangle {
            switch self {
            case .small: return StreamifyShapes.thumbnailSmall
            case .medium: return StreamifyShapes.thumbnailMedium
            case .large: return StreamifyShapes.thumbnailLarge
            }
        }
    }

    public var url: String?
    public var seed: String
    public var size: Size = .medium
    public var label: String = ""

    public init(url: String?, seed: String, size: Size = .medium, label: String = "") {
        self.url = url
        self.seed = seed
        self.size = size
        self.label = label
    }

    public var body: some View {
        let side = size.side
        ZStack {
            StreamifyGradients.linear(StreamifyGradients.preset(forSeed: seed))
            Image(systemName: StreamifyIcon.musicNote)
                .font(.system(size: side * 0.4))
                .foregroundStyle(StreamifyColors.textTertiary)
            if let url, let remote = URL(string: url) {
                AsyncImage(url: remote) { phase in
                    switch phase {
                    case .empty:
                        EmptyView()
                    case .success(let image):
                        image
                            .resizable()
                            .scaledToFill()
                            .frame(width: side, height: side)
                            .clipped()
                    case .failure:
                        EmptyView()
                    @unknown default:
                        EmptyView()
                    }
                }
            }
        }
        .frame(width: side, height: side)
        .clipShape(size.shape)
        .accessibilityLabel(label.isEmpty ? "Track artwork" : label)
    }
}

#Preview("Thumbnails") {
    HStack(spacing: StreamifyDimens.spaceLG) {
        ForEach(Array(PreviewCatalog.tracks.prefix(3).enumerated()), id: \.element.id) { index, track in
            VStack(spacing: StreamifyDimens.spaceSM) {
                YtThumbnail(
                    url: track.artworkUrl,
                    seed: track.id,
                    size: YtThumbnail.Size.allCases[index % 3],
                    label: track.title
                )
                Text(track.title)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
        }
    }
    .padding(24)
    .background(StreamifyColors.bgBase)
}
