package com.streamify.app.data.uniffi

import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StreamifyEngineBridgeTest (UFFI-02 + UFFI-04) — hermetic JVM proof that
 * the bridge never requires host `.so` binaries or generated `uniffi.*`
 * classes:
 *
 *  • default backend degrades to NATIVE_UNAVAILABLE instead of throwing
 *    UnsatisfiedLinkError out of object init (JVM: no libstreamify_core_rs,
 *    and the generated proxies are never on the compile classpath by
 *    design — reflection dispatch is the seam);
 *  • canary operations return typed EngineError failures — never raw
 *    Throwables;
 *  • mock backend installation scripts both success and sealed-error
 *    paths and records dispatches — canaries AND the engine control plane
 *    (initialize/setVolume/fetchPlaybackState/fetchBufferStats);
 *  • StateFlow telemetry publications emit the correct values during
 *    state transitions: every record lands on [StreamifyEngineBridge.playbackState],
 *    Playing -> Buffering rebuffers bump the underrun tally, entering Idle
 *    resets it, and refreshBufferStats publishes the engine sample fused
 *    with the app-side tally (max) until Rust-side producers supersede it;
 *  • the Rust EngineError variants (NetworkError, CodecError, InvalidState,
 *    BufferUnderrun, InternalError) map onto the sealed hierarchy by FFI
 *    class simple-name, including through the InvocationTargetException
 *    wrapper that reflection dispatch adds;
 *  • engine control plane seam defaults degrade to EngineUnavailable on
 *    backends that do not implement them — never a raw Throwable;
 *  • throwing backends are lowered onto the sealed hierarchy;
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
        var throwOnResolve: Throwable? = null,
        var initializeResult: Result<Unit>? = null,
        var volumeResult: Result<Unit>? = null,
        var playbackStateResult: Result<StreamifyEngineBridge.PlaybackState>? = null,
        var bufferStatsResult: Result<StreamifyEngineBridge.BufferStats>? = null,
    ) : StreamifyEngineBridge.EngineBackend {

        override val availability = StreamifyEngineBridge.Availability.MOCK

        val resolveCalls = mutableListOf<String>()
        val hashCalls = mutableListOf<String>()
        val volumeCalls = mutableListOf<Float>()
        var initializeCount: Int = 0
            private set

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

        override fun initializeEngine(): Result<Unit> {
            initializeCount++
            return initializeResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted initialize"))
        }

        override fun setVolume(volume: Float): Result<Unit> {
            volumeCalls += volume
            return volumeResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted volume"))
        }

        override fun fetchPlaybackState(): Result<StreamifyEngineBridge.PlaybackState> =
            playbackStateResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted state"))

        override fun fetchBufferStats(): Result<StreamifyEngineBridge.BufferStats> =
            bufferStatsResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted stats"))
    }

    /**
     * Hermetic stand-ins for the generated `uniffi.streamify.EngineError.*`
     * exception classes: identical simple names (nested classes report the
     * bare variant name), so [StreamifyEngineBridge.EngineError.fromThrowable]
     * exercises the exact duck-typed FFI mapping the native dispatch relies
     * on — without a single generated class on the classpath.
     */
    private class NetworkError(message: String?) : RuntimeException(message)
    private class CodecError(message: String?) : RuntimeException(message)
    private class InvalidState(message: String?) : RuntimeException(message)
    private class BufferUnderrun(message: String?) : RuntimeException(message)
    private class InternalError(message: String?) : RuntimeException(message)

    @After
    fun tearDown() {
        // Never leak a mock or a session tally into another test class on
        // the shared JVM — the bridge is process-wide.
        StreamifyEngineBridge.reset()
        StreamifyEngineBridge.resetTelemetry()
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

    @Test
    fun `engine control plane on the unwired native backend fails with EngineUnavailable - never throws`() {
        // The JVM shard has neither the cdylib nor the generated proxies:
        // every UFFI-04 control-plane op must degrade, not crash.
        assertTrue(
            StreamifyEngineBridge.initializeEngine().exceptionOrNull()
                is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
        assertTrue(
            StreamifyEngineBridge.setVolume(0.5f).exceptionOrNull()
                is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
        assertTrue(
            StreamifyEngineBridge.enginePlaybackState().exceptionOrNull()
                is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
        assertTrue(
            StreamifyEngineBridge.refreshBufferStats().exceptionOrNull()
                is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
        // Degraded refresh must not publish anything over the flow.
        assertEquals(StreamifyEngineBridge.BufferStats.EMPTY, StreamifyEngineBridge.bufferStats.value)
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

    @Test
    fun `setVolume rejects NaN before dispatch and clamps out-of-range values onto the contract range`() {
        val backend = RecordingBackend()
        StreamifyEngineBridge.installBackend(backend)

        val nan = StreamifyEngineBridge.setVolume(Float.NaN)
        assertTrue(nan.isFailure)
        assertTrue(nan.exceptionOrNull() is StreamifyEngineBridge.EngineError.InvalidInput)
        assertTrue("NaN must never reach the backend", backend.volumeCalls.isEmpty())

        StreamifyEngineBridge.setVolume(1.7f)
        StreamifyEngineBridge.setVolume(-0.4f)
        StreamifyEngineBridge.setVolume(0.25f)
        assertEquals(listOf(1.0f, 0.0f, 0.25f), backend.volumeCalls)
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

    @Test
    fun `engine control plane dispatches onto the mock backend`() {
        val backend = RecordingBackend(
            initializeResult = Result.success(Unit),
            volumeResult = Result.success(Unit),
            playbackStateResult = Result.success(StreamifyEngineBridge.PlaybackState.Playing),
            bufferStatsResult = Result.success(
                StreamifyEngineBridge.BufferStats(bufferedDurationMs = 4_200uL, underrunCount = 1u, fillRatio = 0.9f)
            )
        )
        StreamifyEngineBridge.installBackend(backend)

        assertTrue(StreamifyEngineBridge.initializeEngine().isSuccess)
        assertEquals(1, backend.initializeCount)
        assertTrue(StreamifyEngineBridge.setVolume(0.5f).isSuccess)
        assertEquals(listOf(0.5f), backend.volumeCalls)

        assertEquals(
            StreamifyEngineBridge.PlaybackState.Playing,
            StreamifyEngineBridge.enginePlaybackState().getOrNull()
        )

        val sample = StreamifyEngineBridge.refreshBufferStats().getOrNull()
        assertEquals(4_200uL, sample?.bufferedDurationMs)
        assertEquals(0.9f, sample?.fillRatio)

        // Scripted sealed failures cross the bridge without raw Throwables.
        val failing = RecordingBackend(
            playbackStateResult = Result.failure(
                StreamifyEngineBridge.EngineError.Codec("opus header mangled")
            )
        )
        StreamifyEngineBridge.installBackend(failing)
        assertTrue(
            StreamifyEngineBridge.enginePlaybackState().exceptionOrNull()
                is StreamifyEngineBridge.EngineError.Codec
        )
    }

    @Test
    fun `engine control plane seam defaults degrade to EngineUnavailable on backends that do not implement them`() {
        val bare = object : StreamifyEngineBridge.EngineBackend {
            override val availability = StreamifyEngineBridge.Availability.MOCK
            override fun resolveStreamUrl(videoId: String): Result<StreamifyEngineBridge.ResolvedStream> =
                Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unused"))
            override fun hashCandidates(jsonPayload: String): Result<String> =
                Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unused"))
        }
        StreamifyEngineBridge.installBackend(bare)

        for (op in listOf(
            bare.initializeEngine(),
            bare.setVolume(0.5f),
            bare.fetchPlaybackState(),
            bare.fetchBufferStats(),
        )) {
            assertTrue(op.isFailure)
            assertTrue(op.exceptionOrNull() is StreamifyEngineBridge.EngineError.EngineUnavailable)
        }

        // And through the bridge facade — the funnel stays a Result, never a throw.
        assertTrue(
            StreamifyEngineBridge.initializeEngine().exceptionOrNull()
                is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
    }

    // ─────────────────── StateFlow telemetry emissions ─────────────────

    @Test
    fun `recordPlaybackState publishes transitions and detects session underruns`() {
        StreamifyEngineBridge.installBackend(RecordingBackend())

        // Fresh session: dormant flows.
        assertEquals(StreamifyEngineBridge.PlaybackState.Idle, StreamifyEngineBridge.playbackState.value)
        assertEquals(StreamifyEngineBridge.BufferStats.EMPTY, StreamifyEngineBridge.bufferStats.value)

        // Initial warm-up buffering is NOT an underrun (previous state Idle).
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(StreamifyEngineBridge.PlaybackState.Buffering, StreamifyEngineBridge.playbackState.value)
        assertEquals(0u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // Rebuffer: Playing -> Buffering starved the ring.
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(1u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // A second rebuffer tallies cumulatively within the session.
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(2u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // Paused -> Buffering (a seek while paused) is a stall, not an underrun.
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Paused)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(2u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // Session end: entering Idle resets the tally for the next session.
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Idle)
        assertEquals(StreamifyEngineBridge.PlaybackState.Idle, StreamifyEngineBridge.playbackState.value)
        assertEquals(0u, StreamifyEngineBridge.bufferStats.value.underrunCount)
    }

    @Test
    fun `playbackState flow delivers every transition to subscribers`() = kotlinx.coroutines.runBlocking {
        StreamifyEngineBridge.installBackend(RecordingBackend())

        val emissions = mutableListOf<StreamifyEngineBridge.PlaybackState>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            StreamifyEngineBridge.playbackState.collect { emissions += it }
        }

        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Paused)

        job.cancel()

        assertEquals(
            listOf(
                // StateFlow replays the current value on subscription…
                StreamifyEngineBridge.PlaybackState.Idle,
                // …then every publication lands in order.
                StreamifyEngineBridge.PlaybackState.Buffering,
                StreamifyEngineBridge.PlaybackState.Playing,
                StreamifyEngineBridge.PlaybackState.Paused,
            ),
            emissions
        )
    }

    @Test
    fun `refreshBufferStats publishes the engine sample fused with the app-side underrun tally`() {
        val backend = RecordingBackend(
            bufferStatsResult = Result.success(
                StreamifyEngineBridge.BufferStats(
                    bufferedDurationMs = 8_000uL,
                    underrunCount = 0u,
                    fillRatio = 0.75f,
                )
            )
        )
        StreamifyEngineBridge.installBackend(backend)

        // Starve the ring twice before pulling the engine sample.
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(2u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        val sample = StreamifyEngineBridge.refreshBufferStats()
        // The return value is the raw engine sample…
        assertTrue(sample.isSuccess)
        assertEquals(0u, sample.getOrNull()?.underrunCount)
        assertEquals(8_000uL, sample.getOrNull()?.bufferedDurationMs)
        // …while the published flow carries the sample fused with the
        // app-side tally (max) — the engine has no producer wired yet.
        assertEquals(
            StreamifyEngineBridge.BufferStats(
                bufferedDurationMs = 8_000uL,
                underrunCount = 2u,
                fillRatio = 0.75f,
            ),
            StreamifyEngineBridge.bufferStats.value,
        )
    }

    @Test
    fun `refreshBufferStats lets the engine-authoritative underrun count supersede the app tally`() {
        val backend = RecordingBackend(
            bufferStatsResult = Result.success(
                StreamifyEngineBridge.BufferStats(bufferedDurationMs = 1_000uL, underrunCount = 5u, fillRatio = 0.5f)
            )
        )
        StreamifyEngineBridge.installBackend(backend)

        // One app-side rebuffer (tally 1)…
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(1u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // …then the engine sample reports five underruns (Rust-side
        // producers live): max(5, 1) keeps the authoritative count.
        StreamifyEngineBridge.refreshBufferStats()
        assertEquals(5u, StreamifyEngineBridge.bufferStats.value.underrunCount)
    }

    @Test
    fun `resetTelemetry returns the flows to the dormant session state`() {
        StreamifyEngineBridge.installBackend(RecordingBackend())
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertTrue(StreamifyEngineBridge.bufferStats.value.underrunCount > 0u)

        StreamifyEngineBridge.resetTelemetry()
        assertEquals(StreamifyEngineBridge.PlaybackState.Idle, StreamifyEngineBridge.playbackState.value)
        assertEquals(StreamifyEngineBridge.BufferStats.EMPTY, StreamifyEngineBridge.bufferStats.value)
    }

    // ───────────────────── ffi error mapping ───────────────────────────

    @Test
    fun `ffi error variant class names map onto the sealed hierarchy`() {
        val network = StreamifyEngineBridge.EngineError.fromThrowable(NetworkError("tls handshake failed"))
        assertTrue(network is StreamifyEngineBridge.EngineError.Network)
        assertEquals("tls handshake failed", network.message)

        val codec = StreamifyEngineBridge.EngineError.fromThrowable(CodecError("bad frame"))
        assertTrue(codec is StreamifyEngineBridge.EngineError.Codec)
        assertEquals("bad frame", codec.message)

        val invalidState = StreamifyEngineBridge.EngineError.fromThrowable(InvalidState("not initialized"))
        assertTrue(invalidState is StreamifyEngineBridge.EngineError.InvalidState)
        assertEquals("not initialized", invalidState.message)

        val underrun = StreamifyEngineBridge.EngineError.fromThrowable(BufferUnderrun("ring starved"))
        assertTrue(underrun is StreamifyEngineBridge.EngineError.BufferUnderrun)
        assertEquals("ring starved", underrun.message)

        val internal = StreamifyEngineBridge.EngineError.fromThrowable(InternalError("engine fault"))
        assertTrue(internal is StreamifyEngineBridge.EngineError.Internal)
        assertEquals("engine fault", internal.message)
    }

    @Test
    fun `ffi variants with blank messages fall back to the contract diagnostics`() {
        // Flat UDL error enums carry no message field — the mapping must
        // still produce an actionable sealed case.
        val blank = StreamifyEngineBridge.EngineError.fromThrowable(InternalError(null))
        assertTrue(blank is StreamifyEngineBridge.EngineError.Internal)
        assertEquals("internal error: unexpected engine fault", blank.message)
    }

    @Test
    fun `reflection wrapper exceptions unwrap to the ffi cause`() {
        // NativeEngineBackend dispatches via Method.invoke — engine
        // failures arrive wrapped in InvocationTargetException.
        val wrapped = java.lang.reflect.InvocationTargetException(CodecError("opus header mangled"))
        val mapped = StreamifyEngineBridge.EngineError.fromThrowable(wrapped)
        assertTrue(mapped is StreamifyEngineBridge.EngineError.Codec)
        assertEquals("opus header mangled", mapped.message)
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
