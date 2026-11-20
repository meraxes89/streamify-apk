package com.streamify.app.data.native
import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.data.network.iTunesSearchApi
import com.streamify.app.data.network.LyricsResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import com.streamify.app.util.SLog

data class TaggedAudioResult(
    val coverArtPath: String,
    val lyricsPath: String
)

object NativeMetadataTagger {

    suspend fun tagAndExtractAssets(
        audioFile: File,
        title: String,
        artist: String,
        fallbackThumbnailUrl: String? = null
    ): TaggedAudioResult = withContext(Dispatchers.IO) {
        val baseName = audioFile.absolutePath.substringBeforeLast(".")
        val coverFile = File("$baseName.jpg")
        val lyricsFile = File("$baseName.lrc")

        // 1. Resolve & Download 1400x1400 Retina Cover Art
        var coverArtPath = ""
        try {
            var artUrl = iTunesSearchApi.fetchHdCoverArt(title, artist)
            if (artUrl.isNullOrBlank() && !fallbackThumbnailUrl.isNullOrBlank()) {
                artUrl = fallbackThumbnailUrl
            }

            if (!artUrl.isNullOrBlank()) {
                val req = Request.Builder().url(artUrl).header("User-Agent", "Mozilla/5.0").get().build()
                NetworkEngine.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            coverFile.writeBytes(bytes)
                            coverArtPath = coverFile.absolutePath
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SLog.st("NativeMetadataTagger", "NativeMetadataTagger.tagAndExtractAssets failed", e)
        }

        // 2. Resolve & Write Synced Lyrics
        var lyricsPath = ""
        try {
            val lyrics = LyricsResolver.fetchSyncedLyrics(title, artist)
            if (!lyrics.isNullOrBlank()) {
                lyricsFile.writeText(lyrics)
                lyricsPath = lyricsFile.absolutePath
            }
        } catch (e: Exception) {
            SLog.st("NativeMetadataTagger", "NativeMetadataTagger.tagAndExtractAssets failed", e)
        }

        TaggedAudioResult(coverArtPath, lyricsPath)
    }
}
