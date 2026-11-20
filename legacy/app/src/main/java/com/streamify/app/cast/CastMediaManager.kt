package com.streamify.app.cast

import android.content.Context
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.streamify.app.connect.ConnectCommand
import com.streamify.app.connect.ConnectDevice
import com.streamify.app.connect.ConnectGateway
import com.streamify.app.connect.ConnectGatewayEvent
import com.streamify.app.connect.ConnectVolumePolicy
import com.streamify.app.connect.PlaybackSnapshot
import com.streamify.app.util.SLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Live queue fingerprint handed to [CastMediaManager] at transfer time.
 * The CastPlayer needs FULL MediaItems (playback URI + metadata + art);
 * the coordinator snapshot only carries the position/index fingerprint.
 */
data class CastPlaybackState(
    val items: List<MediaItem>,
    val startIndex: Int,
    val positionMs: Long,
    val isPlaying: Boolean
)

/**
 * CastMediaManager (Gap #53) — Media3 Cast extension session binding.
 *
 * Responsibilities:
 *  • Owns the process-wide [CastPlayer] over the shared CastContext.
 *  • Publishes the Cast route into ConnectRuntime's device registry.
 *  • Implements the [ConnectGateway] seam so the Connect coordinator can
 *    transfer playback to a receiver exactly like any other sink:
 *    metadata pass-through, album-art serialization (artworkUri) and
 *    stream-URL handover all ride the standard MediaItem list.
 *  • Route-aware volume sync (Gap #16): receiver volume is only mirrored
 *    while the active route's ACL allows a shared slider.
 *
 * Everything framework-touching is defensive: devices without Play
 * Services (or with Cast disabled) get a silent no-op manager instead of
 * a crash at startup.
 */
object CastMediaManager {

    @Volatile
    private var castContext: CastContext? = null

    @Volatile
    private var castPlayer: CastPlayer? = null

    @Volatile
    var routePhase: CastRoutePhase = CastRoutePhase.NO_DEVICES
        private set

    @Volatile
    var receiverName: String? = null
        private set

    /** Wired by the app shell: full media items of the live session. */
    @Volatile
    var queueProvider: (() -> CastPlaybackState?)? = null

    private val _events = MutableSharedFlow<ConnectGatewayEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ConnectGatewayEvent> = _events

    /**
     * Wake the Cast stack. Idempotent; safe on non-Play-Services hardware
     * (returns quietly with routePhase = NO_DEVICES).
     */
    fun initialize(context: Context) {
        runCatching {
            val ctx = CastContext.getSharedInstance(context.applicationContext)
            castContext = ctx
            ctx.addCastStateListener { state -> onCastStateChanged(state) }
            onCastStateChanged(ctx.castState)

            val player = CastPlayer(ctx)
            player.addListener(playerListener)
            castPlayer = player

            // Feed the Connect registry + gateway holder.
            com.streamify.app.connect.ConnectRuntime.castRouteProvider =
                CastMediaManager::currentRouteRows
            com.streamify.app.connect.CastConnectGatewayHolder.install(CastConnectGateway())
        }.onFailure { t ->
            SLog.st("CastMediaManager", "Cast unavailable on this device", t)
        }
    }

    private fun currentRouteRows(): List<ConnectDevice> =
        listOfNotNull(StreamifyMediaRouteProvider.castDeviceFor(routePhase, receiverName))

    private fun onCastStateChanged(state: Int) {
        val previous = routePhase
        routePhase = when (state) {
            CastState.NO_DEVICES_AVAILABLE -> CastRoutePhase.NO_DEVICES
            CastState.NOT_CONNECTED -> CastRoutePhase.IDLE
            CastState.CONNECTING -> CastRoutePhase.CONNECTING
            CastState.CONNECTED -> CastRoutePhase.CONNECTED
            else -> CastRoutePhase.IDLE
        }
        receiverName = runCatching {
            castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        }.getOrNull() ?: receiverName

        // Route death while a receiver session was live → the coordinator
        // must fall back to local playback at the last known position.
        if (StreamifyMediaRouteProvider.isRouteDeath(previous, routePhase)) {
            _events.tryEmit(ConnectGatewayEvent.Disconnected)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            castPlayer?.let { p ->
                _events.tryEmit(
                    ConnectGatewayEvent.RemotePosition(p.currentPosition, isPlaying)
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            castPlayer?.let { p ->
                _events.tryEmit(
                    ConnectGatewayEvent.RemotePosition(p.currentPosition, p.isPlaying)
                )
            }
        }

        override fun onVolumeChanged(volume: Float) {
            _events.tryEmit(ConnectGatewayEvent.RemoteVolume(volume))
        }
    }

    /** True when a receiver session can accept a handoff right now. */
    fun isReceiverReady(): Boolean =
        routePhase == CastRoutePhase.CONNECTED || routePhase == CastRoutePhase.TRANSFERRING

    /** Sink-visible disconnect signal (used by the nested gateway impl). */
    private fun emitDisconnectEvent() {
        _events.tryEmit(ConnectGatewayEvent.Disconnected)
    }

    /**
     * Route-aware volume application (Gap #16): the value only reaches the
     * receiver when the active route's ACL grants a shared slider; guests on
     * OS-owned-gain routes are locked out by policy, never by accident.
     */
    fun applyReceiverVolume(volume: Float): Boolean {
        val acl = ConnectVolumePolicy.forRoute(
            com.streamify.app.connect.ConnectRouteKind.CAST_RECEIVER
        )
        if (!acl.remoteSliderShared) return false
        val player = castPlayer ?: return false
        return runCatching {
            player.setVolume(volume.coerceIn(0f, 1f))
            true
        }.getOrDefault(false)
    }

    /**
     * ConnectGateway implementation backed by the CastPlayer. The
     * coordinator drives connect/send/disconnect; the queue handed over at
     * connect time comes from [queueProvider] (full MediaItems with stream
     * URLs + metadata + artwork).
     */
    private class CastConnectGateway : ConnectGateway {

        override val boundDeviceId: String?
            get() = CastMediaManager.castPlayer?.takeIf { CastMediaManager.isReceiverReady() }?.let {
                StreamifyMediaRouteProvider.routeIdFor(CastMediaManager.receiverName ?: "cast")
            }

        override suspend fun connect(device: ConnectDevice, snapshot: PlaybackSnapshot): Boolean {
            val player = CastMediaManager.castPlayer ?: return false
            if (!CastMediaManager.isReceiverReady()) return false
            val live = CastMediaManager.queueProvider?.invoke() ?: return false
            if (live.items.isEmpty()) return false

            return runCatching {
                // Metadata pass-through + album art serialization + stream
                // URL handover all ride the MediaItem list itself; the
                // snapshot decides WHERE on the timeline the receiver starts.
                val startIndex = if (snapshot.currentIndex in live.items.indices) {
                    snapshot.currentIndex
                } else {
                    live.startIndex
                }
                val positionMs = if (snapshot.positionMs >= 0) snapshot.positionMs else live.positionMs
                player.setMediaItems(live.items, startIndex, positionMs)
                player.prepare()
                if (snapshot.isPlaying || live.isPlaying) player.play() else player.pause()
                true
            }.getOrElse { t ->
                SLog.st("CastMediaManager", "cast handoff failed", t)
                false
            }
        }

        override suspend fun disconnect() {
            runCatching {
                CastMediaManager.castPlayer?.let { player ->
                    if (player.isPlaying) player.pause()
                }
                CastMediaManager.castContext?.sessionManager?.endCurrentSession(true)
            }
            CastMediaManager.emitDisconnectEvent()
        }

        override suspend fun send(command: ConnectCommand): Boolean {
            val player = CastMediaManager.castPlayer ?: return false
            if (!CastMediaManager.isReceiverReady()) return false
            return runCatching {
                when (command) {
                    is ConnectCommand.PlayPause -> if (command.play) player.play() else player.pause()
                    is ConnectCommand.Seek -> player.seekTo(command.positionMs)
                    is ConnectCommand.Scrub -> player.seekTo(command.positionMs)
                    is ConnectCommand.SetVolume -> return@runCatching CastMediaManager.applyReceiverVolume(command.volume)
                    is ConnectCommand.QueueReorder -> player.moveMediaItem(command.fromIndex, command.toIndex)
                    ConnectCommand.SkipNext -> player.seekToNextMediaItem()
                    ConnectCommand.SkipPrevious -> player.seekToPreviousMediaItem()
                }
                true
            }.getOrDefault(false)
        }

        override val events: SharedFlow<ConnectGatewayEvent> get() = CastMediaManager.events
    }
}
