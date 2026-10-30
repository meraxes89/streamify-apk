import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.15 — RelatedDiscoverSheet: the "Related / discover" panel
// (sheet content).
//
// Port of ui/components/RelatedDiscoverSheet.kt (ModalBottomSheet: start-radio
// action, more-from-artist rail, similar-song rows). The Compose twin fetches
// radio candidates through UniversalCandidateBroker; the native port keeps the
// panel PURE — Engineer 4 lowers the discovery data onto the props, the sheet
// owns only presentation (chips rail, three recommended rows, start-radio).
// ─────────────────────────────────────────────────────────────────────────────

public struct RelatedDiscoverSheet: View {
    public let track: VirtualShelfTrack
    public let similarArtists: [String]
    public let recommendations: [VirtualShelfTrack]
    public let onStartRadio: () -> Void
    public let onTrackTap: (VirtualShelfTrack) -> Void
    public let onDismiss: () -> Void

    public init(
        track: VirtualShelfTrack,
        similarArtists: [String],
        recommendations: [VirtualShelfTrack],
        onStartRadio: @escaping () -> Void,
        onTrackTap: @escaping (VirtualShelfTrack) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.track = track
        self.similarArtists = similarArtists
        self.recommendations = recommendations
        self.onStartRadio = onStartRadio
        self.onTrackTap = onTrackTap
        self.onDismiss = onDismiss
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
            header

            Button(action: onStartRadio) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    Image(systemName: StreamifyIcon.radio)
                        .font(.system(size: 15, weight: .semibold))
                    Text("Start \(track.title) Radio")
                        .font(StreamifyType.titleSmall)
                        .lineLimit(1)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, StreamifyDimens.spaceMD)
                .background(Capsule().fill(StreamifyColors.primary))
            }
            .buttonStyle(.pressableCard)

            if !similarArtists.isEmpty {
                artistsRail
            }

            if recommendations.isEmpty {
                Text("No related tracks found for this song")
                    .font(StreamifyType.bodySmall)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, StreamifyDimens.spaceGiant)
            } else {
                VStack(spacing: StreamifyDimens.spaceXS) {
                    Text("Recommended")
                        .font(StreamifyType.titleMedium)
                        .foregroundStyle(StreamifyColors.textMain)

                    ForEach(recommendations) { recommendation in
                        RelatedTrackRow(track: recommendation) {
                            onTrackTap(recommendation)
                        }
                    }
                }
            }
        }
        .padding(StreamifyDimens.spaceLG)
    }

    // MARK: Header

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Related")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text("Discoveries based on \(track.title)")
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer()
            Button(action: onDismiss) {
                Image(systemName: StreamifyIcon.close)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textMain)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close related panel")
        }
    }

    // MARK: Similar-artists chips row

    private var artistsRail: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            Text("Similar artists")
                .font(StreamifyType.titleMedium)
                .foregroundStyle(StreamifyColors.textMain)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: StreamifyDimens.spaceSM) {
                    ForEach(similarArtists, id: \.self) { artist in
                        HStack(spacing: 6) {
                            Image(systemName: StreamifyIcon.avatar)
                                .font(.system(size: 12))
                                .foregroundStyle(StreamifyColors.textSecondary)
                            Text(artist)
                                .font(StreamifyType.chipText)
                                .foregroundStyle(StreamifyColors.textMain)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, StreamifyDimens.spaceMD)
                        .padding(.vertical, 7)
                        .chipSurface(active: false)
                    }
                }
            }
        }
    }
}

// MARK: - Recommended row (inline)

private struct RelatedTrackRow: View {
    let track: VirtualShelfTrack
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: StreamifyDimens.spaceMD) {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: StreamifyGradients.preset(forSeed: track.title),
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 48, height: 48)
                    .overlay(
                        Image(systemName: StreamifyIcon.musicNote)
                            .font(.system(size: 16))
                            .foregroundStyle(.white.opacity(0.85))
                    )

                VStack(alignment: .leading, spacing: 2) {
                    Text(track.title)
                        .font(StreamifyType.titleSmall)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    Text(track.artist)
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }

                Spacer(minLength: StreamifyDimens.spaceSM)

                Text(PlaybackFormatting.formatPlaybackTime(track.durationSec * 1_000))
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
            }
            .padding(.vertical, StreamifyDimens.spaceXS)
            .contentShape(Rectangle())
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Related discover sheet") {
    RelatedDiscoverSheet(
        track: PreviewCatalog.tracks[0],
        similarArtists: ["Aurora Nine", "Vetra", "Mirabel", "DAC Kingsley"],
        recommendations: Array(PreviewCatalog.tracks[1...3]),
        onStartRadio: {},
        onTrackTap: { _ in },
        onDismiss: {}
    )
    .glassPanel(StreamifyMaterials.sheet, cornerRadius: StreamifyRadius.xxl)
    .padding(20)
    .frame(maxWidth: 480)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
