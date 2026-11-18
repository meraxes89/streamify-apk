// ─────────────────────────────────────────────────────────────────────────────
// VENDORED into Streamify from zephyr4289/Weft @ 08e3a53
// (android/weft-core/src/main/kotlin/dev/weft/Weft.kt) per the Engineering
// Directive: Real-Device Integration of the Weft Continuous-State Plane.
//
// Kernel semantics are FROZEN — only the package declaration changed.
// Upstream documentation references are preserved verbatim for provenance.
// ─────────────────────────────────────────────────────────────────────────────

// Weft.kt — Triad Protocol kernel (Kotlin/JVM reference port)
//
// WHY EXISTS: Implements the corrected single-atomic-exchange Triad Protocol
// (RFC-0001 §4) for the JVM. The exchange maps to AtomicReference.getAndSet
// (single RMW, SC ordering — strictly stronger than C AcqRel per WO-P4
// decision 2). Per 02-KERNEL §2: one shared atomic, three off-heap buffers,
// ownership by exchange. See docs/PORTS.md §1 for the full mapping table.
//
// STATUS: SOURCE-ONLY, PENDING REAL-DEVICE VERIFICATION.
// Per WHITEPAPER §8.6: no performance claims for this port.

package com.streamify.app.weft

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/// Magic "WEFT" little-endian: 0x54464557
const val WEFT_MAGIC: Int = 0x54464557
const val WEFT_VERSION_1: Short = 1

/// Upper bound for payload_max (1 MiB) — the TIER4 §5 validation wall
/// (issue #19). Mirrors WEFT_PAYLOAD_MAX_LIMIT (core/c/weft.h).
const val WEFT_PAYLOAD_MAX_LIMIT: Int = 1 shl 20

/// Publish result (02 §4).
enum class PubResult { OK, DROPPED_REVOKED, INVALID }

/// Decode result (03-ENVELOPE §2).
enum class DecodeResult { OK, SHORT, BAD_MAGIC, BAD_HEADER }

/// A single Weft: three buffers + the single shared atomic `latest`.
///
/// Buffer layout: [0..16) envelope · [16..16+payload_max) payload ·
/// [buf_size-8..buf_size) canary (u64 LE, value = seq).
///
/// Per 02 §1: latest=0, w_work=1, r_work=2. JVM SC ≥ C AcqRel (decision 2).
class Weft(val payloadMax: Int) {

    init {
        // TIER4 §5 validation wall (issue #19): fail fast on programmer error.
        // require() throws IllegalArgumentException — loud, never a half-built
        // object (the C kernel's -1 refusal maps to a throw in the VM port).
        require(payloadMax in 1..WEFT_PAYLOAD_MAX_LIMIT) {
            "Weft: payloadMax must be in [1, $WEFT_PAYLOAD_MAX_LIMIT] (TIER4 §5), got $payloadMax"
        }
    }

    val bufSize: Int = ((16 + payloadMax + 8 + 63) / 64) * 64

    // Three buffers (JVM heap — no off-heap in pure Kotlin; the JNI bridge
    // to the C kernel uses DirectByteBuffer for production).
    private val buffers: Array<ByteBuffer> = arrayOf(
        ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN),
        ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN),
        ByteBuffer.allocate(bufSize).order(ByteOrder.LITTLE_ENDIAN)
    )

    // The single shared atomic. Exchanged by writer (publish) and reader (claim).
    // AtomicReference.getAndSet = single RMW, SC (≥ AcqRel per decision 2).
    private val latest: AtomicReference<Int> = AtomicReference(0)

    // Writer-private working index. Volatile for debug-view visibility.
    @Volatile private var wWork: Int = 1

    // Reader-private held index. Volatile for debug-view visibility.
    @Volatile private var rWork: Int = 2

    // I6: writer revocation.
    private val revoked: AtomicBoolean = AtomicBoolean(false)
    private val epoch: AtomicInteger = AtomicInteger(0)

    // Telemetry (advisory per AXIOM T)
    private val tPublish: AtomicLong = AtomicLong(0)
    private val tClaim: AtomicLong = AtomicLong(0)
    private val tDrop: AtomicLong = AtomicLong(0)
    private val tInvalid: AtomicLong = AtomicLong(0)

    init {
        // Initialize all 3 buffers with null frames (seq=0, pat(0,i) payload).
        // Per 04-LITMUS §0.6: the null frame is a valid initial state.
        for (i in 0 until 3) {
            envelopeEncodeV1(buffers[i], 0, payloadMax)
            val p = buffers[i].duplicate().apply { position(16) }
            for (j in 0 until payloadMax) {
                p.put(pat(0, j))
            }
        }
    }

    // --- Writer ---

    /// Get a write cursor for the writer's working buffer: a payload-relative
    /// SLICE (position 0 = payload start, zero-copy view). Writers index
    /// relative to the payload: put(0, x) writes payload byte 0. The envelope
    /// is written by publish() and is unreachable through this cursor's
    /// intended window — never write the envelope yourself (02 §2).
    ///
    /// SERIES-7 FIX (wire-order): Java's ByteBuffer.slice() does NOT inherit
    /// the source's byte order — the slice came back BIG_ENDIAN over the
    /// LITTLE_ENDIAN wire buffer, silently byte-swapping every u32/f32 the
    /// cursor touched (byte-granular users were unaffected; word-granular
    /// users got swapped words — caught by rLiveWords' word-level parity
    /// check, RecyclerTest R7). The order is now pinned explicitly: words
    /// through this cursor match the C kernel's little-endian wire format.
    fun wBegin(): ByteBuffer =
        buffers[wWork].duplicate().apply { position(16) }
            .slice().order(ByteOrder.LITTLE_ENDIAN)

    /// Publish: write envelope + canary, then exchange latest.
    /// Per 02 §2 + §6: revoked checked FIRST; exchange is THE atomic.
    fun publish(seq: Int, payloadLen: Int): PubResult {
        // §6 step 1: revoked checked FIRST (advisory — Relaxed/SC load).
        if (revoked.get()) {
            epoch.getAndAdd(1) // ACK (SC ≥ AcqRel)
            tDrop.incrementAndGet()
            return PubResult.DROPPED_REVOKED
        }

        // TIER4 §5 validation wall (issue #19): refuse the frame WHOLE before
        // any byte write. Counted (tInvalid), never silent.
        if (payloadLen < 0 || payloadLen > payloadMax) {
            tInvalid.incrementAndGet()
            return PubResult.INVALID
        }

        // Write envelope (v1, seq, payload_len) into buf[w_work].
        envelopeEncodeV1(buffers[wWork], seq, payloadLen)
        // Write canary = seq at buf[w_work].tail (u64 LE).
        buffers[wWork].putLong(bufSize - 8, seq.toLong())

        // THE atomic: latest.exchange(w_work). getAndSet = single RMW, SC.
        val old = latest.getAndSet(wWork)
        wWork = old

        tPublish.incrementAndGet()
        return PubResult.OK
    }

    // --- Reader ---

    /// Claim the freshest published buffer. NEVER fails.
    fun claim(): Int {
        val r = rWork
        val mine = latest.getAndSet(r) // THE atomic: single RMW, SC.
        rWork = mine
        tClaim.incrementAndGet()
        return mine
    }

    /// Read envelope seq of the reader's held buffer (live).
    fun rSeq(): Int = buffers[rWork].getInt(8)
    fun rMagic(): Int = buffers[rWork].getInt(0)
    fun rPayloadLen(): Int = buffers[rWork].getInt(12)
    fun rCanary(): Long = buffers[rWork].getLong(bufSize - 8)

    /// Read LIVE held-buffer bytes at call time (A3).
    /// Uses duplicate() so the shared ByteBuffer's position is never mutated
    /// (a reader-thread race in earlier revisions — see PORTS.md §1).
    fun rReadSlice(dst: ByteArray, offset: Int): Int {
        if (offset >= bufSize) return 0
        val n = minOf(dst.size, bufSize - offset)
        buffers[rWork].duplicate().apply { position(offset) }.get(dst, 0, n)
        return n
    }

    /// Live view of the READER-HELD buffer (r_work): a payload-relative SLICE
    /// (absolute index 0 = payload start + [payloadOffset], zero-copy, shares
    /// memory with the live buffer — never a snapshot; A3: the reader must
    /// observe the live buffer). This is the API draw-phase bindings MUST use
    /// after claim(); reading wBegin() from the draw thread is a protocol
    /// violation (it is the writer's scratch buffer — torn reads by design).
    /// Parity: C weft_r_live_ptr / Swift rLivePtr take absolute offsets;
    /// this takes a payload-relative offset (0 = start of payload).
    ///
    /// LAW 2 (Series 7 hardening): this method allocates TWO ByteBuffer
    /// wrappers per call (duplicate + slice) — fine for setup and demos,
    /// NOT for a 60-120 Hz drawing loop. Per-frame consumers MUST use
    /// [rLiveWords] (zero allocation: absolute reads into the caller's
    /// pooled slot) or the fan-out reader's view().
    fun rLiveBuf(payloadOffset: Int = 0): ByteBuffer {
        require(payloadOffset >= 0 && payloadOffset < payloadMax) {
            "payloadOffset out of range: $payloadOffset"
        }
        // SERIES-7 FIX (wire-order): slice() does not inherit LITTLE_ENDIAN —
        // pinned explicitly (see wBegin's note).
        return buffers[rWork].duplicate().apply { position(16 + payloadOffset) }
            .slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    /// ZERO-ALLOCATION bulk read of the reader-held payload (Series 7): the
    /// drawing-loop API. Copies at most [dst].size payload words (u32,
    /// little-endian) starting at payload word [offsetWords] into the
    /// CALLER-OWNED destination — a pooled slot from WeftBufferRecycler, a
    /// preallocated IntArray, anything stable; no ByteBuffer wrapper, no
    /// boxing, no temporary arrays (Law 2 — the JVM battery audits bytes).
    /// A3 discipline unchanged: reads the LIVE reader-held buffer at call
    /// time, after claim(). Returns the number of words read.
    fun rLiveWords(dst: IntArray, offsetWords: Int = 0): Int {
        val maxWords = payloadMax / 4
        require(offsetWords >= 0 && offsetWords <= maxWords) {
            "offsetWords out of range: $offsetWords"
        }
        val n = minOf(dst.size, maxWords - offsetWords)
        val buf = buffers[rWork]
        var p = 16 + 4 * offsetWords
        for (i in 0 until n) {
            dst[i] = buf.getInt(p)
            p += 4
        }
        return n
    }

    // --- I6 handshake ---

    fun revoke() { revoked.set(true) }

    fun reclaim(preRevokeEpoch: Int, timeoutMs: Int): Boolean {
        // TIER4 §4 (issue #19): effective bound = min(timeoutMs, ceiling).
        // Ceiling 0 disables itself. Timeouts are counted, never silent; the
        // caller must NOT poison/free after false (the writer has not ACKed).
        val effectiveMs = if (maxReclaimTimeoutMs != 0 && timeoutMs > maxReclaimTimeoutMs)
            maxReclaimTimeoutMs else timeoutMs
        val start = System.currentTimeMillis()
        while (true) {
            if (epoch.get() != preRevokeEpoch) return true
            if (System.currentTimeMillis() - start >= effectiveMs) {
                tReclaimTimeouts.incrementAndGet()
                return false
            }
            Thread.sleep(1)
        }
    }

    /// TIER4 §4: runtime-configurable reclaim ceiling (ms); 0 disables.
    @Volatile var maxReclaimTimeoutMs: Int = 1000
        private set
    fun setMaxReclaimTimeout(maxMs: Int) { maxReclaimTimeoutMs = maxMs }
    private val tReclaimTimeouts = AtomicLong(0)
    fun tReclaimTimeoutsCount(): Long = tReclaimTimeouts.get()

    // --- Telemetry (advisory per AXIOM T) ---
    fun tPublishCount(): Long = tPublish.get()
    fun tClaimCount(): Long = tClaim.get()
    fun tDropCount(): Long = tDrop.get()
    fun tInvalidCount(): Long = tInvalid.get()
    fun epochVal(): Int = epoch.get()
    /// Destroy: free resources (JVM GC handles it, but explicit destroy for API parity with C kernel).
    fun destroy() {
        // JVM GC handles buffer deallocation; this is a no-op for API parity.
        // The Steward handles the real lifecycle via releaseAll().
    }
    fun isRevoked(): Boolean = revoked.get()

    // --- Debug view (WO-P2 T1 mirror) ---
    fun debugState(): WeftDebugView {
        val v = WeftDebugView()
        v.latest = latest.get()
        v.wWork = wWork
        v.rWork = rWork
        v.revoked = revoked.get()
        v.epoch = epoch.get()
        v.tPublish = tPublish.get()
        v.tClaim = tClaim.get()
        v.tDrop = tDrop.get()
        v.midPublishSample = false
        // Sample two live buffers
        var liveCount = 0
        for (i in 0 until 3) {
            if ((i == v.wWork || i == v.rWork || i == v.latest) && liveCount < 2) {
                val b = v.bufs[liveCount]
                b.slotIdx = i
                b.owner = when (i) { v.wWork -> 1; v.rWork -> 2; else -> 3 }
                b.seq = buffers[i].getInt(8)
                b.version = buffers[i].getShort(4).toInt()
                b.headerSize = buffers[i].getShort(6).toInt()
                b.payloadLen = buffers[i].getInt(12)
                liveCount++
            }
        }
        while (liveCount < 2) { v.bufs[liveCount].slotIdx = 3; liveCount++ }
        return v
    }
}

// --- Envelope pure functions (03-ENVELOPE §1, §2) ---

fun envelopeEncodeV1(buf: ByteBuffer, seq: Int, payloadLen: Int) {
    envelopeEncode(buf, WEFT_VERSION_1, 16, seq, payloadLen)
}

fun envelopeEncode(buf: ByteBuffer, version: Short, headerSize: Short, seq: Int, payloadLen: Int) {
    buf.putInt(0, WEFT_MAGIC)
    buf.putShort(4, version)
    buf.putShort(6, headerSize)
    buf.putInt(8, seq)
    buf.putInt(12, payloadLen)
    for (i in 16 until headerSize) buf.put(i.toInt(), 0xAA.toByte())
}

fun envelopeDecode(buf: ByteBuffer, avail: Int): DecodeResult {
    if (avail < 16) return DecodeResult.SHORT
    if (buf.getInt(0) != WEFT_MAGIC) return DecodeResult.BAD_MAGIC
    val hs = buf.getShort(6).toInt()
    if (hs < 16 || hs > avail) return DecodeResult.BAD_HEADER
    val pl = buf.getInt(12)
    if (pl > avail - hs) return DecodeResult.SHORT
    return DecodeResult.OK
}

fun negotiate(writerVersion: Short, readerVersions: ShortArray): Short {
    var chosen: Short = 0
    for (rv in readerVersions) {
        if (rv <= writerVersion && rv > chosen) chosen = rv
    }
    return chosen // 0 = BIND_INCOMPATIBLE
}

// --- Shared payload pattern (04-LITMUS §0.1) ---

fun mix32(x: Int): Int {
    var v = x
    v = v xor (v ushr 16)
    v = (v.toLong() * 0x7FEB352DL).toInt()
    v = v xor (v ushr 15)
    v = (v.toLong() * 0x846CA68BL).toInt()
    v = v xor (v ushr 16)
    return v
}

fun pat(seq: Int, i: Int): Byte {
    val x = (seq.toLong() * 2654435761L + i.toLong() * 2246822519L).toInt()
    return (mix32(x) and 0xFF).toByte()
}

// --- Debug view structs ---

class WeftDebugBuf {
    var slotIdx: Int = 0
    var seq: Int = 0
    var version: Int = 0
    var headerSize: Int = 0
    var payloadLen: Int = 0
    var owner: Int = 0 // 0=free, 1=writer, 2=reader, 3=in-exchange
}

class WeftDebugView {
    var latest: Int = 0
    var wWork: Int = 0
    var rWork: Int = 0
    var revoked: Boolean = false
    var epoch: Int = 0
    var tPublish: Long = 0
    var tClaim: Long = 0
    var tDrop: Long = 0
    var bufs: Array<WeftDebugBuf> = arrayOf(WeftDebugBuf(), WeftDebugBuf())
    var midPublishSample: Boolean = false
}
