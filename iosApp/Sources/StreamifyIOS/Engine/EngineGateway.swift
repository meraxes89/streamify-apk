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
//    behind `#if canImport(streamifyFFI)` so this scaffold builds TODAY
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

    // ── SW-ENG1-04: the typed data lane (SW-ENG1-03 contract surface) ──
    // Lane-neutral currency (EngineContract.swift); the real gateway maps
    // the UniFFI records at the seam, the preview gateway synthesizes
    // fixtures. Failures degrade to empty pages / nil — never exceptions
    // into UI code — except `searchTracks`, the one genuinely failable
    // network operation, which throws.

    /// Typed search through the resolver (RustyPipe YTM lane). Throws on
    /// transport/resolver failure.
    func searchTracks(_ query: String, limit: Int) async throws -> EngineSearchResult

    /// Appends one track to the engine's staged queue; returns the row's
    /// index, or nil when the lane refused the row.
    func enqueue(track: EngineTrack, enqueuedBy: String) async -> Int?

    /// The whole staged queue as one consistent page.
    func queue() async -> [EngineQueueItem]

    /// Drops every staged queue row (idempotent).
    func clearQueue() async

    /// The ten-band equalizer curve (flat preset by default).
    func equalizerBands() async -> [EngineEqualizerBand]

    /// Overwrites one equalizer band; invalid bands are swallowed by the
    /// boundary (the engine rejects them as Internal — a read reflects
    /// the unchanged curve).
    func setEqualizerBand(_ band: EngineEqualizerBand) async

    /// Presentation state of the active Jam room, or nil when no session
    /// is live.
    func jamRoomState() async -> EngineJamRoomState?

    // ── WS-B (B3/B4, issue #151's additive UDL v2): the playback data
    // plane + the resolve lane. The real gateway maps the generated
    // surface; the preview gateway keeps the protocol defaults below
    // (no-ops / empty), exactly like the typed data lane above.
    //
    // The transport's `play_track` (A4) is deliberately NOT just another
    // MediaCommand: it stages, resolves and starts in one engine-side
    // transaction, so the shell's play flow (B3) lowers onto it after
    // `enqueue` — see `UniFFIFeatureLibrary.play(track:)`.

    /// Builds the canonical-geometry SPSC ring + spawns the producer
    /// thread (replace semantics when a plane is already live).
    func attachSink(capacityFrames: Int) async

    /// Tears the playback data plane down (idempotent).
    func detachSink() async

    /// Plays one staged track NOW: selects its queue row, resets the
    /// position target, drives the lifecycle to Playing and emits the
    /// play intent into the producer (the skip path). The row must be
    /// staged and carry a resolved stream URL. Returns false when the
    /// lane refused the intent — never throws.
    @discardableResult
    func playTrack(trackId: String) async -> Bool

    /// The data plane's progress read; nil when the lane cannot answer
    /// (no plane attached on the preview lane).
    func bufferProgress() async -> EngineBufferProgress?

    /// Mirrors one hardware DAC presentation clock sample into the engine
    /// (the UDL `record_dac_clock` seam — republished inside every pushed
    /// snapshot). Called from a non-audio thread per contract.
    func recordDacClock(_ clock: DacClockSample)

    /// Resolves the playable stream-format set for one video id through
    /// the resolver (persistent cache -> RustyPipe -> legacy Innertube).
    /// Audio-only rows form the head of the returned list. Empty when the
    /// lane cannot resolve (never throws — the caller degrades honestly).
    func resolveStreamFormats(videoId: String) async -> [EngineStreamFormat]
}

// MARK: - Data-plane defaults (conformers override only what they script)

extension EngineGateway {

    func attachSink(capacityFrames: Int) async {}

    func detachSink() async {}

    func playTrack(trackId: String) async -> Bool { false }

    func bufferProgress() async -> EngineBufferProgress? { nil }

    func recordDacClock(_ clock: DacClockSample) {}

    func resolveStreamFormats(videoId: String) async -> [EngineStreamFormat] { [] }
}
