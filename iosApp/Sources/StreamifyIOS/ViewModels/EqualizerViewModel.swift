import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): EqualizerViewModel — 10-band gain offsets, Q
// factors and true-peak limiter state bridged onto the streamify-dsp plane;
// the Swift mirror of `EqualizerManager.kt` (presets + persistence) lifted
// onto the Observation stack.
//
// The DSP itself stays sovereign in Rust (RBJ peaking biquads); the VM owns
// the presentation state, the preset matrix, persistence, and the LIVE
// FREQUENCY-RESPONSE PREVIEW math (`responseDb(at:)`) — the exact RBJ
// cookbook magnitude evaluated at 48 kHz so the console's curve preview
// matches what the engine renders.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class EqualizerViewModel {

    // ── Profile state ──────────────────────────────────────────────────────

    private(set) var profile: EqualizerProfile

    /// The 11 preset shapes (Kotlin parity), interpolated onto the 10-band
    /// ISO octave centers the Rust DSP uses.
    private(set) var presetName: String

    // ── Wiring ─────────────────────────────────────────────────────────────

    private let library: any FeatureLibrary
    private let settings: SettingsStore
    nonisolated(unsafe) private var applyTask: Task<Void, Never>?

    /// The preview sample rate (canonical 48 kHz internal DSP).
    static let previewSampleRateHz: Double = 48_000

    init(
        library: (any FeatureLibrary)? = nil,
        settings: SettingsStore? = nil
    ) {
        let library = library ?? AppServices.library
        let settings = settings ?? AppServices.settings
        self.library = library
        self.settings = settings
        self.profile = settings.loadEqualizerProfile()
        self.presetName = settings.activePresetName()
    }

    deinit {
        applyTask?.cancel()
    }

    // MARK: - Band mutations (optimistic + dispatched; the DSP applies)

    func setBandGain(index: Int, gainDb: Double) {
        guard profile.bands.indices.contains(index) else { return }
        profile.bands[index].gainDb = min(max(gainDb, EqualizerProfile.gainRange.lowerBound),
                                          EqualizerProfile.gainRange.upperBound)
        presetName = "Custom"
        persistAndApply()
    }

    func setBandQ(index: Int, q: Double) {
        guard profile.bands.indices.contains(index) else { return }
        profile.bands[index].q = min(max(q, EqualizerProfile.qRange.lowerBound),
                                     EqualizerProfile.qRange.upperBound)
        persistAndApply()
    }

    func setPreamp(_ preampDb: Double) {
        profile.preampDb = min(max(preampDb, EqualizerProfile.gainRange.lowerBound),
                               EqualizerProfile.gainRange.upperBound)
        persistAndApply()
    }

    func setEnabled(_ enabled: Bool) {
        profile.isEnabled = enabled
        persistAndApply()
    }

    func setLimiterEnabled(_ enabled: Bool) {
        profile.limiterEnabled = enabled
        persistAndApply()
    }

    // MARK: - Presets

    /// The 11 preset profiles (Android `PresetProfile` parity, expanded to
    /// the 10-band Rust centers).
    static let presets: [(name: String, gains: [Double])] = [
        ("Flat", [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
        ("Bass Booster", [8, 7, 5, 3, 1, 0, 0, 0, 0, 0]),
        ("Vocal Booster", [-2, -1, 0, 2, 4, 6, 5, 3, 2, 1]),
        ("Rock", [5, 4, 3, 1, -1, 0, 2, 4, 5, 6]),
        ("Pop", [-1, 0, 2, 4, 5, 4, 3, 2, 1, 1]),
        ("Hip-Hop", [7, 7, 6, 4, 1, 0, 1, 2, 4, 4]),
        ("Electronic", [6, 5, 4, 2, 0, 0, 2, 3, 6, 7]),
        ("Jazz", [3, 3, 2, 2, 1, 2, 2, 3, 4, 4]),
        ("Classical", [4, 3, 2, 1, 0, 0, 1, 3, 4, 5]),
        ("Acoustic", [4, 3, 3, 3, 2, 2, 3, 3, 3, 3]),
        ("Deep", [8, 7, 4, 1, -2, -3, -3, -4, -4, -4]),
    ]

    static let presetNames: [String] = presets.map(\.name)

    func applyPreset(named name: String) {
        guard let preset = Self.presets.first(where: { $0.name == name }) else { return }
        for (index, band) in profile.bands.enumerated() {
            profile.bands[index].gainDb = preset.gains.indices.contains(index)
                ? preset.gains[index]
                : band.gainDb
        }
        presetName = name
        persistAndApply()
    }

    func reset() {
        applyPreset(named: "Flat")
    }

    // MARK: - Persistence + DSP dispatch

    private func persistAndApply() {
        settings.saveEqualizerProfile(profile)
        settings.saveActivePresetName(presetName)
        applyTask?.cancel()
        let profile = profile
        let library = library
        applyTask = Task {
            await library.applyEqualizerProfile(profile)
        }
    }

    // MARK: - Live frequency response preview (RBJ cookbook math)

    /// The summed magnitude response in dB at frequency `hz` (preamp
    /// included) — what the console's response curve renders. Pure math:
    /// each band is an RBJ peaking biquad evaluated on the unit circle at
    /// 48 kHz.
    func responseDb(at hz: Double) -> Double {
        var total = Complex(1, 0)
        for band in profile.bands where abs(band.gainDb) > 0.001 {
            if let h = Self.peakingTransfer(band: band, at: hz) {
                total = total * h
            }
        }
        let magnitudeDb = 20 * log10(max(total.magnitude, 1e-12))
        return profile.isEnabled ? magnitudeDb + profile.preampDb : 0
    }

    /// The RBJ peaking-EQ transfer function evaluated at `hz`.
    static func peakingTransfer(band: EqualizerBand, at hz: Double) -> Complex? {
        let fs = EqualizerViewModel.previewSampleRateHz
        guard (0..<fs / 2).contains(hz), band.q > 0 else { return nil }

        let w0 = 2 * Double.pi * band.centerHz / fs
        let alpha = sin(w0) / (2 * band.q)
        let a = pow(10, band.gainDb / 40) // A = 10^(G/40)

        let b0 = 1 + alpha * a
        let b1 = -2 * cos(w0)
        let b2 = 1 - alpha * a
        let a0 = 1 + alpha / a
        let a1 = b1
        let a2 = 1 - alpha / a

        // Evaluate H(z) at z = e^{-jw} (the unit circle at the probe freq).
        let w = 2 * Double.pi * hz / fs
        let z = Complex(cos(-w), sin(-w))
        let z2 = z * z

        let numerator = Complex(b0, 0) + Complex(b1, 0) * z + Complex(b2, 0) * z2
        let denominator = Complex(a0, 0) + Complex(a1, 0) * z + Complex(a2, 0) * z2
        return numerator / denominator
    }

    // MARK: - Projections

    var bands: [EqualizerBand] { profile.bands }
    var isEnabled: Bool { profile.isEnabled }
    var preampDb: Double { profile.preampDb }
    var isLimiterEnabled: Bool { profile.limiterEnabled }
}

// MARK: - Minimal complex arithmetic (preview math only)

struct Complex: Equatable, Sendable {
    var re: Double
    var im: Double

    init(_ re: Double, _ im: Double = 0) {
        self.re = re
        self.im = im
    }

    static func + (lhs: Complex, rhs: Complex) -> Complex {
        Complex(lhs.re + rhs.re, lhs.im + rhs.im)
    }

    static func - (lhs: Complex, rhs: Complex) -> Complex {
        Complex(lhs.re - rhs.re, lhs.im - rhs.im)
    }

    static func * (lhs: Complex, rhs: Complex) -> Complex {
        Complex(lhs.re * rhs.re - lhs.im * rhs.im, lhs.re * rhs.im + lhs.im * rhs.re)
    }

    static func / (lhs: Complex, rhs: Complex) -> Complex? {
        let denominator = rhs.re * rhs.re + rhs.im * rhs.im
        guard denominator > 0 else { return nil }
        return Complex(
            (lhs.re * rhs.re + lhs.im * rhs.im) / denominator,
            (lhs.im * rhs.re - lhs.re * rhs.im) / denominator
        )
    }

    var magnitude: Double {
        (re * re + im * im).squareRoot()
    }
}
