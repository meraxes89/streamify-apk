package com.streamify.app.ui.models

data class VirtualShelfTrack(
    val cadId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val durationSec: Int = 0,
    val isrc: String? = null,
    val ytmVideoId: String? = null,
    val isLiked: Boolean = false,
    val platformOrigin: String = "SPOTIFY"
)
