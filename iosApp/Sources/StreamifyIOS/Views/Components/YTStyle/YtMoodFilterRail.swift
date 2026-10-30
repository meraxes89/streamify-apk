import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtMoodFilterRail — the horizontally scrolling mood filter rail (Android
// ui/components/yt/YtMoodFilterRail.kt). The Compose plain-text chips become
// MoodGenre chips carrying their own gradient dot; the LazyRow maps onto a
// horizontal ScrollView (lazy under the hood) with the same 32 pt chip height
// and chipSurface(active:) paint. Tapping the selected mood clears the filter
// (nil = no mood), the native toggle idiom.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtMoodFilterRail: View {
    public var moods: [MoodGenre]
    @Binding public var selected: String?

    public init(moods: [MoodGenre], selected: Binding<String?>) {
        self.moods = moods
        self._selected = selected
    }

    public var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(moods) { mood in
                    let isActive = selected == mood.id
                    Button {
                        withAnimation(Motion.snappy) {
                            selected = isActive ? nil : mood.id
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Circle()
                                .fill(
                                    LinearGradient(
                                        colors: mood.gradient,
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    )
                                )
                                .frame(width: 8, height: 8)
                            Text(mood.name)
                                .font(StreamifyType.chipText)
                                .lineLimit(1)
                        }
                        .padding(.horizontal, 12)
                        .frame(height: StreamifyDimens.filterChipHeight)
                        .chipSurface(active: isActive)
                    }
                    .buttonStyle(.pressableCard)
                    .foregroundStyle(isActive ? StreamifyColors.textOnActiveChip : StreamifyColors.textMain)
                    .accessibilityLabel(mood.name)
                    .accessibilityAddTraits(isActive ? .isSelected : [])
                }
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
        }
        .frame(height: 48)
    }
}

#Preview("Mood filter rail") {
    MoodRailDemo()
        .background(StreamifyColors.bgBase)
}

private struct MoodRailDemo: View {
    @State private var selected: String? = "focus"

    var body: some View {
        VStack(spacing: 16) {
            YtMoodFilterRail(moods: PreviewCatalog.moods, selected: $selected)
            Text("Selected mood: \(selected ?? "none")")
                .font(StreamifyType.caption)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }
}
