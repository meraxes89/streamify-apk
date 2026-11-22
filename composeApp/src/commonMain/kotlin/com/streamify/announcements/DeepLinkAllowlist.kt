package com.streamify.announcements

/**
 * Hardcoded allowlist of safe domains to prevent remote phishing attacks (comm19 Decision 5).
 */
object DeepLinkAllowlist {
    private val SAFE_HOSTS = listOf(
        "github.com/meraxes89",
        "discord.gg",
        "x.com/sireenyadav",
        "streamify.app"
    )

    fun isAllowed(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://")) return false
        val hostPart = lower.removePrefix("https://").substringBefore('?')
        return SAFE_HOSTS.any { hostPart.startsWith(it) }
    }
}
