package com.streamify.app.jam

import java.security.SecureRandom

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JamPairing — offline cryptographic room pairing codec (QR / NFC / paste)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The room's ephemeral pairing key never touches a server. The host mints a
 * 32-byte key; the full join intent is carried offline as a URI:
 *
 *   streamify://jam/<6-char PIN>?v=4&s=<sessionId>&k=<64 hex pairing key>
 *
 * Format decisions:
 *  • The URI deliberately reuses the EXISTING `streamify://jam/` deep-link
 *    shape — `lastPathSegment` still yields the 6-char PIN, so a tap scanned
 *    by the SYSTEM (NFC beam, camera app) routes through the app's
 *    already-shipped deep link into the Jam screen even before the richer
 *    key exchange is understood.
 *  • `k` is the ephemeral pairing key: the Rust mesh (Engineer 2) derives the
 *    room's transport encryption keys from it (HKDF over session id + key),
 *    so a QR shared on paper expires when the room dies.
 *  • QR content = the URI verbatim; NFC NDEF URI record = the same string.
 */
object JamPairing {

    const val SCHEME = "streamify"
    const val HOST = "jam"
    const val VERSION = 4
    const val PIN_LENGTH = 6
    const val KEY_BYTES = 32

    data class PairingPayload(
        val sessionCode: String,
        val sessionId: String,
        val pairingKey: ByteArray
    ) {
        override fun equals(other: Any?): Boolean =
            other is PairingPayload && other.sessionCode == sessionCode &&
                other.sessionId == sessionId && other.pairingKey.contentEquals(pairingKey)

        override fun hashCode(): Int =
            sessionCode.hashCode() * 31 + sessionId.hashCode() + pairingKey.contentHashCode()
    }

    private val codeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    /** Mints a fresh 6-char PIN (ambiguous glyphs excluded: I L O 0 1). */
    fun generateSessionCode(): String = buildString {
        repeat(PIN_LENGTH) { append(codeAlphabet[secureRandom.nextInt(codeAlphabet.length)]) }
    }

    /** Mints a fresh ephemeral 32-byte pairing key. */
    fun generatePairingKey(): ByteArray = ByteArray(KEY_BYTES).also { secureRandom.nextBytes(it) }

    /** Serializes a join intent into the offline QR / NFC payload. */
    fun encodePayload(session: JamEngine.JamSession): String =
        "streamify://jam/${session.sessionCode}" +
            "?v=$VERSION&s=${session.id}&k=${session.pairingKeyHex}"

    /**
     * Parses a pairing payload from any of the accepted forms:
     * full URI, bare 6-char PIN, or "PIN k=hex s=id" fragment pairs.
     * Returns null when nothing usable is present.
     */
    fun parsePayload(raw: String?): PairingPayload? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()

        // Full URI form.
        if (text.startsWith("$SCHEME://")) {
            val body = text.removePrefix("$SCHEME://")
            val path = body.substringBefore('?')
            val query = body.substringAfter('?', "")
            val pin = path.substringAfter("$HOST/", "").substringBefore('/').uppercase()
            if (pin.length != PIN_LENGTH || pin.any { it !in codeAlphabet }) return null
            val params = parseQuery(query)
            val sessionId = params["s"]
            val keyHex = params["k"]
            if (!sessionId.isNullOrBlank() && !keyHex.isNullOrBlank()) {
                val key = hexToBytes(keyHex)
                if (key.size == KEY_BYTES) return PairingPayload(pin, sessionId, key)
            }
            // PIN-only deep link (system scanner stripped nothing else).
            return PairingPayload(pin, "pin-$pin", ByteArray(KEY_BYTES))
        }

        // Bare PIN form.
        val pin = text.uppercase()
        if (pin.length == PIN_LENGTH && pin.all { it in codeAlphabet }) {
            return PairingPayload(pin, "pin-$pin", ByteArray(KEY_BYTES))
        }

        // Key/value fragment form ("PIN s=... k=...").
        val parts = text.split(Regex("\\s+"))
        val first = parts.firstOrNull()?.uppercase() ?: return null
        if (first.length == PIN_LENGTH && first.all { it in codeAlphabet }) {
            val params = parseQuery(parts.drop(1).joinToString("&").replace("=", "="))
            val sessionId = params["s"]
            val keyHex = params["k"]
            if (!sessionId.isNullOrBlank() && !keyHex.isNullOrBlank()) {
                val key = hexToBytes(keyHex)
                if (key.size == KEY_BYTES) return PairingPayload(first, sessionId, key)
            }
            return PairingPayload(first, "pin-$first", ByteArray(KEY_BYTES))
        }
        return null
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val k = pair.substring(0, idx).lowercase()
            val v = pair.substring(idx + 1)
            if (k in setOf("s", "k")) out[k] = v
        }
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hex[i * 2].digitToIntOrNull(16) ?: return ByteArray(0)
            val lo = hex[i * 2 + 1].digitToIntOrNull(16) ?: return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
