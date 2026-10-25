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

enum class PlaybackButtonState {
    BUFFERING,
    PLAYING,
    PAUSED
}

/**
 * [Immutable]: holds only immutable vals — [Track] is itself @Immutable and
 * the queue list is replaced wholesale (copy-on-write) by the viewmodel, never
 * mutated in place. The Compose compiler treats this state as immutable, so
 * every composable taking it (or a [Track] from it) becomes skippable and
 * parent containers never recompose just because a field advanced.
 */
@androidx.compose.runtime.Immutable
data class PlayerState(
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isShuffleActive: Boolean = false,
    val isRepeatActive: Boolean = false,
    val sleepTimerMinutesLeft: Int? = null,
    val sleepTimerEndTrack: Boolean = false,
    val isAutoPlayEnabled: Boolean = true,
    val isVideoMode: Boolean = false,
    /** Last fatal playback/resolution error for UI surfaces. Null = healthy. */
    val lastError: String? = null
) {
    val buttonState: PlaybackButtonState
        get() = when {
            isBuffering -> PlaybackButtonState.BUFFERING
            isPlaying -> PlaybackButtonState.PLAYING
            else -> PlaybackButtonState.PAUSED
        }
}
