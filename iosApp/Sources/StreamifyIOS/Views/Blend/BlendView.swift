import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Social wave) — BlendView: the shared Blend playlist surface.
//
// Native port of ui/screens/BlendScreen.kt: the split-avatar header (two
// overlapping member circles, each tinted by its BlendProfile accent, over the
// brand gradient), the taste-match card (a ring gauge animating to
// matchPercent with Motion.fluid), the shared playlist of TrackListItem rows
// with per-row provenance dots (whose taste surfaced the track — derived
// deterministically from index parity), the dashed "Add songs" row and the
// "Regenerate blend" chip. Pure props — the shell lowers BlendViewModel state.
// Fixtures: PreviewCatalog.
// ─────────────────────────────────────────────────────────────────────────────

public struct BlendView: View {

    public var profiles: [BlendProfile]
    public var match: BlendMatch
    public var tracks: [VirtualShelfTrack]
    public var onPlayTrack: (VirtualShelfTrack) -> Void
    public var onRegenerate: () -> Void
    public var onAddSongs: () -> Void

    public init(
        profiles: [BlendProfile] = PreviewCatalog.blendProfiles,
        match: BlendMatch = PreviewCatalog.blendMatch,
        tracks: [VirtualShelfTrack] = Array(PreviewCatalog.tracks[0..<6]),
        onPlayTrack: @escaping (VirtualShelfTrack) -> Void,
        onRegenerate: @escaping () -> Void = {},
        onAddSongs: @escaping () -> Void = {}
    ) {
        self.profiles = profiles
        self.match = match
        self.tracks = tracks
        self.onPlayTrack = onPlayTrack
        self.onRegenerate = onRegenerate
        self.onAddSongs = onAddSongs
    }

    private var friendName: String { profiles.first?.name ?? "Mira" }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: StreamifyDimens.spaceLG) {
                hero
                matchCard
                sectionHeader("SHARED PLAYLIST · \(tracks.count)")
                ForEach(Array(tracks.enumerated()), id: \.element.id) { index, track in
                    HStack(spacing: StreamifyDimens.spaceSM) {
                        ProvenanceDots(profiles: profiles, index: index)
                        TrackListItem(track: track, onPlay: { onPlayTrack(track) })
                    }
                    .padding(.horizontal, StreamifyDimens.spaceLG)
                }
                addSongsRow
                regenerateButton
            }
            .padding(.vertical, StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .font(StreamifyType.caption.weight(.bold))
            .tracking(1)
            .foregroundStyle(StreamifyColors.textSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    /// Split-avatar hero: two 56 pt circles offset ±12 pt over the brand gradient.
    private var hero: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            SplitAvatars(profiles: profiles)
            Text(profiles.map(\.name).joined(separator: " & "))
                .font(StreamifyType.headlineMedium)
                .foregroundStyle(Color.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text("BLEND")
                .font(StreamifyType.caption.weight(.bold))
                .tracking(2)
                .foregroundStyle(Color.white.opacity(0.75))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, StreamifyDimens.spaceXL)
        .background(
            ZStack {
                Rectangle().fill(StreamifyGradients.linear(StreamifyGradients.brand))
                LinearGradient(
                    colors: [Color.black.opacity(0.08), Color.black.opacity(0.30)],
                    startPoint: .top, endPoint: .bottom
                )
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    /// Taste-match card: the animated ring gauge + shared-track count line.
    private var matchCard: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            MatchGauge(percent: match.matchPercent)
                .frame(width: 140, height: 140)
            Text("You and \(friendName) share \(match.sharedTrackCount) tracks")
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, StreamifyDimens.spaceLG)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, StreamifyDimens.spaceLG)
        .cardSurface(hoverable: false)
        .padding(.horizontal, StreamifyDimens.spaceLG)
    }

    /// The dashed "Add songs" row (Android's Add Songs affordance).
    private var addSongsRow: some View {
        Button {
            Haptics.play(.queueGrab)
            onAddSongs()
        } label: {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: StreamifyIcon.plusCircle)
                    .font(.system(size: 14))
                    .foregroundStyle(StreamifyColors.primary)
                Text("Add songs")
                    .font(StreamifyType.chipText)
                    .foregroundStyle(StreamifyColors.textMain)
                Spacer(minLength: 0)
            }
            .padding(StreamifyDimens.spaceMD)
            .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
            .overlay(
                StreamifyShapes.card.strokeBorder(
                    StreamifyColors.borderChip, style: StrokeStyle(lineWidth: 1.2, dash: [5, 4])
                )
            )
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
        .buttonStyle(.pressableCard)
    }

    /// "Regenerate blend" — the secondary chip.
    private var regenerateButton: some View {
        Button {
            Haptics.play(.scrubTick)
            onRegenerate()
        } label: {
            HStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: StreamifyIcon.refresh).font(.system(size: 13, weight: .semibold))
                Text("Regenerate blend").font(StreamifyType.chipText)
            }
            .foregroundStyle(StreamifyColors.textMain)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .chipSurface(active: false)
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
        .buttonStyle(.pressableCard)
    }
}

// MARK: - Split avatar pair (Android AvatarSlideIn + BlendAvatar)

private struct SplitAvatars: View {

    let profiles: [BlendProfile]
    private static let avatarSize: CGFloat = 56

    private func accent(_ index: Int) -> Color {
        profiles.indices.contains(index) ? Color(streamifyHex: profiles[index].accentHex) : StreamifyColors.primary
    }

    private func initial(_ index: Int) -> String {
        let name = profiles.indices.contains(index) ? profiles[index].name : "?"
        return String(name.prefix(1)).uppercased()
    }

    var body: some View {
        ZStack {
            avatarCircle(fill: accent(0), label: initial(0)).offset(x: -12)
            avatarCircle(fill: accent(1), label: initial(1)).offset(x: 12)
        }
        .frame(width: 120, height: Self.avatarSize)
    }

    /// One accent circle, white hairline ring and centered initial — ring and
    /// label travel with the circle as it offsets.
    private func avatarCircle(fill: Color, label: String) -> some View {
        ZStack {
            Circle().fill(fill)
            Text(label)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(Color.white)
        }
        .frame(width: Self.avatarSize, height: Self.avatarSize)
        .overlay(Circle().strokeBorder(Color.white, lineWidth: 2))
    }
}

// MARK: - Match ring gauge (Android counting score, native fluid trim)

private struct MatchGauge: View {

    let percent: Int
    @State private var revealed = false

    private var fraction: Double { min(max(Double(percent) / 100.0, 0.0), 1.0) }

    var body: some View {
        ZStack {
            Circle().stroke(StreamifyColors.borderSubtle, lineWidth: 10)
            Circle()
                .trim(from: 0, to: revealed ? fraction : 0)
                .stroke(StreamifyColors.primary, style: StrokeStyle(lineWidth: 10, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 2) {
                Text("\(percent)%")
                    .font(.system(size: 30, weight: .black))
                    .foregroundStyle(StreamifyColors.textMain)
                    .monospacedDigit()
                Text("TASTE MATCH")
                    .font(.system(size: 9, weight: .heavy))
                    .tracking(1.5)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
        }
        .onAppear { withAnimation(Motion.fluid) { revealed = true } }
    }
}

// MARK: - Provenance dots (Android BlendTrackRow member dot pair)

private struct ProvenanceDots: View {

    let profiles: [BlendProfile]
    let index: Int

    private func accent(_ position: Int) -> Color {
        profiles.indices.contains(position) ? Color(streamifyHex: profiles[position].accentHex) : StreamifyColors.primary
    }

    var body: some View {
        VStack(spacing: 3) {
            Circle().fill(accent(0)).opacity(index % 2 == 0 ? 1 : 0.25)
            Circle().fill(accent(1)).opacity(index % 2 == 0 ? 0.25 : 1)
        }
        .frame(width: 8)
    }
}

#Preview("Blend") {
    BlendView(onPlayTrack: { _ in })
}
