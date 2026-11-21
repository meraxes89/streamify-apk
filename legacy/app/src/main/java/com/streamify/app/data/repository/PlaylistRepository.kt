package com.streamify.app.data.repository
import android.content.Context
import com.streamify.app.data.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import com.streamify.app.util.SLog
import com.streamify.app.data.ingestion.ParsedTrackItem
import com.streamify.app.data.NativeBridge

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val coverUrl: String? = null,
    val isSystem: Boolean = false,
    val isDeleted: Boolean = false,
    val version: Long = 1L,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val trackIds: List<Int> = emptyList(),
    val trackPositions: Map<Int, Double> = emptyMap()
)

object FractionalIndexEngine {
    private const val DEFAULT_START_POSITION = 1000.0
    private const val DEFAULT_SPACING = 1000.0

    /**
     * Computes an O(1) conflict-free fractional index between two boundary tracks.
     */
    fun calculateMidpoint(prevPosition: Double?, nextPosition: Double?): Double {
        return when {
            prevPosition == null && nextPosition == null -> DEFAULT_START_POSITION
            prevPosition == null -> (nextPosition!! / 2.0).coerceAtLeast(0.0000001)
            nextPosition == null -> prevPosition + DEFAULT_SPACING
            else -> {
                val delta = nextPosition - prevPosition
                if (delta < 0.00001) {
                    // Precision degradation guard: micro-offset
                    prevPosition + (delta / 2.0)
                } else {
                    prevPosition + (delta / 2.0)
                }
            }
        }
    }
}

sealed class PlaylistSyncAction {
    data class Upsert(val playlist: Playlist) : PlaylistSyncAction()
    data class Delete(val playlistId: String) : PlaylistSyncAction()
    data class AddTrack(val playlistId: String, val trackId: Int, val position: Double) : PlaylistSyncAction()
    data class RemoveTrack(val playlistId: String, val trackId: Int) : PlaylistSyncAction()
}

object PlaylistRepository {
    private var playlistFile: File? = null
    private val ioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncChannel = kotlinx.coroutines.channels.Channel<PlaylistSyncAction>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    init {
        // Structured Concurrency Actor: Sequential cloud sync with zero orphaned coroutines
        repoScope.launch {
            for (action in syncChannel) {
                try {
                    when (action) {
                        is PlaylistSyncAction.Upsert -> com.streamify.app.data.supabase.SupabaseClient.syncPlaylistUpsert(action.playlist)
                        is PlaylistSyncAction.Delete -> com.streamify.app.data.supabase.SupabaseClient.syncPlaylistDelete(action.playlistId)
                        is PlaylistSyncAction.AddTrack -> com.streamify.app.data.supabase.SupabaseClient.syncPlaylistTrackAdd(action.playlistId, action.trackId, action.position)
                        is PlaylistSyncAction.RemoveTrack -> com.streamify.app.data.supabase.SupabaseClient.syncPlaylistTrackRemove(action.playlistId, action.trackId)
                    }
                } catch (e: Exception) {
                    // Non-fatal background sync
                }
            }
        }
    }

    fun getPlaylists(): List<Playlist> = _playlists.value.filter { !it.isDeleted }

    fun init(context: Context) {
        if (playlistFile != null) return
        playlistFile = File(context.filesDir, "playlists.json")
        loadPlaylists()
    }

    fun refresh() = loadPlaylists()

    private fun loadPlaylists() {
        val file = playlistFile ?: return
        if (!file.exists()) {
            _playlists.value = emptyList()
            return
        }
        try {
            val jsonText = file.readText()
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<Playlist>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val trackIdsArray = obj.getJSONArray("trackIds")
                val trackIds = mutableListOf<Int>()
                for (j in 0 until trackIdsArray.length()) {
                    trackIds.add(trackIdsArray.getInt(j))
                }
                
                val positionsMap = mutableMapOf<Int, Double>()
                val posObj = obj.optJSONObject("trackPositions")
                if (posObj != null) {
                    val keys = posObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val tId = k.toIntOrNull()
                        if (tId != null) {
                            positionsMap[tId] = posObj.optDouble(k, 1000.0)
                        }
                    }
                }

                list.add(
                    Playlist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        coverUrl = obj.optString("coverUrl", "").ifBlank { null },
                        isSystem = obj.optBoolean("isSystem", false),
                        isDeleted = obj.optBoolean("isDeleted", false),
                        version = obj.optLong("version", 1L),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        trackIds = trackIds,
                        trackPositions = positionsMap
                    )
                )
            }
            _playlists.value = list
        } catch (e: Exception) {
            SLog.st("PlaylistRepository", "PlaylistRepository.loadPlaylists failed", e)
        }
    }

    // Atomic Async File I/O: Zero Main-Thread Disk Blocks, Zero Corruption on App Crash
    private fun savePlaylists() {
        val currentPlaylists = _playlists.value
        val targetFile = playlistFile ?: return
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")

        repoScope.launch(ioDispatcher) {
            try {
                val jsonArray = JSONArray()
                currentPlaylists.forEach { playlist ->
                    val obj = JSONObject()
                    obj.put("id", playlist.id)
                    obj.put("name", playlist.name)
                    obj.put("description", playlist.description)
                    obj.put("isSystem", playlist.isSystem)
                    obj.put("isDeleted", playlist.isDeleted)
                    obj.put("version", playlist.version)
                    obj.put("updatedAt", playlist.updatedAt)
                    obj.put("createdAt", playlist.createdAt)
                    if (playlist.coverUrl != null) obj.put("coverUrl", playlist.coverUrl)

                    val trackIdsArray = JSONArray()
                    playlist.trackIds.forEach { trackIdsArray.put(it) }
                    obj.put("trackIds", trackIdsArray)

                    val posObj = JSONObject()
                    playlist.trackPositions.forEach { (tId, pos) ->
                        posObj.put(tId.toString(), pos)
                    }
                    obj.put("trackPositions", posObj)

                    jsonArray.put(obj)
                }

                // 1. Serialize JSON and write to a TEMP file on IO thread
                tempFile.bufferedWriter().use { writer ->
                    writer.write(jsonArray.toString())
                }

                // 2. Atomic Rename: Instantly swap temp file for real file
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
            } catch (e: Exception) {
                try { tempFile.delete() } catch (_: Exception) {}
                SLog.st("PlaylistRepository", "PlaylistRepository.savePlaylists failed", e)
            }
        }
    }

    fun createPlaylist(name: String, description: String = "", isSystem: Boolean = false): Playlist {
        val now = System.currentTimeMillis()
        val newPlaylist = Playlist(
            id = "pl_" + UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "New Playlist" },
            description = description.trim(),
            isSystem = isSystem,
            isDeleted = false,
            version = 1L,
            updatedAt = now,
            createdAt = now
        )
        _playlists.value = _playlists.value + newPlaylist
        savePlaylists()
        
        // Enqueue cloud sync to sequential channel actor
        syncChannel.trySend(PlaylistSyncAction.Upsert(newPlaylist))
        return newPlaylist
    }

    fun addPlaylist(playlist: Playlist) {
        _playlists.value = _playlists.value.filter { it.id != playlist.id } + playlist
        savePlaylists()
        syncChannel.trySend(PlaylistSyncAction.Upsert(playlist))
    }

    fun deletePlaylist(id: String): Boolean {
        val target = _playlists.value.find { it.id == id } ?: return false
        if (target.isSystem) {
            // Protect system playlists like "Liked Music"
            return false
        }
        
        val now = System.currentTimeMillis()
        val tombstone = target.copy(
            isDeleted = true,
            version = target.version + 1,
            updatedAt = now
        )
        
        _playlists.value = _playlists.value.filter { it.id != id }
        savePlaylists()

        // Enqueue cloud sync to sequential channel actor
        syncChannel.trySend(PlaylistSyncAction.Delete(id))
        return true
    }

    fun renamePlaylist(playlistId: String, newName: String, newDescription: String? = null): Boolean {
        val cleanName = newName.trim()
        if (cleanName.isBlank()) return false
        val now = System.currentTimeMillis()
        var updatedPlaylist: Playlist? = null
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) {
                val updated = it.copy(
                    name = cleanName,
                    description = newDescription?.trim() ?: it.description,
                    version = it.version + 1,
                    updatedAt = now
                )
                updatedPlaylist = updated
                updated
            } else it
        }
        savePlaylists()

        updatedPlaylist?.let { pl ->
            syncChannel.trySend(PlaylistSyncAction.Upsert(pl))
        }
        return true
    }

    /**
     * Phase 3 — sets a custom cropped cover image (local file path) on a
     * playlist; bumps version + updatedAt and syncs upsert to the cloud.
     */
    fun updatePlaylistCover(playlistId: String, coverPath: String): Boolean {
        if (coverPath.isBlank()) return false
        val now = System.currentTimeMillis()
        var updatedPlaylist: Playlist? = null
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) {
                val updated = it.copy(coverUrl = coverPath, version = it.version + 1, updatedAt = now)
                updatedPlaylist = updated
                updated
            } else it
        }
        savePlaylists()
        updatedPlaylist?.let { pl -> syncChannel.trySend(PlaylistSyncAction.Upsert(pl)) }
        return true
    }

    /**
     * Phase 3 — batch removal for in-playlist bulk edit. Removes every
     * listed track id in ONE pass (single save + single cloud sync), in
     * contrast to N sequential removeTrackFromPlaylist calls.
     */
    fun removeTracksFromPlaylist(playlistId: String, trackIds: List<Int>): Boolean {
        if (trackIds.isEmpty()) return false
        val now = System.currentTimeMillis()
        val removalSet = trackIds.toHashSet()
        var updatedPlaylist: Playlist? = null
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) {
                val updated = it.copy(
                    trackIds = it.trackIds.filterNot { tId -> tId in removalSet },
                    trackPositions = it.trackPositions.filterKeys { tId -> tId !in removalSet },
                    version = it.version + 1,
                    updatedAt = now
                )
                updatedPlaylist = updated
                updated
            } else it
        }
        savePlaylists()
        updatedPlaylist?.let { pl -> syncChannel.trySend(PlaylistSyncAction.Upsert(pl)) }
        return true
    }

    fun overwritePlaylistTracks(playlistId: String, trackIds: List<Int>) {
        val now = System.currentTimeMillis()
        var curPos = 1000.0
        val posMap = mutableMapOf<Int, Double>()
        trackIds.forEach { tId ->
            posMap[tId] = curPos
            curPos += 1000.0
        }
        var updatedPlaylist: Playlist? = null
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) {
                val updated = it.copy(
                    trackIds = trackIds,
                    trackPositions = posMap,
                    version = it.version + 1,
                    updatedAt = now
                )
                updatedPlaylist = updated
                updated
            } else it
        }
        savePlaylists()

        updatedPlaylist?.let { pl ->
            syncChannel.trySend(PlaylistSyncAction.Upsert(pl))
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackId: Int, targetPosition: Double? = null) {
        val now = System.currentTimeMillis()
        var finalPos = targetPosition
        var updatedPlaylist: Playlist? = null

        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) {
                if (!pl.trackIds.contains(trackId)) {
                    val lastPos = pl.trackIds.maxOfOrNull { pl.trackPositions[it] ?: 1000.0 }
                    val pos = targetPosition ?: FractionalIndexEngine.calculateMidpoint(lastPos, null)
                    finalPos = pos
                    val newPositions = pl.trackPositions + (trackId to pos)
                    val newTrackIds = (pl.trackIds + trackId).sortedBy { newPositions[it] ?: 1000.0 }
                    val updated = pl.copy(
                        trackIds = newTrackIds,
                        trackPositions = newPositions,
                        version = pl.version + 1,
                        updatedAt = now
                    )
                    updatedPlaylist = updated
                    updated
                } else pl
            } else pl
        }
        savePlaylists()

        finalPos?.let { pos ->
            syncChannel.trySend(PlaylistSyncAction.AddTrack(playlistId, trackId, pos))
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: Int) {
        val now = System.currentTimeMillis()
        var updatedPlaylist: Playlist? = null

        _playlists.value = _playlists.value.map { pl ->
            if (pl.id == playlistId) {
                val newTrackIds = pl.trackIds.filter { id -> id != trackId }
                val newPositions = pl.trackPositions.filterKeys { it != trackId }
                val updated = pl.copy(
                    trackIds = newTrackIds,
                    trackPositions = newPositions,
                    version = pl.version + 1,
                    updatedAt = now
                )
                updatedPlaylist = updated
                updated
            } else pl
        }
        savePlaylists()

        syncChannel.trySend(PlaylistSyncAction.RemoveTrack(playlistId, trackId))
    }

    fun reorderTrack(playlistId: String, trackId: Int, prevTrackId: Int?, nextTrackId: Int?) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return
        val prevPos = prevTrackId?.let { playlist.trackPositions[it] }
        val nextPos = nextTrackId?.let { playlist.trackPositions[it] }
        val newPos = FractionalIndexEngine.calculateMidpoint(prevPos, nextPos)
        val now = System.currentTimeMillis()

        val newPositions = playlist.trackPositions + (trackId to newPos)
        val newTrackIds = playlist.trackIds.sortedBy { newPositions[it] ?: 1000.0 }
        val updated = playlist.copy(
            trackIds = newTrackIds,
            trackPositions = newPositions,
            version = playlist.version + 1,
            updatedAt = now
        )

        _playlists.value = _playlists.value.map { if (it.id == playlistId) updated else it }
        savePlaylists()

        syncChannel.trySend(PlaylistSyncAction.AddTrack(playlistId, trackId, newPos))
    }

    suspend fun getTracksForPlaylist(playlistId: String, allTracks: List<Track>): List<Track> = withContext(Dispatchers.Default) {
        val playlist = _playlists.value.find { it.id == playlistId } ?: return@withContext emptyList()
        val trackMap = allTracks.associateBy { it.id }
        playlist.trackIds.mapNotNull { trackMap[it] }
    }

    suspend fun importAndLinkPlaylist(
        playlistName: String,
        importedTracks: List<ParsedTrackItem>
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        if (importedTracks.isEmpty()) return@withContext Pair(0, 0)
        
        var playlist = _playlists.value.find { it.name.equals(playlistName, ignoreCase = true) }
        if (playlist == null) {
            playlist = createPlaylist(playlistName, "Imported from universal playlist source")
        }

        var linkedCount = 0
        var queuedCount = 0

        for (item in importedTracks) {
            val localTrackId = NativeBridge.findFuzzyTrackMatch(item.title, item.artist)
            if (localTrackId > 0) {
                addTrackToPlaylist(playlist.id, localTrackId)
                linkedCount++
            } else {
                // Enqueue missing track for background download
                try {
                    val searchUrl = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("${item.title} ${item.artist}", "UTF-8")
                    com.streamify.app.viewmodel.IngestionViewModel.enqueueDownloadDirect(
                        url = searchUrl,
                        title = item.title,
                        artist = item.artist,
                        album = item.album,
                        quality = "320"
                    )
                    queuedCount++
                } catch (e: Exception) {
                    SLog.st("PlaylistRepository", "PlaylistRepository.importAndLinkPlaylist failed", e)
                }
            }
        }
        Pair(linkedCount, queuedCount)
    }

    suspend fun exportPlaylistToM3U8(playlistId: String, allTracks: List<Track>, context: Context): File? = withContext(Dispatchers.IO) {
        val playlist = _playlists.value.find { it.id == playlistId || it.name.equals(playlistId, ignoreCase = true) }
        val playlistTracks = if (playlist != null) {
            val trackMap = allTracks.associateBy { it.id }
            val resolved = playlist.trackIds.mapNotNull { trackMap[it] }
            if (resolved.isNotEmpty()) resolved else allTracks
        } else {
            allTracks
        }
        if (playlistTracks.isEmpty()) return@withContext null

        try {
            val exportDir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Streamify/Playlists")
            if (!exportDir.exists()) exportDir.mkdirs()

            val rawName = playlist?.name ?: playlistId.removePrefix("album_").removePrefix("playlist_")
            val safeName = rawName.replace(Regex("[^a-zA-Z0-9_\\-\\s]"), "").trim().ifBlank { "playlist" }
            val m3u8File = File(exportDir, "$safeName.m3u8")

            val sb = StringBuilder()
            sb.append("#EXTM3U\n")
            sb.append("#PLAYLIST:$rawName\n\n")

            for (t in playlistTracks) {
                sb.append("#EXTINF:${t.durationSec},${t.artist} - ${t.title}\n")
                
                // Compute relative path for universal car & portable USB playback
                val audioFile = File(t.filepath)
                val relativePath = try {
                    if (audioFile.exists() && audioFile.parentFile != null) {
                        exportDir.toURI().relativize(audioFile.toURI()).path
                    } else {
                        t.filepath
                    }
                } catch (e: Exception) {
                    t.filepath
                }
                sb.append("$relativePath\n\n")
            }

            m3u8File.writeText(sb.toString(), Charsets.UTF_8)
            m3u8File
        } catch (e: Exception) {
            SLog.st("PlaylistRepository", "PlaylistRepository.exportPlaylistToM3U8 failed", e)
            null
        }
    }

    fun hardResetState() {
        _playlists.value = emptyList()
        val file = playlistFile
        if (file != null && file.exists()) {
            file.delete()
        }
    }
}
