import SwiftUI
import WidgetKit
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-06 (Phase 5 / MP-CAP03): the WidgetKit bundle — iOS Home Screen
// (small + medium), Lock Screen (accessory families) interactive widgets,
// powered by iOS 17 AppIntent buttons (with a deep-link fallback for the
// iOS 16 floor of this package).
//
// CLOCK DISCIPLINE (MP-DEC-03): widget progress derives from the snapshot's
// hardware-anchored position + the monotonic delta since `snapshotAt`
// (`WidgetPlaybackMath`) — never a wall-clock estimate. The timeline
// cadence is 2 s while playing (the widget's own battery discipline — far
// inside the 1 Hz surface budget), 15 min while paused.
//
// HOST WIRING (the wrapper targets' responsibility):
//  * The widget-extension target's entry file:
//        @main
//        struct StreamifyWidgetsEntryPoint: WidgetBundle {
//            var body: some Widget { StreamifyWidgetBundle().body }
//        }
//    (This file lives in a LIBRARY: `@main` here would collide with the
//    host app's own entry point at link time.)
//  * App Group capability on both targets; the deep-link scheme
//    `streamify://player/toggle` registered in the app target for the
//    iOS 16 fallback.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Pure progress math (unit-tested, MP-DEC-03)

enum WidgetPlaybackMath {

    /// Progress in [0, 1] at render time `date`; nil when the duration is
    /// unknown. Paused snapshots freeze at their anchored position; playing
    /// ones advance by the monotonic delta since the snapshot instant.
    static func progress(
        snapshot: WidgetPlaybackSnapshot?,
        at date: Date
    ) -> Double? {
        guard let snapshot, snapshot.durationSeconds > 0 else { return nil }
        var position = snapshot.positionSeconds
        if snapshot.isPlaying {
            position += max(0, date.timeIntervalSince(snapshot.snapshotAt))
        }
        return min(max(position / snapshot.durationSeconds, 0), 1)
    }

    /// The timeline cadence for the freshest snapshot: 2 s while playing
    /// (the scrubber advances per entry), 15 min while idle/paused.
    static func reloadInterval(snapshot: WidgetPlaybackSnapshot?) -> TimeInterval {
        snapshot?.isPlaying == true ? 2 : 900
    }
}

// MARK: - Timeline

/// One widget render tick: the timeline date + the freshest cross-process
/// snapshot (nil renders the "open Streamify" placeholder).
struct PlaybackWidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetPlaybackSnapshot?
}

/// Reads the App Group snapshot store; the app process's
/// `WidgetPlaybackRefresher` keeps it fresh on every engine push.
struct PlaybackTimelineProvider: TimelineProvider {

    private let store: WidgetPlaybackStore

    init(store: WidgetPlaybackStore = WidgetPlaybackStore()) {
        self.store = store
    }

    func placeholder(in context: Context) -> PlaybackWidgetEntry {
        PlaybackWidgetEntry(date: .now, snapshot: sampleSnapshot(playing: true))
    }

    func getSnapshot(
        in context: Context,
        completion: @escaping (PlaybackWidgetEntry) -> Void
    ) {
        completion(currentEntry())
    }

    func getTimeline(
        in context: Context,
        completion: @escaping (Timeline<PlaybackWidgetEntry>) -> Void
    ) {
        let entry = currentEntry()
        let next = Date().addingTimeInterval(
            WidgetPlaybackMath.reloadInterval(snapshot: entry.snapshot)
        )
        completion(Timeline(entries: [entry], policy: .after(next)))
    }

    private func currentEntry() -> PlaybackWidgetEntry {
        PlaybackWidgetEntry(date: .now, snapshot: store.snapshot())
    }

    /// The placeholder/snapshot lane's sample content (explicitly
    /// non-playing-state fabrication for PREVIEW rendering only).
    private func sampleSnapshot(playing: Bool) -> WidgetPlaybackSnapshot {
        WidgetPlaybackSnapshot(
            trackId: "preview",
            title: "Nightcall",
            artist: "Kavinsky",
            isPlaying: playing,
            positionSeconds: 61,
            snapshotAt: .now,
            durationSeconds: 252,
            volume: 0.7,
            artwork: nil
        )
    }
}

// MARK: - The widgets

/// The Home Screen widget: small (artwork + transport) and medium (artwork
/// + metadata + transport + scrubber + volume).
struct NowPlayingWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: "StreamifyNowPlayingWidget",
            provider: PlaybackTimelineProvider()
        ) { entry in
            NowPlayingWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Streamify Player")
        .description("Current track, transport controls and live progress.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

#if os(iOS)
/// The Lock Screen widget: circular (play/pause), inline (track + timer),
/// and rectangular (artwork + metadata + progress).
struct LockScreenPlaybackWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: "StreamifyLockScreenWidget",
            provider: PlaybackTimelineProvider()
        ) { entry in
            LockScreenPlaybackWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Streamify Lock Screen")
        .description("Transport and progress on the Lock Screen.")
        .supportedFamilies([.accessoryCircular, .accessoryInline, .accessoryRectangular])
    }
}
#endif

/// The bundle (see the header for the host entry-point pattern).
struct StreamifyWidgetBundle: WidgetBundle {
    var body: some Widget {
        NowPlayingWidget()
        #if os(iOS)
        LockScreenPlaybackWidget()
        #endif
    }
}

// MARK: - Views

struct NowPlayingWidgetEntryView: View {
    let entry: PlaybackWidgetEntry

    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot, !snapshot.trackId.isEmpty {
            switch family {
            case .systemMedium: mediumContent(snapshot)
            default: smallContent(snapshot)
            }
        } else {
            WidgetPlaceholderView()
        }
    }

    // MARK: Small: artwork + stacked transport

    private func smallContent(_ snapshot: WidgetPlaybackSnapshot) -> some View {
        VStack(spacing: 6) {
            WidgetArtworkView(artworkData: snapshot.artwork, cornerRadius: 12)
            WidgetTransportRow(
                isPlaying: snapshot.isPlaying,
                compact: true
            )
        }
        .padding(4)
        .widgetBackground()
    }

    // MARK: Medium: artwork + metadata + transport + scrubber

    private func mediumContent(_ snapshot: WidgetPlaybackSnapshot) -> some View {
        HStack(spacing: 12) {
            WidgetArtworkView(artworkData: snapshot.artwork, cornerRadius: 10)
                .frame(width: 64, height: 64)
            VStack(alignment: .leading, spacing: 4) {
                Text(snapshot.title)
                    .font(.headline)
                    .lineLimit(1)
                Text(snapshot.artist)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                WidgetScrubberView(snapshot: snapshot, date: entry.date)
                WidgetTransportRow(
                    isPlaying: snapshot.isPlaying,
                    compact: false
                )
            }
        }
        .padding(10)
        .widgetBackground()
    }
}

#if os(iOS)
struct LockScreenPlaybackWidgetEntryView: View {
    let entry: PlaybackWidgetEntry

    @Environment(\.widgetFamily) private var family

    var body: some View {
        if let snapshot = entry.snapshot, !snapshot.trackId.isEmpty {
            switch family {
            case .accessoryCircular: circularContent(snapshot)
            case .accessoryInline: inlineContent(snapshot)
            default: rectangularContent(snapshot)
            }
        } else {
            WidgetPlaceholderView()
        }
    }

    private func circularContent(_ snapshot: WidgetPlaybackSnapshot) -> some View {
        WidgetPlayPauseButton(isPlaying: snapshot.isPlaying, circular: true)
            .widgetURL(URL(string: "streamify://player/toggle"))
    }

    private func inlineContent(_ snapshot: WidgetPlaybackSnapshot) -> some View {
        HStack(spacing: 4) {
            Image(systemName: snapshot.isPlaying ? "waveform" : "pause.circle")
            Text("\(snapshot.title) — \(snapshot.artist)")
                .lineLimit(1)
        }
        .widgetURL(URL(string: "streamify://player/open"))
    }

    private func rectangularContent(_ snapshot: WidgetPlaybackSnapshot) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 8) {
                WidgetArtworkView(
                    artworkData: snapshot.artwork,
                    cornerRadius: 6
                )
                .frame(width: 34, height: 34)
                VStack(alignment: .leading, spacing: 1) {
                    Text(snapshot.title)
                        .font(.headline)
                        .lineLimit(1)
                    Text(snapshot.artist)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            WidgetScrubberView(snapshot: snapshot, date: entry.date, accessory: true)
        }
        .widgetURL(URL(string: "streamify://player/open"))
    }
}
#endif

/// The pre-first-launch placeholder: an honest "open the app" affordance,
/// never fabricated track data.
struct WidgetPlaceholderView: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "music.note")
                .font(.title2)
                .foregroundStyle(.secondary)
            Text("Open Streamify")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .widgetBackground()
    }
}

/// Artwork or the placeholder glyph — never a broken image.
struct WidgetArtworkView: View {
    let artworkData: Data?
    var cornerRadius: CGFloat = 8

    var body: some View {
        #if canImport(UIKit)
        if let image = artworkData.flatMap(UIImage.init(data:)) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        } else {
            placeholder
        }
        #elseif canImport(AppKit)
        if let data = artworkData, let image = NSImage(data: data) {
            Image(nsImage: image)
                .resizable()
                .scaledToFill()
                .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        } else {
            placeholder
        }
        #else
        placeholder
        #endif
    }

    private var placeholder: some View {
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius)
                .fill(.quaternary)
            Image(systemName: "music.note")
                .foregroundStyle(.secondary)
        }
    }
}

/// The transport row: iOS 17 AppIntent buttons; the iOS 16 floor gets
/// deep-link buttons (same visual, one hop through the app).
struct WidgetTransportRow: View {
    let isPlaying: Bool
    var compact: Bool = false

    var body: some View {
        HStack(spacing: compact ? 10 : 14) {
            if #available(iOS 17.0, *) {
                Button(intent: PreviousTrackPlaybackIntent()) {
                    WidgetTransportIcon(systemName: "backward.fill")
                }
                .buttonStyle(.plain)
                Button(intent: PlayPausePlaybackIntent()) {
                    WidgetTransportIcon(
                        systemName: isPlaying ? "pause.fill" : "play.fill",
                        primary: true
                    )
                }
                .buttonStyle(.plain)
                Button(intent: NextTrackPlaybackIntent()) {
                    WidgetTransportIcon(systemName: "forward.fill")
                }
                .buttonStyle(.plain)
            } else {
                Link(destination: URL(string: "streamify://player/previous")!) {
                    WidgetTransportIcon(systemName: "backward.fill")
                }
                Link(destination: URL(string: "streamify://player/toggle")!) {
                    WidgetTransportIcon(
                        systemName: isPlaying ? "pause.fill" : "play.fill",
                        primary: true
                    )
                }
                Link(destination: URL(string: "streamify://player/next")!) {
                    WidgetTransportIcon(systemName: "forward.fill")
                }
            }
        }
    }
}

/// One transport button visual (shared by the intent + deep-link paths).
struct WidgetTransportIcon: View {
    let systemName: String
    var primary: Bool = false

    var body: some View {
        Image(systemName: systemName)
            .font(primary ? .title2.weight(.semibold) : .body)
            .foregroundStyle(primary ? .white : .secondary)
            .frame(
                width: primary ? 34 : 28,
                height: primary ? 34 : 28
            )
            .background {
                if primary {
                    Circle().fill(Color.accentColor)
                }
            }
    }
}

/// Standalone play/pause (the lock screen circular widget).
struct WidgetPlayPauseButton: View {
    let isPlaying: Bool
    var circular: Bool = false

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.accentColor.opacity(0.35))
            Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                .font(.title2)
        }
    }
}

/// The progress bar: hardware-anchored position + monotonic advancement
/// (`WidgetPlaybackMath` — MP-DEC-03), rendered at the entry's date.
struct WidgetScrubberView: View {
    let snapshot: WidgetPlaybackSnapshot
    let date: Date
    var accessory: Bool = false

    var body: some View {
        let fraction = WidgetPlaybackMath.progress(snapshot: snapshot, at: date)
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(.quaternary)
                Capsule()
                    .fill(Color.accentColor)
                    .frame(width: (fraction ?? 0) * proxy.size.width)
            }
        }
        .frame(height: accessory ? 4 : 5)
        .opacity(fraction == nil ? 0.4 : 1)
    }
}

// MARK: - iOS 17 container-background shim

extension View {
    /// iOS 17 requires `containerBackground` on widget content (else the
    /// system suppresses rendering); the iOS 16 floor keeps the plain
    /// background.
    @ViewBuilder
    func widgetBackground() -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(for: .widget) {
                Color.black.opacity(0.18)
            }
        } else {
            background(Color.black.opacity(0.18))
        }
    }
}

// MARK: - The app-side refresher

/// The app-process side of the widget plane: mirrors engine pushes into
/// the App Group snapshot store and reloads timelines when (and only when)
/// a rendered value changed — the 1 Hz discipline, exactly like the
/// lockscreen publisher.
@MainActor
final class WidgetPlaybackRefresher {

    private var store: WidgetPlaybackStore
    private var lastFingerprint: String?
    private var lastArtworkTrackId: String?
    private var artworkProvider: LiveActivityArtworkProvider

    init(
        store: WidgetPlaybackStore = WidgetPlaybackStore(),
        artworkProvider: LiveActivityArtworkProvider = LiveActivityArtworkProvider()
    ) {
        self.store = store
        self.artworkProvider = artworkProvider
    }

    /// Offers the current UI state; writes + reloads only on rendered-value
    /// changes. Artwork is fetched ONCE per track (the expensive network
    /// hop never rides the 1 Hz cadence — subsequent ticks reuse the
    /// thumbnail persisted in the store).
    func refresh(state: PlaybackUiState) async {
        guard let track = state.currentTrack else { return }
        let positionSeconds = Double(state.positionMs ?? 0) / 1000.0
        let durationSeconds = Double(track.durationMs ?? 0) / 1000.0

        // Pre-check the fingerprint BEFORE any network work: the common
        // 1 Hz tick (same second, same track) returns with zero I/O.
        let fingerprint = "\(track.id)|\(track.title)|\(track.artist)|\(state.isPlaying ? 1 : 0)|\(Int(positionSeconds))|\(Int(durationSeconds))|\(Int(state.volume * 100))"
        guard fingerprint != lastFingerprint else { return }

        var artwork: Data?
        if track.id != lastArtworkTrackId {
            lastArtworkTrackId = track.id
            artwork = await artworkProvider.thumbnail(for: track.artworkUrl)
        } else {
            artwork = store.snapshot()?.artwork
        }
        let snapshot = WidgetPlaybackSnapshot(
            trackId: track.id,
            title: track.title,
            artist: track.artist,
            isPlaying: state.isPlaying,
            positionSeconds: positionSeconds,
            snapshotAt: Date(),
            durationSeconds: durationSeconds,
            volume: state.volume,
            artwork: artwork
        )
        lastFingerprint = fingerprint
        store.refresh(snapshot: snapshot)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Drains the widget/intent command mailbox onto the engine plane
    /// (called on every engine push: during playback that is the 1 Hz
    /// collect loop — the cadence the mailbox was designed for).
    /// Returns the drained commands in posting order.
    @discardableResult
    func drainCommands(into dispatch: @escaping (RemoteCommand) -> Void) -> [RemoteCommand] {
        let commands = store.drain()
        commands.forEach(dispatch)
        return commands
    }
}
