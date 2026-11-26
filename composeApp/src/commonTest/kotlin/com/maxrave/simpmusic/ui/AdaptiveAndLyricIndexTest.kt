package com.maxrave.simpmusic.ui

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.simpmusic.ui.theme.AppWindowSizeClass
import com.maxrave.simpmusic.viewModel.computeActiveLyricLineIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveAndLyricIndexTest {

    @Test
    fun computeActiveLyricLineIndexHandlesUnsyncedAndBounds() {
        val unsynced = Lyrics(syncType = "UNSYNCED", lines = listOf(Line(startTimeMs = "1000", words = "a", endTimeMs = "2000", syllables = emptyList())))
        assertEquals(-1, computeActiveLyricLineIndex(1500L, unsynced, offsetMs = 0))
        assertEquals(-1, computeActiveLyricLineIndex(1500L, null, offsetMs = 0))
    }

    @Test
    fun computeActiveLyricLineIndexFindsLineWithOffsetAndFastPath() {
        val lyrics = Lyrics(
            syncType = "LINE_SYNCED",
            lines = listOf(
                Line(startTimeMs = "1000", words = "one", endTimeMs = "2000", syllables = emptyList()),
                Line(startTimeMs = "3000", words = "two", endTimeMs = "5000", syllables = emptyList()),
                Line(startTimeMs = "6000", words = "three", endTimeMs = "8000", syllables = emptyList()),
            ),
        )
        assertEquals(-1, computeActiveLyricLineIndex(500L, lyrics, offsetMs = 0))
        assertEquals(0, computeActiveLyricLineIndex(1500L, lyrics, offsetMs = 0))
        // Fast-path hit on previousIndex
        assertEquals(1, computeActiveLyricLineIndex(3500L, lyrics, offsetMs = 0, previousIndex = 1))
        // Offset shifts ear position back into previous line
        assertEquals(0, computeActiveLyricLineIndex(3200L, lyrics, offsetMs = 500, previousIndex = -1))
        // Last line stays active within trailing 60s window
        assertEquals(2, computeActiveLyricLineIndex(10000L, lyrics, offsetMs = 0))
    }

    @Test
    fun appWindowSizeClassExposesAdaptiveBreakpoints() {
        assertTrue(AppWindowSizeClass.Compact.isCompact)
        assertFalse(AppWindowSizeClass.Medium.isCompact)
        assertTrue(AppWindowSizeClass.Expanded.isExpanded)
        assertEquals(2, AppWindowSizeClass.Compact.moodGridColumns)
        assertEquals(3, AppWindowSizeClass.Medium.moodGridColumns)
        assertEquals(4, AppWindowSizeClass.Expanded.moodGridColumns)
        assertTrue(AppWindowSizeClass.Expanded.maxDialogListHeight > AppWindowSizeClass.Compact.maxDialogListHeight)
    }
}
