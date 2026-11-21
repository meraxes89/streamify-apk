package com.streamify.app.data.repository
import com.streamify.app.data.models.Track
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the track catalog.
 *
 * ViewModels depend on this interface instead of the [TrackRepository]
 * singleton, giving tests an in-memory fake and future migrations (Room,
 * remote-first catalog) a single swap point. The production object
 * remains a process-wide singleton wired by AppGraph.
 *
 * Scope note: this interface intentionally covers the members consumed
 * by ViewModels. Screens and workers may keep referencing the concrete
 * [TrackRepository] object for index flows and maintenance operations.
 */
interface TrackRepositoryApi {

    val allTracks: StateFlow<List<Track>>

    suspend fun refresh(): List<Track>

    suspend fun getTracksByIds(ids: List<Int>): List<Track>

    suspend fun searchTracks(query: String): List<Track>

    fun isTrackLiked(track: Track): Boolean

    fun hydrateTrack(track: Track): Track

    suspend fun registerStreamedTrack(
        track: Track,
        context: android.content.Context? = null,
        addToDefaultPlaylist: Boolean = false
    ): Track

    suspend fun toggleLike(trackId: Int, userId: Int = 1, track: Track? = null): Boolean

    suspend fun logPlayEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1)

    suspend fun logSkipEvent(fromTrackId: Int, toTrackId: Int, userId: Int = 1)

    suspend fun recordTrackPlay(trackId: Int): Boolean

    suspend fun getTopPlayedTracks(limit: Int = 20): List<Track>

    // ── Gap #27: Taste Profile controls ────────────────────────────────
    // Tracks/playlists flagged excludedFromTaste stop feeding the radio
    // seed pool and telemetry logging (play/skip events, session vectors,
    // engagement + hook telemetry, top-played & recommendation surfaces).

    fun setTrackExcludedFromTaste(track: com.streamify.app.data.models.Track, excluded: Boolean)

    fun isTrackExcludedFromTaste(track: com.streamify.app.data.models.Track): Boolean

    fun setPlaylistExcludedFromTaste(playlistName: String, excluded: Boolean)

    fun isPlaylistExcludedFromTaste(playlistName: String): Boolean

    suspend fun updateSessionVector(trackId: Int, alpha: Float = 0.45f)

    suspend fun getSessionRecommendations(limit: Int = 50): List<Track>

    suspend fun getLongTermRecommendations(userId: Int = 1, limit: Int = 50): List<Track>

    suspend fun getCircadianRecommendations(hourOfDay: Int, limit: Int = 20): List<Track>

    fun getCircadianSlot(hourOfDay: Int): String

    suspend fun getEmergencyComfortTrack(): Track?
}
