package com.streamify.app.jam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1 (Gap #13) — JamTopologyMachine transition contract.
 *
 * The machine is PURE (no Android, no coroutines, no JNI), so these tests
 * drive exactly the paths the engine drives on every local toggle and every
 * inbound TOPOLOGY frame:
 *
 *   1. Host authority    — only the leader may flip the room topology.
 *   2. Session gate      — no session, no topology mutation.
 *   3. Idempotence       — a redundant request is a no-op, not an error.
 *   4. Epoch monotonicity — stale out-of-order frames are refused.
 *   5. Render discipline — guests render in MULTI_RENDER only; the host
 *                          always renders; guests in SINGLE_RENDER become
 *                          remote controllers.
 *   6. 32-member cap     — admission refuses past the hard cap.
 */
class JamTopologyMachineTest {

    // ── Local toggle (host intent) ─────────────────────────────────────────

    @Test
    fun `host may switch multi to single render`() {
        val d = JamTopologyMachine.request(
            current = JamTopology.MULTI_RENDER,
            isActive = true,
            isHost = true,
            target = JamTopology.SINGLE_RENDER,
            nextEpoch = 5L,
            leaderNonce = "HOST"
        )
        assertTrue(d is TopologyDecision.Applied)
        assertEquals(JamTopology.SINGLE_RENDER, (d as TopologyDecision.Applied).topology)
        assertEquals(5L, d.epoch)
    }

    @Test
    fun `host may switch single render back to multi`() {
        val d = JamTopologyMachine.request(
            current = JamTopology.SINGLE_RENDER,
            isActive = true,
            isHost = true,
            target = JamTopology.MULTI_RENDER,
            nextEpoch = 6L,
            leaderNonce = "HOST"
        )
        assertTrue(d is TopologyDecision.Applied)
        assertEquals(JamTopology.MULTI_RENDER, (d as TopologyDecision.Applied).topology)
    }

    @Test
    fun `guest topology request is refused - host authority invariant`() {
        val d = JamTopologyMachine.request(
            current = JamTopology.MULTI_RENDER,
            isActive = true,
            isHost = false,
            target = JamTopology.SINGLE_RENDER,
            nextEpoch = 5L,
            leaderNonce = "HOST",
            requesterNonce = "GUEST"
        )
        assertTrue(d is TopologyDecision.RefusedNotHost)
        assertEquals("GUEST", (d as TopologyDecision.RefusedNotHost).requestedBy)
        assertEquals("HOST", d.leaderNonce)
    }

    @Test
    fun `request without a live session is refused`() {
        val d = JamTopologyMachine.request(
            current = JamTopology.MULTI_RENDER,
            isActive = false,
            isHost = true,
            target = JamTopology.SINGLE_RENDER,
            nextEpoch = 1L,
            leaderNonce = null
        )
        assertTrue(d is TopologyDecision.RefusedNoSession)
    }

    @Test
    fun `redundant request to current topology is a no-op`() {
        val d = JamTopologyMachine.request(
            current = JamTopology.SINGLE_RENDER,
            isActive = true,
            isHost = true,
            target = JamTopology.SINGLE_RENDER,
            nextEpoch = 9L,
            leaderNonce = "HOST"
        )
        assertTrue(d is TopologyDecision.RefusedNoOp)
        assertEquals(JamTopology.SINGLE_RENDER, (d as TopologyDecision.RefusedNoOp).current)
    }

    // ── Inbound frame ingest (mesh → engine) ───────────────────────────────

    @Test
    fun `guest ingests leader topology frame and it applies`() {
        val d = JamTopologyMachine.ingest(
            current = JamTopology.MULTI_RENDER,
            isActive = true,
            senderIsLeader = true,
            frameTopology = JamTopology.SINGLE_RENDER,
            frameEpoch = 7L,
            appliedEpoch = 6L
        )
        assertTrue(d is TopologyDecision.Applied)
        assertEquals(JamTopology.SINGLE_RENDER, (d as TopologyDecision.Applied).topology)
        assertEquals(7L, d.epoch)
    }

    @Test
    fun `topology frame from non-leader is refused`() {
        val d = JamTopologyMachine.ingest(
            current = JamTopology.MULTI_RENDER,
            isActive = true,
            senderIsLeader = false,
            frameTopology = JamTopology.SINGLE_RENDER,
            frameEpoch = 7L,
            appliedEpoch = 1L
        )
        assertTrue(d is TopologyDecision.RefusedNotHost)
    }

    @Test
    fun `stale epoch frame that disagrees with current state is refused`() {
        val d = JamTopologyMachine.ingest(
            current = JamTopology.SINGLE_RENDER,   // we already flipped at epoch 10
            isActive = true,
            senderIsLeader = true,
            frameTopology = JamTopology.MULTI_RENDER, // stale frame wants the old regime
            frameEpoch = 4L,                          // ... from regime 4
            appliedEpoch = 10L
        )
        assertTrue(d is TopologyDecision.RefusedStaleEpoch)
        assertEquals(4L, (d as TopologyDecision.RefusedStaleEpoch).frameEpoch)
        assertEquals(10L, d.appliedEpoch)
    }

    @Test
    fun `higher-epoch frame always wins even against older applied epoch`() {
        val d = JamTopologyMachine.ingest(
            current = JamTopology.SINGLE_RENDER,
            isActive = true,
            senderIsLeader = true,
            frameTopology = JamTopology.MULTI_RENDER,
            frameEpoch = 11L,
            appliedEpoch = 10L
        )
        assertTrue(d is TopologyDecision.Applied)
    }

    @Test
    fun `ingest without a session is refused`() {
        val d = JamTopologyMachine.ingest(
            current = JamTopology.MULTI_RENDER,
            isActive = false,
            senderIsLeader = true,
            frameTopology = JamTopology.SINGLE_RENDER,
            frameEpoch = 2L,
            appliedEpoch = 0L
        )
        assertTrue(d is TopologyDecision.RefusedNoSession)
    }

    // ── Audio-output + UI directives ───────────────────────────────────────

    @Test
    fun `host always renders locally in both topologies`() {
        assertTrue(JamTopologyMachine.rendersLocally(JamTopology.MULTI_RENDER, isHost = true))
        assertTrue(JamTopologyMachine.rendersLocally(JamTopology.SINGLE_RENDER, isHost = true))
    }

    @Test
    fun `guest renders only in multi render`() {
        assertTrue(JamTopologyMachine.rendersLocally(JamTopology.MULTI_RENDER, isHost = false))
        assertFalse(JamTopologyMachine.rendersLocally(JamTopology.SINGLE_RENDER, isHost = false))
    }

    @Test
    fun `guest is a remote controller exactly in party mode`() {
        assertTrue(JamTopologyMachine.isRemoteController(JamTopology.SINGLE_RENDER, isHost = false))
        assertFalse(JamTopologyMachine.isRemoteController(JamTopology.MULTI_RENDER, isHost = false))
        assertFalse(JamTopologyMachine.isRemoteController(JamTopology.SINGLE_RENDER, isHost = true))
    }

    // ── Roster admission (Gap #11) ─────────────────────────────────────────

    @Test
    fun `rooms admit below the 32-member hard cap`() {
        for (n in 0 until 32) {
            assertTrue("count $n must admit", JamTopologyMachine.admitsMember(n))
        }
    }

    @Test
    fun `room at 32 members refuses further admission`() {
        assertFalse(JamTopologyMachine.admitsMember(32))
        assertFalse(JamTopologyMachine.admitsMember(100))
    }

    @Test
    fun `cap constant is the spotify-grade 32`() {
        assertEquals(32, JamTopologyMachine.MAX_MEMBERS_HARD_CAP)
    }

    @Test
    fun `party mode flag mirrors single render`() {
        assertTrue(JamTopology.SINGLE_RENDER.isPartyMode)
        assertFalse(JamTopology.MULTI_RENDER.isPartyMode)
    }
}
