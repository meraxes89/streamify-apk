package com.streamify.app.data.uniffi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * Phase 4a (MP-HAL06) — the `record_dac_clock` control-plane seam.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The DAC clock publisher (non-real-time worker) feeds hardware
 * presentation samples into the engine via
 * [StreamifyEngineBridge.recordDacClock] — the UDL `record_dac_clock`
 * entrypoint that republishes the latest clock inside every pushed
 * EngineSnapshot (MP-C01). This suite pins the seam contract:
 *
 *  • dispatch reaches the installed backend with the EXACT triple
 *    (house RecordingBackend pattern — see StreamifyEngineBridgeTest);
 *  • a degraded backend (no .so / no generated proxies — the hermetic
 *    JVM reality) yields a typed [StreamifyEngineBridge.EngineError]
 *    failure, never a raw Throwable, never a crash of the clock worker;
 *  • the push ingestion round-trip: a snapshot carrying a dacClock lands
 *    on the reactive surface verbatim (the Phase 3 lowering already
 *    covers this — here it doubles as the MP-C01 republish proof).
 *
 * NOT covered here (by design, Phase 3 house pattern): the reflective
 * `PushContractAdapter.buildDacClock` raising — it needs the real
 * generated `uniffi.streamify.DacPresentationClock` on the classpath; a
 * test-scoped FQN stand-in would collide with Alpha's bindgen output the
 * day it lands. The duck-typed LOWERING direction is covered by
 * EngineEventListenerTest; the raising direction compiles against the
 * same stable field-name contract and lights up with the real proxies.
 */
class RecordDacClockBridgeTest {

    private class RecordingClockBackend : StreamifyEngineBridge.EngineBackend {

        override val availability = StreamifyEngineBridge.Availability.MOCK

        val recorded = java.util.concurrent.ConcurrentLinkedQueue<DacPresentationClock>()

        override fun resolveStreamUrl(videoId: String): Result<StreamifyEngineBridge.ResolvedStream> =
            Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted resolve"))

        override fun hashCandidates(jsonPayload: String): Result<String> =
            Result.failure(StreamifyEngineBridge.EngineError.Unexpected("unscripted hash"))

        override fun recordDacClock(clock: DacPresentationClock): Result<Unit> {
            recorded += clock
            return Result.success(Unit)
        }
    }

    @After
    fun tearDown() {
        StreamifyEngineBridge.reset()
        StreamifyEngineBridge.resetTelemetry()
        StreamifyEngineBridge.resetRevisionGuard()
    }

    @Test
    fun `recordDacClock dispatches the exact hardware triple to the backend`() {
        val backend = RecordingClockBackend()
        StreamifyEngineBridge.installBackend(backend)

        val sample = DacPresentationClock(
            dacFramePosition = 480_000_000uL,
            sampleRate = 48_000u,
            monotonicNs = 10_000_000_000uL,
        )
        val result = StreamifyEngineBridge.recordDacClock(sample)

        assertEquals(Result.success(Unit), result)
        assertEquals(1, backend.recorded.size)
        assertEquals(sample, backend.recorded.first())
    }

    @Test
    fun `degraded backend fails typed, never throws`() {
        // No mock installed: the production backend on a hermetic JVM is
        // NATIVE_UNAVAILABLE — recordDacClock must surface a typed
        // EngineError, exactly like every other seam operation.
        val result = StreamifyEngineBridge.recordDacClock(
            DacPresentationClock(0uL, 48_000u, 1uL)
        )
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue(
            "expected an EngineError, got $err",
            err is StreamifyEngineBridge.EngineError,
        )
    }

    @Test
    fun `pushed snapshots republish the dac clock verbatim (MP-C01)`() {
        val clock = DacPresentationClock(
            dacFramePosition = 96_000uL,
            sampleRate = 48_000u,
            monotonicNs = 2_000_000_000uL,
        )
        StreamifyEngineBridge.onEngineSnapshot(
            EngineSnapshot(
                rev = 1uL,
                playbackState = StreamifyEngineBridge.PlaybackState.Playing,
                bufferStats = StreamifyEngineBridge.BufferStats.EMPTY,
                dacClock = clock,
            )
        )
        assertEquals(clock, StreamifyEngineBridge.engineSnapshots.value?.dacClock)
    }
}
