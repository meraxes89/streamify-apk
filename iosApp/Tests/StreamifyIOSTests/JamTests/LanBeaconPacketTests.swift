import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-05 (Engineer 4): LAN UDP Beacon 0x09 codec — the Swift mirror of
// `app/src/test/java/com/streamify/app/jam/LanBeaconPacketTest.kt`. Pins the
// frozen Rust wire contract from the Apple side: header layout, FNV-1a/32
// integrity, and the total-parser discipline (hostile datagrams die at the
// codec boundary, never inside the listener).
// ─────────────────────────────────────────────────────────────────────────────

final class LanBeaconPacketTests: XCTestCase {

    private let sessionId = Data((0..<16).map { UInt8(truncatingIfNeeded: ($0 * 7 + 3)) })

    private func beacon(
        sender: UInt64 = 0x21B2C3D4E5F60718,
        caps: UInt16 = LanBeaconPacket.capLan | LanBeaconPacket.capWebRtc,
        port: UInt16 = UInt16(LanBeaconPacket.defaultMeshPort)
    ) -> Data {
        LanBeaconPacket.encodeBeacon(senderId: sender, sessionId: sessionId, caps: caps, port: port)!
    }

    // ── Round trip ─────────────────────────────────────────────────────────

    func testBeaconRoundTripsSenderRoomIdentityCapsAndPort() {
        let d = LanBeaconPacket.decode(beacon())
        XCTAssertNotNil(d)
        XCTAssertEqual(16, d?.senderIdHex.count)
        XCTAssertEqual(32, d?.sessionIdHex.count)
        XCTAssertEqual(true, d?.hasLan)
        XCTAssertEqual(true, d?.hasWebRtc)
        XCTAssertEqual(LanBeaconPacket.defaultMeshPort, d?.meshPort)
    }

    func testCapsBitsAreReportedIndependently() {
        let lanOnly = LanBeaconPacket.decode(beacon(caps: LanBeaconPacket.capLan))!
        XCTAssertTrue(lanOnly.hasLan)
        XCTAssertFalse(lanOnly.hasWebRtc)

        let none = LanBeaconPacket.decode(beacon(caps: 0))!
        XCTAssertFalse(none.hasLan)
        XCTAssertFalse(none.hasWebRtc)
    }

    func testNonDefaultMeshPortsSurvive() {
        let d = LanBeaconPacket.decode(beacon(port: 7778))!
        XCTAssertEqual(7778, d.meshPort)
    }

    func testFnv1a32MatchesTheRustDeviationD2Contract() {
        // Known vector: FNV-1a/32 of 42 zero bytes (offset basis + 42 zero
        // folds). Pinning this keeps the Swift, Kotlin and Rust checksummers
        // from ever silently diverging — a divergence would blackhole every
        // beacon as "corrupt".
        let zeros = Data(count: 64)
        var expected: UInt32 = 0x811C9DC5
        for _ in 0..<42 {
            expected ^= 0
            expected = expected &* 0x01000193
        }
        XCTAssertEqual(expected, LanBeaconPacket.fnv1a32(zeros))
    }

    // ── Total-parser hardening ─────────────────────────────────────────────

    func testNilAndShortDatagramsAreRejected() {
        XCTAssertNil(LanBeaconPacket.decode(nil))
        XCTAssertNil(LanBeaconPacket.decode(Data()))
        XCTAssertNil(
            LanBeaconPacket.decode(
                Data(count: LanBeaconPacket.headerLen + LanBeaconPacket.beaconPayloadLen - 1)
            )
        )
    }

    func testWrongMagicVersionOrMessageTypeIsRejected() {
        var badMagic = [UInt8](beacon()); badMagic[0] = 0x00
        XCTAssertNil(LanBeaconPacket.decode(Data(badMagic)))

        var badVersion = [UInt8](beacon()); badVersion[2] = 0x02
        XCTAssertNil(LanBeaconPacket.decode(Data(badVersion)))

        var badMsg = [UInt8](beacon()); badMsg[3] = 0x08 // not BEACON 0x09
        XCTAssertNil(LanBeaconPacket.decode(Data(badMsg)))
    }

    func testCorruptedChecksumIsRejected() {
        var corrupt = [UInt8](beacon())
        // Flip one bit inside the checksummed header span.
        corrupt[10] ^= 0x40
        XCTAssertNil(LanBeaconPacket.decode(Data(corrupt)))
    }

    func testWrongPayloadLengthIsRejected() {
        // Overwrite payload_len (offset 40, u16 LE) with a lie, then repair
        // the checksum so the frame is provably killed by the length check
        // itself — not incidentally by checksum mismatch.
        var lie = [UInt8](beacon())
        lie[40] = 0x20
        lie[41] = 0x00
        XCTAssertNil(LanBeaconPacket.decode(ByteBufferFixer.fix(Data(lie))))
    }

    func testHostileZeroPortsAreRejected() {
        // A zero port is smuggled past encode by hand-building the frame.
        var hostile = [UInt8](beacon(port: 7777))
        // Zero the port in the payload (last 2 bytes) and fix the checksum.
        let portOffset = hostile.count - 2
        hostile[portOffset] = 0
        hostile[portOffset + 1] = 0
        XCTAssertNil(LanBeaconPacket.decode(ByteBufferFixer.fix(Data(hostile))))
    }

    // ── Encoder validation ─────────────────────────────────────────────────

    func testEncoderRefusesSessionIdsThatAreNot16Bytes() {
        XCTAssertNil(LanBeaconPacket.encodeBeacon(senderId: 1, sessionId: Data(count: 15), caps: 0, port: 7777))
        XCTAssertNil(LanBeaconPacket.encodeBeacon(senderId: 1, sessionId: Data(count: 17), caps: 0, port: 7777))
    }

    func testHeaderConstantsPinTheFrozenRustGrammar() {
        XCTAssertEqual(0x5354, LanBeaconPacket.magic)
        XCTAssertEqual(3, LanBeaconPacket.protoVersion)
        XCTAssertEqual(0x09, LanBeaconPacket.msgBeacon)
        XCTAssertEqual(46, LanBeaconPacket.headerLen)
        XCTAssertEqual(42, LanBeaconPacket.checksumCoveredLen)
        XCTAssertEqual(12, LanBeaconPacket.beaconPayloadLen)
    }
}

/// Recomputes and writes the FNV-1a checksum at offset 42 (test helper).
private enum ByteBufferFixer {
    static func fix(_ raw: Data) -> Data {
        var out = raw
        let checksum = LanBeaconPacket.fnv1a32(out)
        out[42] = UInt8(checksum & 0xFF)
        out[43] = UInt8((checksum >> 8) & 0xFF)
        out[44] = UInt8((checksum >> 16) & 0xFF)
        out[45] = UInt8((checksum >> 24) & 0xFF)
        return out
    }
}
