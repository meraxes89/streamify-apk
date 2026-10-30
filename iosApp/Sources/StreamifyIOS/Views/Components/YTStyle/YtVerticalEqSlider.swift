import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtVerticalEqSlider — one vertical per-band EQ slider (Android
// ui/components/yt/YtVerticalEqSlider.kt: 36×130 dp canvas slider with
// center-zero line, dB readout and frequency label). The native port drives
// an absolute DragGesture (thumb tracks the finger 1:1), quantizes gain to
// 0.5 dB detents and paints with plain SwiftUI shapes instead of Canvas.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtVerticalEqSlider: View {
    public var band: EqualizerBand
    public var range: ClosedRange<Double> = -12...12
    public var onGainChange: (Double) -> Void

    public init(
        band: EqualizerBand,
        range: ClosedRange<Double> = -12...12,
        onGainChange: @escaping (Double) -> Void
    ) {
        self.band = band
        self.range = range
        self.onGainChange = onGainChange
    }

    private let trackHeight: CGFloat = 130

    private var span: Double { range.upperBound - range.lowerBound }

    private var gainFraction: Double {
        guard span > 0 else { return 0.5 }
        return min(max((band.gainDb - range.lowerBound) / span, 0), 1)
    }

    private var thumbY: CGFloat {
        trackHeight * (1 - CGFloat(gainFraction))
    }

    private var activeHeight: CGFloat {
        max(abs(trackHeight / 2 - thumbY), 4)
    }

    private var activeOffsetY: CGFloat {
        min(thumbY, trackHeight / 2) + activeHeight / 2 - trackHeight / 2
    }

    private var gainText: String {
        let rounded = (band.gainDb * 2).rounded() / 2
        let body = rounded.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(rounded))
            : String(format: "%.1f", rounded)
        return (rounded > 0 ? "+\(body)" : body) + " dB"
    }

    public var body: some View {
        VStack(spacing: 8) {
            Text(gainText)
                .font(StreamifyType.seekbarTime)
                .monospacedDigit()
                .foregroundStyle(
                    band.gainDb != 0 ? StreamifyColors.activeControl : StreamifyColors.textSecondary
                )

            ZStack {
                Capsule()
                    .fill(StreamifyColors.divider)
                    .frame(width: 4, height: trackHeight)
                Rectangle()
                    .fill(StreamifyColors.textTertiary)
                    .frame(width: 14, height: 1.5)
                Capsule()
                    .fill(StreamifyColors.activeControl)
                    .frame(width: 4, height: activeHeight)
                    .offset(y: activeOffsetY)
                Circle()
                    .fill(StreamifyColors.activeControl)
                    .frame(width: 14, height: 14)
                    .overlay(Circle().strokeBorder(StreamifyColors.bgCard, lineWidth: 2))
                    .offset(y: thumbY - trackHeight / 2)
            }
            .frame(width: 36, height: trackHeight)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard span > 0, trackHeight > 0 else { return }
                        let fraction = min(max(1 - Double(value.location.y / trackHeight), 0), 1)
                        let gain = range.lowerBound + fraction * span
                        onGainChange((gain * 2).rounded() / 2)
                    }
            )
            .accessibilityLabel("\(band.frequencyLabel) band gain")
            .accessibilityValue(gainText)

            Text(band.frequencyLabel)
                .font(StreamifyType.songArtist)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
        .padding(.horizontal, 2)
        .padding(.vertical, 4)
    }
}

#Preview("Vertical EQ sliders") {
    EqSliderDemo()
        .background(StreamifyColors.bgBase)
}

private struct EqSliderDemo: View {
    @State private var bands = PreviewCatalog.eqBands

    var body: some View {
        HStack(spacing: StreamifyDimens.spaceLG) {
            ForEach(bands.indices, id: \.self) { index in
                YtVerticalEqSlider(band: bands[index]) { gain in
                    bands[index].gainDb = gain
                }
            }
        }
        .padding(24)
    }
}
