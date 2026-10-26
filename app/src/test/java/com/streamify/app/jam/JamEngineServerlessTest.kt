package com.streamify.app.jam

import com.streamify.app.MockNativeBridge
import com.streamify.app.VirtualMeshRoom
import com.streamify.app.data.models.Track
import com.streamify.app.data.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.BeforeClass
import com.streamify.app.testkit.JvmSLogSink

/**
 * JamEngine v4 end-to-end on the mock mesh: serverless room bootstrap, wire
 * egress contract, guest continuation adoption (zero audio pause), Death
 * Pivot failover, and the legacy CRDT-less queue fallback.
 *
 * The engine singleton runs with the mock room's VIRTUAL synced clock, so
 * the 1 200 ms heartbeat contract is exercised without long sleeps.
 */
class JamEngineServerlessTest {


    companion object {
        // Strict-mock migration (P1): SLog reaches android.util.Log on this
        // path. Install the explicit JVM log fake instead of relying on
        // isReturnDefaultValues = true.
        @BeforeClass
        @JvmStatic
        fun installJvmSLogSink() {
            JvmSLogSink.install()
        }
    }

    private lateinit var mock: MockNativeBridge
    private lateinit var room: VirtualMeshRoom
    private lateinit var collectorScope: CoroutineScope
    private val commands = CopyOnWriteArrayList<JamEngine.Command>()

    private val track = Track(
        id = 1,
        title = "Neon Skyline",
        artist = "Vektor Prime",
        album = "Jam",
        durationSec = 200,
        filepath = "https://cdn.example/neon.m4a"
    )

    @Before
    fun setUp() {
        room = VirtualMeshRoom()
        mock = MockNativeBridge(JamEngine.deviceId, room)
        mock.install()
        JamEngine.resetForTest()
        collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        collectorScope.launch {
            JamEngine.commands.collect { commands.add(it) }
        }
        // Let the SharedFlow subscriber latch before any emissions.
        Thread.sleep(150)
        commands.clear()
    }

    @After
    fun tearDown() {
        JamEngine.resetForTest()
        mock.uninstall()
        collectorScope.cancel()
    }

    private fun drain(ms: Long = 250) = Thread.sleep(ms)

    private fun framesOf(type: Int): List<ByteArray> =
        mock.sentFrames.filter { it.first == type }.map { it.second }

    // ── Room bootstrap + wire egress contract ──────────────────────────────

    @Test
    fun `serverless room creation installs the founding leader`() {
        val session = JamEngine.createServerlessRoom()

        assertNotNull(JamEngine.activeSession())
        assertTrue(JamEngine.isActive())
        assertTrue(JamEngine.isHost())
        assertEquals(1L, JamEngine.syncTelemetry.value.authorityEpoch)
        assertEquals(6, session.sessionCode.length)
        assertEquals(64, session.pairingKeyHex.length)
        drain(300) // presence pulse + loops
    }

    @Test
    fun `egress frames are valid JamWire binaries`() {
        JamEngine.createServerlessRoom()
        JamEngine.myDisplayName = "Alice"
        JamEngine.pulsePresence("Alice", null)
        JamEngine.heartbeatTick(track, positionMs = 42_000L, isPlaying = true)
        JamEngine.setPolicy(JamEngine.ControlPolicy.HOST_ONLY)
        drain(200)

        val presence = framesOf(JamWire.Msg.PRESENCE).mapNotNull { JamWire.decode(it) }
            .mapNotNull { JamWire.parsePresence(it) }
        assertTrue(presence.any { it.name == "Alice" && it.isHost })

        val tick = framesOf(JamWire.Msg.TICK).mapNotNull { JamWire.decode(it) }
            .mapNotNull { it.asTick }
        assertTrue(tick.any { it.positionMs == 42_000L && it.playing })

        val policy = framesOf(JamWire.Msg.POLICY).mapNotNull { JamWire.decode(it) }
            .mapNotNull { JamWire.parsePolicy(it) }
        assertTrue(policy.contains(0)) // HOST_ONLY ordinal

        // Everything the engine emitted decodes cleanly — no junk on the wire.
        for ((_, payload) in mock.sentFrames) {
            assertNotNull(JamWire.decode(payload))
        }
    }

    // ── Guest continuation adoption (Death Pivot landing) ──────────────────

    @Test
    fun `guest adopts continuation epoch with zero audio pause`() {
        assertTrue(JamEngine.joinServerlessRoom("streamify://jam/AB3CD9?v=4&s=sess-guest-1&k=" + "ab".repeat(32)))
        assertTrue(JamEngine.isActive())
        assertTrue(!JamEngine.isHost())

        val host = "host1234"
        // Establish the room leader via presence + a first tick.
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.PRESENCE,
            JamWire.encodePresence(host, 1L, room.clockNanos(), isHost = true, linkType = JamWire.LinkType.LAN_5GHZ_UDP, name = "Host", avatarUrl = null)
        )
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.TICK,
            JamWire.encodeTick(host, 1L, 1L, room.clockNanos(), 50_000L, 200_000L, true, 1)
        )
        drain(200)

        // The leader dies; the successor's CONTINUATION epoch lands on us.
        val successor = "newhost1"
        commands.clear()
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.CONTINUATION,
            JamWire.encodeContinuation(
                host, 0L,
                newEpoch = 5L, successorNonce = successor,
                pivotPosMs = 61_250L, pivotSyncedNanos = room.clockNanos(),
                durationMs = 200_000L, playing = true, trackMatches = true
            )
        )
        drain(200)

        assertEquals(5L, JamEngine.syncTelemetry.value.authorityEpoch)

        // ZERO-STUTTER CONTRACT: continuity arrives as a PLL tick — never a
        // pause, never a stop.
        val pivotCmds = commands.filterIsInstance<JamEngine.Command.ApplyPllTick>()
        assertTrue(pivotCmds.isNotEmpty())
        assertEquals(61_250L, pivotCmds.last().hostPositionMs)
        assertTrue(commands.none { it is JamEngine.Command.ApplyPlayPause && !it.play })
        assertTrue(commands.none { it is JamEngine.Command.SessionEnded })

        // Epoch fence: a replayed older continuation changes nothing.
        commands.clear()
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.CONTINUATION,
            JamWire.encodeContinuation(
                host, 0L, newEpoch = 3L, successorNonce = "ghost999",
                pivotPosMs = 0L, pivotSyncedNanos = room.clockNanos(),
                durationMs = 0L, playing = false, trackMatches = true
            )
        )
        drain(150)
        assertEquals(5L, JamEngine.syncTelemetry.value.authorityEpoch)
        assertTrue(commands.none { it is JamEngine.Command.ApplyPllTick })
    }

    // ── Death Pivot: heartbeat loss → deterministic election → pivot ───────

    @Test
    fun `host death triggers death pivot with extrapolated continuation`() {
        assertTrue(JamEngine.joinServerlessRoom("streamify://jam/AB3CD9?v=4&s=sess-guest-2&k=" + "cd".repeat(32)))

        // A host whose Blake3 rank LOSES to ours — we are the successor.
        val sessionCode = "AB3CD9"
        val myRank = Blake3.hash(JamEngine.deviceId + sessionCode)
        var host = "host9999"
        for (i in 0 until 10_000) {
            val candidate = "host%04d".format(i)
            if (Blake3.compareUnsigned(Blake3.hash(candidate + sessionCode), myRank) > 0) {
                host = candidate
                break
            }
        }

        mock.simulateIncomingPeerPacket(
            JamWire.Msg.PRESENCE,
            JamWire.encodePresence(host, 1L, room.clockNanos(), true, JamWire.LinkType.LAN_5GHZ_UDP, "Host", null)
        )
        val t0 = room.clockNanos()
        mock.simulateIncomingPeerPacket(
            JamWire.Msg.TICK,
            JamWire.encodeTick(host, 1L, 1L, t0, 50_000L, 200_000L, true, 1)
        )
        drain(300)

        // Host goes silent. Advance the synced clock past the 1 200 ms
        // contract; the watchdog (150 ms real polls) fires the election.
        commands.clear()
        mock.sentFrames.clear()
        var guard = 0
        while (!JamEngine.isHost() && guard++ < 40) {
            room.advanceMs(120)
            Thread.sleep(80)
        }
        assertTrue("engine should self-elect after heartbeat loss (guard=$guard)", JamEngine.isHost())

        // THE DEATH PIVOT: continuation broadcast with the extrapolated
        // position = LastHostPosition + (Now − LastHostNanos).
        val continuations = framesOf(JamWire.Msg.CONTINUATION)
            .mapNotNull { JamWire.decode(it) }
            .mapNotNull { JamWire.parseContinuation(it) }
        assertTrue(continuations.isNotEmpty())
        val c = continuations.last()
        assertEquals(JamEngine.deviceId, c.successorNonce)
        assertTrue(c.newEpoch >= 1L)
        assertTrue(c.playing)
        assertTrue(c.trackMatches)
        val elapsedMs = (c.pivotSyncedNanos - t0) / 1_000_000L
        assertEquals(50_000L + elapsedMs, c.pivotPosMs)
        assertTrue(elapsedMs in 1_200..4_000)

        // And the local player was re-anchored through the PLL path —
        // zero pause commands across the entire failover.
        val pivotCmds = commands.filterIsInstance<JamEngine.Command.ApplyPllTick>()
        assertTrue(pivotCmds.isNotEmpty())
        assertTrue(commands.none { it is JamEngine.Command.ApplyPlayPause && !it.play })
    }

    // ── Queue fallback (no native CRDT on the JVM shard) ───────────────────

    @Test
    fun `queue add falls back to snapshot broadcast without native crdt`() {
        JamEngine.createServerlessRoom()
        JamEngine.addToQueue(track, addedByName = "Alice")
        drain(200)

        assertEquals(1, JamEngine.queue.value.size)
        assertEquals("Neon Skyline", JamEngine.queue.value.first().title)
        assertEquals("Added by Alice", JamEngine.addedBy(track.id))

        val snapshots = framesOf(JamWire.Msg.QUEUE_SNAPSHOT)
            .mapNotNull { JamWire.decode(it) }
            .mapNotNull { JamWire.parseQueueSnapshot(it) }
        assertTrue(snapshots.isNotEmpty())
        assertTrue(snapshots.last().contains("Neon Skyline"))
    }

    // ── Pairing ingestion ──────────────────────────────────────────────────

    @Test
    fun `pairing payload and bare pin both open a session`() {
        assertTrue(JamEngine.joinServerlessRoom("streamify://jam/ZZ9QQ7?v=4&s=uuid-x&k=" + "ef".repeat(32)))
        val first = JamEngine.activeSession()!!
        assertEquals("ZZ9QQ7", first.sessionCode)
        JamEngine.resetForTest()

        assertTrue(JamEngine.joinServerlessRoom("zz9qq7"))
        assertEquals("ZZ9QQ7", JamEngine.activeSession()!!.sessionCode)
    }
}
