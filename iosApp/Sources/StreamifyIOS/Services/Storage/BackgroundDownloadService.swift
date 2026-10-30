import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG1-08 (Swift migration, Engineer 1): BackgroundDownloadService —
// URLSessionDownloadDelegate over URLSessionConfiguration.background for
// resumable, OS-scheduled track downloads (the Swift successor of
// StreamifyDownloadManager.kt + ResumableDownloadEngine.kt).
//
// ARCHITECTURE (structured concurrency, SW-DEC-03):
//   * One BACKGROUND session per identifier, owned for the process
//     lifetime by this service (the background-session contract: the
//     delegate must stay alive or in-flight transfers error out — the
//     session retains its delegate, so a service instance is
//     intentionally process-scoped; use `shared(identifier:)`).
//   * Delegate callbacks arrive on the session's serial delegate queue;
//     state is guarded by one lock, and completions hop to callers
//     through CheckedContinuations — `download(from:)` is a plain async
//     function from the caller's point of view.
//   * RESUMABILITY, two layers:
//       1. In-flight cancellation persists the OS resumeData snapshot
//          (URLError.downloadTaskResumeData / cancel(byProducingResumeData:))
//          under Application Support;
//       2. A cold launch re-arms those snapshots as fresh download tasks
//          (`resumePersisted()`), continuing from the transferred ranges.
//   * PROGRESS is a per-download AsyncStream (fraction + byte counters);
//     single consumer by design (the download's progress surface).
//
// AWAIT CONTRACT: one `download(from:)` await per URL at a time — a
// second concurrent await for the same URL fails fast with a transport
// error instead of silently dropping a continuation.
//
// MULTIPLATFORM (SW-DEC-04): background URLSessions behave identically on
// iOS and macOS; no platform forks in this file.
// ─────────────────────────────────────────────────────────────────────────────

/// One live progress sample of a background download.
struct DownloadProgress: Equatable, Sendable {
    let bytesWritten: Int64
    /// Total expected bytes; 0 when the server did not advertise a length
    /// (render an indeterminate indicator, never a fabricated fraction).
    let totalBytes: Int64

    /// Completed fraction in [0, 1]; nil while the total is unknown.
    var fraction: Double? {
        guard totalBytes > 0 else { return nil }
        return min(1, max(0, Double(bytesWritten) / Double(totalBytes)))
    }
}

/// Failure taxonomy for one download.
enum DownloadError: Error, Equatable, Sendable {
    /// The task was cancelled (resume data persisted when the OS had any).
    case cancelled
    /// Transport-level failure (URLError and friends).
    case transport(String)
    /// The finished file could not be moved into the destination vault.
    case destination(String)
}

/// Resumable, background track downloads — SW-ENG1-08.
final class BackgroundDownloadService: NSObject, @unchecked Sendable {

    /// Per-download in-flight bookkeeping (touched only under `lock`).
    private final class Entry {
        let url: URL
        var continuation: CheckedContinuation<URL, Error>?
        var progressStream: AsyncStream<DownloadProgress>?
        var progressContinuation: AsyncStream<DownloadProgress>.Continuation?

        init(url: URL) {
            self.url = url
        }
    }

    /// Default vault: Application Support/StreamifyDownloads (created on
    /// demand; the same directory on iOS and macOS).
    static func defaultDestinationDirectory() -> URL {
        let base = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("StreamifyDownloads", isDirectory: true)
    }

    private let lock = NSLock()
    private let destinationDirectory: URL
    private let resumeDirectory: URL
    private var entriesByTask: [Int: Entry] = [:]
    private var taskByURL: [URL: Int] = [:]

    private let identifier: String

    /// The one background session (creating a second URLSession with the
    /// same identifier would throw; the lazy var is materialized exactly
    /// once, in `init`).
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(
            withIdentifier: identifier
        )
        configuration.isDiscretionary = false
        configuration.waitsForConnectivity = true
        configuration.sessionSendsLaunchEvents = true
        return URLSession(
            configuration: configuration,
            delegate: self,
            delegateQueue: nil
        )
    }()

    /// Background-session services are singletons per identifier (the OS
    /// keys the transfer store by it); this registry hands the SAME
    /// instance back for repeated lookups.
    private static let registryLock = NSLock()
    private static var registry: [String: BackgroundDownloadService] = [:]

    /// The shared service for the app's offline-audio identifier.
    static func shared(
        identifier: String = "com.streamify.offline.audio",
        destinationDirectory: URL = BackgroundDownloadService.defaultDestinationDirectory()
    ) -> BackgroundDownloadService {
        registryLock.lock()
        defer { registryLock.unlock() }
        if let existing = registry[identifier] {
            return existing
        }
        let service = BackgroundDownloadService(
            identifier: identifier,
            destinationDirectory: destinationDirectory
        )
        registry[identifier] = service
        return service
    }

    init(
        identifier: String = "com.streamify.offline.audio",
        destinationDirectory: URL = BackgroundDownloadService.defaultDestinationDirectory()
    ) {
        self.identifier = identifier
        self.destinationDirectory = destinationDirectory
        self.resumeDirectory = destinationDirectory
            .appendingPathComponent(".resume", isDirectory: true)
        super.init()
        try? FileManager.default.createDirectory(
            at: destinationDirectory,
            withIntermediateDirectories: true
        )
        try? FileManager.default.createDirectory(
            at: resumeDirectory,
            withIntermediateDirectories: true
        )
        // Materialize the session eagerly so a cold relaunch re-binds the
        // OS's restored tasks to this delegate immediately.
        _ = session
    }

    // MARK: - Public surface

    /// Starts (or re-attaches to) one background download and awaits its
    /// completion, returning the moved file's URL in the destination
    /// vault. Cancellation via `cancel(_:)` resumes the caller with
    /// `DownloadError.cancelled` after persisting resume data.
    func download(from url: URL) async throws -> URL {
        // Find or create the entry for this URL under one lock.
        // (withLock everywhere: NSLock.lock()/unlock() are flagged as
        // unavailable from asynchronous contexts under Swift 6.)
        let entry: Entry = lock.withLock {
            if let taskID = taskByURL[url], let live = entriesByTask[taskID] {
                return live
            }
            let fresh = Entry(url: url)
            var progressContinuation: AsyncStream<DownloadProgress>.Continuation!
            fresh.progressStream = AsyncStream(bufferingPolicy: .bufferingNewest(8)) {
                progressContinuation = $0
            }
            fresh.progressContinuation = progressContinuation
            return fresh
        }

        // Register a download task when the entry has none — a live entry
        // reuses its in-flight task; a resumePersisted() entry already
        // carries one (both identified by membership in entriesByTask).
        let registeredTask: URLSessionDownloadTask?
        if lock.withLock({ entriesByTask.values.contains { $0 === entry } }) {
            registeredTask = nil
        } else {
            let task = session.downloadTask(with: url)
            lock.withLock {
                entriesByTask[task.taskIdentifier] = entry
                taskByURL[url] = task.taskIdentifier
            }
            registeredTask = task
        }

        return try await withCheckedThrowingContinuation { continuation in
            let alreadyAwaited = lock.withLock {
                if entry.continuation != nil {
                    return true
                }
                entry.continuation = continuation
                return false
            }
            if alreadyAwaited {
                continuation.resume(
                    throwing: DownloadError.transport("download already awaited")
                )
            } else if let task = registeredTask {
                task.resume()
            }
        }
    }

    /// The live progress stream for one URL (single consumer; nil when
    /// that URL is not downloading right now).
    func progress(for url: URL) -> AsyncStream<DownloadProgress>? {
        lock.lock()
        defer { lock.unlock() }
        guard let taskID = taskByURL[url], let entry = entriesByTask[taskID] else {
            return nil
        }
        return entry.progressStream
    }

    /// The URLs currently downloading.
    func activeDownloads() -> [URL] {
        lock.lock()
        defer { lock.unlock() }
        return Array(taskByURL.keys)
    }

    /// Cancels one download, persisting the OS resume snapshot so a later
    /// `resumePersisted()` (or the next cold launch) continues from the
    /// transferred byte ranges. Idempotent for a non-live URL.
    func cancel(_ url: URL) {
        lock.lock()
        let taskID = taskByURL[url]
        lock.unlock()
        guard let taskID else { return }

        session.getTasksWithCompletionHandler { _, _, downloadTasks in
            guard let task = downloadTasks.first(where: { $0.taskIdentifier == taskID }) else {
                return
            }
            task.cancel { resumeData in
                if let resumeData {
                    self.persistResumeData(resumeData, for: url)
                }
            }
        }
    }

    /// Re-arms every persisted resume snapshot as a fresh background
    /// download (cold-launch recovery; safe to call any time). A re-armed
    /// download completes into the destination vault exactly like a fresh
    /// one; call `download(from:)` afterwards to await its completion.
    func resumePersisted() {
        for resumeFile in persistedResumeURLs() {
            guard let data = try? Data(contentsOf: resumeFile),
                  let originalURL = originalURL(forResumeFile: resumeFile) else {
                continue
            }
            try? FileManager.default.removeItem(at: resumeFile)

            let entry = Entry(url: originalURL)
            var progressContinuation: AsyncStream<DownloadProgress>.Continuation!
            let progressStream = AsyncStream(bufferingPolicy: .bufferingNewest(8)) {
                progressContinuation = $0
            }
            entry.progressStream = progressStream
            entry.progressContinuation = progressContinuation

            let task = session.downloadTask(withResumeData: data)
            lock.lock()
            entriesByTask[task.taskIdentifier] = entry
            taskByURL[originalURL] = task.taskIdentifier
            lock.unlock()
            task.resume()
        }
    }

    // MARK: - Internals

    /// FNV-1a 64-bit: a stable, dependency-free filename hash.
    private static func stableHash(_ string: String) -> String {
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in string.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100000001b3
        }
        return String(format: "%016llx", hash)
    }

    private func persistResumeData(_ data: Data, for url: URL) {
        let resumeURL = resumeDirectory.appendingPathComponent(
            "resume-\(Self.stableHash(url.absoluteString)).bin"
        )
        let metadataURL = URL(fileURLWithPath: resumeURL.path + ".url")
        try? data.write(to: resumeURL, options: .atomic)
        try? Data(url.absoluteString.utf8).write(to: metadataURL, options: .atomic)
    }

    private func persistedResumeURLs() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: resumeDirectory,
            includingPropertiesForKeys: nil
        )) ?? []
        return files.filter { $0.pathExtension == "bin" }
    }

    private func originalURL(forResumeFile file: URL) -> URL? {
        let metadataURL = URL(fileURLWithPath: file.path + ".url")
        guard let data = try? Data(contentsOf: metadataURL),
              let absolute = String(data: data, encoding: .utf8),
              let url = URL(string: absolute) else {
            return nil
        }
        try? FileManager.default.removeItem(at: metadataURL)
        return url
    }

    /// Moves a finished temp file into the vault (last-wins: a completed
    /// re-download replaces the older file).
    private func moveIntoVault(_ location: URL, for remote: URL) throws -> URL {
        let fileName = remote.lastPathComponent.isEmpty
            ? "download-\(Self.stableHash(remote.absoluteString)).bin"
            : remote.lastPathComponent
        let destination = destinationDirectory.appendingPathComponent(fileName)
        try? FileManager.default.removeItem(at: destination)
        do {
            try FileManager.default.moveItem(at: location, to: destination)
            return destination
        } catch {
            throw DownloadError.destination(error.localizedDescription)
        }
    }

    private func finishEntry(
        taskID: Int,
        remote: URL,
        result: Result<URL, Error>
    ) {
        lock.lock()
        let entry = entriesByTask[taskID]
        entriesByTask[taskID] = nil
        if let id = taskByURL[remote], id == taskID {
            taskByURL[remote] = nil
        }
        let continuation = entry?.continuation
        let progressContinuation = entry?.progressContinuation
        entry?.continuation = nil
        lock.unlock()

        continuation?.resume(with: result)
        progressContinuation?.finish()
    }
}

// MARK: - URLSessionDownloadDelegate

extension BackgroundDownloadService: URLSessionDownloadDelegate {

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        lock.lock()
        let progressContinuation = entriesByTask[downloadTask.taskIdentifier]?.progressContinuation
        lock.unlock()
        progressContinuation?.yield(
            DownloadProgress(
                bytesWritten: totalBytesWritten,
                totalBytes: totalBytesExpectedToWrite
            )
        )
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        let remote = downloadTask.originalRequest?.url
            ?? downloadTask.currentRequest?.url
            ?? URL(string: "unknown")!
        do {
            let vaultURL = try moveIntoVault(location, for: remote)
            finishEntry(
                taskID: downloadTask.taskIdentifier,
                remote: remote,
                result: .success(vaultURL)
            )
        } catch {
            finishEntry(
                taskID: downloadTask.taskIdentifier,
                remote: remote,
                result: .failure(error)
            )
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        guard let error else { return } // success path handled above

        let remote = task.originalRequest?.url
            ?? task.currentRequest?.url
            ?? URL(string: "unknown")!

        // Persist whatever resume snapshot the OS captured inside the
        // error (a user cancel or a dropped route both land here).
        if let urlError = error as? URLError,
           let resumeData = urlError.downloadTaskResumeData {
            persistResumeData(resumeData, for: remote)
        }

        let domainError: DownloadError
        if (error as NSError).code == NSURLErrorCancelled {
            domainError = .cancelled
        } else {
            domainError = .transport(error.localizedDescription)
        }
        finishEntry(taskID: task.taskIdentifier, remote: remote, result: .failure(domainError))
    }
}
