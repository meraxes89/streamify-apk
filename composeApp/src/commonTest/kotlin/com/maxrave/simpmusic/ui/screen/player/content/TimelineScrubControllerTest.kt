package com.maxrave.simpmusic.ui.screen.player.content

import com.maxrave.domain.data.model.streams.TimeLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineScrubControllerTest {
    @Test
    fun synchronousTapCommitsNewPositionInsteadOfStaleValue() {
        val controller = TimelineScrubController()
        controller.onTimelineUpdate(TimeLine(current = 10_000L, total = 100_000L, bufferedPercent = 50))
        assertEquals(10f, controller.sliderValue)

        var committed = -1f
        // Simulate tap: onValueChange and onValueChangeFinished fire in the same frame before recomposition.
        controller.onValueChange(72.5f)
        controller.onValueChangeFinished { committed = it }

        assertFalse(controller.isSliding)
        assertEquals(72.5f, committed)
    }

    @Test
    fun draggingIgnoresIncomingTimelineTicksUntilFinished() {
        val controller = TimelineScrubController()
        controller.onTimelineUpdate(TimeLine(current = 20_000L, total = 100_000L, bufferedPercent = 50))
        assertEquals(20f, controller.sliderValue)

        controller.onValueChange(45f)
        assertTrue(controller.isSliding)

        // Background 50ms playback tick arrives mid-scrub and must not overwrite the thumb position.
        val valueDuringScrub = controller.onTimelineUpdate(TimeLine(current = 21_000L, total = 100_000L, bufferedPercent = 50))
        assertEquals(45f, valueDuringScrub)

        controller.onValueChange(60f)
        var committed = -1f
        controller.onValueChangeFinished { committed = it }

        assertFalse(controller.isSliding)
        assertEquals(60f, committed)
    }

    @Test
    fun sliderValueClampsOutOfRangeInputsAndHandlesUnsetTotal() {
        val controller = TimelineScrubController()
        assertEquals(0f, controller.onTimelineUpdate(TimeLine(current = 5_000L, total = -1L, bufferedPercent = 0)))

        assertEquals(100f, controller.onValueChange(150f))
        var committed = -1f
        controller.onValueChangeFinished { committed = it }
        assertEquals(100f, committed)

        assertEquals(0f, controller.onValueChange(-10f))
        controller.onValueChangeFinished { committed = it }
        assertEquals(0f, committed)
    }
}
