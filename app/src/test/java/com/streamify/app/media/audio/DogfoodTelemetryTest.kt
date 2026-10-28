package com.streamify.app.media.audio

import com.streamify.app.data.uniffi.DacPresentationClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 5 (MP-CUT01 / Beta): the dogfood telemetry object, verified on the
 * hermetic JVM shard — pure Kotlin + StateFlow, no device, no .so, no
 * debug build (the same discipline as AudioRoutePolicy's JVM matrix).
 *
 * Covers the MP-CUT01 directive's three telemetry metrics — underrun rate,
 * DAC drift, cold-start latency — plus the route-decision log and the
 * zero-crash fallback tally.
 */
class DogfoodTelemetryTest {

    @Before
    fun resetTelemetry() {
        DogfoodTelemetry.reset()
    }

    private fun routeDecision(
        route: AudioRoutePolicy.Route,
    ): AudioRoutePolicy.RouteDecision = AudioRoutePolicy.RouteDecision(
        route = route,
        compileFlag = true,
        runtimeOverride = false,
        nativeEdges = true,
        reason = "test decision",
    )

    private fun telemetry(
        underruns: Long,
        fullBursts: Long,
        playing: Boolean = true,
    ): RustAudioPipeline.PipelineTelemetry = RustAudioPipeline.PipelineTelemetry(
        generation = 1L,
        framesPulled = fullBursts * 1024L,
        feederUnderruns = underruns,
        fullBursts = fullBursts,
        dacClockSamples = 0L,
        playing = playing,
        tornOutput = false,
    )

    private fun dacClock(
        frames: Long,
        monotonicNs: Long,
        rate: UInt = 48_000u,
    ): DacPresentationClock = DacPresentationClock(
        dacFramePosition = frames.toULong(),
        sampleRate = rate,
        monotonicNs = monotonicNs.toULong(),
    )

    @Test
    fun recordsRouteDecisionsAndLogsTheReason() {
        DogfoodTelemetry.recordRouteDecision(routeDecision(AudioRoutePolicy.Route.RUST_AUDIO_PULL))

        val snapshot = DogfoodTelemetry.snapshot.value
        assertEquals("RUST_AUDIO_PULL", snapshot.route)
        assertEquals("test decision", snapshot.routeReason)
        // No pipeline yet: derived metrics absent, never fabricated.
        assertNull(snapshot.underrunRatePercent)
        assertNull(snapshot.dacDriftPpm)
        assertNull(snapshot.coldStartMs)
        assertEquals(0L, snapshot.framesPulled)
        assertEquals(0L, snapshot.legacyFallbacks)
    }

    @Test
    fun computesUnderrunRateFromPipelineTelemetry() {
        DogfoodTelemetry.recordRouteDecision(routeDecision(AudioRoutePolicy.Route.RUST_AUDIO_PULL))
        DogfoodTelemetry.recordPipelineTelemetry(telemetry(underruns = 1L, fullBursts = 99L))

        val snapshot = DogfoodTelemetry.snapshot.value
        assertNotNull(snapshot.underrunRatePercent)
        assertEquals(1.0f, snapshot.underrunRatePercent!!, 0.001f)

        // Degenerate case: zero bursts observed -> no rate yet (null).
        DogfoodTelemetry.recordPipelineTelemetry(telemetry(underruns = 0L, fullBursts = 0L))
        assertNull(DogfoodTelemetry.snapshot.value.underrunRatePercent)
    }

    @Test
    fun derivesDacDriftFromSuccessiveHardwareSamples() {
        DogfoodTelemetry.recordRouteDecision(routeDecision(AudioRoutePolicy.Route.RUST_AUDIO_PULL))

        DogfoodTelemetry.recordDacClockSample(dacClock(0L, 0L))
        // First sample: no pair yet -> no drift.
        assertNull(DogfoodTelemetry.snapshot.value.dacDriftPpm)

        // Exactly nominal: 10 s @ 48 kHz.
        DogfoodTelemetry.recordDacClockSample(dacClock(48_000L * 10L, 10_000_000_000L))
        val drift = DogfoodTelemetry.snapshot.value.dacDriftPpm
        assertNotNull(drift)
        assertTrue("expected ~0 ppm, got $drift", kotlin.math.abs(drift!!) < 1.0f)

        // Device running fast: +481 frames over 10 s -> ~+10020 ppm.
        DogfoodTelemetry.recordDacClockSample(dacClock(48_000L * 10L + 481L, 20_000_000_000L))
        val fast = DogfoodTelemetry.snapshot.value.dacDriftPpm
        assertNotNull(fast)
        assertTrue(
            "expected ~10k ppm, got $fast",
            fast!! > 9_000f && fast < 11_000f,
        )
    }

    @Test
    fun measuresColdStartFromRouteResolutionToFirstDacSample() {
        DogfoodTelemetry.recordRouteDecision(routeDecision(AudioRoutePolicy.Route.RUST_AUDIO_PULL))
        DogfoodTelemetry.recordDacClockSample(dacClock(48_000L, 1_000_000_000L))

        // Same-thread recording: the measured window is >= 0 ms (the JVM
        // wall clock advances in whole ms; on a fast host this may be 0).
        val coldStart = DogfoodTelemetry.snapshot.value.coldStartMs
        assertNotNull(coldStart)
        assertTrue(coldStart!! >= 0L)
        // Measured exactly once: later samples must not re-arm it.
        DogfoodTelemetry.recordDacClockSample(dacClock(96_000L, 2_000_000_000L))
        assertEquals(coldStart, DogfoodTelemetry.snapshot.value.coldStartMs)
    }

    @Test
    fun legacyRouteDoesNotArmTheColdStartStopwatch() {
        DogfoodTelemetry.recordRouteDecision(routeDecision(AudioRoutePolicy.Route.LEGACY_MEDIA3))
        DogfoodTelemetry.recordDacClockSample(dacClock(48_000L, 1_000_000_000L))

        assertNull(DogfoodTelemetry.snapshot.value.coldStartMs)
    }

    @Test
    fun talliesZeroCrashFallbacks() {
        assertEquals(0L, DogfoodTelemetry.snapshot.value.legacyFallbacks)

        DogfoodTelemetry.recordLegacyFallback("pipeline init failed: IllegalStateException: boom")
        DogfoodTelemetry.recordLegacyFallback("RustMedia3AudioSink init failed: RuntimeException: x")

        assertEquals(2L, DogfoodTelemetry.snapshot.value.legacyFallbacks)
        // The route record survives fallbacks (mixed dogfood lifecycle).
        assertEquals("not-yet-resolved", DogfoodTelemetry.snapshot.value.route)
    }

    @Test
    fun resetRestoresThePristineSnapshot() {
        DogfoodTelemetry.recordRouteDecision(routeDecision(AudioRoutePolicy.Route.RUST_AUDIO_PULL))
        DogfoodTelemetry.recordPipelineTelemetry(telemetry(underruns = 3L, fullBursts = 7L))
        DogfoodTelemetry.recordDacClockSample(dacClock(48_000L, 1_000_000_000L))
        DogfoodTelemetry.recordLegacyFallback("test")

        DogfoodTelemetry.reset()

        val snapshot = DogfoodTelemetry.snapshot.value
        assertEquals("not-yet-resolved", snapshot.route)
        assertNull(snapshot.underrunRatePercent)
        assertNull(snapshot.dacDriftPpm)
        assertNull(snapshot.coldStartMs)
        assertEquals(0L, snapshot.dacClockSamples)
        assertEquals(0L, snapshot.legacyFallbacks)
    }
}
