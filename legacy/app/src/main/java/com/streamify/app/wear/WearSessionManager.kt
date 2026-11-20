package com.streamify.app.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/**
 * WearNodeTransport — GMS Wearable implementation of the phone↔watch links
 * (Gap #55).
 *
 *  • Now Playing state → DataClient DataMap items (auto-synced, persisted
 *    by the framework while the watch is off-wrist).
 *  • Wrist commands → MessageClient frames decoded by [WearInputHandler].
 *  • Run bundles → ChannelClient file streams into watch storage.
 *
 * All decision logic lives in the pure models/handler/coordinator classes
 * this file feeds — the GMS surface is deliberately thin glue.
 */
object WearSessionManager {

    /** Command router — pure, fed by the GMS message listener below. */
    @Volatile
    var inputHandler: WearInputHandler = WearInputHandler(NoopWearActionSink)

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Ids of watches currently reachable (for Connect device rows). */
    @Volatile
    var connectedNodeIds: List<String> = emptyList()
        private set

    private const val WEAR_CAPABILITY = "streamify_wear_companion"

    fun initialize(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val ctx = appContext ?: return
            Wearable.getMessageClient(ctx).addListener(WearSessionManager::onMessageReceived)
            Wearable.getCapabilityClient(ctx).addListener(
                { _ -> refreshConnectedNodes() },
                WEAR_CAPABILITY
            )
            refreshConnectedNodes()
        }.onFailure { t ->
            SLog.st("WearSessionManager", "wearable stack unavailable", t)
        }

        // Surface the paired watch as a Connect target row while reachable.
        com.streamify.app.connect.ConnectRuntime.wearRouteProvider = {
            if (connectedNodeIds.isNotEmpty()) {
                listOf(
                    com.streamify.app.connect.ConnectDevice(
                        id = "watch-primary",
                        name = "Watch",
                        kind = com.streamify.app.connect.ConnectRouteKind.WATCH,
                        origin = com.streamify.app.connect.DeviceOrigin.LAN_DISCOVERY
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    private fun refreshConnectedNodes() {
        scope.launch {
            runCatching {
                val ctx = appContext ?: return@launch
                val nodes: List<Node> = Wearable.getNodeClient(ctx).connectedNodes.await()
                connectedNodeIds = nodes.map { it.id }
            }
        }
    }

    private fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.COMMANDS) return
        val handled = runCatching {
            inputHandler.handle(event.data)
        }.getOrDefault(false)
        if (!handled) {
            SLog.w("WearSessionManager", "unhandled wrist frame (${event.data.size}B)")
        }
    }

    /**
     * Publish the compact Now Playing snapshot to every connected watch.
     * DataMap items are versioned by the framework — publishing identical
     * states is cheap and idempotent. Jam queue rows ride parallel string
     * arrays (ids / titles / artists / votes) — the most portable DataMap
     * encoding across Wear SDK versions.
     */
    suspend fun publishNowPlaying(state: WearNowPlayingState): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext false
        runCatching {
            val request = PutDataMapRequest.create(WearPaths.NOW_PLAYING).apply {
                dataMap.putString("title", state.trackTitle)
                dataMap.putString("artist", state.artist)
                dataMap.putString("artwork", state.artworkUrl ?: "")
                dataMap.putBoolean("isPlaying", state.isPlaying)
                dataMap.putLong("positionMs", state.positionMs)
                dataMap.putLong("durationMs", state.durationMs)
                dataMap.putFloat("volume", state.volume)
                dataMap.putBoolean("jamActive", state.jamActive)
                dataMap.putLong("updatedAtMs", state.updatedAtMs)
                if (state.jamQueueTop.isNotEmpty()) {
                    dataMap.putStringArray(
                        "jamIds",
                        state.jamQueueTop.map { it.trackId.toString() }.toTypedArray()
                    )
                    dataMap.putStringArray("jamTitles", state.jamQueueTop.map { it.title }.toTypedArray())
                    dataMap.putStringArray("jamArtists", state.jamQueueTop.map { it.artist }.toTypedArray())
                    dataMap.putLongArray("jamVotes", state.jamQueueTop.map { it.votes.toLong() }.toLongArray())
                }
            }
            val dataClient: DataClient = Wearable.getDataClient(ctx)
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
            true
        }.getOrElse { t ->
            SLog.st("WearSessionManager", "publishNowPlaying failed", t)
            false
        }
    }

    /**
     * Stream one compact workout audio file into watch storage over a
     * dedicated channel. Used by [WearRunSyncCoordinator].
     */
    suspend fun pushRunBundleFile(file: File): Boolean = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext false
        val targets = connectedNodeIds
        if (targets.isEmpty()) return@withContext false
        runCatching {
            val channelClient = Wearable.getChannelClient(ctx)
            var anySuccess = false
            for (nodeId in targets) {
                val channel = channelClient.openChannel(nodeId, WearPaths.RUN_SYNC).await()
                channelClient.sendFile(channel, Uri.fromFile(file)).await()
                anySuccess = true
            }
            anySuccess
        }.getOrElse { t ->
            SLog.st("WearSessionManager", "run bundle push failed", t)
            false
        }
    }
}
