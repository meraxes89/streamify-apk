import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// WS-B B7 (Engineer 2): DownloadViewModel — the Downloads screen's state
// holder, wiring the previously-orphaned BackgroundDownloadService into the
// shell: live transfer rows over `DownloadTransfer`, a tiny on-disk manifest
// correlating vault files back to tracks across launches, resume-data
// verification at cold start (the explicit B7 acceptance), honest storage
// accounting, and the offline-play lookup.
//
// ARCHITECTURE (mirrors the house view models — QueueViewModel's task
// discipline, UpdatesHubViewModel's load/refresh split):
//   * ONE child-task PAIR per live URL: an AWAITER owning
//     `service.download(from:)`'s terminal result and a PROGRESS COLLECTOR
//     draining the service's single-consumer progress stream into the row
//     (the service's await contract allows exactly one awaiter per URL —
//     `runTransfer` guards that).
//   * STATE IS DERIVED, NEVER FABRICATED: a row shows a fraction only when
//     the server advertised a length; an unknown total stays indeterminate,
//     a vanished vault file reads "File missing", an unavailable capacity
//     key renders no denominator.
//   * RESUME SEMANTICS (the service's honest surface): `cancel(_:)`
//     persists the OS resume snapshot and the row reads Paused; RESUME is
//     service-wide — `resumePersisted()` re-arms every persisted snapshot
//     (continuing from the transferred byte ranges) and
//     `reattachActiveDownloads()` puts a row + awaiter behind each URL that
//     went live, so no download ever resumes invisibly.
//   * UNITS are decimal (Apple Settings parity): 1 MB = 10^6 B,
//     1 GB = 10^9 B.
// ─────────────────────────────────────────────────────────────────────────────

/// The outcome of B7's cold-launch resume-data verification: how many
/// persisted resume snapshots `start()` found and re-armed.
enum ResumeVerification: Equatable, Sendable {
    /// `count` persisted snapshots were re-armed as live background
    /// downloads (each continues from its transferred byte ranges).
    case verified(count: Int)
    /// No resume snapshots were pending.
    case nonePending
}

@Observable
@MainActor
final class DownloadViewModel {

    // ── Published state (the view currency) ─────────────────────────────────

    /// The transfer rows — one per manifest-known, in-flight or recovered
    /// download (empty until `start()`; never seeded with fixtures).
    private(set) var transfers: [DownloadTransfer] = []

    /// Vault bytes actually on disk (recursive size of the destination
    /// directory), in decimal GB.
    private(set) var usedGb: Double = 0

    /// Volume capacity of the vault, in decimal GB; 0 when the volume key
    /// is unavailable (the view then hides the denominator honestly).
    private(set) var totalGb: Double = 0

    /// B7's explicit acceptance surface: the resume-data verification the
    /// view renders as "N downloads recovered".
    private(set) var resumeVerification: ResumeVerification = .nonePending

    // ── Wiring ───────────────────────────────────────────────────────────────

    private let gateway: any EngineGateway
    private let service: BackgroundDownloadService

    /// The download vault: manifest home + storage-accounting root. This
    /// is the shared service's default destination; tests injecting a
    /// custom-routed service pass a matching directory alongside it.
    private let destinationDirectory: URL
    private let manifestFileURL: URL
    private let resumeDirectory: URL

    // ── Correlation indexes ──────────────────────────────────────────────────

    /// trackId → manifest entry (the on-disk truth).
    private var manifest: [String: ManifestEntry] = [:]
    /// transfer id → remote source URL.
    private var sourceUrlsById: [String: URL] = [:]
    /// transfer id → presentation metadata (offline play / re-resolve).
    private var tracksById: [String: PresentationTrack] = [:]

    /// The per-download task graph. `nonisolated(unsafe)`: both dicts are
    /// mutated only on the main actor; the nonisolated `deinit` reads them
    /// solely to cancel (Task.cancel() is itself thread-safe — the same
    /// discipline as QueueViewModel's collectTask).
    nonisolated(unsafe) private var awaitersByUrl: [URL: Task<Void, Never>] = [:]
    nonisolated(unsafe) private var collectorsByUrl: [URL: Task<Void, Never>] = [:]

    private var didStart = false

    /// One manifest row: correlates a vault file back to its track.
    /// Deliberately tiny — {title, artist, artworkUrl, durationMs,
    /// sourceUrl, fileName} keyed by trackId.
    private struct ManifestEntry: Codable, Sendable {
        let trackId: String
        let title: String
        let artist: String
        let artworkUrl: String?
        let durationMs: Int?
        let sourceUrl: String
        /// The vault file name once the download completed (nil while in
        /// flight — a crash between the vault move and this write is
        /// recovered by deriving the name from the source URL).
        var fileName: String?
    }

    private static let manifestFileName = "downloads-manifest.json"

    // MARK: - Init (optional-with-nil-default discipline)

    /// Creates the downloads state holder. Every dependency is optional
    /// with a nil default: the shared engine gateway and the shared
    /// background-download service resolve on the main actor, INSIDE this
    /// isolated init body (default arguments evaluate in a nonisolated
    /// context — PlaybackViewModel's two-phase discipline).
    init(
        gateway: (any EngineGateway)? = nil,
        service: BackgroundDownloadService? = nil,
        destinationDirectory: URL? = nil
    ) {
        self.gateway = gateway ?? AppServices.engineGateway
        self.service = service ?? BackgroundDownloadService.shared()
        self.destinationDirectory = destinationDirectory
            ?? BackgroundDownloadService.defaultDestinationDirectory()
        self.manifestFileURL = self.destinationDirectory
            .appendingPathComponent(Self.manifestFileName, isDirectory: false)
        self.resumeDirectory = self.destinationDirectory
            .appendingPathComponent(".resume", isDirectory: true)
    }

    deinit {
        // Drop the waiter/collector graph only. The OS-owned background
        // tasks themselves are unaffected (they belong to the session,
        // not to these Tasks) and still finish into the vault — the next
        // `start()` reconciles them through the manifest.
        for task in awaitersByUrl.values { task.cancel() }
        for task in collectorsByUrl.values { task.cancel() }
    }

    // MARK: - Lifecycle

    /// Cold-start bring-up (idempotent; the view's `.task`): loads the
    /// manifest, verifies and re-arms the persisted resume data — B7's
    /// acceptance check; the snapshot count is read BEFORE
    /// `resumePersisted()` consumes the files — then rebuilds the transfer
    /// rows behind live task pairs and refreshes storage truth.
    func start() {
        guard !didStart else { return }
        didStart = true
        loadManifest()
        let pendingUrls = Set(pendingResumeSnapshots())
        service.resumePersisted()
        resumeVerification = pendingUrls.isEmpty
            ? .nonePending
            : .verified(count: pendingUrls.count)
        rebuildTransfers(pendingUrls: pendingUrls)
        refreshStorage()
    }

    /// Pull-to-refresh: reconciles settled rows against the vault (files
    /// that appeared or vanished) and recomputes storage. A refresh that
    /// arrives before `start()` performs the full cold-start bring-up.
    func refresh() async {
        guard didStart else {
            start()
            return
        }
        reconcileVault()
        refreshStorage()
    }

    // MARK: - Downloads

    /// Downloads one track end-to-end: resolves the playable stream via
    /// the engine gateway (`resolveStreamFormats` → `bestAudio`), records
    /// the manifest entry, then starts the background transfer with a
    /// child-task pair behind it. An already-live row is a no-op; a
    /// paused row resumes from its persisted snapshot instead of
    /// restarting. Resolve failures land as a failed row
    /// carrying the error — never a silent drop.
    func download(track: PresentationTrack) async {
        if let existing = transfers.first(where: { $0.id == track.id }) {
            switch existing.state {
            case .downloading, .queued:
                // Exactly one live row per track (the service's await
                // contract allows one awaiter per URL).
                return
            case .paused:
                // A paused row keeps the resume promise: it continues from
                // its persisted snapshot instead of restarting from byte
                // zero (and leaking the stale snapshot to a cold launch).
                tracksById[track.id] = track
                if let url = sourceUrlsById[track.id] {
                    resumeDownload(id: track.id, sourceUrl: url)
                    return
                }
            case .completed, .failed:
                break // an explicit re-download; the vault write is last-wins
            }
        }
        tracksById[track.id] = track
        do {
            let formats = try await gateway.resolveStreamFormats(videoId: track.id)
            guard let best = EngineResolvedStreamFormat.bestAudio(of: formats),
                  let sourceUrl = URL(string: best.url) else {
                upsertRow(DownloadTransfer(
                    id: track.id,
                    title: track.title,
                    artist: track.artist,
                    state: .failed(message: "No playable audio stream"),
                    quality: "Audio",
                    sizeMb: 0,
                    fileURL: nil
                ))
                return
            }
            sourceUrlsById[track.id] = sourceUrl
            manifest[track.id] = ManifestEntry(
                trackId: track.id,
                title: track.title,
                artist: track.artist,
                artworkUrl: track.artworkUrl,
                durationMs: track.durationMs,
                sourceUrl: best.url,
                fileName: nil
            )
            saveManifest()
            upsertRow(DownloadTransfer(
                id: track.id,
                title: track.title,
                artist: track.artist,
                state: .downloading(progress: nil), // unknown until the first delegate sample
                quality: Self.qualityLabel(for: best),
                sizeMb: 0,
                fileURL: nil
            ))
            runTransfer(id: track.id, sourceUrl: sourceUrl)
        } catch {
            upsertRow(DownloadTransfer(
                id: track.id,
                title: track.title,
                artist: track.artist,
                state: .failed(message: "Resolve failed: \(error.localizedDescription)"),
                quality: "Audio",
                sizeMb: 0,
                fileURL: nil
            ))
        }
    }

    // MARK: - Controls

    /// Pause/resume toggle for one row. PAUSE cancels the service task —
    /// the OS resume snapshot is persisted and the awaiter marks the row
    /// Paused when the cancellation lands. RESUME re-arms the persisted
    /// snapshots (service-wide) and continues from the transferred byte
    /// ranges; see `resumeDownload(id:sourceUrl:)`.
    func pauseResume(_ id: String) {
        guard let row = transfers.first(where: { $0.id == id }) else { return }
        switch row.state {
        case .downloading, .queued:
            guard let url = sourceUrlsById[id] else { return }
            service.cancel(url)
        case .paused:
            guard let url = sourceUrlsById[id] else { return }
            resumeDownload(id: id, sourceUrl: url)
        case .completed, .failed:
            break // settled rows use play / retry
        }
    }

    /// Restarts a settled (failed or completed) row: with a recorded source
    /// URL it goes through `resumeDownload(id:sourceUrl:)` — RESUMING from
    /// the persisted resume data when the OS captured any (a restart with
    /// no snapshot is an honest fresh task; the vault write is last-wins
    /// either way) — else it re-resolves the track through the engine
    /// gateway. Live and paused rows are no-ops — they use pause-resume.
    func retry(_ id: String) {
        guard let row = transfers.first(where: { $0.id == id }) else { return }
        switch row.state {
        case .downloading, .queued, .paused:
            return
        case .completed, .failed:
            break
        }
        if let url = sourceUrlsById[id] {
            upsertRow(DownloadTransfer(
                id: row.id,
                title: row.title,
                artist: row.artist,
                state: .downloading(progress: nil),
                quality: row.quality,
                sizeMb: 0,
                fileURL: nil
            ))
            resumeDownload(id: id, sourceUrl: url)
        } else if let track = tracksById[id] {
            Task { await download(track: track) }
        }
    }

    /// Removes one row for good: cancels any live task, discards its
    /// persisted resume snapshot (a REMOVED download must not resurrect at
    /// the next cold launch — `cancel(_:)` always persists a snapshot, so
    /// removal deletes it explicitly), deletes the vault file, the
    /// manifest entry and the row.
    func remove(_ id: String) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        let row = transfers[index]
        if let url = sourceUrlsById[id] {
            awaitersByUrl[url]?.cancel()
            collectorsByUrl[url]?.cancel()
            awaitersByUrl[url] = nil
            collectorsByUrl[url] = nil
            service.cancel(url)
            discardPersistedResumeData(for: url)
            // RACE SWEEP: the service persists its resume snapshot from
            // `cancel(_:)`'s ASYNC completion, which lands after the
            // synchronous discard above. One delayed re-discard removes
            // whatever the OS wrote in the meantime — a removed download
            // must not resurrect at the next cold launch. (A process death
            // inside the window degrades honestly: the next `start()`
            // shows the snapshot as a recovered row.)
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                guard let self, !Task.isCancelled else { return }
                // Skip when the same source is transferring again — a
                // re-download started inside the window owns its fresh
                // snapshot.
                if !self.sourceUrlsById.values.contains(url),
                   !self.manifest.values.contains(where: { $0.sourceUrl == url.absoluteString }) {
                    self.discardPersistedResumeData(for: url)
                }
            }
        }
        if let fileURL = row.fileURL {
            try? FileManager.default.removeItem(at: fileURL)
        }
        manifest[id] = nil
        saveManifest()
        tracksById[id] = nil
        sourceUrlsById[id] = nil
        transfers.remove(at: index)
        refreshStorage()
    }

    // MARK: - Offline play

    /// The manifest track behind a completed transfer, for the view's play
    /// affordance (`onPlayOffline`). The playable URL is NOT resolved
    /// again — it is the vault file itself, carried by the transfer as
    /// `DownloadTransfer.playableUrl` ("file://" + path): the play path
    /// reads `file://` ranges straight off disk. Nil for non-completed
    /// rows or a vanished file (the affordance then hides honestly).
    func offlineTrack(for transfer: DownloadTransfer) -> PresentationTrack? {
        guard case .completed = transfer.state,
              let fileURL = transfer.fileURL,
              FileManager.default.fileExists(atPath: fileURL.path) else {
            return nil
        }
        if let track = tracksById[transfer.id] {
            return track
        }
        if let entry = manifest[transfer.id] {
            return PresentationTrack(
                id: entry.trackId,
                title: entry.title,
                artist: entry.artist,
                durationMs: entry.durationMs,
                artworkUrl: entry.artworkUrl
            )
        }
        return PresentationTrack(
            id: transfer.id,
            title: transfer.title,
            artist: transfer.artist,
            durationMs: nil,
            artworkUrl: nil
        )
    }

    // MARK: - Transfer rows

    private func upsertRow(_ row: DownloadTransfer) {
        if let index = transfers.firstIndex(where: { $0.id == row.id }) {
            transfers[index] = row
        } else {
            transfers.append(row)
        }
    }

    private func setState(_ state: DownloadState, for id: String) {
        updateRow(id: id) { row in
            DownloadTransfer(
                id: row.id,
                title: row.title,
                artist: row.artist,
                state: state,
                quality: row.quality,
                sizeMb: row.sizeMb,
                fileURL: row.fileURL
            )
        }
    }

    private func updateRow(
        id: String,
        _ rebuild: (DownloadTransfer) -> DownloadTransfer
    ) {
        guard let index = transfers.firstIndex(where: { $0.id == id }) else { return }
        transfers[index] = rebuild(transfers[index])
    }

    /// Spawns the child-task pair behind one URL — an AWAITER owning
    /// `download(from:)`'s terminal result and a PROGRESS COLLECTOR
    /// draining the service's single-consumer stream into the row. No-ops
    /// when an awaiter already exists (the service's one-await-per-URL
    /// contract); for a `resumePersisted()`-re-armed URL the awaiter binds
    /// to the ALREADY-RUNNING task instead of creating a second one.
    private func runTransfer(id: String, sourceUrl: URL) {
        guard awaitersByUrl[sourceUrl] == nil else { return }
        setState(.downloading(progress: nil), for: id)
        let service = service
        awaitersByUrl[sourceUrl] = Task { [weak self] in
            defer { self?.awaitersByUrl[sourceUrl] = nil }
            do {
                let fileURL = try await service.download(from: sourceUrl)
                guard let self, !Task.isCancelled else { return }
                self.completeTransfer(id: id, sourceUrl: sourceUrl, fileURL: fileURL)
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.failTransfer(id: id, error: error)
            }
        }
        collectorsByUrl[sourceUrl] = Task { [weak self] in
            defer { self?.collectorsByUrl[sourceUrl] = nil }
            // download(from:) registers its entry synchronously before
            // suspending; poll briefly until the progress stream exists
            // (bounded, so an instantly-finished transfer can never spin
            // here — a late consumer still receives the newest buffered
            // sample, or a finished stream).
            var stream: AsyncStream<DownloadProgress>?
            var attempts = 0
            while stream == nil, attempts < 100, !Task.isCancelled {
                stream = service.progress(for: sourceUrl)
                if stream == nil {
                    try? await Task.sleep(nanoseconds: 20_000_000)
                    attempts += 1
                }
            }
            guard let stream else { return }
            for await sample in stream {
                guard let self, !Task.isCancelled else { break }
                self.applyProgress(sample, to: id)
            }
        }
    }

    /// A download finished: the row flips to Completed with the file's
    /// REAL size and the manifest records the vault file name (an
    /// unmanifested recovery gets a post-hoc entry so the row survives
    /// relaunches — the vault file is real; hiding it would lie by
    /// omission while the storage card counts its bytes).
    private func completeTransfer(id: String, sourceUrl: URL, fileURL: URL) {
        let sizeMb = Self.fileSizeMb(fileURL)
        updateRow(id: id) { row in
            DownloadTransfer(
                id: row.id,
                title: row.title,
                artist: row.artist,
                state: .completed,
                quality: row.quality,
                sizeMb: sizeMb,
                fileURL: fileURL
            )
        }
        if var entry = manifest[id] {
            entry.fileName = fileURL.lastPathComponent
            manifest[id] = entry
        } else {
            let row = transfers.first { $0.id == id }
            manifest[id] = ManifestEntry(
                trackId: id,
                title: row?.title ?? Self.recoveredTitle(for: sourceUrl),
                artist: row?.artist ?? "Recovered download",
                artworkUrl: nil,
                durationMs: nil,
                sourceUrl: sourceUrl.absoluteString,
                fileName: fileURL.lastPathComponent
            )
        }
        saveManifest()
        refreshStorage()
    }

    /// A download failed: a user cancellation reads Paused (the service
    /// persisted the OS resume snapshot); anything else reads Failed with
    /// the transport/destination detail verbatim.
    private func failTransfer(id: String, error: Error) {
        if let downloadError = error as? DownloadError, case .cancelled = downloadError {
            setState(.paused, for: id)
            return
        }
        let message = Self.failureMessage(for: error)
        updateRow(id: id) { row in
            DownloadTransfer(
                id: row.id,
                title: row.title,
                artist: row.artist,
                state: .failed(message: message),
                quality: row.quality,
                sizeMb: row.sizeMb,
                fileURL: nil
            )
        }
    }

    /// One progress sample applied to its row, quantized to 0.1 % so the
    /// repaint cadence tracks VISIBLE change (a 48 MB transfer otherwise
    /// repaints per kilobyte). Identical samples coalesce, and an unknown
    /// total stays indeterminate — never a fabricated fraction.
    private func applyProgress(_ sample: DownloadProgress, to id: String) {
        let fraction = sample.fraction.map { ($0 * 1_000).rounded() / 1_000 }
        let totalMb = sample.totalBytes > 0
            ? Double(sample.totalBytes) / 1_000_000
            : nil
        updateRow(id: id) { row in
            if case .downloading(let shown) = row.state, shown == fraction,
               totalMb == nil || row.sizeMb == totalMb {
                return row
            }
            return DownloadTransfer(
                id: row.id,
                title: row.title,
                artist: row.artist,
                state: .downloading(progress: fraction),
                quality: row.quality,
                sizeMb: totalMb ?? row.sizeMb,
                fileURL: row.fileURL
            )
        }
    }

    // MARK: - Resume + relaunch reconciliation

    /// RESUME, mapped onto the service's actual semantics. The service's
    /// resume surface is SERVICE-WIDE: `resumePersisted()` re-arms every
    /// persisted snapshot as a live task continuing from its transferred
    /// byte ranges; `reattachActiveDownloads()` then puts a row + awaiter
    /// behind EVERY re-armed URL — including the side-effect resumes of
    /// other paused rows, so nothing resumes invisibly. A paused row with
    /// NO persisted snapshot (an interrupted transfer that never wrote
    /// resume data) restarts as a fresh background task — its progress
    /// honestly restarts from zero.
    private func resumeDownload(id: String, sourceUrl: URL) {
        service.resumePersisted()
        reattachActiveDownloads()
        if awaitersByUrl[sourceUrl] == nil, !service.activeDownloads().contains(sourceUrl) {
            runTransfer(id: id, sourceUrl: sourceUrl)
        }
    }

    /// Puts a row + task pair behind every live service URL that has no
    /// awaiter yet (post-`resumePersisted()` reconciliation).
    private func reattachActiveDownloads() {
        for url in service.activeDownloads() {
            guard awaitersByUrl[url] == nil else { continue }
            let id = ensureRow(for: url)
            runTransfer(id: id, sourceUrl: url)
        }
    }

    /// Finds (or creates) the row id for a live source URL, synthesizing
    /// an honest recovery row when no manifest correlation exists.
    private func ensureRow(for url: URL) -> String {
        if let known = sourceUrlsById.first(where: { $0.value == url })?.key {
            return known
        }
        if let entry = manifest.values.first(where: { $0.sourceUrl == url.absoluteString }) {
            sourceUrlsById[entry.trackId] = url
            return entry.trackId
        }
        let id = url.absoluteString
        sourceUrlsById[id] = url
        transfers.append(DownloadTransfer(
            id: id,
            title: Self.recoveredTitle(for: url),
            artist: "Recovered download",
            state: .downloading(progress: nil),
            quality: "Audio",
            sizeMb: 0,
            fileURL: nil
        ))
        return id
    }

    /// Rebuilds the rows from the manifest + vault after a cold start:
    /// vault-backed entries render Completed with their real size, re-armed
    /// snapshots render Downloading behind a fresh task pair, interrupted
    /// entries render Paused (resume restarts), and a recorded file that
    /// vanished renders Failed honestly.
    private func rebuildTransfers(pendingUrls: Set<URL>) {
        var rows: [DownloadTransfer] = []
        for (trackId, entry) in manifest {
            let sourceUrl = URL(string: entry.sourceUrl)
            if let sourceUrl {
                sourceUrlsById[trackId] = sourceUrl
            }
            tracksById[trackId] = PresentationTrack(
                id: trackId,
                title: entry.title,
                artist: entry.artist,
                durationMs: entry.durationMs,
                artworkUrl: entry.artworkUrl
            )
            let quality = entry.fileName.map(Self.qualityLabel(forFileName:)) ?? "Audio"
            let row: DownloadTransfer
            if let fileURL = vaultFileURL(for: entry) {
                row = DownloadTransfer(
                    id: trackId,
                    title: entry.title,
                    artist: entry.artist,
                    state: .completed,
                    quality: quality,
                    sizeMb: Self.fileSizeMb(fileURL),
                    fileURL: fileURL
                )
            } else if entry.fileName != nil {
                row = DownloadTransfer(
                    id: trackId,
                    title: entry.title,
                    artist: entry.artist,
                    state: .failed(message: "File missing from storage"),
                    quality: quality,
                    sizeMb: 0,
                    fileURL: nil
                )
            } else if let sourceUrl, pendingUrls.contains(sourceUrl) {
                row = DownloadTransfer(
                    id: trackId,
                    title: entry.title,
                    artist: entry.artist,
                    state: .downloading(progress: nil),
                    quality: quality,
                    sizeMb: 0,
                    fileURL: nil
                )
            } else {
                row = DownloadTransfer(
                    id: trackId,
                    title: entry.title,
                    artist: entry.artist,
                    state: .paused,
                    quality: quality,
                    sizeMb: 0,
                    fileURL: nil
                )
            }
            rows.append(row)
        }
        transfers = rows.sorted {
            $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
        }
        // Re-armed snapshots with no manifest correlation (a corrupt or
        // absent manifest): honest recovery rows behind fresh task pairs.
        let knownSources = Set(manifest.values.map(\.sourceUrl))
        for url in pendingUrls where !knownSources.contains(url.absoluteString) {
            let id = ensureRow(for: url)
            runTransfer(id: id, sourceUrl: url)
        }
        // Re-armed manifest entries: attach the task pairs (the awaiters
        // bind to the already-running re-armed tasks — no second task is
        // created, exactly the service's documented re-attach flow).
        for (trackId, entry) in manifest where entry.fileName == nil {
            if let sourceUrl = URL(string: entry.sourceUrl), pendingUrls.contains(sourceUrl) {
                runTransfer(id: trackId, sourceUrl: sourceUrl)
            }
        }
    }

    /// Settled rows re-derived from the vault on refresh: a file that
    /// appeared (the background session finished it) flips its row to
    /// Completed with the real size. Live rows are untouched — they are
    /// progress-driven.
    private func reconcileVault() {
        for (id, entry) in manifest {
            guard let index = transfers.firstIndex(where: { $0.id == id }) else { continue }
            switch transfers[index].state {
            case .downloading, .queued:
                continue
            case .paused, .completed, .failed:
                break
            }
            guard let fileURL = vaultFileURL(for: entry) else { continue }
            let row = transfers[index]
            transfers[index] = DownloadTransfer(
                id: row.id,
                title: row.title,
                artist: row.artist,
                state: .completed,
                quality: row.quality,
                sizeMb: Self.fileSizeMb(fileURL),
                fileURL: fileURL
            )
        }
    }

    /// The vault file behind one entry, or nil when it is not (yet) on
    /// disk. A nil `fileName` on a finished download (crash between the
    /// vault move and the manifest write) is recovered through the
    /// service's own naming rule: the remote URL's last path component.
    private func vaultFileURL(for entry: ManifestEntry) -> URL? {
        if let fileName = entry.fileName {
            let url = destinationDirectory.appendingPathComponent(fileName, isDirectory: false)
            return FileManager.default.fileExists(atPath: url.path) ? url : nil
        }
        guard let sourceUrl = URL(string: entry.sourceUrl),
              !sourceUrl.lastPathComponent.isEmpty else { return nil }
        let derived = destinationDirectory
            .appendingPathComponent(sourceUrl.lastPathComponent, isDirectory: false)
        return FileManager.default.fileExists(atPath: derived.path) ? derived : nil
    }

    // MARK: - Persistence (manifest + resume snapshots)

    private func loadManifest() {
        guard let data = try? Data(contentsOf: manifestFileURL) else {
            manifest = [:] // fresh install — honestly empty
            return
        }
        // A corrupt manifest re-initializes empty: tiny and defensive.
        manifest = (try? JSONDecoder().decode([String: ManifestEntry].self, from: data)) ?? [:]
    }

    private func saveManifest() {
        guard let data = try? JSONEncoder().encode(manifest) else { return }
        try? FileManager.default.createDirectory(
            at: destinationDirectory,
            withIntermediateDirectories: true
        )
        try? data.write(to: manifestFileURL, options: .atomic)
    }

    /// The persisted resume snapshots' original source URLs, read from
    /// the service's `.url` sidecars (read-only — `resumePersisted()`
    /// consumes the files itself).
    private func pendingResumeSnapshots() -> [URL] {
        resumeFiles().compactMap { file in
            guard let data = try? Data(contentsOf: URL(fileURLWithPath: file.path + ".url")),
                  let absolute = String(data: data, encoding: .utf8) else { return nil }
            return URL(string: absolute)
        }
    }

    /// Deletes the persisted resume snapshot for one URL: `cancel(_:)`
    /// ALWAYS persists a snapshot when the OS had one, so a REMOVED
    /// download discards it explicitly — otherwise the next cold launch
    /// would resurrect it.
    private func discardPersistedResumeData(for url: URL) {
        for file in resumeFiles() {
            let sidecar = URL(fileURLWithPath: file.path + ".url")
            guard let data = try? Data(contentsOf: sidecar),
                  let absolute = String(data: data, encoding: .utf8),
                  absolute == url.absoluteString else { continue }
            try? FileManager.default.removeItem(at: file)
            try? FileManager.default.removeItem(at: sidecar)
        }
    }

    private func resumeFiles() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: resumeDirectory,
            includingPropertiesForKeys: nil
        )) ?? []
        return files.filter { $0.pathExtension == "bin" }
    }

    // MARK: - Storage accounting

    /// Recomputes the vault's used bytes (recursive, resume snapshots and
    /// manifest included — they occupy the same directory) and the volume
    /// capacity; an unavailable capacity key leaves `totalGb` at 0 and the
    /// view renders no denominator.
    private func refreshStorage() {
        usedGb = Self.directorySizeGb(destinationDirectory)
        if let values = try? destinationDirectory.resourceValues(
            forKeys: [.volumeTotalCapacityKey]
        ), let capacity = values.volumeTotalCapacity, capacity > 0 {
            totalGb = Double(capacity) / 1_000_000_000
        } else {
            totalGb = 0
        }
    }

    // MARK: - Static derivations

    private static func fileSizeMb(_ fileURL: URL) -> Double {
        guard let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path),
              let size = attributes[.size] as? NSNumber else { return 0 }
        return size.doubleValue / 1_000_000
    }

    private static func directorySizeGb(_ directory: URL) -> Double {
        guard let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.fileSizeKey]
        ) else { return 0 }
        var bytes: Int64 = 0
        for case let fileURL as URL in enumerator {
            if let values = try? fileURL.resourceValues(forKeys: [.fileSizeKey]),
               let size = values.fileSize {
                bytes += Int64(size)
            }
        }
        return Double(bytes) / 1_000_000_000
    }

    private static func failureMessage(for error: Error) -> String {
        if let downloadError = error as? DownloadError {
            switch downloadError {
            case .cancelled: return "Cancelled"
            case .transport(let detail): return detail
            case .destination(let detail): return detail
            }
        }
        return error.localizedDescription
    }

    private static func recoveredTitle(for url: URL) -> String {
        url.host ?? url.absoluteString
    }

    /// The in-session quality chip, derived from the resolved format the
    /// download actually runs on (mimeType + bitrate) — never a
    /// fabricated audiophile badge.
    private static func qualityLabel(for format: EngineResolvedStreamFormat) -> String {
        let mime = format.mimeType.lowercased()
        let codec: String
        if mime.contains("opus") {
            codec = "Opus"
        } else if mime.contains("mp4a") || mime.contains("aac") {
            codec = "AAC"
        } else if mime.contains("flac") {
            codec = "FLAC"
        } else if mime.contains("vorbis") {
            codec = "Vorbis"
        } else {
            codec = "Audio"
        }
        guard format.bitrate > 0 else { return codec }
        return "\(codec) \(Int((Double(format.bitrate) / 1_000).rounded()))k"
    }

    /// The relaunch quality chip, derived from the vault file's extension
    /// (the resolved format is not persisted — the manifest stays tiny).
    private static func qualityLabel(forFileName fileName: String) -> String {
        switch URL(fileURLWithPath: fileName).pathExtension.lowercased() {
        case "flac": return "FLAC"
        case "m4a", "mp4", "aac": return "AAC"
        case "opus": return "Opus"
        case "webm": return "WebM"
        case "mp3": return "MP3"
        case "ogg", "oga": return "Vorbis"
        default: return "Audio"
        }
    }
}
