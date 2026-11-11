import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Profile wave) — UserProfileView: the listener profile surface.
//
// Native port of ui/screens/UserProfileScreen.kt's identity half: the hero
// (large gradient avatar with initials — the engine avatar overlays it once
// loaded — display name, bio, "Private profile" label), the 3-chip stats
// row (followers, following, top genre), the listening chronotype card
// (derived from the local hour) and the "Your wrapped" teaser card that
// routes to the wrapped surface.
//
// WS-B B2 (de-fixturing): the screen OWNS its UserProfileViewModel and
// rides the live feature lane (`AppServices.library` → UniFFIFeatureLibrary
// when the engine is linked) — every value renders the VM's engine
// projections (the fixture-only admin chip and top-track row left the
// screen with their PreviewCatalog source; the engine profile contract
// carries neither). Previews inject a scripted PreviewFeatureLibrary.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
public struct UserProfileView: View {

    /// WS-B B2: the screen's own state holder — constructed bare, it rides
    /// the live feature lane automatically (`load(userId: nil)` = the local
    /// user's own profile; a non-nil id renders another listener's public
    /// page).
    @State private var model = UserProfileViewModel()

    public var onOpenWrapped: () -> Void
    public var onPlayTopTrack: () -> Void

    public init(
        onOpenWrapped: @escaping () -> Void = {},
        onPlayTopTrack: @escaping () -> Void = {}
    ) {
        self.onOpenWrapped = onOpenWrapped
        self.onPlayTopTrack = onPlayTopTrack
    }

    /// Preview/test lane: inject a scripted view model (the bare init above
    /// rides `AppServices.library`).
    init(
        model: UserProfileViewModel,
        onOpenWrapped: @escaping () -> Void = {},
        onPlayTopTrack: @escaping () -> Void = {}
    ) {
        self._model = State(initialValue: model)
        self.onOpenWrapped = onOpenWrapped
        self.onPlayTopTrack = onPlayTopTrack
    }

    public var body: some View {
        ScrollView {
            LazyVStack(spacing: StreamifyDimens.spaceLG) {
                if model.isLoading {
                    loadingPlaceholder
                } else {
                    hero
                    statsRow
                    chronotypeCard
                    wrappedTeaser
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceLG)
        }
        .background(StreamifyColors.bgBase)
        .task { model.load(userId: nil) }
    }

    // MARK: - Hero (avatar, name, chips, bio)

    /// Gradient initials plate; the engine-reported avatar overlays it once
    /// loaded (nil on today's lane → the calm initials remain).
    private var avatar: some View {
        ZStack {
            Circle().fill(
                StreamifyGradients.linear(StreamifyGradients.preset(forSeed: model.displayName))
            )
            Text(String(model.displayName.prefix(1)).uppercased())
                .font(.system(size: 32, weight: .bold))
                .foregroundStyle(Color.white)
        }
        .frame(width: 88, height: 88)
        .overlay {
            if let url = model.avatarUrl.flatMap({ URL(string: $0) }) {
                AsyncImage(url: url) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFill()
                            .frame(width: 88, height: 88)
                            .clipShape(Circle())
                    }
                }
            }
        }
    }

    private var hero: some View {
        VStack(spacing: StreamifyDimens.spaceMD) {
            avatar
            Text(model.displayName)
                .font(StreamifyType.headlineLarge)
                .foregroundStyle(StreamifyColors.textMain)
            if model.isPrivate {
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 10))
                    Text("Private profile")
                        .font(StreamifyType.caption)
                }
                .foregroundStyle(StreamifyColors.textSecondary)
            }
            Text(model.bio)
                .font(StreamifyType.bodySmall)
                .foregroundStyle(StreamifyColors.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, StreamifyDimens.spaceXL)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Stats row (followers · following · top genre)

    private var statsRow: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            statChip(
                symbol: StreamifyIcon.friends,
                value: model.followerCountLabel,
                label: "followers"
            )
            statChip(
                symbol: StreamifyIcon.personPlus,
                value: model.followingCountLabel,
                label: "following"
            )
            statChip(
                symbol: StreamifyIcon.eq,
                value: model.favoriteGenre,
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

    // MARK: - Chronotype card (derived from the local listening hour)

    private var chronotypeCard: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            Text(model.chronotype.emoji)
                .font(.system(size: 28))
            VStack(alignment: .leading, spacing: 2) {
                Text(model.chronotype.name)
                    .font(StreamifyType.titleMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(model.chronotype.detail)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            Spacer(minLength: 0)
            Text(model.listeningSummary)
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textTertiary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(StreamifyDimens.spaceMD)
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

    // MARK: - Loading

    /// The load-in-flight state — a redacted hero + stat-chip row.
    private var loadingPlaceholder: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            Circle()
                .fill(StreamifyColors.bgChipInactive)
                .frame(width: 88, height: 88)
            RoundedRectangle(cornerRadius: StreamifyRadius.sm, style: .continuous)
                .fill(StreamifyColors.bgChipInactive)
                .frame(width: 140, height: 18)
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(0..<3, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: StreamifyRadius.md, style: .continuous)
                        .fill(StreamifyColors.bgCard)
                        .frame(height: 64)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .padding(.top, StreamifyDimens.spaceXL)
        .redacted(reason: .placeholder)
        .accessibilityLabel("Loading profile")
    }
}

#Preview("User profile") {
    let scripted = PreviewFeatureLibrary()
    scripted.profileContent = UserProfileSummary(
        userId: "u1",
        displayName: "Sireen",
        bio: "Developer by day, audiophile by night.",
        followerCount: 1_284,
        followingCount: 312,
        favoriteGenre: "Ambient Techno",
        listeningMinutes: 92_640,
        topGenre: "Ambient Techno"
    )
    return NavigationStack {
        UserProfileView(model: UserProfileViewModel(library: scripted))
    }
}

#Preview("User profile — empty") {
    NavigationStack {
        UserProfileView()
    }
}
