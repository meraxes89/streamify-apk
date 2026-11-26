package com.streamify.supabase

import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

internal actual object PlatformTokenStore {
    private const val KEY_SESSION_JSON = "streamify_stored_auth_session_v2"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    actual fun init(context: Any?) {
        // NSUserDefaults requires no context.
    }

    actual fun load(): StoredAuthSession? {
        val raw = NSUserDefaults.standardUserDefaults.stringForKey(KEY_SESSION_JSON) ?: return null
        return runCatching { json.decodeFromString<StoredAuthSession>(raw) }.getOrNull()
    }

    actual fun save(session: StoredAuthSession) {
        runCatching {
            val encoded = json.encodeToString(StoredAuthSession.serializer(), session)
            NSUserDefaults.standardUserDefaults.setObject(encoded, forKey = KEY_SESSION_JSON)
        }
    }

    actual fun clear() {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(KEY_SESSION_JSON)
    }
}
