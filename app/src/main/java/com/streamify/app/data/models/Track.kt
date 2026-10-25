package com.streamify.app.data.models

@androidx.compose.runtime.Stable
data class TrackNative(
    val id: Int,
    val filepath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val bpm: Float,
    val key: String,
    val vectorOffset: Int,
    val coverArtPath: String,
    val lyricsPath: String,
    val source: String,
    val isProcessed: Int,
    val downloadQuality: String
)

/**
 * [Immutable]: every field is a val of an immutable type (String / Int /
 * Float / Boolean / nullable String) and instances are replaced, never
 * mutated. The Compose compiler can therefore treat [Track] as an immutable
 * value and mark every composable that only takes a [Track] as skippable —
 * the foundation of the 120Hz zero-jank recomposition architecture.
 */
@androidx.compose.runtime.Immutable
data class Track(
    val id: Int = 0,
    val filepath: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val bpm: Float = 0f,
    val key: String = "",
    val coverArtPath: String? = null,
    val lyricsPath: String? = null,
    val source: String = "local",
    val isLiked: Boolean = false,
    val isProcessed: Boolean = false,
    val genre: String = "",
    val playCount: Int = 0,
    val isrc: String? = null,
    val ytmVideoId: String? = null
) {
    val filePath: String get() = filepath
}

fun TrackNative.toTrack() = Track(
    id = id,
    filepath = filepath,
    title = title,
    artist = artist,
    album = album,
    durationSec = durationSec,
    bpm = bpm,
    key = key,
    coverArtPath = coverArtPath.ifBlank { null },
    lyricsPath = lyricsPath.ifBlank { null },
    source = source,
    isProcessed = isProcessed == 1
)
