import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtPlayerActionPills — the player's circular action pills row (Android
// ui/components/yt/YtPlayerActionPills.kt: capsule pills with icon+label).
// The YTM full-player idiom is symbol-only 36 pt circles (per the port spec):
// cast/DAC, song↔video switch, sleep timer, comments and share, TextSecondary
// at rest and TextMain when active, each named for accessibility.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtPlayerActionPills: View {
    public var isCasting: Bool = false
    public var isVideoMode: Bool = false
    public var onCast: () -> Void = {}
    public var onToggleVideoMode: () -> Void = {}
    public var onSleepTimer: () -> Void = {}
    public var onComments: () -> Void = {}
    public var onShare: () -> Void = {}

    public init(
        isCasting: Bool = false,
        isVideoMode: Bool = false,
        onCast: @escaping () -> Void = {},
        onToggleVideoMode: @escaping () -> Void = {},
        onSleepTimer: @escaping () -> Void = {},
        onComments: @escaping () -> Void = {},
        onShare: @escaping () -> Void = {}
    ) {
        self.isCasting = isCasting
        self.isVideoMode = isVideoMode
        self.onCast = onCast
        self.onToggleVideoMode = onToggleVideoMode
        self.onSleepTimer = onSleepTimer
        self.onComments = onComments
        self.onShare = onShare
    }

    public var body: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            ActionPill(
                symbol: StreamifyIcon.airplay,
                label: "Cast or DAC output",
                isActive: isCasting,
                action: onCast
            )
            ActionPill(
                symbol: isVideoMode ? StreamifyIcon.songVideo : StreamifyIcon.video,
                label: isVideoMode ? "Switch to song" : "Switch to video",
                isActive: isVideoMode,
                action: onToggleVideoMode
            )
            ActionPill(
                symbol: StreamifyIcon.timer,
                label: "Sleep timer",
                isActive: false,
                action: onSleepTimer
            )
            ActionPill(
                symbol: "text.bubble",
                label: "Comments",
                isActive: false,
                action: onComments
            )
            ActionPill(
                symbol: StreamifyIcon.share,
                label: "Share",
                isActive: false,
                action: onShare
            )
        }
    }
}

// MARK: - One circular pill

private struct ActionPill: View {
    var symbol: String
    var label: String
    var isActive: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(isActive ? StreamifyColors.textMain : StreamifyColors.textSecondary)
                .frame(
                    width: StreamifyDimens.playerActionPill,
                    height: StreamifyDimens.playerActionPill
                )
                .background(StreamifyColors.bgSurfaceElevated, in: StreamifyShapes.actionPill)
                .overlay(
                    StreamifyShapes.actionPill.strokeBorder(StreamifyColors.borderChip, lineWidth: 1)
                )
                .contentShape(StreamifyShapes.actionPill)
        }
        .buttonStyle(.pressableCard)
        .animation(Motion.snappy, value: isActive)
        .accessibilityLabel(label)
    }
}

#Preview("Player action pills") {
    VStack(spacing: 32) {
        YtPlayerActionPills(isCasting: false, isVideoMode: false)
        YtPlayerActionPills(isCasting: true, isVideoMode: true)
        Text("Actions for \(PreviewCatalog.tracks[0].title)")
            .font(StreamifyType.caption)
            .foregroundStyle(StreamifyColors.textSecondary)
    }
    .padding(24)
    .background(StreamifyColors.bgPlayer)
}
