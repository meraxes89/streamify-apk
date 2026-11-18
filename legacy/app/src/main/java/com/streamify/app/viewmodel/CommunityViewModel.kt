package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Track
import com.streamify.app.data.supabase.SupabaseClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.streamify.app.data.supabase.TrackComment
import com.streamify.app.data.supabase.FriendActivity
import com.streamify.app.data.supabase.CommunityPlaylist

data class CommunityUiState(
    val communityPlaylists: List<CommunityPlaylist> = emptyList(),
    val friendsActivity: List<FriendActivity> = emptyList(),
    val activeBroadcasts: List<String> = emptyList(),
    val currentTrackComments: List<TrackComment> = emptyList(),
    val isCommentsLoading: Boolean = false,
    /** Gap #34: comment ids the local user has upvoted this session. */
    val likedComments: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class CommunityViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    init {
        loadCommunityFeed()
    }

    fun loadCommunityFeed() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val playlists = SupabaseClient.fetchCommunityPlaylists(limit = 15)
            val friends = SupabaseClient.fetchFriendsActivity()
            val broadcasts = SupabaseClient.fetchActiveBroadcasts()

            _uiState.value = _uiState.value.copy(
                communityPlaylists = playlists,
                friendsActivity = friends,
                activeBroadcasts = broadcasts,
                isLoading = false
            )
        }
    }

    fun loadCommentsForTrack(track: Track?) {
        if (track == null) {
            _uiState.value = _uiState.value.copy(currentTrackComments = emptyList())
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCommentsLoading = true)
            val trackCloudId = "trk_${(track.title + track.artist).hashCode()}"
            val comments = SupabaseClient.fetchTrackComments(trackCloudId)
            _uiState.value = _uiState.value.copy(
                currentTrackComments = comments,
                isCommentsLoading = false
            )
        }
    }

    fun postComment(track: Track?, currentPositionMs: Long, commentText: String, parentId: String? = null, onComplete: (Boolean) -> Unit) {
        if (track == null || commentText.isBlank()) return

        viewModelScope.launch {
            val trackCloudId = "trk_${(track.title + track.artist).hashCode()}"
            val result = SupabaseClient.postTrackComment(trackCloudId, currentPositionMs, commentText.trim(), parentId)
            result.onSuccess { newComment ->
                val updated = (_uiState.value.currentTrackComments + newComment).sortedBy { it.timestampMs }
                _uiState.value = _uiState.value.copy(currentTrackComments = updated)
                onComplete(true)
            }.onFailure {
                onComplete(false)
            }
        }
    }

    /**
     * Gap #34 comment upvotes: optimistic local toggle (UI reflects instantly)
     * + best-effort remote PATCH that silently degrades offline. Re-tapping
     * un-likes and decrements.
     */
    fun toggleCommentLike(comment: TrackComment) {
        val liked = comment.id in _uiState.value.likedComments
        val delta = if (liked) -1 else 1
        val newCount = (comment.likesCount + delta).coerceAtLeast(0)

        _uiState.value = _uiState.value.copy(
            currentTrackComments = _uiState.value.currentTrackComments.map {
                if (it.id == comment.id) it.copy(likesCount = newCount) else it
            },
            likedComments = if (liked) {
                _uiState.value.likedComments - comment.id
            } else {
                _uiState.value.likedComments + comment.id
            }
        )

        viewModelScope.launch {
            SupabaseClient.updateCommentLikes(comment.id, newCount)
        }
    }

    fun submitLyrics(track: Track?, lrcText: String, onComplete: (Boolean) -> Unit) {
        if (track == null || lrcText.isBlank()) return

        viewModelScope.launch {
            val trackCloudId = "trk_${(track.title + track.artist).hashCode()}"
            val result = SupabaseClient.submitSyncedLyrics(trackCloudId, lrcText)
            onComplete(result.isSuccess)
        }
    }
}
