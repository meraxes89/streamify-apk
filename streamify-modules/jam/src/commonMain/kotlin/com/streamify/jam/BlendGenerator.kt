package com.streamify.jam

import kotlinx.serialization.Serializable

@Serializable
data class UserTasteProfile(
    val userId: String,
    val username: String,
    val topArtists: List<String>,
    val topGenres: List<String>,
    val favoriteTracks: List<JamTrack>
)

@Serializable
data class BlendResult(
    val blendTitle: String,
    val matchScorePercent: Int,
    val sharedArtists: List<String>,
    val playlistTracks: List<JamTrack>
)

/**
 * Multi-User Taste Profiler & Dynamic Blend Generator.
 * Compares two users' listening profiles and synthesizes a dynamically combined playlist.
 */
object BlendGenerator {

    fun generateBlend(userA: UserTasteProfile, userB: UserTasteProfile): BlendResult {
        val artistsA = userA.topArtists.map { it.lowercase() }.toSet()
        val artistsB = userB.topArtists.map { it.lowercase() }.toSet()

        val shared = artistsA.intersect(artistsB)
        val union = artistsA.union(artistsB)

        // Jaccard similarity index on artist affinity
        val jaccard = if (union.isEmpty()) 0.5f else shared.size.toFloat() / union.size.toFloat()
        val score = (jaccard * 60f + 40f).coerceIn(45f, 98f).toInt()

        // Interleave tracks fairly from both users
        val combinedTracks = mutableListOf<JamTrack>()
        val maxLen = maxOf(userA.favoriteTracks.size, userB.favoriteTracks.size)

        for (i in 0 until maxLen) {
            if (i < userA.favoriteTracks.size) combinedTracks.add(userA.favoriteTracks[i])
            if (i < userB.favoriteTracks.size) combinedTracks.add(userB.favoriteTracks[i])
        }

        // Deduplicate
        val deduped = combinedTracks.distinctBy { it.videoId }

        return BlendResult(
            blendTitle = "${userA.username} + ${userB.username} Blend",
            matchScorePercent = score,
            sharedArtists = shared.map { it.replaceFirstChar(Char::titlecase) }.toList(),
            playlistTracks = deduped
        )
    }
}
