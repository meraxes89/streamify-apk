package com.streamify.app.media.cache
import java.io.File
import com.streamify.app.util.SLog

object LosslessRemuxer {

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    fun prepareTargetFile(
        outputDir: File,
        title: String,
        artist: String,
        mimeType: String
    ): File {
        val safeTitle = sanitizeFilename(title).ifBlank { "Track_${System.currentTimeMillis()}" }
        val safeArtist = sanitizeFilename(artist).ifBlank { "Artist" }
        val baseName = "$safeTitle - $safeArtist"

        val ext = when {
            mimeType.contains("mp4", ignoreCase = true) || mimeType.contains("aac", ignoreCase = true) -> ".m4a"
            mimeType.contains("opus", ignoreCase = true) || mimeType.contains("webm", ignoreCase = true) -> ".opus"
            mimeType.contains("flac", ignoreCase = true) -> ".flac"
            else -> ".m4a"
        }

        var candidate = File(outputDir, "$baseName$ext")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(outputDir, "$baseName ($counter)$ext")
            counter++
        }
        return candidate
    }

    fun remuxLossless(rawFile: File, targetFile: File): Boolean {
        if (!rawFile.exists() || rawFile.length() == 0L) return false
        try {
            // Direct stream copy: rename or copy raw bytes directly into the container file
            if (rawFile.renameTo(targetFile)) {
                return true
            }
            rawFile.copyTo(targetFile, overwrite = true)
            rawFile.delete()
            return true
        } catch (e: Exception) {
            SLog.st("LosslessRemuxer", "LosslessRemuxer.remuxLossless failed", e)
            return false
        }
    }
}
