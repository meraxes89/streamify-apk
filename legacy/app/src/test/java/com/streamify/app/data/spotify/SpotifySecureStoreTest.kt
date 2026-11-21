package com.streamify.app.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException

/**
 * SpotifySecureStoreTest (SEC-04 — Android 8/8.1 cold-boot fix) — hermetic
 * JVM proof that a failing hardware keystore degrades instead of crashing:
 *
 *  • the exact production failure — MasterKey.Builder throwing
 *    `KeyStoreException: -28` from an early TrustZone/TEE on a cold boot —
 *    is scripted into the hardware probe; the chain must fall back to the
 *    software AES-256-GCM store and never rethrow;
 *  • a double failure (hardware + software both unusable) degrades to the
 *    safe in-memory session store;
 *  • every hop logs a warning (collected via the injected sink);
 *  • the software store seals values (no plaintext token bytes in the
 *    sink), round-trips strings and longs, survives instance recreation
 *    (persisted key material), and clear() ROTATES the key so old sealed
 *    values become unreadable while new writes stay readable.
 *
 * No Android framework classes on this path — javax.crypto only.
 */
class SpotifySecureStoreTest {

    private class MapStore : SpotifySecureStore.TokenStore {
        val map = linkedMapOf<String, String>()
        override fun getString(key: String, def: String?): String? = map[key] ?: def
        override fun getLong(key: String, def: Long): Long = map[key]?.toLongOrNull() ?: def
        override fun edit(): SpotifySecureStore.TokenStore.Editor = Editor()

        private inner class Editor : SpotifySecureStore.TokenStore.Editor {
            private val puts = ArrayList<Pair<String, String?>>()
            override fun putString(key: String, value: String?): SpotifySecureStore.TokenStore.Editor {
                puts += key to value; return this
            }
            override fun putLong(key: String, value: Long): SpotifySecureStore.TokenStore.Editor {
                puts += key to value.toString(); return this
            }
            override fun remove(key: String): SpotifySecureStore.TokenStore.Editor {
                puts += key to null; return this
            }
            override fun clear(): SpotifySecureStore.TokenStore.Editor {
                map.clear(); return this
            }
            override fun apply() {
                puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            }
        }
    }

    private class Warnings {
        val messages = mutableListOf<String>()
        val causes = mutableListOf<Throwable?>()
        val sink: (String, Throwable?) -> Unit = { m, t ->
            messages += m
            causes += t
        }
    }

    // ── The Android 8 crash, verbatim ──────────────────────────────────────

    @Test
    fun `android 8 keystore -28 degrades to the software store without throwing`() {
        val warnings = Warnings()
        // A real sink backs the fallback store — the degraded path must be
        // fully usable, not just constructible.
        val sink = linkedMapOf<String, String>()
        val store = SpotifySecureStore.create(
            hardwareAttempt = {
                throw KeyStoreException("android keystore: -28 (hardware keystore not initialized)")
            },
            softwareAttempt = {
                SpotifySecureStore.SoftwareCryptoStore(
                    read = { sink[it] },
                    write = { k, v -> if (v == null) sink.remove(k) else sink[k] = v },
                )
            },
            inMemoryFactory = { SpotifySecureStore.InMemoryStore() },
            warn = warnings.sink,
        )

        assertEquals("software", SpotifySecureStore.lastBackendKind)
        assertTrue(store is SpotifySecureStore.SoftwareCryptoStore)
        // The hardware failure was WARNED with its cause — never silent.
        assertEquals(1, warnings.causes.count { it is KeyStoreException })
        assertTrue(warnings.messages.first().contains("hardware keystore unavailable"))

        // The store is fully usable — the exact cold-boot path that used to
        // crash now round-trips a token.
        store.edit().putString("refresh_token", "rt-abc123").putLong("token_expiry", 42L).apply()
        assertEquals("rt-abc123", store.getString("refresh_token", null))
        assertEquals(42L, store.getLong("token_expiry", 0L))
    }

    @Test
    fun `double failure degrades to the safe in-memory session store`() {
        val warnings = Warnings()
        val store = SpotifySecureStore.create(
            hardwareAttempt = { throw KeyStoreException("-28") },
            softwareAttempt = { throw IllegalStateException("sandbox prefs unusable") },
            inMemoryFactory = { SpotifySecureStore.InMemoryStore() },
            warn = warnings.sink,
        )

        assertEquals("memory", SpotifySecureStore.lastBackendKind)
        assertTrue(store is SpotifySecureStore.InMemoryStore)
        store.edit().putString("access_token", "at").apply()
        assertEquals("at", store.getString("access_token", null))
        assertTrue(warnings.messages.any { it.contains("IN-MEMORY") })
    }

    @Test
    fun `healthy hardware keystore wins with no degradation warnings`() {
        val warnings = Warnings()
        val healthy = MapStore()
        val store = SpotifySecureStore.create(
            hardwareAttempt = { healthy },
            softwareAttempt = { throw IllegalStateException("must not be reached") },
            inMemoryFactory = { throw IllegalStateException("must not be reached") },
            warn = warnings.sink,
        )

        assertEquals("hardware", SpotifySecureStore.lastBackendKind)
        assertTrue(store === healthy)
        assertTrue(warnings.messages.isEmpty())
    }

    // ── Software store: seal, round-trip, persistence, rotation ───────────

    @Test
    fun `software store seals values - no plaintext token bytes in the sink`() {
        val sink = linkedMapOf<String, String>()
        val store = SpotifySecureStore.SoftwareCryptoStore(
            read = { sink[it] },
            write = { k, v -> if (v == null) sink.remove(k) else sink[k] = v },
        )
        store.edit().putString("refresh_token", "super-secret-token-value").apply()

        val persisted = sink["refresh_token"]
        assertTrue("sealed image must be present", persisted != null)
        assertFalse("sealed image must not contain the plaintext token", persisted!!.contains("super-secret"))
        assertTrue("sealed image is base64", persisted.matches(Regex("[A-Za-z0-9+/=]+")))
        assertEquals("super-secret-token-value", store.getString("refresh_token", null))
    }

    @Test
    fun `software store survives instance recreation through persisted key material`() {
        val sink = linkedMapOf<String, String>()
        val first = SpotifySecureStore.SoftwareCryptoStore(read = { sink[it] }, write = { k, v ->
            if (v == null) sink.remove(k) else sink[k] = v
        })
        first.edit().putString("access_token", "at-xyz").putLong("token_expiry", 123_456L).apply()

        // New process, same sink: the key material reloads and the value
        // unseals.
        val second = SpotifySecureStore.SoftwareCryptoStore(read = { sink[it] }, write = { k, v ->
            if (v == null) sink.remove(k) else sink[k] = v
        })
        assertEquals("at-xyz", second.getString("access_token", null))
        assertEquals(123_456L, second.getLong("token_expiry", 0L))
    }

    @Test
    fun `software store clear rotates the key - old seals die, new writes live`() {
        val sink = linkedMapOf<String, String>()
        val store = SpotifySecureStore.SoftwareCryptoStore(
            read = { sink[it] },
            write = { k, v -> if (v == null) sink.remove(k) else sink[k] = v },
        )
        store.edit().putString("refresh_token", "old-token").apply()
        val keyBefore = sink[SpotifySecureStore.SoftwareCryptoStore.KEY_MATERIAL]

        store.edit().clear().apply()
        val keyAfter = sink[SpotifySecureStore.SoftwareCryptoStore.KEY_MATERIAL]

        // Key rotated: old sealed values are permanently unreadable.
        assertTrue(keyBefore != null && keyAfter != null && keyBefore != keyAfter)
        assertNull("old seal must not unseal under the fresh key", store.getString("refresh_token", null))

        // New writes ride the fresh key and stay readable.
        store.edit().putString("refresh_token", "new-token").apply()
        assertEquals("new-token", store.getString("refresh_token", null))
    }

    @Test
    fun `software store remove drops a single entry`() {
        val sink = linkedMapOf<String, String>()
        val store = SpotifySecureStore.SoftwareCryptoStore(
            read = { sink[it] },
            write = { k, v -> if (v == null) sink.remove(k) else sink[k] = v },
        )
        store.edit().putString("a", "1").putString("b", "2").apply()
        store.edit().remove("a").apply()
        assertNull(store.getString("a", null))
        assertEquals("2", store.getString("b", null))
    }

    // ── In-memory last resort ──────────────────────────────────────────────

    @Test
    fun `in-memory store round-trips strings and longs for the session`() {
        val store = SpotifySecureStore.InMemoryStore()
        store.edit()
            .putString("sp_dc_cookie", "dc")
            .putLong("token_expiry", 77L)
            .apply()
        assertEquals("dc", store.getString("sp_dc_cookie", null))
        assertEquals(77L, store.getLong("token_expiry", 0L))
        store.edit().clear().apply()
        assertNull(store.getString("sp_dc_cookie", null))
        assertEquals(0L, store.getLong("token_expiry", 0L))
    }
}
