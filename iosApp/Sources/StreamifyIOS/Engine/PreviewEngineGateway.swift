import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// The always-available engine lane: a deterministic preview stand-in for
// SwiftUI previews, XCTest, and the pre-xcframework scaffold period.
// Emits scripted snapshots on a timer-free cadence (a background Task with
// a one-second tick — visible state movement for previews; tests drive
// pushes by hand via `push`).
// ─────────────────────────────────────────────────────────────────────────────

final class PreviewEngineGateway: EngineGateway {

    private let lock = NSLock()
    private var state = PlaybackSnapshot.idle
    private var continuation: AsyncStream<PlaybackSnapshot>.Continuation?
    private var demoTask: Task<Void, Never>?

    private var _dispatchedCommands: [PlayerCommand] = []
    var dispatchedCommands: [PlayerCommand] {
        lock.withLock { _dispatchedCommands }
    }
    var nextResult: PlayerCommandResult = .applied
    var failAll = false

    var snapshots: AsyncStream<PlaybackSnapshot> {
        AsyncStream { continuation in
            self.continuation = continuation
            continuation.yield(self.state)
        }
    }

    private func recordCommand(_ command: PlayerCommand) {
        lock.withLock {
            _dispatchedCommands.append(command)
        }
    }

    func send(_ command: PlayerCommand) async -> PlayerCommandResult {
        recordCommand(command)
        return failAll ? .failed : nextResult
    }

    func currentSnapshot() async -> PlaybackSnapshot? {
        failAll ? nil : state
    }

    func createJamSession(name: String) async -> String? {
        failAll ? nil : name
    }

    func jamStateHash() async -> String? {
        failAll
            ? nil
            : "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }

    func shutdown() {
        demoTask?.cancel()
        continuation?.finish()
        continuation = nil
    }

    /// Test/preview helper: hand-push a snapshot.
    func push(_ snapshot: PlaybackSnapshot) {
        state = snapshot
        continuation?.yield(snapshot)
    }

    /// Preview helper: starts a scripted 1 Hz demo (status/volume/position
    /// movement — pure UI exercise, clearly not engine truth).
    func startDemo() {
        guard demoTask == nil else { return }
        demoTask = Task { [weak self] in
            // Local to the task's closure: a captured-and-mutated outer var
            // is a data race in concurrently-executing code (compile error
            // under strict concurrency; a local is exactly as deterministic).
            var rev = 1
            while !Task.isCancelled {
                guard let self else { return }
                let playing = rev % 8 != 0
                let snapshot = PlaybackSnapshot(
                    rev: rev,
                    status: playing ? .playing : .buffering,
                    volume: 0.7,
                    buffer: BufferHealth(
                        bufferedDurationMs: 12_000,
                        underrunCount: rev / 20,
                        fillRatio: 0.9
                    ),
                    clock: DacClockSample(
                        framePosition: 48_000 * rev,
                        sampleRateHz: 48_000,
                        monotonicNs: rev * 1_000_000_000
                    )
                )
                self.push(snapshot)
                rev += 1
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }
}
