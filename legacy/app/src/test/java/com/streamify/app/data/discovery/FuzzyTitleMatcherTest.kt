package com.streamify.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the identity/fuzzy matching engine.
 * These back the CI "data-identity-fuzzy" shard: every CDN resolution,
 * queue dedup and library hydration decision flows through this gate, so
 * regressions here silently corrupt what users hear.
 */
class FuzzyTitleMatcherTest {

    // ── extractRootHash ────────────────────────────────────────────────

    @Test
    fun `root hash strips official video noise`() {
        assertEquals(
            FuzzyTitleMatcher.extractRootHash("Blinding Lights"),
            FuzzyTitleMatcher.extractRootHash("Blinding Lights (Official Video)")
        )
    }

    @Test
    fun `root hash is token order invariant`() {
        assertEquals(
            FuzzyTitleMatcher.extractRootHash("love story"),
            FuzzyTitleMatcher.extractRootHash("story love")
        )
    }

    @Test
    fun `root hash ignores remaster tags`() {
        assertEquals(
            FuzzyTitleMatcher.extractRootHash("Bohemian Rhapsody"),
            FuzzyTitleMatcher.extractRootHash("Bohemian Rhapsody (Remastered 2011)")
        )
    }

    @Test
    fun `root hash separates genuinely different songs`() {
        assertNotEquals(
            FuzzyTitleMatcher.extractRootHash("Blinding Lights"),
            FuzzyTitleMatcher.extractRootHash("Save Your Tears")
        )
    }

    @Test
    fun `root hash falls back for ultra short titles`() {
        val h = FuzzyTitleMatcher.extractRootHash("Go")
        assertNotEquals(0L, h)
    }

    // ── identity gates ────────────────────────────────────────────────

    @Test
    fun `titles match on case and punctuation differences`() {
        assertTrue(FuzzyTitleMatcher.titlesMatch("Mr. Brightside", "mr brightside"))
    }

    @Test
    fun `titles reject empty input`() {
        assertFalse(FuzzyTitleMatcher.titlesMatch("", "anything"))
        assertFalse(FuzzyTitleMatcher.titlesMatch("anything", ""))
    }

    @Test
    fun `artists strip distribution noise`() {
        assertTrue(FuzzyTitleMatcher.artistsMatch("The Weeknd", "The Weeknd - Topic"))
        assertTrue(FuzzyTitleMatcher.artistsMatch("Daft Punk", "Daft Punk VEVO"))
    }

    @Test
    fun `empty query artist never rejects but empty candidate does`() {
        assertTrue(FuzzyTitleMatcher.artistsMatch("", "Anyone"))
        assertFalse(FuzzyTitleMatcher.artistsMatch("Anyone", ""))
    }

    @Test
    fun `duration gate tolerates unknown durations`() {
        assertTrue(FuzzyTitleMatcher.durationMatches(0, 200))
        assertTrue(FuzzyTitleMatcher.durationMatches(200, -5))
        assertFalse(FuzzyTitleMatcher.durationMatches(180, 260))
        assertTrue(FuzzyTitleMatcher.durationMatches(180, 190))
    }

    @Test
    fun `same recording proof accepts verified candidate`() {
        assertTrue(
            FuzzyTitleMatcher.isSameRecording(
                titleA = "Snow (Hey Oh)", artistA = "Red Hot Chili Peppers", durationSecA = 349,
                titleB = "Snow Hey Oh (Official)", artistB = "Red Hot Chili Peppers - Topic", durationSecB = 351
            )
        )
    }

    @Test
    fun `same recording proof rejects wrong artist`() {
        assertFalse(
            FuzzyTitleMatcher.isSameRecording(
                titleA = "Snow (Hey Oh)", artistA = "Red Hot Chili Peppers", durationSecA = 349,
                titleB = "Snow (Hey Oh)", artistB = "Some Cover Band", durationSecB = 350
            )
        )
    }

    // ── variation detection ───────────────────────────────────────────

    @Test
    fun `subset medley title counts as same song variation`() {
        assertTrue(
            FuzzyTitleMatcher.isSameSongVariation(
                "House of Balloons",
                "The Weeknd",
                "House of Balloons / Glass Table Girls",
                "The Weeknd"
            )
        )
    }

    @Test
    fun `different songs are not variations`() {
        assertFalse(
            FuzzyTitleMatcher.isSameSongVariation(
                "Starboy",
                "The Weeknd",
                "Blinding Lights",
                "The Weeknd"
            )
        )
    }

    // ── similarity primitives ─────────────────────────────────────────

    @Test
    fun `similarity is one for identical and zero for empty`() {
        assertEquals(1.0, FuzzyTitleMatcher.calculateSimilarity("identical", "identical"), 1e-9)
        assertEquals(0.0, FuzzyTitleMatcher.calculateSimilarity("", "x"), 1e-9)
    }

    @Test
    fun `levenshtein distance basics`() {
        assertEquals(0, FuzzyTitleMatcher.computeLevenshtein("kitten", "kitten"))
        assertEquals(3, FuzzyTitleMatcher.computeLevenshtein("kitten", "sitting"))
        assertEquals(1, FuzzyTitleMatcher.computeLevenshtein("abc", "abd"))
    }
}
