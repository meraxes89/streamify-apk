import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtSearchFilterChips — the search result-type filter chips (Android
// ui/components/yt/YtSearchFilterChips.kt: All/Songs/Videos/… string chips).
// The native port is typed: "All" is the nil selection and the rest are the
// SearchResultType cases, so the filter stays part of the search graph's
// currency instead of raw strings. Same 32 pt chip height, chipSurface paint
// and pressable-card feel as the Android chips.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtSearchFilterChips: View {
    @Binding public var selected: SearchResultType?

    public init(selected: Binding<SearchResultType?>) {
        self._selected = selected
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceSM) {
                chip(label: "All", isActive: selected == nil) {
                    selected = nil
                }
                ForEach(SearchResultType.allCases, id: \.self) { type in
                    chip(label: type.label, isActive: selected == type) {
                        selected = type
                    }
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceSM)
        }
    }

    private func chip(
        label: String,
        isActive: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            withAnimation(Motion.snappy) { action() }
        } label: {
            Text(label)
                .font(StreamifyType.chipText)
                .padding(.horizontal, 12)
                .frame(height: StreamifyDimens.filterChipHeight)
                .chipSurface(active: isActive)
                .contentShape(StreamifyShapes.chip)
        }
        .buttonStyle(.pressableCard)
        .foregroundStyle(isActive ? StreamifyColors.textOnActiveChip : StreamifyColors.textMain)
        .accessibilityLabel(label)
        .accessibilityAddTraits(isActive ? .isSelected : [])
    }
}

#Preview("Search filter chips") {
    SearchFilterChipsDemo()
        .background(StreamifyColors.bgBase)
}

private struct SearchFilterChipsDemo: View {
    @State private var selected: SearchResultType?

    private var resultCount: Int {
        PreviewCatalog.onlineResults.filter { result in
            selected == nil || result.type == selected
        }
        .count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            YtSearchFilterChips(selected: $selected)
            Text("\(resultCount) of \(PreviewCatalog.onlineResults.count) fixture results match")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
                .padding(.horizontal, StreamifyDimens.spaceLG)
        }
    }
}
