import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.5 — TrackCoverArt: THE artwork component used everywhere.
//
// Port of ui/components/TrackCoverArt.kt. The Compose twin paints Coil's
// AsyncImage over a PERMANENT generative fallback layer (primary → secondary →
// generative downgrade, zero recompose churn). The native expression keeps the
// same contract: AsyncImage(url:) when artwork exists, and the deterministic
// seed-gradient generative cover — big music-note glyph + title initials — as
// the loading AND failure layer. Optional AmbientPalette tint harmonizes the
// art with the player's ambient mesh.
// ─────────────────────────────────────────────────────────────────────────────

public struct TrackCoverArt: View {

    public let title: String
    public let artist: String
    public let artworkUrl: String?
    public let size: CGFloat
    /// Card-shape radius (Android StreamifyShapes.CardShape / thumbnailMedium).
    public var cornerRadius: CGFloat
    /// Optional ambient tint washed over the art (player surfaces).
    public var palette: AmbientPalette?

    public init(
        title: String,
        artist: String = "",
        artworkUrl: String? = nil,
        size: CGFloat,
        cornerRadius: CGFloat = StreamifyRadius.md,
        palette: AmbientPalette? = nil
    ) {
        self.title = title
        self.artist = artist
        self.artworkUrl = artworkUrl
        self.size = size
        self.cornerRadius = cornerRadius
        self.palette = palette
    }

    public var body: some View {
        Group {
            if let urlString = artworkUrl, let url = URL(string: urlString) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFill()
                    } else {
                        // Loading AND failure both rest on the generative cover.
                        GenerativeCoverContent(title: title, artist: artist, size: size)
                    }
                }
            } else {
                GenerativeCoverContent(title: title, artist: artist, size: size)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay {
            if let palette {
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [palette.dominant, palette.accent],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .opacity(0.16)
                    .blendMode(.overlay)
                    .allowsHitTesting(false)
            }
        }
        .accessibilityLabel("\(title) cover")
    }
}

// MARK: - Generative fallback layer (Android GenerativeCoverContent)

/// Deterministic seed gradient + music-note glyph + title initials; a
/// permanent layer beneath the network image so a failed load never flashes.
private struct GenerativeCoverContent: View {

    let title: String
    let artist: String
    let size: CGFloat

    private var initials: String {
        let leading = title
            .split(separator: " ")
            .prefix(2)
            .compactMap(\.first)
            .map { String($0).uppercased() }
        return leading.joined()
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: StreamifyGradients.preset(forSeed: title),
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )

            // Soft studio glow so tiny sizes keep their depth.
            Rectangle()
                .fill(
                    RadialGradient(
                        colors: [.white.opacity(0.30), .clear],
                        center: UnitPoint(x: 0.28, y: 0.22),
                        startRadius: 0,
                        endRadius: max(size, 1) * 0.75
                    )
                )

            VStack(spacing: StreamifyDimens.spaceXXS) {
                Image(systemName: StreamifyIcon.musicNote)
                    .font(.system(size: max(18, size * 0.30), weight: .bold))
                    .foregroundStyle(.white.opacity(0.9))
                    .shadow(color: .black.opacity(0.25), radius: 3, y: 1)

                if !initials.isEmpty {
                    Text(initials)
                        .font(.system(size: max(9, size * 0.15), weight: .semibold))
                        .tracking(1.5)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(.white.opacity(0.66))
                        .padding(.horizontal, StreamifyDimens.spaceXS)
                }

                if !artist.isEmpty && artist != "Unknown" && size >= 64 {
                    Text(artist)
                        .font(.system(size: max(8, size * 0.11), weight: .medium))
                        .lineLimit(1)
                        .foregroundStyle(.white.opacity(0.55))
                        .padding(.horizontal, StreamifyDimens.spaceXS)
                }
            }
            .padding(StreamifyDimens.spaceXXS)
        }
    }
}

#Preview("Track cover art") {
    // Single-expression body: a `let` statement before the view leaves the
    // macro's () -> any View closure uninferrable (the UIKit Preview inits
    // then collide with SwiftUI's — the "ambiguous init(_:traits:body:)"
    // runner failure).
    VStack(spacing: 20) {
        HStack(spacing: 14) {
            TrackCoverArt(
                title: PreviewCatalog.tracks[0].title,
                artist: PreviewCatalog.tracks[0].artist,
                size: 150
            )
            TrackCoverArt(
                title: PreviewCatalog.tracks[1].title,
                artist: PreviewCatalog.tracks[1].artist,
                size: 48
            )
        }
        TrackCoverArt(
            title: PreviewCatalog.tracks[2].title,
            artist: PreviewCatalog.tracks[2].artist,
            size: 220,
            palette: PreviewCatalog.palette(forTitle: "Obsidian Sunrise")
        )
    }
    .padding(24)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
