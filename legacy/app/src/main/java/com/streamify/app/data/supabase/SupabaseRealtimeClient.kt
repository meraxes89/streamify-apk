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

/** Realtime WebSocket CDC engine: channel joins, heartbeat, CDC dispatch, delta sync. */
internal object SupabaseRealtimeClient {
    internal var realtimeWebSocket: WebSocket? = null

    private val socketLock = Any()

    private var heartbeatJob: Job? = null

    private val syncScope = CoroutineScope(Dispatchers.IO)

    private var lastSyncWatermarkMs: Long = System.currentTimeMillis()

    internal val _isRealtimeConnected = MutableStateFlow(false)

    val isRealtimeConnected: StateFlow<Boolean> = _isRealtimeConnected.asStateFlow()

    private fun disconnectRealtimeInternal() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        realtimeWebSocket?.let { socket ->
            try { socket.close(1000, "Clean termination") } catch (e: Exception) {}
            try { socket.cancel() } catch (e: Exception) {}
        }
        realtimeWebSocket = null
        _isRealtimeConnected.value = false
    }

    fun startRealtimeSync(userId: String) {
        synchronized(socketLock) {
            disconnectRealtimeInternal()
            val wsUrl = BuildConfig.SUPABASE_URL
                .replace("https://", "wss://")
                .replace("http://", "ws://") + "/realtime/v1/websocket?apikey=${BuildConfig.SUPABASE_ANON_KEY}&vsn=1.0.0"

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            realtimeWebSocket = NetworkEngine.client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _isRealtimeConnected.value = true
                    SLog.i("SupabaseRealtime", "Connected to Supabase Realtime WebSocket")

                    // 1. Join user_likes channel
                    val joinLikesMsg = JSONObject().apply {
                    put("topic", "realtime:public:user_likes")
                    put("event", "phx_join")
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("postgres_changes", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("event", "*")
                                    put("schema", "public")
                                    put("table", "user_likes")
                                    put("filter", "user_id=eq.$userId")
                                })
                            })
                        })
                    })
                    put("ref", "likes_sub")
                }
                webSocket.send(joinLikesMsg.toString())

                // 2. Join user_taste_profiles channel
                val joinTasteMsg = JSONObject().apply {
                    put("topic", "realtime:public:user_taste_profiles")
                    put("event", "phx_join")
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("postgres_changes", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("event", "*")
                                    put("schema", "public")
                                    put("table", "user_taste_profiles")
                                    put("filter", "user_id=eq.$userId")
                                })
                            })
                        })
                    })
                    put("ref", "taste_sub")
                }
                webSocket.send(joinTasteMsg.toString())

                // 3. Join profiles channel for reactive multi-tenant updates & Admin Live telemetry
                val joinProfilesMsg = JSONObject().apply {
                    put("topic", "realtime:public:profiles")
                    put("event", "phx_join")
                    put("payload", JSONObject().apply {
                        put("config", JSONObject().apply {
                            put("postgres_changes", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("event", "*")
                                    put("schema", "public")
                                    put("table", "profiles")
                                })
                            })
                        })
                    })
                    put("ref", "profiles_sub")
                }
                webSocket.send(joinProfilesMsg.toString())

                // 4. Heartbeat loop (every 25s)
                heartbeatJob?.cancel()
                heartbeatJob = syncScope.launch {
                    while (_isRealtimeConnected.value) {
                        delay(25000L)
                        val heartbeat = JSONObject().apply {
                            put("topic", "phoenix")
                            put("event", "heartbeat")
                            put("payload", JSONObject())
                            put("ref", "hb_${System.currentTimeMillis()}")
                        }
                        webSocket.send(heartbeat.toString())
                    }
                }

                // 6. Trigger Cursor-based Delta Reconciliation on Connect/Wake
                syncScope.launch {
                    fetchDeltasSince(lastSyncWatermarkMs)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val event = root.optString("event", "")
                    val payload = root.optJSONObject("payload") ?: return

                    if (event == "postgres_changes") {
                        val data = payload.optJSONObject("data") ?: return
                        val table = data.optString("table", "")
                        val eventType = data.optString("type", "")
                        val record = data.optJSONObject("record") ?: data.optJSONObject("old_record") ?: return

                        handleIncomingCdcEvent(table, eventType, record)
                        // v2/v3 jam rows + playback_sync broadcasts removed with
                        // the SupabaseJamClient dead path — the live Jam stack
                        // is the v4 JamEngine P2P mesh (jam/JamEngine.kt).
                    }
                } catch (e: Exception) {
                    SLog.e("SupabaseRealtime", "CDC Parse error: ${e.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(socketLock) {
                    if (realtimeWebSocket === webSocket) {
                        _isRealtimeConnected.value = false
                        heartbeatJob?.cancel()
                        heartbeatJob = null
                        realtimeWebSocket = null
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(socketLock) {
                    if (realtimeWebSocket === webSocket) {
                        disconnectRealtimeInternal()
                    }
                }
                // Auto-reconnect with 3s backoff
                syncScope.launch {
                    delay(3000L)
                    val u = SupabaseClient._currentUser.value
                    if (u != null) {
                        startRealtimeSync(u.id)
                    }
                }
            }
        })
        }
    }

    fun stopRealtimeSync() {
        synchronized(socketLock) {
            disconnectRealtimeInternal()
        }
    }

    private fun handleIncomingCdcEvent(table: String, eventType: String, record: JSONObject) {
        syncScope.launch {
            when (table) {
                "user_likes" -> {
                    val trackId = record.optString("track_id", "")
                    if (trackId.isNotBlank()) {
                        when (eventType.uppercase()) {
                            "INSERT" -> {
                                val fetchedTrack = SupabaseTracksClient.fetchTrackById(trackId)
                                if (fetchedTrack != null) {
                                    val currentLiked = TrackRepository.likedTracks.value
                                    if (currentLiked.none { it.filepath == fetchedTrack.filepath || it.title == fetchedTrack.title }) {
                                        TrackRepository.registerStreamedTrack(fetchedTrack)
                                        TrackRepository.refresh()
                                    }
                                }
                            }
                            "DELETE" -> {
                                TrackRepository.refresh()
                            }
                        }
                    }
                }
                "user_taste_profiles" -> {
                    val totalSec = record.optLong("total_listening_seconds", 0L)
                    val cur = SupabaseClient._currentUser.value
                    if (cur != null && totalSec > cur.listeningSeconds) {
                        SupabaseClient._currentUser.value = cur.copy(listeningSeconds = totalSec)
                    }
                }
                "profiles" -> {
                    val uId = record.optString("id", "")
                    val listeningSec = record.optLong("listening_seconds", 0L)
                    val totalPlays = record.optInt("total_plays", 0)
                    val topTrack = record.optString("top_track", "")
                    val bio = record.optString("bio", "")
                    val genre = record.optString("favorite_genre", "")

                    val cur = SupabaseClient._currentUser.value
                    if (cur != null && cur.id == uId) {
                        SupabaseClient._currentUser.value = cur.copy(
                            listeningSeconds = if (listeningSec > 0) listeningSec else cur.listeningSeconds,
                            totalPlays = if (totalPlays > 0) totalPlays else cur.totalPlays,
                            topTrack = topTrack.ifBlank { cur.topTrack },
                            bio = bio.ifBlank { cur.bio },
                            favoriteGenre = genre.ifBlank { cur.favoriteGenre }
                        )
                    }
                    SupabaseClient.liveProfileUpdates.emit(record)
                }
            }
            lastSyncWatermarkMs = System.currentTimeMillis()
        }
    }

    suspend fun fetchDeltasSince(sinceTimestampMs: Long): List<String> = withContext(Dispatchers.IO) {
        val user = SupabaseClient._currentUser.value ?: return@withContext emptyList()
        try {
            val isoSince = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(sinceTimestampMs))

            val (code, resp) = SupabaseClient.executeRpc("user_likes?user_id=eq.${user.id}&created_at=gte.$isoSince&select=track_id", "GET")

            val deltaTrackIds = mutableListOf<String>()
            if (code in 200..299 && resp != null) {
                val arr = JSONArray(resp)
                for (i in 0 until arr.length()) {
                    val tid = arr.getJSONObject(i).optString("track_id", "")
                    if (tid.isNotBlank()) deltaTrackIds.add(tid)
                }
            }

            if (deltaTrackIds.isNotEmpty()) {
                TrackRepository.refresh()
            }
            lastSyncWatermarkMs = System.currentTimeMillis()
            deltaTrackIds
        } catch (e: Exception) {
            emptyList()
        }
    }

}
