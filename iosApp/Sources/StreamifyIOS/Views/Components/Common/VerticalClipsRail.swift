import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.16 — Vertical clips: rail, card, feed sheet.
//
// Port of ui/components/VerticalClipsRail.kt (the 30s vertical-clip discovery
// surfaces). The Android twin drives ExoPlayer decoders; this wave's port
// renders the poster layer (gradient poster + play glyph + duration chip) and
// the immersive feed surface — video decode stays on the engine lane. Tapping
// a rail card presents VerticalClipsFeedSheet as a full-height sheet
// (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Rail

public struct VerticalClipsRail: View {

    public let clips: [ArtistClipModel]

    /// The clip the feed should open on, presented as a sheet.
    @State private var selectedClip: ArtistClipModel?

    public init(clips: [ArtistClipModel]) {
        self.clips = clips
    }

    public var body: some View {
        if !clips.isEmpty {
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
                HStack(spacing: 6) {
                    Image(systemName: StreamifyIcon.video)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(StreamifyColors.successGreen)
                    Text("Clips")
                        .font(StreamifyType.headlineSmall)
                        .foregroundStyle(StreamifyColors.textMain)
                    Text("\(clips.count) verticals")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textTertiary)
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(clips) { clip in
                            VerticalClipCard(clip: clip) {
                                selectedClip = clip
                            }
                        }
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                }
            }
            .sheet(item: $selectedClip) { clip in
                VerticalClipsFeedSheet(clip: clip) {
                    selectedClip = nil
                }
            }
        }
    }
}

// MARK: - Card

public struct VerticalClipCard: View {

    public let clip: ArtistClipModel
    public var width: CGFloat
    public let onTap: () -> Void

    public init(
        clip: ArtistClipModel,
        width: CGFloat = 118,
        onTap: @escaping () -> Void
    ) {
        self.clip = clip
        self.width = width
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            ZStack {
                Rectangle()
                    .fill(
                        LinearGradient(
                            colors: StreamifyGradients.preset(forSeed: clip.title),
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )

                // Center play affordance.
                ZStack {
                    Circle()
                        .fill(Color.black.opacity(0.42))
                        .frame(width: 30, height: 30)
                    Image(systemName: StreamifyIcon.play)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                }

                // Bottom scrim + metadata.
                VStack(alignment: .leading, spacing: 2) {
                    Spacer()
                    LinearGradient(
                        colors: [.clear, Color.black.opacity(0.85)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 44)
                    .overlay(alignment: .bottomLeading) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(clip.title)
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(.white)
                                .lineLimit(2)
                            Text(clip.artistName)
                                .font(.system(size: 9, weight: .medium))
                                .foregroundStyle(.white.opacity(0.75))
                                .lineLimit(1)
                        }
                        .padding(8)
                    }
                }

                // Duration chip.
                VStack {
                    HStack {
                        Spacer()
                        Text("\(clip.durationSec)s")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 3)
                            .background(Capsule().fill(Color.black.opacity(0.6)))
                            .padding(6)
                    }
                    Spacer()
                }
            }
            .frame(width: width, height: width * 16 / 9)
            .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))
            .accessibilityLabel("Play clip \(clip.title)")
        }
        .buttonStyle(.pressableCard)
    }
}

// MARK: - Feed sheet (full-height sheet content)

public struct VerticalClipsFeedSheet: View {

    public let clip: ArtistClipModel
    public let onClose: () -> Void

    public init(clip: ArtistClipModel, onClose: @escaping () -> Void) {
        self.clip = clip
        self.onClose = onClose
    }

    public var body: some View {
        ZStack(alignment: .top) {
            Color.black.ignoresSafeArea()

            VStack(spacing: StreamifyDimens.spaceLG) {
                HStack {
                    Text("Clips")
                        .font(StreamifyType.headlineSmall)
                        .foregroundStyle(.white)
                    Spacer()
                    Button(action: onClose) {
                        Image(systemName: StreamifyIcon.close)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(Circle().fill(Color.white.opacity(0.14)))
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Close clips")
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
                .padding(.top, StreamifyDimens.spaceMD)

                VerticalClipCard(clip: clip, width: 262, onTap: {})

                Spacer(minLength: 0)

                HStack(spacing: 8) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.85))
                    Text("Swipe up for more")
                        .font(StreamifyType.caption)
                        .foregroundStyle(.white.opacity(0.75))
                }
                .padding(.bottom, StreamifyDimens.spaceXXL)
            }
        }
    }
}

#Preview("Vertical clips rail") {
    VStack {
        Spacer()
        VerticalClipsRail(clips: PreviewCatalog.artistClips)
        Spacer()
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}

#Preview("Vertical clips feed sheet") {
    VerticalClipsFeedSheet(clip: PreviewCatalog.artistClips[0], onClose: {})
}
