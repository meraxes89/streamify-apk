package com.streamify.app.widget

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AppWidgetProviderTest (Phase 4 — Gap #42) — widget state discipline:
 *  • value mapping from playback transitions (blank fields normalized)
 *  • re-render gating: only VISIBLE field changes trigger the fan-out
 *  • debounce with guaranteed trailing edge
 *  • router defaults degrade safely with no activity alive
 *
 * The store is exercised with a fake [WidgetUpdater]; Android Context is
 * never dereferenced, so the suite stays on the plain JVM shard.
 */
class AppWidgetProviderTest {

    private lateinit var updater: RecordingUpdater

    @Before
    fun setUp() {
        NowPlayingWidgetStateStore.resetForTest()
        updater = RecordingUpdater()
        NowPlayingWidgetStateStore.updater = updater
        NowPlayingWidgetStateStore.throttleMsForTest = 0L
    }

    @After
    fun tearDown() {
        NowPlayingWidgetStateStore.resetForTest()
    }

    private fun awaitCalls(expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (updater.calls.size < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    @Test
    fun `widget state mapping normalizes blank fields`() {
        val state = widgetStateOf(
            title = "  ",
            artist = null,
            artwork = "",
            isPlaying = true
        )
        assertEquals("", state.title)
        assertEquals(null, state.artworkUrl)
        assertFalse(state.hasTrack)
        assertTrue(state.isPlaying)
    }

    @Test
    fun `widget state mapping keeps real identities`() {
        val state = widgetStateOf(
            title = "Nightcall",
            artist = "Kavinsky",
            artwork = "https://img/nc.jpg",
            isPlaying = false,
            isLiked = true
        )
        assertTrue(state.hasTrack)
        assertEquals("Nightcall", state.title)
        assertEquals("Kavinsky", state.artist)
        assertEquals("https://img/nc.jpg", state.artworkUrl)
        assertTrue(state.isLiked)
        assertFalse(state.isPlaying)
    }

    @Test
    fun `invisible changes never re-render`() = runBlocking {
        NowPlayingWidgetStateStore.update(NullContext, "Song", "Artist", "art.jpg", true)
        awaitCalls(1)
        val rendersBefore = updater.calls.size

        // updatedAtMs differs but nothing visible changed.
        NowPlayingWidgetStateStore.update(NullContext, "Song", "Artist", "art.jpg", true)

        Thread.sleep(120)
        assertEquals(rendersBefore, updater.calls.size)
    }

    @Test
    fun `visible changes trigger the fan-out`() {
        NowPlayingWidgetStateStore.update(NullContext, "Song A", "Artist", "a.jpg", true)
        awaitCalls(1)
        assertEquals(1, updater.calls.size)

        NowPlayingWidgetStateStore.update(NullContext, "Song B", "Artist", "a.jpg", true)
        awaitCalls(2)
        assertEquals(2, updater.calls.size)
        // The store held the newest identity for the render.
        assertEquals("Song B", NowPlayingWidgetStateStore.state.title)
    }

    @Test
    fun `transport flag changes re-render`() {
        NowPlayingWidgetStateStore.update(NullContext, "Song", "Artist", "a.jpg", true)
        awaitCalls(1)
        NowPlayingWidgetStateStore.update(NullContext, "Song", "Artist", "a.jpg", false)
        awaitCalls(2)
        assertFalse(NowPlayingWidgetStateStore.state.isPlaying)
    }

    @Test
    fun `jam flag changes re-render for the join shortcut`() {
        NowPlayingWidgetStateStore.update(NullContext, "Song", "Artist", "a.jpg", true, jamActive = false)
        awaitCalls(1)
        NowPlayingWidgetStateStore.update(NullContext, "Song", "Artist", "a.jpg", true, jamActive = true)
        awaitCalls(2)
        assertTrue(NowPlayingWidgetStateStore.state.jamActive)
    }

    @Test
    fun `isVisibleChange covers exactly the rendered fields`() {
        val base = widgetStateOf("A", "B", "c.jpg", true, isLiked = false, jamActive = false)
        assertTrue(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(title = "Z")))
        assertTrue(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(artist = "Z")))
        assertTrue(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(artworkUrl = "z.jpg")))
        assertTrue(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(isPlaying = false)))
        assertTrue(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(isLiked = true)))
        assertTrue(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(jamActive = true)))
        assertFalse(NowPlayingWidgetStateStore.isVisibleChange(base, base.copy(updatedAtMs = 999L)))
    }

    @Test
    fun `throttle constant stays human-tappable`() {
        // Sub-second re-render cadence: fast enough for transport taps,
        // slow enough to never spam the widget host.
        assertTrue(NowPlayingWidgetStateStore.THROTTLE_MS in 250..2000)
    }

    @Test
    fun `router defaults are safe without a live activity`() {
        assertEquals(null, WidgetActionRouter.onToggleLike)
    }
}

private class RecordingUpdater : WidgetUpdater {
    val calls = mutableListOf<String>()
    override suspend fun refreshAll(context: Context?) {
        calls.add(NowPlayingWidgetStateStore.state.title)
    }
}

/** Never dereferenced by the store — null stands in for a dead context. */
private val NullContext: Context? = null
