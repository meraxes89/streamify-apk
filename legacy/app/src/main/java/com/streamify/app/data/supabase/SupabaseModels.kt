package com.streamify.app.data.supabase
import com.streamify.app.util.SLog
import android.content.Context
import android.content.SharedPreferences
import com.streamify.app.BuildConfig
import com.streamify.app.data.models.Track
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.repository.EdgeMeshRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import com.streamify.app.data.network.NetworkEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Wire-model data classes exchanged with the Supabase backend. */
data class UserProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val avatarUrl: String,
    val bio: String = "Music lover on Streamify 🎧",
    val isAdmin: Boolean = false,
    val totalPlays: Int = 0,
    val listeningSeconds: Long = 0L,
    val favoriteGenre: String = "All",
    val topTrack: String = "",
    val isPrivate: Boolean = false,
    val createdAt: String = "",
    val lastActiveAt: String = ""
)

data class TelemetryPayload(
    val listeningSeconds: Long,
    val totalPlays: Int,
    val topTrack: String,
    val favoriteGenre: String,
    val bio: String,
    val lastActiveAt: String
)

data class TrackComment(
    val id: String,
    val trackId: String,
    val userId: String,
    val userName: String,
    val userAvatar: String,
    val timestampMs: Long,
    val commentText: String,
    val likesCount: Int = 0,
    /** Gap #34 threading: id of the comment this replies to (null = root). */
    val parentId: String? = null,
    val createdAt: String = ""
)

// ============================================================================
// JAM IDENTITY CODEC — lossless track identity across the wire.
// ytmVideoId/isrc MUST travel with every payload so guests never fall into
// blind fuzzy resolution (the root of historical wrong-song jams).
// ============================================================================

data class FriendActivity(
    val userId: String,
    val displayName: String,
    val avatarUrl: String,
    val trackTitle: String,
    val trackArtist: String,
    val coverUrl: String,
    val lastActiveAt: String,
    /** Live Jam session this friend is in (Gap #32 one-tap "Join Jam"). */
    val sessionCode: String? = null
)

data class CommunityPlaylist(
    val id: String,
    val userId: String,
    val creatorName: String,
    val name: String,
    val description: String,
    val coverUrl: String,
    val isCollaborative: Boolean,
    val likesCount: Int,
    val trackCount: Int
)

data class EdgeComputeTask(
    val taskId: String,
    val trackId: String,
    val taskType: String,
    val trackTitle: String,
    val trackArtist: String,
    val audioUrl: String,
    val nonce: String
)

data class EdgeNodeActivityItem(
    val deviceId: String,
    val displayName: String,
    val userEmail: String,
    val status: String,
    val currentTrackTitle: String,
    val totalContributions: Int,
    val bandwidthSavedMb: Double,
    val lastActiveAt: String
)

data class EdgeContributorItem(
    val userId: String,
    val displayName: String,
    val userEmail: String,
    val totalContributions: Int,
    val bandwidthSavedMb: Double,
    val lastActiveAt: String
)

data class DbTableStatItem(
    val tableName: String,
    val rowCount: Long
)

data class AdminEdgeMeshStats(
    val totalTasksCount: Int = 0,
    val completedTasksCount: Int = 0,
    val activeNodesCount: Int = 0,
    val totalBandwidthSavedMb: Double = 0.0,
    val activeNodes: List<EdgeNodeActivityItem> = emptyList(),
    val topContributors: List<EdgeContributorItem> = emptyList(),
    val tableStats: List<DbTableStatItem> = emptyList()
)

data class AdminTelemetry(
    val totalUsers: Int = 0,
    val totalTracks: Int = 0,
    val totalPlaylists: Int = 0,
    val activeJamSessions: Int = 0,
    val totalComments: Int = 0,
    val totalLikes: Int = 0,
    val totalPlays: Long = 0L,
    val dau24h: Int = 0,
    val userList: List<UserProfile> = emptyList(),
    val serverStatus: String = "Operational",
    val latencyMs: Long = 24L,
    val engineMode: String = "PostgreSQL 15 + pgvector 0.5.1"
)

data class AdminJamSession(
    val id: String,
    val sessionCode: String,
    val hostName: String,
    val hostEmail: String,
    val currentTrackTitle: String,
    val currentTrackArtist: String,
    val participantCount: Int,
    val isPlaying: Boolean,
    val updatedAt: String
)

data class AdminCommentItem(
    val id: String,
    val trackId: String,
    val trackTitle: String,
    val userId: String,
    val userName: String,
    val userAvatar: String,
    val commentText: String,
    val timestampMs: Long,
    val createdAt: String
)
