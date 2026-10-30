import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — DogfoodPanelView: the Rust audio dogfood telemetry panel.
//
// SwiftUI port of ui/screens/RustAudioDogfoodSection.kt (the shared CMP
// RustAudioDogfoodPanel the debug Settings section hosts, MP-CUT01): a
// compact dark card reporting the USE_RUST_AUDIO window's live telemetry —
// route decision, underrun rate, DAC drift, cold-start latency, pull/clk
// counters — behind a pulsing LIVE badge while the pipeline holds. The
// fixture snapshot is inline (the observability surfaces are Android-side).
// ─────────────────────────────────────────────────────────────────────────────

/// One monospaced label/value telemetry pair.
private struct DogfoodMetric: Identifiable {
    let label: String
    let value: String
    var id: String { label }
}

/// Static plate tokens: the telemetry card stays dark in BOTH appearances
/// (like the Android terminal twin), so its ink is fixed, not dynamic.
private enum DogfoodPlate {
    static let ink = Color(streamifyHex: 0xFFFFFF)
    static let inkSecondary = Color(streamifyHex: 0xAAAAAA)
    static let inkTertiary = Color(streamifyHex: 0x717171)
    static let live = Color(streamifyHex: 0x35C46B)
    static let surface = Color(streamifyHex: 0x0F0F0F)
    static let hairline = Color(streamifyHex: 0x383838)
}

public struct DogfoodPanelView: View {

    /// MP-CUT01 dogfood window snapshot (inline-fixture'd, SW-ENG2-09).
    private let pipelineLive = true
    private let metrics: [DogfoodMetric] = [
        DogfoodMetric(label: "route", value: "rust_core_sink"),
        DogfoodMetric(label: "routeReason", value: "native HAL"),
        DogfoodMetric(label: "underrunRatePercent", value: "0.02 %"),
        DogfoodMetric(label: "dacDriftPpm", value: "2.4 ppm"),
        DogfoodMetric(label: "coldStartMs", value: "41 ms"),
        DogfoodMetric(label: "framesPulled", value: "9,214,880"),
        DogfoodMetric(label: "dacClockSamples", value: "318"),
    ]
    private let footerCaption = "legacyFallbacks: none since rev 88"

    @State private var pulsing = true

    public init() {}

    private var monoFont: Font {
        Font.system(size: 12, design: .monospaced)
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            header
            ForEach(metrics) { metric in
                HStack(alignment: .firstTextBaseline) {
                    Text(metric.label)
                        .font(monoFont)
                        .foregroundStyle(DogfoodPlate.inkTertiary)
                    Spacer(minLength: StreamifyDimens.spaceMD)
                    Text(metric.value)
                        .font(monoFont)
                        .foregroundStyle(DogfoodPlate.ink)
                }
            }
            Text(footerCaption)
                .font(StreamifyType.caption)
                .foregroundStyle(DogfoodPlate.inkTertiary)
        }
        .padding(StreamifyDimens.spaceLG)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            DogfoodPlate.surface,
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(DogfoodPlate.hairline, lineWidth: 1)
        )
    }

    // MARK: - Header + pulsing live badge

    private var header: some View {
        HStack(spacing: StreamifyDimens.spaceSM) {
            VStack(alignment: .leading, spacing: 1) {
                Text("Rust audio dogfood")
                    .font(StreamifyType.titleSmall)
                    .foregroundStyle(DogfoodPlate.ink)
                Text("USE_RUST_AUDIO two-week dogfood window")
                    .font(StreamifyType.caption)
                    .foregroundStyle(DogfoodPlate.inkSecondary)
            }
            Spacer(minLength: 0)
            HStack(spacing: 5) {
                Circle()
                    .fill(
                        pipelineLive
                            ? DogfoodPlate.live
                            : DogfoodPlate.inkSecondary
                    )
                    .frame(width: 7, height: 7)
                    .opacity(pipelineLive && !pulsing ? 0.35 : 1)
                Text(pipelineLive ? "LIVE" : "LEGACY")
                    .font(StreamifyType.caption)
                    .tracking(0.8)
                    .foregroundStyle(
                        pipelineLive
                            ? DogfoodPlate.live
                            : DogfoodPlate.inkSecondary
                    )
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(
                Capsule().fill(
                    DogfoodPlate.live.opacity(pipelineLive ? 0.14 : 0)
                )
            )
            .onAppear {
                guard pipelineLive else { return }
                withAnimation(
                    .easeInOut(duration: 1.1).repeatForever(autoreverses: true)
                ) {
                    pulsing = false
                }
            }
        }
    }
}

#Preview("Rust audio dogfood") {
    DogfoodPanelView()
        .padding(StreamifyDimens.spaceLG)
        .background(StreamifyColors.bgBase)
}
