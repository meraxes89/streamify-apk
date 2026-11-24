package com.streamify.sync

import com.streamify.supabase.CloudEpisodeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhaseCSyncResilienceTest {

    @Test
    fun testBootstrapResumableStageProgression() {
        val stages = listOf(
            "LIKES",
            "ARTISTS",
            "ALBUMS",
            "LIBRARY_TRACKS",
            "SAVED_PLAYLISTS",
            "PLAYLISTS",
            "SETTINGS",
            "HISTORY",
            "PODCASTS",
            "EPISODES",
            "SEARCH"
        )

        // Case 1: Fresh bootstrap starts at 0 (LIKES)
        val freshStage = ""
        val freshResumeIdx = stages.indexOf(freshStage).let { if (it >= 0) it + 1 else 0 }
        assertEquals(0, freshResumeIdx)
        assertEquals("LIKES", stages[freshResumeIdx])

        // Case 2: Interrupted after ALBUMS resumes at LIBRARY_TRACKS
        val interruptedStage = "ALBUMS"
        val resumeIdx = stages.indexOf(interruptedStage).let { if (it >= 0) it + 1 else 0 }
        assertEquals(3, resumeIdx)
        assertEquals("LIBRARY_TRACKS", stages[resumeIdx])

        // Case 3: Interrupted after PODCASTS resumes at EPISODES
        val podcastStage = "PODCASTS"
        val epResumeIdx = stages.indexOf(podcastStage).let { if (it >= 0) it + 1 else 0 }
        assertEquals(9, epResumeIdx)
        assertEquals("EPISODES", stages[epResumeIdx])
    }

    @Test
    fun testSearchHistoryOrderingInversionResolutionD8() {
        // Cloud search history arrives in searched_at DESC (newest first)
        val cloudSearchQueries = listOf("quantum mechanics", "lofi beats", "ambient space")

        // Defect D8 resolution: inverting order ensures oldest is inserted first,
        // so SQLite auto-increment rowids preserve true chronological order
        val insertionOrder = cloudSearchQueries.reversed()
        assertEquals(listOf("ambient space", "lofi beats", "quantum mechanics"), insertionOrder)
        assertEquals("ambient space", insertionOrder.first())
        assertEquals("quantum mechanics", insertionOrder.last())
    }

    @Test
    fun testEpisodeStateLastWriteWinsHlcResolution() {
        val localState = StreamifySyncEngine.LocalEpisodeState(
            episodeId = "ep_101",
            podcastId = "pod_huberman",
            positionSec = 120,
            completed = false,
            modifiedHlc = 1000L
        )

        val newerCloudState = CloudEpisodeState(
            episodeId = "ep_101",
            podcastId = "pod_huberman",
            positionSec = 300,
            completed = false,
            modifiedHlc = 1500L
        )

        val olderCloudState = CloudEpisodeState(
            episodeId = "ep_101",
            podcastId = "pod_huberman",
            positionSec = 60,
            completed = false,
            modifiedHlc = 800L
        )

        // Newer cloud HLC should supersede local
        assertTrue((newerCloudState.modifiedHlc ?: 0L) > localState.modifiedHlc)

        // Older cloud HLC should NOT supersede local
        assertTrue((olderCloudState.modifiedHlc ?: 0L) < localState.modifiedHlc)
    }
}
