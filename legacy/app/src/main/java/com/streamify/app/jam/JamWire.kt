package com.streamify.app.jam

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JamWire v4 — binary frame codec for the zero-server P2P mesh transport
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * All Jam room traffic rides [NativeBridge.nativeP2pBroadcast] /
 * [NativeBridge.nativeP2pSendToPeer] as compact binary frames instead of the
 * legacy Supabase realtime JSON. Control-plane frames (TICK, OP, SYNC, …) are
 * FIXED-LAYOUT structures — no parser allocation, no key strings, no JSON
 * escaping; mutation ops embed the frozen 48-byte [JamOpWire] verbatim.
 * Variable payloads (track metadata) ride an optional length-prefixed UTF-8
 * JSON tail flagged in the header, kept only where a human-facing schema is
 * genuinely dynamic (track bibliographic data).
 *
 * ── Common header — 24 bytes, little-endian ────────────────────────────────
 *
 *   offset  size  field
 *   ──────  ────  ─────────────────────────────────────────────────────────
 *   0       4     magic        'J','A','M','4'  (0x4A 0x41 0x4D 0x34)
 *   4       2     version      u16 = 4
 *   6       1     msgType      u8  — [Msg] registry below
 *   7       1     flags        u8  — bit0 = HAS_TRACK_JSON tail
 *   8       8     senderNonce  8 bytes ASCII (deviceId.take(8), space padded)
 *   16      8     epoch        i64 — playback-regime fence; 0 = n/a
 *
 * Optional tail (flags bit0): u32 jsonLen + jsonLen UTF-8 bytes, placed
 * IMMEDIATELY after the header and BEFORE the fixed body — the body boundary
 * is therefore (frame length − tail), exactly what a decoder needs to
 * validate per-type body sizes without parsing.
 *
 * ── Fixed bodies (after header + optional tail) ───────────────────────────
 *
 *   TICK         i64 seq, i64 hostSyncedNanos, i64 positionMs,
 *                i64 durationMs, i32 playing, i32 policy           (40 B)
 *   PRESENCE     i64 syncedNanos, u8 isHost, u8 linkType, u8 role,
 *                u8 nameLen, name…, u16 avatarLen, avatar…        (var)
 *   OP           JamOpWire sealed 48 bytes                          (48 B)
 *   CONTINUATION i64 newEpoch, 8 B successorNonce, i64 pivotPosMs,
 *                i64 pivotSyncedNanos, i64 durationMs,
 *                i8 playing, u8 trackMatches                       (42 B)
 *   SYNC_REQ     i32 probeId, i64 t0Nanos                           (12 B)
 *   SYNC_ACK     i32 probeId, i64 t0, i64 t1, i64 t2, 8 B target    (36 B)
 *   NEXT_IS      u8 isNull  (+ track JSON tail when non-null)        (1 B)
 *   TRACK_CHANGE i64 positionMs, u8 playing   (+ track JSON tail)    (9 B)
 *   SEEK         i64 positionMs                                      (8 B)
 *   PLAY/PAUSE/LEAVE/SESSION_END  — empty
 *   POLICY       u8 policyOrdinal (0=HOST_ONLY, 1=EVERYONE)          (1 B)
 *   TRACK_META        i64 cadId + u16 jsonLen + json                 (var)
 *   QUEUE_SNAPSHOT    empty body + track-JSON-array tail (legacy path)
 *   STATE_REQ         empty body — joiner asks the leader for live state
 *
 * Message ids 15–20 were the Merkle sync registry, retired with the Kotlin
 * CRDT amputation (UFFI-04): state convergence is engine-authoritative
 * (Rust jam_crdt + outbox replay); the ids stay reserved so remaining
 * wire types never shift.
 *
 * The codec is pure Kotlin (java.nio only) so the entire transport grammar is
 * unit-testable on the JVM shard without Android or native artifacts.
 */
object JamWire {

    const val MAGIC: Int = 0x34_4D_41_4A // "JAM4" read as little-endian u32
    const val VERSION: Int = 4
    const val HEADER_SIZE: Int = 24
    const val MAX_FRAME_BYTES: Int = 256 * 1024
    const val MAX_TRACK_JSON_BYTES: Int = 64 * 1024

    /** Message-type registry (u8). 1–14 protocol core; 15–20 retired Merkle sync (UFFI-04). */
    object Msg {
        const val TICK = 1
        const val PRESENCE = 2
        const val OP = 3
        const val CONTINUATION = 4
        const val SYNC_REQ = 5
        const val SYNC_ACK = 6
        const val NEXT_IS = 7
        const val TRACK_CHANGE = 8
        const val SEEK = 9
        const val PLAY = 10
        const val PAUSE = 11
        const val POLICY = 12
        const val LEAVE = 13
        const val SESSION_END = 14
        const val TRACK_META = 21
        const val QUEUE_SNAPSHOT = 22
        const val STATE_REQ = 23
        // ── Phase 1 additions (Gaps #13, #14, #18) ─────────────────────────
        const val TOPOLOGY = 24
        const val GOVERNANCE = 25
        const val KICK = 26
        const val REPORT = 27
        const val VOTE = 28

        fun nameOf(type: Int): String = when (type) {
            TICK -> "TICK"; PRESENCE -> "PRESENCE"; OP -> "OP"; CONTINUATION -> "CONTINUATION"
            SYNC_REQ -> "SYNC_REQ"; SYNC_ACK -> "SYNC_ACK"; NEXT_IS -> "NEXT_IS"
            TRACK_CHANGE -> "TRACK_CHANGE"; SEEK -> "SEEK"; PLAY -> "PLAY"; PAUSE -> "PAUSE"
            POLICY -> "POLICY"; LEAVE -> "LEAVE"; SESSION_END -> "SESSION_END"
            TRACK_META -> "TRACK_META"; QUEUE_SNAPSHOT -> "QUEUE_SNAPSHOT"
            STATE_REQ -> "STATE_REQ"
            TOPOLOGY -> "TOPOLOGY"; GOVERNANCE -> "GOVERNANCE"; KICK -> "KICK"
            REPORT -> "REPORT"
            VOTE -> "VOTE"
            else -> "UNKNOWN($type)"
        }
    }

    object Flags {
        const val HAS_TRACK_JSON: Int = 0x01
    }

    /** Transport link flavour announced by the sender's mesh layer. */
    object LinkType {
        const val UNKNOWN = 0
        const val WIFI_DIRECT = 1
        const val LAN_5GHZ_UDP = 2
        const val WEBRTC = 3
    }

    // ═════════ Frame model ═════════

    data class Frame(
        val msgType: Int,
        val senderNonce: String,
        val epoch: Long,
        val body: ByteArray,
        val trackJson: String?
    ) {
        /** Caller-checked typed view: TICK. */
        val asTick: TickBody? get() = parseTick(this)

        /** Caller-checked typed view: OP (sealed 48-byte op). */
        val asOp: JamOpWire? get() = parseOp(this)

        override fun equals(other: Any?): Boolean =
            other is Frame && other.msgType == msgType && other.senderNonce == senderNonce &&
                other.epoch == epoch && other.body.contentEquals(body)

        override fun hashCode(): Int = msgType * 31 + senderNonce.hashCode() + body.contentHashCode()
    }

    data class TickBody(
        val seq: Long,
        val hostSyncedNanos: Long,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val policy: Int
    )

    data class PresenceBody(
        val syncedNanos: Long,
        val isHost: Boolean,
        val linkType: Int,
        val name: String,
        val avatarUrl: String?
    )

    data class ContinuationBody(
        val newEpoch: Long,
        val successorNonce: String,
        val pivotPosMs: Long,
        val pivotSyncedNanos: Long,
        val durationMs: Long,
        val playing: Boolean,
        val trackMatches: Boolean
    )

    data class SyncReqBody(val probeId: Int, val t0Nanos: Long)

    data class SyncAckBody(
        val probeId: Int,
        val t0Nanos: Long,
        val t1Nanos: Long,
        val t2Nanos: Long,
        val targetNonce: String
    )

    data class TrackChangeBody(val positionMs: Long, val playing: Boolean, val trackJson: String?)

    data class TrackMetaBody(val cadId: Long, val trackJson: String)

    // ═════════ Encoding ═════════

    private fun header(msgType: Int, senderNonce: String, epoch: Long, flags: Int): ByteArray {
        val h = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(MAGIC)
        h.putShort(VERSION.toShort())
        h.put(msgType.toByte())
        h.put(flags.toByte())
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = senderNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        h.put(nonce)
        h.putLong(epoch)
        return h.array()
    }

    private fun assemble(
        msgType: Int, senderNonce: String, epoch: Long, body: ByteArray, trackJson: String?
    ): ByteArray {
        val jsonBytes = trackJson?.toByteArray(Charsets.UTF_8)
        require(jsonBytes == null || jsonBytes.size <= MAX_TRACK_JSON_BYTES) { "track JSON tail too large" }
        val flags = if (jsonBytes != null) Flags.HAS_TRACK_JSON else 0
        // Wire order: [header][jsonLen+json tail][body] — the tail sits
        // directly behind the header so the decoder can split body/tail
        // without any per-type lookahead (see decode()).
        val tailLen = if (jsonBytes != null) 4 + jsonBytes.size else 0
        val out = ByteArray(HEADER_SIZE + tailLen + body.size)
        System.arraycopy(header(msgType, senderNonce, epoch, flags), 0, out, 0, HEADER_SIZE)
        if (jsonBytes != null) {
            val tail = ByteBuffer.allocate(4 + jsonBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            tail.putInt(jsonBytes.size)
            tail.put(jsonBytes)
            System.arraycopy(tail.array(), 0, out, HEADER_SIZE, tail.array().size)
        }
        System.arraycopy(body, 0, out, HEADER_SIZE + tailLen, body.size)
        require(out.size <= MAX_FRAME_BYTES) { "frame exceeds mesh MTU guard" }
        return out
    }

    fun encodeTick(
        senderNonce: String, epoch: Long, seq: Long, hostSyncedNanos: Long,
        positionMs: Long, durationMs: Long, playing: Boolean, policy: Int
    ): ByteArray {
        val b = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(seq); b.putLong(hostSyncedNanos); b.putLong(positionMs)
        b.putLong(durationMs); b.putInt(if (playing) 1 else 0); b.putInt(policy)
        return assemble(Msg.TICK, senderNonce, epoch, b.array(), null)
    }

    fun encodePresence(
        senderNonce: String, epoch: Long, syncedNanos: Long, isHost: Boolean,
        linkType: Int, name: String, avatarUrl: String?
    ): ByteArray {
        val nameB = name.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, 200)) }
        val avB = avatarUrl?.toByteArray(Charsets.UTF_8)?.let { it.copyOf(minOf(it.size, 512)) }
        val b = ByteBuffer.allocate(12 + nameB.size + 2 + (avB?.size ?: 0))
            .order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(syncedNanos)
        b.put(if (isHost) 1 else 0.toByte())
        b.put(linkType.coerceIn(0, 3).toByte())
        b.put(0) // reserved
        b.put(nameB.size.toByte())
        b.put(nameB)
        b.putShort((avB?.size ?: 0).toShort())
        avB?.let { b.put(it) }
        return assemble(Msg.PRESENCE, senderNonce, epoch, b.array(), null)
    }

    fun encodeOp(senderNonce: String, epoch: Long, op: JamOpWire): ByteArray =
        assemble(Msg.OP, senderNonce, epoch, JamOpWire.pack(op), null)

    fun encodeContinuation(
        senderNonce: String, epoch: Long, newEpoch: Long, successorNonce: String,
        pivotPosMs: Long, pivotSyncedNanos: Long, durationMs: Long,
        playing: Boolean, trackMatches: Boolean
    ): ByteArray {
        val b = ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(newEpoch)
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = successorNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        b.put(nonce)
        b.putLong(pivotPosMs); b.putLong(pivotSyncedNanos); b.putLong(durationMs)
        b.put(if (playing) 1 else 0.toByte())
        b.put(if (trackMatches) 1 else 0.toByte())
        return assemble(Msg.CONTINUATION, senderNonce, epoch, b.array(), null)
    }

    fun encodeSyncReq(senderNonce: String, probeId: Int, t0Nanos: Long): ByteArray {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(probeId); b.putLong(t0Nanos)
        return assemble(Msg.SYNC_REQ, senderNonce, 0L, b.array(), null)
    }

    fun encodeSyncAck(
        senderNonce: String, probeId: Int, t0Nanos: Long, t1Nanos: Long, t2Nanos: Long,
        targetNonce: String
    ): ByteArray {
        val b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(probeId); b.putLong(t0Nanos); b.putLong(t1Nanos); b.putLong(t2Nanos)
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = targetNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        b.put(nonce)
        return assemble(Msg.SYNC_ACK, senderNonce, 0L, b.array(), null)
    }

    fun encodeNextIs(senderNonce: String, epoch: Long, isNull: Boolean, trackJson: String?): ByteArray =
        assemble(Msg.NEXT_IS, senderNonce, epoch, byteArrayOf(if (isNull) 1 else 0), trackJson?.takeIf { !isNull })

    fun encodeTrackChange(
        senderNonce: String, epoch: Long, positionMs: Long, playing: Boolean, trackJson: String
    ): ByteArray {
        val b = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(positionMs); b.put(if (playing) 1 else 0.toByte())
        return assemble(Msg.TRACK_CHANGE, senderNonce, epoch, b.array(), trackJson)
    }

    fun encodeSeek(senderNonce: String, epoch: Long, positionMs: Long): ByteArray {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(positionMs)
        return assemble(Msg.SEEK, senderNonce, epoch, b.array(), null)
    }

    fun encodePlayPause(senderNonce: String, epoch: Long, play: Boolean): ByteArray =
        assemble(if (play) Msg.PLAY else Msg.PAUSE, senderNonce, epoch, ByteArray(0), null)

    fun encodePolicy(senderNonce: String, epoch: Long, policyOrdinal: Int): ByteArray =
        assemble(Msg.POLICY, senderNonce, epoch, byteArrayOf(policyOrdinal.coerceIn(0, 1).toByte()), null)

    fun encodeLeave(senderNonce: String): ByteArray =
        assemble(Msg.LEAVE, senderNonce, 0L, ByteArray(0), null)

    fun encodeSessionEnd(senderNonce: String): ByteArray =
        assemble(Msg.SESSION_END, senderNonce, 0L, ByteArray(0), null)

    fun encodeTrackMeta(senderNonce: String, cadId: Long, trackJson: String): ByteArray {
        val json = trackJson.toByteArray(Charsets.UTF_8)
        require(json.size <= MAX_TRACK_JSON_BYTES) { "track meta JSON too large" }
        val b = ByteBuffer.allocate(10 + json.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(cadId); b.putShort(json.size.toShort()); b.put(json)
        return assemble(Msg.TRACK_META, senderNonce, 0L, b.array(), null)
    }

    /** Legacy convergence path: full queue as a JSON-array tail. */
    fun encodeQueueSnapshot(senderNonce: String, queueJsonArray: String): ByteArray =
        assemble(Msg.QUEUE_SNAPSHOT, senderNonce, 0L, ByteArray(0), queueJsonArray)

    /** Joiner state request — leader answers with a fresh TRACK_CHANGE. */
    fun encodeStateReq(senderNonce: String, epoch: Long): ByteArray =
        assemble(Msg.STATE_REQ, senderNonce, epoch, ByteArray(0), null)

    // ═════════ Phase 1: Topology / Governance / Moderation ═════════

    /**
     * TOPOLOGY — the leader flips the room between MULTI_RENDER (0) and
     * SINGLE_RENDER (1, Party Mode). Guests adopt and re-style their UI +
     * audio-output policy. Epoch-gated like every regime change.
     */
    fun encodeTopology(senderNonce: String, epoch: Long, topologyOrdinal: Int): ByteArray =
        assemble(Msg.TOPOLOGY, senderNonce, epoch, byteArrayOf(topologyOrdinal.coerceIn(0, 1).toByte()), null)

    /**
     * GOVERNANCE — the leader publishes one member-ACL row (or a kick/ban
     * action) so every device's enforcement table stays converged.
     *
     * Body: [targetNonce 8B ASCII][role 1B][allowControl 1B][allowVolume 1B][action 1B]
     */
    fun encodeGovernance(
        senderNonce: String,
        epoch: Long,
        targetNonce: String,
        roleOrdinal: Int,
        allowControl: Boolean,
        allowVolume: Boolean,
        actionOrdinal: Int
    ): ByteArray {
        val b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = targetNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        b.put(nonce)
        b.put(roleOrdinal.coerceIn(0, 2).toByte())
        b.put(if (allowControl) 1 else 0.toByte())
        b.put(if (allowVolume) 1 else 0.toByte())
        b.put(actionOrdinal.coerceIn(0, 3).toByte())
        return assemble(Msg.GOVERNANCE, senderNonce, epoch, b.array(), null)
    }

    /**
     * KICK — host-targeted removal: the named device must end its session
     * locally; every other peer drops the member and blocks the nonce for
     * the room lifetime (rejoin refused at admission).
     */
    fun encodeKick(senderNonce: String, epoch: Long, targetNonce: String): ByteArray {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = targetNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        b.put(nonce)
        return assemble(Msg.KICK, senderNonce, epoch, b.array(), null)
    }

    /**
     * REPORT — guest → host abuse report against a disruptive member.
     *
     * Body: [targetNonce 8B ASCII][reason 1B][reportedAtMs 8B]
     */
    fun encodeReport(senderNonce: String, targetNonce: String, reasonWireCode: Int, reportedAtMs: Long): ByteArray {
        val b = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = targetNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        b.put(nonce)
        b.put(reasonWireCode.coerceIn(1, 4).toByte())
        b.putLong(reportedAtMs)
        return assemble(Msg.REPORT, senderNonce, 0L, b.array(), null)
    }

    /**
     * VOTE — democratic queue upvote (Gap #37). Any member broadcasts one
     * toggle per queue element; every receiving peer folds it into the live
     * tally. The vote itself rides this control frame (the 48-byte CRDT op
     * envelope is FROZEN at ADD/REMOVE/REORDER), while the float-up reorder
     * is emitted as a regular OP_REORDER so late joiners still converge on
     * the democratic order through the engine-authoritative CRDT fold.
     *
     * Body: [targetAddOpId 8B u64][voterNonce 8B ASCII][up 1B][reserved 7B]
     */
    fun encodeVote(senderNonce: String, epoch: Long, targetAddOpId: Long, up: Boolean): ByteArray {
        val b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(targetAddOpId)
        val nonce = ByteArray(8) { ' '.code.toByte() }
        val nb = senderNonce.toByteArray(Charsets.US_ASCII)
        System.arraycopy(nb, 0, nonce, 0, minOf(nb.size, 8))
        b.put(nonce)
        b.put(if (up) 1 else 0.toByte())
        b.put(ByteArray(7)) // reserved — future tally extensions
        return assemble(Msg.VOTE, senderNonce, epoch, b.array(), null)
    }

    // ═════════ Decoding ═════════

    /**
     * Decodes a raw mesh payload. Returns null for any structural violation
     * (bad magic, wrong version, truncated, oversize, bad tail length, wrong
     * per-type body size) — a hostile or corrupt frame must die at the
     * boundary, never inside the engine. Unknown message types decode (the
     * dispatcher no-ops them) but parse to null in every accessor.
     */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < HEADER_SIZE || bytes.size > MAX_FRAME_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != MAGIC) return null
        if (buf.short.toInt() != VERSION) return null
        val msgType = buf.get().toInt() and 0xFF
        val flags = buf.get().toInt() and 0xFF
        val nonceBytes = ByteArray(8)
        buf.get(nonceBytes)
        val senderNonce = nonceBytes.toString(Charsets.US_ASCII).trimEnd(' ')
        val epoch = buf.long
        var trackJson: String? = null
        if ((flags and Flags.HAS_TRACK_JSON) != 0) {
            if (buf.remaining() < 4) return null
            val jsonLen = buf.int
            if (jsonLen < 0 || jsonLen > MAX_TRACK_JSON_BYTES) return null
            if (buf.remaining() < jsonLen) return null
            val jsonBytes = ByteArray(jsonLen)
            buf.get(jsonBytes)
            trackJson = jsonBytes.toString(Charsets.UTF_8)
        }
        val body = ByteArray(buf.remaining())
        buf.get(body)
        if (!bodySizeValid(msgType, body.size)) return null
        return Frame(msgType, senderNonce, epoch, body, trackJson)
    }

    /** Exact body sizes for fixed-layout frames; −1 = variable/unknown. */
    private fun fixedBodySize(msgType: Int): Int = when (msgType) {
        Msg.TICK -> 40
        Msg.OP -> JamOpWire.WIRE_SIZE
        Msg.CONTINUATION -> 42
        Msg.SYNC_REQ -> 12
        Msg.SYNC_ACK -> 36
        Msg.NEXT_IS -> 1
        Msg.TRACK_CHANGE -> 9
        Msg.SEEK -> 8
        Msg.PLAY, Msg.PAUSE -> 0
        Msg.POLICY -> 1
        Msg.LEAVE, Msg.SESSION_END -> 0
        Msg.QUEUE_SNAPSHOT, Msg.STATE_REQ -> 0
        Msg.TOPOLOGY -> 1
        Msg.GOVERNANCE -> 12
        Msg.KICK -> 8
        Msg.REPORT -> 17
        Msg.VOTE -> 24
        else -> -1
    }

    /** Minimum body sizes for variable-layout frames; −1 = no floor. */
    private fun minBodySize(msgType: Int): Int = when (msgType) {
        Msg.PRESENCE -> 12
        Msg.TRACK_META -> 10
        else -> -1
    }

    private fun bodySizeValid(msgType: Int, bodySize: Int): Boolean {
        val fixed = fixedBodySize(msgType)
        if (fixed >= 0) return bodySize == fixed
        val min = minBodySize(msgType)
        return min < 0 || bodySize >= min
    }

    private fun bodyBuf(frame: Frame): ByteBuffer =
        ByteBuffer.wrap(frame.body).order(ByteOrder.LITTLE_ENDIAN)

    fun parseTick(frame: Frame): TickBody? {
        if (frame.msgType != Msg.TICK || frame.body.size != 40) return null
        val b = bodyBuf(frame)
        return TickBody(
            seq = b.long,
            hostSyncedNanos = b.long,
            positionMs = b.long,
            durationMs = b.long,
            playing = b.int != 0,
            policy = b.int
        )
    }

    fun parsePresence(frame: Frame): PresenceBody? {
        if (frame.msgType != Msg.PRESENCE || frame.body.size < 12) return null
        val b = bodyBuf(frame)
        val syncedNanos = b.long
        val isHost = b.get().toInt() != 0
        val linkType = b.get().toInt() and 0xFF
        b.get() // reserved
        val nameLen = b.get().toInt() and 0xFF
        if (b.remaining() < nameLen + 2) return null
        val nameB = ByteArray(nameLen); b.get(nameB)
        val avatarLen = b.short.toInt() and 0xFFFF
        if (b.remaining() < avatarLen) return null
        val avatarB = ByteArray(avatarLen); b.get(avatarB)
        return PresenceBody(
            syncedNanos = syncedNanos,
            isHost = isHost,
            linkType = linkType,
            name = nameB.toString(Charsets.UTF_8),
            avatarUrl = avatarB.toString(Charsets.UTF_8).ifBlank { null }
        )
    }

    fun parseOp(frame: Frame): JamOpWire? =
        if (frame.msgType == Msg.OP) JamOpWire.unpack(frame.body) else null

    fun parseContinuation(frame: Frame): ContinuationBody? {
        if (frame.msgType != Msg.CONTINUATION || frame.body.size != 42) return null
        val b = bodyBuf(frame)
        val newEpoch = b.long
        val nonce = ByteArray(8); b.get(nonce)
        return ContinuationBody(
            newEpoch = newEpoch,
            successorNonce = nonce.toString(Charsets.US_ASCII).trimEnd(' '),
            pivotPosMs = b.long,
            pivotSyncedNanos = b.long,
            durationMs = b.long,
            playing = b.get().toInt() != 0,
            trackMatches = b.get().toInt() != 0
        )
    }

    fun parseSyncReq(frame: Frame): SyncReqBody? {
        if (frame.msgType != Msg.SYNC_REQ || frame.body.size != 12) return null
        val b = bodyBuf(frame)
        return SyncReqBody(probeId = b.int, t0Nanos = b.long)
    }

    fun parseSyncAck(frame: Frame): SyncAckBody? {
        if (frame.msgType != Msg.SYNC_ACK || frame.body.size != 36) return null
        val b = bodyBuf(frame)
        val probeId = b.int
        val t0 = b.long; val t1 = b.long; val t2 = b.long
        val nonce = ByteArray(8); b.get(nonce)
        return SyncAckBody(probeId, t0, t1, t2, nonce.toString(Charsets.US_ASCII).trimEnd(' '))
    }

    fun parseNextIs(frame: Frame): Boolean? {
        if (frame.msgType != Msg.NEXT_IS || frame.body.size != 1) return null
        return frame.body[0].toInt() != 0
    }

    fun parseTrackChange(frame: Frame): TrackChangeBody? {
        if (frame.msgType != Msg.TRACK_CHANGE || frame.body.size != 9) return null
        val b = bodyBuf(frame)
        return TrackChangeBody(positionMs = b.long, playing = b.get().toInt() != 0, trackJson = frame.trackJson)
    }

    fun parseSeek(frame: Frame): Long? {
        if (frame.msgType != Msg.SEEK || frame.body.size != 8) return null
        return bodyBuf(frame).long
    }

    fun parsePolicy(frame: Frame): Int? {
        if (frame.msgType != Msg.POLICY || frame.body.size != 1) return null
        val v = frame.body[0].toInt()
        return if (v in 0..1) v else null
    }

    fun parseTrackMeta(frame: Frame): TrackMetaBody? {
        if (frame.msgType != Msg.TRACK_META || frame.body.size < 10) return null
        val b = bodyBuf(frame)
        val cadId = b.long
        val jsonLen = b.short.toInt() and 0xFFFF
        if (b.remaining() < jsonLen) return null
        val json = ByteArray(jsonLen).also { j -> b.get(j) }.toString(Charsets.UTF_8)
        return TrackMetaBody(cadId, json)
    }

    fun parseQueueSnapshot(frame: Frame): String? =
        if (frame.msgType == Msg.QUEUE_SNAPSHOT) frame.trackJson else null

    fun isStateReq(frame: Frame): Boolean = frame.msgType == Msg.STATE_REQ

    // ═════════ Phase 1 parsers ═════════

    fun parseTopology(frame: Frame): Int? {
        if (frame.msgType != Msg.TOPOLOGY || frame.body.size != 1) return null
        val v = frame.body[0].toInt()
        return if (v in 0..1) v else null
    }

    fun parseGovernance(frame: Frame): GovernanceBody? {
        if (frame.msgType != Msg.GOVERNANCE || frame.body.size != 12) return null
        val b = bodyBuf(frame)
        val nonce = ByteArray(8); b.get(nonce)
        val role = b.get().toInt()
        val allowControl = b.get().toInt() != 0
        val allowVolume = b.get().toInt() != 0
        val action = b.get().toInt()
        if (role !in 0..2 || action !in 0..3) return null
        return GovernanceBody(
            targetNonce = nonce.toString(Charsets.US_ASCII).trimEnd(' '),
            roleOrdinal = role,
            allowControl = allowControl,
            allowVolume = allowVolume,
            actionOrdinal = action
        )
    }

    fun parseKick(frame: Frame): String? {
        if (frame.msgType != Msg.KICK || frame.body.size != 8) return null
        val nonce = ByteArray(8)
        System.arraycopy(frame.body, 0, nonce, 0, 8)
        return nonce.toString(Charsets.US_ASCII).trimEnd(' ')
    }

    fun parseReport(frame: Frame): ReportBody? {
        if (frame.msgType != Msg.REPORT || frame.body.size != 17) return null
        val b = bodyBuf(frame)
        val nonce = ByteArray(8); b.get(nonce)
        val reason = b.get().toInt()
        val atMs = b.long
        if (reason !in 1..4) return null
        return ReportBody(
            targetNonce = nonce.toString(Charsets.US_ASCII).trimEnd(' '),
            reasonWireCode = reason,
            reportedAtMs = atMs
        )
    }

    fun parseVote(frame: Frame): VoteBody? {
        if (frame.msgType != Msg.VOTE || frame.body.size != 24) return null
        val b = bodyBuf(frame)
        val targetAddOpId = b.long
        val nonce = ByteArray(8); b.get(nonce)
        val up = b.get().toInt() != 0
        return VoteBody(
            targetAddOpId = targetAddOpId,
            voterNonce = nonce.toString(Charsets.US_ASCII).trimEnd(' '),
            up = up
        )
    }

    /** VOTE frame body — one member's queue-element vote toggle. */
    data class VoteBody(
        val targetAddOpId: Long,
        val voterNonce: String,
        val up: Boolean
    )

    /** GOVERNANCE frame body — one member-ACL row or kick/ban action. */
    data class GovernanceBody(
        val targetNonce: String,
        val roleOrdinal: Int,
        val allowControl: Boolean,
        val allowVolume: Boolean,
        val actionOrdinal: Int
    ) {
        val isKickAction: Boolean get() = actionOrdinal == GOV_ACTION_KICK
        val isBanAction: Boolean get() = actionOrdinal == GOV_ACTION_BAN

        companion object {
            const val GOV_ACTION_UPSERT = 0
            const val GOV_ACTION_KICK = 1
            const val GOV_ACTION_BAN = 2
            const val GOV_ACTION_UNBLOCK = 3
        }
    }

    /** REPORT frame body — guest abuse report. */
    data class ReportBody(
        val targetNonce: String,
        val reasonWireCode: Int,
        val reportedAtMs: Long
    )
}
