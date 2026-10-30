import Foundation

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-04 (Engineer 4): BLE tap-to-join packet codec — the byte-exact
// Swift mirror of `app/src/main/java/com/streamify/app/jam/BleJoinPayload.kt`
// (BEHIND.md Gap #12, Phase 1). The Kotlin tests pin the wire format; the
// Swift suite in JamTests/BleJoinPayloadTests.swift pins the SAME vectors so
// an iPhone host and an Android guest agree on every byte.
//
// WIRE FORMAT (manufacturer-specific data, byte-oriented):
//
//   ┌──────┬─────────┬─────────┬───────┬─────────────┬───────────┐
//   │ 0x53 │ 0x46    │ version │ flags │ sessionCode │ hostName… │
//   │ 'S'  │ 'F'     │ u8      │ u8    │ 6 bytes     │ UTF-8     │
//   └──────┴─────────┴─────────┴───────┴─────────────┴───────────┘
//
//   magic        0x53 0x46 ("SF") — every parser rejects foreign payloads
//                before touching a single field.
//   version      protocol version (currently 1); parsers accept only their
//                own version.
//   flags        bit 0 TOPOLOGY_SINGLE_RENDER (party mode hint)
//                bit 1 ROOM_HAS_SPACE (host-side 32-cap admission result)
//                bit 2 QUEUE_NON_EMPTY (the host already put a song on)
//                bits 3-7 reserved, must be zero on encode.
//   sessionCode  the 6-char room PIN, ASCII — NOT the pairing key (the
//                pairing key never leaves QR/NFC).
//   hostName     host display name, UTF-8, length-prefixed by the remaining
//                bytes (capped at MAX_HOST_NAME_BYTES).
//
// The codec is total (never traps): every malformed input — wrong magic,
// truncated buffer, hostile length, non-ASCII room code — parses to nil and
// dies at this boundary, never reaching the join flow.
// ─────────────────────────────────────────────────────────────────────────────

enum BleJoinPayload {

    // ── Wire constants (mirror of BleJoinPayload.kt) ──────────────────────

    static let magic0: UInt8 = 0x53 // 'S'
    static let magic1: UInt8 = 0x46 // 'F'
    static let version: UInt8 = 1

    static let offsetMagic = 0
    static let offsetVersion = 2
    static let offsetFlags = 3
    static let offsetCode = 4
    static let codeBytes = 6
    static let offsetHostName = offsetCode + codeBytes

    static let maxHostNameBytes = 48
    static let maxPayloadBytes = offsetHostName + maxHostNameBytes

    // ── Flags ──────────────────────────────────────────────────────────────

    static let flagTopologySingleRender: UInt8 = 0x01
    static let flagRoomHasSpace: UInt8 = 0x02
    static let flagQueueNonEmpty: UInt8 = 0x04
    static let flagsKnownMask: UInt8 = 0x07

    // ── Proximity model ────────────────────────────────────────────────────

    /// RSSI threshold ≈ 1 m free-space distance for phone-class BLE radios
    /// (empirical median ~ -55..-65 dBm at arm's length; -60 keeps the
    /// "tapped together" intent while tolerating pocket fabric).
    static let proximityRssiDbm: Int = -60

    /// Streamify's BLE manufacturer identifier (0xFFFF-range experimental
    /// IDs are not registered; 0x0F1E is far from assigned Bluetooth SIG
    /// space and stable across releases).
    static let manufacturerId: Int = 0x0F1E

    // ── Decoded payload ────────────────────────────────────────────────────

    struct Decoded: Equatable, Sendable {
        let sessionCode: String
        let hostName: String
        let partyMode: Bool
        let hasSpace: Bool
        let queueNonEmpty: Bool

        /// Human subtitle for the one-tap join prompt (Kotlin parity).
        var promptSubtitle: String {
            if !hasSpace {
                return "Room is full (32/32)"
            }
            if partyMode {
                let host = hostName.isEmpty ? "the host" : hostName
                return "Party mode — listening on \(host)'s speaker"
            }
            if queueNonEmpty {
                let host = hostName.isEmpty ? "Host" : hostName
                return "Listening together · \(host)'s room"
            }
            let host = hostName.isEmpty ? "the host" : hostName
            return "Start listening together with \(host)"
        }
    }

    // ── Encode (host side) ────────────────────────────────────────────────

    /// Serializes the room identity into an advertisement-ready payload.
    /// Returns nil when the inputs are unrepresentable (bad code length,
    /// oversize host name) — the advertiser then skips this advert round
    /// rather than emitting junk.
    static func encode(
        sessionCode: String,
        hostName: String,
        partyMode: Bool = false,
        hasSpace: Bool = true,
        queueNonEmpty: Bool = false
    ) -> Data? {
        let code = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard code.count == codeBytes else { return nil }
        guard code.unicodeScalars.allSatisfy({ (32...126).contains($0.value) }) else { return nil }
        let codeAscii = Array(code.utf8)
        guard codeAscii.count == codeBytes else { return nil }

        let nameBytes = Array(hostName.utf8)
        guard nameBytes.count <= maxHostNameBytes else { return nil }

        var flags: UInt8 = 0
        if partyMode { flags |= flagTopologySingleRender }
        if hasSpace { flags |= flagRoomHasSpace }
        if queueNonEmpty { flags |= flagQueueNonEmpty }

        var out = Data(capacity: offsetHostName + nameBytes.count)
        out.append(magic0)
        out.append(magic1)
        out.append(version)
        out.append(flags)
        out.append(contentsOf: codeAscii)
        out.append(contentsOf: nameBytes)
        return out
    }

    // ── Decode (guest side) ────────────────────────────────────────────────

    /// Total parser: any malformed input returns nil. Over-long buffers are
    /// rejected — the name length is not wire-encoded, so extra bytes beyond
    /// the host name would make parsing ambiguous.
    static func decode(_ bytes: Data?) -> Decoded? {
        guard let bytes, bytes.count >= offsetHostName else { return nil }
        // BOTH magic bytes + the version gate (Kotlin audit #22 item 3:
        // decode used to skip byte[1], letting `0x53 ?? 0x01…` foreign
        // payloads through the magic gate).
        guard bytes[offsetMagic] == magic0,
              bytes[offsetMagic + 1] == magic1,
              bytes[offsetVersion] == version
        else { return nil }

        let flags = bytes[offsetFlags]
        guard flags & ~flagsKnownMask == 0 else { return nil } // reserved bits must be zero

        let codeAscii = bytes.subdata(in: offsetCode..<(offsetCode + codeBytes))
        guard codeAscii.count == codeBytes,
              let code = String(data: codeAscii, encoding: .ascii),
              code.unicodeScalars.allSatisfy({ (32...126).contains($0.value) })
        else { return nil }

        let nameLen = bytes.count - offsetHostName
        guard (0...maxHostNameBytes).contains(nameLen) else { return nil }
        let name = nameLen == 0 ? "" : String(data: bytes.suffix(nameLen), encoding: .utf8) ?? ""

        return Decoded(
            sessionCode: code,
            hostName: name,
            partyMode: flags & flagTopologySingleRender != 0,
            hasSpace: flags & flagRoomHasSpace != 0,
            queueNonEmpty: flags & flagQueueNonEmpty != 0
        )
    }

    /// Scans a raw BLE scan record for a Streamify manufacturer-specific
    /// payload with the given manufacturer id and decodes it. AD structures:
    /// `[length][type=0xFF][companyId u16 LE][payload]`.
    ///
    /// Returns nil when the record carries no Streamify advert. Never traps
    /// — hostile scan records die here.
    static func fromScanRecord(_ scanRecord: Data?, manufacturerId target: Int) -> Decoded? {
        guard let scanRecord else { return nil }
        let bytes = [UInt8](scanRecord)
        var i = 0
        while i + 1 < bytes.count {
            let len = Int(bytes[i])
            if len == 0 { break }
            guard i + 1 + len <= bytes.count else { break } // truncated structure
            let type = bytes[i + 1]
            if type == 0xFF && len >= 3 {
                // Company id: little-endian u16.
                let companyId = (Int(bytes[i + 3]) << 8) | Int(bytes[i + 2])
                if companyId == target {
                    let payloadLen = len - 3
                    guard payloadLen >= offsetHostName else { return nil }
                    let payload = Data(bytes[(i + 4)..<(i + 4 + payloadLen)])
                    return decode(payload)
                }
            }
            i += 1 + len
        }
        return nil
    }

    /// Decodes a CoreBluetooth manufacturer-specific blob directly
    /// (`CBAdvertisementDataManufacturerDataKey`: companyId LE + payload).
    static func fromManufacturerData(_ data: Data?) -> Decoded? {
        guard let data, data.count >= 2 + offsetHostName else { return nil }
        let companyId = Int(data[data.startIndex]) | (Int(data[data.startIndex + 1]) << 8)
        guard companyId == manufacturerId else { return nil }
        return decode(data.subdata(in: (data.startIndex + 2)..<data.endIndex))
    }

    /// Builds the complete manufacturer-specific blob (companyId LE +
    /// payload) for `CBAdvertisementDataManufacturerDataKey` — the Swift
    /// counterpart of the Kotlin `encodeManufacturerData`.
    static func encodeManufacturerData(
        sessionCode: String,
        hostName: String,
        partyMode: Bool = false,
        hasSpace: Bool = true,
        queueNonEmpty: Bool = false,
        manufacturerId id: Int = BleJoinPayload.manufacturerId
    ) -> Data? {
        guard let payload = encode(
            sessionCode: sessionCode,
            hostName: hostName,
            partyMode: partyMode,
            hasSpace: hasSpace,
            queueNonEmpty: queueNonEmpty
        ) else { return nil }
        var out = Data(capacity: 2 + payload.count)
        out.append(UInt8(id & 0xFF))        // little-endian company id
        out.append(UInt8((id >> 8) & 0xFF))
        out.append(payload)
        return out
    }
}
