package com.streamify.app.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SEC-08 regression tests: the engine must degrade to "AI assist off" when
 * no key pool is configured, with no embedded fallback credentials to fall
 * back to. The old `FALLBACK_KEYS` round-robin shipped five billable API
 * keys inside every APK (ZhipuAiEngine.kt:25-31 pre-SEC-08).
 *
 * Hermetic JVM shard discipline: no device, no .so — the native bridge is
 * unreachable here (UnsatisfiedLinkError is swallowed by the engine's key
 * probe), and the debug BuildConfig injects an empty ZHIPU_API_URL /
 * ZHIPU_API_KEYS, which is exactly the "unconfigured build" contract.
 */
class ZhipuAiEngineTest {

    @Test
    fun engineNotConfiguredWithoutInjectedKeys() {
        // Neither the build-time pool nor the native bridge's runtime
        // STREAMIFY_ZHIPU_KEYS pool is available in this environment.
        assertFalse(ZhipuAiEngine.isConfigured)
    }

    @Test
    fun completeReturnsNullGracefullyWhenUnconfigured() = runBlocking {
        // Must return null (callers fall back to deterministic paths),
        // never throw, and never attempt network I/O without a key.
        assertNull(ZhipuAiEngine.complete("system prompt", "user prompt"))
    }
}
