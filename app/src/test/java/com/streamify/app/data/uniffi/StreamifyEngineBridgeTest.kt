package com.streamify.app.data.uniffi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamifyEngineBridgeTest (UFFI-02) — hermetic JVM proof that the bridge
 * never requires host `.so` binaries:
 *
 *  • default backend degrades to NATIVE_UNAVAILABLE instead of throwing
 *    UnsatisfiedLinkError out of object init (JVM: no libstreamify_core_rs),
 *  • canary operations return typed EngineError failures — never raw
 *    Throwables,
 *  • mock backend installation scripts both success and sealed-error
 *    paths and records dispatches,
 *  • throwing backends are lowered onto the sealed hierarchy,
 *  • availability flow reflects backend swaps and reset().
 *
 * Runs inside the plain `jvm-unit-suite` CI shard — no Robolectric, no
 * native artifacts, no network.
 */
class StreamifyEngineBridgeTest {

    /** Scriptable + recording mock (house pattern: MockNativeBridge). */
    private class RecordingBackend(
        var resolveResult: Result<StreamifyEngineBridge.ResolvedStream>? = null,
        var hashResult: Result<String>? = null,
        var throwOnResolve: Throwable? = null
    ) : StreamifyEngineBridge.EngineBackend {

        override val availability = StreamifyEngineBridge.Availability.MOCK

        val resolveCalls = mutableListOf<String>()
        val hashCalls = mutableListOf<String>()

        override fun resolveStreamUrl(videoId: String): Result<StreamifyEngineBridge.ResolvedStream> {
            resolveCalls += videoId
            throwOnResolve?.let { throw it }
            return resolveResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted resolve"))
        }

        override fun hashCandidates(jsonPayload: String): Result<String> {
            hashCalls += jsonPayload
            return hashResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted hash"))
        }
    }

    @After
    fun tearDown() {
        // Never leak a mock into another test class on the shared JVM.
        StreamifyEngineBridge.reset()
    }

    // ───────────────────── hermetic degradation ────────────────────────

    @Test
    fun `default backend on the JVM degrades to NATIVE_UNAVAILABLE without throwing`() {
        // No mock installed and no host libstreamify_core_rs exists — object
        // init must survive the failed load and report the degraded state.
        assertEquals(
            StreamifyEngineBridge.Availability.NATIVE_UNAVAILABLE,
            StreamifyEngineBridge.availability.value
        )
    }

    @Test
    fun `canary ops fail with EngineUnavailable - never UnsatisfiedLinkError - on the JVM`() {
        val resolved = StreamifyEngineBridge.resolveStreamUrl("dQw4w9WgXcQ")
        assertTrue(resolved.isFailure)
        assertTrue(
            resolved.exceptionOrNull() is StreamifyEngineBridge.EngineError.EngineUnavailable
        )

        val hashed = StreamifyEngineBridge.hashCandidates("""{"candidates":[]}""")
        assertTrue(hashed.isFailure)
        assertTrue(
            hashed.exceptionOrNull() is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
    }

    // ───────────────────── contract validation ─────────────────────────

    @Test
    fun `blank inputs are rejected as InvalidInput before any backend dispatch`() {
        val backend = RecordingBackend()
        StreamifyEngineBridge.installBackend(backend)

        val blankResolve = StreamifyEngineBridge.resolveStreamUrl("   ")
        assertTrue(blankResolve.isFailure)
        assertTrue(
            blankResolve.exceptionOrNull() is StreamifyEngineBridge.EngineError.InvalidInput
        )
        assertTrue("bridge must not dispatch blank input", backend.resolveCalls.isEmpty())

        val blankHash = StreamifyEngineBridge.hashCandidates("")
        assertTrue(blankHash.isFailure)
        assertTrue(
            blankHash.exceptionOrNull() is StreamifyEngineBridge.EngineError.InvalidInput
        )
        assertTrue("bridge must not dispatch blank payload", backend.hashCalls.isEmpty())
    }

    // ───────────────────── mock dispatch ───────────────────────────────

    @Test
    fun `mock backend scripts success values and records dispatches`() {
        val backend = RecordingBackend(
            resolveResult = Result.success(
                StreamifyEngineBridge.ResolvedStream(
                    videoId = "abc123",
                    url = "https://cdn.example.com/stream/abc123"
                )
            ),
            hashResult = Result.success("deadbeefcafe")
        )
        StreamifyEngineBridge.installBackend(backend)

        assertEquals(StreamifyEngineBridge.Availability.MOCK, StreamifyEngineBridge.availability.value)

        val resolved = StreamifyEngineBridge.resolveStreamUrl("abc123")
        assertEquals("https://cdn.example.com/stream/abc123", resolved.getOrNull()?.url)
        assertEquals("abc123", resolved.getOrNull()?.videoId)

        val hashed = StreamifyEngineBridge.hashCandidates("""{"candidates":[1,2]}""")
        assertEquals("deadbeefcafe", hashed.getOrNull())

        assertEquals(listOf("abc123"), backend.resolveCalls)
        assertEquals(listOf("""{"candidates":[1,2]}"""), backend.hashCalls)
    }

    @Test
    fun `mock backend scripts sealed engine errors end to end`() {
        val backend = RecordingBackend(
            resolveResult = Result.failure(
                StreamifyEngineBridge.EngineError.Network("CDN refused", retryable = false)
            ),
            hashResult = Result.failure(
                StreamifyEngineBridge.EngineError.NotFound("no candidates in payload")
            )
        )
        StreamifyEngineBridge.installBackend(backend)

        val resolved = StreamifyEngineBridge.resolveStreamUrl("xyz")
        val resolveError = resolved.exceptionOrNull()
        assertTrue(resolveError is StreamifyEngineBridge.EngineError.Network)
        assertEquals(false, (resolveError as StreamifyEngineBridge.EngineError.Network).retryable)

        val hashed = StreamifyEngineBridge.hashCandidates("{}")
        assertTrue(hashed.exceptionOrNull() is StreamifyEngineBridge.EngineError.NotFound)
    }

    // ───────────────────── seam robustness ─────────────────────────────

    @Test
    fun `throwing backends are lowered onto the sealed hierarchy - not propagated`() {
        val backend = RecordingBackend(throwOnResolve = IllegalStateException("engine core panicked"))
        StreamifyEngineBridge.installBackend(backend)

        val resolved = StreamifyEngineBridge.resolveStreamUrl("boom")
        assertTrue(resolved.isFailure)
        val error = resolved.exceptionOrNull()
        assertTrue("expected EnginePanicked, got $error", error is StreamifyEngineBridge.EngineError.EnginePanicked)
    }

    @Test
    fun `error mapping projects raw failure modes onto the sealed hierarchy`() {
        val fromLinkError = StreamifyEngineBridge.EngineError.fromThrowable(
            UnsatisfiedLinkError("no streamify_core_rs in java.library.path")
        )
        assertTrue(fromLinkError is StreamifyEngineBridge.EngineError.EngineUnavailable)

        val fromIllegalArg = StreamifyEngineBridge.EngineError.fromThrowable(
            IllegalArgumentException("videoId malformed")
        )
        assertTrue(fromIllegalArg is StreamifyEngineBridge.EngineError.InvalidInput)

        val fromSecurity = StreamifyEngineBridge.EngineError.fromThrowable(
            SecurityException("resolver token rejected")
        )
        assertTrue(fromSecurity is StreamifyEngineBridge.EngineError.Unauthorized)

        val passthrough = StreamifyEngineBridge.EngineError.NotFound("already typed")
        assertEquals(passthrough, StreamifyEngineBridge.EngineError.fromThrowable(passthrough))
    }

    @Test
    fun `reset restores the degraded native backend`() {
        StreamifyEngineBridge.installBackend(RecordingBackend())
        assertEquals(StreamifyEngineBridge.Availability.MOCK, StreamifyEngineBridge.availability.value)

        StreamifyEngineBridge.reset()
        assertEquals(
            StreamifyEngineBridge.Availability.NATIVE_UNAVAILABLE,
            StreamifyEngineBridge.availability.value
        )
    }

    @Test
    fun `availability flow reflects backend swaps`() {
        val first = RecordingBackend()
        StreamifyEngineBridge.installBackend(first)
        assertEquals(
            StreamifyEngineBridge.Availability.MOCK,
            StreamifyEngineBridge.availability.value
        )
        StreamifyEngineBridge.reset()
        assertEquals(
            StreamifyEngineBridge.Availability.NATIVE_UNAVAILABLE,
            StreamifyEngineBridge.availability.value
        )
    }
}
