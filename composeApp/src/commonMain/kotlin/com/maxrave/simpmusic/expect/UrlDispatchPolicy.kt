package com.maxrave.simpmusic.expect

/**
 * Validates URLs before dispatching to system external browser or launcher.
 * Disallows arbitrary schemes (file://, intent://, content://, etc.) to prevent
 * unauthorized file access or unvetted deep-link execution.
 */
object UrlDispatchPolicy {
    private const val MAC_PREFS_PREFIX = "x-apple.systempreferences:"

    fun isDispatchable(url: String): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith(MAC_PREFS_PREFIX, ignoreCase = true)
    }
}
