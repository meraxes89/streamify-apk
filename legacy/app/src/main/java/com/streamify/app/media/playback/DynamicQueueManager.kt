package com.streamify.app.media.playback
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.spotify.SpotifyAuthManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class DynamicQueueManager(
    private val context: Context,
    private val exoPlayer: ExoPlayer
) : Player.Listener {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val isFetching = AtomicBoolean(false)
    private var lastQueuedCadId: String? = null
    private var lookaheadJob: Job? = null

    private val dbPath: String
        get() = context.getDatabasePath("streamify_universal.db").absolutePath

    init {
        exoPlayer.addListener(this)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int
    ) {
        checkAndQueueNextTrack()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            checkAndQueueNextTrack()
        }
    }

    fun checkAndQueueNextTrack() {
        val currentMediaItem = exoPlayer.currentMediaItem ?: return
        val currentDuration = exoPlayer.duration
        val currentPosition = exoPlayer.currentPosition

        // If within 5 seconds of the end and lookahead slot is empty
        if (currentDuration > 0 && (currentDuration - currentPosition) <= 6000) {
            if (exoPlayer.mediaItemCount < 2 && isFetching.compareAndSet(false, true)) {
                fetchAndAppendNextTrack(currentMediaItem.mediaId)
            }
        }
    }

    private fun fetchAndAppendNextTrack(currentCadId: String) {
        lookaheadJob?.cancel()
        lookaheadJob = scope.launch(Dispatchers.IO) {
            try {
                val isShuffle = withContext(Dispatchers.Main) { exoPlayer.shuffleModeEnabled }
                val nextTrack = NativeBridge.getNextTrack(dbPath, currentCadId, isShuffle)

                if (nextTrack != null) {
                    val (nextCadId, nextVideoId) = nextTrack
                    if (nextCadId.isNotBlank() && nextCadId != lastQueuedCadId) {
                        var videoId = nextVideoId.ifBlank { null }
                        if (videoId.isNullOrBlank()) {
                            val authHeader = SpotifyAuthManager(context).getYtAuthHeader() ?: ""
                            videoId = NativeBridge.resolveTrack(dbPath, nextCadId, null, "", "", authHeader)
                        }

                        val cdnUrl = if (!videoId.isNullOrBlank()) {
                            com.streamify.app.data.network.YouTubeStreamResolver.resolveStreamUrl(videoId)?.streamUrl
                        } else null

                        if (!cdnUrl.isNullOrBlank()) {
                            val nextMediaItem = MediaItem.Builder()
                                .setMediaId(nextCadId)
                                .setUri(cdnUrl)
                                .build()

                            withContext(Dispatchers.Main) {
                                if (exoPlayer.mediaItemCount < 2) {
                                    exoPlayer.addMediaItem(nextMediaItem)
                                    lastQueuedCadId = nextCadId
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore non-fatal lookahead error
            } finally {
                isFetching.set(false)
            }
        }
    }

    fun release() {
        lookaheadJob?.cancel()
        exoPlayer.removeListener(this)
    }
}
