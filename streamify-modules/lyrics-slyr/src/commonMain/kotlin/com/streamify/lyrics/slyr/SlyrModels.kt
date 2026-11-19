package com.streamify.lyrics.slyr

import kotlinx.serialization.Serializable

@Serializable
data class Syllable(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

@Serializable
data class LyricsLine(
    val timeMs: Long,
    val text: String,
    val syllables: List<Syllable> = emptyList(),
    val durationMs: Long = 0L
)

@Serializable
data class LyricsData(
    val lines: List<LyricsLine>,
    val isSynced: Boolean = true,
    val globalOffsetMs: Long = 0L
)
