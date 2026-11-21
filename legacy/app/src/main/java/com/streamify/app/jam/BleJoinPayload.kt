package com.streamify.app.jam

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BLE TAP-TO-JOIN PACKET CODEC (BEHIND.md Gap #12, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Zero-friction join fabric over Bluetooth Low Energy: the host runs a
 * lightweight BLE ADVERTISER whose manufacturer-specific payload carries the
 * room identity; nearby guests running a BLE SCANNER see the advertisement
 * and — when two phones are tapped together / come within ~1 m (RSSI ≥
 * [PROXIMITY_RSSI_DBM]) — Streamify auto-prompts a one-tap "Join [Host]'s
 * Jam" dialog. No PIN, no typing, no pairing ceremony.
 *
 * WIRE FORMAT (manufacturer-specific data, little-endian):
 *
 *   ┌──────┬─────────┬───────────┬────────────┬─────────────┬───────────┐
 *   │ 0x53 │ 0x46    │ version  │ flags      │ sessionCode │ hostName… │
 *   │ 'S'  │ 'F'     │ u8       │ u8         │ 6 bytes     │ UTF-8     │
 *   └──────┴─────────┴───────────┴────────────┴─────────────┴───────────┘
 *
 *   magic        0x53 0x46 ("SF") — every parser rejects foreign payloads
 *                before touching a single field.
 *   version      protocol version (currently 1). Forward-compatible: parsers
 *                accept only their own version and refuse everything else.
 *   flags        bit 0  TOPOLOGY_SINGLE_RENDER — party mode hint so the guest
 *                                                UI can pre-style the prompt
 *                                                ("party on host's speaker")
 *                bit 1  ROOM_HAS_SPACE — host-side 32-cap admission result
 *                bit 2  WELCOME_SET — the host already put a song on (empty
 *                queue rooms render a different subtitle)
 *                bits 3-7 reserved, must be zero on encode.
 *   sessionCode  the 6-char room PIN, ASCII — NOT the pairing key. The
 *                pairing key never leaves QR/NFC; BLE carries only the
 *                public room code (same trust level as a spoken PIN).
 *   hostName     display name of the host, UTF-8, length-prefixed by the
 *                remaining bytes (capped at [MAX_HOST_NAME_BYTES]).
 *
 * The codec is deliberately allocation-light and total (never throws):
 * every malformed input — wrong magic, truncated buffer, hostile length,
 * non-ASCII room codes — parses to null and dies at this boundary, never
 * reaching the join flow.
 */
object BleJoinPayload {

    // ── Wire constants ─────────────────────────────────────────────────────

    const val MAGIC_0: Byte = 0x53 // 'S'
    const val MAGIC_1: Byte = 0x46 // 'F'
    const val VERSION: Byte = 1

    const val OFFSET_MAGIC: Int = 0
    const val OFFSET_VERSION: Int = 2
    const val OFFSET_FLAGS: Int = 3
    const val OFFSET_CODE: Int = 4
    const val CODE_BYTES: Int = 6
    const val OFFSET_HOST_NAME: Int = OFFSET_CODE + CODE_BYTES

    const val MAX_HOST_NAME_BYTES: Int = 48
    const val MAX_PAYLOAD_BYTES: Int = OFFSET_HOST_NAME + MAX_HOST_NAME_BYTES

    // ── Flags ──────────────────────────────────────────────────────────────

    const val FLAG_TOPOLOGY_SINGLE_RENDER: Int = 0x01
    const val FLAG_ROOM_HAS_SPACE: Int = 0x02
    const val FLAG_QUEUE_NON_EMPTY: Int = 0x04
    private const val FLAGS_KNOWN_MASK: Int = 0x07

    // ── Proximity model ────────────────────────────────────────────────────

    /**
     * RSSI threshold ≈ 1 m free-space distance for phone-class BLE radios
     * (empirical Android median ~ -55..-65 dBm at arm's length; -60 keeps
     * the "tapped together" intent while tolerating pocket fabric).
     */
    const val PROXIMITY_RSSI_DBM: Int = -60

    /**
     * Parsed advertisement payload.
     *
     * @property sessionCode 6-char uppercase room PIN
     * @property hostName     host display name ("" when omitted)
     * @property partyMode    host is in SINGLE_RENDER (party) topology
     * @property hasSpace     false → room already at the 32-member cap
     * @property queueNonEmpty true → the room already has tracks queued
     */
    data class Decoded(
        val sessionCode: String,
        val hostName: String,
        val partyMode: Boolean,
        val hasSpace: Boolean,
        val queueNonEmpty: Boolean
    ) {
        /** Human subtitle for the one-tap join prompt. */
        val promptSubtitle: String
            get() = when {
                !hasSpace -> "Room is full (32/32)"
                partyMode -> "Party mode — listening on ${hostName.ifBlank { "the host" }}'s speaker"
                queueNonEmpty -> "Listening together · ${hostName.ifBlank { "Host" }}'s room"
                else -> "Start listening together with ${hostName.ifBlank { "the host" }}"
            }
    }

    // ── Encode (host side) ─────────────────────────────────────────────────

    /**
     * Serializes the room identity into an advertisement-ready payload.
     * Returns null when the inputs are unrepresentable (bad code length,
     * oversize host name) — the advertiser then skips this advert round
     * rather than emitting junk.
     */
    fun encode(
        sessionCode: String,
        hostName: String,
        partyMode: Boolean = false,
        hasSpace: Boolean = true,
        queueNonEmpty: Boolean = false
    ): ByteArray? {
        val code = sessionCode.trim().uppercase()
        if (code.length != CODE_BYTES) return null
        if (code.any { it.code !in 32..126 }) return null // ASCII-printable only
        val nameBytes = hostName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > MAX_HOST_NAME_BYTES) return null

        val flags = (if (partyMode) FLAG_TOPOLOGY_SINGLE_RENDER else 0) or
            (if (hasSpace) FLAG_ROOM_HAS_SPACE else 0) or
            (if (queueNonEmpty) FLAG_QUEUE_NON_EMPTY else 0)

        val out = ByteArray(OFFSET_HOST_NAME + nameBytes.size)
        out[OFFSET_MAGIC] = MAGIC_0
        out[OFFSET_MAGIC + 1] = MAGIC_1
        out[OFFSET_VERSION] = VERSION
        out[OFFSET_FLAGS] = flags.toByte()
        System.arraycopy(code.toByteArray(Charsets.US_ASCII), 0, out, OFFSET_CODE, CODE_BYTES)
        System.arraycopy(nameBytes, 0, out, OFFSET_HOST_NAME, nameBytes.size)
        return out
    }

    // ── Decode (guest side) ────────────────────────────────────────────────

    /**
     * Total parser: any malformed input returns null. Handles
     * over-long buffers (scan records carry the payload inside a larger
     * manufacturer-specific blob — extra bytes beyond the host name are
     * tolerated only when they are provably not ours: the name length is
     * not wire-encoded, so over-long payloads are rejected to keep parsing
     * unambiguous).
     */
    fun decode(bytes: ByteArray?): Decoded? {
        if (bytes == null) return null
        if (bytes.size < OFFSET_HOST_NAME) return null
        // BOTH magic bytes (audit #22 item 3): the decode used to check
        // bytes[0] ('S') and bytes[2] (version) but never bytes[1] ('F'),
        // so any `0x53 ?? 0x01…` foreign payload passed the magic gate.
        // Encode has always written both; decode now enforces both.
        if (bytes[OFFSET_MAGIC] != MAGIC_0 ||
            bytes[OFFSET_MAGIC + 1] != MAGIC_1 ||
            bytes[OFFSET_VERSION] != VERSION
        ) return null

        val flags = bytes[OFFSET_FLAGS].toInt() and 0xFF
        if (flags and FLAGS_KNOWN_MASK.inv() != 0) return null // reserved bits must be zero

        val codeBytes = ByteArray(CODE_BYTES)
        System.arraycopy(bytes, OFFSET_CODE, codeBytes, 0, CODE_BYTES)
        val code = String(codeBytes, Charsets.US_ASCII)
        if (code.length != CODE_BYTES) return null
        if (code.any { it.code !in 32..126 }) return null

        val nameLen = bytes.size - OFFSET_HOST_NAME
        if (nameLen < 0 || nameLen > MAX_HOST_NAME_BYTES) return null
        val name = if (nameLen == 0) "" else String(bytes, OFFSET_HOST_NAME, nameLen, Charsets.UTF_8)

        return Decoded(
            sessionCode = code,
            hostName = name,
            partyMode = flags and FLAG_TOPOLOGY_SINGLE_RENDER != 0,
            hasSpace = flags and FLAG_ROOM_HAS_SPACE != 0,
            queueNonEmpty = flags and FLAG_QUEUE_NON_EMPTY != 0
        )
    }

    /**
     * Scans a raw BLE scan record for a Streamify manufacturer-specific
     * payload with the given manufacturer id and decodes it. AD structures:
     * [length][type=0xFF][companyId u16 LE][payload].
     *
     * Returns null when the record carries no Streamify advert. Never
     * throws — hostile scan records die here.
     */
    fun fromScanRecord(scanRecord: ByteArray?, manufacturerId: Int): Decoded? {
        if (scanRecord == null) return null
        var i = 0
        while (i + 1 < scanRecord.size) {
            val len = scanRecord[i].toInt() and 0xFF
            if (len == 0) break
            if (i + 1 + len > scanRecord.size) break // truncated structure
            val type = scanRecord[i + 1].toInt() and 0xFF
            if (type == 0xFF && len >= 3) {
                val companyId = ((scanRecord[i + 3].toInt() and 0xFF) shl 8) or
                    (scanRecord[i + 2].toInt() and 0xFF)
                if (companyId == manufacturerId) {
                    val payloadLen = len - 3
                    if (payloadLen < OFFSET_HOST_NAME) return null
                    val payload = ByteArray(payloadLen)
                    System.arraycopy(scanRecord, i + 4, payload, 0, payloadLen)
                    return decode(payload)
                }
            }
            i += 1 + len
        }
        return null
    }

    /**
     * Builds the complete manufacturer-specific blob (companyId LE + payload)
     * for [android.bluetooth.le.AdvertiseData.Builder.addManufacturerData].
     */
    fun encodeManufacturerData(
        sessionCode: String,
        hostName: String,
        partyMode: Boolean = false,
        hasSpace: Boolean = true,
        queueNonEmpty: Boolean = false,
        manufacturerId: Int = MANUFACTURER_ID
    ): ByteArray? {
        val payload = encode(sessionCode, hostName, partyMode, hasSpace, queueNonEmpty) ?: return null
        val out = ByteBuffer.allocate(2 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(manufacturerId.toShort())
        out.put(payload)
        return out.array()
    }

    /**
     * Streamify's BLE manufacturer identifier. 0xFFFF-range experimental IDs
     * are not registered; 0x0F1E ("Streamify Jam") is far from assigned
     * Bluetooth SIG space and stable across releases.
     */
    const val MANUFACTURER_ID: Int = 0x0F1E
}
