import Foundation
import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// YtStudioArcDial — the rotary arc-dial control (Android
// ui/components/yt/YtStudioArcDial.kt: 240° arc from 150°, percentage
// readout, vertical-drag value change). The native port is a real rotary:
// the drag angle around the dial center maps onto the usable sector (the
// top gap snaps to the nearer end), with a Canvas tick arc, center value and
// min/max labels.
// ─────────────────────────────────────────────────────────────────────────────

public struct YtStudioArcDial: View {
    @Binding public var value: Double
    public var range: ClosedRange<Double>
    public var label: String

    public init(value: Binding<Double>, range: ClosedRange<Double>, label: String) {
        self._value = value
        self.range = range
        self.label = label
    }

    private let dialSize: CGFloat = 120
    private let stroke: CGFloat = 8
    private let startAngle: Double = 150
    private let sweepAngle: Double = 240

    private var span: Double { range.upperBound - range.lowerBound }

    private var fraction: Double {
        guard span > 0 else { return 0 }
        return min(max((value - range.lowerBound) / span, 0), 1)
    }

    private func displayText(_ v: Double) -> String {
        let rounded = (v * 10).rounded() / 10
        return rounded.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(rounded))
            : String(format: "%.1f", rounded)
    }

    public var body: some View {
        VStack(spacing: 6) {
            ZStack {
                dialCanvas
                Text(displayText(value))
                    .font(StreamifyType.headlineMedium)
                    .monospacedDigit()
                    .foregroundStyle(StreamifyColors.textMain)
            }
            .frame(width: dialSize, height: dialSize)

            HStack {
                Text(displayText(range.lowerBound))
                Spacer()
                Text(displayText(range.upperBound))
            }
            .font(StreamifyType.caption)
            .monospacedDigit()
            .foregroundStyle(StreamifyColors.textTertiary)
            .frame(width: dialSize)

            Text(label)
                .font(StreamifyType.songArtist)
                .foregroundStyle(StreamifyColors.textSecondary)
        }
    }

    private var dialCanvas: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - stroke
            let sectorStart = CGFloat(startAngle * .pi / 180)
            let sectorEnd = CGFloat((startAngle + sweepAngle) * .pi / 180)

            var track = Path()
            track.addArc(
                center: center,
                radius: radius,
                startAngle: .radians(Double(sectorStart)),
                endAngle: .radians(Double(sectorEnd)),
                clockwise: true
            )
            context.stroke(
                track,
                with: .color(StreamifyColors.divider),
                style: StrokeStyle(lineWidth: stroke, lineCap: .round)
            )

            var ticks = Path()
            let tickCount = 24
            for tick in 0...tickCount {
                let angle = (startAngle + sweepAngle * Double(tick) / Double(tickCount)) * .pi / 180
                let inner = CGPoint(
                    x: center.x + CGFloat(cos(angle)) * (radius - stroke),
                    y: center.y + CGFloat(sin(angle)) * (radius - stroke)
                )
                let outer = CGPoint(
                    x: center.x + CGFloat(cos(angle)) * (radius - stroke - 5),
                    y: center.y + CGFloat(sin(angle)) * (radius - stroke - 5)
                )
                ticks.move(to: inner)
                ticks.addLine(to: outer)
            }
            context.stroke(ticks, with: .color(StreamifyColors.borderChip), lineWidth: 1.5)

            if fraction > 0.01 {
                var progress = Path()
                progress.addArc(
                    center: center,
                    radius: radius,
                    startAngle: .radians(Double(sectorStart)),
                    endAngle: .radians(Double((startAngle + sweepAngle * fraction) * .pi / 180)),
                    clockwise: true
                )
                context.stroke(
                    progress,
                    with: .color(StreamifyColors.activeControl),
                    style: StrokeStyle(lineWidth: stroke, lineCap: .round)
                )
            }
        }
        .frame(width: dialSize, height: dialSize)
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { gesture in update(from: gesture.location) }
        )
        .accessibilityLabel(label)
        .accessibilityValue(displayText(value))
    }

    /// Rotary drag: the finger's angle around the dial center maps onto the
    /// usable 240° sector; the top gap snaps to the nearer sector end.
    private func update(from location: CGPoint) {
        let dx = Double(location.x - dialSize / 2)
        let dy = Double(location.y - dialSize / 2)
        guard span > 0, !(dx == 0 && dy == 0) else { return }

        var degrees = atan2(dy, dx) * 180 / .pi
        if degrees < 0 { degrees += 360 }

        let minAngle = startAngle
        let maxAngle = startAngle + sweepAngle
        var mapped: Double
        if degrees > maxAngle - 360 && degrees < minAngle {
            mapped = degrees < (maxAngle - 360 + minAngle) / 2 ? maxAngle : minAngle
        } else {
            mapped = degrees < minAngle ? degrees + 360 : degrees
            mapped = min(max(mapped, minAngle), maxAngle)
        }
        value = range.lowerBound + (mapped - minAngle) / sweepAngle * span
    }
}

#Preview("Studio arc dial") {
    ArcDialDemo()
        .padding(24)
        .background(StreamifyColors.bgBase)
}

private struct ArcDialDemo: View {
    @State private var gain = 3.0

    var body: some View {
        YtStudioArcDial(
            value: $gain,
            range: -12...12,
            label: "\(PreviewCatalog.eqBandsDb[0].label) band gain"
        )
    }
}
