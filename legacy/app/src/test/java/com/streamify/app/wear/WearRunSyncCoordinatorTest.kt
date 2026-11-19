package com.streamify.app.wear

import com.streamify.app.data.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WearRunSyncCoordinatorTest (Phase 4 — Gap #55) — offline run bundle
 * planning:
 *  • only local/downloaded tracks are eligible (workouts never depend on
 *    the phone network)
 *  • BPM proximity ranking with play-count tiebreak
 *  • greedy byte budget packing
 *  • the 3-track minimum gate
 *  • pre-push bundle budget verification
 */
class WearRunSyncCoordinatorTest {

    private fun track(
        id: Int,
        bpm: Float = 150f,
        source: String = "local",
        plays: Int = 0,
        path: String = "/music/t$id.m4a"
    ) = Track(
        id = id,
        title = "T$id",
        artist = "A$id",
        bpm = bpm,
        source = source,
        playCount = plays,
        filepath = path
    )

    @Test
    fun `remote-only libraries never produce a bundle`() {
        val cloud = (1..10).map { track(it, source = "remote", path = "") }
        assertTrue(WearRunSyncCoordinator.planRunBundle(cloud).isEmpty())
    }

    @Test
    fun `fewer than three eligible tracks decline the sync`() {
        val few = listOf(track(1), track(2))
        assertTrue(WearRunSyncCoordinator.planRunBundle(few).isEmpty())
    }

    @Test
    fun `ranking prefers bpm closest to the run target`() {
        val tracks = listOf(
            track(1, bpm = 100f),
            track(2, bpm = 168f),
            track(3, bpm = 128f),
            track(4, bpm = 162f)
        )
        val planned = WearRunSyncCoordinator.planRunBundle(
            tracks,
            targetBpm = 165f,
            fileSizeOf = { 1L }
        )
        // 168 (|3|) beats 162 (|3|)? No: 168→3, 162→3 — tie broken by play
        // count (all zero) then stable order. Assert the two closest lead.
        assertEquals(listOf(2, 4), planned.take(2).map { it.id })
    }

    @Test
    fun `play count breaks bpm ties`() {
        val tracks = listOf(
            track(1, bpm = 168f, plays = 0),
            track(2, bpm = 162f, plays = 9)
        ) + (3..6).map { track(it, bpm = 90f) }
        val planned = WearRunSyncCoordinator.planRunBundle(tracks, targetBpm = 165f, fileSizeOf = { 1L })
        assertEquals(2, planned.first().id)
    }

    @Test
    fun `greedy budget skips files that would overflow`() {
        val sizes = mapOf(1 to 10L, 2 to 10L, 3 to 10L, 4 to 10L)
        val planned = WearRunSyncCoordinator.planRunBundle(
            (1..4).map { track(it) },
            budgetBytes = 25L,
            fileSizeOf = { sizes[it.id] ?: 0L }
        )
        // Two 10-byte files fit in 25; the third would overflow to 30.
        assertEquals(listOf(1, 2), planned.map { it.id })
        assertEquals(20L, planned.sumOf { sizes[it.id] ?: 0L })
    }

    @Test
    fun `zero-sized files are skipped`() {
        val planned = WearRunSyncCoordinator.planRunBundle(
            listOf(track(1), track(2), track(3), track(4)),
            fileSizeOf = { if (it.id == 1) 0L else 4L }
        )
        assertTrue(planned.none { it.id == 1 })
    }

    @Test
    fun `bundles cap at 40 tracks`() {
        val many = (1..100).map { track(it) }
        val planned = WearRunSyncCoordinator.planRunBundle(many, fileSizeOf = { 1L })
        assertEquals(40, planned.size)
    }

    @Test
    fun `bundle directory budget verification`() {
        val dir = kotlin.io.path.createTempDirectory("runsync").toFile()
        try {
            // Empty dir fails (nothing to push).
            assertFalse(WearRunSyncCoordinator.bundleFitsBudget(dir))

            File(dir, "a.m4a").writeBytes(ByteArray(10))
            assertTrue(WearRunSyncCoordinator.bundleFitsBudget(dir, budgetBytes = 100L))
            assertFalse(WearRunSyncCoordinator.bundleFitsBudget(dir, budgetBytes = 5L))
        } finally {
            dir.deleteRecursively()
        }
    }
}
