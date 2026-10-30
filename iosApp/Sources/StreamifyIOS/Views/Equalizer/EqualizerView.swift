import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG2-08 — EqualizerView: the EQ & DSP studio surface.
//
// SwiftUI port of ui/screens/EqualizerScreen.kt: the master enable switch,
// the acoustic preset rail (YtPresetFilterChips — selection maps the preset's
// gainsDb onto the band layout), the 5-band graphic matrix (YtVerticalEqSlider
// per band), and the studio arc dial as master pre-amp (YtStudioArcDial).
// The active preset drives the band values; a manual drag drops back to a
// custom curve and reports through onBandsChange.
// ─────────────────────────────────────────────────────────────────────────────

public struct EqualizerView: View {
    public var presets: [EqualizerPreset]
    public var onBandsChange: ([EqualizerBand]) -> Void

    @State private var localBands: [EqualizerBand]
    @State private var selectedPreset: String
    @State private var enabled = true
    @State private var preAmp: Double = 0

    public init(
        presets: [EqualizerPreset] = PreviewCatalog.eqPresets,
        bands: [EqualizerBand] = PreviewCatalog.eqBands,
        onBandsChange: @escaping ([EqualizerBand]) -> Void = {}
    ) {
        self.presets = presets
        self.onBandsChange = onBandsChange
        // The active preset ("Bass lift") drives the band values: its gainsDb
        // map onto the eqBands layout the @State copy seeds from.
        let active = presets.first { $0.name == "Bass lift" } ?? presets.first
        self._localBands = State(initialValue: Self.mapping(bands, from: active))
        self._selectedPreset = State(initialValue: active?.name ?? "")
    }

    public var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: StreamifyDimens.spaceLG) {
                header
                YtPresetFilterChips(
                    presets: presets.map(\.name),
                    selected: $selectedPreset,
                    onSelect: { name in
                        if let preset = presets.first(where: { $0.name == name }) {
                            apply(preset)
                        }
                    }
                )
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.55)
                bandCard
                preAmpCard
            }
            .padding(.horizontal, StreamifyDimens.spaceLG)
            .padding(.bottom, StreamifyDimens.spaceGiant)
        }
        .background(StreamifyColors.bgBase)
    }

    // MARK: - Preset math

    /// Index-aligned gains map: surplus bands keep their seeded gain.
    private static func mapping(
        _ bands: [EqualizerBand],
        from preset: EqualizerPreset?
    ) -> [EqualizerBand] {
        guard let preset else { return bands }
        return bands.enumerated().map { index, band in
            var band = band
            if index < preset.gainsDb.count {
                band.gainDb = preset.gainsDb[index]
            }
            return band
        }
    }

    private func apply(_ preset: EqualizerPreset) {
        selectedPreset = preset.name
        let mapped = Self.mapping(localBands, from: preset)
        withAnimation(Motion.snappy) {
            localBands = mapped
        }
        onBandsChange(mapped)
    }

    private func reset() {
        guard let flat = presets.first(where: {
            $0.name.localizedCaseInsensitiveCompare("Flat") == .orderedSame
        }) else {
            selectedPreset = ""
            localBands = localBands.map { band in
                var band = band
                band.gainDb = 0
                return band
            }
            onBandsChange(localBands)
            return
        }
        apply(flat)
    }

    private func setBand(_ index: Int, to gain: Double) {
        guard localBands.indices.contains(index) else { return }
        localBands[index].gainDb = gain
        selectedPreset = ""  // manual sculpting leaves the preset table
        onBandsChange(localBands)
    }

    // MARK: - Chrome

    private var statusCaption: String {
        guard enabled else { return "DSP bypassed" }
        return selectedPreset.isEmpty ? "Active: custom curve" : "Active: \(selectedPreset)"
    }

    private var header: some View {
        HStack(spacing: StreamifyDimens.spaceMD) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Equalizer")
                    .font(StreamifyType.headlineSmall)
                    .foregroundStyle(StreamifyColors.textMain)
                Text(statusCaption)
                    .font(StreamifyType.caption)
                    .foregroundStyle(
                        enabled
                            ? StreamifyColors.activeControl
                            : StreamifyColors.textSecondary
                    )
            }
            Spacer(minLength: 0)
            Button {
                Haptics.play(.scrubTick)
                reset()
            } label: {
                Text("Reset")
                    .font(StreamifyType.chipText)
                    .foregroundStyle(StreamifyColors.textMain)
                    .padding(.horizontal, StreamifyDimens.spaceMD)
                    .frame(height: StreamifyDimens.filterChipHeight)
                    .chipSurface(active: false)
            }
            .buttonStyle(.pressableCard)
            .disabled(!enabled)
            Toggle("Enabled", isOn: $enabled)
                .labelsHidden()
                .tint(StreamifyColors.primary)
        }
    }

    private var bandCard: some View {
        HStack(alignment: .bottom, spacing: StreamifyDimens.spaceLG) {
            ForEach(localBands.indices, id: \.self) { index in
                YtVerticalEqSlider(band: localBands[index]) { gain in
                    setBand(index, to: gain)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, StreamifyDimens.spaceMD)
        .padding(.horizontal, StreamifyDimens.spaceXS)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.55)
        .cardSurface(hoverable: false)
    }

    private var preAmpCard: some View {
        YtStudioArcDial(value: $preAmp, range: -12 ... 12, label: "Pre-amp")
            .frame(maxWidth: .infinity)
            .padding(.vertical, StreamifyDimens.spaceLG)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.55)
            .cardSurface(hoverable: false)
    }
}

#Preview("Equalizer") {
    EqualizerView()
}
