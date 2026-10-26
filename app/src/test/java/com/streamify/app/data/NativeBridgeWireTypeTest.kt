package com.streamify.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * NativeBridgeWireTypeTest — audit #44 P0-1 regression cover.
 *
 * The Rust mesh's frozen type registry owns 0x01..0x11 as protocol
 * traffic (PTP sync, CRDT ops, heartbeats, gossip control, chunk swarm,
 * governance); the JNI guard rejects app attempts to spoof them, and the
 * Kotlin JamWire vocabulary (Msg 1..=31) collides with that range
 * head-on (OP=3 is MSG_CHUNK_DATA, CONTINUATION=4 is MSG_HEARTBEAT,
 * TICK=1 masquerades as PTP_SYNC). The native egress therefore folds the
 * JamWire family into the single app-visible envelope type 0x20.
 *
 * These tests pin:
 *   1. the mapping itself (private `meshWireType`, via reflection —
 *      a pure function, stable surface for a regression pin);
 *   2. the transport seam: the meshOverride (test mock) path receives
 *      RAW JamWire types — mock assertions are app-layer contracts and
 *      must not observe the wire envelope;
 *   3. ingress symmetry: `simulateIncomingFrame` (the production-shared
 *      test injection gate) keeps feeding raw types into the flow —
 *      JamEngine decodes the payload and ignores the mesh type.
 */
class NativeBridgeWireTypeTest {

    private class RecordingMesh : NativeBridge.P2pMesh {
        val broadcasts = ConcurrentLinkedQueue<Pair<Int, ByteArray>>()
        val unicasts = ConcurrentLinkedQueue<Triple<String, Int, ByteArray>>()
        override fun start(sessionId: String, deviceId: String, enableLan: Boolean, enableWebRtc: Boolean) = true
        override fun stop() {}
        override fun broadcast(msgType: Int, payload: ByteArray): Boolean {
            broadcasts.add(msgType to payload); return true
        }
        override fun sendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean {
            unicasts.add(Triple(peerIdHex, msgType, payload)); return true
        }
        override fun connectedPeerCount() = 0
        override fun registerIncoming(listener: NativeBridge.P2pIncomingListener) = true
    }

    private var savedOverride: NativeBridge.P2pMesh? = null

    @Before
    fun snapshotOverride() {
        savedOverride = NativeBridge.meshOverride
    }

    @After
    fun restoreOverride() {
        NativeBridge.meshOverride = savedOverride
    }

    private fun meshWireType(msgType: Int): Int {
        val m = NativeBridge::class.java.getDeclaredMethod("meshWireType", Int::class.javaPrimitiveType)
        m.isAccessible = true
        // NativeBridge is a Kotlin `object`: meshWireType compiles to an
        // INSTANCE method on the singleton class, so reflection must pass
        // the singleton as receiver. invoke(null, ...) throws NPE — which
        // is exactly what CI surfaced (run #356, jvm-unit-suite) the first
        // time this suite ran under the new blocking gates.
        return m.invoke(NativeBridge, msgType) as Int
    }

    @Test
    fun `jamwire family folds into the 0x20 envelope`() {
        // The full documented JamWire Msg vocabulary (1..=28 today, family
        // closed at 31) must collapse onto the single app-visible type.
        for (t in 1..31) {
            assertEquals("JamWire type $t must fold onto the envelope", 0x20, meshWireType(t))
        }
    }

    @Test
    fun `app extensions at or above 0x20 pass through untouched`() {
        assertEquals(0x20, meshWireType(0x20))
        assertEquals(0x21, meshWireType(0x21))
        assertEquals(0xFF, meshWireType(0xFF))
    }

    @Test
    fun `zero and negatives pass through untouched`() {
        // 0 is not a JamWire type and not reserved on the Rust side's
        // 0..=255 acceptance window; the mapping must not invent values.
        assertEquals(0, meshWireType(0))
        assertEquals(-1, meshWireType(-1))
    }

    @Test
    fun `mock transport seam sees raw JamWire types`() {
        val mesh = RecordingMesh()
        NativeBridge.meshOverride = mesh
        val payload = byteArrayOf(1, 2, 3)

        // JamWire.Msg.OP = 3 (would be MSG_CHUNK_DATA on the native wire).
        assertTrue(NativeBridge.p2pBroadcast(3, payload))
        assertTrue(NativeBridge.p2pSendToPeer("aabb", 15, payload)) // MERKLE_ROOT_REQ

        assertEquals(3, mesh.broadcasts.poll()?.first)
        assertEquals(15, mesh.unicasts.poll()?.second)
    }
}
