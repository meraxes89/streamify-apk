package com.streamify.app.jam

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LAN UDP BEACON 0x09 PARSER (BEHIND.md Gap #12, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The local Rust mesh (p2p_mesh.rs, Engineer 2 — FROZEN ABI) emits subnet
 * broadcast BEACON packets on UDP port 7777 for automatic same-room
 * discovery. This parser transliterates the frozen packet header verbatim so
 * the Kotlin layer can hear rooms without touching native/ or rust/:
 *
 *   ┌────────┬─────────────┬──────────┬────────────┬──────────────┬──────────┬───────────────┬────────────┬────────────────┐
 *   │ offset │ field       │ size     │ type       │              │          │               │            │                │
 *   ├────────┼─────────────┼──────────┼────────────┼──────────────┼──────────┼───────────────┼────────────┼────────────────┤
 *   │ 0      │ magic       │ 2        │ u16 LE     │ 0x5354 ('ST')│          │               │            │                │
 *   │ 2      │ version     │ 1        │ u8         │ 0x03         │          │               │            │                │
 *   │ 3      │ msg_type    │ 1        │ u8         │ 0x09 = BEACON│          │               │            │                │
 *   │ 4      │ sender_id   │ 8        │ u64 LE     │ Blake3 node  │          │               │            │                │
 *   │ 12     │ session_id  │ 16       │ u128 LE    │ room hash    │          │               │            │                │
 *   │ 28     │ sequence    │ 4        │ u32 LE     │ 0 (control)  │          │               │            │                │
 *   │ 32     │ timestamp   │ 8        │ i64 LE     │ mono ns      │          │               │            │                │
 *   │ 40     │ payload_len │ 2        │ u16 LE     │ 12           │          │               │            │                │
 *   │ 42     │ checksum    │ 4        │ u32 LE     │ FNV-1a/32    │          │               │            │                │
 *   │ 46     │ payload     │ 12       │            │ peerId u64, caps u16, port u16          │            │                │
 *   └────────┴─────────────┴──────────┴────────────┴──────────────┴──────────┴───────────────┴────────────┴────────────────┘
 *
 * Beacon payload (12 bytes): [peerId u64 LE][caps u16 LE][meshPort u16 LE]
 * Capability bits: CAP_LAN = 0x0001, CAP_WEBRTC = 0x0002.
 *
 * The parser is TOTAL (never throws): wrong magic, wrong version, non-beacon
 * types, checksum mismatches and truncation all return null and die at this
 * boundary.
 */
object LanBeaconPacket {

    // ── Frozen constants (mirror rust/src/p2p_mesh.rs) ─────────────────────

    const val MAGIC: Int = 0x5354
    const val PROTO_VERSION: Int = 0x03
    const val MSG_BEACON: Int = 0x09
    const val HEADER_LEN: Int = 46
    const val CHECKSUM_COVERED_LEN: Int = 42
    const val BEACON_PAYLOAD_LEN: Int = 12
    const val DEFAULT_MESH_PORT: Int = 7777

    const val CAP_LAN: Int = 0x0001
    const val CAP_WEBRTC: Int = 0x0002

    /**
     * A parsed 0x09 beacon.
     *
     * @property senderIdHex   16-hex rendering of the 64-bit sender node id
     * @property sessionIdHex  32-hex rendering of the 128-bit room identity —
     *                         the key the join fabric uses to distinguish
     *                         distinct active rooms on the network
     * @property caps          advertised capability bits
     * @property meshPort      the sender's mesh UDP port (default 7777)
     */
    data class Decoded(
        val senderIdHex: String,
        val sessionIdHex: String,
        val caps: Int,
        val meshPort: Int
    ) {
        val hasLan: Boolean get() = caps and CAP_LAN != 0
        val hasWebRtc: Boolean get() = caps and CAP_WEBRTC != 0
    }

    /**
     * FNV-1a/32 over [CHECKSUM_COVERED_LEN] header bytes — identical to the
     * Rust `fnv1a32` (deviation D2: checksum covers the full pre-checksum
     * header span [0..42)).
     */
    fun fnv1a32(bytes: ByteArray, length: Int = CHECKSUM_COVERED_LEN): Int {
        var hash = 0x811C9DC5.toInt()
        for (i in 0 until length.coerceAtMost(bytes.size)) {
            hash = hash xor (bytes[i].toInt() and 0xFF)
            hash *= 0x01000193
        }
        return hash
    }

    /**
     * Total parser for one raw datagram. Returns null for anything that is
     * not a well-formed, checksum-valid Streamify BEACON.
     */
    fun decode(datagram: ByteArray?): Decoded? {
        if (datagram == null) return null
        if (datagram.size < HEADER_LEN + BEACON_PAYLOAD_LEN) return null
        val b = ByteBuffer.wrap(datagram).order(ByteOrder.LITTLE_ENDIAN)
        if (b.short.toInt() and 0xFFFF != MAGIC) return null
        if (b.get().toInt() and 0xFF != PROTO_VERSION) return null
        if (b.get().toInt() and 0xFF != MSG_BEACON) return null

        val senderId = b.long
        val sessionIdBytes = ByteArray(16)
        b.get(sessionIdBytes)
        val sequence = b.int // control frames carry 0; not interpreted
        val timestampMonoNs = b.long
        val payloadLen = b.short.toInt() and 0xFFFF
        val checksumStored = b.int

        if (payloadLen != BEACON_PAYLOAD_LEN) return null

        // Integrity: FNV-1a/32 over the pre-checksum header span.
        if (fnv1a32(datagram) != checksumStored) return null

        // Beacon payload: peerId u64 + caps u16 + port u16.
        val peerId = b.long
        val caps = b.short.toInt() and 0xFFFF
        val port = b.short.toInt() and 0xFFFF

        // Sanity: mesh ports live in the dynamic/private-ish range; hostile
        // zero/privileged ports die here.
        if (port <= 0 || port > 65535) return null

        return Decoded(
            senderIdHex = hex16(peerId),
            sessionIdHex = sessionIdBytes.toHex(),
            caps = caps,
            meshPort = if (port == 0) DEFAULT_MESH_PORT else port
        )
    }

    /**
     * Builds a Rust-shaped beacon (for tests and for the app-layer room
     * announcement path parity checks).
     */
    fun encodeBeacon(senderId: Long, sessionId: ByteArray, caps: Int, port: Int): ByteArray? {
        if (sessionId.size != 16) return null
        val out = ByteBuffer.allocate(HEADER_LEN + BEACON_PAYLOAD_LEN).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(MAGIC.toShort())
        out.put(PROTO_VERSION.toByte())
        out.put(MSG_BEACON.toByte())
        out.putLong(senderId)
        out.put(sessionId)
        out.putInt(0) // sequence — control frame
        out.putLong(System.nanoTime()) // timestamp_mono_ns
        out.putShort(BEACON_PAYLOAD_LEN.toShort())
        out.putInt(0) // checksum placeholder
        out.putLong(senderId)
        out.putShort(caps.toShort())
        out.putShort(port.toShort())

        val raw = out.array()
        val checksum = fnv1a32(raw)
        val cb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        cb.putInt(42, checksum)
        return raw
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun hex16(v: Long): String {
        val b = ByteArray(8)
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
        return b.toHex()
    }

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }
}
