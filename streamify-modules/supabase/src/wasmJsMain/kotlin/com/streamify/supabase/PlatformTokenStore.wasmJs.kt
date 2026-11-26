package com.streamify.supabase

import kotlinx.browser.localStorage
import kotlinx.serialization.json.Json

internal actual object PlatformTokenStore {
    private const val KEY_SESSION_JSON = "streamify_stored_auth_session_v2"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    actual fun init(context: Any?) {
        // Browser localStorage requires no context.
    }

    actual fun load(): StoredAuthSession? {
        val raw = localStorage.getItem(KEY_SESSION_JSON) ?: return null
        return runCatching { json.decodeFromString<StoredAuthSession>(raw) }.getOrNull()
    }

    actual fun save(session: StoredAuthSession) {
        runCatching {
            val encoded = json.encodeToString(StoredAuthSession.serializer(), session)
            localStorage.setItem(KEY_SESSION_JSON, encoded)
        }
    }

    actual fun clear() {
        localStorage.removeItem(KEY_SESSION_JSON)
    }
}
