package com.streamify.shared

import com.streamify.shared.model.DacClockSample
import com.streamify.shared.model.PlaybackStatus
import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlaybackUiState
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.model.PlayerCommandResult
import com.streamify.shared.model.dacDriftPpm
import com.streamify.shared.model.formatPlaybackTime
import com.streamify.shared.model.positionMsFromDacClock
import com.streamify.shared.model.progressFraction
import com.streamify.shared.state.PlaybackController
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5 (MP-UI03 / Beta): shared presentation-layer logic, verified
 * headlessly on the desktop compile seam (pure JVM — no Compose rendering,
 * no native engine; the MP-DEC-04 push discipline and the MP-DEC-03
 * DAC-clock derivations are the invariants under test).
 */
class SharedPlaybackLogicTest {

    // ── formatPlaybackTime ──────────────────────────────────────────────

    @Test
    fun formatsSubMinutePositionsAsMinutesSeconds() {
        assertEquals("0:00", formatPlaybackTime(0L))
        assertEquals("0:59", formatPlaybackTime(59_999L))
        assertEquals("1:01", formatPlaybackTime(61_000L))
    }

    @Test
    fun formatsHourPlusPositionsAsHoursMinutesSeconds() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
        assertEquals("1:59:59", formatPlaybackTime(3_600_000L + 59 * 60_000L + 59_000L))
    }

    @Test
    fun clampsNegativePositionsToZero() {
        assertEquals("0:00", formatPlaybackTime(-5_000L))
    }

    // ── progressFraction ────────────────────────────────────────────────

    @Test
    fun progressFractionIsNullWithoutDurationOrPosition() {
        assertNull(progressFraction(null, 100_000L))
        assertNull(progressFraction(1_000L, null))
        assertNull(progressFraction(1_000L, 0L))
    }

    @Test
    fun progressFractionClampsIntoUnitInterval() {
        assertEquals(0f, progressFraction(-5_000L, 100_000L))
        assertEquals(1f, progressFraction(500_000L, 100_000L))
        assertEquals(0.5f, progressFraction(50_000L, 100_000L))
    }

    // ── positionMsFromDacClock (MP-DEC-03) ──────────────────────────────

    @Test
    fun derivesPositionFromDacFrameCounterAndNominalRate() {
        val clock = DacClockSample(
            framePosition = 48_000L * 90L, // exactly 90 s at 48 kHz
            sampleRateHz = 48_000,
            monotonicNs = 90_000_000_000L,
        )
        assertEquals(90_000L, positionMsFromDacClock(clock))
    }

    @Test
    fun rejectsUnsupportedRatesAndNegativeFrames() {
        assertNull(
            positionMsFromDacClock(
                DacClockSample(framePosition = 0L, sampleRateHz = 0, monotonicNs = 0L),
            ),
        )
        assertNull(
            positionMsFromDacClock(
                DacClockSample(framePosition = -1L, sampleRateHz = 48_000, monotonicNs = 0L),
            ),
        )
    }

    // ── dacDriftPpm ─────────────────────────────────────────────────────

    @Test
    fun zeroDriftForNominalProgress() {
        val older = DacClockSample(0L, 48_000, 0L)
        val newer = DacClockSample(48_000L * 10L, 48_000, 10_000_000_000L)
        val drift = dacDriftPpm(older, newer)
        assertTrue(drift != null && kotlin.math.abs(drift) < 1.0f, "expected ~0 ppm, got $drift")
    }

    @Test
    fun detectsPositiveDriftWhenDeviceRunsFast() {
        val older = DacClockSample(0L, 48_000, 0L)
        // 10 s nominal, 481 frames extra -> ~10020 ppm
        val newer = DacClockSample(48_000L * 10L + 481L, 48_000, 10_000_000_000L)
        val drift = dacDriftPpm(older, newer)
        assertTrue(drift != null && drift > 9_000f && drift < 11_000f, "expected ~10k ppm, got $drift")
    }

    @Test
    fun driftRejectsNonMonotonicPairs() {
        val a = DacClockSample(1_000L, 48_000, 5_000_000_000L)
        assertNull(dacDriftPpm(a, a.copy(monotonicNs = 1_000_000_000L)))
        assertNull(dacDriftPpm(a, a.copy(framePosition = 0L)))
    }

    // ── PlaybackController (MP-DEC-04 push discipline) ──────────────────

    @Test
    fun controllerHydratesFromColdStartAndFollowsPushes() = runTest {
        val seeded = snapshot(rev = 7L, status = PlaybackStatus.BUFFERING, volume = 0.4f)
        val port = FakeEnginePort(initial = seeded)
        val controller = PlaybackController(port, scope = backgroundScope)

        advanceUntilIdle()
        assertEquals(7L, controller.lastRevision)
        assertEquals(PlaybackStatus.BUFFERING, controller.state.value.status)

        port.controller.value = snapshot(rev = 8L, status = PlaybackStatus.PLAYING, volume = 0.9f)
        advanceUntilIdle()
        assertEquals(8L, controller.lastRevision)
        assertEquals(PlaybackStatus.PLAYING, controller.state.value.status)
        assertEquals(0.9f, controller.state.value.volume)

    }

    @Test
    fun controllerKeepsNewestSnapshotAndDerivesUiState() = runTest {
        val port = FakeEnginePort()
        val controller = PlaybackController(port, scope = backgroundScope)
        advanceUntilIdle()

        val clock = DacClockSample(
            framePosition = 48_000L * 61L,
            sampleRateHz = 48_000,
            monotonicNs = 61_000_000_000L,
        )
        port.controller.value = PlaybackSnapshot(
            rev = 2L,
            status = PlaybackStatus.PLAYING,
            volume = 0.5f,
            clock = clock,
        )
        advanceUntilIdle()

        val ui = controller.state.value
        assertEquals(61_000L, ui.positionMs)
        assertTrue(ui.isPlaying)
        // No duration known -> indeterminate progress, never fabricated.
        assertNull(ui.progressFraction)

    }

    @Test
    fun controllerDispatchesTransportCommandsToThePort() = runTest {
        val port = FakeEnginePort()
        val controller = PlaybackController(port, scope = backgroundScope)
        advanceUntilIdle()

        controller.togglePlayPause()
        controller.next()
        controller.previous()
        controller.seekTo(42_000L)
        controller.setVolume(1.5f) // must clamp into [0, 1]
        advanceUntilIdle()

        assertEquals(
            listOf(
                PlayerCommand.TogglePlayPause,
                PlayerCommand.Next,
                PlayerCommand.Previous,
                PlayerCommand.Seek(42_000L),
                PlayerCommand.SetVolume(1.0f),
            ),
            port.dispatchedCommands,
        )

    }

    @Test
    fun sendAndAwaitSurfacesDebounceAndFailurePaths() = runTest {
        val port = FakeEnginePort()
        val controller = PlaybackController(port, scope = backgroundScope)
        advanceUntilIdle()

        assertEquals(PlayerCommandResult.APPLIED, controller.sendAndAwait(PlayerCommand.Play))

        port.nextResult = PlayerCommandResult.DEBOUNCED
        assertEquals(
            PlayerCommandResult.DEBOUNCED,
            controller.sendAndAwait(PlayerCommand.Play),
        )

        port.failAll = true
        assertEquals(
            PlayerCommandResult.FAILED,
            controller.sendAndAwait(PlayerCommand.Play),
        )

    }

    private fun snapshot(
        rev: Long,
        status: PlaybackStatus,
        volume: Float,
    ): PlaybackSnapshot = PlaybackSnapshot(
        rev = rev,
        status = status,
        volume = volume,
    )
}
