package com.streamify.app.media.playback

import com.streamify.app.data.uniffi.MediaCommand
import com.streamify.app.data.uniffi.StreamifyEngineBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MediaCommandDispatcherTest (Phase 3, MP-C03 / MP-DEC-07 — directive §C) —
 * virtual-time proof of the hardware debounce semantics:
 *
 *  • bursts inside the window collapse to ONE dispatch of the LAST command
 *    (double-tap toggles, AVRCP re-delivery);
 *  • isolated commands dispatch exactly one window after the last arrival;
 *  • the window is clamped into the approved 50–150ms envelope — an
 *    out-of-range request can never silently disable the debounce;
 *  • stop() disarms the actor and drains the conflation buffer — a dying
 *    session's tail command never replays into the next session;
 *  • a throwing sink kills nothing: the actor keeps dispatching.
 *
 * `runTest` virtual time drives `withTimeoutOrNull` deterministically —
 * no wall-clock sleeps, no flakiness. Runs in the plain jvm-unit-suite
 * shard: no Robolectric, no native artifacts, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaCommandDispatcherTest {

    @After
    fun tearDown() {
        MediaCommandDispatcher.stop()
        // Restore the production sink (bridge dispatch, degrade-typed).
        MediaCommandDispatcher.dispatchSink = {
            StreamifyEngineBridge.dispatchMediaCommand(it)
        }
    }

    @Test
    fun `burst of rapid commands collapses to one dispatch of the last`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        MediaCommandDispatcher.dispatchSink = { dispatched += it }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 100)

        // Double-tap-for-skip arriving as rapid toggles: the engine's
        // convergent state machine only ever sees the final intent.
        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Play)
        advanceTimeBy(40)
        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Play)
        advanceTimeBy(40)
        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.TogglePlayPause)
        advanceTimeBy(300)

        assertEquals(listOf<MediaCommand>(MediaCommand.TogglePlayPause), dispatched)
    }

    @Test
    fun `isolated command dispatches exactly one window after arrival`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        MediaCommandDispatcher.dispatchSink = { dispatched += it }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 100)

        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Seek(42_000))

        advanceTimeBy(90)
        assertEquals("still inside the debounce window", emptyList<MediaCommand>(), dispatched)

        advanceTimeBy(60)
        assertEquals(listOf<MediaCommand>(MediaCommand.Seek(42_000)), dispatched)
    }

    @Test
    fun `window below the envelope clamps up to 50ms`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        MediaCommandDispatcher.dispatchSink = { dispatched += it }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 10)

        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Play)

        advanceTimeBy(45)
        assertEquals("clamped window has not expired yet", emptyList<MediaCommand>(), dispatched)

        advanceTimeBy(10)
        assertEquals(listOf<MediaCommand>(MediaCommand.Play), dispatched)
    }

    @Test
    fun `window above the envelope clamps down to 150ms`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        MediaCommandDispatcher.dispatchSink = { dispatched += it }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 5_000)

        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Pause)

        advanceTimeBy(145)
        assertEquals("clamped window has not expired yet", emptyList<MediaCommand>(), dispatched)

        advanceTimeBy(10)
        assertEquals(listOf<MediaCommand>(MediaCommand.Pause), dispatched)
    }

    @Test
    fun `stop disarms the actor and drains the tail - nothing replays later`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        MediaCommandDispatcher.dispatchSink = { dispatched += it }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 100)

        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Play)
        advanceTimeBy(50)
        MediaCommandDispatcher.stop()
        advanceTimeBy(400)
        assertEquals("cancelled actor dispatches nothing", emptyList<MediaCommand>(), dispatched)

        // The undelivered tail intent was drained with the session: the
        // NEXT actor never sees it.
        MediaCommandDispatcher.start(backgroundScope, windowMs = 100)
        advanceTimeBy(400)
        assertEquals("drained tail never replays", emptyList<MediaCommand>(), dispatched)
    }

    @Test
    fun `a throwing sink kills nothing - the actor keeps dispatching`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        var first = true
        MediaCommandDispatcher.dispatchSink = { cmd ->
            dispatched += cmd
            if (first) {
                first = false
                throw IllegalStateException("sink exploded")
            }
        }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 50)

        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Play)
        advanceTimeBy(100)
        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Pause)
        advanceTimeBy(200)

        // Both commands attempted; the failure was telemetry, not a
        // loop death.
        assertEquals(listOf<MediaCommand>(MediaCommand.Play, MediaCommand.Pause), dispatched)
    }

    @Test
    fun `second start while live only re-clamps the window`() = runTest {
        val dispatched = mutableListOf<MediaCommand>()
        MediaCommandDispatcher.dispatchSink = { dispatched += it }
        MediaCommandDispatcher.start(backgroundScope, windowMs = 100)
        MediaCommandDispatcher.start(backgroundScope, windowMs = 50)

        MediaCommandDispatcher.dispatchMediaCommand(MediaCommand.Next)
        advanceTimeBy(45)
        advanceTimeBy(60)

        assertEquals(listOf<MediaCommand>(MediaCommand.Next), dispatched)
    }
}
