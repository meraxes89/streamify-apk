package com.maxrave.simpmusic.data.scraper

data class ScrapedTrack(
    val title: String,
    val artist: String,
    val videoId: String = "",
    val thumbnailUrl: String? = null,
    val durationSec: Int = 0,
)

data class ScrapedPlaylist(
    val name: String,
    val tracks: List<ScrapedTrack>,
    val source: PlaylistSource = PlaylistSource.UNKNOWN,
)

enum class PlaylistSource {
    YOUTUBE,
    SPOTIFY,
    APPLE_MUSIC,
    UNKNOWN,
}
