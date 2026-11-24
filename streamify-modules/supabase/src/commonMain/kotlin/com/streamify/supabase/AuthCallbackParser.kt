package com.streamify.supabase

/**
 * Structured representation of parsed OAuth callback parameters.
 */
data class AuthCallbackData(
    val code: String? = null,
    val state: String? = null,
    val error: String? = null,
    val errorCode: String? = null,
    val errorDescription: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresIn: Long? = null,
    val tokenType: String? = null
) {
    val isSuccess: Boolean
        get() = error.isNullOrBlank() && (!code.isNullOrBlank() || !accessToken.isNullOrBlank())

    val isError: Boolean
        get() = !error.isNullOrBlank()
}

/**
 * Universal parser for OAuth redirect callback URLs.
 * Supports both PKCE Authorization Code callbacks (?code=...&state=...)
 * and legacy implicit flow fragments (#access_token=...).
 */
object AuthCallbackParser {

    /**
     * Parses all standard OAuth query and fragment parameters from the callback URL.
     */
    fun parse(callbackUri: String): AuthCallbackData {
        if (callbackUri.isBlank()) return AuthCallbackData()

        val fragment = callbackUri.substringAfter("#", "")
        val query = callbackUri.substringAfter("?", "").substringBefore("#")

        val params = HashMap<String, String>()

        fun extractPairs(source: String) {
            if (source.isBlank()) return
            source.split("&").forEach { pair ->
                if (pair.contains("=")) {
                    val parts = pair.split("=", limit = 2)
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    if (key.isNotEmpty() && !params.containsKey(key)) {
                        params[key] = decodeComponent(value)
                    }
                }
            }
        }

        // Query params take precedence, fallback to fragment
        extractPairs(query)
        extractPairs(fragment)

        return AuthCallbackData(
            code = params["code"]?.takeIf { it.isNotBlank() },
            state = params["state"]?.takeIf { it.isNotBlank() },
            error = params["error"]?.takeIf { it.isNotBlank() },
            errorCode = params["error_code"]?.takeIf { it.isNotBlank() },
            errorDescription = params["error_description"]?.takeIf { it.isNotBlank() },
            accessToken = params["access_token"]?.takeIf { it.isNotBlank() },
            refreshToken = params["refresh_token"]?.takeIf { it.isNotBlank() },
            expiresIn = params["expires_in"]?.toLongOrNull(),
            tokenType = params["token_type"]?.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Parses an access token from an OAuth redirect callback URL (fragment or query parameters).
     * Retained for backward compatibility with existing callers.
     */
    fun extractAccessToken(callbackUri: String): String? {
        return parse(callbackUri).accessToken
    }

    /**
     * Minimal URL component decoder replacing '+' with space and decoding '%XX' sequences.
     */
    private fun decodeComponent(encoded: String): String {
        if (!encoded.contains("%") && !encoded.contains("+")) return encoded
        val sb = StringBuilder(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            when (c) {
                '+' -> sb.append(' ')
                '%' -> {
                    if (i + 2 < encoded.length) {
                        val hex = encoded.substring(i + 1, i + 3)
                        val code = hex.toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 2
                        } else {
                            sb.append('%')
                        }
                    } else {
                        sb.append('%')
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
