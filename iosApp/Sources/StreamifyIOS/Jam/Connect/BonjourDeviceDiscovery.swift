import Foundation
import Network
import Observation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-05 (Engineer 4): Bonjour LAN Connect pairing — the Apple
// Network.framework counterpart of the Android `ConnectDeviceRegistry` +
// `LanConnectGateway` stack (Gap #52 / Phase 4).
//
// DISCOVERY: NWBrowser browses `_streamify-connect._tcp` on the local domain;
// NWListener advertises the same service with a TXT record carrying the
// device identity (name, platform, capabilities). Other Streamify devices on
// the same Wi-Fi network — Mac, Android, Desktop — surface in the device
// picker.
//
// SESSION EXCHANGE (Android interop): remote-control sessions speak the
// exact JSON protocol the Android `LanConnectGateway` speaks — one
// `POST /connect-session` handshake frame (deviceId, position, queue), then
// `POST /connect-command` frames carrying the `ConnectCommandCodec` JSON,
// released with `POST /connect-session/leave`. The frame codec
// (`ConnectHttpCodec`) is pure and unit-tested; the socket plumbing is
// NWConnection with structured-concurrency hops onto the main actor.
//
// ARCHITECTURE: the jam/CRDT/PTP machinery stays sovereign in the Rust core;
// this file is pure transport. The LAN UDP mesh beacon (port 7777) is the
// engine's own rail — its Swift codec mirror (`LanBeaconPacket`) exists for
// parity tests and lights up when the real UniFFI lane lands.
// ─────────────────────────────────────────────────────────────────────────────

// MARK: - Connect protocol vocabulary (mirrors ConnectModels.kt)

/// Commands the silent remote-controller forwards to the active sink
/// (byte-compatible with the Android `ConnectCommandCodec` JSON).
enum ConnectCommand: Equatable, Sendable {
    case playPause(play: Bool)
    case seek(positionMs: Int)
    case scrub(positionMs: Int)
    case setVolume(volume: Double)
    case queueReorder(fromIndex: Int, toIndex: Int)
    case skipNext
    case skipPrevious
}

enum ConnectCommandCodec {

    /// One-line JSON encoding — byte-compatible with the Kotlin codec
    /// (`JSONObject.toString()` with no spaces).
    static func encode(_ command: ConnectCommand) -> String {
        switch command {
        case .playPause(let play):
            return "{\"op\":\"playPause\",\"play\":\(play)}"
        case .seek(let positionMs):
            return "{\"op\":\"seek\",\"positionMs\":\(positionMs)}"
        case .scrub(let positionMs):
            return "{\"op\":\"scrub\",\"positionMs\":\(positionMs)}"
        case .setVolume(let volume):
            // Swift's default Double description matches Java's
            // JSONObject double rendering ("0.5", "1.0") — byte parity.
            return "{\"op\":\"setVolume\",\"volume\":\(volume)}"
        case .queueReorder(let from, let to):
            return "{\"op\":\"queueReorder\",\"fromIndex\":\(from),\"toIndex\":\(to)}"
        case .skipNext:
            return "{\"op\":\"skipNext\"}"
        case .skipPrevious:
            return "{\"op\":\"skipPrevious\"}"
        }
    }

    /// Total parser: any malformed input returns nil (JSONObject parity).
    static func decode(_ raw: String) -> ConnectCommand? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let json = object as? [String: Any],
              let op = json["op"] as? String
        else { return nil }
        switch op {
        case "playPause":
            guard let play = json["play"] as? Bool else { return nil }
            return .playPause(play: play)
        case "seek":
            guard let positionMs = json["positionMs"] as? Int else { return nil }
            return .seek(positionMs: positionMs)
        case "scrub":
            guard let positionMs = json["positionMs"] as? Int else { return nil }
            return .scrub(positionMs: positionMs)
        case "setVolume":
            guard let volume = (json["volume"] as? NSNumber)?.doubleValue else { return nil }
            return .setVolume(volume: min(max(volume, 0), 1))
        case "queueReorder":
            guard let from = json["fromIndex"] as? Int, let to = json["toIndex"] as? Int
            else { return nil }
            return .queueReorder(fromIndex: from, toIndex: to)
        case "skipNext":
            return .skipNext
        case "skipPrevious":
            return .skipPrevious
        default:
            return nil
        }
    }
}

/// The connect-session handshake fingerprint (position + queue at transfer).
struct ConnectHandshake: Equatable, Sendable {
    var deviceId: String = "this-device"
    var positionMs: Int = 0
    var currentIndex: Int = 0
    var play: Bool = false
    var volume: Double = 1.0
    var queueTitles: [String] = []

    /// The exact JSON body shape the Android `LanConnectGateway.connect`
    /// posts (queue entries keyed q0, q1, …).
    var json: String {
        let queue = queueTitles.enumerated()
            .map { "\"q\($0.offset)\":\(Self.escape($0.element))" }
            .joined(separator: ",")
        return "{\"deviceId\":\(Self.escape(deviceId)),\"positionMs\":\(positionMs)," +
            "\"currentIndex\":\(currentIndex),\"play\":\(play ? "true" : "false")," +
            "\"volume\":\(volume),\"queue\":{\(queue)}}"
    }

    static func decode(_ raw: String) -> ConnectHandshake? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let json = object as? [String: Any]
        else { return nil }
        var handshake = ConnectHandshake()
        handshake.deviceId = json["deviceId"] as? String ?? handshake.deviceId
        handshake.positionMs = json["positionMs"] as? Int ?? 0
        handshake.currentIndex = json["currentIndex"] as? Int ?? 0
        handshake.play = json["play"] as? Bool ?? false
        handshake.volume = (json["volume"] as? NSNumber)?.doubleValue ?? 1.0
        if let queue = json["queue"] as? [String: Any] {
            handshake.queueTitles = queue
                .sorted { lhs, rhs in
                    (Int(lhs.key.dropFirst()) ?? 0) < (Int(rhs.key.dropFirst()) ?? 0)
                }
                .compactMap { $0.value as? String }
        }
        return handshake
    }

    private static func escape(_ raw: String) -> String {
        var out = "\""
        for scalar in raw.unicodeScalars {
            switch scalar {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            default: out.unicodeScalars.append(scalar)
            }
        }
        return out + "\""
    }
}

// MARK: - HTTP frame codec (pure; unit-tested)

enum ConnectHttpCodec {

    static let connectSessionPath = "/connect-session"
    static let connectCommandPath = "/connect-command"
    static let connectLeavePath = "/connect-session/leave"

    /// Builds one minimal HTTP/1.1 POST frame.
    static func buildRequest(path: String, jsonBody: String) -> Data {
        let body = Data(jsonBody.utf8)
        var request = Data()
        request.append(contentsOf: Array("POST \(path) HTTP/1.1\r\n".utf8))
        request.append(contentsOf: Array("Host: streamify-connect\r\n".utf8))
        request.append(contentsOf: Array("Content-Type: application/json\r\n".utf8))
        request.append(contentsOf: Array("Content-Length: \(body.count)\r\n".utf8))
        request.append(contentsOf: Array("Connection: keep-alive\r\n\r\n".utf8))
        request.append(body)
        return request
    }

    struct Request: Equatable {
        let path: String
        let body: String
        /// Bytes consumed by this frame (pipelining-safe buffering).
        let consumedBytes: Int
    }

    /// Total parser for one received HTTP request frame (header + body).
    /// Returns nil until the full body (Content-Length) has arrived.
    static func parseRequest(_ data: Data) -> Request? {
        guard let headerEnd = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let headerData = data.subdata(in: data.startIndex..<headerEnd.lowerBound)
        guard let header = String(data: headerData, encoding: .utf8) else { return nil }
        let lines = header.components(separatedBy: "\r\n")
        guard let requestLine = lines.first else { return nil }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2, parts[0] == "POST" else { return nil }

        var contentLength = 0
        for line in lines.dropFirst() {
            let lower = line.lowercased()
            if lower.hasPrefix("content-length:") {
                contentLength = Int(
                    line.dropFirst("content-length:".count).trimmingCharacters(in: .whitespaces)
                ) ?? 0
            }
        }

        let bodyStart = headerEnd.upperBound
        let available = data.distance(from: bodyStart, to: data.endIndex)
        guard available >= contentLength else { return nil } // incomplete frame
        let bodyData = data.subdata(in: bodyStart..<data.index(bodyStart, offsetBy: contentLength))
        let body = String(data: bodyData, encoding: .utf8) ?? ""

        let headerBytes = data.distance(from: data.startIndex, to: bodyStart)
        return Request(
            path: String(parts[1]),
            body: body,
            consumedBytes: headerBytes + contentLength
        )
    }

    /// The minimal success response.
    static func buildResponse(status: Int = 200) -> Data {
        Data("HTTP/1.1 \(status) OK\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n".utf8)
    }

    /// True once a full response header has arrived.
    static func isResponseComplete(_ data: Data) -> Bool {
        data.range(of: Data("\r\n\r\n".utf8)) != nil
    }
}

// MARK: - Discovered devices (the `@Observable` projection)

/// One discovered Streamify Connect target on the LAN.
struct DiscoveredConnectDevice: Equatable, Sendable, Identifiable {
    /// Stable identity: the advertised device id (TXT `did`).
    let deviceId: String
    var name: String
    var platform: String
    var supportsHandoff: Bool = true

    var id: String { deviceId }

    var platformIcon: String {
        switch platform.lowercased() {
        case "android": return "antenna.radiowaves.left.and.right"
        case "macos", "mac": return "macbook"
        case "windows": return "pc"
        case "linux", "desktop": return "desktopcomputer"
        default: return "apple.tv"
        }
    }
}

/// The `@Observable` projection the device picker reads. Main-queue
/// confined: every mutation arrives through the transport's `runOnMain`
/// trampoline (the transport is nonisolated so its DispatchQueues and NW
/// callbacks stay off the main actor; the writes themselves still hop on).
@Observable
final class ConnectDiscoveryModel {

    enum BrowserState: Equatable {
        case idle
        case browsing
        case failed(String)
    }

    private(set) var browserState: BrowserState = .idle
    private(set) var isAdvertising = false
    private(set) var devices: [DiscoveredConnectDevice] = []
    private(set) var activeSessionDevice: DiscoveredConnectDevice?

    /// Remote-control events flowing back from an active sink (push only).
    private(set) var lastRemoteCommand: ConnectCommand?
    private(set) var lastRemotePositionMs = 0

    // MARK: Mutations (transport hops in on the main actor)

    func applyBrowserState(_ state: BrowserState) {
        browserState = state
    }

    func applyAdvertising(_ advertising: Bool) {
        isAdvertising = advertising
    }

    func applyDevices(_ devices: [DiscoveredConnectDevice]) {
        self.devices = devices
    }

    func applySession(device: DiscoveredConnectDevice?) {
        activeSessionDevice = device
    }

    func applyRemoteCommand(_ command: ConnectCommand) {
        lastRemoteCommand = command
        if case .seek(let positionMs) = command { lastRemotePositionMs = positionMs }
        if case .scrub(let positionMs) = command { lastRemotePositionMs = positionMs }
    }
}

// MARK: - The discovery transport (NWBrowser + NWListener + NWConnection)

final class BonjourDeviceDiscovery: @unchecked Sendable {

    static let serviceType = "_streamify-connect._tcp"

    let model = ConnectDiscoveryModel()

    private let queue = DispatchQueue(label: "streamify.bonjour.discovery")
    private var browser: NWBrowser?
    private var listener: NWListener?

    /// Inbound connections (sink side) keyed by object identifier.
    private var inboundConnections: [ObjectIdentifier: NWConnection] = [:]
    /// The outbound remote-control connection (controller side).
    private var outboundConnection: NWConnection?
    private var outboundDevice: DiscoveredConnectDevice?
    private var outboundBuffer = Data()
    private var inboundBuffers: [ObjectIdentifier: Data] = [:]

    private let deviceId: String
    private let deviceName: String
    private let platform: String

    init(
        deviceId: String = "ios-\(UUID().uuidString.prefix(8).lowercased())",
        deviceName: String = "My iPhone",
        platform: String = "ios"
    ) {
        self.deviceId = deviceId
        self.deviceName = deviceName
        self.platform = platform
    }

    // MARK: - Browsing (guest side of discovery)

    func startBrowsing() {
        guard browser == nil else { return }
        let params = NWParameters()
        params.includePeerToPeer = true
        let browser = NWBrowser(
            for: .bonjour(type: Self.serviceType, domain: nil),
            using: params
        )
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.handleBrowseResults(results)
        }
        browser.stateUpdateHandler = { [weak self] state in
            self?.handleBrowserState(state)
        }
        browser.start(queue: queue)
        self.browser = browser
        runOnMain { [weak self] in self?.model.applyBrowserState(.browsing) }
    }

    func stopBrowsing() {
        browser?.cancel()
        browser = nil
        runOnMain { [weak self] in
            self?.model.applyBrowserState(.idle)
            self?.model.applyDevices([])
        }
    }

    private func handleBrowserState(_ state: NWBrowser.State) {
        switch state {
        case .failed(let error):
            runOnMain { [weak self] in
                self?.model.applyBrowserState(.failed(error.localizedDescription))
            }
        case .cancelled:
            runOnMain { [weak self] in self?.model.applyBrowserState(.idle) }
        default:
            break
        }
    }

    private func handleBrowseResults(_ results: Set<NWBrowser.Result>) {
        var devices: [DiscoveredConnectDevice] = []
        for result in results {
            // NWBrowser surfaces only the service endpoint — Network.framework
            // browsing does NOT expose the DNS-SD TXT record (the 4th pattern
            // component is the NWInterface, not the TXT data). Identity
            // therefore falls back to the advertised service name, exactly
            // like the TXT-less path of the Kotlin resolver.
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            let displayName = name.isEmpty ? "Streamify device" : name
            devices.append(
                DiscoveredConnectDevice(
                    deviceId: name,
                    name: displayName,
                    platform: "streamify",
                    supportsHandoff: true
                )
            )
        }
        runOnMain { [weak self] in self?.model.applyDevices(devices) }
    }

    /// Lenient TXT-record parser: DNS-SD TXT data is a concatenation of
    /// `key=value` entries; when surfaced as a String the entries are
    /// separated by unit separators (0x01) or NULs depending on OS version.
    /// Identity ALWAYS falls back to the advertised service name.
    static func parseTxtRecord(_ raw: String?) -> [String: String] {
        guard let raw, !raw.isEmpty else { return [:] }
        var pairs: [String: String] = [:]
        let separators = CharacterSet(charactersIn: "\u{01}\u{00};,")
        for entry in raw.components(separatedBy: separators) {
            guard let eq = entry.firstIndex(of: "="), eq > entry.startIndex else { continue }
            let key = String(entry[entry.startIndex..<eq])
            let value = String(entry[entry.index(after: eq)...])
            if !key.isEmpty { pairs[key] = value }
        }
        return pairs
    }

    // MARK: - Advertising (host side of discovery)

    func startAdvertising() {
        guard listener == nil else { return }
        do {
            let params = NWParameters.tcp
            params.allowLocalEndpointReuse = true
            let listener = try NWListener(using: params)
            let txt = NWTXTRecord()
            // NWTXTRecord is written through its String subscript
            // (there is no setValue/setStringValue — verified against the
            // Network framework interface).
            txt["nm"] = deviceName
            txt["pf"] = platform
            txt["did"] = deviceId
            txt["cap"] = "lan"
            txt["ver"] = "1"
            listener.service = NWListener.Service(
                name: deviceId,
                type: Self.serviceType,
                domain: nil,
                txtRecord: txt
            )
            listener.newConnectionHandler = { [weak self] connection in
                self?.acceptInbound(connection)
            }
            listener.stateUpdateHandler = { [weak self] state in
                if case .ready = state {
                    self?.runOnMain { [weak self] in self?.model.applyAdvertising(true) }
                }
                if case .failed = state {
                    self?.runOnMain { [weak self] in self?.model.applyAdvertising(false) }
                }
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            runOnMain { [weak self] in self?.model.applyAdvertising(false) }
        }
    }

    func stopAdvertising() {
        listener?.cancel()
        listener = nil
        for (_, connection) in inboundConnections {
            connection.cancel()
        }
        inboundConnections.removeAll()
        inboundBuffers.removeAll()
        runOnMain { [weak self] in self?.model.applyAdvertising(false) }
    }

    // MARK: - Sink side: accepting remote-control sessions

    private func acceptInbound(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        inboundConnections[id] = connection
        inboundBuffers[id] = Data()
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.receiveInbound(connection)
            case .failed, .cancelled:
                self.teardownInbound(connection)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func teardownInbound(_ connection: NWConnection) {
        inboundConnections.removeValue(forKey: ObjectIdentifier(connection))
        inboundBuffers.removeValue(forKey: ObjectIdentifier(connection))
    }

    private func receiveInbound(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            let id = ObjectIdentifier(connection)
            if let data {
                self.inboundBuffers[id, default: Data()].append(data)
                self.processInboundBuffer(connection)
            }
            if error == nil && !isComplete {
                self.receiveInbound(connection)
            }
        }
    }

    private func processInboundBuffer(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        guard let buffer = inboundBuffers[id],
              let request = ConnectHttpCodec.parseRequest(buffer)
        else { return }

        // Consume exactly the frame's bytes; keep any pipelined remainder.
        if request.consumedBytes < buffer.count {
            let remainderStart = buffer.index(
                buffer.startIndex, offsetBy: request.consumedBytes
            )
            inboundBuffers[id] = buffer.subdata(in: remainderStart..<buffer.endIndex)
        } else {
            inboundBuffers[id] = Data()
        }

        connection.send(
            content: ConnectHttpCodec.buildResponse(),
            contentContext: .defaultMessage,
            isComplete: true,
            completion: .contentProcessed { _ in }
        )

        switch request.path {
        case ConnectHttpCodec.connectSessionPath:
            if let handshake = ConnectHandshake.decode(request.body) {
                runOnMain { [weak self] in
                    self?.model.applyRemoteCommand(.seek(positionMs: handshake.positionMs))
                }
            }
        case ConnectHttpCodec.connectCommandPath:
            if let command = ConnectCommandCodec.decode(request.body) {
                runOnMain { [weak self] in self?.model.applyRemoteCommand(command) }
            }
        case ConnectHttpCodec.connectLeavePath:
            connection.cancel()
        default:
            break
        }
    }

    // MARK: - Controller side: opening a remote-control session

    /// Connects to a discovered device's endpoint and performs the
    /// Android-compatible `/connect-session` handshake.
    func connect(device: DiscoveredConnectDevice, handshake: ConnectHandshake) {
        guard let browser,
              let result = browser.browseResults.first(where: { matches($0, device: device) })
        else {
            runOnMain { [weak self] in self?.model.applySession(device: nil) }
            return
        }
        let connection = NWConnection(to: result.endpoint, using: .tcp)
        outboundConnection = connection
        outboundDevice = device
        outboundBuffer = Data()
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                var frame = ConnectHandshake(
                    deviceId: handshake.deviceId,
                    positionMs: handshake.positionMs,
                    currentIndex: handshake.currentIndex,
                    play: handshake.play,
                    volume: handshake.volume,
                    queueTitles: handshake.queueTitles
                )
                frame.deviceId = self.deviceId
                self.sendFrame(path: ConnectHttpCodec.connectSessionPath, json: frame.json)
                self.receiveOutbound()
                self.runOnMain { [weak self] in
                    self?.model.applySession(device: device)
                }
            case .failed, .cancelled:
                self.outboundConnection = nil
                self.outboundDevice = nil
                self.runOnMain { [weak self] in self?.model.applySession(device: nil) }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    /// Forwards one remote command to the active sink (Android frame shape).
    func sendCommand(_ command: ConnectCommand) {
        sendFrame(path: ConnectHttpCodec.connectCommandPath, json: ConnectCommandCodec.encode(command))
    }

    func disconnect() {
        guard let connection = outboundConnection else { return }
        // Best-effort leave frame, then cancel (Android parity).
        sendFrame(path: ConnectHttpCodec.connectLeavePath, json: "{}")
        connection.cancel()
        outboundConnection = nil
        outboundDevice = nil
        runOnMain { [weak self] in self?.model.applySession(device: nil) }
    }

    // MARK: - Frame plumbing

    private func sendFrame(path: String, json: String) {
        guard let connection = outboundConnection else { return }
        connection.send(
            content: ConnectHttpCodec.buildRequest(path: path, jsonBody: json),
            contentContext: .defaultMessage,
            isComplete: true,
            completion: .contentProcessed { _ in }
        )
    }

    private func receiveOutbound() {
        guard let connection = outboundConnection else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data { self.outboundBuffer.append(data) }
            if ConnectHttpCodec.isResponseComplete(self.outboundBuffer) {
                self.outboundBuffer = Data()
            }
            if error == nil && !isComplete {
                self.receiveOutbound()
            }
        }
    }

    private func matches(_ result: NWBrowser.Result, device: DiscoveredConnectDevice) -> Bool {
        guard case let .service(name, _, _, _) = result.endpoint else { return false }
        return name == device.deviceId || name == device.name
    }

    private func runOnMain(_ update: @escaping @MainActor () -> Void) {
        Task { @MainActor in update() }
    }
}
