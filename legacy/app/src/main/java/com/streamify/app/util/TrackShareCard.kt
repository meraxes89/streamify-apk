package com.streamify.app.util

import android.content.Context
import android.content.Intent
import com.streamify.app.data.models.Track
import com.streamify.app.util.SLog

object TrackShareCard {

    fun shareTrack(context: Context, track: Track) {
        try {
            val shareText = buildString {
                appendLine("🎵 Now Playing on Streamify:")
                appendLine("\"${track.title}\" by ${track.artist}")
                if (track.album.isNotBlank() && track.album != "Single") {
                    appendLine("Album: ${track.album}")
                }
                appendLine()
                if (track.filepath.startsWith("http")) {
                    appendLine("Listen here: ${track.filepath}")
                } else {
                    appendLine("Listen here: https://music.youtube.com/search?q=${java.net.URLEncoder.encode("${track.title} ${track.artist}", "UTF-8")}")
                }
            }

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                type = "text/plain"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val shareIntent = Intent.createChooser(sendIntent, "Share Track").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(shareIntent)
        } catch (e: Exception) {
            SLog.st("TrackShareCard", "TrackShareCard.shareTrack failed", e)
        }
    }
}
