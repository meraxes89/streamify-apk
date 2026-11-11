import XCTest

// ─────────────────────────────────────────────────────────────────────────────
// WS-D D1: the AcousticEmbedding v1 layout contract — the Swift side of the
// three-language pin (Rust: streamify-core acoustic_embedding tests; Kotlin:
// AcousticEmbeddingContractTest). The 516-byte golden fixture below is the
// byte-exact reference image produced by the Rust contract
// (fixtures/acoustic_embedding/v1_reference.bin); this suite recomputes the
// documented lanes from the schema constants and compares layout-for-layout.
// A lane renumbering fails all three languages at once — that is the point.
// ─────────────────────────────────────────────────────────────────────────────

final class AcousticEmbeddingContractTests: XCTestCase {

    // The v1 schema (docs/architecture/acoustic_embedding_contract_v1.md):
    // [version: 4B u32 LE][dims: 128 × 4B f32 LE] = 516 bytes exactly.
    private enum Layout {
        static let dim = 128
        static let wireBytes = 4 + dim * 4
        // Named lane indices.
        static let bpm = 0
        static let camelotNumber = 1
        static let camelotLetter = 2
        static let integratedLufs = 3
        static let crestFactorDb = 4
        static let spectralBandFirst = 5
        static let spectralCentroidKhz = 15
        static let spectralFlatness = 16
        static let zeroCrossingRate = 17
        static let rmsMean = 18
        static let dynamicRange = 19
        static let onsetRate = 20
        static let beatStrength = 21
        static let bpmConfidence = 22
        static let danceability = 23
        static let energy = 24
        static let valence = 25
        static let acousticness = 26
        static let waveformFirst = 27
        static let reservedFirst = 91
        static let layoutVersion = 127
        static let spectralBandCount = 10
        static let waveformBucketCount = 64
    }

    // The golden fixture — hex of the full 516-byte reference image
    // (the multi-line literal's closing delimiter indentation is
    // stripped from the content, so the hex line carries the same
    // leading indentation).
    private static let fixtureHex = """
        010000000000f8420000004100000000333363c100001841cdcccc3dcdcc4c3e9a99993ecdcccc3e0000003f9a99193f3333333fcdcc4c3f6766663f0000803f9a991940ec51383eec51b83dae47613eae47e13e00006040ec51383f295c4f3fd7a3303fe17a143f52b81e3fd7a3f03e00000000e1e0e03ce1e0603da9a8a83de1e0e03d8d8c0c3ea9a8283ec5c4443ee1e0603efdfc7c3e8d8c8c3e9b9a9a3ea9a8a83eb7b6b63ec5c4c43ed3d2d23ee1e0e03eefeeee3efdfcfc3e8685053f8d8c0c3f9493133f9b9a1a3fa2a1213fa9a8283fb0af2f3fb7b6363fbebd3d3fc5c4443fcccb4b3fd3d2523fdad9593fe1e0603fe8e7673fefee6e3ff6f5753f8180803b8180003df1f0703db1b0b03de9e8e83d9190103eadac2c3ec9c8483ee5e4643e8180803e8f8e8e3e9d9c9c3eabaaaa3eb9b8b83ec7c6c63ed5d4d43ee3e2e23ef1f0f03efffefe3e8786063f8e8d0d3f9594143f9c9b1b3fa3a2223faaa9293fb1b0303fb8b7373fbfbe3e3f0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000803f
        """

    private static func decodeHex(_ hex: String) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(hex.count / 2)
        var iter = hex.makeIterator()
        while let hi = iter.next(), let lo = iter.next() {
            let byte = UInt8(String([hi, lo]), radix: 16)!
            out.append(byte)
        }
        return out
    }

    private static func laneF32(_ bytes: [UInt8], _ lane: Int) -> Float {
        let base = 4 + lane * 4
        var bits: UInt32 = 0
        for i in 0..<4 {
            bits |= UInt32(bytes[base + i]) << (8 * UInt32(i))
        }
        return Float(bitPattern: bits)
    }

    func testWireGeometryIsExactlyFiveHundredSixteenBytes() {
        let bytes = Self.decodeHex(Self.fixtureHex)
        XCTAssertEqual(bytes.count, Layout.wireBytes, "the v1 wire image is 516 bytes")
        XCTAssertEqual(Layout.wireBytes, 516)
    }

    func testVersionHeaderIsV1LittleEndian() {
        let bytes = Self.decodeHex(Self.fixtureHex)
        XCTAssertEqual(bytes[0], 1, "version low byte")
        XCTAssertEqual(bytes[1], 0)
        XCTAssertEqual(bytes[2], 0)
        XCTAssertEqual(bytes[3], 0)
    }

    func testDocumentedLanesParseLayoutForLayout() {
        let bytes = Self.decodeHex(Self.fixtureHex)
        // The reference values the Rust suite pins (reference_vector()).
        XCTAssertEqual(laneF32(bytes, Layout.bpm), 124.0, "lane 0 = BPM")
        XCTAssertEqual(laneF32(bytes, Layout.camelotNumber), 8.0, "lane 1 = Camelot number")
        XCTAssertEqual(laneF32(bytes, Layout.camelotLetter), 0.0, "lane 2 = Camelot letter (0 = A/major)")
        XCTAssertEqual(laneF32(bytes, Layout.integratedLufs), -14.2, accuracy: 0.0001, "lane 3 = LUFS")
        XCTAssertEqual(laneF32(bytes, Layout.crestFactorDb), 9.5, "lane 4 = crest dB")
        XCTAssertEqual(laneF32(bytes, Layout.spectralCentroidKhz), 2.4, accuracy: 0.0001, "lane 15")
        XCTAssertEqual(laneF32(bytes, Layout.danceability), 0.69, accuracy: 0.0001, "lane 23")
        XCTAssertEqual(laneF32(bytes, Layout.energy), 0.58, accuracy: 0.0001, "lane 24")
        XCTAssertEqual(laneF32(bytes, Layout.valence), 0.62, accuracy: 0.0001, "lane 25")
        XCTAssertEqual(laneF32(bytes, Layout.acousticness), 0.47, accuracy: 0.0001, "lane 26")
        // Lane 127 = the layout-version tripwire.
        XCTAssertEqual(laneF32(bytes, Layout.layoutVersion), 1.0, "the version tripwire")
    }

    func testSpectralAndWaveformBlocksCoverTheirRanges() {
        let bytes = Self.decodeHex(Self.fixtureHex)
        // The 10-band block occupies [5, 15).
        for i in 0..<Layout.spectralBandCount {
            let v = laneF32(bytes, Layout.spectralBandFirst + i)
            XCTAssertEqual(v, 0.1 * Float(i + 1), accuracy: 0.0001, "band lane \(i)")
        }
        // The waveform block occupies [27, 91) with the deterministic
        // ((i*7) % 251)/255 ramp.
        for i in 0..<Layout.waveformBucketCount {
            let v = laneF32(bytes, Layout.waveformFirst + i)
            let expect = Float((i * 7) % 251) / 255.0
            XCTAssertEqual(v, expect, accuracy: 0.0001, "waveform lane \(i)")
        }
        // Reserved lanes [91, 127) are zero in v1.
        for lane in Layout.reservedFirst..<Layout.layoutVersion {
            XCTAssertEqual(laneF32(bytes, lane), 0.0, "reserved lane \(lane) must be zero")
        }
    }

    func testSchemaCoversAllOneHundredTwentyEightLanes() {
        // The documented lane set is dense over [0, 128): every index is
        // either a named scalar, part of the band/waveform/reserved blocks
        // or the version tripwire. The block arithmetic below must cover
        // exactly 128 lanes.
        let covered = 5   // bpm, camelotNumber, camelotLetter, lufs, crest
            + Layout.spectralBandCount
            + 12          // centroid..acousticness scalars
            + Layout.waveformBucketCount
            + (Layout.layoutVersion - Layout.reservedFirst) // reserved + tripwire
        XCTAssertEqual(covered, Layout.dim, "the documented schema covers all 128 lanes")
    }
}
