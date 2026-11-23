package com.streamify.supabase

object AuthCallbackParser {
    /**
     * Parses an access token from an OAuth redirect callback URL (fragment or query parameters).
     * Supports standard OAuth implicit flow fragments (#access_token=...)
     * and query string redirects (?access_token=...).
     */
    fun extractAccessToken(callbackUri: String): String? {
        if (callbackUri.isBlank()) return null
        val fragment = callbackUri.substringAfter("#", "")
        val query = callbackUri.substringAfter("?", "").substringBefore("#")
        val params = (fragment.split("&") + query.split("&"))
            .filter { it.contains("=") }
            .associate {
                val parts = it.split("=", limit = 2)
                parts[0] to parts[1]
            }
        return params["access_token"]?.takeIf { it.isNotBlank() }
    }
}
