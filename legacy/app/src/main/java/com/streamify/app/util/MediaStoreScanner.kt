package com.streamify.app.util

import android.content.ContentResolver
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object MediaStoreScanner {
    
    data class LocalAudioFile(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val dataPath: String,
        val durationMs: Long
    )

    private val EXCLUDED_PATH_KEYWORDS = listOf(
        "/recordings/", "/recording/", "/callrecordings/", "/call recording/",
        "/call_rec/", "/voice recorder/", "/sound_recorder/", "/soundrecorder/",
        "/miui/sound_recorder/", "/whatsapp/", "/telegram/",
        "/android/media/com.whatsapp/", "/android/media/org.telegram.messenger/",
        "/notifications/", "/ringtones/", "/alarms/", "/system/media/",
        "/podcasts/", "/audiobooks/"
    )

    private val EXCLUDED_FILENAME_PREFIXES = listOf(
        "call_rec_", "call_", "rec_", "recording_", "voice_", "ptt-", "audiorecord_", "call@"
    )

    private fun isMusicFile(dataPath: String, title: String): Boolean {
        val lowerPath = dataPath.lowercase().replace('\\', '/')
        val lowerTitle = title.lowercase()
        val fileName = File(lowerPath).name

        // Exclude paths matching blacklist keywords
        for (keyword in EXCLUDED_PATH_KEYWORDS) {
            if (lowerPath.contains(keyword)) {
                return false
            }
        }

        // Exclude filenames starting with recording prefixes
        for (prefix in EXCLUDED_FILENAME_PREFIXES) {
            if (fileName.startsWith(prefix) || lowerTitle.startsWith(prefix)) {
                return false
            }
        }

        return true
    }

    suspend fun scanLocalMusic(context: Context): List<LocalAudioFile> = withContext(Dispatchers.IO) {
        val audioFiles = mutableListOf<LocalAudioFile>()
        val resolver: ContentResolver = context.contentResolver
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"

        resolver.query(uri, projection, selection, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"
                val artist = cursor.getString(artistColumn) ?: "Unknown Artist"
                val album = cursor.getString(albumColumn) ?: ""
                val dataPath = cursor.getString(dataColumn) ?: continue
                val durationMs = cursor.getLong(durationColumn)

                if (isMusicFile(dataPath, title)) {
                    audioFiles.add(LocalAudioFile(id, title, artist, album, dataPath, durationMs))
                }
            }
        }
        
        return@withContext audioFiles
    }
}
