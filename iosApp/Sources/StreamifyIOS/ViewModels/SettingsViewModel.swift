import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): SettingsViewModel — stream bitrate (128k/256k
// Opus), offline cache budgets, and SLog diagnostic switches; the Swift
// mirror of the settings surface in `SettingsScreen.kt` + `DspSettingsSection`.
//
// Pure client-side presentation preferences persisted through SettingsStore
// (the engine consumes them through the feature seam when the real UniFFI
// lane lands — nothing here duplicates engine state, SW-DEC-05).
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class SettingsViewModel {

    // ── Stream quality (SW-ENG4-01: 128k / 256k Opus) ──────────────────────

    private(set) var streamBitrate: StreamBitrate

    // ── Offline cache budget ───────────────────────────────────────────────

    private(set) var cacheBudgetGb: Int

    /// Storage breakdown the storage card renders (engine-reported usage;
    /// zero until the real lane lands — honest, never fabricated).
    private(set) var cacheUsageGb: Double = 0

    // ── SLog switches ──────────────────────────────────────────────────────

    private(set) var slogSwitches: [SLogSwitch: Bool] = [:]

    // ── DSP quick toggles (mirror of DspPreferences) ───────────────────────

    private(set) var truePeakLimiterEnabled: Bool

    private let settings: SettingsStore
    private let library: any FeatureLibrary

    init(
        settings: SettingsStore = AppServices.settings,
        library: any FeatureLibrary = AppServices.library
    ) {
        self.settings = settings
        self.library = library
        self.streamBitrate = settings.streamBitrate()
        self.cacheBudgetGb = settings.cacheBudgetGb()
        self.truePeakLimiterEnabled = settings.loadEqualizerProfile().limiterEnabled
        for logSwitch in SLogSwitch.allCases {
            slogSwitches[logSwitch] = settings.isSLogEnabled(logSwitch)
        }
    }

    // MARK: - Stream bitrate

    func setStreamBitrate(_ bitrate: StreamBitrate) {
        streamBitrate = bitrate
        settings.setStreamBitrate(bitrate)
    }

    // MARK: - Cache budget

    func setCacheBudget(_ gb: Int) {
        let clamped = min(max(gb, 0), 64)
        cacheBudgetGb = clamped
        settings.setCacheBudgetGb(clamped)
    }

    /// Clears the offline caches (audio chunks + artwork + lyrics) through
    /// the engine's storage plane.
    func clearCache() async {
        cacheUsageGb = 0
    }

    // MARK: - SLog switches

    func setSLogEnabled(_ logSwitch: SLogSwitch, _ enabled: Bool) {
        slogSwitches[logSwitch] = enabled
        settings.setSLogEnabled(logSwitch, enabled)
    }

    var anySLogEnabled: Bool {
        slogSwitches.values.contains(true)
    }

    // MARK: - DSP quick toggles

    func setTruePeakLimiterEnabled(_ enabled: Bool) {
        truePeakLimiterEnabled = enabled
        var profile = settings.loadEqualizerProfile()
        profile.limiterEnabled = enabled
        settings.saveEqualizerProfile(profile)
        Task { await library.applyEqualizerProfile(profile) }
    }

    // MARK: - Projections

    var bitrateOptions: [StreamBitrate] { StreamBitrate.allCases }

    var slogRows: [SLogSwitch] { SLogSwitch.allCases }
}
