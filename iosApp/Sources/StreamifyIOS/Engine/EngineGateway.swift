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

    // ── WS-B B3/B4: the drivetrain v2 surface (UDL CONTRACT_VERSION 2) ──
    // The playback data plane merged in PR #151: stream-format resolution,
    // the full play path (resolve → stage → attach → intent), the sink
    // lifecycle and the DAC-clock feedback lane. Same failure discipline
    // as the typed data lane above: control-plane calls degrade to
    // nil/no-op across the seam — never exceptions into UI code — except
    // `resolveStreamFormats`, the network operation, which throws.

    /// Resolves the playable CDN formats for one video id (the resolver's
    /// cache-backed network lane; audio-only rows first). Throws on
    /// transport/resolver failure.
    func resolveStreamFormats(videoId: String) async throws -> [EngineResolvedStreamFormat]

    /// The full play path: resolve (or reuse the row's resolved URL) →
    /// stage the track in the queue with that URL → attach the sink plane
    /// (idempotent) → emit the play intent. Returns the staged queue
    /// index, or nil when the lane refused the row.
    func play(track: EngineTrack) async -> Int?

    /// Plays a row that is ALREADY staged in the engine's queue (queue-row
    /// taps — no re-resolve, no duplicate row). False when the row is
    /// unknown or carries no resolved URL.
    func playStagedTrack(id: String) async -> Bool

    /// Mounts the engine's SPSC ring + producer (canonical interleaved
    /// stereo f32 @ 48 kHz; the capacity is power-of-two-rounded by the
    /// engine). Replace semantics per the contract — the gateway attaches
    /// once per plane lifetime and re-attaches only after a detach.
    func attachSink(capacityFrames: Int) async

    /// Tears the playback plane down (prompt producer shutdown; the
    /// transport and queue surfaces keep working — control-plane-only).
    func detachSink() async

    /// Live progress of the attached plane; producer fields are zero and
    /// `producerLive` is false while no plane exists.
    func bufferProgress() async -> EngineBufferProgress?

    /// Feeds one hardware DAC clock sample back to the engine (MP-DEC-03:
    /// the hardware render core is the SOLE clock master — this is the
    /// DacClockPublisher drain's destination).
    func recordDacClock(_ sample: DacClockSample) async
}
