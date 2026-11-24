package com.maxrave.simpmusic.expect

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.maxrave.logger.Logger
import org.koin.mp.KoinPlatform.getKoin

actual fun openUrl(url: String) {
    if (url.isBlank()) return
    runCatching {
        val context: AppCompatActivity = getKoin().get()
        val browserIntent =
            Intent(
                Intent.ACTION_VIEW,
                url.toUri(),
            ).apply {
                flags = FLAG_ACTIVITY_NEW_TASK
            }
        context.startActivity(browserIntent)
    }.onFailure { e ->
        Logger.w("OpenUrl", "Failed to open URL $url: ${e.message}")
    }
}

actual fun shareUrl(
    title: String,
    url: String,
) {
    runCatching {
        val context: AppCompatActivity = getKoin().get()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            flags = FLAG_ACTIVITY_NEW_TASK
        }
        val chooserIntent = Intent.createChooser(shareIntent, title)
        context.startActivity(chooserIntent)
    }.onFailure { e ->
        Logger.w("OpenUrl", "Failed to share URL $url: ${e.message}")
    }
}
