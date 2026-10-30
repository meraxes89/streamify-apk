import Charts
import SwiftUI
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): the Equalizer console — 10 interactive vertical
// band sliders with a live frequency-response curve preview (Swift Charts),
// preset picker, preamp and true-peak limiter toggles. The DSP itself stays
// sovereign in Rust (streamify-dsp); the console drives
// EqualizerViewModel's profile and renders its RBJ-cookbook preview math.
// ─────────────────────────────────────────────────────────────────────────────

@MainActor
struct EqualizerView: View {
    @State private var eqModel = EqualizerViewModel()
    @State private var selectedBand: Int?

    /// Log-spaced probe points for the response curve (20 Hz … 20 kHz).
    private static let curveProbes: [Double] = (0..<64).map { i in
        20 * pow(1000, Double(i) / 63)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    headerControls
                    responseCurve
                    bandConsole
                    if let band = selectedBandDetail {
                        bandDetail(band)
                    }
                    limiterCard
                }
                .padding(16)
            }
            .background(StreamifyTheme.bgBase)
            .navigationTitle("Equalizer")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - Header (enable + presets + preamp)

    private var headerControls: some View {
        VStack(spacing: 12) {
            HStack {
                Toggle(isOn: Binding(
                    get: { eqModel.isEnabled },
                    set: { eqModel.setEnabled($0) }
                )) {
                    Label("Equalizer", systemImage: "slider.horizontal.3")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundColor(StreamifyTheme.textMain)
                }
                .tint(StreamifyTheme.primary)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(EqualizerViewModel.presetNames, id: \.self) { name in
                        Button(name) {
                            eqModel.applyPreset(named: name)
                        }
                        .font(.system(size: 12, weight: .medium))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(
                            eqModel.presetName == name
                                ? StreamifyTheme.primary.opacity(0.18)
                                : StreamifyTheme.bgSurfaceElevated,
                            in: Capsule()
                        )
                        .foregroundColor(
                            eqModel.presetName == name
                                ? StreamifyTheme.primary
                                : StreamifyTheme.textSecondary
                        )
                    }
                }
            }

            HStack {
                Text("Preamp")
                    .font(.system(size: 12))
                    .foregroundColor(StreamifyTheme.textSecondary)
                Slider(
                    value: Binding(
                        get: { eqModel.preampDb },
                        set: { eqModel.setPreamp($0) }
                    ),
                    in: EqualizerProfile.gainRange
                )
                .tint(StreamifyTheme.primary)
                Text(String(format: "%+.1f dB", eqModel.preampDb))
                    .font(.system(size: 11).monospacedDigit())
                    .foregroundColor(StreamifyTheme.textSecondary)
                    .frame(width: 58, alignment: .trailing)
            }
        }
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - Live frequency response curve (Swift Charts)

    private var responseCurve: some View {
        Chart {
            ForEach(Array(Self.curveProbes.enumerated()), id: \.offset) { _, hz in
                LineMark(
                    x: .value("Frequency", hz),
                    y: .value("Gain", eqModel.responseDb(at: hz))
                )
                .foregroundStyle(
                    LinearGradient(
                        colors: [StreamifyTheme.accentBlue, StreamifyTheme.primary],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round))
                .interpolationMethod(.catmullRom)
            }

            ForEach(Array(eqModel.bands.enumerated()), id: \.element.id) { _, band in
                PointMark(
                    x: .value("Band", band.centerHz),
                    y: .value("Gain", band.gainDb + eqModel.preampDb)
                )
                .symbolSize(40)
                .foregroundStyle(band.gainDb >= 0 ? StreamifyTheme.primary : StreamifyTheme.accentBlue)
            }

            RuleMark(y: .value("Unity", 0))
                .foregroundStyle(StreamifyTheme.textTertiary.opacity(0.4))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }
        .chartXScale(domain: 20...20_000, type: .log)
        .chartYScale(domain: -15...15)
        .chartXAxis {
            AxisMarks(values: [31, 125, 500, 2000, 8000, 16000]) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let hz = value.as(Double.self) {
                        Text(EqualizerBand(
                            index: 0,
                            centerHz: hz,
                            gainDb: 0,
                            q: 1
                        ).frequencyLabel)
                    }
                }
            }
        }
        .chartYAxis {
            AxisMarks(values: [-12, -6, 0, 6, 12])
        }
        .frame(height: 170)
        .padding(16)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - The 10-band console (vertical sliders)

    private var bandConsole: some View {
        HStack(alignment: .bottom, spacing: 6) {
            ForEach(eqModel.bands) { band in
                VerticalBandSlider(
                    band: band,
                    isSelected: selectedBand == band.index,
                    onChanged: { gain in
                        eqModel.setBandGain(index: band.index, gainDb: gain)
                    },
                    onSelect: { selectedBand = band.index }
                )
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 14)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
        .opacity(eqModel.isEnabled ? 1 : 0.45)
    }

    private var selectedBandDetail: EqualizerBand? {
        guard let index = selectedBand else { return nil }
        return eqModel.bands.first { $0.index == index }
    }

    /// The Q-factor tuner for the selected band.
    private func bandDetail(_ band: EqualizerBand) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(band.frequencyLabel) — Q factor")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(StreamifyTheme.textMain)
                Text("Narrower (higher Q) shapes the band more surgically.")
                    .font(.system(size: 10))
                    .foregroundColor(StreamifyTheme.textTertiary)
            }
            Spacer()
            Slider(
                value: Binding(
                    get: { band.q },
                    set: { eqModel.setBandQ(index: band.index, q: $0) }
                ),
                in: EqualizerProfile.qRange
            )
            .tint(StreamifyTheme.accentBlue)
            .frame(maxWidth: 180)
            Text(String(format: "Q %.1f", band.q))
                .font(.system(size: 11).monospacedDigit())
                .foregroundColor(StreamifyTheme.textSecondary)
                .frame(width: 52, alignment: .trailing)
        }
        .padding(14)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }

    // MARK: - True-peak limiter

    private var limiterCard: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Label("True-Peak Limiter", systemImage: "waveform.path")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(StreamifyTheme.textMain)
                Text(String(format: "Ceiling %.1f dBFS — catches inter-sample peaks the sample peak meter misses.", eqModel.profile.limiterCeilingDb))
                    .font(.system(size: 10))
                    .foregroundColor(StreamifyTheme.textTertiary)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { eqModel.isLimiterEnabled },
                set: { eqModel.setLimiterEnabled($0) }
            ))
            .labelsHidden()
            .tint(StreamifyTheme.primary)
        }
        .padding(14)
        .background(StreamifyTheme.bgCard, in: RoundedRectangle(cornerRadius: 16))
    }
}
// MARK: - One vertical band slider (absolute-position console fader)
//
// The drag is **absolute**: every gesture sample maps the finger's Y on the
// track straight to gain — bottom edge = gainRange.lowerBound, top edge =
// gainRange.upperBound, unity notch at the 0 dB fraction. There is no
// translation delta and no captured "base gain", so a dropped frame, a
// re-entering finger or a mid-drag preset reset can never desync the fader:
// whatever the finger's position says, the gain becomes. Gains snap to
// 0.5 dB detents, `minimumDistance: 0` makes a plain tap jump the band, and
// VoiceOver gets a proper adjustable element with the same detents.

private struct VerticalBandSlider: View {
    let band: EqualizerBand
    let isSelected: Bool
    let onChanged: (Double) -> Void
    let onSelect: () -> Void

    /// Track geometry in points.
    private let trackHeight: CGFloat = 150
    private let trackWidth: CGFloat = 26

    @State private var isDragging = false

    private var gainRange: ClosedRange<Double> { EqualizerProfile.gainRange }

    /// Fader position along the track: 0 = bottom (−12 dB) … 1 = top (+12 dB).
    private var faderFraction: Double {
        let span = gainRange.upperBound - gainRange.lowerBound
        return (band.gainDb - gainRange.lowerBound) / span
    }

    /// Track fraction where 0 dB sits (mid-track for a symmetric range, but
    /// derived, not hard-coded, so an asymmetric range stays correct).
    private var unityFraction: Double {
        -gainRange.lowerBound / (gainRange.upperBound - gainRange.lowerBound)
    }

    /// Absolute position → gain: touch Y in track space to a detented value.
    private func gain(atTouchY y: CGFloat, trackHeight height: CGFloat) -> Double {
        let fraction = 1 - Double(y / max(height, 1))
        let clamped = min(max(fraction, 0), 1)
        let raw = gainRange.lowerBound
            + clamped * (gainRange.upperBound - gainRange.lowerBound)
        return (raw * 2).rounded() / 2 // 0.5 dB detents
    }

    var body: some View {
        VStack(spacing: 6) {
            Text(String(format: "%+.1f", band.gainDb))
                .font(.system(size: 9).monospacedDigit())
                .minimumScaleFactor(0.7)
                .lineLimit(1)
                .foregroundColor(
                    band.gainDb == 0 ? StreamifyTheme.textTertiary : StreamifyTheme.textMain
                )

            GeometryReader { geo in
                let height = max(geo.size.height, 1)
                let fraction = min(max(faderFraction, 0), 1)

                ZStack(alignment: .bottom) {
                    // Track.
                    Capsule()
                        .fill(StreamifyTheme.bgSurfaceElevated)
                        .frame(width: 6, height: height)
                    // Fill from the bottom up to the fader position.
                    Capsule()
                        .fill(
                            band.gainDb > 0
                                ? StreamifyTheme.primary
                                : StreamifyTheme.accentBlue
                        )
                        .frame(width: 6, height: height * fraction)
                    // Unity-gain notch (the 0 dB reference line).
                    Rectangle()
                        .fill(StreamifyTheme.textTertiary.opacity(0.5))
                        .frame(width: 14, height: 1)
                        .offset(y: -height * unityFraction)
                    // The fader knob — exactly where the finger lands.
                    Circle()
                        .fill(StreamifyTheme.textMain)
                        .frame(width: 12, height: 12)
                        .shadow(color: .black.opacity(0.35), radius: 2, y: 1)
                        .offset(y: -height * fraction + 6)
                }
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            isDragging = true
                            onSelect()
                            onChanged(gain(atTouchY: value.location.y, trackHeight: height))
                        }
                        .onEnded { _ in
                            isDragging = false
                        }
                )
            }
            .frame(width: trackWidth, height: trackHeight)

            Text(band.frequencyLabel)
                .font(.system(size: 9, weight: .medium).monospacedDigit())
                .foregroundColor(isSelected ? StreamifyTheme.primary : StreamifyTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 4)
        .background(
            isSelected ? StreamifyTheme.primary.opacity(0.10) : Color.clear,
            in: RoundedRectangle(cornerRadius: 8)
        )
        // While dragging the fader tracks the finger 1:1 (no spring lag);
        // preset changes glide the knobs to their new positions instead.
        .animation(
            isDragging ? nil : .spring(response: 0.3, dampingFraction: 0.8),
            value: band.gainDb
        )
        .accessibilityElement()
        .accessibilityLabel("\(band.frequencyLabel) band")
        .accessibilityValue(String(format: "%+.1f decibels", band.gainDb))
        .accessibilityAdjustableAction { direction in
            onSelect()
            switch direction {
            case .increment:
                onChanged(min(band.gainDb + 0.5, gainRange.upperBound))
            case .decrement:
                onChanged(max(band.gainDb - 0.5, gainRange.lowerBound))
            @unknown default:
                break
            }
        }
    }
}
