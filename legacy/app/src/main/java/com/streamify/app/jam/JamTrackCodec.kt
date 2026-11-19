package com.streamify.app.jam
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

/** JSON codec for jam-queue tracks (session wire format). */
fun jamTrackToJson(track: Track, addedBy: String = ""): JSONObject = JSONObject().apply {
    put("id", track.id)
    put("title", track.title)
    put("artist", track.artist)
    put("album", track.album)
    put("filepath", track.filepath)
    put("coverArtPath", track.coverArtPath ?: "")
    put("durationSec", track.durationSec)
    track.ytmVideoId?.let { put("ytmVideoId", it) }
    track.isrc?.let { put("isrc", it) }
    if (addedBy.isNotBlank()) put("addedBy", addedBy)
}

fun jamTrackFromJson(o: JSONObject?): Track? {
    o ?: return null
    val title = o.optString("title", "")
    if (title.isBlank()) return null
    return Track(
        id = o.optInt("id", -(title.hashCode())),
        title = title,
        artist = o.optString("artist", ""),
        album = o.optString("album", "Streamify Jam"),
        filepath = o.optString("filepath", ""),
        coverArtPath = o.optString("coverArtPath", "").ifBlank { null },
        durationSec = o.optInt("durationSec", 0),
        bpm = o.optDouble("bpm", 0.0).toFloat(),
        key = o.optString("key", ""),
        source = "cloud_jam",
        isrc = o.optString("isrc", "").ifBlank { null },
        ytmVideoId = o.optString("ytmVideoId", "").ifBlank { null }
    )
}
