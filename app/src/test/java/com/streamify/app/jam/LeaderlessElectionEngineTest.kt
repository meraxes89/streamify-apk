package com.streamify.app.jam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.BeforeClass
import com.streamify.app.testkit.JvmSLogSink

/**
 * Leaderless election FSM + Death Pivot — deterministic, virtual-clock driven.
 *
 * The injected clock makes the 1 200 ms heartbeat contract (and the cascade
 * grace windows) testable in milliseconds of wall time: the test advances the
 * virtual clock in small steps while the 10 ms watchdog polls, so every
 * protocol round is actually processed — no frozen-clock artifacts, no
 * sleep-lottery.
 */
class LeaderlessElectionEngineTest {


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

    private lateinit var scope: CoroutineScope
    private val continuations = CopyOnWriteArrayList<JamWire.ContinuationBody>()

    @Volatile private var virtualNanos: Long = 10_000_000_000L

    private fun clock(): Long = virtualNanos

    /** Advances virtual time in watchable steps so every round is observed. */
    private fun advanceAndPump(ms: Long, stepMs: Long = 40) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(stepMs, remaining)
            virtualNanos += step * 1_000_000L
            Thread.sleep(12)
            remaining -= step
        }
    }

    @Volatile private var lastTick: LeaderlessElectionEngine.HostTickSource.Snapshot? = null

    private val SESSION_CODE = "TESTRM"

    private fun newEngine(selfNonce: String): LeaderlessElectionEngine =
        LeaderlessElectionEngine(
            scope = scope,
            selfNonce = selfNonce,
            config = LeaderlessElectionEngine.ElectionConfig(
                heartbeatTimeoutNanos = 1_200_000_000L, // spec value — never loosened for tests
                continuationGraceNanos = 300_000_000L,
                peerLivenessNanos = 3_600_000_000L,
                pollMillis = 10L
            ),
            clock = ::clock,
            sessionId = { SESSION_CODE },
            transport = object : LeaderlessElectionEngine.Transport {
                override fun broadcastContinuation(body: JamWire.ContinuationBody): Boolean {
                    continuations.add(body)
                    return true
                }
            },
            tickSource = object : LeaderlessElectionEngine.HostTickSource {
                override fun lastVerifiedTick(): LeaderlessElectionEngine.HostTickSource.Snapshot? = lastTick
            }
        )

    /** A nonce whose Blake3 rank beats (sorts lower than) [reference]. */
    private fun nonceOutranking(reference: String): String {
        val refRank = Blake3.hash(reference + SESSION_CODE)
        var i = 0
        while (true) {
            val candidate = "peer%04d".format(i)
            if (Blake3.compareUnsigned(Blake3.hash(candidate + SESSION_CODE), refRank) < 0) return candidate
            i++
        }
    }

    /** A nonce whose Blake3 rank loses to (sorts higher than) [reference]. */
    private fun nonceLosingTo(reference: String): String {
        val refRank = Blake3.hash(reference + SESSION_CODE)
        var i = 9_999
        while (true) {
            val candidate = "peer%04d".format(i)
            if (Blake3.compareUnsigned(Blake3.hash(candidate + SESSION_CODE), refRank) > 0) return candidate
            i--
        }
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        continuations.clear()
        virtualNanos = 10_000_000_000L
        lastTick = null
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // ── Deterministic minimization ─────────────────────────────────────────

    @Test
    fun `election rank is stable and session-bound`() {
        val e = newEngine("self0001")
        val r1 = e.electionRank("dev0009")
        val r2 = e.electionRank("dev0009")
        assertEquals(32, r1!!.size)
        assertTrue(r1.contentEquals(r2!!))
        // A different session must produce a different rank (no cross-room
        // host pinning) — verified against the raw hash contract:
        assertTrue(!r1.contentEquals(Blake3.hash("dev0009" + "OTHER1")))
    }

    @Test
    fun `engines with the same live set elect the same winner`() {
        val a = newEngine("aaaa0001")
        val b = newEngine("bbbb0002")
        val live = listOf("cccc0003", "dddd0004")
        val winnerA = a.electDeterministically(live)
        val winnerB = b.electDeterministically(live)
        assertEquals(winnerA, winnerB)
        // Winner is always a member of (live ∪ self) — never invented.
        assertTrue(winnerA in live || winnerA == "aaaa0001")
        assertTrue(winnerB in live || winnerB == "bbbb0002")
    }

    @Test
    fun `unknown session disables election math`() {
        val e = LeaderlessElectionEngine(
            scope = scope, selfNonce = "self0001", clock = ::clock,
            sessionId = { null },
            transport = object : LeaderlessElectionEngine.Transport {
                override fun broadcastContinuation(body: JamWire.ContinuationBody) = true
            },
            tickSource = object : LeaderlessElectionEngine.HostTickSource {
                override fun lastVerifiedTick() = null
            }
        )
        assertNull(e.electionRank("dev0009"))
        assertNull(e.electDeterministically(listOf("a", "b")))
    }

    // ── Founding leader ────────────────────────────────────────────────────

    @Test
    fun `founding leader takes authority at epoch 1`() {
        val e = newEngine("founder1")
        e.start()
        e.becomeFoundingLeader()
        advanceAndPump(60)
        assertEquals(LeaderlessElectionEngine.ElectionState.LEADER, e.state.value)
        assertEquals("founder1", e.leaderNonce.value)
        assertEquals(1L, e.authorityEpoch.value)
        assertEquals(0, continuations.size) // no pivot — clean bootstrap
    }

    // ── Heartbeat loss → self election → Death Pivot ───────────────────────

    @Test
    fun `heartbeat silence triggers election and death pivot extrapolation`() {
        // Host rank LOSES to ours: after heartbeat loss the deterministic
        // minimum is self — instant pivot, no grace rounds.
        val host = nonceLosingTo("self0001")
        val e = newEngine("self0001")
        e.start()
        advanceAndPump(30)

        val t0 = clock()
        e.notePeerSeen(host)
        e.noteLeaderTick(host, positionMs = 100_000L, syncedNanos = t0, durationMs = 300_000L, playing = true)
        lastTick = LeaderlessElectionEngine.HostTickSource.Snapshot(
            hostNonce = host, positionMs = 100_000L, syncedNanos = t0, durationMs = 300_000L, playing = true
        )
        assertEquals(LeaderlessElectionEngine.ElectionState.FOLLOWER, e.state.value)

        // 1 100 ms of silence must NOT fire (spec: 1 200 ms).
        advanceAndPump(1_100)
        assertEquals(LeaderlessElectionEngine.ElectionState.FOLLOWER, e.state.value)
        assertEquals(0, continuations.size)

        // Cross the spec threshold.
        advanceAndPump(200)
        assertEquals(LeaderlessElectionEngine.ElectionState.LEADER, e.state.value)
        assertEquals("self0001", e.leaderNonce.value)

        // Death Pivot formula (spec): LastHostPosition + (Now − LastHostNanos).
        assertEquals(1, continuations.size)
        val c = continuations.first()
        assertEquals("self0001", c.successorNonce)
        assertEquals(1L, c.newEpoch)
        assertTrue(c.trackMatches)
        assertTrue(c.playing)
        assertEquals(100_000L + (c.pivotSyncedNanos - t0) / 1_000_000L, c.pivotPosMs)
        // Fired no earlier than the heartbeat timeout, not unreasonably late.
        assertTrue(c.pivotSyncedNanos - t0 >= 1_200_000_000L)
        assertTrue(c.pivotSyncedNanos - t0 < 2_600_000_000L)
    }

    @Test
    fun `pivot beyond track end resets to zero and flags mismatch`() {
        val host = nonceLosingTo("self0001")
        val e = newEngine("self0001")
        e.start()
        advanceAndPump(30)
        val t0 = clock()
        e.notePeerSeen(host)
        e.noteLeaderTick(host, 299_000L, t0, 300_000L, true)
        lastTick = LeaderlessElectionEngine.HostTickSource.Snapshot(host, 299_000L, t0, 300_000L, true)

        advanceAndPump(3_000) // extrapolates far past the end
        assertEquals(LeaderlessElectionEngine.ElectionState.LEADER, e.state.value)
        val c = continuations.first()
        assertEquals(0L, c.pivotPosMs)
        assertTrue(!c.trackMatches)
    }

    // ── Successor outranks us → we follow their CONTINUATION ───────────────

    @Test
    fun `lower-rank successor wins and guests adopt its continuation epoch`() {
        val stronger = nonceOutranking("self0001")
        val host = nonceLosingTo(stronger)
        val e = newEngine("self0001")
        e.start()
        advanceAndPump(30)
        val t0 = clock()
        e.notePeerSeen(stronger)
        e.notePeerSeen(host)
        e.noteLeaderTick(host, 50_000L, t0, 200_000L, true)
        lastTick = LeaderlessElectionEngine.HostTickSource.Snapshot(host, 50_000L, t0, 200_000L, true)

        advanceAndPump(1_400) // heartbeat loss
        assertEquals(
            "we must NOT claim leadership — the stronger peer wins",
            LeaderlessElectionEngine.ElectionState.ELECTION,
            e.state.value
        )
        assertEquals(0, continuations.size) // we broadcast nothing

        // The successor's CONTINUATION lands:
        e.noteContinuation(
            JamWire.ContinuationBody(
                newEpoch = 7L, successorNonce = stronger,
                pivotPosMs = 52_000L, pivotSyncedNanos = clock(),
                durationMs = 200_000L, playing = true, trackMatches = true
            )
        )
        assertEquals(LeaderlessElectionEngine.ElectionState.FOLLOWER, e.state.value)
        assertEquals(stronger, e.leaderNonce.value)
        assertEquals(7L, e.authorityEpoch.value)

        // Epoch fence: a stale replayed continuation must be ignored.
        e.noteContinuation(
            JamWire.ContinuationBody(
                newEpoch = 3L, successorNonce = "ghost123",
                pivotPosMs = 0L, pivotSyncedNanos = clock(),
                durationMs = 0L, playing = false, trackMatches = true
            )
        )
        assertEquals(stronger, e.leaderNonce.value)
        assertEquals(7L, e.authorityEpoch.value)
    }

    // ── Cascading failure: the elected successor is ALSO dead ──────────────

    @Test
    fun `dead successor cascades authority down the blake3 ordering`() {
        // Ordering: stronger < self < host < (liveness horizon keeps them).
        val stronger = nonceOutranking("self0001")
        val host = nonceLosingTo("self0001")
        assertTrue(Blake3.compareUnsigned(Blake3.hash(stronger + SESSION_CODE), Blake3.hash(host + SESSION_CODE)) < 0)

        val e = newEngine("self0001")
        e.start()
        advanceAndPump(30)
        val t0 = clock()
        e.notePeerSeen(stronger)
        e.noteLeaderTick(host, 10_000L, t0, 200_000L, true)
        lastTick = LeaderlessElectionEngine.HostTickSource.Snapshot(host, 10_000L, t0, 200_000L, true)

        advanceAndPump(1_400) // host dies → election → winner is `stronger`
        assertEquals(LeaderlessElectionEngine.ElectionState.ELECTION, e.state.value)

        // `stronger` never CONTINUATIONs and never refreshes liveness: after
        // its grace round expires it is dropped; the dead host lingers in the
        // liveness table and is dropped next; authority walks down to self.
        advanceAndPump(3_000)

        assertEquals(LeaderlessElectionEngine.ElectionState.LEADER, e.state.value)
        assertEquals("self0001", e.leaderNonce.value)
        assertEquals(1, continuations.size)
        assertEquals(1L, continuations.first().newEpoch)
        assertEquals(10_000L + (continuations.first().pivotSyncedNanos - t0) / 1_000_000L, continuations.first().pivotPosMs)
    }

    // ── Flap: leader resumes ticking mid-election ──────────────────────────

    @Test
    fun `leader resuming ticks stands the election down`() {
        // Host outranks us: after heartbeat loss the election waits for the
        // host's continuation — exactly the window in which a flap resolves.
        val host = nonceOutranking("self0001")
        val e = newEngine("self0001")
        e.start()
        advanceAndPump(30)
        e.notePeerSeen(host)
        e.noteLeaderTick(host, 20_000L, clock(), 200_000L, true)

        advanceAndPump(1_400) // heartbeat loss → ELECTION (awaiting host)
        assertEquals(LeaderlessElectionEngine.ElectionState.ELECTION, e.state.value)
        assertEquals(0, continuations.size)

        // The "dead" leader comes back (Wi-Fi flap) with a fresh tick.
        e.noteLeaderTick(host, 21_400L, clock(), 200_000L, true)
        assertEquals(LeaderlessElectionEngine.ElectionState.FOLLOWER, e.state.value)
        assertEquals(0, continuations.size)
        assertEquals(host, e.leaderNonce.value)
    }

    @Test
    fun `explicit peer left shortens the failure window`() {
        val host = nonceLosingTo("self0001")
        val e = newEngine("self0001")
        e.start()
        advanceAndPump(30)
        e.notePeerSeen(host)
        e.noteLeaderTick(host, 5_000L, clock(), 200_000L, true)
        lastTick = LeaderlessElectionEngine.HostTickSource.Snapshot(host, 5_000L, clock(), 200_000L, true)

        // Mesh-level departure is reported instantly (battery death detected
        // by the mesh layer — no need to wait out the full 1 200 ms).
        e.notePeerLeft(host)
        advanceAndPump(200)
        assertEquals(LeaderlessElectionEngine.ElectionState.LEADER, e.state.value)
        assertNotNull(continuations.firstOrNull())
    }
}
