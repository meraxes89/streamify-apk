import Foundation
import Security

// ─────────────────────────────────────────────────────────────────────────────
// SW-ENG4-06 (Engineer 4): offline cryptographic room pairing codec — the
// Swift mirror of `app/src/main/java/com/streamify/app/jam/JamPairing.kt`.
//
// The room's ephemeral pairing key never touches a server. The host mints a
// 32-byte key; the full join intent is carried offline as a URI:
//
//   streamify://jam/<6-char PIN>?v=4&s=<sessionId>&k=<64 hex pairing key>
//
// The URI deliberately reuses the cross-platform `streamify://jam/` deep-link
// shape so a QR scanned by the SYSTEM camera routes through the existing
// deep link into the Jam screen even before the richer key exchange is
// understood. `k` is the ephemeral pairing key: the Rust mesh derives the
// room's transport encryption keys from it (HKDF over session id + key), so
// a QR shared on paper expires when the room dies. QR content = the URI
// verbatim (see JamSessionView's CoreImage QR renderer).
// ─────────────────────────────────────────────────────────────────────────────

enum JamPairing {

    static let scheme = "streamify"
    static let host = "jam"
    static let version = 4
    static let pinLength = 6
    static let keyBytes = 32

    /// PIN alphabet (ambiguous glyphs excluded: I L O 0 1 — Kotlin parity).
    static let codeAlphabet = Array("ABCDEFGHJKMNPQRSTUVWXYZ23456789".utf8)

    struct PairingPayload: Equatable, Sendable {
        let sessionCode: String
        let sessionId: String
        let pairingKey: [UInt8]
    }

    /// One room identity for QR sharing (the Swift projection of the
    /// Kotlin `JamEngine.JamSession` for pairing purposes only — the room
    /// state machine stays sovereign in Rust).
    struct RoomIdentity: Equatable, Sendable {
        let sessionCode: String
        let sessionId: String
        let pairingKeyHex: String
    }

    // MARK: - Minting (SecRandom)

    /// Mints a fresh 6-char PIN (ambiguous glyphs excluded).
    static func generateSessionCode() -> String {
        var code = ""
        code.reserveCapacity(pinLength)
        for _ in 0..<pinLength {
            code.append(Character(UnicodeScalar(codeAlphabet[secureRandom(below: codeAlphabet.count)])))
        }
        return code
    }

    /// Mints a fresh ephemeral 32-byte pairing key.
    static func generatePairingKey() -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: keyBytes)
        var index = 0
        while index < keyBytes {
            var chunk = [UInt8](repeating: 0, count: 64)
            let status = SecRandomCopyBytes(kSecRandomDefault, chunk.count, &chunk)
            if status != errSecSuccess {
                chunk = Array(UUID().uuidString.utf8) // defensive fallback
            }
            for byte in chunk where index < keyBytes {
                bytes[index] = byte
                index += 1
            }
        }
        return bytes
    }

    private static func secureRandom(below upper: Int) -> Int {
        var chunk = [UInt8](repeating: 0, count: 8)
        let status = SecRandomCopyBytes(kSecRandomDefault, chunk.count, &chunk)
        if status == errSecSuccess {
            var value = 0 as UInt64
            for byte in chunk.reversed() { value = (value << 8) | UInt64(byte) }
            return Int(value % UInt64(upper))
        }
        return Int.random(in: 0..<upper)
    }

    // MARK: - Serialization

    /// Serializes a join intent into the offline QR / NFC payload.
    static func encodePayload(_ session: RoomIdentity) -> String {
        "streamify://jam/\(session.sessionCode)"
            + "?v=\(version)&s=\(session.sessionId)&k=\(session.pairingKeyHex)"
    }

    /// Parses a pairing payload from any of the accepted forms: full URI,
    /// bare 6-char PIN, or "PIN s=… k=…" fragment pairs. Returns nil when
    /// nothing usable is present (Kotlin parity, including the empty-key
    /// PIN-only deep-link fallback).
    static func parsePayload(_ raw: String?) -> PairingPayload? {
        guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)

        // Full URI form.
        if text.hasPrefix("\(scheme)://") {
            let body = String(text.dropFirst("\(scheme)://".count))
            let path = body.split(separator: "?", maxSplits: 1).map(String.init)[0]
            let query = body.contains("?")
                ? String(body.split(separator: "?", maxSplits: 1)[1])
                : ""
            let pathAfterHost = path.hasPrefix("\(host)/")
                ? String(path.dropFirst("\(host)/".count))
                : path
            let pin = pathAfterHost.split(separator: "/").map(String.init).first?
                .uppercased() ?? ""
            guard isPin(pin) else { return nil }
            let params = parseQuery(query)
            if let sessionId = params["s"], let keyHex = params["k"],
               !sessionId.isEmpty, !keyHex.isEmpty {
                let key = hexToBytes(keyHex)
                if key.count == keyBytes {
                    return PairingPayload(sessionCode: pin, sessionId: sessionId, pairingKey: key)
                }
            }
            // PIN-only deep link (system scanner stripped nothing else).
            return PairingPayload(sessionCode: pin, sessionId: "pin-\(pin)", pairingKey: [UInt8](repeating: 0, count: keyBytes))
        }

        // Bare PIN form.
        let upper = text.uppercased()
        if isPin(upper) {
            return PairingPayload(sessionCode: upper, sessionId: "pin-\(upper)", pairingKey: [UInt8](repeating: 0, count: keyBytes))
        }

        // Key/value fragment form ("PIN s=... k=...").
        let parts = text.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard let first = parts.first, isPin(first.uppercased()) else { return nil }
        let pin = first.uppercased()
        let params = parseQuery(parts.dropFirst().joined(separator: "&"))
        if let sessionId = params["s"], let keyHex = params["k"],
           !sessionId.isEmpty, !keyHex.isEmpty {
            let key = hexToBytes(keyHex)
            if key.count == keyBytes {
                return PairingPayload(sessionCode: pin, sessionId: sessionId, pairingKey: key)
            }
        }
        return PairingPayload(sessionCode: pin, sessionId: "pin-\(pin)", pairingKey: [UInt8](repeating: 0, count: keyBytes))
    }

    // MARK: - Helpers (Kotlin parity)

    private static func isPin(_ pin: String) -> Bool {
        guard pin.count == pinLength else { return false }
        let scalars = Array(pin.utf8)
        return scalars.allSatisfy { codeAlphabet.contains($0) }
    }

    private static func parseQuery(_ query: String) -> [String: String] {
        guard !query.isEmpty else { return [:] }
        var out: [String: String] = [:]
        for pairRaw in query.split(separator: "&") {
            let pair = String(pairRaw)
            guard let idx = pair.firstIndex(of: "="), idx > pair.startIndex else { continue }
            let key = String(pair[pair.startIndex..<idx]).lowercased()
            let value = String(pair[pair.index(after: idx)...])
            if key == "s" || key == "k" {
                out[key] = value
            }
        }
        return out
    }

    private static func hexToBytes(_ hex: String) -> [UInt8] {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return [] }
        func nibble(_ c: UInt8) -> Int? {
            switch c {
            case UInt8(ascii: "0")...UInt8(ascii: "9"): return Int(c - UInt8(ascii: "0"))
            case UInt8(ascii: "a")...UInt8(ascii: "f"): return Int(c - UInt8(ascii: "a") + 10)
            case UInt8(ascii: "A")...UInt8(ascii: "F"): return Int(c - UInt8(ascii: "A") + 10)
            default: return nil
            }
        }
        var out = [UInt8]()
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i + 1 < chars.count {
            guard let hi = nibble(chars[i]), let lo = nibble(chars[i + 1]) else { return [] }
            out.append(UInt8((hi << 4) | lo))
            i += 2
        }
        return out
    }
}
