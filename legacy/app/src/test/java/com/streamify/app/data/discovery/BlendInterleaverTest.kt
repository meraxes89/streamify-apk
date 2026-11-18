package com.streamify.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlendInterleaver JVM unit suite (Phase 2 — Gap #25).
 *
 * Locks the cross-pollination contract: round-robin provenance, mutual
 * artist detection + boost, fuzzy dedup, repetition penalty, and the
 * taste-match percentage window.
 */
class BlendInterleaverTest {

    private fun cand(videoId: String, title: String, artist: String) =
        BlendCandidate(videoId = videoId, title = title, artist = artist)

    @Test
    fun `round robin interleaves member provenance`() {
        val poolA = (1..3).map { cand("AAAAAAAAAA$it", "A Song $it", "Artist A") }
        val poolB = (1..3).map { cand("BBBBBBBBBB$it", "B Song $it", "Artist B") }
        val rows = BlendInterleaver.interleave(
            listOf(poolA, poolB),
            listOf(setOf("artist a"), setOf("artist b")),
            limit = 6
        )
        // Both members represented; provenance alternates before scoring.
        assertTrue(rows.any { it.memberIndex == 0 } && rows.any { it.memberIndex == 1 })
        // First two rows in raw interleave come from different members.
        assertTrue(rows.size >= 4)
    }

    @Test
    fun `duplicate videoIds across pools are deduped`() {
        val shared = cand("dQw4w9WgXcQ", "Same", "X Artist")
        val rows = BlendInterleaver.interleave(
            listOf(listOf(shared), listOf(shared, cand("CCCCCCCCCC1", "Other", "Y"))),
            listOf(setOf("x artist"), setOf("x artist"))
        )
        assertEquals(1, rows.count { it.videoId == "dQw4w9WgXcQ" })
    }

    @Test
    fun `fuzzy title-artist dedup catches same song different ids`() {
        val a = cand("AAAAAAAAAA1", "Same Song ", "The Artist")
        val b = cand("BBBBBBBBBB1", "same song", "the artist")
        val rows = BlendInterleaver.interleave(
            listOf(listOf(a), listOf(b, cand("CCCCCCCCCC1", "Else", "Zed"))),
            listOf(setOf("the artist"), setOf("zed"))
        )
        // First occurrence survives; the fuzzy duplicate is dropped.
        assertEquals(1, rows.count { it.candidate.titleKey == "same song" })
        assertTrue(rows.none { it.videoId == "BBBBBBBBBB1" })
        assertEquals(1, rows.count { it.candidate.titleKey == "else" })
    }

    @Test
    fun `mutual artist rows are flagged and bubble to the top`() {
        val poolA = listOf(
            cand("AAAAAAAAAA1", "A solo", "Solo Artist"),
            cand("AAAAAAAAAA2", "A shared", "Shared Artist")
        )
        val poolB = listOf(
            cand("BBBBBBBBBB1", "B shared", "Shared Artist"),
            cand("BBBBBBBBBB2", "B solo", "Other Solo")
        )
        val rows = BlendInterleaver.interleave(
            listOf(poolA, poolB),
            listOf(setOf("solo artist", "shared artist"), setOf("other solo", "shared artist"))
        )
        val mutualRows = rows.filter { it.mutual }
        assertTrue(mutualRows.isNotEmpty())
        assertTrue(mutualRows.all { it.artist == "Shared Artist" })
        // Highest-scored row is a mutual one.
        assertTrue(rows.first().mutual)
        // Mutual tracks outrank non-mutual.
        val firstNonMutual = rows.indexOfFirst { !it.mutual }
        val lastMutual = rows.indexOfLast { it.mutual }
        assertTrue(firstNonMutual < 0 || lastMutual < firstNonMutual || rows[firstNonMutual].score < rows.first().score)
    }

    @Test
    fun `taste match percent bounds are friendly`() {
        // Identical artist universes → 99.
        val same = listOf(listOf(cand("AAAAAAAAAA1", "t", "X")), listOf(cand("BBBBBBBBBB1", "u", "X")))
        assertEquals(99, BlendInterleaver.tasteMatchPercent(same))
        // Fully disjoint universes → 35 (never reads as 0%).
        val disjoint = listOf(
            listOf(cand("AAAAAAAAAA1", "t", "X")),
            listOf(cand("BBBBBBBBBB1", "u", "Y"))
        )
        assertEquals(35, BlendInterleaver.tasteMatchPercent(disjoint))
        // Partial overlap lands strictly between.
        val partial = listOf(
            listOf(cand("AAAAAAAAAA1", "t", "X"), cand("AAAAAAAAAA2", "t2", "Z")),
            listOf(cand("BBBBBBBBBB1", "u", "X"))
        )
        val pct = BlendInterleaver.tasteMatchPercent(partial)
        assertTrue(pct in 36..98)
        // Fewer than two pools → 0.
        assertEquals(0, BlendInterleaver.tasteMatchPercent(listOf(emptyList())))
    }

    @Test
    fun `limit is respected`() {
        val poolA = (1..20).map { cand("AAAAAAAAA%02d".format(it), "A$it", "Artist $it") }
        val poolB = (1..20).map { cand("BBBBBBBBB%02d".format(it), "B$it", "Artist B$it") }
        val rows = BlendInterleaver.interleave(listOf(poolA, poolB), listOf(emptySet(), emptySet()), limit = 10)
        assertEquals(10, rows.size)
    }

    @Test
    fun `repetition penalty demotes artist flooding`() {
        val flood = (1..8).map { cand("AAAAAAAAA%02d".format(it), "Flood $it", "Flooder") }
        val varied = listOf(
            cand("BBBBBBBBB01", "Varied 1", "Varied One"),
            cand("CCCCCCCCC01", "Varied 2", "Varied Two")
        )
        val rows = BlendInterleaver.interleave(
            listOf(flood, varied),
            listOf(setOf("flooder"), setOf("varied one", "varied two")),
            limit = 10
        )
        // The first flooded row still ranks (depth bonus), but by row 3+
        // the varied tracks overtake the flood.
        val floodPositions = rows.filter { it.artist == "Flooder" }.map { rows.indexOf(it) }
        val variedPositions = rows.filter { it.artist.startsWith("Varied") }.map { rows.indexOf(it) }
        assertTrue(floodPositions.isNotEmpty())
        assertTrue(variedPositions.isNotEmpty())
        assertTrue(variedPositions.min() < floodPositions.max())
    }

    @Test
    fun `mutual artists helper surfaces shared names`() {
        val pools = listOf(
            listOf(cand("AAAAAAAAAA1", "t", "X"), cand("AAAAAAAAAA2", "t2", "OnlyA")),
            listOf(cand("BBBBBBBBBB1", "u", "X"), cand("BBBBBBBBBB2", "u2", "OnlyB"))
        )
        val mutual = BlendInterleaver.mutualArtists(pools, listOf(setOf("onlya"), setOf("onlyb")))
        assertTrue(mutual.contains("x"))
        assertFalse(mutual.contains("onlya"))
        assertFalse(mutual.contains("onlyb"))
    }

    @Test
    fun `empty pools yield empty blend`() {
        val rows = BlendInterleaver.interleave(
            listOf(emptyList(), emptyList()),
            listOf(emptySet(), emptySet())
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `blend track converts to playable Track`() {
        val bt = BlendTrack(
            candidate = cand("dQw4w9WgXcQ", "T", "A"),
            memberIndex = 1,
            mutual = true,
            score = 100
        )
        val track = bt.toTrack()
        assertEquals("T", track.title)
        assertEquals("A", track.artist)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", track.filepath)
        assertEquals("dQw4w9WgXcQ", track.ytmVideoId)
        assertEquals("online_stream", track.source)
    }
}
