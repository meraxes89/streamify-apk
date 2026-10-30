import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-04 (Engineer 4): BLE join-fabric packet codec — the Swift mirror of
// `app/src/test/java/com/streamify/app/jam/BleJoinPayloadTest.kt`.
//
// These tests pin the SAME wire format the Kotlin suite pins: a 6-char room
// code, the host's display name, and the three live room flags. Hostile or
// corrupt scan records must die at the codec boundary — never inside the
// scanner, never inside the engine. Byte-exactness across endianness
// boundaries (the little-endian manufacturer id) is asserted literally.
// ─────────────────────────────────────────────────────────────────────────────

final class BleJoinPayloadTests: XCTestCase {

    private let code = "AB3X9K"

    // ── Wire format ────────────────────────────────────────────────────────

    func testHeaderLayoutIsMagicVersionFlagsSixCharCodeHostName() {
        let bytes = BleJoinPayload.encode(
            sessionCode: code,
            hostName: "Zephyr",
            partyMode: true,
            hasSpace: false,
            queueNonEmpty: true
        )
        XCTAssertNotNil(bytes)
        let b = [UInt8](bytes!)

        XCTAssertEqual(BleJoinPayload.magic0, b[BleJoinPayload.offsetMagic])
        XCTAssertEqual(BleJoinPayload.magic1, b[BleJoinPayload.offsetMagic + 1])
        XCTAssertEqual(BleJoinPayload.version, b[BleJoinPayload.offsetVersion])

        let flags = b[BleJoinPayload.offsetFlags]
        XCTAssertNotEqual(0, flags & BleJoinPayload.flagTopologySingleRender)
        XCTAssertEqual(0, flags & BleJoinPayload.flagRoomHasSpace)
        XCTAssertNotEqual(0, flags & BleJoinPayload.flagQueueNonEmpty)

        let wireCode = String(
            data: Data(b[BleJoinPayload.offsetCode..<(BleJoinPayload.offsetCode + BleJoinPayload.codeBytes)]),
            encoding: .ascii
        )
        XCTAssertEqual(code, wireCode)

        let name = String(
            data: Data(b[BleJoinPayload.offsetHostName...]),
            encoding: .utf8
        )
        XCTAssertEqual("Zephyr", name)
    }

    func testEncodeDecodeRoundTripPreservesEveryField() {
        for party in [false, true] {
            for space in [false, true] {
                for queue in [false, true] {
                    let dec = BleJoinPayload.decode(
                        BleJoinPayload.encode(
                            sessionCode: code,
                            hostName: "Ada Lovelace",
                            partyMode: party,
                            hasSpace: space,
                            queueNonEmpty: queue
                        )
                    )
                    XCTAssertNotNil(dec)
                    XCTAssertEqual(code, dec?.sessionCode)
                    XCTAssertEqual("Ada Lovelace", dec?.hostName)
                    XCTAssertEqual(party, dec?.partyMode)
                    XCTAssertEqual(space, dec?.hasSpace)
                    XCTAssertEqual(queue, dec?.queueNonEmpty)
                }
            }
        }
    }

    func testSessionCodeIsTrimmedAndUppercasedOnTheWire() {
        let dec = BleJoinPayload.decode(
            BleJoinPayload.encode(sessionCode: "  ab3x9k ", hostName: "Host")
        )
        XCTAssertEqual(code, dec?.sessionCode)
    }

    func testEmptyHostNameIsRepresentable() {
        let dec = BleJoinPayload.decode(
            BleJoinPayload.encode(sessionCode: code, hostName: "")
        )
        XCTAssertEqual("", dec?.hostName)
    }

    func testUtf8HostNamesSurviveTheRoundTrip() {
        let dec = BleJoinPayload.decode(
            BleJoinPayload.encode(sessionCode: code, hostName: "张伟 🎧")
        )
        XCTAssertEqual("张伟 🎧", dec?.hostName)
    }

    // ── Encode-side validation ─────────────────────────────────────────────

    func testWrongLengthSessionCodesAreRefused() {
        XCTAssertNil(BleJoinPayload.encode(sessionCode: "ABC", hostName: "Host"))
        XCTAssertNil(BleJoinPayload.encode(sessionCode: "ABCDEFG", hostName: "Host"))
        XCTAssertNil(BleJoinPayload.encode(sessionCode: "", hostName: "Host"))
    }

    func testNonPrintableAsciiSessionCodesAreRefused() {
        XCTAssertNil(BleJoinPayload.encode(sessionCode: "AB3\t9K", hostName: "Host"))
        XCTAssertNil(BleJoinPayload.encode(sessionCode: "AB3\n9K", hostName: "Host"))
    }

    func testOversizeHostNamesAreRefused() {
        let name = String(repeating: "x", count: BleJoinPayload.maxHostNameBytes + 1)
        XCTAssertNil(BleJoinPayload.encode(sessionCode: code, hostName: name))
        // Exactly at the cap is fine.
        XCTAssertNotNil(
            BleJoinPayload.encode(
                sessionCode: code,
                hostName: String(repeating: "y", count: BleJoinPayload.maxHostNameBytes)
            )
        )
    }

    // ── Decode-side hardening (hostile scan records die here) ──────────────

    func testNilAndShortBuffersAreRejected() {
        XCTAssertNil(BleJoinPayload.decode(nil))
        XCTAssertNil(BleJoinPayload.decode(Data(count: 3)))
        XCTAssertNil(BleJoinPayload.decode(Data(count: BleJoinPayload.offsetHostName - 1)))
    }

    func testBadMagicAndUnknownVersionAreRejected() {
        let good = [UInt8](BleJoinPayload.encode(sessionCode: code, hostName: "Host")!)

        var badMagic = good; badMagic[0] = 0x00
        XCTAssertNil(BleJoinPayload.decode(Data(badMagic)))

        var badVersion = good; badVersion[BleJoinPayload.offsetVersion] = 2
        XCTAssertNil(BleJoinPayload.decode(Data(badVersion)))

        // Audit #22 item 3 regression lock: byte[1] must be 'F' too —
        // decode used to skip it, so `0x53 ?? 0x01…` foreign payloads
        // passed the magic gate.
        var badMagic1 = good; badMagic1[1] = 0x00
        XCTAssertNil(BleJoinPayload.decode(Data(badMagic1)))

        // Test vector: 'S','X',version 1 — valid byte 0, invalid byte 1.
        var sneaky = good; sneaky[1] = UInt8(ascii: "X")
        XCTAssertNil(BleJoinPayload.decode(Data(sneaky)))
    }

    func testReservedFlagBitsMustBeZero() {
        let good = [UInt8](BleJoinPayload.encode(sessionCode: code, hostName: "Host")!)
        var hostile = good
        hostile[BleJoinPayload.offsetFlags] = 0x08 // bit 3 reserved
        XCTAssertNil(BleJoinPayload.decode(Data(hostile)))
    }

    func testOverLongBuffersBeyondTheNameCapAreRejected() {
        let good = BleJoinPayload.encode(sessionCode: code, hostName: "Host")!
        let padded = good + Data(count: BleJoinPayload.maxHostNameBytes)
        XCTAssertNil(BleJoinPayload.decode(padded))
    }

    func testCorruptUtf8InTheHostNameNeverTraps() {
        let good = [UInt8](BleJoinPayload.encode(sessionCode: code, hostName: "Host")!)
        var hostile = good
        hostile[hostile.count - 1] = 0x80 // lone continuation byte
        // May decode to a replacement char, but MUST never trap.
        _ = BleJoinPayload.decode(Data(hostile))
    }

    // ── Manufacturer-specific scan records (AD structure walk) ─────────────

    private func buildScanRecord(_ manufacturerBlob: Data) -> Data {
        var ad = Data(capacity: 2 + manufacturerBlob.count + 4)
        ad.append(UInt8(1 + manufacturerBlob.count)) // length covers type + payload
        ad.append(0xFF)
        ad.append(manufacturerBlob)
        // Trailing padding (other AD structures / zero terminator) is normal.
        ad.append(Data(count: 4))
        return ad
    }

    func testPayloadRoundTripsThroughAManufacturerSpecificScanRecord() {
        let blob = BleJoinPayload.encodeManufacturerData(
            sessionCode: code,
            hostName: "Zephyr",
            partyMode: true
        )!
        let record = buildScanRecord(blob)

        let dec = BleJoinPayload.fromScanRecord(record, manufacturerId: BleJoinPayload.manufacturerId)
        XCTAssertEqual(code, dec?.sessionCode)
        XCTAssertEqual("Zephyr", dec?.hostName)
        XCTAssertEqual(true, dec?.partyMode)
    }

    func testScanRecordWithoutOurManufacturerIdYieldsNothing() {
        let blob = BleJoinPayload.encodeManufacturerData(sessionCode: code, hostName: "Host")!
        let record = buildScanRecord(blob)
        XCTAssertNil(BleJoinPayload.fromScanRecord(record, manufacturerId: 0x1234))
    }

    func testEmptyAndForeignScanRecordsYieldNothing() {
        XCTAssertNil(BleJoinPayload.fromScanRecord(nil, manufacturerId: BleJoinPayload.manufacturerId))
        XCTAssertNil(BleJoinPayload.fromScanRecord(Data(), manufacturerId: BleJoinPayload.manufacturerId))

        // A well-formed AD structure carrying a non-Streamify payload.
        let foreign = Data([
            0x05, 0x09,
            UInt8(ascii: "S"), UInt8(ascii: "P"), UInt8(ascii: "T"), UInt8(ascii: "F"),
        ])
        XCTAssertNil(BleJoinPayload.fromScanRecord(foreign, manufacturerId: BleJoinPayload.manufacturerId))
    }

    func testTruncatedADStructuresAreWalkedSafely() {
        let blob = BleJoinPayload.encodeManufacturerData(sessionCode: code, hostName: "Host")!
        let record = buildScanRecord(blob)
        for cut in 1..<record.count {
            // Never traps, never returns a half-payload.
            _ = BleJoinPayload.fromScanRecord(
                record.prefix(cut),
                manufacturerId: BleJoinPayload.manufacturerId
            )
        }
        XCTAssertNil(
            BleJoinPayload.fromScanRecord(record.prefix(2), manufacturerId: BleJoinPayload.manufacturerId)
        )
    }

    func testManufacturerBlobIsLittleEndianCompanyIdFollowedByPayload() {
        let blob = [UInt8](
            BleJoinPayload.encodeManufacturerData(sessionCode: code, hostName: "Host")!
        )
        // Little-endian 0x0F1E = bytes [0x1E, 0x0F].
        XCTAssertEqual([0x1E, 0x0F], Array(blob[0..<2]))
        XCTAssertEqual(BleJoinPayload.manufacturerId, Int(blob[0]) | (Int(blob[1]) << 8))

        let payload = Data(blob[2...])
        XCTAssertEqual(BleJoinPayload.encode(sessionCode: code, hostName: "Host"), payload)
    }

    func testCoreBluetoothManufacturerBlobDecodesDirectly() {
        // The CBAdvertisementDataManufacturerDataKey path: companyId LE + payload.
        let blob = BleJoinPayload.encodeManufacturerData(
            sessionCode: code,
            hostName: "Zephyr",
            partyMode: true,
            hasSpace: true,
            queueNonEmpty: false
        )
        let dec = BleJoinPayload.fromManufacturerData(blob)
        XCTAssertEqual(code, dec?.sessionCode)
        XCTAssertEqual("Zephyr", dec?.hostName)
        XCTAssertEqual(true, dec?.partyMode)

        // A foreign company id must not decode.
        var foreign = [UInt8](blob!)
        foreign[0] = 0x34
        foreign[1] = 0x12
        XCTAssertNil(BleJoinPayload.fromManufacturerData(Data(foreign)))
    }

    // ── Proximity contract ─────────────────────────────────────────────────

    func testProximityRssiThresholdIsInTheOneMetrePhoneClassBand() {
        XCTAssertTrue((-70...(-45)).contains(BleJoinPayload.proximityRssiDbm))
    }

    func testPromptSubtitleReflectsLiveRoomState() {
        let full = BleJoinPayload.decode(
            BleJoinPayload.encode(sessionCode: code, hostName: "Z", hasSpace: false)
        )!
        XCTAssertTrue(full.promptSubtitle.lowercased().contains("full"))

        let party = BleJoinPayload.decode(
            BleJoinPayload.encode(sessionCode: code, hostName: "Z", partyMode: true)
        )!
        XCTAssertTrue(party.promptSubtitle.lowercased().contains("speaker"))

        let plain = BleJoinPayload.decode(
            BleJoinPayload.encode(sessionCode: code, hostName: "Z")
        )!
        XCTAssertFalse(plain.promptSubtitle.isEmpty)
    }
}
