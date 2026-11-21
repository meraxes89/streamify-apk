package com.streamify.app.media.sync
import android.os.SystemClock
import com.streamify.app.util.SLog as Log
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.network.MeshDiscoveryEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PrecisionTimeProtocol v4 — nanosecond PTP discipline (Kotlin wrapper)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Two sample transports, one clock model (IEEE-1588-style ping-pong over the
 * room's synced timeline):
 *
 *  PRIMARY — PTP over the P2P mesh: JamEngine's SYNC_REQ / SYNC_ACK frames
 *  carry t0..t3 across the Rust mesh and feed the samples straight into the
 *  native filters ([NativeBridge.nativePtpProcessTimestamps] /
 *  [NativeBridge.nativeGetSynchronizedClockNanos] — Engineer 1's frozen ABI).
 *  This path needs no sockets and follows the room wherever the mesh goes.
 *
 *  FALLBACK — the legacy raw-UDP ping-pong through [MeshDiscoveryEngine]
 *  (LAN-only, pre-mesh rooms), preserved verbatim so a mesh-less build keeps
 *  a functioning clock.
 *
 * ABI progression: the new frozen symbols are tried first; until Engineer 1
 *  ships them the calls fall back to the legacy externals (both compute the
 *  same Cristian/EMA offset filter), and finally to a pass-through local
 *  clock — the class degrades, never crashes.
 */
class PrecisionTimeProtocol private constructor(
    private val meshEngine: MeshDiscoveryEngine
) {
    companion object {
        private const val TAG = "PTP_Engine"

        @Volatile
        private var INSTANCE: PrecisionTimeProtocol? = null

        fun getInstance(meshEngine: MeshDiscoveryEngine): PrecisionTimeProtocol {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrecisionTimeProtocol(meshEngine).also { INSTANCE = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null
    private var isRunning = false

    private val _isSynchronized = MutableStateFlow(false)
    val isSynchronized: StateFlow<Boolean> = _isSynchronized.asStateFlow()

    private val _rttMs = MutableStateFlow(0f)
    val rttMs: StateFlow<Float> = _rttMs.asStateFlow()

    private val _offsetMs = MutableStateFlow(0f)
    val offsetMs: StateFlow<Float> = _offsetMs.asStateFlow()

    /** Live clock drift in NANOSECONDS (μs-resolution acoustic gauge source). */
    private val _clockDriftNanos = MutableStateFlow(0L)
    val clockDriftNanos: StateFlow<Long> = _clockDriftNanos.asStateFlow()

    init {
        // Continuously listen to raw UDP packets received by MeshDiscoveryEngine
        // (fallback transport).
        scope.launch {
            meshEngine.rawUdpPackets.collect { packet ->
                runCatching { handleIncomingPtpPacket(packet) }
            }
        }
    }

    fun startClockAlignment(targetIp: InetAddress, isHost: Boolean) {
        isRunning = true
        pingJob?.cancel()

        if (!isHost) {
            // Client initiates PTP 10Hz sync with Host (fallback UDP transport)
            pingJob = scope.launch {
                val sendBuffer = ByteArray(16)
                while (isActive && isRunning) {
                    try {
                        val t0 = System.nanoTime()
                        ByteBuffer.wrap(sendBuffer, 0, 8).putLong(t0)
                        ByteBuffer.wrap(sendBuffer, 8, 8).putLong(0L) // reserved

                        meshEngine.sendUdpPacket(sendBuffer, targetIp)
                    } catch (e: Exception) {
                        Log.w(TAG, "PTP send failure", e)
                    }
                    delay(100) // 10Hz Ping-Pong loop (100ms)
                }
            }
        }
    }

    private fun handleIncomingPtpPacket(packet: DatagramPacket) {
        val len = packet.length
        val data = packet.data

        if (len == 16) {
            // 1. Host received request from client (contains t0). Host replies with (t0, t1, t2)
            val t0 = ByteBuffer.wrap(data, 0, 8).long
            val t1 = System.nanoTime()

            val replyBuffer = ByteArray(24)
            ByteBuffer.wrap(replyBuffer, 0, 8).putLong(t0)
            ByteBuffer.wrap(replyBuffer, 8, 8).putLong(t1)
            val t2 = System.nanoTime()
            ByteBuffer.wrap(replyBuffer, 16, 8).putLong(t2)

            meshEngine.sendUdpPacket(replyBuffer, packet.address, packet.port)
        } else if (len == 24) {
            // 2. Client received response from host (contains t0, t1, t2). Client marks t3.
            val t3 = System.nanoTime()
            val t0 = ByteBuffer.wrap(data, 0, 8).long
            val t1 = ByteBuffer.wrap(data, 8, 8).long
            val t2 = ByteBuffer.wrap(data, 16, 8).long

            applySample(t0, t1, t2, t3)
        }
    }

    /**
     * Feeds one Cristian sample through the native clock filters. Progression:
     * frozen v4 ABI → legacy external → 0 (unsynchronized).
     */
    internal fun applySample(t0: Long, t1: Long, t2: Long, t3: Long) {
        val offsetNanos = try {
            NativeBridge.ptpProcessTimestampsNanos(t0, t1, t2, t3)
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                NativeBridge.processPtpTimestamps(t0, t1, t2, t3)
            } catch (_: Throwable) {
                0L
            }
        }
        val rttNanos = try {
            NativeBridge.getPtpRttNanos()
        } catch (_: Throwable) {
            (t3 - t0).coerceAtLeast(0L)
        }

        _offsetMs.value = offsetNanos / 1_000_000f
        _clockDriftNanos.value = offsetNanos
        _rttMs.value = rttNanos / 1_000_000f
        _isSynchronized.value = true
    }

    /**
     * Returns the atomic synchronized monotonic time in milliseconds.
     */
    fun getSynchronizedClockMs(): Long {
        return try {
            NativeBridge.getSynchronizedClockMs()
        } catch (_: Throwable) {
            SystemClock.elapsedRealtime()
        }
    }

    /**
     * Returns the atomic synchronized monotonic time in NANOSECONDS — the
     * v4 domain for scheduled starts, Death-Pivot math and the mesh wire.
     */
    fun getSynchronizedClockNanos(): Long = NativeBridge.synchronizedClockNanos()

    /**
     * Estimated hardware playout (DAC / A2DP) delay in nanoseconds, used by
     * [ScheduledAudioScheduler] when the native probe is unavailable.
     */
    fun estimatedPlayoutDelayNanos(): Long {
        val nativeNanos = NativeBridge.hardwarePlayoutDelayNanos()
        if (nativeNanos >= 0L) return nativeNanos
        return 18_000_000L // conservative built-in speaker default
    }

    fun stop() {
        isRunning = false
        pingJob?.cancel()
        runCatching { NativeBridge.resetPtpState() }
        runCatching { NativeBridge.ptpReset() }
        _isSynchronized.value = false
    }
}
