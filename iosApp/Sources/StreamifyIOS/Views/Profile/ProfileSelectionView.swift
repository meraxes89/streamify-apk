import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Profile wave) — ProfileSelectionView: "Choose your profile".
//
// Native port of ui/screens/ProfileSelectionScreen.kt (mapped onto the local
// ProfileAccount currency): a 2-column grid of profile cards — gradient
// initials avatar, name, email caption, AppMode badge chip with the
// brand-colored mode dot, and the activeControl selection ring + checkmark —
// plus the dashed "Add account" card and the SireenBrandingBadge footer.
// Pure props — the shell lowers its account store onto them.
// Fixtures: PreviewCatalog.
// ─────────────────────────────────────────────────────────────────────────────

public struct ProfileSelectionView: View {

    public var profiles: [ProfileAccount]
    public var selectedId: String?
    public var onSelect: (ProfileAccount) -> Void
    public var onAddAccount: () -> Void

    public init(
        profiles: [ProfileAccount] = PreviewCatalog.profiles,
        selectedId: String? = "p1",
        onSelect: @escaping (ProfileAccount) -> Void = { _ in },
        onAddAccount: @escaping () -> Void = {}
    ) {
        self.profiles = profiles
        self.selectedId = selectedId
        self.onSelect = onSelect
        self.onAddAccount = onAddAccount
    }

    public var body: some View {
        VStack(spacing: StreamifyDimens.spaceLG) {
            VStack(spacing: StreamifyDimens.spaceXS) {
                Text("Choose your profile")
                    .font(StreamifyType.headlineMedium)
                    .foregroundStyle(StreamifyColors.textMain)
                Text("One tap switches the whole engine over.")
                    .font(StreamifyType.bodySmall)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            ScrollView {
                LazyVGrid(
                    columns: [
                        GridItem(.flexible(), spacing: StreamifyDimens.spaceMD),
                        GridItem(.flexible(), spacing: StreamifyDimens.spaceMD),
                    ],
                    spacing: StreamifyDimens.spaceMD
                ) {
                    ForEach(profiles) { profile in
                        ProfileCard(profile: profile, isSelected: profile.id == selectedId) {
                            Haptics.play(.scrubTick)
                            onSelect(profile)
                        }
                    }
                    addAccountCard
                }
                .padding(.horizontal, StreamifyDimens.spaceLG)
                .padding(.top, StreamifyDimens.spaceXS)
                .padding(.bottom, StreamifyDimens.spaceLG)
            }
            SireenBrandingBadge()
                .padding(.bottom, StreamifyDimens.spaceMD)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Add account (dashed card)

    private var addAccountCard: some View {
        Button {
            Haptics.play(.queueGrab)
            onAddAccount()
        } label: {
            VStack(spacing: StreamifyDimens.spaceSM) {
                Image(systemName: StreamifyIcon.plus)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(StreamifyColors.textSecondary)
                Text("Add account")
                    .font(StreamifyType.chipText)
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 136)
            .background(StreamifyColors.bgCard, in: StreamifyShapes.card)
            .overlay(
                StreamifyShapes.card.strokeBorder(
                    StreamifyColors.borderChip,
                    style: StrokeStyle(lineWidth: 1.2, dash: [5, 4])
                )
            )
        }
        .buttonStyle(.pressableCard)
    }
}

// MARK: - One profile card (Android BrandedProfileCard, local-currency port)

private struct ProfileCard: View {

    let profile: ProfileAccount
    let isSelected: Bool
    let onSelect: () -> Void

    /// Brand dot per mode (exhaustive over AppMode).
    private var modeTint: Color {
        switch profile.mode {
        case .streamify: return StreamifyColors.primary
        case .spotify: return StreamifyColors.successGreen
        case .youtubeMusic: return StreamifyColors.primaryAccent
        }
    }

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: StreamifyDimens.spaceSM) {
                ZStack {
                    Circle().fill(
                        StreamifyGradients.linear(StreamifyGradients.preset(forSeed: profile.displayName))
                    )
                    Text(String(profile.displayName.prefix(1)).uppercased())
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(Color.white)
                }
                .frame(width: 56, height: 56)
                Text(profile.displayName)
                    .font(StreamifyType.titleSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                    .lineLimit(1)
                Text(profile.email)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.textTertiary)
                    .lineLimit(1)
                HStack(spacing: StreamifyDimens.spaceXS) {
                    Circle()
                        .fill(modeTint)
                        .frame(width: 6, height: 6)
                    Text(profile.mode.label)
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(StreamifyDimens.spaceMD)
            .cardSurface(hoverable: false)
            .overlay(
                StreamifyShapes.card.strokeBorder(
                    isSelected ? StreamifyColors.activeControl : Color.clear,
                    lineWidth: 2
                )
            )
            .overlay(alignment: .topTrailing) {
                if isSelected {
                    Image(systemName: StreamifyIcon.checkmarkCircle)
                        .font(.system(size: 16))
                        .foregroundStyle(StreamifyColors.activeControl)
                        .padding(6)
                }
            }
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Profile selection") {
    ProfileSelectionView()
}
