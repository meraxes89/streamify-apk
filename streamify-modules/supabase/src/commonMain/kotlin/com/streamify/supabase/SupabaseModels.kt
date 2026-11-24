package com.streamify.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserProfile(
    val id: String = "",
    val email: String = "",
    @SerialName("display_name")
    val displayName: String = "",
    @SerialName("avatar_url")
    val avatarUrl: String = "",
    val bio: String = "Music lover on Streamify",
    @SerialName("is_admin")
    val isAdmin: Boolean = false,
    @SerialName("total_plays")
    val totalPlays: Int = 0,
    @SerialName("listening_seconds")
    val listeningSeconds: Long = 0L,
    @SerialName("favorite_genre")
    val favoriteGenre: String = "All",
    @SerialName("top_track")
    val topTrack: String? = null,
    @SerialName("is_private")
    val isPrivate: Boolean = false,
    @SerialName("active_session")
    val activeSession: CloudActiveSession? = null,
    @SerialName("created_at")
    val createdAt: String = "",
    @SerialName("last_active_at")
    val lastActiveAt: String = ""
)

@Serializable
data class CloudTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    @SerialName("duration_sec")
    val durationSec: Int = 0,
    @SerialName("cover_url")
    val coverUrl: String = "",
    @SerialName("stream_url")
    val streamUrl: String = "",
    val bpm: Int = 0,
    @SerialName("key_signature")
    val keySignature: String = "",
    @SerialName("play_count")
    val playCount: Int = 0,
    @SerialName("like_count")
    val likeCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class CloudUserLike(
    @SerialName("user_id")
    val user_id: String,
    @SerialName("track_id")
    val track_id: String,
    @SerialName("created_at")
    val created_at: String? = null
)

@Serializable
data class CloudUserItemFavorite(
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("item_type")
    val itemType: String = "",
    @SerialName("item_id")
    val itemId: String = "",
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class CloudUserSettings(
    @SerialName("user_id")
    val userId: String = "",
    val settings: Map<String, String> = emptyMap(),
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class CloudListeningHistoryItem(
    val id: String = "",
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("track_id")
    val trackId: String = "",
    @SerialName("album_id")
    val albumId: String? = null,
    @SerialName("artist_id")
    val artistId: String? = null,
    @SerialName("listened_duration_sec")
    val listenedDurationSec: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null
)

@Serializable
data class CloudPlaylist(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String,
    val description: String = "",
    @SerialName("cover_url")
    val coverUrl: String = "",
    @SerialName("is_public")
    val isPublic: Boolean = true,
    @SerialName("likes_count")
    val likesCount: Int = 0,
    @SerialName("youtube_playlist_id")
    val youtubePlaylistId: String? = null
)

@Serializable
data class CloudSearchQuery(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String? = null,
    val query: String,
    @SerialName("searched_at")
    val searchedAt: String? = null,
    @SerialName("is_deleted")
    val isDeleted: Boolean = false
)

@Serializable
data class CloudActiveSession(
    @SerialName("track_id")
    val trackId: String? = null,
    @SerialName("position_ms")
    val positionMs: Long = 0L,
    @SerialName("queue_ids")
    val queueIds: List<String> = emptyList(),
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class CloudPlaylistTrack(
    val id: String? = null,
    @SerialName("playlist_id")
    val playlistId: String = "",
    @SerialName("track_id")
    val trackId: String = "",
    val position: Int = 0,
    @SerialName("added_by")
    val addedBy: String? = null
)

@Serializable
data class CloudPlaylistTrackWithMetadata(
    val id: String? = null,
    @SerialName("playlist_id")
    val playlistId: String = "",
    @SerialName("track_id")
    val trackId: String = "",
    val position: Int = 0,
    val tracks: CloudTrack? = null
)

@Serializable
data class SessionTrack(
    val id: Int? = null,
    val title: String = "",
    val artist: String = "",
    val filepath: String = "",
    val durationSec: Int = 0,
    val coverArtPath: String = ""
)

@Serializable
data class CloudListeningSession(
    val id: String = "",
    @SerialName("session_code")
    val session_code: String = "",
    @SerialName("host_user_id")
    val host_user_id: String = "",
    @SerialName("current_track_id")
    val current_track_id: String? = null,
    @SerialName("position_ms")
    val position_ms: Long = 0L,
    @SerialName("is_playing")
    val is_playing: Boolean = false,
    @SerialName("current_track_json")
    val current_track_json: SessionTrack? = null,
    @SerialName("updated_at")
    val updated_at: String? = null
)

@Serializable
data class FriendActivityItem(
    val userId: String,
    val displayName: String,
    val avatarUrl: String,
    val trackTitle: String,
    val trackArtist: String,
    val coverUrl: String,
    val isLive: Boolean,
    val jamCode: String? = null,
    val timeAgo: String = "Listening now",
    val listeningTimeText: String = "",
    val positionMs: Long = 0L,
    val durationSec: Int = 0
)

@Serializable
data class PlaybackTelemetryEvent(
    val userId: String,
    val videoId: String,
    val trackTitle: String,
    val artistName: String,
    val durationSeconds: Long,
    val listenedSeconds: Long,
    val timestamp: Long
)

@Serializable
data class WrappedCloudSnapshot(
    val userId: String,
    val year: Int,
    val totalMinutes: Long,
    val topSongsJson: String,
    val topArtistsJson: String,
    val topAlbumsJson: String,
    val biggestDay: String = "",
    val dominantGenre: String = "",
    val personaHeadline: String = "",
    val personaSummary: String = "",
    val updatedAt: Long
)

@Serializable
data class AdminCommentItem(
    val id: String,
    @SerialName("track_id")
    val trackId: String = "",
    @SerialName("user_id")
    val userId: String = "",
    @SerialName("user_name")
    val userName: String = "Listener",
    @SerialName("user_avatar")
    val userAvatar: String = "",
    @SerialName("comment_text")
    val commentText: String = "",
    @SerialName("timestamp_ms")
    val timestampMs: Long = 0L,
    @SerialName("created_at")
    val createdAt: String = ""
)

@Serializable
data class AdminBroadcastItem(
    val id: String = "",
    val message: String = "",
    @SerialName("author_email")
    val authorEmail: String = "",
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("created_at")
    val createdAt: String = ""
)

@Serializable
data class AdminTelemetry(
    val totalUsers: Int,
    val totalTracks: Int,
    val totalPlaylists: Int,
    val activeJamSessions: Int,
    val totalComments: Int,
    val totalLikes: Int,
    val totalPlays: Long,
    val dau24h: Int,
    val serverStatus: String,
    val rpcHealthy: Boolean,
    val engineMode: String,
    val latencyMs: Long
)
