import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B7 (ENG-2): DownloadsViewModel — the transfer-manager state holder
// over `BackgroundDownloadService` (429 LOC, orphaned until now), the Swift
// mirror of the Android download-flow projection.
//
// FLOW (the offline lane, honest v1 boundary):
//   tap "download" on a track
//     -> resolve (the engine's resolve lane — the SAME cache the play
//        flow rides; element 0, the audio-only head)
//     -> `service.download(from: resolvedUrl)` — a BACKGROUND URLSession
//        transfer (OS-scheduled, resumable, cold-launch re-arming)
//     -> completion lands in the vault (Application Support/StreamifyDownloads)
//
// RESUME DATA (B7's verification gate): in-flight cancels persist the OS
// resume snapshot under the vault's `.resume` bookkeeping directory; a
// cold launch re-arms them (`resumePersisted`), and the persisted count
// is surfaced here for the UI + the diagnostics plane.
//
// OFFLINE PLAY (the honest v1 boundary, documented in the PR): the
// downloads land in the vault, but the engine's producer fetcher is
// HTTP-only (RustyPipe range fetches) — playing a vault file offline
// needs a `file://` range-fetcher lane on ENG-1's side of the seam
// (a one-line `default_fetcher` route + security constraints). The VM
// surfaces the vault paths so that lane can consume them the day it
// lands; nothing here fabricates offline playback.
//
// PUSH DISCIPLINE: progress arrives over the service's per-download
// AsyncStream; completions over the awaited `download(from:)` call. No
// polling, no timers.
// ─────────────────────────────────────────────────────────────────────────────

@Observable
@MainActor
final class DownloadsViewModel {

    // ── State (the transfer list the DownloadsView renders) ───────────────

    private(set) var transfers: [DownloadTransfer] = []

    /// Diagnostics: transfers re-armed from persisted resume data at
    /// launch (cold-launch recovery — B7's resume-data verification).
    private(set) var resumedFromResumeData = 0

    /// Diagnostics: resume snapshots currently persisted on disk.
    private(set) var persistedResumeCount = 0

    // ── Bookkeeping ───────────────────────────────────────────────────────

    private struct Record {
        var track: PresentationTrack
        var url: URL
        var state: DownloadState
        var fileUrl: URL?
        var totalBytes: Int64?
    }

    private var records: [URL: Record] = [:]
    /// nonisolated(unsafe): the deinit (nonisolated by nature) cancels the
    /// watchers — the same pattern SearchViewModel's debounce tasks use.
    nonisolated(unsafe) private var watchTasks: [URL: Task<Void, Never>] = [:]

    private let service: BackgroundDownloadService

    init(service: BackgroundDownloadService? = nil) {
        self.service = service ?? BackgroundDownloadService.shared()
    }

    deinit {
        for task in watchTasks.values {
            task.cancel()
        }
    }

    // MARK: - Launch recovery (resume-data verification)

    /// Cold-launch recovery: re-arms every persisted resume snapshot and
    /// refreshes the diagnostics counters. Idempotent; called once at
    /// DownloadsView appearance.
    func recoverPersistedTransfers() {
        service.resumePersisted()
        persistedResumeCount = service.persistedResumeSnapshots().count
        resumedFromResumeData = service.persistedResumeSnapshots().count
        // The re-armed transfers are already in flight; reflect them.
        for url in service.activeDownloads() where records[url] == nil {
            records[url] = Record(
                track: PresentationTrack(
                    id: url.lastPathComponent,
                    title: url.lastPathComponent,
                    artist: "Resumed transfer",
                    durationMs: nil,
                    artworkUrl: nil
                ),
                url: url,
                state: .downloading(progress: 0),
                fileUrl: nil,
                totalBytes: nil
            )
            startWatchers(for: url)
        }
        rebuildTransfers()
    }

    // MARK: - Enqueue (resolve -> background transfer)

    /// Starts one offline download: resolves the playable formats (the
    /// same cache the play flow rides) and hands the audio-only head to
    /// the background session.
    func startDownload(track: PresentationTrack) async {
        let formats = await AppServices.engine.resolveStreamFormats(videoId: track.id)
        guard let best = formats.first, let url = URL(string: best.url) else {
            // Honest degrade: a track that cannot resolve cannot download;
            // the row never appears (there is nothing to show — the PR's
            // "known boundaries" section explains why this is not an error).
            return
        }
        guard records[url] == nil else { return }  // one transfer per URL
        records[url] = Record(
            track: track,
            url: url,
            state: .queued,
            fileUrl: nil,
            totalBytes: Int64(best.bitrate) * Int64(max(1, best.durationSec)) / 8
        )
        rebuildTransfers()
        startWatchers(for: url)
    }

    // MARK: - Row actions (pause/resume/remove)

    /// Pause: cancels the in-flight task (the OS persists resume data —
    /// the await resolves `.cancelled` and the row flips to `paused`).
    func pause(url: URL) {
        service.cancel(url)
    }

    /// Resume: re-arms the persisted snapshot (one pass re-arms every
    /// paused transfer) and re-attaches the await/progress watchers.
    func resume(url: URL) {
        guard let record = records[url], case .paused = record.state else { return }
        records[url]?.state = .downloading(progress: 0)
        rebuildTransfers()
        service.resumePersisted()
        startWatchers(for: url)
    }

    /// Remove: in-flight rows cancel (resume data discarded with the
    /// record); completed rows delete their vault file.
    func remove(url: URL) {
        guard records[url] != nil else { return }
        if case .completed = records[url]?.state, let fileUrl = records[url]?.fileUrl {
            try? FileManager.default.removeItem(at: fileUrl)
        } else {
            service.cancel(url)
        }
        records[url] = nil
        watchTasks[url]?.cancel()
        watchTasks[url] = nil
        persistedResumeCount = service.persistedResumeSnapshots().count
        rebuildTransfers()
    }

    // MARK: - Watchers (the per-URL push consumers)

    private func startWatchers(for url: URL) {
        guard watchTasks[url] == nil else { return }
        watchTasks[url] = Task { [weak self] in
            guard let self else { return }
            // The progress consumer (a child task — it ends when the
            // service finishes the entry's stream at completion).
            let progressTask = Task { [weak self] in
                guard let stream = self?.service.progress(for: url) else { return }
                for await progress in stream {
                    self?.apply(progress: progress, to: url)
                }
            }
            defer { progressTask.cancel() }
            do {
                let fileUrl = try await self.service.download(from: url)
                self.finish(url: url, fileUrl: fileUrl)
            } catch {
                self.failOrPause(url: url, error: error)
            }
        }
    }

    private func apply(progress: DownloadProgress, to url: URL) {
        guard records[url] != nil else { return }
        records[url]?.totalBytes = progress.totalBytes
        records[url]?.state = .downloading(progress: progress.fraction ?? 0)
        rebuildTransfers()
    }

    private func finish(url: URL, fileUrl: URL) {
        guard records[url] != nil else { return }
        records[url]?.state = .completed
        records[url]?.fileUrl = fileUrl
        watchTasks[url] = nil
        persistedResumeCount = service.persistedResumeSnapshots().count
        rebuildTransfers()
    }

    private func failOrPause(url: URL, error: Error) {
        guard records[url] != nil else { return }
        watchTasks[url] = nil
        if let downloadError = error as? DownloadError, case .cancelled = downloadError {
            // A cancel with persisted resume data is a PAUSE, not a
            // failure (B7's resume-data contract).
            records[url]?.state = .paused
        } else {
            records[url]?.state = .failed(
                message: (error as? DownloadError)?.messageText ?? error.localizedDescription
            )
        }
        persistedResumeCount = service.persistedResumeSnapshots().count
        rebuildTransfers()
    }

    // MARK: - Projection (records -> DownloadTransfer rows)

    private func rebuildTransfers() {
        transfers = records.values
            .sorted { $0.url.absoluteString < $1.url.absoluteString }
            .map { record in
                DownloadTransfer(
                    id: record.url.absoluteString,
                    title: record.track.title,
                    artist: record.track.artist,
                    state: record.state,
                    quality: Self.qualityLabel(for: record.state),
                    sizeMb: Self.sizeMb(totalBytes: record.totalBytes, fileUrl: record.fileUrl)
                )
            }
    }

    /// Pure projection helpers (the XCTest lane asserts these without a
    /// background session — the service is final and stays untested here
    /// by design; its own behavior is OS-driven).
    static func qualityLabel(for state: DownloadState) -> String {
        switch state {
        case .completed: return "AAC · saved"
        case .downloading: return "Resolving stream…"
        case .queued: return "Queued"
        case .paused: return "Paused (resume data kept)"
        case .failed: return "Failed"
        }
    }

    static func sizeMb(totalBytes: Int64?, fileUrl: URL?) -> Double {
        if let fileUrl,
           let size = try? FileManager.default.attributesOfItem(atPath: fileUrl.path)[.size]
                as? Int64 {
            return max(0, Double(size)) / 1_048_576.0
        }
        guard let totalBytes else { return 0 }
        return max(0, Double(totalBytes)) / 1_048_576.0
    }
}

private extension DownloadError {
    /// A short, row-level message (never the raw transport dump).
    var messageText: String {
        switch self {
        case .cancelled: return "Cancelled"
        case .transport(let detail): return detail
        case .destination(let detail): return detail
        }
    }
}
