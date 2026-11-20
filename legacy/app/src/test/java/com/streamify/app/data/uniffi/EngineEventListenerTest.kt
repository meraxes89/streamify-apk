package com.streamify.app.data.uniffi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EngineEventListenerTest (Phase 3, MP-C02/MP-C16 — directive §A/§E) —
 * hermetic JVM proof of the reactive push surface:
 *
 *  • the monotonic revision guard: out-of-order and duplicate revs are
 *    dropped, higher revs land, and resetRevisionGuard re-arms for a
 *    restarted engine instance;
 *  • push snapshots advance the reactive telemetry surface
 *    ([StreamifyEngineBridge.playbackState], [StreamifyEngineBridge.bufferStats],
 *    [StreamifyEngineBridge.engineSnapshots]) with the same underrun-tally
 *    fusion the pulled path applies;
 *  • [EngineContract.EngineEventListenerImpl] delegates every snapshot;
 *  • bindEngineEvents / dispatchMediaCommand degrade to typed
 *    EngineUnavailable failures on unwired backends — never a raw
 *    Throwable — and dispatch reaches the backend seam when wired;
 *  • panic capture verification (MP-DEC-06 client side): a Rust
 *    `InternalError` lowers onto [StreamifyEngineBridge.EngineError.Internal],
 *    including through the InvocationTargetException wrapper reflection
 *    dispatch adds;
 *  • the new MP-C04 FFI variant names (AudioError/StorageError/CryptoError)
 *    map onto the sealed hierarchy;
 *  • the EXHAUSTIVE [StreamifyEngineBridge.handleEngineError] when handles
 *    every sealed variant (no else arm — exhaustiveness is compiler
 *    enforced; this test pins the no-throw contract for all of them).
 *
 * Runs inside the plain jvm-unit-suite CI shard — no Robolectric, no
 * native artifacts, no network.
 */
class EngineEventListenerTest {

    /**
     * Scriptable + recording mock over the two new Phase 3 seam operations
     * (house pattern: RecordingBackend in StreamifyEngineBridgeTest).
     */
    private class RecordingPushBackend(
        var bindResult: Result<Unit>? = null,
        var mediaCommandResult: Result<Unit>? = null,
    ) : StreamifyEngineBridge.EngineBackend {

        override val availability = StreamifyEngineBridge.Availability.MOCK

        val bindCalls = java.util.concurrent.atomic.AtomicInteger()
        val mediaCommandCalls =
            java.util.Collections.synchronizedList(mutableListOf<MediaCommand>())

        override fun resolveStreamUrl(videoId: String): Result<StreamifyEngineBridge.ResolvedStream> =
            Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted resolve"))

        override fun hashCandidates(jsonPayload: String): Result<String> =
            Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted hash"))

        override fun bindEngineEventListener(): Result<Unit> {
            bindCalls.incrementAndGet()
            return bindResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted bind"))
        }

        override fun dispatchMediaCommand(command: MediaCommand): Result<Unit> {
            mediaCommandCalls += command
            return mediaCommandResult
                ?: Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted command"))
        }
    }

    /**
     * Hermetic stand-ins for the generated `uniffi.streamify.EngineError.*`
     * exception classes the MP-C04 contract will emit (simple names are
     * what the duck-typed FFI mapping matches on).
     */
    private class InternalError(message: String?) : RuntimeException(message)
    private class AudioError(message: String?) : RuntimeException(message)
    private class StorageError(message: String?) : RuntimeException(message)
    private class CryptoError(message: String?) : RuntimeException(message)

    @After
    fun tearDown() {
        // Never leak a mock, a bound flag, a session tally or a revision
        // guard position into another test — the bridge is process-wide
        // and JUnit method order is arbitrary. Production resets the guard
        // at (re)bind (a restarted engine restarts its counter); tests
        // must do the same per-method for deterministic isolation.
        StreamifyEngineBridge.reset()
        StreamifyEngineBridge.resetTelemetry()
        StreamifyEngineBridge.resetRevisionGuard()
    }

    private fun snapshot(
        rev: Long,
        state: StreamifyEngineBridge.PlaybackState,
        underruns: Int = 0,
        fill: Float = 0f,
    ) = EngineSnapshot(
        rev = rev.toULong(),
        playbackState = state,
        bufferStats = StreamifyEngineBridge.BufferStats(
            bufferedDurationMs = 1_200uL,
            underrunCount = underruns.toUInt(),
            fillRatio = fill,
        ),
    )

    // ─────────────────── monotonic revision guard (§A.3) ───────────────

    @Test
    fun `first pushed snapshot lands and advances the reactive surface`() {
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 1, state = StreamifyEngineBridge.PlaybackState.Playing, fill = 0.75f))

        assertEquals(StreamifyEngineBridge.PlaybackState.Playing, StreamifyEngineBridge.playbackState.value)
        assertEquals(0.75f, StreamifyEngineBridge.bufferStats.value.fillRatio, 0.0001f)
        assertEquals(1uL, StreamifyEngineBridge.engineSnapshots.value?.rev)
    }

    @Test
    fun `out-of-order snapshot with a lower rev is dropped`() {
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 7, state = StreamifyEngineBridge.PlaybackState.Playing))

        // A stale queued snapshot delivered late (device sleep, background
        // transitions) must never regress the UI.
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 5, state = StreamifyEngineBridge.PlaybackState.Paused))

        assertEquals(StreamifyEngineBridge.PlaybackState.Playing, StreamifyEngineBridge.playbackState.value)
        assertEquals(7uL, StreamifyEngineBridge.engineSnapshots.value?.rev)
    }

    @Test
    fun `duplicate rev is dropped as out-of-order`() {
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 3, state = StreamifyEngineBridge.PlaybackState.Buffering))

        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 3, state = StreamifyEngineBridge.PlaybackState.Error))

        assertEquals(StreamifyEngineBridge.PlaybackState.Buffering, StreamifyEngineBridge.playbackState.value)
    }

    @Test
    fun `rev 0 is not a valid emission and never regresses the guard`() {
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 2, state = StreamifyEngineBridge.PlaybackState.Playing))
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 0, state = StreamifyEngineBridge.PlaybackState.Error))

        assertEquals(StreamifyEngineBridge.PlaybackState.Playing, StreamifyEngineBridge.playbackState.value)
    }

    @Test
    fun `resetRevisionGuard re-arms for a restarted engine instance`() {
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 99, state = StreamifyEngineBridge.PlaybackState.Playing))

        // A fresh engine instance restarts its counter at 1 — the guard
        // re-arms at (re)bind so the authoritative snapshot lands.
        StreamifyEngineBridge.resetRevisionGuard()
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 1, state = StreamifyEngineBridge.PlaybackState.Paused))

        assertEquals(StreamifyEngineBridge.PlaybackState.Paused, StreamifyEngineBridge.playbackState.value)
        assertEquals(1uL, StreamifyEngineBridge.engineSnapshots.value?.rev)
    }

    // ─────────────────── reactive publications (§A.2) ──────────────────

    @Test
    fun `pushed snapshots fuse the app-side underrun tally at the max`() {
        // App-side Media3 detection already counted two rebuffers.
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Playing)
        StreamifyEngineBridge.recordPlaybackState(StreamifyEngineBridge.PlaybackState.Buffering)
        assertEquals(2u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // The engine knows about one — the fusion keeps the higher count,
        // exactly like the pulled refreshBufferStats path.
        StreamifyEngineBridge.onEngineSnapshot(
            snapshot(rev = 1, state = StreamifyEngineBridge.PlaybackState.Playing, underruns = 1)
        )
        assertEquals(2u, StreamifyEngineBridge.bufferStats.value.underrunCount)

        // Once the engine's authoritative count surpasses the app tally,
        // the engine wins.
        StreamifyEngineBridge.onEngineSnapshot(
            snapshot(rev = 2, state = StreamifyEngineBridge.PlaybackState.Playing, underruns = 4)
        )
        assertEquals(4u, StreamifyEngineBridge.bufferStats.value.underrunCount)
    }

    @Test
    fun `resetTelemetry returns the push surface to the dormant state`() {
        StreamifyEngineBridge.onEngineSnapshot(snapshot(rev = 4, state = StreamifyEngineBridge.PlaybackState.Error))

        StreamifyEngineBridge.resetTelemetry()

        assertEquals(StreamifyEngineBridge.PlaybackState.Idle, StreamifyEngineBridge.playbackState.value)
        assertEquals(StreamifyEngineBridge.BufferStats.EMPTY, StreamifyEngineBridge.bufferStats.value)
        assertNull(StreamifyEngineBridge.engineSnapshots.value)
    }

    @Test
    fun `EngineEventListenerImpl delegates every snapshot to its callback`() {
        val seen = mutableListOf<EngineSnapshot>()
        val listener = EngineEventListenerImpl { seen += it }

        listener.onEngineEvent(snapshot(rev = 1, state = StreamifyEngineBridge.PlaybackState.Playing))
        listener.onEngineEvent(snapshot(rev = 2, state = StreamifyEngineBridge.PlaybackState.Paused))

        assertEquals(listOf(1uL, 2uL), seen.map { it.rev })
    }

    // ─────────────────── degraded & wired seam behavior ────────────────

    @Test
    fun `bindEngineEvents degrades to EngineUnavailable on the JVM - never throws`() {
        val result = StreamifyEngineBridge.bindEngineEvents()

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is StreamifyEngineBridge.EngineError.EngineUnavailable
        )
    }

    @Test
    fun `bindEngineEvents is idempotent per backend - a live registration is a no-op`() {
        val backend = RecordingPushBackend(bindResult = Result.success(Unit))
        StreamifyEngineBridge.installBackend(backend)

        assertTrue(StreamifyEngineBridge.bindEngineEvents().isSuccess)
        assertTrue(StreamifyEngineBridge.bindEngineEvents().isSuccess)

        assertEquals(1, backend.bindCalls.get())
    }

    @Test
    fun `bindEngineEvents failure does not latch the bound flag - retry stays possible`() {
        val backend = RecordingPushBackend(
            bindResult = Result.failure(StreamifyEngineBridge.EngineError.EngineUnavailable("contract pending"))
        )
        StreamifyEngineBridge.installBackend(backend)

        assertTrue(StreamifyEngineBridge.bindEngineEvents().isFailure)
        assertTrue(StreamifyEngineBridge.bindEngineEvents().isFailure)

        assertEquals(2, backend.bindCalls.get())
    }

    @Test
    fun `dispatchMediaCommand reaches the backend seam and degrades typed when unwired`() {
        // Unwired: typed failure, never a raw Throwable.
        val degraded = StreamifyEngineBridge.dispatchMediaCommand(MediaCommand.Next)
        assertTrue(degraded.isFailure)
        assertTrue(
            degraded.exceptionOrNull() is StreamifyEngineBridge.EngineError.EngineUnavailable
        )

        // Wired: the exact command object reaches the seam.
        val backend = RecordingPushBackend(mediaCommandResult = Result.success(Unit))
        StreamifyEngineBridge.installBackend(backend)
        val seek = MediaCommand.Seek(42_000)
        StreamifyEngineBridge.dispatchMediaCommand(seek)

        assertEquals(listOf<MediaCommand>(seek), backend.mediaCommandCalls)
    }

    // ───────────── panic capture verification (MP-DEC-06 client side) ──

    @Test
    fun `Rust panic InternalError lowers onto EngineError Internal`() {
        val lowered = StreamifyEngineBridge.EngineError.fromThrowable(
            InternalError("panicked at 'engine invariant', src/state.rs:42")
        )

        assertTrue(lowered is StreamifyEngineBridge.EngineError.Internal)
        assertEquals("panicked at 'engine invariant', src/state.rs:42", lowered.message)
    }

    @Test
    fun `panic capture survives the InvocationTargetException wrapper reflection dispatch adds`() {
        val wrapped = java.lang.reflect.InvocationTargetException(
            InternalError("panicked at 'core panicked', src/jam.rs:17")
        )

        val lowered = StreamifyEngineBridge.EngineError.fromThrowable(wrapped)

        assertTrue(lowered is StreamifyEngineBridge.EngineError.Internal)
        assertEquals("panicked at 'core panicked', src/jam.rs:17", lowered.message)
    }

    // ───────────── MP-C04 structured variant mapping (§E.1) ────────────

    @Test
    fun `AudioError StorageError CryptoError FFI names map onto the sealed hierarchy`() {
        assertTrue(
            StreamifyEngineBridge.EngineError.fromThrowable(AudioError("device open failed")) is
                StreamifyEngineBridge.EngineError.Audio
        )
        assertTrue(
            StreamifyEngineBridge.EngineError.fromThrowable(StorageError("sqlcipher locked")) is
                StreamifyEngineBridge.EngineError.Storage
        )
        assertTrue(
            StreamifyEngineBridge.EngineError.fromThrowable(CryptoError("keystore refused")) is
                StreamifyEngineBridge.EngineError.Crypto
        )
    }

    @Test
    fun `Network keeps source compatibility for pre-Phase3 construction sites`() {
        // Existing call sites construct Network(message, retryable) — the
        // structured kind/status payload rides optional defaults until the
        // MP-C04 nested enum lands, so nothing above the seam breaks.
        val err = StreamifyEngineBridge.EngineError.Network("CDN refused", retryable = false)

        assertEquals("unknown", err.kind)
        assertNull(err.status)
        assertEquals(false, err.retryable)
    }

    // ───────────── exhaustive handler (§E.1) ────────────────────────────

    @Test
    fun `exhaustive handleEngineError classifies every sealed variant without throwing`() {
        val everyVariant = listOf(
            StreamifyEngineBridge.EngineError.Network("net down", retryable = false, kind = "dns", status = 503u),
            StreamifyEngineBridge.EngineError.Audio("hal dead", kind = "device_open", deviceId = "bt:A2"),
            StreamifyEngineBridge.EngineError.Storage("db locked"),
            StreamifyEngineBridge.EngineError.Crypto("keychain refused"),
            StreamifyEngineBridge.EngineError.Internal("rust panic"),
            StreamifyEngineBridge.EngineError.Codec("bad container"),
            StreamifyEngineBridge.EngineError.InvalidState("wrong lifecycle step"),
            StreamifyEngineBridge.EngineError.BufferUnderrun("starved"),
            StreamifyEngineBridge.EngineError.InvalidInput("blank id"),
            StreamifyEngineBridge.EngineError.NotFound("no such track"),
            StreamifyEngineBridge.EngineError.Unauthorized("token expired"),
            StreamifyEngineBridge.EngineError.EngineUnavailable("cdylib not linked"),
            StreamifyEngineBridge.EngineError.EnginePanicked("catch_unwind shield"),
            StreamifyEngineBridge.EngineError.Unexpected("unclassified"),
        )

        // One entry per sealed variant — the compiler enforces the `when`
        // covers them all (no else arm), and this pins the no-throw
        // contract. Adding a variant without extending the handler breaks
        // compilation of the handler itself.
        assertEquals(14, everyVariant.size)
        for (err in everyVariant) {
            StreamifyEngineBridge.handleEngineError(err)
        }
    }

    // ───────────── DacPresentationClock projection sanity (§D) ─────────

    @Test
    fun `snapshots carry the hardware presentation clock through the projection`() {
        val clock = DacPresentationClock(
            dacFramePosition = 480_000uL,
            sampleRate = 48_000u,
            monotonicNs = 10_000_000_000uL,
        )
        StreamifyEngineBridge.onEngineSnapshot(
            EngineSnapshot(
                rev = 1uL,
                playbackState = StreamifyEngineBridge.PlaybackState.Playing,
                bufferStats = StreamifyEngineBridge.BufferStats.EMPTY,
                dacClock = clock,
            )
        )

        val pushed = StreamifyEngineBridge.engineSnapshots.value
        assertNotNull(pushed)
        // 480_000 frames @ 48kHz = 10_000ms — the calculator derives it
        // interpolation-free from the carried hardware clock.
        assertEquals(10_000L, pushed?.dacClock?.let { calculateMs(it) })
    }

    private fun calculateMs(clock: DacPresentationClock): Long =
        PlaybackPositionCalculator.calculatePlaybackPositionMs(clock)
}
