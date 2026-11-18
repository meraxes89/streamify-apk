package com.streamify.app.data.persistence
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.streamify.app.util.SLog

data class StorageBreakdown(
    val audioCacheBytes: Long,
    val imageCacheBytes: Long,
    val lyricsCacheBytes: Long,
    val totalCacheBytes: Long
)

object StorageManager {

    private fun getFolderSize(dir: File): Long {
        var size = 0L
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                size += if (file.isDirectory) getFolderSize(file) else file.length()
            }
        }
        return size
    }

    suspend fun getStorageBreakdown(context: Context): StorageBreakdown = withContext(Dispatchers.IO) {
        val audioDir = File(context.cacheDir, "streamify_audio_cache")
        val imageDir = context.cacheDir.resolve("image_cache")
        val lyricsDir = File(context.cacheDir, "lyrics")

        val audioSize = getFolderSize(audioDir)
        val imageSize = getFolderSize(imageDir)
        val lyricsSize = getFolderSize(lyricsDir)

        StorageBreakdown(
            audioCacheBytes = audioSize,
            imageCacheBytes = imageSize,
            lyricsCacheBytes = lyricsSize,
            totalCacheBytes = audioSize + imageSize + lyricsSize
        )
    }

    suspend fun clearAllCache(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val audioDir = File(context.cacheDir, "streamify_audio_cache")
            val imageDir = context.cacheDir.resolve("image_cache")
            val lyricsDir = File(context.cacheDir, "lyrics")

            audioDir.deleteRecursively()
            imageDir.deleteRecursively()
            lyricsDir.deleteRecursively()
            true
        } catch (e: Exception) {
            SLog.st("StorageManager", "StorageManager.clearAllCache failed", e)
            false
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb < 1.0) {
            val kb = bytes.toDouble() / 1024
            "%.1f KB".format(kb)
        } else {
            "%.1f MB".format(mb)
        }
    }
}
