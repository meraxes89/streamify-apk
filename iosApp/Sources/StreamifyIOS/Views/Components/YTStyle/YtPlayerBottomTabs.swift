import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtPlayerBottomTabs — the full player's bottom tab strip (UP NEXT / LYRICS /
// RELATED). Port of ui/components/yt/YtPlayerBottomTabs.kt: evenly spaced
// label + chevron tab items; active renders TextMain, inactive TextSecondary.
// Native touches: selection lands with Motion.snappy + a scrub-tick haptic.
// ─────────────────────────────────────────────────────────────────────────────

/// One player tab (Android: hardcoded label + KeyboardArrowUp glyph).
public struct YtPlayerTab: Hashable, Identifiable {
    public let label: String
    public let icon: String

    public init(label: String, icon: String) {
        self.label = label
        self.icon = icon
    }

    public var id: String { label }
}

public struct YtPlayerBottomTabs: View {
    public var tabs: [YtPlayerTab]
    @Binding public var selectedIndex: Int
    public var onSelect: (Int) -> Void

    public init(
        tabs: [YtPlayerTab],
        selectedIndex: Binding<Int>,
        onSelect: @escaping (Int) -> Void
    ) {
        self.tabs = tabs
        self._selectedIndex = selectedIndex
        self.onSelect = onSelect
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(Array(tabs.enumerated()), id: \.element.id) { index, tab in
                Button {
                    select(index)
                } label: {
                    tabLabel(tab, isActive: index == selectedIndex)
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, StreamifyDimens.spaceMD)
        .animation(Motion.snappy, value: selectedIndex)
    }

    private func select(_ index: Int) {
        guard index != selectedIndex else { return }
        selectedIndex = index
        Haptics.play(.scrubTick)
        onSelect(index)
    }

    private func tabLabel(_ tab: YtPlayerTab, isActive: Bool) -> some View {
        HStack(spacing: StreamifyDimens.spaceXS) {
            Text(tab.label)
                .font(StreamifyType.chipText)
                .foregroundStyle(isActive ? StreamifyColors.textMain : StreamifyColors.textSecondary)
            Image(systemName: tab.icon)
                .font(.system(size: 14))
                .foregroundStyle(isActive ? StreamifyColors.textMain : StreamifyColors.textSecondary)
        }
        .padding(.horizontal, StreamifyDimens.spaceMD)
        .padding(.vertical, StreamifyDimens.spaceXS)
    }
}

private struct PlayerBottomTabsDemo: View {
    @State private var selected = 0
    private let tabs = [
        YtPlayerTab(label: "UP NEXT", icon: "chevron.up"),
        YtPlayerTab(label: "LYRICS", icon: StreamifyIcon.lyrics),
        YtPlayerTab(label: "RELATED", icon: StreamifyIcon.related),
    ]

    var body: some View {
        YtPlayerBottomTabs(tabs: tabs, selectedIndex: $selected, onSelect: { _ in })
            .padding(.vertical, StreamifyDimens.spaceLG)
    }
}

#Preview("Player bottom tabs") {
    PlayerBottomTabsDemo()
        .padding(StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
