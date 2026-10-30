import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Profile wave) — UserProfileView: the listener profile surface.
//
// Native port of ui/screens/UserProfileScreen.kt's identity half: the hero
// (large gradient avatar with initials, display name, bio, admin chip with
// crown, "Private profile" label), the 3-chip stats row (total plays,
// listening hours, top genre), the "Your wrapped" teaser card (an inline
// brand-gradient card — YtWrappedHeroCard exposes no compact variant — that
// routes to the wrapped surface) and the top-track row with its play affordance.
// Pure props — the shell lowers auth/telemetry state onto them.
// Fixtures: PreviewCatalog.
// ─────────────────────────────────────────────────────────────────────────────

public struct UserProfileView: View {

    public var profile: UserProfileEntry
    public var onOpenWrapped: () -> Void
    public var onPlayTopTrack: () -> Void

    public init(
        profile: UserProfileEntry = PreviewCatalog.userProfile,
        onOpenWrapped: @escaping () -> Void = {},
        onPlayTopTrack: @escaping () -> Void = {}
    ) {
        self.profile = profile
        self.onOpenWrapped = onOpenWrapped
        self.onPlayTopTrack = onPlayTopTrack
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: StreamifyDimens.spaceLG) {
                hero
                statsRow
                wrappedTeaser
                topTrackRow
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Hero (avatar, name, chips, bio)

    private var hero: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            ZStack {
                Circle().fill(
                    StreamifyGradients.linear(StreamifyGradients.preset(forSeed: profile.displayName))
                )
                Text(String(profile.displayName.prefix(1)).uppercased())
                    .font(.system(size: 32, weight: .bold))
                    .foregroundStyle(Color.white)
            }
            .frame(width: 88, height: 88)
            Text(profile.displayName)
                .font(StreamifyType.headlineLarge)
                .foregroundStyle(StreamifyColors.textMain)
            if profile.isAdmin {
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Image(systemName: StreamifyIcon.crown)
                        .font(.system(size: 11))
                    Text("Admin")
                        .font(StreamifyType.caption.weight(.bold))
                        .tracking(1)
                }
                .foregroundStyle(StreamifyColors.primary)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(StreamifyColors.primary.opacity(0.14), in: StreamifyShapes.chip)
            }
            if profile.isPrivate {
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10))
                    Text("Private profile")
                        .font(StreamifyType.caption)
                }
                .foregroundStyle(StreamifyColors.textSecondary)
            }
            Text(profile.bio)
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, StreamifyDimens.spaceXL)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Stats row (plays · listening hours · top genre)

    private var statsRow: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            statChip(
                symbol: StreamifyIcon.play,
                value: Self.grouped(profile.totalPlays),
                label: "plays"
            )
            statChip(
                symbol: StreamifyIcon.clock,
                value: "\(Self.grouped(profile.listeningMinutes / 60)) h",
                label: "listening"
            )
            statChip(
                symbol: StreamifyIcon.eq,
                value: profile.favoriteGenre,
                label: "top genre"
            )
        }
    }

    private func statChip(symbol: String, value: String, label: String) -> some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceXXS) {
            HStack(spacing: StreamifyDimens.spaceXS) {
                Image(systemName: symbol)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(StreamifyColors.primary)
                Text(value)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            Text(label)
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
                .lineLimit(1)
        }
        .padding(StreamifyDimens.spaceMD)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(hoverable: false)
    }

    // MARK: - Wrapped teaser (inline brand-gradient card → wrapped surface)

    private var wrappedTeaser: some View {
        Button {
            Haptics.play(.scrubTick)
            onOpenWrapped()
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                ZStack {
                    Circle().fill(Color.white.opacity(0.18))
                    Image(systemName: StreamifyIcon.starFill)
                        .font(.system(size: 16))
                        .foregroundStyle(Color.white)
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Your wrapped")
                        .font(StreamifyType.titleMedium)
                        .foregroundStyle(Color.white)
                    Text("Your year in sound, replayed")
                        .font(StreamifyType.caption)
                        .foregroundStyle(Color.white.opacity(0.8))
                }
                Spacer(minLength: 0)
                Image(systemName: StreamifyIcon.chevronRight)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Color.white.opacity(0.7))
            }
            .padding(StreamifyDimens.spaceLG)
            .background(
                ZStack {
                    Rectangle().fill(StreamifyGradients.linear(StreamifyGradients.brand))
                    LinearGradient(
                        colors: [Color.black.opacity(0.10), Color.black.opacity(0.30)],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }
            )
            .clipShape(RoundedRectangle(cornerRadius: StreamifyRadius.lg, style: .continuous))
        }
        .buttonStyle(.pressableCard)
    }

    // MARK: - Top track row

    private var topTrackRow: some View {
        Button {
            Haptics.play(.playPauseClick)
            onPlayTopTrack()
        } label: {
            HStack(spacing: StreamifyDimens.spaceMD) {
                TrackCoverArt(title: profile.topTrack, artist: "", size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text("TOP TRACK")
                        .font(.system(size: 9, weight: .heavy))
                        .tracking(1)
                        .foregroundStyle(StreamifyColors.textTertiary)
                    Text(profile.topTrack)
                        .font(StreamifyType.songTitle)
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: StreamifyIcon.play)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(StreamifyColors.activeControl)
            }
            .padding(StreamifyDimens.spaceMD)
            .cardSurface(hoverable: false)
        }
        .buttonStyle(.pressableCard)
    }

    /// Locale-stable thousands grouping ("18432" → "18,432").
    private static func grouped(_ value: Int) -> String {
        let digits = String(value)
        var result = ""
        for (offset, character) in digits.enumerated() {
            if offset > 0 && (digits.count - offset) % 3 == 0 {
                result += ","
            }
            result.append(character)
        }
        return result
    }
}

#Preview("User profile") {
    UserProfileView()
}
