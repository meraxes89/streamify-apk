package com.streamify.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSessionTrackerTest {

    @Test
    fun recordsThirtyFiveSecondPlayOnLongTrackOnPause() {
        var seq = 0
        val tracker = PlaybackSessionTracker { "evt_${++seq}" }

        assertNull(tracker.onTrackChanged("trk1", "Song A", "Kanye West", "alb1", "Graduation", 240, 1_000L))
        var t = 1_000L
        repeat(35) {
            t += 1_000L
            val tickFlush = tracker.onProgressTick("trk1", "Song A", "Kanye West", "alb1", "Graduation", 240, t)
            if (it < 29) assertNull(tickFlush)
        }
        val paused = tracker.onPausedOrEnded()
        assertNotNull(paused)
        assertEquals("evt_1", paused.clientEventId)
        assertEquals(35, paused.listenedSec)
    }

    @Test
    fun skipsShortTenSecondPreviewOnLongTrack() {
        val tracker = PlaybackSessionTracker { "evt_1" }
        tracker.onTrackChanged("trk1", "Song A", "Artist", "", "", 200, 1_000L)
        var t = 1_000L
        repeat(10) {
            t += 1_000L
            assertNull(tracker.onProgressTick("trk1", "Song A", "Artist", "", "", 200, t))
        }
        assertNull(tracker.onPausedOrEnded())
    }

    @Test
    fun recordsZeroDurationTrackAfterThirtySecondsAndReusesClientEventId() {
        val tracker = PlaybackSessionTracker { "evt_fixed" }
        tracker.onTrackChanged("arijit1", "Tum Hi Ho", "Arijit Singh", "", "", 0, 0L)
        var firstFlush: PlaybackHistoryFlush? = null
        var t = 0L
        repeat(65) {
            t += 1_000L
            val res = tracker.onProgressTick("arijit1", "Tum Hi Ho", "Arijit Singh", "", "", 0, t)
            if (res != null && firstFlush == null) {
                firstFlush = res
            }
        }
        assertNotNull(firstFlush)
        assertTrue(firstFlush!!.isInitialInsert)
        assertEquals(30, firstFlush!!.listenedSec)

        val finalFlush = tracker.onPausedOrEnded()
        assertNotNull(finalFlush)
        assertEquals("evt_fixed", finalFlush.clientEventId)
        assertEquals(65, finalFlush.listenedSec)
    }
}
