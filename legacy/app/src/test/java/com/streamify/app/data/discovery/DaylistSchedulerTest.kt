package com.streamify.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * DaylistScheduler JVM unit suite (Phase 2 — Gap #20).
 *
 * Locks the circadian time-bucket edges, the [Top Artist] x [Mood
 * Keyword] query mapping, hero-title morphing cadence, and the
 * round-robin interleave with dedup.
 */
class DaylistSchedulerTest {

    // ───────────────────────────────────────────────────── bucket edges

    @Test
    fun `bucket edges map the four circadian dayparts`() {
        assertEquals(DaylistBucket.MORNING, DaylistTimeBuckets.bucketFor(6))
        assertEquals(DaylistBucket.MORNING, DaylistTimeBuckets.bucketFor(10))
        assertEquals(DaylistBucket.AFTERNOON, DaylistTimeBuckets.bucketFor(11))
        assertEquals(DaylistBucket.AFTERNOON, DaylistTimeBuckets.bucketFor(16))
        assertEquals(DaylistBucket.EVENING, DaylistTimeBuckets.bucketFor(17))
        assertEquals(DaylistBucket.EVENING, DaylistTimeBuckets.bucketFor(21))
        assertEquals(DaylistBucket.LATE_NIGHT, DaylistTimeBuckets.bucketFor(22))
        assertEquals(DaylistBucket.LATE_NIGHT, DaylistTimeBuckets.bucketFor(23))
        assertEquals(DaylistBucket.LATE_NIGHT, DaylistTimeBuckets.bucketFor(0))
        assertEquals(DaylistBucket.LATE_NIGHT, DaylistTimeBuckets.bucketFor(5))
    }

    @Test
    fun `out of range hours clamp safely`() {
        // Coerced via the else branch — no exception, still a valid bucket.
        assertEquals(DaylistBucket.LATE_NIGHT, DaylistTimeBuckets.bucketFor(-1))
        assertEquals(DaylistBucket.LATE_NIGHT, DaylistTimeBuckets.bucketFor(24))
    }

    @Test
    fun `every bucket carries exactly three mood keywords`() {
        DaylistBucket.entries.forEach { bucket ->
            assertEquals(3, bucket.moodKeywords.size)
            assertTrue(bucket.moodKeywords.all { it.isNotBlank() })
        }
    }

    // ───────────────────────────────────────────── mood query mapping

    @Test
    fun `mood queries combine top artists with rotating keywords`() {
        val queries = DaylistTimeBuckets.moodQueries(
            DaylistBucket.MORNING,
            listOf("Fred again..", "Polo & Pan", "ODESZA", "Extra Artist")
        )
        assertEquals(
            listOf(
                "Fred again.. Focus",
                "Polo & Pan Upbeat Acoustic",
                "ODESZA Morning Pop"
            ),
            queries
        )
    }

    @Test
    fun `cold start falls back to pure mood keywords`() {
        val queries = DaylistTimeBuckets.moodQueries(DaylistBucket.EVENING, emptyList())
        assertEquals(DaylistBucket.EVENING.moodKeywords, queries)
        val blank = DaylistTimeBuckets.moodQueries(DaylistBucket.EVENING, listOf("  ", ""))
        assertEquals(DaylistBucket.EVENING.moodKeywords, blank)
    }

    @Test
    fun `fewer artists than keywords reuse keywords in order`() {
        val queries = DaylistTimeBuckets.moodQueries(DaylistBucket.LATE_NIGHT, listOf("Enigma"))
        assertEquals(listOf("Enigma Ambient"), queries)
    }

    // ─────────────────────────────────────────────── hero title morphing

    @Test
    fun `hero title matches the directive example shape`() {
        // "Acoustic Morning Chill Tuesday" — directive example.
        assertEquals(
            "Acoustic Morning Chill Tuesday",
            DaylistTimeBuckets.heroTitle(DaylistBucket.MORNING, 1, DayOfWeek.TUESDAY)
        )
    }

    @Test
    fun `hero adjective morphs every refresh slot`() {
        val t0 = DaylistTimeBuckets.heroTitle(DaylistBucket.MORNING, 0, DayOfWeek.MONDAY)
        val t1 = DaylistTimeBuckets.heroTitle(DaylistBucket.MORNING, 1, DayOfWeek.MONDAY)
        val t2 = DaylistTimeBuckets.heroTitle(DaylistBucket.MORNING, 2, DayOfWeek.MONDAY)
        assertTrue(t0 != t1 && t1 != t2)
        // Day-of-week always appears.
        assertTrue(t0.endsWith("Monday"))
    }

    @Test
    fun `refresh slots divide the day into four hour blocks`() {
        assertEquals(0, DaylistTimeBuckets.refreshSlotFor(0))
        assertEquals(0, DaylistTimeBuckets.refreshSlotFor(3))
        assertEquals(1, DaylistTimeBuckets.refreshSlotFor(4))
        assertEquals(2, DaylistTimeBuckets.refreshSlotFor(9))
        assertEquals(3, DaylistTimeBuckets.refreshSlotFor(14))
        assertEquals(5, DaylistTimeBuckets.refreshSlotFor(23))
    }

    @Test
    fun `subtitle carries the mood keyword and cadence note`() {
        val sub = DaylistTimeBuckets.heroSubtitle(DaylistBucket.AFTERNOON, 0)
        assertEquals("Workout • Refreshes every 4 hours", sub)
    }

    // ──────────────────────────────────────────────── interleave + dedup

    private fun dt(videoId: String, title: String, artist: String, seed: String) =
        DaylistTrack(videoId = videoId, title = title, artist = artist, seedQuery = seed)

    @Test
    fun `interleave round-robins pools and dedups videoIds`() {
        val poolA = listOf(
            dt("AAAAAAAAAA1", "A1", "ArtistA", "q1"),
            dt("AAAAAAAAAA2", "A2", "ArtistA", "q1")
        )
        val poolB = listOf(
            dt("AAAAAAAAAA2", "A2 dup", "ArtistA", "q2"), // same videoId
            dt("BBBBBBBBBB1", "B1", "ArtistB", "q2")
        )
        val out = DaylistScheduler.interleave(listOf(poolA, poolB), limit = 10)
        assertEquals(listOf("A1", "A2 dup", "B1"), out.map { it.title })
        // Round-robin provenance: the first two rows come from different pools.
        assertEquals(listOf("q1", "q2", "q2"), out.map { it.seedQuery })
    }

    @Test
    fun `interleave dedups fuzzy title-artist pairs`() {
        val poolA = listOf(dt("AAAAAAAAAA1", "Same Song ", "The Artist", "q1"))
        val poolB = listOf(
            dt("BBBBBBBBBB1", "same song", "the artist", "q2"),
            dt("BBBBBBBBBB2", "Other", "Someone", "q2")
        )
        val out = DaylistScheduler.interleave(listOf(poolA, poolB), limit = 10)
        assertEquals(2, out.size)
        assertTrue(out.none { it.videoId == "BBBBBBBBBB1" })
    }

    @Test
    fun `interleave respects the limit`() {
        val poolA = (1..10).map { dt("AAAAAAAAA%02d".format(it), "A$it", "X", "q") }
        val poolB = (1..10).map { dt("BBBBBBBBB%02d".format(it), "B$it", "Y", "q") }
        val out = DaylistScheduler.interleave(listOf(poolA, poolB), limit = 7)
        assertEquals(7, out.size)
    }

    @Test
    fun `interleave handles empty pools`() {
        assertTrue(DaylistScheduler.interleave(listOf(emptyList(), emptyList()), 10).isEmpty())
        assertTrue(DaylistScheduler.interleave(emptyList(), 10).isEmpty())
    }

    @Test
    fun `bucket gradients are distinct and well formed`() {
        val grads = DaylistBucket.entries.map { it.gradientColors }
        assertEquals(DaylistBucket.entries.size, grads.toSet().size)
        grads.forEach { (a, b) ->
            assertTrue(a in 0xFF000000..0xFFFFFFFF)
            assertTrue(b in 0xFF000000..0xFFFFFFFF)
        }
    }
}
