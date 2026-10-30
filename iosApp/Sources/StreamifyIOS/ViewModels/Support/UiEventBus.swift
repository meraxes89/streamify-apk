import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 (Engineer 4): the shell-wide UI event bus — the Swift mirror of
// the Kotlin `UiEventBus` (replay 0, extra buffer 8, drop-oldest on burst).
//
// Built strictly on AsyncStream (SW-DEC-03: no Combine, no SharedFlow, no
// third-party reactive machinery). Emitters are non-blocking (fire-and-
// forget `emit`); the shell collects the stream from SwiftUI `.task` blocks.
// ─────────────────────────────────────────────────────────────────────────────

/// A one-shot, user-facing UI event.
enum UiEvent: Sendable, Equatable {
    case snackbar(String)
}

@MainActor
final class UiEventBus {

    private var continuations: [UUID: AsyncStream<UiEvent>.Continuation] = [:]
    private var pending: [UiEvent] = []

    /// A fresh push stream of UI events (replay 0, buffer 8, drop-oldest —
    /// exactly the Kotlin bus's overflow policy).
    var events: AsyncStream<UiEvent> {
        AsyncStream(bufferingPolicy: .bufferingNewest(8)) { continuation in
            let id = UUID()
            self.continuations[id] = continuation
            // Backlog from a burst emitted before any collector attached.
            for event in self.pending {
                continuation.yield(event)
            }
            self.pending.removeAll()
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.continuations[id] = nil
                }
            }
        }
    }

    /// Non-blocking emit (the Kotlin `tryEmit`): burst keeps the newest 8.
    func emit(_ event: UiEvent) {
        guard !continuations.isEmpty else {
            pending.append(event)
            if pending.count > 8 { pending.removeFirst(pending.count - 8) }
            return
        }
        for continuation in continuations.values {
            continuation.yield(event)
        }
    }

    func emitSnackbar(_ message: String) {
        emit(.snackbar(message))
    }
}
