package com.streamify.supabase

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json

internal object AndroidTokenCipher {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "streamify_auth_token_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ENC_PREFIX = "enc_v1:"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private fun getOrCreateKey(): java.security.Key? {
        return try {
            val keyStore = java.security.KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            keyStore.getKey(KEY_ALIAS, null) ?: run {
                val keyGen = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                    KEYSTORE_PROVIDER,
                )
                val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                keyGen.init(spec)
                keyGen.generateKey()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun encrypt(plaintext: String): String? {
        val key = getOrCreateKey() ?: return null
        return try {
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            ENC_PREFIX + android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored
        }
        val key = getOrCreateKey() ?: return null
        return try {
            val combined = android.util.Base64.decode(stored.removePrefix(ENC_PREFIX), android.util.Base64.NO_WRAP)
            if (combined.size <= GCM_IV_BYTES) return null
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = javax.crypto.Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                key,
                javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv),
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

internal actual object PlatformTokenStore {
    private const val PREFS_NAME = "streamify_auth"
    private const val KEY_SESSION_JSON = "stored_auth_session_v2"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var prefs: SharedPreferences? = null

    internal fun getPrefs(context: Context): SharedPreferences {
        return prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
            prefs = it
        }
    }

    actual fun init(context: Any?) {
        val ctx = context as? Context ?: return
        getPrefs(ctx)
    }

    actual fun load(): StoredAuthSession? {
        val sp = prefs ?: return null
        val encryptedJson = sp.getString(KEY_SESSION_JSON, null)
        if (!encryptedJson.isNullOrBlank()) {
            val raw = AndroidTokenCipher.decrypt(encryptedJson) ?: return null
            return runCatching { json.decodeFromString<StoredAuthSession>(raw) }.getOrNull()
        }
        // Migrate legacy v1 individual keys if present
        val userId = sp.getString("user_id", null) ?: return null
        val email = sp.getString("user_email", null) ?: return null
        val token = AndroidTokenCipher.decrypt(sp.getString("access_token", null)) ?: return null
        val refreshToken = AndroidTokenCipher.decrypt(sp.getString("refresh_token", null))
        val user = UserProfile(
            id = userId,
            email = email,
            displayName = sp.getString("display_name", null) ?: email.substringBefore("@"),
            avatarUrl = sp.getString("avatar_url", null) ?: "",
            isAdmin = sp.getBoolean("is_admin", false),
        )
        return StoredAuthSession(user = user, accessToken = token, refreshToken = refreshToken)
    }

    actual fun save(session: StoredAuthSession) {
        val sp = prefs ?: return
        val encoded = runCatching { json.encodeToString(StoredAuthSession.serializer(), session) }.getOrNull() ?: return
        val encrypted = AndroidTokenCipher.encrypt(encoded) ?: return
        sp.edit().apply {
            putBoolean("has_completed_onboarding", true)
            putString(KEY_SESSION_JSON, encrypted)
            putString("user_id", session.user.id)
            putString("user_email", session.user.email)
            putString("display_name", session.user.displayName)
            putString("avatar_url", session.user.avatarUrl)
            putBoolean("is_admin", session.user.isAdmin)
            AndroidTokenCipher.encrypt(session.accessToken)?.let { putString("access_token", it) }
            session.refreshToken?.let { rt ->
                AndroidTokenCipher.encrypt(rt)?.let { putString("refresh_token", it) }
            }
            apply()
        }
    }

    actual fun clear() {
        prefs?.edit()?.clear()?.apply()
    }
}
