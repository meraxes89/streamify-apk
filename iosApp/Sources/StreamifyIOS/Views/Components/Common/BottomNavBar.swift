import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2 (Common wave B) — the legacy flat bottom navigation bar.
//
// Port of ui/components/BottomNavBar.kt (the Android delegate itself routes
// into YtBottomNavBar; here the legacy variant stays deliberately FLAT per
// the porting spec — SF Symbol icon + label per tab, the selected tab tinted
// primary, 56 pt tall, no fluid bubble). Selection flows through a Binding so
// hosts can own it, plus the onSelect callback.
// ─────────────────────────────────────────────────────────────────────────────

public struct BottomNavItem: Identifiable, Hashable, Sendable {
    public let id: String
    public let label: String
    public let icon: String

    public init(id: String, label: String, icon: String) {
        self.id = id
        self.label = label
        self.icon = icon
    }
}

public struct BottomNavBar: View {
    public let items: [BottomNavItem]
    @Binding public var selected: String
    public var onSelect: ((BottomNavItem) -> Void)? = nil

    public init(
        items: [BottomNavItem],
        selected: Binding<String>,
        onSelect: ((BottomNavItem) -> Void)? = nil
    ) {
        self.items = items
        self._selected = selected
        self.onSelect = onSelect
    }

    public var body: some View {
        HStack(spacing: 0) {
            ForEach(items) { item in
                tabButton(item)
            }
        }
        .frame(height: StreamifyDimens.bottomNavHeight)
        .frame(maxWidth: .infinity)
        .background(StreamifyColors.bgSurfaceElevated)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(StreamifyColors.divider)
                .frame(height: 0.5)
        }
    }

    private func tabButton(_ item: BottomNavItem) -> some View {
        let isSelected = item.id == selected
        return Button {
            Haptics.play(.scrubTick)
            selected = item.id
            onSelect?(item)
        } label: {
            VStack(spacing: 3) {
                Image(systemName: item.icon)
                    .font(.system(size: 20, weight: isSelected ? .semibold : .regular))
                Text(item.label)
                    .font(StreamifyType.caption)
            }
            .foregroundStyle(isSelected ? StreamifyColors.primary : StreamifyColors.textSecondary)
            .frame(maxWidth: .infinity)
            .frame(height: StreamifyDimens.bottomNavHeight)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .animation(Motion.snappy, value: isSelected)
    }
}

#Preview {
    LegacyNavBarDemo()
        .background(StreamifyColors.bgBase)
}

private struct LegacyNavBarDemo: View {
    @State private var tab = "home"

    private let tabs = [
        BottomNavItem(id: "home", label: "Home", icon: StreamifyIcon.home),
        BottomNavItem(id: "search", label: "Search", icon: StreamifyIcon.search),
        BottomNavItem(id: "library", label: "Library", icon: StreamifyIcon.library),
        BottomNavItem(id: "downloads", label: "Downloads", icon: StreamifyIcon.download),
    ]

    var body: some View {
        VStack {
            Spacer()
            Text("Selected: \(tab)")
                .font(StreamifyType.bodyMedium)
                .foregroundStyle(StreamifyColors.textSecondary)
            Spacer()
            BottomNavBar(items: tabs, selected: $tab)
        }
    }
}
