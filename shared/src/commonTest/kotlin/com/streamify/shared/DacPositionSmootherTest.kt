package com.streamify.shared

import com.streamify.shared.components.QueueListState
import com.streamify.shared.model.DacPositionSmoother
import com.streamify.shared.model.PresentationTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 5 (MP-UI03 / Beta): the DAC playhead smoother + queue projection
 * suite — pure decision machinery, JVM-hermetic on both compile seams.
 *
 * The smoother pins the MP-DEC-03-compliant transition table from
 * [DacPositionSmoother]'s contract: bootstrap snap, verbatim forward
 * motion, jitter hold while playing, paused re-anchor acceptance, seek
 * snap in both directions and null tolerance.
 */
class DacPositionSmootherTest {

    private fun smoother(
        jitterToleranceMs: Long = 400L,
        seekSnapThresholdMs: Long = 2_000L,
    ) = DacPositionSmoother(jitterToleranceMs, seekSnapThresholdMs)

    // ── Bootstrap ───────────────────────────────────────────────────────

    @Test
    fun `first sample snaps without filtering`() {
        val s = smoother()
        assertEquals(5_000L, s.offer(5_000L, isPlaying = true))
        assertEquals(5_000L, s.rendered)
    }

    @Test
    fun `null offers never fabricate a position`() {
        val s = smoother()
        assertNull(s.offer(null, isPlaying = true))
        assertNull(s.rendered)

        assertEquals(1_000L, s.offer(1_000L, isPlaying = true))
        // A clock-less snapshot keeps the last credible position.
        assertEquals(1_000L, s.offer(null, isPlaying = true))
        assertEquals(1_000L, s.rendered)
    }

    // ── Forward motion ──────────────────────────────────────────────────

    @Test
    fun `forward deltas pass through verbatim while playing`() {
        val s = smoother()
        s.offer(10_000L, isPlaying = true)
        // Typical push cadence: a few hundred ms of DAC-true motion.
        assertEquals(10_200L, s.offer(10_200L, isPlaying = true))
        assertEquals(10_450L, s.offer(10_450L, isPlaying = true))
        assertEquals(10_900L, s.offer(10_900L, isPlaying = true))
    }

    @Test
    fun `exact repeats are stable`() {
        val s = smoother()
        s.offer(10_000L, isPlaying = true)
        assertEquals(10_000L, s.offer(10_000L, isPlaying = true))
    }

    // ── Jitter suppression ──────────────────────────────────────────────

    @Test
    fun `small backward jitter is held while playing`() {
        val s = smoother()
        s.offer(20_000L, isPlaying = true)
        // 120 ms backward: ring batching noise — the playhead must not
        // visibly step backwards mid-playback.
        assertEquals(20_000L, s.offer(19_880L, isPlaying = true))
        // The counter catches back up: normal pass-through resumes.
        assertEquals(20_150L, s.offer(20_150L, isPlaying = true))
    }

    @Test
    fun `backward jitter at tolerance boundary still holds while playing`() {
        val s = smoother(jitterToleranceMs = 400L)
        s.offer(20_000L, isPlaying = true)
        // Exactly 400 ms backward is still "at or below" the tolerance.
        assertEquals(20_000L, s.offer(19_600L, isPlaying = true))
    }

    @Test
    fun `small backward steps are accepted while paused`() {
        val s = smoother()
        s.offer(20_000L, isPlaying = true)
        // Pause, then the HAL re-anchors its counter slightly backwards.
        assertEquals(19_900L, s.offer(19_900L, isPlaying = false))
        assertEquals(19_900L, s.offer(19_900L, isPlaying = false))
    }

    // ── Seek snapping ───────────────────────────────────────────────────

    @Test
    fun `large forward delta snaps like a seek`() {
        val s = smoother()
        s.offer(10_000L, isPlaying = true)
        assertEquals(90_000L, s.offer(90_000L, isPlaying = true))
    }

    @Test
    fun `large backward delta snaps like a seek-back while playing`() {
        val s = smoother()
        s.offer(60_000L, isPlaying = true)
        // A 45 s backwards jump is a user seek, not jitter: it must land
        // immediately, not be held.
        assertEquals(15_000L, s.offer(15_000L, isPlaying = true))
    }

    @Test
    fun `seek snap threshold boundary is exclusive`() {
        val s = smoother(seekSnapThresholdMs = 2_000L)
        s.offer(10_000L, isPlaying = true)
        // 2 s forward: still ordinary playback motion (threshold is
        // strictly greater-than).
        assertEquals(12_000L, s.offer(12_000L, isPlaying = true))
        // 2 s + 1 ms: a seek.
        assertEquals(60_000L, s.offer(60_000L, isPlaying = true))
    }

    // ── Reset ───────────────────────────────────────────────────────────

    @Test
    fun `reset drops filter state and the next sample snaps`() {
        val s = smoother()
        s.offer(30_000L, isPlaying = true)
        s.reset()
        assertNull(s.rendered)
        assertNull(s.offer(null, isPlaying = true))
        assertEquals(2_000L, s.offer(2_000L, isPlaying = true))
    }

    @Test
    fun `track change backward jump snaps immediately`() {
        // The canonical end-of-track transition: last position 3:41, the
        // next track's DAC counter restarts near zero.
        val s = smoother()
        s.offer(221_000L, isPlaying = true)
        assertEquals(120L, s.offer(120L, isPlaying = true))
    }
}

/**
 * The queue projection consumed by the shared QueueList component.
 */
class QueueListStateTest {

    private val tracks = listOf(
        PresentationTrack(id = "a", title = "Alpha", artist = "A"),
        PresentationTrack(id = "b", title = "Beta", artist = "B"),
        PresentationTrack(id = "c", title = "Gamma", artist = "C"),
        PresentationTrack(id = "d", title = "Delta", artist = "D"),
    )

    @Test
    fun `empty state degrades honestly`() {
        val state = QueueListState.EMPTY
        assertNull(state.current)
        assertEquals(emptyList(), state.upNext)
        assertEquals(emptyList(), state.played)
    }

    @Test
    fun `playing in the middle splits now playing up next and played`() {
        val state = QueueListState(tracks = tracks, currentIndex = 1, isPlaying = true)
        assertEquals("b", state.current?.id)
        assertEquals(listOf("c", "d"), state.upNext.map { it.second.id })
        assertEquals(listOf(2, 3), state.upNext.map { it.first })
        assertEquals(listOf("a"), state.played.map { it.second.id })
    }

    @Test
    fun `negative index renders the whole queue as up next`() {
        val state = QueueListState(tracks = tracks, currentIndex = -1)
        assertNull(state.current)
        assertEquals(4, state.upNext.size)
        assertEquals(emptyList<Pair<Int, PresentationTrack>>(), state.played)
    }

    @Test
    fun `last track has no up next`() {
        val state = QueueListState(tracks = tracks, currentIndex = 3, isPlaying = false)
        assertEquals("d", state.current?.id)
        assertEquals(emptyList(), state.upNext)
        assertEquals(listOf("a", "b", "c"), state.played.map { it.second.id })
    }
}
