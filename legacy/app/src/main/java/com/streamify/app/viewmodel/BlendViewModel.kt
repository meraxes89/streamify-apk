package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.discovery.BlendEngine
import com.streamify.app.data.discovery.BlendMember
import com.streamify.app.data.discovery.BlendResult
import com.streamify.app.data.models.Track
import com.streamify.app.data.repository.TrackRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BlendViewModel (Gap #25) — orchestrates the cross-pollinated Blend for
 * the local user + one friend (or Jam peer). All scraper work stays on
 * Dispatchers.IO inside [BlendEngine]; only immutable snapshots cross to
 * Compose.
 */
data class BlendUiState(
    val isLoading: Boolean = true,
    val myName: String = "You",
    val friendName: String = "Friend",
    val friendAvatarUrl: String = "",
    val result: BlendResult? = null
)

class BlendViewModel(
    private val repository: com.streamify.app.data.repository.TrackRepositoryApi = TrackRepository
) : ViewModel() {

    private val _state = MutableStateFlow(BlendUiState())
    val state: StateFlow<BlendUiState> = _state.asStateFlow()

    private var generatedKey: String? = null

    fun generateBlend(friendName: String, friendSeeds: List<String>, friendAvatarUrl: String = "") {
        val key = "$friendName|${friendSeeds.joinToString(",")}"
        if (key == generatedKey && _state.value.result != null) return
        generatedKey = key

        _state.value = _state.value.copy(
            isLoading = true,
            friendName = friendName,
            friendAvatarUrl = friendAvatarUrl,
            result = _state.value.result
        )

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Member A — the local user's taste: top-played artists.
                val topPlayed = runCatching { repository.getTopPlayedTracks(24) }
                    .getOrDefault(emptyList())
                val myArtists = topPlayed
                    .map { it.artist }
                    .filter { it.isNotBlank() && it != "Unknown Artist" }
                    .map { it.trim() }
                    .distinct()
                    .take(3)
                // Offline fallback pool so a cold library still blends.
                val localPool = topPlayed.take(12).map {
                    com.streamify.app.data.discovery.BlendCandidate(
                        videoId = it.ytmVideoId ?: "",
                        title = it.title,
                        artist = it.artist,
                        durationSec = it.durationSec,
                        thumbnailUrl = it.coverArtPath ?: ""
                    )
                }.filter { it.title.isNotBlank() && it.artist.isNotBlank() }

                runCatching {
                    BlendEngine.generateBlend(
                        members = listOf(
                            BlendMember(
                                name = "You",
                                seedArtists = myArtists,
                                localPool = localPool
                            ),
                            BlendMember(
                                name = friendName,
                                avatarUrl = friendAvatarUrl,
                                seedArtists = friendSeeds
                            )
                        )
                    )
                }.getOrNull()
            }
            _state.value = _state.value.copy(isLoading = false, result = result)
        }
    }

    /** The blend rows as a playable queue. */
    fun playableQueue(): List<Track> =
        _state.value.result?.tracks?.map { it.toTrack() } ?: emptyList()
}
