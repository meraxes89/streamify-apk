import Foundation
import SwiftUI
import UIKit
import WidgetKit

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-05 (Phase 5 / MP-CAP03): Dynamic Island & Live Activities —
// ActivityKit surface for iPhone 14 Pro+ (iOS 16.1+; interactive controls on
// iOS 17+).
//
// PRESENTATION CONTRACT (the directive's mandate):
//  * COMPACT — album thumbnail on the leading edge, animated live audio
//    waveform bars on the trailing edge.
//  * EXPANDED — full artwork, interactive Play/Pause + Next buttons (iOS 17
//    AppIntent buttons — the same PlaybackIntents the widgets use), and an
//    animated track scrubber that interpolates BETWEEN the 1 Hz content
//    updates so it never stutters.
//  * MINIMAL — waveform bars only (the system picks this presentation
//    when the compact slots are taken by another activity).
//
// DISCIPLINES (same as every other OS surface in this subtree):
//  * 1 Hz UPDATE ADMISSION — ActivityKit updates ride MediaPlayer-adjacent
//    IPC; `LiveActivityUpdatePolicy` admits an update only when a
//    RENDERED value actually changed (second-quantized position, transport
//    state, track identity) — the same budget as NowPlayingInfoPublisher.
//  * DETERMINISTIC ANIMATION — the waveform bars derive from a pure
//    two-sine model over the TimelineView phase (`LiveWaveformModel`):
//    zero mutable view state, zero timers, fully unit-testable heights.
//  * CLOCK DISCIPLINE (MP-DEC-03) — the scrubber advances ONLY from the
//    hardware DAC position + the monotonic delta since the content
//    snapshot's `positionUpdatedAt` (`LiveActivityMath.progress`); no
//    wall-clock position estimation anywhere.
//
// HOST-APP ENTITLEMENTS (the wrapper target's responsibility — the library
// compiles without them): Info.plist `NSSupportsLiveActivities = YES`
// (+ `NSSupportsLiveActivitiesFrequentUpdates` for the push lane).
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(ActivityKit)
import ActivityKit

// MARK: - The activity contract

/// The Live Activity attributes: immutable per-activity identity.
struct StreamifyPlaybackAttributes: ActivityAttributes {

    /// Rendered, 1 Hz-admitted content (kept SMALL: artwork is a
    /// downsampled thumbnail — see LiveActivityArtworkProvider).
    struct ContentState: Codable, Hashable {
        var title: String
        var artist: String
        var isPlaying: Bool
        /// Hardware DAC-derived position at `positionUpdatedAt` (seconds).
        var positionSeconds: Double
        /// When `positionSeconds` was captured — the scrubber's
        /// interpolation anchor (MP-DEC-03: monotonic-domain advancement).
        var positionUpdatedAt: Date
        var durationSeconds: Double
        /// Downsampled JPEG thumbnail (nil renders the placeholder glyph).
        var artwork: Data?
    }

    /// The track this activity was started for (identity: update ONLY
    /// within one track; a track change ends the activity and starts a new
    /// one — cleaner than morphing artwork under a stale title).
    var trackId: String
}

// MARK: - Pure math (unit-tested)

/// The 1 Hz admission policy for ActivityKit updates: an update is worth
/// its IPC only when a RENDERED value changed — the second-quantized
/// position, the transport state, or the track identity.
enum LiveActivityUpdatePolicy {

    static func renderedFingerprint(
        title: String,
        artist: String,
        isPlaying: Bool,
        positionSeconds: Double,
        durationSeconds: Double
    ) -> String {
        // Whole-second position: the scrubber interpolates the sub-second
        // part locally, so fractional pushes are pure waste.
        "\(title)|\(artist)|\(isPlaying ? 1 : 0)|\(Int(positionSeconds))|\(Int(durationSeconds))"
    }

    static func shouldUpdate(
        previous: String?,
        title: String,
        artist: String,
        isPlaying: Bool,
        positionSeconds: Double,
        durationSeconds: Double
    ) -> Bool {
        let fingerprint = renderedFingerprint(
            title: title,
            artist: artist,
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            durationSeconds: durationSeconds
        )
        return previous != fingerprint
    }
}

/// Deterministic waveform bar heights — the "animated live audio waveform"
/// of the compact/minimal presentations. Two incommensurate sines over
/// (barIndex, phase): organic-looking, allocation-free, and exactly
/// reproducible in the unit lane (the phase comes from the TimelineView's
/// clock, never from mutable view state).
enum LiveWaveformModel {

    /// Normalized height in [0, 1] for bar `index` (of `barCount`) at
    /// animation `phase` (ticks monotonically; wrapped internally).
    static func normalizedHeight(
        bar index: Int,
        phase: Int,
        barCount: Int = 4
    ) -> Double {
        guard barCount > 1, index >= 0, index < barCount else { return 0 }
        let t = Double(index) / Double(barCount - 1)
        let p1 = Double(moduloPositive(phase, 60)) / 60.0
        let p2 = Double(moduloPositive(phase, 47)) / 47.0
        let a = sin(2.0 * .pi * (t * 1.7 + p1))
        let b = sin(2.0 * .pi * (t * 3.3 + p2))
        return (0.5 * a + 0.35 * b + 0.85) / 1.7
    }

    /// Heights for a whole bar row; paused rows freeze at a static,
    /// low-key profile (a flat line says "paused" louder than a still wave).
    static func heights(
        phase: Int,
        barCount: Int = 4,
        isPlaying: Bool
    ) -> [Double] {
        guard isPlaying else {
            return (0..<barCount).map { _ in 0.18 }
        }
        return (0..<barCount).map { normalizedHeight(bar: $0, phase: phase, barCount: barCount) }
    }

    private static func moduloPositive(_ value: Int, _ modulus: Int) -> Int {
        let m = modulus > 0 ? modulus : 1
        let r = value % m
        return r < 0 ? r + m : r
    }
}

/// Scrubber interpolation between 1 Hz content updates (MP-DEC-03): the
/// progress advances from the hardware-anchored position plus the elapsed
/// time since the snapshot — both endpoints on the same clock domain the
/// DAC publisher runs.
enum LiveActivityMath {

    static func interpolatedPositionSeconds(
        positionSeconds: Double,
        positionUpdatedAt: Date,
        now: Date,
        isPlaying: Bool
    ) -> Double {
        guard isPlaying else { return positionSeconds }
        let advanced = max(0, now.timeIntervalSince(positionUpdatedAt))
        return positionSeconds + advanced
    }

    /// Progress in [0, 1]; nil when the duration is unknown (indeterminate).
    static func progress(
        positionSeconds: Double,
        positionUpdatedAt: Date,
        durationSeconds: Double,
        now: Date,
        isPlaying: Bool
    ) -> Double? {
        guard durationSeconds > 0 else { return nil }
        let position = interpolatedPositionSeconds(
            positionSeconds: positionSeconds,
            positionUpdatedAt: positionUpdatedAt,
            now: now,
            isPlaying: isPlaying
        )
        return min(max(position / durationSeconds, 0), 1)
    }
}

// MARK: - Artwork (downsampled thumbnails for the ContentState)

/// Non-isolated constants home: default arguments evaluate in a
/// NONISOLATED context, so @MainActor-isolated statics cannot back the
/// `downsample` defaults below (Swift 6 makes that an error).
enum LiveActivityThumbnailPolicy {
    /// Bounded thumbnail edge (the push-update lane's ~4 KB budget).
    static let maxPixelSize: CGFloat = 192
    static let jpegQuality: CGFloat = 0.75
}

/// Thumbnail pipeline for the ContentState: downloads the track artwork
/// once per track and downsamples to a bounded JPEG payload (ContentState
/// must stay small — the push-update lane has a hard ~4 KB budget).
@MainActor
final class LiveActivityArtworkProvider {

    private let fetch: @Sendable (URL) async -> Data?
    private var inFlight: [String: Task<Data?, Never>] = [:]

    /// NONISOLATED on purpose: default arguments such as the controller's
    /// and the widget refresher's `artworkProvider: ... =
    /// LiveActivityArtworkProvider()` evaluate in a nonisolated context —
    /// this injected-seam initializer only stores the closure and touches
    /// no isolated state.
    nonisolated init(
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
    }

    /// Downsampled JPEG thumbnail data, or nil (no URL / fetch failure /
    /// decode failure — the views render the placeholder glyph).
    func thumbnail(for urlString: String?) async -> Data? {
        guard let urlString, let url = URL(string: urlString) else { return nil }
        if let existing = inFlight[urlString] {
            return await existing.value
        }
        let task = Task<Data?, Never> { [fetch] in
            guard let data = await fetch(url) else { return nil }
            return Self.downsample(data)
        }
        inFlight[urlString] = task
        let result = await task.value
        inFlight[urlString] = nil
        return result
    }

    /// Pure downsample: decode → bounded resize → JPEG.
    nonisolated static func downsample(
        _ data: Data,
        maxPixelSize: CGFloat = LiveActivityThumbnailPolicy.maxPixelSize,
        jpegQuality: CGFloat = LiveActivityThumbnailPolicy.jpegQuality
    ) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let largest = max(image.size.width, image.size.height)
        guard largest > maxPixelSize, largest > 0 else {
            return image.jpegData(compressionQuality: jpegQuality)
        }
        let scale = maxPixelSize / largest
        let targetSize = CGSize(
            width: floor(image.size.width * scale),
            height: floor(image.size.height * scale)
        )
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return resized.jpegData(compressionQuality: jpegQuality)
    }
}

// MARK: - The controller

/// Owns the app's playback Live Activity: starts it when real playback
/// begins, updates it through the 1 Hz admission policy, ends it (and
/// starts a fresh one) on track changes, and tears every stale activity
/// down on `endAll`. Availability: iOS 16.1+ (interactive controls render
/// on iOS 17+ — see PlaybackLiveActivityExpanded).
@available(iOS 16.1, *)
@MainActor
final class PlaybackLiveActivityController: ObservableObject {

    private var activity: Activity<StreamifyPlaybackAttributes>?
    private var lastFingerprint: String?

    private let artworkProvider: LiveActivityArtworkProvider

    /// Diagnostics: the last admission decision's fingerprint.
    @Published private(set) var lastUpdateFingerprint: String?

    init(
        artworkProvider: LiveActivityArtworkProvider = LiveActivityArtworkProvider()
    ) {
        self.artworkProvider = artworkProvider
    }

    var isActive: Bool { activity != nil }

    /// True when the OS authorizes Live Activities at all (user toggle +
    /// device support — iPhone 14 Pro+ for the island, any 16.1+ device
    /// for the lock screen presentation).
    static var isAuthorized: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    /// Starts a fresh activity for `trackId`. Ends any activity belonging
    /// to a DIFFERENT track first (track changes restart the activity —
    /// artwork never morphs under a stale title).
    func start(
        trackId: String,
        title: String,
        artist: String,
        isPlaying: Bool,
        positionSeconds: Double,
        durationSeconds: Double,
        artworkUrl: String?
    ) async {
        guard Self.isAuthorized else { return }
        if let existing = activity, existing.attributes.trackId != trackId {
            await end()
        }
        guard activity == nil else { return }

        let artwork = await artworkProvider.thumbnail(for: artworkUrl)
        let state = StreamifyPlaybackAttributes.ContentState(
            title: title,
            artist: artist,
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            positionUpdatedAt: Date(),
            durationSeconds: durationSeconds,
            artwork: artwork
        )
        do {
            activity = try Activity.request(
                attributes: StreamifyPlaybackAttributes(trackId: trackId),
                contentState: state,
                pushType: nil
            )
            lastFingerprint = LiveActivityUpdatePolicy.renderedFingerprint(
                title: title,
                artist: artist,
                isPlaying: isPlaying,
                positionSeconds: positionSeconds,
                durationSeconds: durationSeconds
            )
        } catch {
            // A refused request is a diagnostic (activity cap hit, user
            // toggled mid-flight): playback continues regardless.
            activity = nil
        }
    }

    /// Offers the current playback state; admits only RENDERED-value
    /// changes (the 1 Hz discipline).
    func update(
        trackId: String,
        title: String,
        artist: String,
        isPlaying: Bool,
        positionSeconds: Double,
        durationSeconds: Double,
        artworkUrl: String?
    ) async {
        guard let activity else { return }
        guard activity.attributes.trackId == trackId else {
            // Track changed under a live activity: restart cleanly.
            await start(
                trackId: trackId,
                title: title,
                artist: artist,
                isPlaying: isPlaying,
                positionSeconds: positionSeconds,
                durationSeconds: durationSeconds,
                artworkUrl: artworkUrl
            )
            return
        }
        guard LiveActivityUpdatePolicy.shouldUpdate(
            previous: lastFingerprint,
            title: title,
            artist: artist,
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            durationSeconds: durationSeconds
        ) else { return }

        var artwork = activity.contentState.artwork
        if artwork == nil {
            artwork = await artworkProvider.thumbnail(for: artworkUrl)
        }
        let state = StreamifyPlaybackAttributes.ContentState(
            title: title,
            artist: artist,
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            positionUpdatedAt: Date(),
            durationSeconds: durationSeconds,
            artwork: artwork
        )
        await activity.update(using: state)
        lastFingerprint = LiveActivityUpdatePolicy.renderedFingerprint(
            title: title,
            artist: artist,
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            durationSeconds: durationSeconds
        )
        lastUpdateFingerprint = lastFingerprint
    }

    /// Ends the activity (playback stopped).
    func end() async {
        guard let activity else { return }
        await activity.end(using: nil, dismissalPolicy: .immediate)
        self.activity = nil
        lastFingerprint = nil
        lastUpdateFingerprint = nil
    }

    /// Kills every stale activity of ours still lingering from a previous
    /// process lifetime (call once at app start).
    static func endAllStale() async {
        for stale in Activity<StreamifyPlaybackAttributes>.activities {
            await stale.end(using: nil, dismissalPolicy: .immediate)
        }
    }
}

// MARK: - Views

/// The lock-screen / banner presentation.
///
/// Host wiring (the widget-extension target): the `ActivityConfiguration`
/// content closure passes the system context's state straight through —
/// `PlaybackLiveActivityLockScreenView(state: context.state)` — keeping
/// this library type decoupled from the SDK's context generics.
@available(iOS 16.1, *)
struct PlaybackLiveActivityLockScreenView: View {
    let state: StreamifyPlaybackAttributes.ContentState

    var body: some View {
        HStack(spacing: 12) {
            LiveActivityArtworkView(artworkData: state.artwork, cornerRadius: 10)
                .frame(width: 56, height: 56)
            VStack(alignment: .leading, spacing: 3) {
                Text(state.title)
                    .font(.headline)
                    .lineLimit(1)
                Text(state.artist)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                LiveScrubberView(state: state, tint: .accentColor)
            }
            Spacer(minLength: 4)
            LiveWaveformBarsView(isPlaying: state.isPlaying, tint: .accentColor)
                .frame(width: 42)
        }
        .padding(14)
        .activityBackgroundTint(.black.opacity(0.35))
    }
}

/// The Dynamic Island presentation: compact leading = thumbnail, compact
/// trailing = waveform bars; expanded = artwork + interactive transport +
/// animated scrubber.
///
/// Host wiring (the widget-extension target): the `ActivityConfiguration`
/// `dynamicIsland:` closure passes the system context's state straight
/// through — `PlaybackLiveActivityDynamicIslandView(state:
/// context.state)`.
@available(iOS 16.1, *)
struct PlaybackLiveActivityDynamicIslandView: View {
    let state: StreamifyPlaybackAttributes.ContentState

    var body: some View {
        DynamicIsland {
            DynamicIslandExpandedRegion(.leading) {
                LiveActivityArtworkView(
                    artworkData: state.artwork,
                    cornerRadius: 14
                )
                .frame(width: 64, height: 64)
                .padding(.leading, 4)
            }
            DynamicIslandExpandedRegion(.center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(state.title)
                        .font(.headline)
                        .lineLimit(1)
                    Text(state.artist)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            DynamicIslandExpandedRegion(.trailing) {
                LiveTransportControlsView(state: state)
                    .padding(.trailing, 4)
            }
            DynamicIslandExpandedRegion(.bottom) {
                LiveScrubberView(state: state, tint: .accentColor)
                    .padding(.horizontal, 4)
            }
        } compactLeading: {
            LiveActivityArtworkView(
                artworkData: state.artwork,
                cornerRadius: 6
            )
            .frame(width: 22, height: 22)
        } compactTrailing: {
            LiveWaveformBarsView(isPlaying: state.isPlaying, tint: .accentColor)
                .frame(width: 34)
        } minimal: {
            LiveWaveformBarsView(isPlaying: state.isPlaying, tint: .accentColor)
                .frame(width: 22)
        }
        .keylineTint(.accentColor)
    }
}

/// Artwork (or the placeholder music-note glyph — never a broken image).
@available(iOS 16.1, *)
struct LiveActivityArtworkView: View {
    let artworkData: Data?
    var cornerRadius: CGFloat = 8

    var body: some View {
        if let image = artworkData.flatMap(UIImage.init(data:)) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        } else {
            Image(systemName: "music.note")
                .font(.title3)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: cornerRadius))
        }
    }
}

/// The animated waveform bars: heights derive purely from the TimelineView
/// phase (`LiveWaveformModel`) — no mutable state, no timers.
@available(iOS 16.1, *)
struct LiveWaveformBarsView: View {
    let isPlaying: Bool
    var tint: Color = .accentColor
    var barCount: Int = 4

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { timeline in
            let phase = Int(timeline.date.timeIntervalSinceReferenceDate * 2.0)
            let heights = LiveWaveformModel.heights(
                phase: phase,
                barCount: barCount,
                isPlaying: isPlaying
            )
            HStack(alignment: .center, spacing: 3) {
                ForEach(0..<barCount, id: \.self) { index in
                    Capsule()
                        .fill(tint)
                        .frame(width: 3.5, height: max(4, heights[index] * 18))
                }
            }
            .animation(.easeInOut(duration: 0.45), value: heights)
        }
    }
}

/// The animated scrubber: interpolates between 1 Hz content updates from
/// the hardware-anchored position (`LiveActivityMath` — MP-DEC-03).
@available(iOS 16.1, *)
struct LiveScrubberView: View {
    let state: StreamifyPlaybackAttributes.ContentState
    var tint: Color = .accentColor

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { timeline in
            let fraction = LiveActivityMath.progress(
                positionSeconds: state.positionSeconds,
                positionUpdatedAt: state.positionUpdatedAt,
                durationSeconds: state.durationSeconds,
                now: timeline.date,
                isPlaying: state.isPlaying
            )
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    Capsule().fill(.quaternary)
                    Capsule()
                        .fill(tint)
                        .frame(width: (fraction ?? 0) * proxy.size.width)
                }
            }
            .frame(height: 5)
            .opacity(fraction == nil ? 0.35 : 1.0)
        }
    }
}

/// Interactive transport (iOS 17 AppIntent buttons — the same intents the
/// widgets use; iOS 16.1 renders the non-interactive fallback).
@available(iOS 16.1, *)
struct LiveTransportControlsView: View {
    let state: StreamifyPlaybackAttributes.ContentState

    var body: some View {
        if #available(iOS 17.0, *) {
            HStack(spacing: 14) {
                Button(intent: PlayPausePlaybackIntent()) {
                    Image(systemName: state.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                Button(intent: NextTrackPlaybackIntent()) {
                    Image(systemName: "forward.fill")
                        .font(.title3)
                        .foregroundStyle(.white.opacity(0.85))
                }
                .buttonStyle(.plain)
            }
        } else {
            // iOS 16.1: the island is presentation-only; the lockscreen
            // transport (MPRemoteCommandCenter) remains the control surface.
            Image(systemName: state.isPlaying ? "waveform" : "pause.circle")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
    }
}

#endif  // canImport(ActivityKit)
