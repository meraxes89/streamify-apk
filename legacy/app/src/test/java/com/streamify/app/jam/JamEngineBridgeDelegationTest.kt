package com.streamify.app.jam

import com.streamify.app.MockNativeBridge
import com.streamify.app.VirtualMeshRoom
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.models.Track
import com.streamify.app.data.uniffi.StreamifyEngineBridge
import com.streamify.app.testkit.JvmSLogSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * JamEngineBridgeDelegationTest (UFFI-04) — hermetic proof that the session
 * coordinator delegates every CRDT op application and state query to
 * StreamifyEngineBridge's backend seam instead of touching the native plane
 * directly:
 *
 *  • addToQueue → applyJamLocalOp on the installed mock backend with the
 *    sealed field-for-field op, and the UI queue StateFlow rebuilds from
 *    the receipt's authoritative fold;
 *  • a peer OP frame on the wire → applyJamDelta (the single ingress
 *    funnel for remote deltas), queue updated from the receipt;
 *  • own-echo suppression survives the delegation (an OP we generated is
 *    never re-applied as a delta);
 *  • a degraded backend (the hermetic default, no .so) degrades to the
 *    legacy QUEUE_SNAPSHOT path — the CRDT return reports the refusal
 *    (pre-amputation contract) while the fallback keeps the room usable:
 *    never a crash, never a raw Throwable.
 */
class JamEngineBridgeDelegationTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun installJvmSLogSink() {
            JvmSLogSink.install()
        }
    }

    /**
     * Recording mock: the hermetic stand-in for the Rust jam CRDT. Folds
     * ADD/REMOVE ops in memory and returns the authoritative queue with
     * every receipt — the exact contract the JNI interim dispatch and the
     * future UFFI proxies implement.
     */
    private class RecordingJamBackend : StreamifyEngineBridge.EngineBackend {
        override val availability = StreamifyEngineBridge.Availability.MOCK

        val localOps = mutableListOf<StreamifyEngineBridge.JamDeltaOp>()
        val deltas = mutableListOf<StreamifyEngineBridge.JamDeltaOp>()
        val folds = sortedMapOf<Long, StreamifyEngineBridge.JamQueueSlot>() // cadId -> slot
        var lastDeltaReceiptCount = 0

        override fun resolveStreamUrl(videoId: String): Result<StreamifyEngineBridge.ResolvedStream> =
            Result.failure(StreamifyEngineBridge.EngineError.EngineUnavailable("unscripted"))

        override fun hashCandidates(jsonPayload: String): Result<String> =
            Result.failure(StreamifyEngineBridge.EngineError.EngineUnavailable("unscripted"))

        private fun foldNow(): StreamifyEngineBridge.JamDeltaReceipt =
            StreamifyEngineBridge.JamDeltaReceipt(folds.values.toList())

        override fun applyJamLocalOp(op: StreamifyEngineBridge.JamDeltaOp): Result<StreamifyEngineBridge.JamDeltaReceipt> {
            localOps += op
            if (op.type == 1) {
                folds[op.cadId] = StreamifyEngineBridge.JamQueueSlot(
                    fracBits = op.frac.toRawBits(), addOpId = op.opId, cadId = op.cadId
                )
            }
            return Result.success(foldNow())
        }

        override fun applyJamDelta(op: StreamifyEngineBridge.JamDeltaOp): Result<StreamifyEngineBridge.JamDeltaReceipt> {
            deltas += op
            if (op.type == 1) {
                folds[op.cadId] = StreamifyEngineBridge.JamQueueSlot(
                    fracBits = op.frac.toRawBits(), addOpId = op.opId, cadId = op.cadId
                )
            } else {
                val key = folds.entries.firstOrNull { it.value.addOpId == op.target }?.key
                if (key != null) folds.remove(key)
            }
            lastDeltaReceiptCount = folds.size
            return Result.success(foldNow())
        }

        override fun foldJamQueue(): Result<List<StreamifyEngineBridge.JamQueueSlot>> =
            Result.success(folds.values.toList())
    }

    private lateinit var mock: MockNativeBridge
    private lateinit var room: VirtualMeshRoom
    private lateinit var backend: RecordingJamBackend

    private val track = Track(
        id = 7,
        title = "Delegation Blues",
        artist = "The Mock Backends",
        album = "UFFI-04",
        durationSec = 180,
        filepath = "https://cdn.example/delegation.m4a"
    )

    @Before
    fun setUp() {
        room = VirtualMeshRoom()
        mock = MockNativeBridge(JamEngine.deviceId, room)
        mock.install()
        JamEngine.resetForTest()
        // Hermetic cad ids: without the .so every track collapses to cad 0
        // and mutate() refuses OP_ADD before the bridge seam — a stable
        // string hash keeps the derivation deterministic and non-zero.
        NativeBridge.jamCadIdOverride = { title, artist, dur ->
            (title.hashCode().toLong() * 1_000_003L + artist.hashCode().toLong() * 31L + dur)
                .let { if (it == 0L) 1L else it }
        }
        backend = RecordingJamBackend()
        StreamifyEngineBridge.installBackend(backend)
        // The founding host drives the delegation path under test.
        JamEngine.createServerlessRoom()
    }

    @After
    fun tearDown() {
        JamEngine.resetForTest()
        StreamifyEngineBridge.reset()
        NativeBridge.jamCadIdOverride = null
        mock.uninstall()
    }

    /** Lets the UNDISPATCHED-latched ingress collector drain SharedFlow emissions. */
    private fun drain(ms: Long = 200) = Thread.sleep(ms)

    private fun framesOf(type: Int): List<ByteArray> =
        mock.sentFrames.filter { it.first == type }.map { it.second }

    // ── Local mutation → applyJamLocalOp ───────────────────────────────────

    @Test
    fun `addToQueue delegates the sealed op to the bridge backend`() {
        assertTrue(JamEngine.addToQueue(track, addedByName = "Beta"))

        assertEquals(1, backend.localOps.size)
        val op = backend.localOps.first()
        assertEquals(JamOpWire.OP_ADD, op.type)
        assertEquals(1, op.policy) // EVERYONE default
        assertEquals(JamEngine.senderPacked, op.sender)
        assertTrue("frac must be a positive finite index", op.frac > 0.0 && op.frac.isFinite())
        assertEquals(0L, op.target)
    }

    @Test
    fun `queue StateFlow rebuilds from the receipt fold`() {
        assertTrue(JamEngine.addToQueue(track, addedByName = "Beta"))

        // The engine's cad-cache maps the fold's cadId back to the Track.
        val queue = JamEngine.queue.value
        assertEquals(1, queue.size)
        assertEquals("Delegation Blues", queue.first().title)
        // And the fold's fractional index landed in the reorder cache.
        val cad = JamEngineTestHooks.cadOf(track)
        assertEquals(backend.folds[cad]!!.fracBits, JamEngineTestHooks.fracBitsOf(cad))
    }

    // ── Peer delta → applyJamDelta (the single ingress funnel) ─────────────

    @Test
    fun `peer op frame routes through applyJamDelta and updates the queue`() {
        // A remote peer's ADD arrives on the wire.
        val peerSender = 0x4E1CE0AA11CE0L
        val op = JamOpWire(
            opId = 9001L,
            sender = peerSender,
            type = JamOpWire.OP_ADD,
            policy = 1,
            cadId = 555L,
            frac = 0.25,
            target = 0L
        )
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.OP,
            JamWire.encodeOp("peerdev1", 3L, op)
        )
        // TRACK_META lets the engine resolve cadId → Track for the fold.
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.TRACK_META,
            JamWire.encodeTrackMeta(
                "peerdev1", 555L,
                """{"id":9,"title":"Remote Track","artist":"Peer","album":"J","durationSec":200,"filepath":"https://cdn.example/r.m4a"}"""
            )
        )
        drain()

        assertEquals(1, backend.deltas.size)
        assertEquals(op.opId, backend.deltas.first().opId)
        assertEquals(peerSender, backend.deltas.first().sender)

        val queue = JamEngine.queue.value
        assertTrue("queue must contain the remotely added track", queue.any { it.title == "Remote Track" })
    }

    @Test
    fun `own echo is never re-applied as a delta`() {
        assertTrue(JamEngine.addToQueue(track, addedByName = "Beta"))
        val sentOpFrames = framesOf(JamWire.Msg.OP)
        assertTrue(sentOpFrames.isNotEmpty())

        // The mesh echoes our own OP frame back to us — the delta funnel
        // must drop it (echo suppression), not re-apply it.
        backend.deltas.clear()
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.OP,
            sentOpFrames.last(),
            fromPeerHex = VirtualMeshRoom.peerHexOf(JamEngine.deviceId)
        )
        drain()
        // Own echo dies at the sender-identity gate (senderNonce == ours).
        assertEquals(0, backend.deltas.size)
    }

    // ── Degraded backend → legacy snapshot path ────────────────────────────

    @Test
    fun `degraded backend falls back to the legacy snapshot broadcast`() {
        // Swap to the hermetic default backend: no .so, no proxies — the
        // bridge reports EngineUnavailable and the coordinator degrades.
        StreamifyEngineBridge.reset()

        val ok = JamEngine.addToQueue(track, addedByName = "Beta")
        // Pre-amputation contract, preserved by the delegation rewrite: the
        // CRDT return is FALSE (the engine never sealed the op) while the
        // caller's legacy fallback keeps the room usable — queue updated,
        // QUEUE_SNAPSHOT broadcast, never a crash, never a raw Throwable.
        assertFalse("CRDT seam must report the refusal", ok)
        drain()

        assertEquals(1, JamEngine.queue.value.size)
        val snapshots = framesOf(JamWire.Msg.QUEUE_SNAPSHOT)
            .mapNotNull { JamWire.decode(it) }
            .mapNotNull { JamWire.parseQueueSnapshot(it) }
        assertTrue("legacy convergence broadcast must fire", snapshots.isNotEmpty())
        assertTrue(snapshots.last().contains("Delegation Blues"))
    }
}

/** Read-only probes for assertions on engine internals. */
internal object JamEngineTestHooks {
    fun fracBitsOf(cadId: Long): Long? = JamEngine.fracBitsForTest(cadId)
    fun cadOf(track: com.streamify.app.data.models.Track): Long = JamEngine.cadForTest(track)
}
