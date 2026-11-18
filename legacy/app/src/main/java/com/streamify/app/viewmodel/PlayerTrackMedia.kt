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

/** Pure media-item helpers (no ViewModel state). */

internal fun isCdnExpired(url: String): Boolean {
    try {
        val match = Regex("[?&]expire=([0-9]+)").find(url)
        if (match != null) {
            val expireSec = match.groupValues[1].toLongOrNull() ?: return false
            val nowSec = System.currentTimeMillis() / 1000L
            return nowSec >= (expireSec - 300L) // Expired if within 5 min of expiry
        }
    } catch (e: Exception) {
        // ignore parsing error
    }
    return false
}

internal fun buildMediaItem(t: Track): MediaItem {
    val uri = if (t.filepath.startsWith("http://") || t.filepath.startsWith("https://")) {
        android.net.Uri.parse(t.filepath)
    } else if (t.filepath.startsWith("file://")) {
        android.net.Uri.parse(t.filepath)
    } else if (t.filepath.isNotBlank() && !t.filepath.startsWith("online://") && !t.filepath.startsWith("ytsearch:")) {
        android.net.Uri.fromFile(java.io.File(t.filepath))
    } else {
        android.net.Uri.EMPTY
    }

    val stableMediaId = if (t.id != 0) {
        t.id.toString()
    } else {
        "trk_${kotlin.math.abs((t.title.trim().lowercase() + "_" + t.artist.trim().lowercase()).hashCode())}"
    }

    return MediaItem.Builder()
        .setMediaId(stableMediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .setAlbumTitle(t.album)
                .setArtworkUri(if (!t.coverArtPath.isNullOrBlank()) {
                    if (t.coverArtPath.startsWith("http") || t.coverArtPath.startsWith("file")) {
                        android.net.Uri.parse(t.coverArtPath)
                    } else {
                        android.net.Uri.fromFile(java.io.File(t.coverArtPath))
                    }
                } else null)
                .build()
        )
        .build()
}
