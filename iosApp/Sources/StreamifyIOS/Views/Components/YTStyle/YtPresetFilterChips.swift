import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtPresetFilterChips — horizontally scrollable EQ preset chips. Port of
// ui/components/yt/YtPresetFilterChips.kt: 32 pt pills, 8 pt corner, active =
// BgChipActive + TextOnActiveChip, inactive bordered BgChipInactive + TextMain;
// case-insensitive match, snappy selection spring + scrub tick.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtPresetFilterChips: View {
    public var presets: [String]
    @Binding public var selected: String
    public var onSelect: (String) -> Void

    public init(
        presets: [String],
        selected: Binding<String>,
        onSelect: @escaping (String) -> Void
    ) {
        self.presets = presets
        self._selected = selected
        self.onSelect = onSelect
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(presets, id: \.self) { preset in
                    Button {
                        select(preset)
                    } label: {
                        Text(preset)
                            .font(StreamifyType.chipText)
                            .foregroundStyle(
                                isActive(preset)
                                    ? StreamifyColors.textOnActiveChip
                                    : StreamifyColors.textMain
                            )
                            .padding(.horizontal, StreamifyDimens.spaceMD)
                            .frame(height: StreamifyDimens.filterChipHeight)
                            .chipSurface(active: isActive(preset))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.vertical, StreamifyDimens.spaceSM)
        }
        .animation(Motion.snappy, value: selected)
    }

    private func isActive(_ preset: String) -> Bool {
        preset.localizedCaseInsensitiveCompare(selected) == .orderedSame
    }

    private func select(_ preset: String) {
        guard !isActive(preset) else { return }
        selected = preset
        Haptics.play(.scrubTick)
        onSelect(preset)
    }
}

private struct PresetChipsDemo: View {
    @State private var selected = "Flat"

    var body: some View {
        YtPresetFilterChips(
            presets: PreviewCatalog.eqPresets.map(\.name),
            selected: $selected,
            onSelect: { _ in }
        )
    }
}

#Preview("Preset filter chips") {
    PresetChipsDemo()
        .padding(.vertical, StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
