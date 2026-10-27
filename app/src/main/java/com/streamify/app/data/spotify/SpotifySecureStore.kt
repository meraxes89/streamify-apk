package com.streamify.app.data.spotify

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamify.app.util.SLog
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SpotifySecureStore — resilient token persistence (SEC-04, Android 8 fix).
 *
 * Root cause this closes: on Android 8/8.1 devices with early hardware
 * TrustZone/TEE keystores, `MasterKey.Builder(context).build()` throws an
 * unhandled `KeyStoreException: -28` during cold boot — it used to run as a
 * bare class-initializer field BEFORE the EncryptedSharedPreferences
 * try-catch could see it, crashing the app at startup.
 *
 * The probe chain now degrades instead of crashing, in order:
 *
 *  1. HARDWARE — MasterKey (AES256_GCM) + EncryptedSharedPreferences, the
 *     full AndroidX security-crypto stack (TEE-backed);
 *  2. SOFTWARE — AES-256-GCM encrypted store under a random key persisted
 *     in the app's private sandbox: strictly weaker than TEE isolation but
 *     immune to the flaky early-TEE keystore, and never plaintext;
 *  3. MEMORY — safe in-memory session store: tokens live for the process
 *     lifetime only, with a loud warning. Worst case is a re-login, never
 *     a crash and never a lost session mid-use.
 *
 * Every hop logs a warning with the cause. All three backends implement the
 * same [TokenStore] surface [SpotifyAuthManager] consumes, so no call site
 * knows which one won.
 */
internal object SpotifySecureStore {

    private const val TAG = "SpotifySecureStore"

    /** The minimal persistence surface the auth manager uses. */
    interface TokenStore {
        fun getString(key: String, def: String?): String?
        fun getLong(key: String, def: Long): Long
        fun edit(): Editor

        interface Editor {
            fun putString(key: String, value: String?): Editor
            fun putLong(key: String, value: Long): Editor
            fun remove(key: String): Editor
            fun clear(): Editor
            fun apply()
        }
    }

    /** Which backend won the last probe chain (diagnostics + test hook). */
    @Volatile var lastBackendKind: String = "unset"
        private set

    /** Production probe chain: hardware keystore → software AES-GCM → memory. */
    fun create(context: Context): TokenStore = create(
        hardwareAttempt = { hardware(context) },
        softwareAttempt = { software(context) },
        inMemoryFactory = { InMemoryStore() },
    )

    /**
     * Injectable probe chain (hermetic tests script each hop). A probe
     * throws when its backend is unusable — the chain catches
     * [java.security.KeyStoreException], [java.security.GeneralSecurityException]
     * and anything else the platform raises, and walks down.
     */
    fun create(
        hardwareAttempt: () -> TokenStore,
        softwareAttempt: () -> TokenStore,
        inMemoryFactory: () -> TokenStore,
        warn: (String, Throwable?) -> Unit = { m, t ->
            if (t != null) SLog.st(TAG, m, t) else SLog.w(TAG, m)
        },
    ): TokenStore {
        try {
            val hw = hardwareAttempt()
            lastBackendKind = KIND_HARDWARE
            return hw
        } catch (t: Throwable) {
            warn(
                "hardware keystore unavailable — degrading to the software " +
                    "encrypted store (Android 8/8.1 early TrustZone KeyStoreException " +
                    "is the expected cause here)",
                t,
            )
        }
        try {
            val sw = softwareAttempt()
            lastBackendKind = KIND_SOFTWARE
            warn("token store running on the SOFTWARE AES-256-GCM fallback", null)
            return sw
        } catch (t: Throwable) {
            warn("software encrypted store unavailable — degrading to the in-memory session store", t)
        }
        val mem = inMemoryFactory()
        lastBackendKind = KIND_MEMORY
        warn("token store running IN-MEMORY: tokens survive only this process — " +
            "the next cold start requires a fresh login", null)
        return mem
    }

    // ── Backend 1: hardware keystore ───────────────────────────────────────

    private fun hardware(context: Context): TokenStore {
        // SEC-04: MasterKey construction lives INSIDE the guarded probe — the
        // KeyStoreException -28 from an early TEE lands in the chain's
        // catch, never on the cold-boot path.
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            "spotify_secure_tokens",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return PrefsStore(prefs)
    }

    // ── Backend 2: software AES-256-GCM ────────────────────────────────────

    private fun software(context: Context): TokenStore = SoftwareCryptoStore(
        read = { key -> context.getSharedPreferences("spotify_secure_tokens_sw", Context.MODE_PRIVATE).getString(key, null) },
        write = { key, value ->
            val prefs = context.getSharedPreferences("spotify_secure_tokens_sw", Context.MODE_PRIVATE)
            if (value == null) prefs.edit().remove(key).apply()
            else prefs.edit().putString(key, value).apply()
        },
    )

    /**
     * Software AES-256-GCM value store over an injectable key-value sink
     * (SharedPreferences in production, a map in tests). Each value is
     * sealed with a fresh 96-bit IV; the persisted image is
     * `base64(iv || ciphertext||tag)`. The key is a random 256-bit key
     * persisted (base64) under [KEY_MATERIAL] in the same sink — sandboxed
     * by the OS private-storage boundary, though not TEE-isolated.
     * [TokenStore.Editor.clear] ROTATES the key: every previously sealed
     * value becomes unreadable ciphertext forever, while values written
     * afterwards seal under the fresh key and survive restarts.
     */
    internal class SoftwareCryptoStore(
        private val read: (String) -> String?,
        private val write: (String, String?) -> Unit,
    ) : TokenStore {

        private val secureRandom = SecureRandom()
        private val lock = Any()

        @Volatile private var cachedKey: ByteArray? = null

        private fun currentKey(): ByteArray = cachedKey ?: synchronized(lock) {
            cachedKey ?: run {
                val decoded = read(KEY_MATERIAL)
                    ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                val fresh = if (decoded != null && decoded.size == KEY_BYTES) {
                    decoded
                } else {
                    ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
                        .also { write(KEY_MATERIAL, Base64.getEncoder().encodeToString(it)) }
                }
                cachedKey = fresh
                fresh
            }
        }

        private fun seal(plain: String): String {
            val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(currentKey(), "AES"), GCMParameterSpec(TAG_BITS, iv))
            val ct = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
            val image = ByteArray(iv.size + ct.size)
            iv.copyInto(image)
            ct.copyInto(image, iv.size)
            return Base64.getEncoder().encodeToString(image)
        }

        private fun unseal(sealed: String): String? = runCatching {
            val image = Base64.getDecoder().decode(sealed)
            if (image.size <= IV_BYTES) return@runCatching null
            val iv = image.copyOfRange(0, IV_BYTES)
            val ct = image.copyOfRange(IV_BYTES, image.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(currentKey(), "AES"), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), StandardCharsets.UTF_8)
        }.getOrNull()

        override fun getString(key: String, def: String?): String? {
            val sealed = read(key) ?: return def
            return unseal(sealed) ?: def
        }

        override fun getLong(key: String, def: Long): Long =
            getString(key, null)?.toLongOrNull() ?: def

        override fun edit(): TokenStore.Editor = Editor()

        private inner class Editor : TokenStore.Editor {
            private val ops = ArrayList<() -> Unit>()

            override fun putString(key: String, value: String?): TokenStore.Editor {
                ops += { if (value == null) write(key, null) else write(key, seal(value)) }
                return this
            }

            override fun putLong(key: String, value: Long): TokenStore.Editor {
                putString(key, value.toString())
                return this
            }

            override fun remove(key: String): TokenStore.Editor {
                ops += { write(key, null) }
                return this
            }

            override fun clear(): TokenStore.Editor {
                // Key rotation: every existing sealed value becomes
                // permanently unreadable (fresh key cannot unseal old
                // images) — a true confidentiality wipe without needing an
                // enumerate op on the sink — while new writes ride the
                // fresh key and stay readable across restarts.
                ops += { rotateKey() }
                return this
            }

            override fun apply() {
                synchronized(lock) { ops.forEach { it() } }
            }

            private fun rotateKey() {
                val fresh = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
                write(KEY_MATERIAL, Base64.getEncoder().encodeToString(fresh))
                cachedKey = fresh
            }
        }

        companion object {
            internal const val KEY_MATERIAL = "__sw_key_material"
            private const val TRANSFORM = "AES/GCM/NoPadding"
            private const val KEY_BYTES = 32
            private const val IV_BYTES = 12
            private const val TAG_BITS = 128
        }
    }

    // ── Backend 3: in-memory session store ─────────────────────────────────

    /** Process-lifetime token store — the never-crash last resort. */
    internal class InMemoryStore : TokenStore {
        private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

        override fun getString(key: String, def: String?): String? = map[key] ?: def

        override fun getLong(key: String, def: Long): Long =
            map[key]?.toLongOrNull() ?: def

        override fun edit(): TokenStore.Editor = Editor()

        private inner class Editor : TokenStore.Editor {
            private val puts = ArrayList<Pair<String, String?>>()

            override fun putString(key: String, value: String?): TokenStore.Editor {
                puts += key to value
                return this
            }

            override fun putLong(key: String, value: Long): TokenStore.Editor {
                puts += key to value.toString()
                return this
            }

            override fun remove(key: String): TokenStore.Editor {
                puts += key to null
                return this
            }

            override fun clear(): TokenStore.Editor {
                map.clear()
                return this
            }

            override fun apply() {
                puts.forEach { (k, v) ->
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    /** Adapter: plain SharedPreferences as a [TokenStore] (backend 1). */
    private class PrefsStore(private val prefs: SharedPreferences) : TokenStore {
        override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
        override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
        override fun edit(): TokenStore.Editor = PrefsEditor(prefs.edit())

        private class PrefsEditor(private val e: SharedPreferences.Editor) : TokenStore.Editor {
            override fun putString(key: String, value: String?): TokenStore.Editor { e.putString(key, value); return this }
            override fun putLong(key: String, value: Long): TokenStore.Editor { e.putLong(key, value); return this }
            override fun remove(key: String): TokenStore.Editor { e.remove(key); return this }
            override fun clear(): TokenStore.Editor { e.clear(); return this }
            override fun apply() = e.apply()
        }
    }

    private const val KIND_HARDWARE = "hardware"
    private const val KIND_SOFTWARE = "software"
    private const val KIND_MEMORY = "memory"
}
