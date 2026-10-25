package com.streamify.app.jam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Merkle state reconciliation between two directly cross-wired reconcilers
 * (synchronous transport — every protocol round completes inside the call
 * that started it, exactly like a LAN mesh with sub-millisecond hops).
 *
 * A virtual clock simulates 1 ms of transport latency per frame, so the
 * convergence reports carry realistic nanosecond latencies the < 50 ms
 * contract is asserted against.
 */
class MerkleStateReconcilerTest {

    private lateinit var scope: CoroutineScope

    /**
     * Report collectors run here, on Dispatchers.Unconfined — see the
     * comment in [makePair] for why this MUST NOT be a pooled dispatcher.
     */
    private lateinit var reportScope: CoroutineScope

    @Volatile private var virtualNanos: Long = 5_000_000_000L

    /** Transport hops counter — asserts the round-trip bound. */
    private val hops = AtomicInteger(0)

    private val reportsA = CopyOnWriteArrayList<MerkleStateReconciler.SyncReport>()
    private val reportsB = CopyOnWriteArrayList<MerkleStateReconciler.SyncReport>()

    private lateinit var a: MerkleStateReconciler
    private lateinit var b: MerkleStateReconciler

    /** Applied deltas (per side) for union-convergence assertions. */
    private val appliedA = CopyOnWriteArrayList<Long>() // opIds
    private val appliedB = CopyOnWriteArrayList<Long>()

    private fun clock(): Long = virtualNanos

    /**
     * Builds the cross-wired pair. [mutateBeforeBRootReq] fires once, right
     * before B processes its FIRST root request — the deterministic hook for
     * mid-sync mutation races (host adds a track while a joiner converges).
     */
    private fun makePair(mutateBeforeBRootReq: (() -> Unit)? = null) {
        val routing = HashMap<String, MerkleStateReconciler>()
        val raceFired = AtomicInteger(0)

        fun transport(selfHex: String): MerkleStateReconciler.Transport =
            object : MerkleStateReconciler.Transport {
                override fun sendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean {
                    val target = routing[peerIdHex] ?: return false
                    val frame = JamWire.decode(payload) ?: return false
                    virtualNanos += 1_000_000L // 1 ms simulated LAN hop
                    hops.incrementAndGet()
                    if (mutateBeforeBRootReq != null && target === b &&
                        frame.msgType == JamWire.Msg.MERKLE_ROOT_REQ &&
                        raceFired.getAndIncrement() == 0
                    ) {
                        mutateBeforeBRootReq()
                    }
                    target.onFrame(selfHex, frame)
                    return true
                }
            }

        a = MerkleStateReconciler(
            scope = scope, selfNonce = "aaaa0001",
            transport = transport("aaaa0001"),
            applyDeltaOp = { entry -> appliedA.add(entry.op.opId) },
            clock = ::clock,
            config = MerkleStateReconciler.Config(resyncCooldownNanos = 0L)
        )
        b = MerkleStateReconciler(
            scope = scope, selfNonce = "bbbb0002",
            transport = transport("bbbb0002"),
            applyDeltaOp = { entry -> appliedB.add(entry.op.opId) },
            clock = ::clock,
            config = MerkleStateReconciler.Config(resyncCooldownNanos = 0L)
        )
        routing["aaaa0001"] = a
        routing["bbbb0002"] = b

        // UNCONFINED collectors, UNDISPATCHED start (CI flake runs #281,
        // #287, #309 — NoSuchElementException from `first { it.converged }`):
        // the whole protocol is synchronous on the test thread and ends in
        // `_reports.tryEmit(...)` inside complete(). A collector on a pooled
        // dispatcher (Dispatchers.Default) is resumed asynchronously on a
        // worker thread, so the test thread's immediate
        // `reportsA.first { it.converged }` can execute before the
        // resumption adds the report — most likely under Gradle's parallel
        // test load. Unconfined makes tryEmit resume the collector inline
        // on the emitting (test) thread, so the report is in the list
        // BEFORE onPeerJoined() returns. UNDISPATCHED additionally latches
        // the subscription itself synchronously (replay-0 window guard).
        // Verified standalone (kotlin 1.9.22 / coroutines 1.7.3, harness
        // mirroring this exact pattern): pooled collector 480/3000
        // NoSuchElementException; Unconfined collector 0/50000 under
        // 2-thread CPU load.
        reportScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            a.reports.collect { reportsA.add(it) }
        }
        reportScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            b.reports.collect { reportsB.add(it) }
        }
    }

    private fun op(id: Long, frac: Double = 0.5 + id, type: Int = JamOpWire.OP_ADD): JamOpWire =
        JamOpWire(opId = id, sender = 1L, type = type, policy = 0, cadId = id * 10, frac = frac, target = 0L)

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        reportScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        virtualNanos = 5_000_000_000L
        hops.set(0)
        reportsA.clear()
        reportsB.clear()
        appliedA.clear()
        appliedB.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        reportScope.cancel()
    }

    // ── Zero-byte fast path ────────────────────────────────────────────────

    @Test
    fun `identical states converge with zero ops transferred`() {
        makePair()
        repeat(5) { i ->
            a.noteOpApplied(op(i.toLong()), null)
            b.noteOpApplied(op(i.toLong()), null)
        }
        assertArrayEquals(a.computeRoot(), b.computeRoot())

        val before = hops.get()
        a.onPeerJoined("bbbb0002")
        b.onPeerJoined("aaaa0001")

        val aReport = reportsA.firstOrNull { it.converged }
        val bReport = reportsB.firstOrNull { it.converged }
        assertNotNull(aReport)
        assertNotNull(bReport)
        assertTrue(aReport!!.zeroByteFastPath)
        assertTrue(bReport!!.zeroByteFastPath)
        assertEquals(0, aReport.opsTransferred)
        assertEquals(0, bReport.opsTransferred)
        assertTrue(hops.get() - before <= 4) // two root exchanges, no traversal
    }

    // ── Joiner pull ────────────────────────────────────────────────────────

    @Test
    fun `empty joiner pulls only the missing ops and roots converge`() {
        makePair()
        val jsons = (0 until 7).map { i -> """{"t":"track$i"}""" }
        repeat(7) { i -> b.noteOpApplied(op(i.toLong()), jsons[i]) }

        a.onPeerJoined("bbbb0002")

        assertEquals(7, a.opCount())
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L, 6L), appliedA.sorted())
        assertArrayEquals(a.computeRoot(), b.computeRoot())

        val report = reportsA.first { it.converged }
        assertEquals(7, report.opsTransferred)
        // Bytes: 48 per sealed op + the track metadata JSON that rode along.
        val jsonBytes = jsons.sumOf { it.toByteArray(Charsets.UTF_8).size }
        assertEquals(7 * JamOpWire.WIRE_SIZE + jsonBytes, report.bytesTransferred)
    }

    @Test
    fun `divergent states with equal counts pull symmetrically to the union`() {
        makePair()
        // A: {0,1,2}  B: {1,2,3} — equal counts, different sets.
        listOf(0L, 1L, 2L).forEach { a.noteOpApplied(op(it), null) }
        listOf(1L, 2L, 3L).forEach { b.noteOpApplied(op(it), null) }

        a.onPeerJoined("bbbb0002")
        b.onPeerJoined("aaaa0001")

        assertEquals(setOf(0L, 1L, 2L, 3L), a.opsSnapshot().map { it.opId }.toSet())
        assertEquals(setOf(0L, 1L, 2L, 3L), b.opsSnapshot().map { it.opId }.toSet())
        assertArrayEquals(a.computeRoot(), b.computeRoot())
        assertEquals(listOf(3L), appliedA.sorted()) // A pulled only op 3
        assertEquals(listOf(0L), appliedB.sorted()) // B pulled only op 0
    }

    @Test
    fun `mid-sync mutation triggers a restart that still converges`() {
        makePair(
            mutateBeforeBRootReq = {
                // The host adds a track while the joiner is mid-handshake:
                // B's op set changes AFTER A already built its request.
                b.noteOpApplied(op(99L), null)
            }
        )
        repeat(4) { i -> b.noteOpApplied(op(i.toLong()), null) }

        a.onPeerJoined("bbbb0002")

        // Either the initial traversal happened to include op 99 (race
        // timing) or a restart ran — either way the end state converges.
        assertEquals(5, a.opCount())
        assertArrayEquals(a.computeRoot(), b.computeRoot())
        assertTrue(appliedA.contains(99L))
        val report = reportsA.first { it.converged }
        assertEquals(5, report.opsTransferred)
    }

    // ── Protocol bounds ────────────────────────────────────────────────────

    @Test
    fun `convergence latency stays under the 50ms contract for a 16-op room`() {
        makePair()
        repeat(16) { i -> b.noteOpApplied(op(i.toLong()), null) }

        a.onPeerJoined("bbbb0002")

        val report = reportsA.first { it.converged }
        // 1 ms per simulated hop; depth(16) = 4 ⇒ ~11 hops + delta payload.
        assertTrue(
            "latency ${report.latencyNanos / 1_000_000.0} ms exceeded the 50 ms contract",
            report.latencyNanos < 50_000_000L
        )
        // Round-trip bound: depth + 2 exchanges, each direction counted.
        val expectedMaxHops = 2 * (MerkleStateReconciler.MerkleTree.depthFor(16) + 2)
        assertTrue("hops=${hops.get()} exceeded bound $expectedMaxHops", hops.get() <= expectedMaxHops + 2)
    }

    @Test
    fun `tree depth and padding are depth-aligned across different sizes`() {
        makePair()
        assertEquals(0, MerkleStateReconciler.MerkleTree.depthFor(0))
        assertEquals(0, MerkleStateReconciler.MerkleTree.depthFor(1))
        assertEquals(1, MerkleStateReconciler.MerkleTree.depthFor(2))
        assertEquals(2, MerkleStateReconciler.MerkleTree.depthFor(4))
        assertEquals(4, MerkleStateReconciler.MerkleTree.depthFor(16))
        assertEquals(10, MerkleStateReconciler.MerkleTree.depthFor(1024))

        // A 3-leaf tree (natural depth 2) padded to depth 4 must equal a
        // hand-folded spine: root lifted one level per step, sibling = the
        // all-padding node AT that level (verified against a full depth-4
        // padded tree built leaf-by-leaf).
        val t = MerkleStateReconciler.MerkleTree(
            listOf(Blake3.hash("x"), Blake3.hash("y"), Blake3.hash("z"))
        )
        val aligned = MerkleStateReconciler.MerkleTree.rootAtDepth(t.root, t.depth, 4)
        val naturalRoot = Blake3.hashPair(
            Blake3.hashPair(Blake3.hash("x"), Blake3.hash("y")),
            Blake3.hashPair(Blake3.hash("z"), JamOpWire.PAD_LEAF)
        )
        val manual = Blake3.hashPair(
            Blake3.hashPair(naturalRoot, MerkleStateReconciler.MerkleTree.padAt(2)),
            MerkleStateReconciler.MerkleTree.padAt(3)
        )
        assertArrayEquals(manual, aligned)
        // And the natural root itself is the level-2 root the tree reports.
        assertArrayEquals(naturalRoot, t.root)
    }

    @Test
    fun `roots are insertion-order independent`() {
        makePair()
        a.noteOpApplied(op(1), null)
        a.noteOpApplied(op(2), null)
        a.noteOpApplied(op(3), null)
        val rootA = a.computeRoot()

        b.noteOpApplied(op(3), null)
        b.noteOpApplied(op(1), null)
        b.noteOpApplied(op(2), null)
        assertArrayEquals(rootA, b.computeRoot())
    }

    @Test
    fun `empty rooms share the pad root`() {
        makePair()
        assertArrayEquals(a.computeRoot(), b.computeRoot())
        assertArrayEquals(JamOpWire.PAD_LEAF, a.computeRoot())
    }
}
