import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-C04.3 — FollowButton: the follow/unfollow toggle pill.
//
// Port of ui/components/FollowButton.kt (Gap #31: one-tap follow). The Compose
// twin reads the shared FollowGraphStore; the native port keeps the component
// pure — following state in via props, toggles out through the callback
// (Engineer 4's store owns the graph). Morphs pill ↔ filled with the Streamify
// snappy spring and a queueDrop haptic, per the HIG mapping of the Android
// magnetic detent.
// ─────────────────────────────────────────────────────────────────────────────

public struct FollowButton: View {

    public var isFollowing: Bool
    public var onToggle: ((Bool) -> Void)?

    public init(isFollowing: Bool, onToggle: ((Bool) -> Void)? = nil) {
        self.isFollowing = isFollowing
        self.onToggle = onToggle
    }

    public var body: some View {
        Button {
            Haptics.play(.queueDrop)
            onToggle?(!isFollowing)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: isFollowing ? StreamifyIcon.checkmark : StreamifyIcon.personPlus)
                    .font(.system(size: 13, weight: .semibold))
                    .contentTransition(.symbolEffect(.replace))
                    .frame(height: 14)

                Text(isFollowing ? "Following" : "Follow")
                    .font(StreamifyType.chipText)
                    .lineLimit(1)
            }
            .foregroundStyle(isFollowing ? Color.white : StreamifyColors.textMain)
            .padding(.horizontal, 16)
            .padding(.vertical, 7)
            .frame(minWidth: 92)
            .background(
                Capsule().fill(isFollowing ? StreamifyColors.primary : StreamifyColors.bgChipInactive)
            )
            .overlay(
                Capsule().strokeBorder(
                    isFollowing ? Color.clear : StreamifyColors.borderChip,
                    lineWidth: 1
                )
            )
        }
        .buttonStyle(.pressableCard)
        .animation(Motion.snappy, value: isFollowing)
        .accessibilityLabel(isFollowing ? "Following — tap to unfollow" : "Follow")
    }
}

#Preview("Follow button") {
    VStack(spacing: 20) {
        FollowButton(isFollowing: false) { _ in }
        FollowButton(isFollowing: true) { _ in }
    }
    .padding(40)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(StreamifyColors.bgBase)
}
