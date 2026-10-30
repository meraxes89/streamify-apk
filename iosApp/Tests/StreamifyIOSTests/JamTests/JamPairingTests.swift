import XCTest
@testable import StreamifyIOS

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-06 (Engineer 4): offline room pairing codec — the Swift mirror of
// `app/src/test/java/com/streamify/app/jam/JamPairingTest.kt`.
// ─────────────────────────────────────────────────────────────────────────────

final class JamPairingTests: XCTestCase {

    private func session(
        code: String = "AB3X9K",
        id: String = "room-1",
        keyHex: String = String(repeating: "ab", count: 32)
    ) -> JamPairing.RoomIdentity {
        JamPairing.RoomIdentity(sessionCode: code, sessionId: id, pairingKeyHex: keyHex)
    }

    // ── Full payload ───────────────────────────────────────────────────────

    func testFullPayloadRoundTrips() {
        let uri = JamPairing.encodePayload(session())
        XCTAssertEqual("streamify://jam/AB3X9K?v=4&s=room-1&k=\(String(repeating: "ab", count: 32))", uri)

        let parsed = JamPairing.parsePayload(uri)
        XCTAssertNotNil(parsed)
        XCTAssertEqual("AB3X9K", parsed?.sessionCode)
        XCTAssertEqual("room-1", parsed?.sessionId)
        XCTAssertEqual([UInt8](repeating: 0xAB, count: 32), parsed?.pairingKey)
    }

    func testBarePinParsesWithDerivedSessionId() {
        let parsed = JamPairing.parsePayload("ab3x9k")
        XCTAssertEqual("AB3X9K", parsed?.sessionCode)
        XCTAssertEqual("pin-AB3X9K", parsed?.sessionId)
        // PIN-only joins carry the zero key (the pairing key never leaves QR/NFC).
        XCTAssertEqual([UInt8](repeating: 0, count: 32), parsed?.pairingKey)
    }

    func testDeepLinkFormStripsToPinWhenQueryIsAbsent() {
        let parsed = JamPairing.parsePayload("streamify://jam/AB3X9K")
        XCTAssertEqual("AB3X9K", parsed?.sessionCode)
        XCTAssertEqual("pin-AB3X9K", parsed?.sessionId)
    }

    func testPinWithKeyValueFragmentsParses() {
        let parsed = JamPairing.parsePayload(
            "ab3x9k s=room-77 k=\(String(repeating: "0f", count: 32))"
        )
        XCTAssertEqual("AB3X9K", parsed?.sessionCode)
        XCTAssertEqual("room-77", parsed?.sessionId)
        XCTAssertEqual([UInt8](repeating: 0x0F, count: 32), parsed?.pairingKey)
    }

    func testGarbageAndAmbiguousGlyphPinsRejected() {
        XCTAssertNil(JamPairing.parsePayload(""))
        XCTAssertNil(JamPairing.parsePayload("   "))
        XCTAssertNil(JamPairing.parsePayload("hello world"))
        // Ambiguous glyphs (I, L, O, 0, 1) are NOT in the alphabet.
        XCTAssertNil(JamPairing.parsePayload("AB3X9I"))
        XCTAssertNil(JamPairing.parsePayload("AB3X90"))
        XCTAssertNil(JamPairing.parsePayload("streamify://jam/AB3X9O"))
    }

    // ── Minting ────────────────────────────────────────────────────────────

    func testSessionCodesAvoidAmbiguousGlyphs() {
        let alphabet = Set("ABCDEFGHJKMNPQRSTUVWXYZ23456789".map(String.init))
        for _ in 0..<50 {
            let code = JamPairing.generateSessionCode()
            XCTAssertEqual(JamPairing.pinLength, code.count)
            for ch in code.map(String.init) {
                XCTAssertTrue(alphabet.contains(ch), "generated \(code) contains \(ch)")
            }
        }
    }

    func testPairingKeysAre32RandomBytes() {
        let a = JamPairing.generatePairingKey()
        let b = JamPairing.generatePairingKey()
        XCTAssertEqual(JamPairing.keyBytes, a.count)
        XCTAssertEqual(JamPairing.keyBytes, b.count)
        XCTAssertNotEqual(a, b, "two minted keys must differ")
    }

    // ── QR content ─────────────────────────────────────────────────────────

    func testQrEncodesAndDecodesThePairingPayload() {
        // The QR content is the URI verbatim: parse(decode(encode)) == identity.
        let identity = session()
        let qrContent = JamPairing.encodePayload(identity)
        let parsed = JamPairing.parsePayload(qrContent)
        XCTAssertEqual(identity.sessionCode, parsed?.sessionCode)
        XCTAssertEqual(identity.sessionId, parsed?.sessionId)
        XCTAssertEqual(String(repeating: "ab", count: 32).count, parsed?.pairingKey.count)
    }

    func testFullUriPinIsCaseInsensitiveLikeBarePin() {
        // The scheme/host match is case-sensitive (Kotlin parity) but the
        // PIN uppercases like every accepted form.
        let parsed = JamPairing.parsePayload("streamify://jam/ab3x9k?s=x&k=\(String(repeating: "cd", count: 32))")
        XCTAssertEqual("AB3X9K", parsed?.sessionCode)
        XCTAssertEqual("x", parsed?.sessionId)
    }
}
