package com.streamify.app.data.persistence
import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.streamify.app.util.SLog
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.data.repository.Playlist
import com.streamify.app.data.repository.PlaylistRepository
import com.streamify.app.data.NativeBridge

object BackupManager {

    suspend fun exportLibraryBackup(context: Context): Result<String> = withContext(Dispatchers.IO) {
        try {
            val backupDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Streamify")
            if (!backupDir.exists()) backupDir.mkdirs()

            val backupFile = File(backupDir, "streamify_backup_${System.currentTimeMillis()}.json")
            val writer = OutputStreamWriter(FileOutputStream(backupFile), Charsets.UTF_8)

            writer.write("{\n")
            writer.write("  \"version\": 1,\n")
            writer.write("  \"exportTimestamp\": ${System.currentTimeMillis()},\n")
            writer.write("  \"date\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\",\n")

            // 1. Stream Liked IDs
            val likedIds = NativeBridge.getLikedTracks(1).map { it.id }
            writer.write("  \"likedTrackIds\": [${likedIds.joinToString(",")}],\n")

            // 2. Stream Playlists
            val playlists = PlaylistRepository.getPlaylists()
            val playlistsArray = JSONArray()
            playlists.forEach { p ->
                playlistsArray.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("description", p.description)
                    val tIds = JSONArray()
                    p.trackIds.forEach { tIds.put(it) }
                    put("trackIds", tIds)
                })
            }
            writer.write("  \"playlists\": ${playlistsArray.toString()},\n")

            // 3. Stream Tracks in 500-track chunks to eliminate OOM
            writer.write("  \"tracks\": [\n")
            var offset = 0
            val chunkSize = 500
            var isFirstTrack = true

            while (true) {
                val batch = NativeBridge.getTracksBatch(offset, chunkSize)
                if (batch.isEmpty()) break

                for (track in batch) {
                    if (!isFirstTrack) writer.write(",\n")
                    isFirstTrack = false

                    val trackObj = JSONObject().apply {
                        put("id", track.id)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("album", track.album)
                        put("durationSec", track.durationSec)
                        put("filepath", track.filepath)
                        put("bpm", track.bpm.toDouble())
                        put("source", track.source)
                    }
                    writer.write("    " + trackObj.toString())
                }

                if (batch.size < chunkSize) break
                offset += chunkSize
            }

            writer.write("\n  ]\n}")
            writer.flush()
            writer.close()

            Result.success(backupFile.absolutePath)
        } catch (e: Exception) {
            SLog.st("BackupManager", "BackupManager.exportLibraryBackup failed", e)
            Result.failure(e)
        }
    }

    suspend fun importLibraryBackup(context: Context, jsonString: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            var importedCount = 0

            if (root.has("tracks")) {
                val tracksArray = root.getJSONArray("tracks")
                for (i in 0 until tracksArray.length()) {
                    val t = tracksArray.getJSONObject(i)
                    val filepath = t.optString("filepath", "")
                    val title = t.optString("title", "Unknown")
                    val artist = t.optString("artist", "Unknown")
                    val album = t.optString("album", "Streamify")
                    val duration = t.optInt("durationSec", 0)
                    val bpm = t.optDouble("bpm", 120.0).toFloat()

                    if (filepath.isNotBlank()) {
                        val trackId = NativeBridge.insertTrack(filepath, title, artist, album, duration, bpm)
                        importedCount++
                    }
                }
            }

            if (root.has("playlists")) {
                val playlistsArray = root.getJSONArray("playlists")
                for (i in 0 until playlistsArray.length()) {
                    val p = playlistsArray.getJSONObject(i)
                    val id = p.optString("id", java.util.UUID.randomUUID().toString())
                    val name = p.optString("name", "Restored Playlist")
                    val desc = p.optString("description", "Restored from backup")
                    val tIds = mutableListOf<Int>()
                    val tIdsArray = p.optJSONArray("trackIds")
                    if (tIdsArray != null) {
                        for (j in 0 until tIdsArray.length()) {
                            tIds.add(tIdsArray.getInt(j))
                        }
                    }
                    PlaylistRepository.addPlaylist(Playlist(id = id, name = name, description = desc, trackIds = tIds))
                }
            }

            TrackRepository.refresh()
            Result.success(importedCount)
        } catch (e: Exception) {
            SLog.st("BackupManager", "BackupManager.importLibraryBackup failed", e)
            Result.failure(e)
        }
    }
}
