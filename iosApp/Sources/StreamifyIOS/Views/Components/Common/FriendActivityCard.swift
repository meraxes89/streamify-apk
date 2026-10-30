import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — one row of the live friend activity rail.
//
// Native port of ui/components/FriendActivityCard.kt (Gap #32): who is
// playing what right now — avatar (image or initials plate), name, the
// "listening to" track line, the lastActiveLabel caption and the one-tap
// "Join Jam" chip when the friend hosts a live session. Tapping the row
// opens the friend's profile. Fixtures: PreviewCatalog.friends.
// ─────────────────────────────────────────────────────────────────────────────

public struct FriendActivityCard: View {
    public let friend: FriendActivityEntry
    public var onJoin: ((String) -> Void)? = nil
    public var onOpenProfile: ((String) -> Void)? = nil

    public init(
        friend: FriendActivityEntry,
        onJoin: ((String) -> Void)? = nil,
        onOpenProfile: ((String) -> Void)? = nil
    ) {
        self.friend = friend
        self.onJoin = onJoin
        self.onOpenProfile = onOpenProfile
    }

    public var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            AvatarPlate(name: friend.displayName, avatarUrl: friend.avatarUrl)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(friend.displayName)
                        .font(StreamifyType.bodySmall.weight(.bold))
                        .foregroundStyle(StreamifyColors.textMain)
                        .lineLimit(1)
                    if friend.sessionCode != nil {
                        Image(systemName: StreamifyIcon.jam)
                            .font(.system(size: 11))
                            .foregroundStyle(StreamifyColors.primary)
                    }
                }
                Text(friend.lastActiveLabel)
                    .font(StreamifyType.caption)
                    .foregroundStyle(StreamifyColors.primary)
                    .lineLimit(1)
                HStack(spacing: 5) {
                    Image(systemName: StreamifyIcon.eq)
                        .font(.system(size: 10))
                        .foregroundStyle(StreamifyColors.primary)
                    Text("listening to \(friend.trackTitle) · \(friend.trackArtist)")
                        .font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textSecondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            if let sessionCode = friend.sessionCode {
                ActionChip(
                    label: "Join Jam",
                    icon: StreamifyIcon.personPlus
                ) {
                    onJoin?(sessionCode)
                }
            }
        }
        .padding(StreamifyDimens.spaceMD)
        .cardSurface(hoverable: false)
        .contentShape(Rectangle())
        .onTapGesture { onOpenProfile?(friend.userId) }
    }
}

/// 36 pt circular avatar: AsyncImage, else the initials plate.
private struct AvatarPlate: View {
    let name: String
    let avatarUrl: String?

    private var initials: String {
        let first = name.prefix(1).uppercased()
        return first.isEmpty ? "?" : first
    }

    var body: some View {
        ZStack {
            if let avatarUrl, let url = URL(string: avatarUrl) {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(contentMode: .fill)
                    default:
                        initialsPlate
                    }
                }
            } else {
                initialsPlate
            }
        }
        .frame(width: 36, height: 36)
        .clipShape(Circle())
    }

    private var initialsPlate: some View {
        ZStack {
            Circle().fill(StreamifyGradients.linear(StreamifyGradients.preset(forSeed: name)))
            Text(initials)
                .font(StreamifyType.caption.weight(.bold))
                .foregroundStyle(Color.white)
        }
    }
}

/// The one-tap action chip (Android's ActionChip, emphasized variant).
private struct ActionChip: View {
    let label: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.play(.scrubTick)
            action()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.system(size: 10, weight: .bold))
                Text(label)
                    .font(StreamifyType.caption.weight(.bold))
                    .lineLimit(1)
            }
            .foregroundStyle(StreamifyColors.primary)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(StreamifyColors.primary.opacity(0.16), in: StreamifyShapes.chip)
        }
        .buttonStyle(.pressableCard)
    }
}

#Preview("Friend rows") {
    VStack(spacing: StreamifyDimens.spaceSM) {
        ForEach(PreviewCatalog.friends) { friend in
            FriendActivityCard(
                friend: friend,
                onJoin: { _ in },
                onOpenProfile: { _ in }
            )
        }
    }
    .padding()
    .frame(width: 380)
    .background(StreamifyColors.bgBase)
}
