package com.streamify.app.media.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioFocusGovernorTest (strict architecture firewall, Phase 4) — the full
 * focus transition table on the plain JVM: the governor is pure Kotlin, so
 * every Media3-relevant lifecycle sequence is provable without a device:
 *
 *  • PERMANENT LOSS → the atomic teardown contract: TeardownPlayback +
 *    AbandonFocus, terminal (later events no-op) until an explicit replay;
 *  • TRANSIENT → Pause with focus retained, GAIN resumes;
 *  • DUCK → 20% gain, GAIN restores;
 *  • stacked duck+transient → GAIN clears both in order;
 *  • focus requested exactly once per playback span, retained across the
 *    governor's own pause/duck, abandoned on user stop;
 *  • a playback start after a teardown re-arms the governor.
 */
class AudioFocusGovernorTest {

    private val g = AudioFocusGovernor.Companion

    private fun actions(vararg kinds: String) = kinds.toList()

    private fun kindsOf(result: List<AudioFocusGovernor.Action>): List<String> =
        result.map {
            when (it) {
                is AudioFocusGovernor.Action.RequestFocus -> "request"
                is AudioFocusGovernor.Action.AbandonFocus -> "abandon"
                is AudioFocusGovernor.Action.Duck -> "duck"
                AudioFocusGovernor.Action.Unduck -> "unduck"
                AudioFocusGovernor.Action.Pause -> "pause"
                AudioFocusGovernor.Action.Resume -> "resume"
                AudioFocusGovernor.Action.TeardownPlayback -> "teardown"
            }
        }

    // ── The permanent-loss teardown contract ───────────────────────────────

    @Test
    fun `permanent loss demands the atomic teardown and abandons focus`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()

        val out = kindsOf(gov.onFocusChange(g.FOCUS_LOSS))
        assertEquals(actions("teardown", "abandon"), out)
    }

    @Test
    fun `teardown is terminal - later focus events are ignored`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()
        gov.onFocusChange(g.FOCUS_LOSS)

        assertTrue(gov.onFocusChange(g.FOCUS_GAIN).isEmpty())
        assertTrue(gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT).isEmpty())
        assertTrue(gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT_CAN_DUCK).isEmpty())
    }

    @Test
    fun `a fresh playback start after teardown re-arms the governor`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()
        gov.onFocusChange(g.FOCUS_LOSS)

        // The user explicitly plays again: focus is legitimately re-requested.
        val out = kindsOf(gov.onPlaybackStarted())
        assertEquals(actions("request"), out)
        // And the governor answers events again.
        assertEquals(actions("pause"), kindsOf(gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT)))
    }

    // ── Transient loss ─────────────────────────────────────────────────────

    @Test
    fun `transient loss pauses with focus retained and gain resumes`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()

        assertEquals(actions("pause"), kindsOf(gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT)))
        assertTrue("governor pause must be visible to the service", gov.isTransientPauseActive())

        // Focus retained: the user pausing (observed as onPlaybackStopped)
        // during the governor's pause must NOT abandon — the ending GAIN
        // still has to reach us.
        assertTrue(gov.onPlaybackStopped().isEmpty())

        assertEquals(actions("resume"), kindsOf(gov.onFocusChange(g.FOCUS_GAIN)))
        assertFalse(gov.isTransientPauseActive())
    }

    @Test
    fun `repeated transient loss while paused is a no-op`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()
        gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT)

        assertTrue(gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT).isEmpty())
    }

    // ── Ducking ────────────────────────────────────────────────────────────

    @Test
    fun `duckable loss lowers gain to 20 percent and gain restores`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()

        val out = gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(1, out.size)
        val duck = out.first() as AudioFocusGovernor.Action.Duck
        assertEquals(AudioFocusGovernor.DUCK_GAIN, duck.gain)

        assertEquals(actions("unduck"), kindsOf(gov.onFocusChange(g.FOCUS_GAIN)))
    }

    @Test
    fun `repeated duck while ducked is a no-op`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()
        gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertTrue(gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT_CAN_DUCK).isEmpty())
    }

    @Test
    fun `stacked duck and transient clear in order on gain`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()

        gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT_CAN_DUCK)
        gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT)

        assertEquals(actions("unduck", "resume"), kindsOf(gov.onFocusChange(g.FOCUS_GAIN)))
    }

    // ── Focus request / abandon lifecycle ──────────────────────────────────

    @Test
    fun `playback start requests focus exactly once per span`() {
        val gov = AudioFocusGovernor()
        assertEquals(actions("request"), kindsOf(gov.onPlaybackStarted()))
        assertTrue(gov.onPlaybackStarted().isEmpty()) // already held
    }

    @Test
    fun `user stop abandons focus only when the governor is not holding it`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()

        assertEquals(actions("abandon"), kindsOf(gov.onPlaybackStopped()))
        assertTrue(gov.onPlaybackStopped().isEmpty()) // already abandoned

        // Re-request on the next span.
        assertEquals(actions("request"), kindsOf(gov.onPlaybackStarted()))
    }

    @Test
    fun `user stop during an active duck retains focus for the ending gain`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()
        gov.onFocusChange(g.FOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertTrue(gov.onPlaybackStopped().isEmpty())

        // The duck's GAIN still lands and restores gain.
        assertEquals(actions("unduck"), kindsOf(gov.onFocusChange(g.FOCUS_GAIN)))
    }

    @Test
    fun `unknown focus events are ignored`() {
        val gov = AudioFocusGovernor()
        gov.onPlaybackStarted()
        assertTrue(gov.onFocusChange(0).isEmpty())
        assertTrue(gov.onFocusChange(42).isEmpty())
    }
}
