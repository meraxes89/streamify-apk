package com.streamify.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object DurationFormatter {
    fun formatMs(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    fun formatSec(sec: Long): String {
        val minutes = sec / 60
        val seconds = sec % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
