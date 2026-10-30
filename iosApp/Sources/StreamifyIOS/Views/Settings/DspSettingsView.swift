import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — DspSettingsView: THE audiophile settings surface.
//
// SwiftUI port of ui/screens/DspSettingsSection.kt (the "Audio Quality & DSP"
// card inside SettingsScreen): loudness normalization with a calibrated EBU
// R128 target slider, gapless playback, crossfade, mono output + stereo
// balance, and the true-peak limiter ceiling — all lowered onto the
// DspPreferences value type. Local state drives one prefs value seeded from
// the `dsp` prop; every mutation reports the whole value through onChange.
// ─────────────────────────────────────────────────────────────────────────────

/// The limiter ceiling ladder (dBFS) exposed as picker chips.
private enum TruePeakCeiling: Double, CaseIterable, Identifiable {
    case minusTenth = -0.1
    case minusHalf = -0.5
    case minusOne = -1.0
    case minusThree = -3.0

    var id: Double { rawValue }
    var label: String { "−\(String(format: "%.1f", -rawValue))" }
}

public struct DspSettingsView: View {
    @State private var prefs: DspPreferences
    public var onChange: (DspPreferences) -> Void

    public init(
        dsp: DspPreferences = PreviewCatalog.dsp,
        onChange: @escaping (DspPreferences) -> Void = { _ in }
    ) {
        self._prefs = State(initialValue: dsp)
        self.onChange = onChange
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
                Text("Audio Quality & DSP").font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                loudnessCard
                gaplessCard
                crossfadeCard
                monoCard
                limiterCard
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Live labels

    private var lufsLabel: String {
        "−\(String(format: "%.1f", abs(prefs.targetLufs))) LUFS · EBU R128"
    }

    private var crossfadeLabel: String {
        prefs.crossfadeSeconds <= 0 ? "Off" : "\(Int(prefs.crossfadeSeconds)) s"
    }

    private var balanceLabel: String {
        let balance = Int(prefs.stereoBalance)
        if balance < 0 { return "L \(-balance) %" }
        if balance > 0 { return "R \(balance) %" }
        return "Centered"
    }

    private var ceilingLabel: String {
        "−\(String(format: "%.1f", -prefs.truePeakDb)) dBFS"
    }

    private func push() {
        onChange(prefs)
    }

    // MARK: - Bindings (every mutation reports the whole value)

    private var loudnessBinding: Binding<Bool> {
        Binding(get: { prefs.loudnessNormalization }, set: { prefs.loudnessNormalization = $0; push() })
    }

    private var targetLufsBinding: Binding<Double> {
        Binding(get: { prefs.targetLufs }, set: { prefs.targetLufs = ($0 * 2).rounded() / 2; push() })
    }

    private var gaplessBinding: Binding<Bool> {
        Binding(get: { prefs.gaplessPlayback }, set: { prefs.gaplessPlayback = $0; push() })
    }

    private var crossfadeBinding: Binding<Double> {
        Binding(get: { prefs.crossfadeSeconds }, set: { prefs.crossfadeSeconds = $0.rounded(); push() })
    }

    private var monoBinding: Binding<Bool> {
        Binding(get: { prefs.monoOutput }, set: { prefs.monoOutput = $0; push() })
    }

    private var balanceBinding: Binding<Double> {
        Binding(get: { prefs.stereoBalance }, set: { prefs.stereoBalance = $0.rounded(); push() })
    }

    // MARK: - Cards

    private var loudnessCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            toggleRow("Loudness normalization", subtitle: "Match every track to one calibrated loudness",
                      binding: loudnessBinding)
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
                Text(lufsLabel).font(StreamifyType.bodySmall).monospaced()
                    .foregroundStyle(StreamifyColors.primary)
                Slider(value: targetLufsBinding, in: -23.0 ... -11.0)
                    .tint(StreamifyColors.primary)
                    .disabled(!prefs.loudnessNormalization)
                HStack {
                    Text("−23 Quiet")
                    Spacer(minLength: 0)
                    Text("−14 Balanced")
                    Spacer(minLength: 0)
                    Text("−11 Loud")
                }
                .font(StreamifyType.caption).foregroundStyle(StreamifyColors.textTertiary)
            }
            .opacity(prefs.loudnessNormalization ? 1 : 0.45)
        }
        .card()
    }

    private var gaplessCard: some View {
        toggleRow("Gapless playback", subtitle: "Sample-accurate track boundaries", binding: gaplessBinding)
            .card()
    }

    private var crossfadeCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceSM) {
            HStack {
                Text("Crossfade").font(StreamifyType.bodyMedium).foregroundStyle(StreamifyColors.textMain)
                Spacer(minLength: 0)
                Text(crossfadeLabel).font(StreamifyType.bodyMedium).monospacedDigit()
                    .foregroundStyle(StreamifyColors.textSecondary)
            }
            Slider(value: crossfadeBinding, in: 0 ... 12).tint(StreamifyColors.primary)
            Text("Smooth constant-energy blend between songs. Gapless takes over at 0 s.")
                .font(StreamifyType.caption).foregroundStyle(StreamifyColors.textSecondary)
        }
        .card()
    }

    private var monoCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            toggleRow("Mono output", subtitle: "Combine left and right channels (accessibility)",
                      binding: monoBinding)
            VStack(alignment: .leading, spacing: StreamifyDimens.spaceXS) {
                HStack {
                    Text("Stereo balance").font(StreamifyType.bodyMedium)
                        .foregroundStyle(StreamifyColors.textMain)
                    Spacer(minLength: 0)
                    Text(balanceLabel).font(StreamifyType.bodyMedium).monospacedDigit()
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
                Slider(value: balanceBinding, in: -100 ... 100)
                    .tint(StreamifyColors.primary)
                    .disabled(!prefs.monoOutput)
                HStack {
                    Text("L 100 %")
                    Spacer(minLength: 0)
                    Text("R 100 %")
                }
                .font(StreamifyType.caption).foregroundStyle(StreamifyColors.textTertiary)
            }
            .opacity(prefs.monoOutput ? 1 : 0.45)
        }
        .card()
    }

    private var limiterCard: some View {
        VStack(alignment: .leading, spacing: StreamifyDimens.spaceMD) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("True-peak limiter").font(StreamifyType.bodyMedium)
                        .foregroundStyle(StreamifyColors.textMain)
                    Text("EU volume-law compliance guard").font(StreamifyType.caption)
                        .foregroundStyle(StreamifyColors.textSecondary)
                }
                Spacer(minLength: 0)
                Text(ceilingLabel).font(StreamifyType.bodyMedium).monospaced()
                    .foregroundStyle(StreamifyColors.primary)
            }
            HStack(spacing: StreamifyDimens.spaceSM) {
                ForEach(TruePeakCeiling.allCases) { ceiling in
                    Button {
                        Haptics.play(.scrubTick)
                        prefs.truePeakDb = ceiling.rawValue
                        push()
                    } label: {
                        Text(ceiling.label).font(StreamifyType.chipText).monospacedDigit()
                            .foregroundStyle(
                                prefs.truePeakDb == ceiling.rawValue
                                    ? StreamifyColors.textOnActiveChip
                                    : StreamifyColors.textMain
                            )
                            .padding(.horizontal, StreamifyDimens.spaceMD)
                            .frame(height: StreamifyDimens.filterChipHeight)
                            .chipSurface(active: prefs.truePeakDb == ceiling.rawValue)
                    }
                    .buttonStyle(.pressableCard)
                }
            }
        }
        .card()
    }

    // MARK: - Row chrome

    private func toggleRow(
        _ title: String,
        subtitle: String,
        binding: Binding<Bool>
    ) -> some View {
        Toggle(isOn: binding) {
            VStack(alignment: .leading, spacing: 1) {
                Text(title).font(StreamifyType.bodyMedium).foregroundStyle(StreamifyColors.textMain)
                Text(subtitle).font(StreamifyType.caption).foregroundStyle(StreamifyColors.textSecondary)
            }
        }
        .tint(StreamifyColors.primary)
    }
}

private extension View {
    /// Section card chrome shared by every DSP control group.
    func card() -> some View {
        self
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(StreamifyDimens.spaceLG)
            .cardSurface(hoverable: false)
    }
}

#Preview("DSP settings") {
    DspSettingsView()
}
