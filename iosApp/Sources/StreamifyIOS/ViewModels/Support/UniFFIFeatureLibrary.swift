import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B1 (Engineer 2): `UniFFIFeatureLibrary` — the REAL feature lane over
// the UniFFI engine gateway (the type FeatureLibrary.swift's header comment
// has promised since SW-ENG4-01).
//
// SCOPE — what the Rust core actually owns today (UDL CONTRACT_VERSION 2):
//   * Search          — `search_tracks` (the RustyPipe YTM lane)      → live
//   * Home feed       — composed from search (see `homeFeed` below)   → live
//   * Queue state     — engine queue rows + transport cursor          → live
//   * Equalizer       — the ten-band `set_equalizer_band` plane       → live
//   * Jam sessions    — `create_jam_session` / `get_jam_room_state`   → live
//
// Everything else the `FeatureLibrary` protocol describes (lyrics, blend,
// community, wrapped stats, release radar, profiles, ingestion, jam
// transport beyond hosting) has NO engine surface yet — those requirements
// intentionally fall through to the protocol's honest-empty defaults
// (FeatureLibrary.swift:94-137) rather than fabricating content. The
// preview lane (`PreviewFeatureLibrary`) stays strictly fenced off for
// SwiftUI previews and isolated tests (AppServices switches lanes; tests
// inject explicitly).
//
// PUSH DISCIPLINE: queue state arrives as synthesized full-snapshot pushes
// driven by the ENGINE's own snapshot cadence (each admitted transport push
// triggers one in-memory queue re-read + a diffed push) — the same
// MP-DEC-04 discipline as the playback plane, no polling loop of engine
// GETTERS for UI state.
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(streamifyFFI)

final class UniFFIFeatureLibrary: FeatureLibrary, @unchecked Sendable {

    private let gateway: UniFFIEngineGateway

    // ── Push-lane plumbing (single collector per lane, like the preview) ──

    private let lock = NSLock()
    private var queueContinuation: AsyncStream<QueueEvent>.Continuation?
    private var roomContinuation: AsyncStream<JamRoomEvent>.Continuation?
    private var queueRev = 0
    private var lastQueueFingerprint: String?
    /// Echo state for the auto-mix toggle (no engine surface yet — the
    /// toggle is UI-optimistic and re-served in every synthesized push).
    private var autoMixEnabled = true
    nonisolated(unsafe) private var queueBridgeTask: Task<Void, Never>?

    // MARK: - Init

    init(gateway: UniFFIEngineGateway) {
        self.gateway = gateway

        // The queue-plane bridge: the engine's pushed snapshots carry
        // transport truth only (no queue rows), and queue rows change
        // through this process's own play path plus the transport's
        // cursor moves (next/previous). Every admitted engine push
        // triggers ONE in-memory `queue()` re-read and a diffed
        // full-state push onto `queueEvents` — push-driven, never a
        // polling loop.
        queueBridgeTask = Task { [weak self] in
            for await _ in gateway.snapshots {
                guard let self else { return }
                await self.resyncQueue()
            }
        }
    }

    deinit {
        queueBridgeTask?.cancel()
    }

    // MARK: - Search (the `search_tracks` lane)

    func searchTracks(query: String, filter: SearchResultKind?) async -> [SearchRow] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        guard let result = try? await gateway.searchTracks(trimmed, limit: 25) else {
            return [] // honest empty, never a fabricated error surface
        }
        let rows = Self.rows(from: result)
        guard let filter else { return rows }
        return rows.filter { $0.kind == filter }
    }

    func searchSuggestions(query: String) async -> [String] {
        // No engine surface for suggestions (the resolver is query→rows);
        // recent-search history lives in SettingsStore at the view-model
        // seam. Honest empty.
        []
    }

    /// Engine search rows → presentation rows (tracks are `.song`s;
    /// playlist summaries ride as `.playlist` rows).
    static func rows(from result: EngineSearchResult) -> [SearchRow] {
        var rows: [SearchRow] = result.tracks.map { track in
            SearchRow(
                id: track.id,
                title: track.title,
                uploader: track.artist,
                durationMs: track.durationMs > 0 ? track.durationMs : nil,
                artworkUrl: track.thumbnailUrl,
                kind: .song,
                isVerified: false
            )
        }
        rows.append(
            contentsOf: result.playlists.map { playlist in
                SearchRow(
                    id: playlist.id,
                    title: playlist.title,
                    uploader: "Playlist",
                    durationMs: nil,
                    artworkUrl: playlist.thumbnailUrl,
                    kind: .playlist,
                    isVerified: false
                )
            }
        )
        return rows
    }

    // MARK: - Home feed (search-composed; see the header's scope note)

    /// The engine has no native feed surface yet (SW-ENG1-03's expanded
    /// contract is future work), so the real lane composes the feed from
    /// a small set of editorial seed queries through the SAME live search
    /// lane — real resolver results, no fixtures. Seeds mirror the
    /// circadian slot titles (HomeFeed's own vocabulary).
    func homeFeed(hourOfDay: Int) async -> HomeFeed {
        let slot = CircadianSlot.slot(forHour: hourOfDay)
        async let session = gateway.searchTracks(Self.sessionSeed, limit: 12)
        async let circadian = gateway.searchTracks(Self.circadianSeed(for: slot), limit: 12)
        async let trending = gateway.searchTracks(Self.trendingSeed, limit: 12)
        async let mix = gateway.searchTracks(Self.mixSeed, limit: 12)

        let sessionRows = (try? await session).map(Self.rows(from:)) ?? []
        let circadianRows = (try? await circadian).map(Self.rows(from:)) ?? []
        let trendingRows = (try? await trending).map(Self.rows(from:)) ?? []
        let mixRows = (try? await mix).map(Self.rows(from:)) ?? []

        let sessionTracks = sessionRows.filter { $0.kind == .song }.map(\.asPresentationTrack)
        let circadianTracks = circadianRows.filter { $0.kind == .song }.map(\.asPresentationTrack)
        let trendingTracks = trendingRows.filter { $0.kind == .song }.map(\.asPresentationTrack)
        let mixTracks = mixRows.filter { $0.kind == .song }.map(\.asPresentationTrack)

        var feed = HomeFeed()
        feed.sessionRecommendations = Array(sessionTracks.prefix(10))
        feed.circadianRecommendations = Array(circadianTracks.prefix(10))
        feed.trending = Array(trendingTracks.prefix(10))
        // Two seeded daily mixes until the engine grows a native mix plane.
        feed.dailyMixes = [
            DailyMix(number: 1, seedArtist: "Daily Mix 1", tracks: Array(mixTracks.prefix(10))),
            DailyMix(number: 2, seedArtist: "Daily Mix 2", tracks: Array(sessionTracks.dropFirst(10).prefix(10))),
        ]
        // recent / topPlayed: no listening-history surface in the contract
        // yet — honest empties (a fresh engine session has neither).
        return feed
    }

    private static let sessionSeed = "essential tracks"
    private static let trendingSeed = "trending music"
    private static let mixSeed = "new music friday"

    private static func circadianSeed(for slot: CircadianSlot) -> String {
        switch slot {
        case .morning: return "morning energy wake up"
        case .afternoon: return "focus lo-fi flow"
        case .evening: return "golden hour unwind"
        case .lateNight: return "late night deep chill"
        }
    }

    // MARK: - Queue plane (mutations dispatch; state pushes below)

    func moveQueueItem(from: Int, to: Int) async {
        // No engine queue-mutation surface yet (only enqueue/clear exist in
        // the contract) — the reorder lands as a no-op on the real lane.
        // The synthesized push keeps view state consistent.
        await resyncQueue()
    }

    func removeQueueItems(at: [Int]) async {
        // Same as above: no per-row removal surface in the contract yet.
        await resyncQueue()
    }

    func clearPlayedHistory() async {
        await resyncQueue()
    }

    func setAutoMix(enabled: Bool) async {
        lock.withLock { autoMixEnabled = enabled }
        await resyncQueue()
    }

    var queueEvents: AsyncStream<QueueEvent> {
        AsyncStream { continuation in
            lock.withLock { queueContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.clearQueueContinuation()
            }
        }
    }

    private func clearQueueContinuation() {
        lock.withLock { queueContinuation = nil }
    }

    /// One queue re-read → diffed full-state push (called from the
    /// snapshot-driven bridge AND after dispatched mutations).
    private func resyncQueue() async {
        let items = await gateway.queue()
        let tracks = items.map { PresentationTrack($0.track) }
        // The cursor: the engine correlates the current session through
        // buffer_progress().track_id (playback_engine.rs) — the queue row
        // whose id matches is the playing row.
        let currentId = await gateway.bufferProgress()?.trackId
        let currentIndex = currentId.flatMap { id in
            items.firstIndex(where: { $0.track.id == id })
        }
        let isPlaying = await gateway.currentSnapshot()?.status == .playing
        let autoMix = lock.withLock { autoMixEnabled }

        let fingerprint = "\(tracks.map(\.id).joined(separator: ","))|\(currentIndex ?? -1)|\(isPlaying)|\(autoMix)"
        guard fingerprint != lock.withLock({ lastQueueFingerprint }) else { return }

        let rev = lock.withLock {
            queueRev += 1
            lastQueueFingerprint = fingerprint
            let continuation = queueContinuation
            return (queueRev, continuation)
        }
        let snapshot = QueueSnapshot(
            rev: rev.0,
            tracks: tracks,
            currentIndex: currentIndex ?? -1,
            isPlaying: isPlaying,
            autoMixEnabled: autoMix
        )
        rev.1?.yield(.snapshot(snapshot))
    }

    // MARK: - Equalizer (the ten-band `set_equalizer_band` plane)

    func applyEqualizerProfile(_ profile: EqualizerProfile) async {
        // The engine exposes the ten-band curve only; preamp and the
        // true-peak limiter are DSP-side surfaces still inside the Rust
        // core's pipeline (applied at the producer stage) — no per-band
        // write needed for them today.
        for band in profile.bands {
            await gateway.setEqualizerBand(
                EngineEqualizerBand(
                    bandIndex: band.index,
                    frequencyHz: Float(band.centerHz),
                    gainDb: profile.isEnabled ? Float(band.gainDb) : 0,
                    qFactor: Float(min(max(band.q, 0.4), 4.0))
                )
            )
        }
    }

    // MARK: - Jam rooms (host-side live; transport surfaces pending)

    var jamRoomEvents: AsyncStream<JamRoomEvent> {
        AsyncStream { continuation in
            lock.withLock { roomContinuation = continuation }
            continuation.onTermination = { [weak self] _ in
                self?.clearRoomContinuation()
            }
        }
    }

    private func clearRoomContinuation() {
        lock.withLock { roomContinuation = nil }
    }

    func hostJamRoom(name: String) async -> JamRoomSnapshot? {
        guard let sessionName = await gateway.createJamSession(name: name) else { return nil }
        let snapshot = Self.roomSnapshot(sessionName: sessionName, roomState: await gateway.jamRoomState())
        lock.withLock { roomContinuation?.yield(.snapshot(snapshot)) }
        return snapshot
    }

    private static func roomSnapshot(
        sessionName: String,
        roomState: EngineJamRoomState?
    ) -> JamRoomSnapshot {
        JamRoomSnapshot(
            rev: 1,
            sessionCode: roomState?.roomCode ?? sessionName,
            sessionId: sessionName,
            isHost: true,
            topology: .multiRender,
            controlPolicy: .everyone,
            connStatus: .live,
            participants: [
                JamParticipant(
                    nonce: "host",
                    name: sessionName,
                    isHost: true,
                    role: .host,
                    rttMs: 0
                ),
            ],
            queue: []
        )
    }
}

#endif // canImport(streamifyFFI)
