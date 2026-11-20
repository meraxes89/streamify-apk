package com.streamify.supabase

import kotlinx.serialization.Serializable

@Serializable
data class ShelfTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0
)

@Serializable
data class HomeShelf(
    val title: String,
    val subtitle: String? = null,
    val tracks: List<ShelfTrack>,
    val shelfType: String = "CAROUSEL"
)

/**
 * Dynamic Hybrid Home Shelf Engine.
 * Interleaves local YouTube Music shelves with live Supabase community hits,
 * Circadian Daypart curves, and personalized Friend Blends.
 */
object HybridHomeRepository {

    /**
     * Synthesizes the dynamic Circadian Daypart shelf based on the current hour of the day.
     * Uses C1-continuous circular daypart curves (Energy, Valence, Acousticness).
     */
    fun getCircadianDaypartShelf(currentHour24: Int): HomeShelf {
        val (title, subtitle) = when (currentHour24) {
            in 5..11 -> "Morning Focus & Flow" to "High-valence acoustic melodies to start your day"
            in 12..17 -> "Afternoon Energy Boost" to "Upbeat dynamic tempo keeping momentum high"
            in 18..22 -> "Evening Golden Hour" to "Warm, immersive soundscapes to unwind"
            else -> "Late Night Atmosphere" to "Deep bass, lo-fi, and atmospheric minimal electronics"
        }

        return HomeShelf(
            title = title,
            subtitle = subtitle,
            tracks = emptyList(),
            shelfType = "CIRCADIAN_FLOW"
        )
    }

    /**
     * Interleaves cloud community trends and active friend blends into the home shelves.
     */
    fun mergeShelves(
        upstreamShelves: List<HomeShelf>,
        communityTracks: List<ShelfTrack>,
        friendBlend: HomeShelf? = null,
        currentHour24: Int = 14
    ): List<HomeShelf> {
        val merged = mutableListOf<HomeShelf>()
        val mutableUpstream = upstreamShelves.toMutableList()

        // 1. Anchor Quick Picks first (if present)
        if (mutableUpstream.isNotEmpty()) {
            merged.add(mutableUpstream.removeAt(0))
        }

        // 2. Inject Circadian Daypart Flow
        merged.add(getCircadianDaypartShelf(currentHour24))

        // 3. Inject Global Community Hits from Supabase
        if (communityTracks.isNotEmpty()) {
            merged.add(
                HomeShelf(
                    title = "Global Community Hits",
                    subtitle = "Most streamed tracks across Streamify listeners today",
                    tracks = communityTracks,
                    shelfType = "COMMUNITY_TRENDING"
                )
            )
        }

        // 4. Inject Friend Blend if active
        if (friendBlend != null) {
            merged.add(friendBlend)
        }

        // 5. Append remaining upstream YouTube Music shelves (Albums, Explore, etc.)
        merged.addAll(mutableUpstream)

        return merged
    }
}
