import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtQueueHeader — the queue sheet's "Up next" header (Android
// ui/components/yt/YtQueueHeader.kt). Android showed a close button, "Clear"
// text button and an "Autoplay" Switch; the Streamify queue surfaces it as
// title + "Playing from …" subtitle with two toggles: the shuffle state and
// the "Infinite radio" auto-radio switch (StreamifyIcon.radio). The Material
// Switch maps onto the native switch toggle style (SW-DEC-06).
// ─────────────────────────────────────────────────────────────────────────────

public struct YtQueueHeader: View {
    public var title: String = "Up next"
    public var sourceName: String = ""
    @Binding public var isShuffled: Bool
    @Binding public var isInfiniteRadio: Bool

    public init(
        title: String = "Up next",
        sourceName: String = "",
        isShuffled: Binding<Bool>,
        isInfiniteRadio: Binding<Bool>
    ) {
        self.title = title
        self.sourceName = sourceName
        self._isShuffled = isShuffled
        self._isInfiniteRadio = isInfiniteRadio
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(StreamifyType.headlineMedium)
                        .foregroundStyle(StreamifyColors.textMain)
                    if !sourceName.isEmpty {
                        Text("Playing from \(sourceName)")
                            .font(StreamifyType.songArtist)
                            .foregroundStyle(StreamifyColors.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: StreamifyDimens.spaceMD)
                Button {
                    isShuffled.toggle()
                } label: {
                    Image(systemName: StreamifyIcon.shuffle)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(isShuffled ? StreamifyColors.textMain : StreamifyColors.textSecondary)
                        .frame(width: 32, height: 32)
                        .background(
                            isShuffled ? StreamifyColors.bgSurfaceElevated : Color.clear,
                            in: StreamifyShapes.actionPill
                        )
                        .contentShape(StreamifyShapes.actionPill)
                }
                .buttonStyle(.plain)
                .animation(Motion.snappy, value: isShuffled)
                .accessibilityLabel("Shuffle queue")

                HStack(spacing: StreamifyDimens.spaceXS) {
                    Image(systemName: StreamifyIcon.radio)
                        .font(.system(size: 12))
                    Text("Infinite radio")
                        .font(StreamifyType.chipText)
                }
                .foregroundStyle(isInfiniteRadio ? StreamifyColors.activeControl : StreamifyColors.textSecondary)

                Toggle("Infinite radio", isOn: $isInfiniteRadio)
                    .toggleStyle(.switch)
                    .controlSize(.small)
                    .tint(StreamifyColors.activeControl)
                    .labelsHidden()
                    .accessibilityLabel("Infinite radio")
            }
        }
        .padding(.horizontal, StreamifyDimens.spaceLG)
        .padding(.vertical, 6)
    }
}

#Preview("Queue header") {
    QueueHeaderDemo()
        .padding(.vertical, 12)
        .frame(maxWidth: 520)
        .background(StreamifyColors.bgBase)
}

private struct QueueHeaderDemo: View {
    @State private var shuffled = false
    @State private var radio = true

    var body: some View {
        YtQueueHeader(
            sourceName: PreviewCatalog.shelves[0].title,
            isShuffled: $shuffled,
            isInfiniteRadio: $radio
        )
    }
}
