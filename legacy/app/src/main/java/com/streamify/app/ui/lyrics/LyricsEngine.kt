package com.streamify.app.ui.lyrics

import androidx.compose.runtime.Immutable
import com.streamify.app.data.NativeBridge

object LyricsEngine {
    private var lyricMapPtr: Long = 0L

    var lyricLines: List<String> = emptyList()
        private set

    @Synchronized
    fun loadLyrics(lrc: String) {
        if (lyricMapPtr != 0L) {
            try {
                NativeBridge.nativeFreeLyricMap(lyricMapPtr)
            } catch (e: Throwable) {
                // Ignore
            }
            lyricMapPtr = 0L
        }

        if (lrc.isBlank()) {
            lyricLines = emptyList()
            return
        }

        try {
            lyricMapPtr = NativeBridge.nativeParseLrc(lrc)
        } catch (e: Throwable) {
            lyricMapPtr = 0L
        }

        // Parse clean display strings once
        val re = Regex("""^\[\d{2}:\d{2}\.\d{2,3}\](.*)$""")
        val parsed = ArrayList<String>()
        for (line in lrc.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && !trimmed.startsWith("[ti:") && !trimmed.startsWith("[ar:") && !trimmed.startsWith("[al:")) {
                val match = re.find(trimmed)
                val text = match?.groupValues?.getOrNull(1)?.trim() ?: trimmed
                if (text.isNotBlank()) {
                    parsed.add(text)
                }
            }
        }
        lyricLines = parsed
    }

    fun getActiveIndex(currentMs: Long): Int {
        val ptr = lyricMapPtr
        if (ptr == 0L || lyricLines.isEmpty()) return 0
        return try {
            val idx = NativeBridge.nativeGetLyricIndex(ptr, currentMs)
            if (idx < 0) 0 else idx.coerceAtMost(lyricLines.size - 1)
        } catch (e: Throwable) {
            0
        }
    }

    @Synchronized
    fun release() {
        if (lyricMapPtr != 0L) {
            try {
                NativeBridge.nativeFreeLyricMap(lyricMapPtr)
            } catch (e: Throwable) {
                // Ignore
            }
            lyricMapPtr = 0L
        }
        lyricLines = emptyList()
    }
}
