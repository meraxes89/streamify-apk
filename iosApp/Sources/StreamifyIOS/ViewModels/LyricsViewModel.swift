import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 / SW-ENG4-03 (Engineer 4): the lyrics state holder — fetches
// synced LRC content for the active track through the feature seam, binds it
// into the LyricPacerService, and exposes the per-track user sync offset.
//
// PUSH DISCIPLINE: fetching is one async call per track identity (no polling,
// no retry loops). The 120 Hz karaoke cursor is NOT observable state — the
// view evaluates the pacer's pure cursor functions per display frame inside
// TimelineView(.animation), so per-frame reads never invalidate the whole
// lyrics surface.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class LyricsViewModel {

    private(set) var isLoading = false
    private(set) var trackId: String = ""
    private(set) var trackTitle: String?
    private(set) var trackArtist: String?
    private(set) var lastError: String?

    /// The karaoke cursor (document + user sync offset + pure cursor math).
    let pacer = LyricPacerService()

    private let library: any FeatureLibrary
    private var fetchTask: Task<Void, Never>?

    init(library: any FeatureLibrary = AppServices.library) {
        self.library = library
    }

    deinit {
        fetchTask?.cancel()
    }

    // MARK: - Fetch + bind

    /// Fetches synced lyrics for the active track and binds the pacer.
    /// Idempotent per track identity; a re-entrant call for a new track
    /// cancels the in-flight fetch for the old one.
    func loadLyrics(for track: PresentationTrack?) {
        guard let track else {
            trackId = ""
            trackTitle = nil
            trackArtist = nil
            pacer.bindTrack(id: "", title: nil, artist: nil, lrcContent: nil)
            return
        }
        guard track.id != trackId || pacer.document == .empty else { return }

        trackId = track.id
        trackTitle = track.title
        trackArtist = track.artist
        lastError = nil
        isLoading = true

        fetchTask?.cancel()
        let library = library
        let id = track.id
        let title = track.title
        let artist = track.artist
        let durationMs = track.durationMs ?? 0
        fetchTask = Task { [weak self] in
            let lrc = await library.fetchLyrics(
                trackId: id,
                title: title,
                artist: artist,
                durationMs: durationMs
            )
            guard !Task.isCancelled else { return }
            guard let self else { return }
            self.isLoading = false
            self.pacer.bindTrack(id: id, title: title, artist: artist, lrcContent: lrc)
            if lrc == nil {
                self.lastError = "No synced lyrics for this track yet"
            }
        }
    }

    // MARK: - User sync offset (persisted per track)

    func nudgeOffsetForward() {
        pacer.adjustOffset(deltaMs: LyricPacerService.offsetNudgeStepMs)
    }

    func nudgeOffsetBackward() {
        pacer.adjustOffset(deltaMs: -LyricPacerService.offsetNudgeStepMs)
    }

    func resetOffset() {
        pacer.resetOffset()
    }

    // MARK: - Projections (line-change cadence, not per frame)

    var hasLyrics: Bool { pacer.hasLyrics }
    var isSynced: Bool { pacer.isSynced }
}
