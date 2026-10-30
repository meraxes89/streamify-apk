import CoreBluetooth
import Foundation
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-04 (Engineer 4): CoreBluetooth proximity jam discovery — the Apple
// counterpart of the Android `JoinFabric` BLE rails + `BleJoinPayload` codec.
//
// HOST (peripheral mode): while hosting a Jam, advertise the custom
// Streamify 128-bit service UUID with the room identity encoded in the
// manufacturer-specific payload (BleJoinPayload: magic 'SF', version 1,
// flags, 6-char room PIN, host name — the little-endian 0x0F1E company id).
//
// GUEST (central mode): on the Jam tab (and in the background when the app
// declares the bluetooth-central background mode) scan for the service UUID;
// a beacon within the proximity band (RSSI >= -60 dBm ≈ 1 m) raises the
// one-tap "Join [Host]'s Jam" prompt.
//
// ARCHITECTURE (SW-DEC-02/03): CoreBluetooth delivers delegate callbacks on
// its own dispatch queue, so this file splits into a NON-observable
// transport (`CoreBluetoothJamBeacon`, the CBCentralManager/CBPeripheralManager
// delegate owner) and an `@Observable @MainActor` projection
// (`JamBeaconModel`). All delegate hops land on the main actor through a
// single `runOnMain` trampoline — no raw GCD queues in view-observable
// state, no Combine, zero polling (discovery is 100% push).
//
// The room STATE MACHINE (CRDT queue, PTP lockstep, authority) stays
// sovereign in the Rust jam core; this beacon is pure proximity transport.
// ─────────────────────────────────────────────────────────────────────────────

/// The Streamify Jam BLE service UUID (advertised + used as the scan filter;
/// background scanning on iOS requires a service-UUID-filtered scan).
/// "53544E46" = 'S''T''N''F', "0F1E" = the manufacturer id, "4A414D" = 'J''A''M'.
let jamBeaconServiceUUID = CBUUID(string: "53544E46-0F1E-4A41-4D00-00000000000A")

/// One nearby jam room surfaced by the scanner.
struct DiscoveredJamRoom: Equatable, Sendable, Identifiable {
    /// Room identity key: session code + host name (the BLE dedup key).
    let sessionCode: String
    let hostName: String
    var partyMode: Bool
    var hasSpace: Bool
    var queueNonEmpty: Bool
    /// Last observed signal strength in dBm (closer to 0 = nearer).
    var rssi: Int
    /// Host monotonic uptime (ns) of the last sighting (expiry window).
    var lastSeenUptimeNs: Int

    var id: String { "\(sessionCode):\(hostName)" }

    /// One-tap prompt copy (Kotlin parity: "Join X's Jam").
    var promptTitle: String {
        let host = hostName.isEmpty ? "a friend" : hostName
        return "Join \(host)'s Jam"
    }

    var isWithinProximity: Bool { rssi >= BleJoinPayload.proximityRssiDbm }
}

/// The `@Observable` projection the Jam views read (main-actor only).
@Observable
@MainActor
final class JamBeaconModel {

    // ── Transport availability (CBCentralManager state) ────────────────────

    enum Availability: Equatable {
        case unknown
        case resetting
        case unsupported
        case unauthorized
        case poweredOff
        case poweredOn
    }

    private(set) var availability: Availability = .unknown
    private(set) var peripheralAvailability: Availability = .unknown

    // ── Host (advertising) state ────────────────────────────────────────────

    private(set) var isAdvertising = false

    // ── Guest (scanning) state ─────────────────────────────────────────────

    private(set) var isScanning = false

    /// Nearby rooms, keyed by session code + host name, strongest-first.
    private(set) var discoveredRooms: [DiscoveredJamRoom] = []

    /// Sightings older than this window expire from the list.
    static let roomExpiryNs: Int = 10_000_000_000

    /// The nearest in-proximity room (the one-tap join prompt), if any.
    var nearestRoom: DiscoveredJamRoom? {
        discoveredRooms
            .filter(\.isWithinProximity)
            .max { $0.rssi < $1.rssi }
    }

    // ── Mutation surface (transport hops in on the main actor) ─────────────

    func applyAvailability(_ state: Availability) {
        availability = state
        if state != .poweredOn {
            isScanning = false
        }
    }

    func applyPeripheralAvailability(_ state: Availability) {
        peripheralAvailability = state
        if state != .poweredOn {
            isAdvertising = false
        }
    }

    func applyScanning(_ active: Bool) {
        isScanning = active
        if !active { clearRooms() }
    }

    func applyAdvertising(_ advertising: Bool) {
        isAdvertising = advertising
    }

    func applySighting(_ room: DiscoveredJamRoom) {
        if let index = discoveredRooms.firstIndex(where: { $0.id == room.id }) {
            discoveredRooms[index] = room
        } else {
            discoveredRooms.append(room)
        }
        discoveredRooms.sort { $0.rssi > $1.rssi }
        prune(nowUptimeNs: room.lastSeenUptimeNs)
    }

    func prune(nowUptimeNs: Int) {
        discoveredRooms.removeAll {
            nowUptimeNs - $0.lastSeenUptimeNs > Self.roomExpiryNs
        }
    }

    func clearRooms() {
        discoveredRooms.removeAll()
    }
}

// MARK: - The transport (owns both CoreBluetooth managers)

/// Non-observable CoreBluetooth transport. Construct it once per app
/// (the Jam tab owns it); all state flows into the `@Observable` model.
final class CoreBluetoothJamBeacon: NSObject, @unchecked Sendable {

    let model = JamBeaconModel()

    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?

    /// The advertisement currently on the air (nil = not advertising).
    private var advertisingRoom: BleJoinPayload.Decoded?
    private var advertisingHasSpace = true
    private var advertisingQueueNonEmpty = false

    // MARK: - Guest mode: scanning for nearby rooms

    /// Arms the central manager (idempotent). Callbacks hop to the model.
    func startScanning() {
        scanDesired = true
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: self, queue: nil)
        } else {
            beginScanIfDesired()
        }
    }

    private var scanDesired = false

    private func beginScanIfDesired() {
        guard scanDesired, let centralManager, centralManager.state == .poweredOn else { return }
        // Duplicates ARE wanted: RSSI freshness drives the proximity prompt.
        centralManager.scanForPeripherals(
            withServices: [jamBeaconServiceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        runOnMain { [weak self] in self?.model.applyScanning(true) }
    }

    func stopScanning() {
        scanDesired = false
        centralManager?.stopScan()
        runOnMain { [weak self] in self?.model.applyScanning(false) }
    }

    // MARK: - Host mode: advertising the room

    /// Starts (or refreshes) the host advertisement for the room.
    func startAdvertising(
        sessionCode: String,
        hostName: String,
        partyMode: Bool,
        hasSpace: Bool = true,
        queueNonEmpty: Bool = false
    ) {
        advertisingHasSpace = hasSpace
        advertisingQueueNonEmpty = queueNonEmpty
        advertisingRoom = BleJoinPayload.decode(
            BleJoinPayload.encode(
                sessionCode: sessionCode,
                hostName: hostName,
                partyMode: partyMode,
                hasSpace: hasSpace,
                queueNonEmpty: queueNonEmpty
            )
        )
        guard let advertisingRoom else { return }
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
        } else if peripheralManager?.state == .poweredOn {
            beginAdvertisingIfDesired()
        }
    }

    private func beginAdvertisingIfDesired() {
        guard let room = advertisingRoom, let peripheralManager,
              peripheralManager.state == .poweredOn else { return }
        guard let manufacturerData = BleJoinPayload.encodeManufacturerData(
            sessionCode: room.sessionCode,
            hostName: room.hostName,
            partyMode: room.partyMode,
            hasSpace: advertisingHasSpace,
            queueNonEmpty: advertisingQueueNonEmpty
        ) else { return }
        peripheralManager.stopAdvertising()
        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [jamBeaconServiceUUID],
            CBAdvertisementDataManufacturerDataKey: manufacturerData,
        ])
        runOnMain { [weak self] in self?.model.applyAdvertising(true) }
    }

    /// Updates just the live admission flags on a running advertisement
    /// (the 32-cap admission result + queue-non-empty hint).
    func updateAdvertisementFlags(hasSpace: Bool, queueNonEmpty: Bool) {
        guard advertisingRoom != nil else { return }
        advertisingHasSpace = hasSpace
        advertisingQueueNonEmpty = queueNonEmpty
        beginAdvertisingIfDesired()
    }

    func stopAdvertising() {
        advertisingRoom = nil
        peripheralManager?.stopAdvertising()
        runOnMain { [weak self] in self?.model.applyAdvertising(false) }
    }

    // MARK: - Main-actor trampoline (SW-DEC-03: structured concurrency hops)

    private func runOnMain(_ update: @escaping @MainActor () -> Void) {
        Task { @MainActor in update() }
    }
}

// MARK: - CBCentralManagerDelegate (guest side)

extension CoreBluetoothJamBeacon: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        runOnMain { [weak self] in
            self?.model.applyAvailability(Self.availability(from: central.state))
        }
        if central.state == .poweredOn {
            beginScanIfDesired()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // Parse FIRST (total codec; foreign payloads die here), model SECOND.
        let decoded = BleJoinPayload.fromManufacturerData(
            advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data
        )
        guard let decoded else { return }

        let rssi = RSSI.intValue
        let now = Int(DispatchTime.now().uptimeNanoseconds)
        let room = DiscoveredJamRoom(
            sessionCode: decoded.sessionCode,
            hostName: decoded.hostName,
            partyMode: decoded.partyMode,
            hasSpace: decoded.hasSpace,
            queueNonEmpty: decoded.queueNonEmpty,
            rssi: rssi,
            lastSeenUptimeNs: now
        )
        runOnMain { [weak self] in
            self?.model.applySighting(room)
        }
    }

    fileprivate static func availability(from state: CBManagerState) -> JamBeaconModel.Availability {
        switch state {
        case .unknown: return .unknown
        case .resetting: return .resetting
        case .unsupported: return .unsupported
        case .unauthorized: return .unauthorized
        case .poweredOff: return .poweredOff
        case .poweredOn: return .poweredOn
        @unknown default: return .unknown
        }
    }
}

// MARK: - CBPeripheralManagerDelegate (host side)

extension CoreBluetoothJamBeacon: CBPeripheralManagerDelegate {

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        runOnMain { [weak self] in
            self?.model.applyPeripheralAvailability(
                Self.availability(from: peripheral.state)
            )
        }
        if peripheral.state == .poweredOn {
            beginAdvertisingIfDesired()
        } else {
            runOnMain { [weak self] in self?.model.applyAdvertising(false) }
        }
    }
}
