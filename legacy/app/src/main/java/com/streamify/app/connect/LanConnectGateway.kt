package com.streamify.app.connect

import com.streamify.app.util.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LAN/Cloud Connect transport (Phase 4).
 *
 * Posts one JSON command frame per [ConnectCommand] to the sink's
 * advertised control endpoint over OkHttp on Dispatchers.IO. The endpoint
 * map comes from device discovery metadata (LAN advertisement or cloud
 * presence row); unknown endpoints make [send] decline so the command
 * executes locally — a missing remote must never swallow a play/pause.
 *
 * NOTE: the rust/ side exposes no Connect RPC in the frozen ABI of this
 * phase, so the LAN transport is OkHttp-native by design. A later
 * `RustConnectGatewayAdapter` can implement [ConnectGateway] over the mesh
 * without a single change above this seam.
 */
class LanConnectGateway(
    private val client: OkHttpClient = defaultClient()
) : ConnectGateway {

    private val _events = MutableSharedFlow<ConnectGatewayEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ConnectGatewayEvent> = _events

    override var boundDeviceId: String? = null
        private set

    /** Device id → base URL of its Connect control endpoint. */
    private val endpoints = mutableMapOf<String, String>()

    fun registerEndpoint(deviceId: String, baseUrl: String) {
        endpoints[deviceId] = baseUrl.trimEnd('/')
    }

    override suspend fun connect(device: ConnectDevice, snapshot: PlaybackSnapshot): Boolean = withContext(Dispatchers.IO) {
        if (device.isLocal) return@withContext true
        val base = endpoints[device.id] ?: return@withContext false
        val body = JSONObject()
            .put("deviceId", device.id)
            .put("positionMs", snapshot.positionMs)
            .put("currentIndex", snapshot.currentIndex)
            .put("play", snapshot.isPlaying)
            .put("volume", snapshot.volume.toDouble())
            .put("queue", JSONObject(snapshot.queueTitles.mapIndexed { i, t -> "q$i" to t }.toMap()))
        val ok = post("$base/connect-session", body.toString())
        if (ok) boundDeviceId = device.id
        ok
    }

    override suspend fun disconnect() {
        val id = boundDeviceId ?: return
        val base = endpoints[id]
        boundDeviceId = null
        if (base != null) {
            withContext(Dispatchers.IO) {
                runCatching { post("$base/connect-session/leave", "{}") }
            }
        }
        _events.tryEmit(ConnectGatewayEvent.Disconnected)
    }

    override suspend fun send(command: ConnectCommand): Boolean {
        val id = boundDeviceId ?: return false
        val base = endpoints[id] ?: return false
        return withContext(Dispatchers.IO) {
            post("$base/connect-command", ConnectCommandCodec.encode(command))
        }
    }

    private fun post(url: String, body: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response: Response ->
            response.isSuccessful
        }
    }.getOrElse { t ->
        SLog.st("LanConnectGateway", "control frame failed url=$url", t)
        false
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

/**
 * Latency probe seam — the registry measures RTT per discovered target so
 * the picker can badge "12 ms" / "230 ms". The default implementation
 * performs a HEAD against the control endpoint; tests inject fakes.
 */
interface LatencyProbe {
    /** Round-trip in ms, or null when the target did not answer. */
    suspend fun ping(device: ConnectDevice): Long?
}

/** Endpoint-aware probe used by the Android runtime wiring. */
class HttpLatencyProbe(private val gateway: LanConnectGateway) : LatencyProbe {
    override suspend fun ping(device: ConnectDevice): Long? {
        if (device.isLocal) return 0L
        val start = System.nanoTime()
        // connect() doubles as the RTT measurement: the handshake frame is
        // exactly the packet whose round-trip a listener cares about.
        val reachable = runCatching {
            gateway.connect(device, PlaybackSnapshot())
        }.getOrDefault(false)
        if (!reachable) return null
        return (System.nanoTime() - start) / 1_000_000L
    }
}
