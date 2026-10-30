import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-05 (Engineer 4): LAN UDP BEACON 0x09 codec — the byte-exact Swift
// mirror of `app/src/main/java/com/streamify/app/jam/LanBeaconPacket.kt`,
// which transliterates the FROZEN Rust mesh ABI (rust/src/p2p_mesh.rs):
//
//   offset  field        size  type      value
//   0       magic        2     u16 LE    0x5354 ('ST')
//   2       version      1     u8        0x03
//   3       msg_type     1     u8        0x09 = BEACON
//   4       sender_id    8     u64 LE    Blake3 node id
//   12      session_id   16    raw LE    room hash
//   28      sequence     4     u32 LE    0 (control)
//   32      timestamp    8     i64 LE    mono ns
//   40      payload_len  2     u16 LE    12
//   42      checksum     4     u32 LE    FNV-1a/32 over [0..42)
//   46      payload      12    [peerId u64 LE][caps u16 LE][meshPort u16 LE]
//
// Capability bits: CAP_LAN = 0x0001, CAP_WEBRTC = 0x0002.
//
// The parser is TOTAL (never traps): wrong magic, wrong version, non-beacon
// types, checksum mismatches and truncation all return nil and die at this
// boundary.
// ─────────────────────────────────────────────────────────────────────────────

enum LanBeaconPacket {

    // ── Frozen constants (mirror rust/src/p2p_mesh.rs) ────────────────────

    static let magic: UInt16 = 0x5354
    static let protoVersion: UInt8 = 0x03
    static let msgBeacon: UInt8 = 0x09
    static let headerLen = 46
    static let checksumCoveredLen = 42
    static let beaconPayloadLen = 12
    static let defaultMeshPort = 7777

    static let capLan: UInt16 = 0x0001
    static let capWebRtc: UInt16 = 0x0002

    // ── Decoded beacon ────────────────────────────────────────────────────

    struct Decoded: Equatable, Sendable {
        /// 16-hex rendering of the 64-bit sender node id (LE byte order,
        /// exactly like the Kotlin `hex16`).
        let senderIdHex: String
        /// 32-hex rendering of the 128-bit room identity — the key the join
        /// fabric uses to distinguish distinct active rooms.
        let sessionIdHex: String
        let caps: UInt16
        let meshPort: Int

        var hasLan: Bool { caps & LanBeaconPacket.capLan != 0 }
        var hasWebRtc: Bool { caps & LanBeaconPacket.capWebRtc != 0 }
    }

    // ── Checksum ──────────────────────────────────────────────────────────

    /// FNV-1a/32 over the pre-checksum header span [0..42) — identical to
    /// the Rust `fnv1a32` and the Kotlin mirror.
    static func fnv1a32(_ bytes: Data, length: Int = checksumCoveredLen) -> UInt32 {
        var hash: UInt32 = 0x811C9DC5
        let upper = min(length, bytes.count)
        for i in 0..<upper {
            hash ^= UInt32(bytes[bytes.startIndex + i])
            hash = hash &* 0x01000193
        }
        return hash
    }

    // ── Little-endian primitive readers ───────────────────────────────────

    private static func readU16(_ b: [UInt8], _ i: Int) -> UInt16 {
        UInt16(b[i]) | (UInt16(b[i + 1]) << 8)
    }

    private static func readU32(_ b: [UInt8], _ i: Int) -> UInt32 {
        UInt32(b[i]) | (UInt32(b[i + 1]) << 8) | (UInt32(b[i + 2]) << 16) | (UInt32(b[i + 3]) << 24)
    }

    private static func readU64(_ b: [UInt8], _ i: Int) -> UInt64 {
        var v: UInt64 = 0
        for k in (0..<8).reversed() {
            v = (v << 8) | UInt64(b[i + k])
        }
        return v
    }

    // ── Decode (total) ────────────────────────────────────────────────────

    /// Total parser for one raw datagram. Returns nil for anything that is
    /// not a well-formed, checksum-valid Streamify BEACON.
    static func decode(_ datagram: Data?) -> Decoded? {
        guard let datagram, datagram.count >= headerLen + beaconPayloadLen else { return nil }
        let b = [UInt8](datagram)

        guard readU16(b, 0) == magic else { return nil }
        guard b[2] == protoVersion else { return nil }
        guard b[3] == msgBeacon else { return nil }

        // Header sender id (bytes 4..<12) is integrity-covered by the FNV
        // checksum below; the surfaced identity is the payload's peerId.
        let sessionIdBytes = Array(b[12..<28])
        let payloadLen = Int(readU16(b, 40))
        let checksumStored = readU32(b, 42)

        guard payloadLen == beaconPayloadLen else { return nil }

        // Integrity: FNV-1a/32 over the pre-checksum header span.
        guard fnv1a32(datagram) == checksumStored else { return nil }

        // Beacon payload: peerId u64 + caps u16 + port u16.
        let peerId = readU64(b, 46)
        let caps = readU16(b, 54)
        let port = Int(readU16(b, 56))

        // Sanity: mesh ports live in the dynamic/private-ish range; hostile
        // zero/privileged ports die here.
        guard (1...65_535).contains(port) else { return nil }

        return Decoded(
            senderIdHex: Self.hex16(peerId),
            sessionIdHex: Self.hex(sessionIdBytes),
            caps: caps,
            meshPort: port == 0 ? defaultMeshPort : port
        )
    }

    // ── Encode (tests + app-layer announcement parity) ────────────────────

    /// Builds a Rust-shaped beacon (for tests and the app-layer room
    /// announcement parity checks).
    static func encodeBeacon(
        senderId: UInt64,
        sessionId: Data,
        caps: UInt16,
        port: UInt16,
        timestampMonoNs: UInt64 = UInt64(PresentationFormatting.monotonicNs())
    ) -> Data? {
        guard sessionId.count == 16 else { return nil }

        func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF)] }
        func le32(_ v: UInt32) -> [UInt8] {
            [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8((v >> 24) & 0xFF)]
        }
        func le64(_ v: UInt64) -> [UInt8] { (0..<8).map { UInt8((v >> UInt64($0 * 8)) & 0xFF) } }

        var raw = [UInt8]()
        raw.reserveCapacity(headerLen + beaconPayloadLen)
        raw.append(contentsOf: le16(magic))
        raw.append(protoVersion)
        raw.append(msgBeacon)
        raw.append(contentsOf: le64(senderId))
        raw.append(contentsOf: [UInt8](sessionId))
        raw.append(contentsOf: le32(0))                 // sequence — control frame
        raw.append(contentsOf: le64(timestampMonoNs))   // timestamp_mono_ns
        raw.append(contentsOf: le16(UInt16(beaconPayloadLen)))
        raw.append(contentsOf: le32(0))                 // checksum placeholder
        raw.append(contentsOf: le64(senderId))          // peerId
        raw.append(contentsOf: le16(caps))
        raw.append(contentsOf: le16(port))

        let data = Data(raw)
        let checksum = fnv1a32(data)
        var out = data
        withUnsafeBytes(of: checksum.littleEndian) { out.replaceSubrange(42..<46, with: $0) }
        return out
    }

    // ── Helpers (Kotlin parity) ───────────────────────────────────────────

    private static func hex16(_ v: UInt64) -> String {
        var b = [UInt8](repeating: 0, count: 8)
        for i in 0..<8 {
            b[i] = UInt8((v >> UInt64(i * 8)) & 0xFF)
        }
        return hex(b)
    }

    private static func hex(_ bytes: [UInt8]) -> String {
        let digits = Array("0123456789abcdef".utf8)
        var sb = ""
        sb.reserveCapacity(bytes.count * 2)
        for byte in bytes {
            sb.append(Character(UnicodeScalar(digits[Int(byte >> 4)])))
            sb.append(Character(UnicodeScalar(digits[Int(byte & 0x0F)])))
        }
        return sb
    }
}
