import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-01 / SW-ENG4-06 (Engineer 4): JamViewModel — room state, host
// controls, participant ping latencies and sync indicators; the Swift mirror
// of `JamViewModel.kt` (the thin executor over the sovereign jam engine).
//
// PUSH DISCIPLINE: room state arrives exclusively as rev-guarded full-state
// snapshots over the `jamRoomEvents` stream (the CRDT queue, PTP lockstep
// and authority machines stay sovereign in `streamify-jam` — MP-DEC-04
// applied to rooms). Nearby-room discovery flows from the CoreBluetooth
// beacon's @Observable model (push sightings, proximity-prompted joins).
// No polling anywhere.
//
// SYNC INDICATORS (SW-ENG4-06): the PTP badge grade derives from the worst
// participant round-trip — green < 5 ms, yellow < 20 ms, orange < 50 ms,
// red >= 50 ms (SyncGrade).
// ─────────────────────────────────────────────────────────────────────────────

/// Discovery seam so tests drive room sightings without touching radio
/// hardware (the real conformer is CoreBluetoothJamBeacon).
protocol JamRoomDiscovering: AnyObject {
    var model: JamBeaconModel { get }
}

extension CoreBluetoothJamBeacon: JamRoomDiscovering {}

@Observable
@MainActor
final class JamViewModel {

    enum Status: Equatable {
        case idle
        case joining
        case active
        case ended(String)
        case failed(String)
    }

    // ── Room state (push-fed, rev-guarded) ────────────────────────────────

    private(set) var status: Status = .idle
    private(set) var room: JamRoomSnapshot?

    private var lastRev = 0
    nonisolated(unsafe) private var collectTask: Task<Void, Never>?

    // ── Nearby-room discovery (BLE beacon projection) ──────────────────────

    let discovery: any JamRoomDiscovering

    /// Nearby rooms within the one-tap proximity band (the join prompt list).
    var nearbyRooms: [DiscoveredJamRoom] {
        discovery.model.nearestRoom.map { [$0] } ?? []
    }

    var isBluetoothAvailable: Bool {
        discovery.model.availability == .poweredOn
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private let library: any FeatureLibrary
    private let displayName: String

    init(
        library: (any FeatureLibrary)? = nil,
        discovery: any JamRoomDiscovering = CoreBluetoothJamBeacon(),
        displayName: String = "Listener"
    ) {
        self.library = library ?? AppServices.library
        self.discovery = discovery
        self.displayName = displayName
    }

    deinit {
        collectTask?.cancel()
    }

    // MARK: - Push collection

    func start() {
        guard collectTask == nil else { return }
        collectTask = Task { [weak self] in
            guard let stream = self?.library.jamRoomEvents else { return }
            for await event in stream {
                guard let self else { return }
                switch event {
                case .snapshot(let snapshot):
                    self.ingest(snapshot)
                case .closed(let reason):
                    self.status = .ended(reason)
                    self.room = nil
                }
            }
        }
    }

    func stop() {
        collectTask?.cancel()
        collectTask = nil
    }

    private func ingest(_ snapshot: JamRoomSnapshot) {
        guard snapshot.rev > lastRev || lastRev == 0 else { return }
        lastRev = snapshot.rev
        room = snapshot
        status = .active
    }

    // MARK: - Room lifecycle

    /// Hosts a room (the engine mints the PIN; the BLE beacon starts
    /// advertising the room identity).
    func hostRoom(name: String = "My Jam") async {
        status = .joining
        if let snapshot = await library.hostJamRoom(name: name) {
            // The beacon payload derives from the engine-minted room identity.
            startAdvertising(from: snapshot, hostName: name)
        } else {
            status = .failed("Could not open a room — try again")
        }
    }

    /// Joins by room code (typed, scanned QR, or the BLE one-tap prompt).
    func joinRoom(code: String) async {
        let cleaned = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard cleaned.count >= 6 else {
            status = .failed("Check the code — rooms use 6-character codes")
            return
        }
        status = .joining
        if await library.joinJamRoom(code: cleaned) {
            // The engine's next room snapshot flips status to .active.
        } else {
            status = .failed("Could not join — check the code or pairing link")
        }
    }

    /// Joins a discovered nearby room (the BLE one-tap prompt path).
    func joinNearbyRoom(_ room: DiscoveredJamRoom) async {
        await joinRoom(code: room.sessionCode)
    }

    func leaveRoom(endForEveryone: Bool = false) async {
        await library.leaveJamRoom(endForEveryone: endForEveryone)
        stopRoomAdvertising()
        room = nil
        status = .idle
    }

    private func startAdvertising(from snapshot: JamRoomSnapshot, hostName: String) {
        guard let beacon = discovery as? CoreBluetoothJamBeacon else { return }
        beacon.startAdvertising(
            sessionCode: snapshot.sessionCode,
            hostName: hostName,
            partyMode: snapshot.topology.isPartyMode,
            hasSpace: snapshot.participants.count < 32,
            queueNonEmpty: !snapshot.queue.isEmpty
        )
    }

    private func stopRoomAdvertising() {
        guard let beacon = discovery as? CoreBluetoothJamBeacon else { return }
        beacon.stopAdvertising()
    }

    // MARK: - Discovery control (the Jam tab arms scanning on appear)

    func startDiscovery() {
        guard let beacon = discovery as? CoreBluetoothJamBeacon else { return }
        beacon.startScanning()
    }

    func stopDiscovery() {
        guard let beacon = discovery as? CoreBluetoothJamBeacon else { return }
        beacon.stopScanning()
    }

    // MARK: - Host controls & governance

    func toggleTopology() {
        guard let room else { return }
        let next: JamTopology = room.topology.isPartyMode ? .multiRender : .singleRender
        Task { await library.setJamTopology(next) }
    }

    func cycleControlPolicy() {
        Task { await library.cycleJamControlPolicy() }
    }

    func setMemberAcl(nonce: String, allowControl: Bool? = nil, allowVolume: Bool? = nil) {
        Task { await library.setMemberAcl(nonce: nonce, allowControl: allowControl, allowVolume: allowVolume) }
    }

    func setMemberRole(nonce: String, role: JamRole) {
        Task { await library.setMemberRole(nonce: nonce, role: role) }
    }

    func kickMember(nonce: String) {
        Task { await library.kickMember(nonce: nonce) }
    }

    // MARK: - Shared queue (CRDT ops dispatch; pushes repaint)

    func addToQueue(track: PresentationTrack) {
        Task { await library.addToJamQueue(track: track) }
    }

    func removeFromQueue(trackId: String) {
        Task { await library.removeFromJamQueue(trackId: trackId) }
    }

    func moveInQueue(trackId: String, toPosition: Int) {
        Task { await library.moveInJamQueue(trackId: trackId, toPosition: toPosition) }
    }

    func castVote(trackId: String) {
        Task { await library.castJamVote(trackId: trackId) }
    }

    // MARK: - Projections

    var participants: [JamParticipant] { room?.participants ?? [] }

    var sessionCode: String { room?.sessionCode ?? "" }

    var isHost: Bool { room?.isHost ?? false }

    /// The PTP sync badge latency: the worst measured participant round-trip
    /// (0 when alone — the host's own clock is the reference).
    var syncLatencyMs: Double {
        let measured = participants.map { max($0.rttMs, 0) }
        return measured.max() ?? 0
    }

    var syncGrade: SyncGrade {
        SyncGrade.grade(forLatencyMs: syncLatencyMs)
    }

    var queue: [JamQueueEntry] { room?.queue ?? [] }

    /// The offline pairing URI for QR sharing (the pairing key stays
    /// engine-owned; pre-UDL rooms share the PIN deep-link form).
    var pairingUri: String {
        guard let room else { return "" }
        return JamPairing.encodePayload(
            JamPairing.RoomIdentity(
                sessionCode: room.sessionCode,
                sessionId: room.sessionId,
                pairingKeyHex: String(repeating: "0", count: 64)
            )
        )
    }

    var inviteShareText: String {
        "🎵 Join my Streamify Jam!\nCode: \(sessionCode)\nOr tap: \(pairingUri)"
    }
}
