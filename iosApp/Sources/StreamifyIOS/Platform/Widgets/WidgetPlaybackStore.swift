import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG3-06 (Phase 5): the widget/intent data plane — a cross-process
// mailbox + playback snapshot store over an App Group container.
//
// Widgets, Live Activity buttons (iOS 17 AppIntents), and the macOS menu
// bar extra run OUTSIDE the engine's process: the ONLY sanctioned bridge
// is a tiny shared container (UserDefaults suite) the app process owns:
//
//   ┌────────────────┐  post(command)   ┌──────────────────┐  drain()
//   │ Widget process │ ───────────────► │ App Group mailbox │ ──────────► EngineGateway
//   │ Intent perform │                  │ (bounded FIFO)    │              (PlayerCommand plane)
//   └────────────────┘                  └──────────────────┘
//   ┌────────────────┐  refresh(snap)   ┌──────────────────┐  read()
//   │ App process    │ ───────────────► │ Snapshot store    │ ──────────► Widget timeline
//   └────────────────┘                  └──────────────────┘
//
// The mailbox is bounded (stale commands age out), the snapshot is
// single-slot latest-wins (widgets always render the freshest engine
// truth), and both are pure-value Codable seams — the XCTest lane drives
// them with an isolated UserDefaults suite, zero processes.
//
// App Group wiring: `suiteName` = the team-prefixed group id in
// production; nil degrades to the standard suite (single-process / tests).
// ─────────────────────────────────────────────────────────────────────────────

/// The snapshot widgets render (the widget-side mirror of
/// `PlaybackUiState`'s rendered values).
struct WidgetPlaybackSnapshot: Codable, Equatable {
    let trackId: String
    let title: String
    let artist: String
    let isPlaying: Bool
    /// Hardware DAC-derived position at `snapshotAt` (seconds).
    let positionSeconds: Double
    let snapshotAt: Date
    let durationSeconds: Double
    let volume: Double
    /// Downsampled JPEG thumbnail (bounded like the Live Activity's).
    let artwork: Data?

    /// The rendered-value fingerprint (whole-second position): two
    /// snapshots with equal fingerprints would repaint identically — the
    /// widget refresher skips store writes + timeline reloads for them
    /// (the same 1 Hz discipline as the lockscreen surface).
    var renderedFingerprint: String {
        "\(trackId)|\(title)|\(artist)|\(isPlaying ? 1 : 0)|\(Int(positionSeconds))|\(Int(durationSeconds))|\(Int(volume * 100))"
    }

    /// The pre-app placeholder (the menu-bar volume slider before the
    /// first snapshot lands): empty identity, zeroed transport.
    static let placeholder = WidgetPlaybackSnapshot(
        trackId: "",
        title: "",
        artist: "",
        isPlaying: false,
        positionSeconds: 0,
        snapshotAt: Date(),
        durationSeconds: 0,
        volume: 0,
        artwork: nil
    )

    /// Copy with a new output volume (clamped to [0, 1]).
    func withVolume(_ newVolume: Double) -> WidgetPlaybackSnapshot {
        WidgetPlaybackSnapshot(
            trackId: trackId,
            title: title,
            artist: artist,
            isPlaying: isPlaying,
            positionSeconds: positionSeconds,
            snapshotAt: snapshotAt,
            durationSeconds: durationSeconds,
            volume: min(max(newVolume, 0), 1),
            artwork: artwork
        )
    }
}

/// One mailbox entry: a transport intent + its posting instant (bounded
/// FIFO; stale entries age out at drain time).
struct WidgetCommandEntry: Codable, Equatable {
    let commandKind: String
    let positionMs: Int?
    let postedAt: Date
}

/// The transport intents that can cross the mailbox (the widget/intent
/// subset of the RemoteCommand plane).
enum WidgetTransportCommand: String, Codable, CaseIterable {
    case play
    case pause
    case togglePlayPause
    case next
    case previous

    var remoteCommand: RemoteCommand {
        switch self {
        case .play: return .play
        case .pause: return .pause
        case .togglePlayPause: return .togglePlayPause
        case .next: return .next
        case .previous: return .previous
        }
    }
}

/// Cross-process mailbox + snapshot store over one UserDefaults suite.
/// Thread-safe by UserDefaults' own locking; every accessor is a value
/// type over data — injectable, deterministically testable.
struct WidgetPlaybackStore {

    /// Bounded queue: un-drained commands older than this are dropped at
    /// drain time (a stale "next" landing minutes later is worse than a
    /// dropped one).
    static let maxCommandAgeSeconds: TimeInterval = 30
    /// FIFO bound (a jammed queue cannot grow unbounded).
    static let maxQueuedCommands = 16

    static let commandsKey = "streamify.widget.commandMailbox"
    static let snapshotKey = "streamify.widget.playbackSnapshot"

    private let defaults: UserDefaults
    /// Injectable clock (drives entry aging deterministically in tests).
    private let now: () -> Date

    /// - Parameters:
    ///   - suiteName: the App Group id in production; nil uses the standard
    ///     suite (single-process / test mode).
    ///   - now: the posting/aging clock (tests drive virtual time).
    init(
        suiteName: String? = nil,
        now: @escaping () -> Date = { Date() }
    ) {
        if let suiteName,
           let suite = UserDefaults(suiteName: suiteName) {
            defaults = suite
        } else {
            defaults = .standard
        }
        self.now = now
    }

    /// Injectable seams (the XCTest lane installs an isolated suite +
    /// virtual clock).
    init(
        defaults: UserDefaults,
        now: @escaping () -> Date = { Date() }
    ) {
        self.defaults = defaults
        self.now = now
    }

    // MARK: - Command mailbox (widget → app)

    /// Posts one transport intent (bounded: the oldest entry is evicted at
    /// `maxQueuedCommands`).
    mutating func post(_ command: WidgetTransportCommand) {
        var entries = drainedRawEntries()
        entries.append(
            WidgetCommandEntry(
                commandKind: command.rawValue,
                positionMs: nil,
                postedAt: now()
            )
        )
        if entries.count > Self.maxQueuedCommands {
            entries = Array(entries.suffix(Self.maxQueuedCommands))
        }
        write(entries)
    }

    /// Posts a seek intent (position-carrying).
    mutating func postSeek(positionMs: Int) {
        var entries = drainedRawEntries()
        entries.append(
            WidgetCommandEntry(
                commandKind: "seek",
                positionMs: positionMs,
                postedAt: now()
            )
        )
        if entries.count > Self.maxQueuedCommands {
            entries = Array(entries.suffix(Self.maxQueuedCommands))
        }
        write(entries)
    }

    /// Posts a volume intent: volume rides the SNAPSHOT (latest-wins — the
    /// slider's value is absolute, not incremental), never the FIFO queue.
    mutating func postVolume(_ volume: Double) {
        var current = snapshot() ?? WidgetPlaybackSnapshot.placeholder
        current = current.withVolume(volume)
        refresh(snapshot: current)
    }

    /// Drains the mailbox: returns the still-fresh commands in posting
    /// order and empties the queue. Seek/volume entries resolve to
    /// `RemoteCommand`s on the same plane as the discrete transports.
    mutating func drain() -> [RemoteCommand] {
        let entries = drainedRawEntries()
        let reference = now()
        let fresh = entries.filter {
            reference.timeIntervalSince($0.postedAt) < Self.maxCommandAgeSeconds
        }
        write(fresh.isEmpty ? nil : fresh)
        return fresh.compactMap(Self.resolve)
    }

    /// Resolves one entry onto the RemoteCommand plane (nil for unknown /
    /// corrupt kinds — never a crash over shared data).
    static func resolve(_ entry: WidgetCommandEntry) -> RemoteCommand? {
        switch entry.commandKind {
        case WidgetTransportCommand.togglePlayPause.rawValue: return .togglePlayPause
        case WidgetTransportCommand.play.rawValue: return .play
        case WidgetTransportCommand.pause.rawValue: return .pause
        case WidgetTransportCommand.next.rawValue: return .next
        case WidgetTransportCommand.previous.rawValue: return .previous
        case "seek":
            guard let positionMs = entry.positionMs else { return nil }
            return .seek(positionMs: positionMs)
        default: return nil
        }
    }

    // MARK: - Snapshot store (app → widget)

    /// Latest-wins snapshot write; returns false when the write was
    /// skipped (identical rendered fingerprint — the 1 Hz discipline).
    @discardableResult
    mutating func refresh(snapshot: WidgetPlaybackSnapshot) -> Bool {
        // `self.` disambiguates the reader method from the like-named
        // parameter (a bare `snapshot()` would try to CALL the parameter).
        if let current = self.snapshot(), current.renderedFingerprint == snapshot.renderedFingerprint {
            return false
        }
        if let data = try? JSONEncoder().encode(snapshot) {
            defaults.set(data, forKey: Self.snapshotKey)
            return true
        }
        return false
    }

    /// The freshest snapshot, nil before the app ever wrote one.
    func snapshot() -> WidgetPlaybackSnapshot? {
        guard let data = defaults.data(forKey: Self.snapshotKey) else { return nil }
        return try? JSONDecoder().decode(WidgetPlaybackSnapshot.self, from: data)
    }

    // MARK: - Raw plumbing

    private func drainedRawEntries() -> [WidgetCommandEntry] {
        guard let data = defaults.data(forKey: Self.commandsKey) else { return [] }
        return (try? JSONDecoder().decode([WidgetCommandEntry].self, from: data)) ?? []
    }

    private func write(_ entries: [WidgetCommandEntry]?) {
        guard let entries, !entries.isEmpty else {
            defaults.removeObject(forKey: Self.commandsKey)
            return
        }
        if let data = try? JSONEncoder().encode(entries) {
            defaults.set(data, forKey: Self.commandsKey)
        }
    }
}
