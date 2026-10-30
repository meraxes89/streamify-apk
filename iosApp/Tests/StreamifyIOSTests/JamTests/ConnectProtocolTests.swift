import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-05 (Engineer 4): the Connect JSON protocol frames — byte-compatible
// with the Android `ConnectCommandCodec` / `LanConnectGateway` bodies, so an
// iPhone remote controller drives an Android sink and vice versa. The HTTP
// frame codec is pure and pinned here end-to-end (build → parse → consume,
// pipelining included).
// ─────────────────────────────────────────────────────────────────────────────

final class ConnectProtocolTests: XCTestCase {

    // ── Command codec (Android `ConnectCommandCodec` parity) ────────────────

    func testCommandEncodingIsByteCompatibleWithTheAndroidCodec() {
        XCTAssertEqual(
            "{\"op\":\"playPause\",\"play\":true}",
            ConnectCommandCodec.encode(.playPause(play: true))
        )
        XCTAssertEqual(
            "{\"op\":\"seek\",\"positionMs\":42000}",
            ConnectCommandCodec.encode(.seek(positionMs: 42_000))
        )
        XCTAssertEqual(
            "{\"op\":\"scrub\",\"positionMs\":1234}",
            ConnectCommandCodec.encode(.scrub(positionMs: 1_234))
        )
        XCTAssertEqual(
            "{\"op\":\"queueReorder\",\"fromIndex\":1,\"toIndex\":3}",
            ConnectCommandCodec.encode(.queueReorder(fromIndex: 1, toIndex: 3))
        )
        XCTAssertEqual("{\"op\":\"skipNext\"}", ConnectCommandCodec.encode(.skipNext))
        XCTAssertEqual("{\"op\":\"skipPrevious\"}", ConnectCommandCodec.encode(.skipPrevious))
        // Volumes serialize with Java JSONObject double semantics ("0.5", "1.0").
        XCTAssertEqual(
            "{\"op\":\"setVolume\",\"volume\":0.5}",
            ConnectCommandCodec.encode(.setVolume(volume: 0.5))
        )
        XCTAssertEqual(
            "{\"op\":\"setVolume\",\"volume\":1.0}",
            ConnectCommandCodec.encode(.setVolume(volume: 1))
        )
    }

    func testCommandRoundTripAcrossEveryOp() {
        let commands: [ConnectCommand] = [
            .playPause(play: false),
            .seek(positionMs: 90_000),
            .scrub(positionMs: 1_500),
            .setVolume(volume: 0.75),
            .queueReorder(fromIndex: 0, toIndex: 2),
            .skipNext,
            .skipPrevious,
        ]
        for command in commands {
            XCTAssertEqual(command, ConnectCommandCodec.decode(ConnectCommandCodec.encode(command)))
        }
    }

    func testCommandDecodeIsTotalAgainstHostileInput() {
        XCTAssertNil(ConnectCommandCodec.decode(""))
        XCTAssertNil(ConnectCommandCodec.decode("not json"))
        XCTAssertNil(ConnectCommandCodec.decode("{\"op\":\"nope\"}"))
        XCTAssertNil(ConnectCommandCodec.decode("{\"op\":\"seek\"}"))             // missing field
        XCTAssertNil(ConnectCommandCodec.decode("{\"op\":\"seek\",\"positionMs\":\"x\"}"))
        // Volumes clamp into [0, 1] (Kotlin parity).
        XCTAssertEqual(
            .setVolume(volume: 1),
            ConnectCommandCodec.decode("{\"op\":\"setVolume\",\"volume\":1.7}")
        )
    }

    // ── Handshake frame (Android `LanConnectGateway.connect` body parity) ──

    func testHandshakeBodyMatchesTheAndroidGatewayShape() {
        var handshake = ConnectHandshake()
        handshake.deviceId = "ios-abc12345"
        handshake.positionMs = 61_234
        handshake.currentIndex = 2
        handshake.play = true
        handshake.volume = 0.7
        handshake.queueTitles = ["Nightcall", "Doin' It Right"]

        let json = handshake.json
        XCTAssertTrue(json.hasPrefix("{\"deviceId\":\"ios-abc12345\",\"positionMs\":61234"))
        XCTAssertTrue(json.contains("\"currentIndex\":2"))
        XCTAssertTrue(json.contains("\"play\":true"))
        XCTAssertTrue(json.contains("\"q0\":\"Nightcall\""))
        XCTAssertTrue(json.contains("\"q1\":\"Doin' It Right\""))

        let decoded = ConnectHandshake.decode(json)
        XCTAssertEqual(handshake, decoded)
    }

    func testHandshakeDecodeIsTotal() {
        XCTAssertNil(ConnectHandshake.decode(""))
        XCTAssertNil(ConnectHandshake.decode("[]"))
        // Missing fields degrade to defaults, never trap.
        let sparse = ConnectHandshake.decode("{\"deviceId\":\"x\"}")
        XCTAssertEqual("x", sparse?.deviceId)
        XCTAssertEqual(0, sparse?.positionMs)
        XCTAssertEqual(1.0, sparse?.volume ?? -1)
    }

    // ── HTTP frame codec ────────────────────────────────────────────────────

    func testRequestFrameBuildsParsesAndConsumesExactly() {
        let frame = ConnectHttpCodec.buildRequest(
            path: ConnectHttpCodec.connectCommandPath,
            jsonBody: "{\"op\":\"skipNext\"}"
        )
        let parsed = ConnectHttpCodec.parseRequest(frame)
        XCTAssertEqual(ConnectHttpCodec.connectCommandPath, parsed?.path)
        XCTAssertEqual("{\"op\":\"skipNext\"}", parsed?.body)
        XCTAssertEqual(frame.count, parsed?.consumedBytes)
    }

    func testIncompleteFramesReturnNilUntilTheBodyArrives() {
        let frame = ConnectHttpCodec.buildRequest(
            path: ConnectHttpCodec.connectSessionPath,
            jsonBody: "{\"deviceId\":\"x\",\"positionMs\":1}"
        )
        // Header only: no complete body yet.
        let headerOnly = Data(frame.prefix(frame.count - 2))
        XCTAssertNil(ConnectHttpCodec.parseRequest(headerOnly))
        // Full frame parses.
        XCTAssertNotNil(ConnectHttpCodec.parseRequest(frame))
    }

    func testPipelinedFramesConsumeExactlyTheirOwnBytes() {
        let first = ConnectHttpCodec.buildRequest(
            path: ConnectHttpCodec.connectSessionPath,
            jsonBody: "{\"deviceId\":\"a\"}"
        )
        let second = ConnectHttpCodec.buildRequest(
            path: ConnectHttpCodec.connectCommandPath,
            jsonBody: "{\"op\":\"skipNext\"}"
        )
        let pipelined = first + second

        let p1 = ConnectHttpCodec.parseRequest(pipelined)
        XCTAssertEqual(first.count, p1?.consumedBytes)
        XCTAssertEqual(ConnectHttpCodec.connectSessionPath, p1?.path)

        let remainder = pipelined.subdata(
            in: pipelined.index(pipelined.startIndex, offsetBy: p1!.consumedBytes)..<pipelined.endIndex
        )
        let p2 = ConnectHttpCodec.parseRequest(remainder)
        XCTAssertEqual(ConnectHttpCodec.connectCommandPath, p2?.path)
        XCTAssertEqual("{\"op\":\"skipNext\"}", p2?.body)
    }

    func testNonPostAndGarbageRequestsDieAtTheBoundary() {
        XCTAssertNil(ConnectHttpCodec.parseRequest(Data("GET /x HTTP/1.1\r\n\r\n".utf8)))
        XCTAssertNil(ConnectHttpCodec.parseRequest(Data()))
        XCTAssertNil(ConnectHttpCodec.parseRequest(Data("\r\n\r\n".utf8)))
        // Response completeness gate.
        XCTAssertTrue(ConnectHttpCodec.isResponseComplete(ConnectHttpCodec.buildResponse()))
        XCTAssertFalse(
            ConnectHttpCodec.isResponseComplete(Data("HTTP/1.1 200 OK\r\n".utf8))
        )
    }

    // ── TXT record parsing (discovery identity) ────────────────────────────

    func testTxtRecordParsingCoversSeparatorVariants() {
        XCTAssertEqual([:], BonjourDeviceDiscovery.parseTxtRecord(nil))
        XCTAssertEqual([:], BonjourDeviceDiscovery.parseTxtRecord(""))

        let usSeparated = "nm=Riya's Mac\u{01}pf=macos\u{01}did=mac-1234\u{01}cap=lan"
        let parsed = BonjourDeviceDiscovery.parseTxtRecord(usSeparated)
        XCTAssertEqual("Riya's Mac", parsed["nm"])
        XCTAssertEqual("macos", parsed["pf"])
        XCTAssertEqual("mac-1234", parsed["did"])
        XCTAssertEqual("lan", parsed["cap"])

        // Single-entry records and junk entries never trap.
        XCTAssertEqual(["did": "android-77"], BonjourDeviceDiscovery.parseTxtRecord("did=android-77"))
        XCTAssertEqual([:], BonjourDeviceDiscovery.parseTxtRecord("noequalsign"))
    }

    // ── Discovered device projection ───────────────────────────────────────

    func testDiscoveredDevicePlatformIconsAndIdentity() {
        let device = DiscoveredConnectDevice(deviceId: "mac-1234", name: "Studio Mac", platform: "macos")
        XCTAssertEqual("mac-1234", device.id)
        XCTAssertEqual("macbook", device.platformIcon)
        XCTAssertEqual("antenna.radiowaves.left.and.right",
                       DiscoveredConnectDevice(deviceId: "a", name: "A", platform: "android").platformIcon)
    }
}
