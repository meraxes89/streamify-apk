package com.streamify.app.viewmodel

import com.streamify.app.util.SLog
import android.content.ComponentName
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.streamify.app.data.NativeBridge
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.models.Track
import com.streamify.app.media.playback.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/** Controller wiring, saved-state restore, current-item hydration and
 * single-owner lyric fetching. */
internal fun PlayerViewModel.setupController(context: Context) {
    val ctrl = controller ?: return
    
    com.streamify.app.media.playback.PlaybackService.onSeekNextListener = {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            advanceQueue(isUserSkip = true)
        }
    }
    com.streamify.app.media.playback.PlaybackService.onSeekPrevListener = {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            skipPrevious()
        }
    }

    ctrl.removeListener(playerListener)
    ctrl.addListener(playerListener)
}

internal fun PlayerViewModel.restorePlayerState(context: Context) {
    val prefs = context.getSharedPreferences("player_state", Context.MODE_PRIVATE)
    val queueStr = prefs.getString("saved_queue", "") ?: ""
    if (queueStr.isNotEmpty()) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ids = queueStr.split(",").mapNotNull { it.toIntOrNull() }
            if (ids.isNotEmpty()) {
                val tracks = repository.getTracksByIds(ids)
                if (tracks.isNotEmpty()) {
                    val currentId = prefs.getInt("saved_current_id", -1)
                    val currentTrack = tracks.find { it.id == currentId } ?: tracks.first()
                    val position = prefs.getLong("saved_position", 0L)
                    val targetIndex = tracks.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        // Restore UI state only in PAUSED mode (do not auto-play on app launch)
                        _playerState.value = _playerState.value.copy(
                            currentTrack = currentTrack,
                            currentIndex = targetIndex,
                            queue = tracks,
                            isPlaying = false,
                            isBuffering = false,
                            currentPosition = position,
                            duration = (currentTrack.durationSec * 1000L).coerceAtLeast(0L)
                        )
                        _positionMs.value = position.coerceAtLeast(0L)
                        _durationMs.value = (currentTrack.durationSec * 1000L).coerceAtLeast(0L)
                    }
                }
            }
        }
    }

    // Reactive One-Shot Lookahead Trigger (Fires strictly once at >=75% progress or <=30s remaining)
    viewModelScope.launch {
        _playerState
            .map { state ->
                val dur = state.duration
                val pos = state.currentPosition
                state.isPlaying && dur > 0L && (pos.toFloat() / dur.toFloat() >= 0.75f || (dur - pos) <= 30000L)
            }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                val curState = _playerState.value
                val currentIdx = controller?.currentMediaItemIndex ?: curState.currentIndex
                preResolveLookaheadTrack(currentIdx, curState.queue)
            }
    }
}

internal fun PlayerViewModel.updateCurrentTrackFromMediaItem(mediaItem: MediaItem?) {
    if (mediaItem == null) return
    
    val mediaId = mediaItem.mediaId
    val metaTitle = mediaItem.mediaMetadata.title?.toString()
    val metaArtist = mediaItem.mediaMetadata.artist?.toString()

    // Match against current logical queue using stable identifier or metadata
    val track = _playerState.value.queue.find { 
        (it.id != 0 && it.id.toString() == mediaId) ||
        "trk_${kotlin.math.abs((it.title.trim().lowercase() + "_" + it.artist.trim().lowercase()).hashCode())}" == mediaId ||
        (metaTitle != null && metaArtist != null && it.title.equals(metaTitle, ignoreCase = true) && it.artist.equals(metaArtist, ignoreCase = true)) ||
        (metaTitle != null && it.title.equals(metaTitle, ignoreCase = true))
    }

    if (track != null) {
        _playerState.value = _playerState.value.copy(
            currentTrack = track,
            duration = if (track.durationSec > 0) track.durationSec * 1000L else _playerState.value.duration
        )
    }
}

internal fun PlayerViewModel.maybeFetchLyricsForTrack(playingTrack: Track?) {
    if (playingTrack == null) return
    if (!playingTrack.lyricsPath.isNullOrBlank()) return

    val vid = playingTrack.ytmVideoId
        ?: com.streamify.app.data.network.YouTubeStreamResolver.extractVideoId(
            playingTrack.filepath,
            playingTrack.coverArtPath
        )

    val attemptKey = "${playingTrack.id}:${vid ?: ""}"
    if (!lyricsFetchAttempts.add(attemptKey)) return

    viewModelScope.launch(Dispatchers.IO) {
        try {
            val lyricsText = com.streamify.app.data.network.LyricsResolver.fetchSyncedLyrics(
                title = playingTrack.title,
                artist = playingTrack.artist,
                durationSec = playingTrack.durationSec,
                videoId = vid ?: ""
            ) ?: ""

            if (lyricsText.isNotBlank() && (lyricsText.contains("[") || lyricsText.length > 40)) {
                var storedPath: String? = null

                // 1. Canonical app-private cache — authoritative source for all UI surfaces
                val ctx = appContext
                if (ctx != null) {
                    try {
                        val cachedFile = com.streamify.app.data.lyrics.LyricsCacheManager.getCachedLyricsFile(
                            ctx, playingTrack.title, playingTrack.artist
                        )
                        cachedFile.writeText(lyricsText)
                        storedPath = cachedFile.absolutePath
                    } catch (e: Exception) {
                        SLog.st("PlayerViewModel", "PlayerViewModel.maybeFetchLyricsForTrack failed", e)
                    }
                }

                // 2. Optional external mirror for user-accessible .lrc files
                try {
                    val lyricsDir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        ".Streamify/lyrics"
                    )
                    if (!lyricsDir.exists()) lyricsDir.mkdirs()
                    val lrcFile = java.io.File(lyricsDir, "${playingTrack.id}.lrc")
                    lrcFile.writeText(lyricsText)
                    if (storedPath == null) storedPath = lrcFile.absolutePath
                } catch (_: Exception) {
                }

                if (storedPath != null) {
                    withContext(Dispatchers.Main) {
                        val cur = _playerState.value.currentTrack
                        if (cur != null && cur.id == playingTrack.id && cur.lyricsPath.isNullOrBlank()) {
                            _playerState.value = _playerState.value.copy(
                                currentTrack = cur.copy(lyricsPath = storedPath)
                            )
                        }
                    }
                }
            } else {
                // Nothing usable found: clear the attempt so a later transition can retry
                lyricsFetchAttempts.remove(attemptKey)
            }
        } catch (e: Exception) {
            SLog.st("PlayerViewModel", "PlayerViewModel.maybeFetchLyricsForTrack failed", e)
            lyricsFetchAttempts.remove(attemptKey)
        }
    }
}
