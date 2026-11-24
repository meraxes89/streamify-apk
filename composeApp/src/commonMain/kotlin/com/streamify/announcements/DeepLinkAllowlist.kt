package com.streamify.announcements

/**
 * Hardcoded allowlist of safe domains to prevent remote phishing attacks (comm19 Decision 5).
 * Validates exact domains and canonical path prefixes without allowing suffix spoofing.
 */
object DeepLinkAllowlist {
    private val SAFE_EXACT_HOSTS = setOf(
        "discord.gg",
        "streamify.app"
    )
    private const val SAFE_GITHUB_PREFIX = "github.com/meraxes89"
    private const val SAFE_X_PREFIX = "x.com/sireenyadav"

    fun isAllowed(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://")) return false
        val afterScheme = lower.removePrefix("https://").substringBefore('#').substringBefore('?')
        val host = afterScheme.substringBefore('/').substringBefore(':')

        if (SAFE_EXACT_HOSTS.contains(host)) return true
        if (afterScheme == SAFE_GITHUB_PREFIX || afterScheme.startsWith("$SAFE_GITHUB_PREFIX/")) return true
        if (afterScheme == SAFE_X_PREFIX || afterScheme.startsWith("$SAFE_X_PREFIX/")) return true
        return false
    }
}
