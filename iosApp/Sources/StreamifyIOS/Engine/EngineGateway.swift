import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// Phase 5 (MP-UI01 / Beta): the iOS engine seam — the Swift mirror of the
// shared `EnginePort` contract (shared/src/commonMain/.../engine/EnginePort.kt).
//
// Everything the shell needs from the sovereign Rust core crosses this ONE
// protocol, in the shared presentation models — no Kotlin/Native, no
// Obj-C wrapper, no second bridge (the Apple-platform mandate, MP-DEC-02).
//
// Two conformers exist:
//  * `UniFFIEngineGateway` — the REAL lane over the direct Swift UniFFI
//    bindings from Alpha's StreamifyCore.xcframework, compiled strictly
//    behind `#if canImport(uniffi_streamify)` so this scaffold builds TODAY
//    and lights up automatically the moment the xcframework is linked.
//  * `PreviewEngineGateway` — the always-available stand-in (previews,
//    tests, and the pre-xcframework scaffold period).
// ─────────────────────────────────────────────────────────────────────────────

/// Push, event-driven engine access (MP-DEC-04: the shell collects the
/// snapshot stream and NEVER polls a one-shot getter; `currentSnapshot()`
/// exists solely for cold-start hydration, exactly as the UDL sanctions).
protocol EngineGateway: AnyObject {
    /// Coalesced engine snapshots with strictly monotonic revisions.
    var snapshots: AsyncStream<PlaybackSnapshot> { get }

    /// Dispatches one reverse-plane command; the engine applies its
    /// 50-150 ms hardware debounce to identical duplicates (a `debounced`
    /// result is a success path). Never throws.
    func send(_ command: PlayerCommand) async -> PlayerCommandResult

    /// Cold-start hydration read; nil when the lane cannot answer.
    func currentSnapshot() async -> PlaybackSnapshot?

    /// Creates (or replaces) the engine's local Jam session mirror;
    /// nil on rejection. Never throws.
    func createJamSession(name: String) async -> String?

    /// BLAKE3 state hash of the active Jam session, or nil when absent.
    func jamStateHash() async -> String?

    /// Releases bridge resources (idempotent).
    func shutdown()
}
