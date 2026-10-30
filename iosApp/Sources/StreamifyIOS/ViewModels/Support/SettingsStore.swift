import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): the client-side preference store — pure device
// local settings that are presentation concerns (search history, the EQ
// profile, per-track lyric offsets, release pre-saves, stream quality,
// cache budget, SLog switches).
//
// Deliberately UserDefaults-backed and injectable: tests construct a store
// over a throwaway suite, the app uses `.live`. Nothing here duplicates
// engine state (SW-DEC-05) — these are client preferences the Rust core
// receives through the feature seam, never local playback truth.
// ─────────────────────────────────────────────────────────────────────────────

/// Streaming bitrate tier (SW-ENG4-01: 128k / 256k Opus).
enum StreamBitrate: String, CaseIterable, Identifiable, Sendable {
    case opus128 = "128k Opus"
    case opus256 = "256k Opus"

    var id: String { rawValue }

    var detail: String {
        switch self {
        case .opus128: return "Data saver • 128 kbit/s"
        case .opus256: return "Audiophile • 256 kbit/s"
        }
    }
}

/// SLog diagnostic subsystem switches (the settings surface for the Rust
// `tracing`/SLog plane).
enum SLogSwitch: String, CaseIterable, Identifiable, Sendable {
    case engine
    case audio
    case jam
    case storage

    var id: String { rawValue }

    var label: String {
        switch self {
        case .engine: return "Engine (resolver & state)"
        case .audio: return "Audio (DSP & HAL)"
        case .jam: return "Jam (mesh & PTP)"
        case .storage: return "Storage (SQLite & cache)"
        }
    }
}

@MainActor
final class SettingsStore {

    /// The production store (the app's standard UserDefaults).
    static let live = SettingsStore()

    /// A fully isolated store for previews and tests.
    static func ephemeral() -> SettingsStore {
        let suiteName = "streamify-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName) ?? .standard
        return SettingsStore(defaults: defaults)
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - Search history (cap 10, mirror of SearchViewModel.kt)

    private static let searchHistoryKey = "search_history"

    func searchHistory() -> [String] {
        (defaults.stringArray(forKey: Self.searchHistoryKey) ?? []).filter { !$0.isEmpty }
    }

    func addSearchQuery(_ query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var history = searchHistory().filter { $0.caseInsensitiveCompare(trimmed) != .orderedSame }
        history.insert(trimmed, at: 0)
        if history.count > 10 { history.removeLast(history.count - 10) }
        defaults.set(history, forKey: Self.searchHistoryKey)
    }

    func clearSearchHistory() {
        defaults.removeObject(forKey: Self.searchHistoryKey)
    }

    // MARK: - Equalizer profile

    private static let eqEnabledKey = "eq_enabled"
    private static let eqPresetKey = "eq_preset"
    private static let eqPreampKey = "eq_preamp_db"
    private static let eqGainsKey = "eq_gains_db"
    private static let eqQKey = "eq_q_factors"
    private static let eqLimiterKey = "eq_true_peak_limiter"

    func loadEqualizerProfile() -> EqualizerProfile {
        var profile = EqualizerProfile.flat
        profile.isEnabled = defaults.object(forKey: Self.eqEnabledKey) as? Bool ?? true
        let gains = defaults.array(forKey: Self.eqGainsKey) as? [Double]
        let qs = defaults.array(forKey: Self.eqQKey) as? [Double]
        if gains?.count == EqualizerProfile.bandFrequencies.count {
            profile.bands = profile.bands.enumerated().map { index, band in
                var band = band
                band.gainDb = min(max(gains?[index] ?? 0, EqualizerProfile.gainRange.lowerBound), EqualizerProfile.gainRange.upperBound)
                return band
            }
        }
        if qs?.count == profile.bands.count {
            profile.bands = profile.bands.enumerated().map { index, band in
                var band = band
                band.q = min(max(qs?[index] ?? 1, EqualizerProfile.qRange.lowerBound), EqualizerProfile.qRange.upperBound)
                return band
            }
        }
        profile.preampDb = min(
            max(defaults.double(forKey: Self.eqPreampKey), EqualizerProfile.gainRange.lowerBound),
            EqualizerProfile.gainRange.upperBound
        )
        profile.limiterEnabled = defaults.object(forKey: Self.eqLimiterKey) as? Bool ?? true
        profile.limiterCeilingDb = EqualizerProfile.truePeakCeilingDb
        return profile
    }

    func saveEqualizerProfile(_ profile: EqualizerProfile) {
        defaults.set(profile.isEnabled, forKey: Self.eqEnabledKey)
        defaults.set(profile.bands.map(\.gainDb), forKey: Self.eqGainsKey)
        defaults.set(profile.bands.map(\.q), forKey: Self.eqQKey)
        defaults.set(profile.preampDb, forKey: Self.eqPreampKey)
        defaults.set(profile.limiterEnabled, forKey: Self.eqLimiterKey)
    }

    func activePresetName() -> String {
        defaults.string(forKey: Self.eqPresetKey) ?? "Flat"
    }

    func saveActivePresetName(_ name: String) {
        defaults.set(name, forKey: Self.eqPresetKey)
    }

    // MARK: - Per-track lyric sync offsets (mirror of LyricOffsetStore.kt)

    private static let lyricOffsetsKey = "lyric_user_offsets"

    static func lyricKey(title: String?, artist: String?) -> String {
        let t = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let a = (artist ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return "\(t)|\(a)"
    }

    func lyricOffset(forKey key: String) -> Int {
        guard !key.isEmpty else { return 0 }
        return defaults.integer(forKey: Self.lyricOffsetsKey + ":" + key)
    }

    func setLyricOffset(_ offsetMs: Int, forKey key: String) {
        guard !key.isEmpty else { return }
        defaults.set(offsetMs, forKey: Self.lyricOffsetsKey + ":" + key)
    }

    // MARK: - Release pre-saves (mirror of PreSaveStore)

    private static let preSavesKey = "release_presaves"

    func savedReleaseIds() -> Set<String> {
        Set(defaults.stringArray(forKey: Self.preSavesKey) ?? [])
    }

    func addReleasePreSave(_ releaseId: String) {
        var ids = savedReleaseIds()
        ids.insert(releaseId)
        defaults.set(Array(ids), forKey: Self.preSavesKey)
    }

    func removeReleasePreSave(_ releaseId: String) {
        var ids = savedReleaseIds()
        ids.remove(releaseId)
        defaults.set(Array(ids), forKey: Self.preSavesKey)
    }

    // MARK: - Streaming / cache / SLog switches

    private static let bitrateKey = "stream_bitrate"
    private static let cacheBudgetKey = "offline_cache_budget_gb"
    private static let slogPrefix = "slog_enabled_"

    func streamBitrate() -> StreamBitrate {
        StreamBitrate(rawValue: defaults.string(forKey: Self.bitrateKey) ?? "") ?? .opus256
    }

    func setStreamBitrate(_ bitrate: StreamBitrate) {
        defaults.set(bitrate.rawValue, forKey: Self.bitrateKey)
    }

    func cacheBudgetGb() -> Int {
        let stored = defaults.integer(forKey: Self.cacheBudgetKey)
        return (0...64).contains(stored) ? stored : 8
    }

    func setCacheBudgetGb(_ gb: Int) {
        defaults.set(min(max(gb, 0), 64), forKey: Self.cacheBudgetKey)
    }

    func isSLogEnabled(_ logSwitch: SLogSwitch) -> Bool {
        let key = Self.slogPrefix + logSwitch.rawValue
        return defaults.object(forKey: key) as? Bool ?? (logSwitch == .engine)
    }

    func setSLogEnabled(_ logSwitch: SLogSwitch, _ enabled: Bool) {
        defaults.set(enabled, forKey: Self.slogPrefix + logSwitch.rawValue)
    }
}
