package com.streamify.app.connect

import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject

/**
 * ConnectGateway — the transport seam between the session coordinator and
 * a remote sink (Gap #52 "Remote Controller Mode").
 *
 * The Phase-4 app layer speaks this interface ONLY. The production LAN
 * implementation ([LanConnectGateway]) posts JSON command frames over
 * OkHttp to an advertised `/connect-command` endpoint on the sink; the
 * Rust mesh transport (rust/ module, frozen ABI in this phase) plugs in
 * behind the SAME interface in a later phase via a
 * `RustConnectGatewayAdapter` without touching any call site above it.
 */
interface ConnectGateway {
    /** Target this gateway is bound to after [connect] succeeds. */
    val boundDeviceId: String?

    /**
     * Open the control channel to [device] and hand the live session over:
     * the sink must load [snapshot]'s queue at [PlaybackSnapshot.positionMs]
     * and honor its play intent.
     * @return true when the sink accepted the session handoff.
     */
    suspend fun connect(device: ConnectDevice, snapshot: PlaybackSnapshot): Boolean

    /** Gracefully close the control channel (sink keeps rendering). */
    suspend fun disconnect()

    /**
     * Forward one controller command to the sink.
     * @return true when the sink consumed it; false = "execute locally".
     */
    suspend fun send(command: ConnectCommand): Boolean

    /** Server-sent events from the sink (position heartbeats, drops). */
    val events: SharedFlow<ConnectGatewayEvent>
}

/**
 * Gateway for the phone itself. `connect` trivially succeeds and `send`
 * always declines — every command is meant for the LOCAL player, which the
 * caller executes directly. This keeps the coordinator loop branch-free.
 */
class LoopbackConnectGateway : ConnectGateway {
    override val boundDeviceId: String? = ConnectDevice.LOCAL_DEVICE_ID
    override val events: SharedFlow<ConnectGatewayEvent> = kotlinx.coroutines.flow.MutableSharedFlow()

    override suspend fun connect(device: ConnectDevice, snapshot: PlaybackSnapshot): Boolean = device.isLocal

    override suspend fun disconnect() {}

    override suspend fun send(command: ConnectCommand): Boolean = false
}

/**
 * Pure command ↔ JSON wire codec (shared by the LAN gateway and tests).
 * Kept separate from the HTTP client so the JVM shard can round-trip every
 * command shape without a network stack.
 */
object ConnectCommandCodec {

    fun encode(command: ConnectCommand): String {
        val json = JSONObject()
        when (command) {
            is ConnectCommand.PlayPause -> json.put("op", "playPause").put("play", command.play)
            is ConnectCommand.Seek -> json.put("op", "seek").put("positionMs", command.positionMs)
            is ConnectCommand.Scrub -> json.put("op", "scrub").put("positionMs", command.positionMs)
            is ConnectCommand.SetVolume -> json.put("op", "setVolume").put("volume", command.volume)
            is ConnectCommand.QueueReorder ->
                json.put("op", "queueReorder")
                    .put("fromIndex", command.fromIndex)
                    .put("toIndex", command.toIndex)
            ConnectCommand.SkipNext -> json.put("op", "skipNext")
            ConnectCommand.SkipPrevious -> json.put("op", "skipPrevious")
        }
        return json.toString()
    }

    fun decode(raw: String): ConnectCommand? = runCatching {
        val json = JSONObject(raw)
        when (json.optString("op")) {
            "playPause" -> ConnectCommand.PlayPause(json.optBoolean("play"))
            "seek" -> ConnectCommand.Seek(json.optLong("positionMs"))
            "scrub" -> ConnectCommand.Scrub(json.optLong("positionMs"))
            "setVolume" -> ConnectCommand.SetVolume(json.optDouble("volume").toFloat().coerceIn(0f, 1f))
            "queueReorder" -> ConnectCommand.QueueReorder(json.optInt("fromIndex"), json.optInt("toIndex"))
            "skipNext" -> ConnectCommand.SkipNext
            "skipPrevious" -> ConnectCommand.SkipPrevious
            else -> null
        }
    }.getOrNull()
}
