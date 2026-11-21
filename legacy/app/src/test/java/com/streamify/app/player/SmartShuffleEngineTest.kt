package com.streamify.app.player

import com.streamify.app.data.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SmartShuffleEngine JVM unit suite (Phase 3 — queue intelligence).
 *
 * Locks the interleave contract:
 *  • Relative order of user tracks is NEVER permuted.
 *  • Current + next track are protected (no injection before them).
 *  • Injections land after every 2 user tracks (default cadence).
 *  • Dedup by ytmVideoId / title+artist / id.
 *  • Reshuffle strips old injections but never the playing track.
 */
class SmartShuffleEngineTest {

    private fun user(id: Int, title: String = "Song $id", artist: String = "Artist $id") =
        Track(id = id, title = title, artist = artist)

    private fun rec(id: Int, title: String = "Rec $id", artist: String = "RecArtist $id", vid: String? = null) =
        Track(id = id, title = title, artist = artist, source = "smart_shuffle", ytmVideoId = vid)

    // ───────────────────────────────────────────────────── interleave core

    @Test
    fun `user track relative order is never permuted`() {
        val queue = (1..6).map { user(it) }
        val recs = listOf(rec(-101), rec(-102), rec(-103))
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 0, recommendations = recs)

        val userOrder = result.queue.filter { it.id > 0 }.map { it.id }
        assertEquals(listOf(1, 2, 3, 4, 5, 6), userOrder)
    }

    @Test
    fun `current and next tracks are protected`() {
        val queue = (1..6).map { user(it) }
        val recs = listOf(rec(-101), rec(-102))
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 2, recommendations = recs)

        // Positions 2 (current) and 3 (protected next) untouched.
        assertEquals(3, result.queue[2].id)
        assertEquals(4, result.queue[3].id)
        // First injection may only appear at index >= 4.
        val firstInjectionIdx = result.queue.indexOfFirst { it.id < 0 }
        assertTrue(firstInjectionIdx >= 4)
    }

    @Test
    fun `injections follow the after-every-2 cadence`() {
        val queue = (1..9).map { user(it) }  // currentIndex 0, tail = tracks 2..9
        val recs = (1..4).map { rec(-100 - it) }
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 0, recommendations = recs)

        // Queue layout: [1] [2(protected)] u3 r u4 r u5 r u6 r u7 u8 u9
        // (tail starts at track 3; inject after every 2 user tail tracks)
        val ids = result.queue.map { it.id }
        assertTrue(ids.contains(-101))
        // No two recommendations adjacent to each other.
        val recPositions = ids.mapIndexedNotNull { i, v -> if (v < 0) i else null }
        recPositions.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= 2) }
        assertEquals(4, result.injectedCount)
    }

    @Test
    fun `short queue tops up recommendations at the end`() {
        val queue = listOf(user(1)) // current; protected next = none; tail empty
        val recs = listOf(rec(-101), rec(-102))
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 0, recommendations = recs)
        assertEquals(listOf(1, -101, -102), result.queue.map { it.id })
        assertEquals(2, result.injectedCount)
    }

    // ─────────────────────────────────────────────────────────── dedup

    @Test
    fun `recommendations matching queue videoIds are dropped`() {
        val queue = listOf(
            user(1).copy(ytmVideoId = "vidA"),
            user(2).copy(ytmVideoId = "vidB")
        )
        val recs = listOf(
            rec(-101, vid = "vidA"),   // dupe by videoId
            rec(-102, vid = "vidC")     // fresh
        )
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 0, recommendations = recs)
        assertEquals(setOf(-102), result.injectedIds)
    }

    @Test
    fun `recommendations matching title and artist are dropped`() {
        val queue = listOf(user(1, title = "Same Song", artist = "Same Artist"))
        val recs = listOf(
            rec(-101, title = "Same Song", artist = "Same Artist"), // dupe by key
            rec(-102, title = "Different", artist = "Other")
        )
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 0, recommendations = recs)
        assertEquals(setOf(-102), result.injectedIds)
    }

    @Test
    fun `all-duplicate recommendations yield zero injections`() {
        val queue = listOf(user(1))
        val recs = listOf(rec(-101, title = "Song 1", artist = "Artist 1"))
        val result = SmartShuffleEngine.interleave(queue, currentIndex = 0, recommendations = recs)
        assertEquals(0, result.injectedCount)
        assertEquals(queue, result.queue)
    }

    // ───────────────────────────────────────────────────────── maxima

    @Test
    fun `maxInjections caps the batch`() {
        val queue = (1..40).map { user(it) }
        val recs = (1..20).map { rec(-100 - it) }
        val result = SmartShuffleEngine.interleave(
            queue, currentIndex = 0, recommendations = recs, maxInjections = 5
        )
        assertEquals(5, result.injectedCount)
    }

    // ─────────────────────────────────────────────────────── reshuffle

    @Test
    fun `reshuffle strips old injections and weaves fresh ones`() {
        val queue = listOf(user(1), rec(-101), user(2), rec(-102), user(3))
        val previousInjected = setOf(-101, -102)
        val fresh = listOf(rec(-201), rec(-202))

        val result = SmartShuffleEngine.reshuffle(
            queue = queue,
            currentIndex = 0,
            previousInjectedIds = previousInjected,
            freshRecommendations = fresh
        )
        // Old picks gone, fresh picks in.
        assertTrue(result.queue.none { it.id in previousInjected })
        assertTrue(result.queue.any { it.id == -201 })
        assertTrue(result.queue.any { it.id == -202 })
        // User order preserved.
        assertEquals(listOf(1, 2, 3), result.queue.filter { it.id > 0 }.map { it.id })
    }

    @Test
    fun `reshuffle never strips the playing track even if injected`() {
        val queue = listOf(rec(-101), user(1), user(2))
        val result = SmartShuffleEngine.reshuffle(
            queue = queue,
            currentIndex = 0,               // playing the injected -101
            previousInjectedIds = setOf(-101),
            freshRecommendations = listOf(rec(-201))
        )
        assertEquals(-101, result.queue[0].id)
    }

    @Test
    fun `empty queue is a no-op`() {
        val result = SmartShuffleEngine.interleave(emptyList(), currentIndex = -1, recommendations = listOf(rec(-1)))
        assertEquals(0, result.injectedCount)
        assertTrue(result.queue.isEmpty())
    }
}
