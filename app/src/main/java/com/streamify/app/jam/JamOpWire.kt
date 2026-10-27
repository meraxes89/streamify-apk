package com.streamify.app.jam

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JamOpWire — the packed 48-byte CRDT mutation envelope (FROZEN wire format)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Every shared-queue mutation (ADD / REMOVE / REORDER) travels as this exact
 * 48-byte little-endian structure — zero serialization overhead, identical
 * bits on every peer. The byte image is the cross-boundary currency of the
 * jam control plane: ops are applied and folded by the Rust core through
 * [com.streamify.app.data.uniffi.StreamifyEngineBridge] (UFFI-04 — the
 * duplicate Kotlin Merkle/hash stack was amputated; state convergence is
 * engine-authoritative).
 *
 * Layout (little-endian):
 *
 *   offset  size  field
 *   ──────  ────  ───────────────────────────────────────────────────────────
 *   0       8     opId            u64 — globally unique, strictly increasing
 *                                     per origin (native jamGenerateOpId)
 *   8       8     sender          u64 — packed 4-byte device nonce half
 *   16      4     type            u32 — 1=ADD 2=REMOVE 3=REORDER
 *   20      4     policy          u32 — 0=HOST_ONLY 1=EVERYONE (mutation-time
 *                                     policy snapshot, folded by the CRDT)
 *   24      8     cadId           u64 — canonical track identity
 *   32      8     frac            f64 raw bits — fractional ordering index
 *                                     (bit order == numeric order for the
 *                                     strictly-positive finite domain)
 *   40      8     target          u64 — element add-op targeted by REMOVE /
 *                                     REORDER; 0 for ADD
 *
 *   total   48
 */
data class JamOpWire(
    val opId: Long,
    val sender: Long,
    val type: Int,
    val policy: Int,
    val cadId: Long,
    val frac: Double,
    val target: Long
) {
    companion object {
        const val WIRE_SIZE = 48

        const val OP_ADD: Int = 1
        const val OP_REMOVE: Int = 2
        const val OP_REORDER: Int = 3

        /**
         * Packs this op into a fresh 48-byte little-endian buffer.
         * The byte image is CANONICAL: identical op ⇒ identical bytes on every
         * peer — the property the engine-side state hash relies on.
         */
        fun pack(op: JamOpWire): ByteArray {
            val buf = ByteBuffer.allocate(WIRE_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            buf.putLong(op.opId)
            buf.putLong(op.sender)
            buf.putInt(op.type)
            buf.putInt(op.policy)
            buf.putLong(op.cadId)
            buf.putLong(op.frac.toRawBits())
            buf.putLong(op.target)
            return buf.array()
        }

        /**
         * Unpacks a sealed op. Returns null when the buffer is not exactly
         * 48 bytes or the type/policy fields are outside the frozen enum
         * ranges — a malformed op must never enter the CRDT.
         */
        fun unpack(bytes: ByteArray): JamOpWire? {
            if (bytes.size != WIRE_SIZE) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val opId = buf.long
            val sender = buf.long
            val type = buf.int
            val policy = buf.int
            val cadId = buf.long
            val fracBits = buf.long
            val target = buf.long
            if (type !in OP_ADD..OP_REORDER) return null
            if (policy !in 0..1) return null
            val frac = Double.fromBits(fracBits)
            if (!frac.isFinite()) return null
            return JamOpWire(opId, sender, type, policy, cadId, frac, target)
        }
    }
}
