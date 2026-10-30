import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtBottomNavBar — the shell's fluid bottom tab bar (Android
// ui/components/yt/YtBottomNavBar.kt, 4-item YTM explorer bar). The Compose
// "120 FPS spring pill slider" (animateDpAsState behind the tabs) is expressed
// as a Capsule bubble that slides with Motion.tabBar; the tab items keep the
// 1.12× selection pop, hierarchical SF symbol rendering and the queue-boundary
// selection haptic. Route strings collapse to a selected-index Binding.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtBottomNavBar: View {
    public var items: [(label: String, icon: String)]
    @Binding public var selected: Int
    public var onSelect: (Int) -> Void = { _ in }

    public init(
        items: [(label: String, icon: String)],
        selected: Binding<Int>,
        onSelect: @escaping (Int) -> Void = { _ in }
    ) {
        self.items = items
        self._selected = selected
        self.onSelect = onSelect
    }

    public var body: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(StreamifyColors.borderSubtle)
                .frame(height: 1)
            GeometryReader { proxy in
                let tabWidth = proxy.size.width / CGFloat(max(items.count, 1))
                let bubbleWidth = max(tabWidth - 16, 40)
                ZStack(alignment: .topLeading) {
                    Capsule()
                        .fill(StreamifyColors.bgSurfaceElevated.opacity(0.85))
                        .frame(width: bubbleWidth)
                        .padding(.vertical, 6)
                        .offset(x: CGFloat(selected) * tabWidth + (tabWidth - bubbleWidth) / 2)
                        .animation(Motion.tabBar, value: selected)
                    HStack(spacing: 0) {
                        ForEach(items.indices, id: \.self) { index in
                            YtFluidNavItem(
                                label: items[index].label,
                                icon: items[index].icon,
                                isSelected: index == selected
                            ) {
                                selected = index
                                onSelect(index)
                            }
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
        }
        .background(StreamifyColors.bgBase)
        .frame(height: StreamifyDimens.bottomNavHeight)
        .onChange(of: selected) { _, _ in
            Haptics.play(.queueBoundaryTick)
        }
    }
}

/// One tab of the fluid bar: hierarchical symbol + label, 1.12× selection pop,
/// tint crossfade to TextMain (the Android YtFluidNavItem, native).
public struct YtFluidNavItem: View {
    public var label: String
    public var icon: String
    public var isSelected: Bool
    public var onTap: () -> Void

    public init(
        label: String,
        icon: String,
        isSelected: Bool,
        onTap: @escaping () -> Void
    ) {
        self.label = label
        self.icon = icon
        self.isSelected = isSelected
        self.onTap = onTap
    }

    public var body: some View {
        Button(action: onTap) {
            VStack(spacing: 3) {
                Image(systemName: icon)
                    .symbolRenderingMode(.hierarchical)
                    .font(.system(size: 22))
                    .scaleEffect(isSelected ? 1.12 : 1)
                Text(label)
                    .font(.system(size: 10, weight: isSelected ? .bold : .regular))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(isSelected ? StreamifyColors.textMain : StreamifyColors.textSecondary)
        .animation(Motion.snappy, value: isSelected)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

#Preview("Bottom nav bar") {
    BottomNavBarDemo()
}

private struct BottomNavBarDemo: View {
    @State private var selected = 0

    var body: some View {
        VStack(spacing: 0) {
            Text("Sections for \(PreviewCatalog.userProfile.displayName)")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(StreamifyDimens.spaceLG)
            Spacer()
            YtBottomNavBar(
                items: [
                    (label: "Home", icon: StreamifyIcon.home),
                    (label: "Explore", icon: StreamifyIcon.search),
                    (label: "Library", icon: StreamifyIcon.musicNoteList),
                    (label: "Downloads", icon: StreamifyIcon.download),
                ],
                selected: $selected
            )
        }
        .background(StreamifyColors.bgBase)
    }
}
