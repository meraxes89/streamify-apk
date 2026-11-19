package com.streamify.app

import com.streamify.app.data.NativeBridge
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MockNativeBridge — the serverless Jam mock harness (mission §5)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Lets the FSMs, wire codecs, Merkle reconciler, election engine and the
 * JamEngine dispatcher be developed and tested in complete isolation from
 * Engineer 1 (C++ DSP) and Engineer 2 (Rust mesh).
 *
 * Architecture: a [VirtualMeshRoom] hosts one [DeviceEndpoint] per simulated
 * device; each endpoint implements [NativeBridge.P2pMesh] so it can be
 * installed as `NativeBridge.meshOverride` for the code under test. Frames
 * broadcast by one endpoint are routed synchronously to every other live
 * endpoint exactly as the real mesh upcall would, which means production
 * code and test code share a single transport path.
 *
 * The room also owns a virtual nanosecond clock (installed via
 * `NativeBridge.syncedClockOverride`) with injectable jitter, so Death-Pivot
 * extrapolation and heartbeat-loss detection are deterministic under test.
 */
class MockNativeBridge(val deviceId: String, private val room: VirtualMeshRoom = VirtualMeshRoom()) {

    val endpoint: VirtualMeshRoom.DeviceEndpoint = room.endpoint(deviceId)

    /** Every frame this device has broadcast (msgType, payload) — assertions. */
    val sentFrames: ConcurrentLinkedQueue<Pair<Int, ByteArray>> get() = endpoint.sentFrames

    fun install() {
        NativeBridge.meshOverride = endpoint
        NativeBridge.syncedClockOverride = { room.clockNanos() }
    }

    fun uninstall() {
        NativeBridge.meshOverride = null
        NativeBridge.syncedClockOverride = null
    }

    // ── Mission-§5 surface ─────────────────────────────────────────────────

    /**
     * Feeds a raw frame into THIS device's incoming pipeline exactly as a
     * remote peer's mesh upcall would.
     */
    fun simulateIncomingPeerPacket(type: Int, payload: ByteArray, fromPeerHex: String = "deadbeef") {
        endpoint.injectIncoming(fromPeerHex, type, payload)
    }

    /**
     * Kills the given host device (radio off + clock of its frames stopped)
     * — the surviving peers must detect heartbeat loss and elect a successor
     * through the deterministic native-governor path with zero audio pause.
     */
    fun simulateHostDropAndFailover(hostDeviceId: String) {
        room.endpoint(hostDeviceId).kill()
    }

    /** Injects clock jitter (nanos) onto the shared virtual synced clock. */
    fun simulateClockJitter(jitterNanos: Long) {
        room.jitterNanos = jitterNanos
    }

    fun reset() {
        room.reset()
        install()
    }
}

/**
 * The virtual room: synchronous frame routing between simulated devices plus
 * the shared virtual clock.
 */
class VirtualMeshRoom {

    /** Virtual synced clock (nanos) + injectable jitter. */
    @Volatile var jitterNanos: Long = 0L

    private var baseNanos: Long = 1_000_000_000L // non-zero, boot-like domain
    private val endpoints = LinkedHashMap<String, DeviceEndpoint>()

    fun clockNanos(): Long = baseNanos + jitterNanos

    /** Advances virtual time (ms granularity helper). */
    fun advanceMs(ms: Long) {
        advanceNanos(ms * 1_000_000L)
    }

    fun advanceNanos(nanos: Long) {
        baseNanos += nanos
    }

    fun endpoint(deviceId: String): DeviceEndpoint =
        endpoints.getOrPut(deviceId) { DeviceEndpoint(deviceId) }

    fun reset() {
        endpoints.values.forEach { it.reset() }
        endpoints.clear()
        baseNanos = 1_000_000_000L
        jitterNanos = 0L
    }

    inner class DeviceEndpoint internal constructor(val deviceId: String) : NativeBridge.P2pMesh {

        @Volatile private var started = false

        /** Radio death: broadcasts fail, nothing routes in. */
        @Volatile var dead = false

        @Volatile private var listener: NativeBridge.P2pIncomingListener? = null

        val sentFrames = ConcurrentLinkedQueue<Pair<Int, ByteArray>>()

        /** Latency applied to this device's frame delivery (nanos). */
        @Volatile var deliveryDelayNanos = 0L

        override fun start(
            sessionId: String, deviceId: String, enableLan: Boolean, enableWebRtc: Boolean
        ): Boolean {
            started = true
            dead = false
            return true
        }

        override fun stop() {
            started = false
        }

        override fun broadcast(msgType: Int, payload: ByteArray): Boolean {
            if (!started || dead) return false
            sentFrames.add(msgType to payload.copyOf())
            routeToOthers(msgType, payload)
            return true
        }

        override fun sendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean {
            if (!started || dead) return false
            sentFrames.add(msgType to payload.copyOf())
            val target = endpoints.values.firstOrNull { peerHexOf(it.deviceId) == peerIdHex }
                ?: return false
            deliver(target, msgType, payload)
            return true
        }

        override fun connectedPeerCount(): Int =
            endpoints.values.count { it !== this && it.started && !it.dead }

        override fun registerIncoming(listener: NativeBridge.P2pIncomingListener): Boolean {
            this.listener = listener
            return true
        }

        private fun routeToOthers(msgType: Int, payload: ByteArray) {
            endpoints.values
                .filter { it !== this && it.started && !it.dead }
                .forEach { deliver(it, msgType, payload) }
        }

        private fun deliver(target: DeviceEndpoint, msgType: Int, payload: ByteArray) {
            val l = target.listener ?: return
            if (deliveryDelayNanos > 0L) {
                // Simulated transport latency: virtual time moves first.
                advanceNanos(deliveryDelayNanos)
            }
            l.onP2pFrame(peerHexOf(deviceId), msgType, payload.copyOf())
        }

        /** Test injection: a frame arriving from a remote peer. */
        fun injectIncoming(fromPeerHex: String, msgType: Int, payload: ByteArray) {
            listener?.onP2pFrame(fromPeerHex, msgType, payload.copyOf())
        }

        fun injectPeerJoined(peerIdHex: String) {
            listener?.onP2pPeerJoined(peerIdHex)
        }

        fun injectPeerLeft(peerIdHex: String) {
            listener?.onP2pPeerLeft(peerIdHex)
        }

        fun kill() {
            dead = true
            started = false
        }

        fun revive() {
            dead = false
            started = true
        }

        fun reset() {
            started = false
            dead = false
            listener = null
            sentFrames.clear()
            deliveryDelayNanos = 0L
        }
    }

    companion object {
        /** Deterministic peer-id hex for a device nonce (mesh-level identity). */
        fun peerHexOf(deviceId: String): String {
            val sb = StringBuilder()
            for (b in deviceId.toByteArray(Charsets.US_ASCII)) {
                sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
                sb.append("0123456789abcdef"[b.toInt() and 0xF])
            }
            return sb.toString()
        }
    }
}
