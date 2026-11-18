package com.streamify.app.ui.models

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class VirtualShelfTrack(
    val cadId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String,
    val durationSec: Int,
    val isrc: String? = null,
    val ytmVideoId: String? = null,
    val isLiked: Boolean = false,
    val platformOrigin: String = "UNIFIED" // "SPOTIFY", "YTM", "UNIFIED"
)

@Immutable
data class VirtualShelf(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<VirtualShelfTrack>
)

@Immutable
data class AmbientPalette(
    val dominantColor: Color = Color(0xFF121212),
    val accentColor: Color = Color(0xFF1DB954),
    val darkMutedColor: Color = Color(0xFF080808)
)
