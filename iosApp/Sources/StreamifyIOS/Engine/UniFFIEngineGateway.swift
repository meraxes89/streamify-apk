import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// The REAL engine lane: direct Swift UniFFI bindings from Alpha's
// StreamifyCore.xcframework (`uniffi_streamify` module). Compiled strictly
// behind `#if canImport(uniffi_streamify)` — until the xcframework is
// linked this whole type is absent, the shell falls back to the preview
// gateway, and the scaffold compiles standalone. The mapping below follows
// the frozen UDL contract (rust/src/streamify.udl); the moment Alpha's
// packaging automation ships the framework, this lane activates with zero
// edits anywhere else (same philosophy as the desktop CI's Windows
// probe-gate).
//
// ZERO Kotlin/Native double-bridge (MP-DEC-02): SwiftUI → uniffi_streamify
// Swift proxies → the staticlib's uniffi_* C ABI. Nothing in between.
// ─────────────────────────────────────────────────────────────────────────────

#if canImport(uniffi_streamify)
import uniffi_streamify

final class UniFFIEngineGateway: EngineGateway {

    private let engine: StreamifyEngine
    private var listenerBridge: ListenerBridge?

    /// Constructs and arms the engine (Idle → Buffering). Throws on
    /// initialization rejection — the factory (`EngineGatewayLoader`)
    /// degrades that to the preview lane instead of crashing.
    init() throws {
        engine = StreamifyEngine()
        try engine.initialize()
    }

    var snapshots: AsyncStream<PlaybackSnapshot> {
        AsyncStream { continuation in
            let bridge = ListenerBridge { snapshot in
                continuation.yield(Self.map(snapshot))
            }
            listenerBridge = bridge
            _ = try? engine.addEventListener(listener: bridge)
            continuation.onTermination = { [weak bridge] _ in
                // Keep the bridge alive for the stream's lifetime; the
                // engine object's own lifetime follows the gateway.
                _ = bridge
            }
        }
    }

    func send(_ command: PlayerCommand) async -> PlayerCommandResult {
        do {
            switch command {
            case .setVolume(let volume):
                try engine.setVolume(volume: Float(min(max(volume, 0), 1)))
                return .applied
            default:
                let applied = try engine.onMediaCommand(command: Self.map(command))
                return applied ? .applied : .debounced
            }
        } catch {
            // Failures across the seam (including marshaled Rust panics,
            // MP-C06) degrade to a failed dispatch — never an exception
            // into UI code.
            return .failed
        }
    }

    func currentSnapshot() async -> PlaybackSnapshot? {
        guard let snapshot = try? engine.getEngineSnapshot() else { return nil }
        return Self.map(snapshot)
    }

    func createJamSession(name: String) async -> String? {
        guard let handle = try? engine.createJamSession(sessionName: name) else { return nil }
        return handle.sessionName
    }

    func jamStateHash() async -> String? {
        try? engine.getJamStateHash()
    }

    func shutdown() {
        listenerBridge = nil
    }

    // ── UDL → shared presentation mapping (total by construction) ──────────

    private final class ListenerBridge: EngineEventListener {
        private let sink: (EngineSnapshot) -> Void

        init(sink: @escaping (EngineSnapshot) -> Void) {
            self.sink = sink
        }

        func onEngineEvent(snapshot: EngineSnapshot) {
            // Engine dispatch thread: cheap total mapping only.
            sink(snapshot)
        }
    }

    private static func map(_ snapshot: EngineSnapshot) -> PlaybackSnapshot {
        let clock = snapshot.clock.map { clock in
            DacClockSample(
                framePosition: Int(clock.dacFramePosition),
                sampleRateHz: Int(clock.sampleRate),
                monotonicNs: Int(clock.monotonicNs)
            )
        }
        return PlaybackSnapshot(
            rev: Int(snapshot.rev),
            status: map(snapshot.state),
            volume: Double(snapshot.volume),
            buffer: BufferHealth(
                bufferedDurationMs: Int(snapshot.bufferStats.bufferedDurationMs),
                underrunCount: Int(snapshot.bufferStats.underrunCount),
                fillRatio: Double(snapshot.bufferStats.fillRatio)
            ),
            clock: clock,
            jamSessionName: nil
        )
    }

    private static func map(_ state: PlaybackState) -> PlaybackStatus {
        switch state {
        case .idle: return .idle
        case .buffering: return .buffering
        case .playing: return .playing
        case .paused: return .paused
        case .error: return .error
        @unknown default: return .idle
        }
    }

    private static func map(_ command: PlayerCommand) -> MediaCommand {
        switch command {
        case .play: return .play
        case .pause: return .pause
        case .togglePlayPause: return .togglePlayPause
        case .next: return .next
        case .previous: return .previous
        case .seek(let positionMs): return .seek(positionMs: UInt64(positionMs))
        case .setVolume(let volume): return .setVolume(volume: Float(volume))
        case .stop: return .stop
        }
    }
}
#endif
