import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtLibraryFilterChips — library filter pills (Playlists / Songs / Albums /
// Artists / Downloads). Port of ui/components/yt/YtLibraryFilterChips.kt:
// identical anatomy to the preset chips — 32 pt pills, active BgChipActive +
// TextOnActiveChip, inactive bordered + TextMain, case-insensitive selection.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtLibraryFilterChips: View {
    public var filters: [String]
    @Binding public var selected: String
    public var onSelect: (String) -> Void

    public init(
        filters: [String] = ["Playlists", "Songs", "Albums", "Artists", "Downloads"],
        selected: Binding<String>,
        onSelect: @escaping (String) -> Void
    ) {
        self.filters = filters
        self._selected = selected
        self.onSelect = onSelect
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(filters, id: \.self) { filter in
                    Button {
                        select(filter)
                    } label: {
                        Text(filter)
                            .font(StreamifyType.chipText)
                            .foregroundStyle(
                                isActive(filter)
                                    ? StreamifyColors.textOnActiveChip
                                    : StreamifyColors.textMain
                            )
                            .padding(.horizontal, StreamifyDimens.spaceMD)
                            .frame(height: StreamifyDimens.filterChipHeight)
                            .chipSurface(active: isActive(filter))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceSM)
        }
        .animation(Motion.snappy, value: selected)
    }

    private func isActive(_ filter: String) -> Bool {
        filter.localizedCaseInsensitiveCompare(selected) == .orderedSame
    }

    private func select(_ filter: String) {
        guard !isActive(filter) else { return }
        selected = filter
        Haptics.play(.scrubTick)
        onSelect(filter)
    }
}

private struct LibraryChipsDemo: View {
    @State private var selected = "Playlists"

    var body: some View {
        YtLibraryFilterChips(selected: $selected, onSelect: { _ in })
    }
}

#Preview("Library filter chips") {
    LibraryChipsDemo()
        .padding(.vertical, StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
