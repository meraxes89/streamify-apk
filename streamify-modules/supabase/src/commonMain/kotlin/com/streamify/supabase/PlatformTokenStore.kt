package com.streamify.supabase

import kotlinx.serialization.Serializable

/**
 * Persistent snapshot of an authenticated Supabase session.
 * Stored per-platform by [PlatformTokenStore] so rotated RTR refresh tokens
 * survive process restarts without triggering Supabase reuse detection.
 */
@Serializable
data class StoredAuthSession(
    val user: UserProfile,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long = 0L,
)

/**
 * Cross-platform session persistence primitive for `S18` (Identity & Trust).
 *
 * Implementations:
 * - `androidMain`: AES-256-GCM (`AndroidKeyStore`) in `SharedPreferences("streamify_auth")`.
 * - `jvmMain`: `~/.streamify/auth_session.json` with owner-only (`0600`) permissions.
 * - `iosMain`: `NSUserDefaults.standardUserDefaults`.
 * - `wasmJsMain`: `window.localStorage`.
 */
internal expect object PlatformTokenStore {
    fun init(context: Any? = null)
    fun load(): StoredAuthSession?
    fun save(session: StoredAuthSession)
    fun clear()
}
