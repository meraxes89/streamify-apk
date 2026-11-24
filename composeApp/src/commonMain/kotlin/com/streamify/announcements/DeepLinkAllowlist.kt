package com.streamify.announcements

import kotlin.concurrent.Volatile

/**
 * Validates deep link destinations against trusted baseline domains and
 * cryptographically verified dynamic domains delivered via signed governance manifests.
 */
object DeepLinkAllowlist {
    private val SAFE_EXACT_HOSTS = setOf(
        "discord.gg",
        "streamify.app"
    )
    private const val SAFE_GITHUB_PREFIX = "github.com/meraxes89"
    private const val SAFE_X_PREFIX = "x.com/sireenyadav"

    @Volatile
    private var dynamicExtraDomains: Set<String> = emptySet()

    fun setDynamicAllowedDomains(domains: List<String>) {
        dynamicExtraDomains = domains
            .map { it.trim().lowercase().removePrefix("https://").trimEnd('/') }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun isAllowed(url: String, extraDomains: List<String> = emptyList()): Boolean {
        val lower = url.trim().lowercase()
        if (!lower.startsWith("https://")) return false
        val afterScheme = lower.removePrefix("https://").substringBefore('#').substringBefore('?')
        val host = afterScheme.substringBefore('/').substringBefore(':')

        if (SAFE_EXACT_HOSTS.contains(host)) return true
        if (afterScheme == SAFE_GITHUB_PREFIX || afterScheme.startsWith("$SAFE_GITHUB_PREFIX/")) return true
        if (afterScheme == SAFE_X_PREFIX || afterScheme.startsWith("$SAFE_X_PREFIX/")) return true

        val allExtras = if (extraDomains.isNotEmpty()) {
            extraDomains.map { it.trim().lowercase().removePrefix("https://").trimEnd('/') } + dynamicExtraDomains
        } else {
            dynamicExtraDomains
        }

        for (extra in allExtras) {
            if (extra.isBlank()) continue
            if (extra.contains('/')) {
                if (afterScheme == extra || afterScheme.startsWith("$extra/")) return true
            } else {
                if (host == extra || host.endsWith(".$extra")) return true
            }
        }
        return false
    }
}
