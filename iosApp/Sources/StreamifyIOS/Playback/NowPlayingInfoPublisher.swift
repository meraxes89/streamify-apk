import Foundation
import MediaPlayer
import UIKit

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta) — hardened SW-ENG3-04 (Engineer 3): lockscreen /
// now-playing publishing with the directive's 1 Hz rate limit and the
// async high-resolution artwork pipeline.
//
// MPNowPlayingInfoCenter updates are the most expensive per-frame surface on
// iOS (each apply re-renders the system lockscreen over MediaPlayer IPC), so
// the directive mandates: "Rate-limit lockscreen and now-playing updates to
// 1 Hz." This file implements that discipline as four small pieces:
//
//  * `OneHertzGate` — a PURE admission gate over an injectable clock: the
//    first value always admits, everything inside the trailing 1 s window
//    defers, the boundary re-opens. Value type, zero mocks, fully testable.
//  * `NowPlayingInfoPublisher` — coalesces engine pushes into
//    MPNowPlayingInfoCenter applies THROUGH the gate, with a trailing-edge
//    flush so the LAST state of a burst (e.g. the paused snapshot) always
//    lands — a leading-edge-only limiter would freeze the lockscreen on a
//    stale elapsed time forever. State transitions (Play/Pause/Seek) may
//    bypass the gate via `publishNow` — the directive's "dispatched only on
//    state transitions" escape hatch.
//  * `ArtworkLoader` — downloads and decodes the track's artwork
//    asynchronously (URLSession behind an injectable seam), caches it, and
//    wraps it as `MPMediaItemArtwork` for arbitrary lockscreen render
//    sizes. Artwork applies on TRACK TRANSITIONS only (rare, deliberate —
//    it never rides the per-frame path).
//  * The lockscreen transport controls themselves live in
//    Platform/Remote/RemoteCommandCenterAdapter.swift (SW-ENG3-03) since
//    the hardening split the two concerns apart.
// ─────────────────────────────────────────────────────────────────────────────

/// Pure 1 Hz admission gate. `nowNs` comes from an injectable clock so the
/// full window semantics are unit-testable without timers.
struct OneHertzGate: Sendable {

    /// The directive's rate limit: one emission per second.
    static let intervalNs: UInt64 = 1_000_000_000

    private let intervalNs: UInt64
    private let lastEmitNs: UInt64?

    init(intervalNs: UInt64 = OneHertzGate.intervalNs, lastEmitNs: UInt64? = nil) {
        self.intervalNs = intervalNs
        self.lastEmitNs = lastEmitNs
    }

    /// The admission decision for a value arriving at `nowNs` (pure).
    func admits(nowNs: UInt64) -> Bool {
        guard let lastEmitNs else { return true }
        return nowNs - lastEmitNs >= intervalNs
    }

    /// The state after admitting an emission at `nowNs`.
    func admitting(nowNs: UInt64) -> OneHertzGate {
        OneHertzGate(intervalNs: intervalNs, lastEmitNs: nowNs)
    }
}

// MARK: - Artwork pipeline (SW-ENG3-04)

/// The lockscreen artwork seam: resolves a track's artwork URL into an
/// `MPMediaItemArtwork` asynchronously (download + decode + cache behind
/// the protocol; tests install a deterministic fake).
@MainActor
protocol ArtworkProviding: AnyObject {
    func artwork(for urlString: String?) async -> MPMediaItemArtwork?
}

/// Production artwork pipeline: `URLSession` download behind an injectable
/// fetch seam, an `NSCache` keyed by URL (the artwork cache the storage
/// budget accounts for), and `preparingForDisplay()` decoding so the
/// lockscreen renderer never decodes on the UI frame path.
@MainActor
final class ArtworkLoader: ArtworkProviding {

    private let fetch: @Sendable (URL) async -> Data?
    private let cache = NSCache<NSString, UIImage>()

    init(
        fetch: @escaping @Sendable (URL) async -> Data? = { url in
            guard let (data, response) = try? await URLSession.shared.data(from: url)
            else { return nil }
            guard (response as? HTTPURLResponse)?.statusCode ?? 200 < 400 else {
                return nil
            }
            return data
        }
    ) {
        self.fetch = fetch
        // Artwork is big (multi-MB decoded); a modest count cap bounds the
        // cache to the "recently played" working set.
        cache.countLimit = 48
    }

    func artwork(for urlString: String?) async -> MPMediaItemArtwork? {
        guard let urlString, let url = URL(string: urlString) else { return nil }
        let key = url.absoluteString as NSString
        if let cached = cache.object(forKey: key) {
            return Self.wrap(cached)
        }
        guard let data = await fetch(url) else { return nil }
        guard let image = UIImage(data: data)?.preparingForDisplay() else {
            return nil
        }
        cache.setObject(image, forKey: key)
        return Self.wrap(image)
    }

    /// `MPMediaItemArtwork` over one decoded image (the handler answers
    /// EVERY requested size with the same high-resolution bitmap — the
    /// system downsamples itself).
    nonisolated private static func wrap(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}

// MARK: - The publisher

/// The lockscreen info surface the publisher applies to (the real center is
/// `MPNowPlayingInfoCenter.default()`; tests install a recording stub).
@MainActor
protocol NowPlayingInfoApplying: AnyObject {
    func applyNowPlayingInfo(_ info: [String: Any])
}

/// Production sink: one line over the system center.
@MainActor
final class SystemNowPlayingInfoCenter: NowPlayingInfoApplying {
    func applyNowPlayingInfo(_ info: [String: Any]) {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }
}

/// Coalescing 1 Hz publisher onto the lockscreen / now-playing surface.
@MainActor
final class NowPlayingInfoPublisher {

    private let center: NowPlayingInfoApplying
    private let clock: () -> UInt64
    private var gate: OneHertzGate
    private var pendingInfo: [String: Any]?
    private var flushTask: Task<Void, Never>?
    /// The last APPLIED info (the base the async artwork merge repaints).
    private var lastAppliedInfo: [String: Any]?

    /// The artwork pipeline (SW-ENG3-04); nil disables artwork entirely
    /// (the pure-gate test lane).
    private let artworkProvider: ArtworkProviding?
    private var artworkTask: Task<Void, Never>?
    private var appliedArtworkUrl: String?

    /// The rate-limit window in nanoseconds (1 Hz in production; tests
    /// inject a short window so the trailing-edge flush is verifiable
    /// without sleeping a full second).
    private let intervalNs: UInt64

    init(
        // Optional-with-nil-defaults: default arguments evaluate in a
        // NONISOLATED context, so constructing the @MainActor
        // SystemNowPlayingInfoCenter / ArtworkLoader there would not
        // compile. The fallbacks run inside this isolated init body instead.
        center: NowPlayingInfoApplying? = nil,
        clock: @escaping () -> UInt64 = { DispatchTime.now().uptimeNanoseconds },
        intervalNs: UInt64 = OneHertzGate.intervalNs,
        artworkProvider: ArtworkProviding? = nil
    ) {
        self.center = center ?? SystemNowPlayingInfoCenter()
        self.clock = clock
        self.intervalNs = intervalNs
        self.gate = OneHertzGate(intervalNs: intervalNs)
        self.artworkProvider = artworkProvider ?? ArtworkLoader()
    }

    /// Offers the latest UI state to the lockscreen; applies immediately
    /// when the gate admits, otherwise stores the value as the pending
    /// trailing edge and schedules the boundary flush. Artwork for a NEW
    /// track kicks the async pipeline (state transition — applies outside
    /// the gate when it lands).
    func publish(state: PlaybackUiState) {
        updateArtwork(for: state.currentTrack?.artworkUrl)
        publish(info: NowPlayingInfoPublisher.infoDict(for: state))
    }

    func publish(info: [String: Any]) {
        let now = clock()
        if gate.admits(nowNs: now) {
            pendingInfo = nil
            flushTask?.cancel()
            flushTask = nil
            gate = gate.admitting(nowNs: now)
            lastAppliedInfo = info
            center.applyNowPlayingInfo(info)
        } else {
            pendingInfo = info
            schedulePendingFlush()
        }
    }

    /// Applies IMMEDIATELY, gate be damned — the directive's state-
    /// transition escape hatch (Play/Pause/Seek repaint the transport now;
    /// the 1 Hz budget governs the PER-FRAME churn, not the rare
    /// transitions).
    func publishNow(info: [String: Any]) {
        pendingInfo = nil
        flushTask?.cancel()
        flushTask = nil
        gate = gate.admitting(nowNs: clock())
        lastAppliedInfo = info
        center.applyNowPlayingInfo(info)
    }

    /// Clears the lockscreen surface (playback stopped / teardown).
    func clear() {
        pendingInfo = nil
        flushTask?.cancel()
        flushTask = nil
        artworkTask?.cancel()
        artworkTask = nil
        appliedArtworkUrl = nil
        lastAppliedInfo = nil
        center.applyNowPlayingInfo([:])
    }

    private func schedulePendingFlush() {
        guard flushTask == nil else { return }
        let windowNs = intervalNs
        flushTask = Task { [weak self] in
            // Sleep to just past the boundary; the gate re-opens there.
            try? await Task.sleep(nanoseconds: windowNs)
            guard let self, !Task.isCancelled else { return }
            self.flushTask = nil
            if let pending = self.pendingInfo {
                self.publish(info: pending)
            }
        }
    }

    // MARK: - Async artwork (SW-ENG3-04)

    /// Kicks the artwork pipeline when the track's artwork URL changes;
    /// cancels any in-flight load for the previous track so stale artwork
    /// can never land on the new track's surface.
    private func updateArtwork(for urlString: String?) {
        guard urlString != appliedArtworkUrl else { return }
        appliedArtworkUrl = urlString
        artworkTask?.cancel()
        artworkTask = nil
        guard let urlString, let artworkProvider else { return }
        artworkTask = Task { [weak self] in
            guard let artwork = await artworkProvider.artwork(for: urlString)
            else { return }
            guard !Task.isCancelled else { return }
            self?.applyArtwork(artwork)
        }
    }

    /// Merges the loaded artwork into the CURRENT info and applies it
    /// immediately — a track transition, not per-frame churn (the gate
    /// stays armed for the elapsed-time cadence).
    private func applyArtwork(_ artwork: MPMediaItemArtwork) {
        var info = pendingInfo ?? lastAppliedInfo ?? [:]
        info[MPMediaItemPropertyArtwork] = artwork
        lastAppliedInfo = info
        center.applyNowPlayingInfo(info)
    }

    // MARK: - Pure info-dict derivation (unit-tested)

    /// Builds the MPNowPlayingInfoCenter dictionary from one UI state.
    /// Absent values are OMITTED, never fabricated (no track -> no title
    /// key; no clock -> no elapsed key; the system renders its own
    /// placeholders for missing keys).
    nonisolated static func infoDict(for state: PlaybackUiState) -> [String: Any] {
        var info: [String: Any] = [:]
        if let track = state.currentTrack {
            info[MPMediaItemPropertyTitle] = track.title
            info[MPMediaItemPropertyArtist] = track.artist
            if let durationMs = track.durationMs {
                info[MPMediaItemPropertyPlaybackDuration] = Double(durationMs) / 1000.0
            }
        }
        if let positionMs = state.positionMs {
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
                Double(positionMs) / 1000.0
        }
        info[MPNowPlayingInfoPropertyPlaybackRate] = state.isPlaying ? 1.0 : 0.0
        return info
    }
}
