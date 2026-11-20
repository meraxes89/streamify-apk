package com.streamify.app.data.discovery

import com.streamify.app.data.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TasteProfileGuard JVM unit suite (Phase 2 — Gap #27).
 *
 * Locks the exclusion index semantics: stable normalized track keys,
 * immutable snapshots, playlist-level exclusion, and id resolution against
 * a catalog map.
 */
class TasteProfileGuardTest {

    private fun track(title: String, artist: String, id: Int = 1) =
        Track(id = id, title = title, artist = artist)

    @Test
    fun `track keys normalize whitespace and case`() {
        assertEquals(
            TasteExclusionIndex.trackKey("Same   Song", "The Artist"),
            TasteExclusionIndex.trackKey(" same song ", "the artist")
        )
    }

    @Test
    fun `track exclusion toggles immutably`() {
        val base = TasteExclusionIndex()
        val withA = base.withTrackExcluded("Song A", "Artist X", excluded = true)
        val withAB = withA.withTrackExcluded("Song B", "Artist Y", excluded = true)

        assertTrue(withA.isTrackExcluded(track("song a", "ARTIST X")))
        assertFalse(base.isTrackExcluded(track("Song A", "Artist X")))
        assertEquals(2, withAB.excludedTrackKeys.size)

        // Un-excluding returns to a clean state.
        val cleared = withAB.withTrackExcluded("Song A", "Artist X", excluded = false)
        assertFalse(cleared.isTrackExcluded(track("Song A", "Artist X")))
        assertTrue(cleared.isTrackExcluded(track("Song B", "Artist Y")))
    }

    @Test
    fun `playlist exclusion is case and whitespace insensitive`() {
        val index = TasteExclusionIndex(excludedPlaylistNames = setOf("workout 2024"))
        assertTrue(index.isPlaylistExcluded("Workout 2024"))
        assertTrue(index.isPlaylistExcluded("  WORKOUT   2024  "))
        assertFalse(index.isPlaylistExcluded("Chill Mix"))
    }

    @Test
    fun `playlist exclusion toggles immutably`() {
        val base = TasteExclusionIndex()
        val with = base.withPlaylistExcluded("Gym Mix", excluded = true)
        assertTrue(with.isPlaylistExcluded("gym mix"))
        val without = with.withPlaylistExcluded("Gym Mix", excluded = false)
        assertFalse(without.isPlaylistExcluded("Gym Mix"))
    }

    @Test
    fun `anyTrackIdExcluded resolves ids against the catalog`() {
        val index = TasteExclusionIndex()
            .withTrackExcluded("Secret Song", "Private Artist", excluded = true)
        val catalog = mapOf(
            1 to track("Public Song", "Known Artist", 1),
            2 to track("Secret Song", "Private Artist", 2)
        )
        assertTrue(index.anyTrackIdExcluded(listOf(1, 2), catalog))
        assertTrue(index.anyTrackIdExcluded(listOf(2), catalog))
        assertFalse(index.anyTrackIdExcluded(listOf(1), catalog))
        // Unknown ids never match.
        assertFalse(index.anyTrackIdExcluded(listOf(99), catalog))
    }

    @Test
    fun `empty index short-circuits to no exclusions`() {
        val index = TasteExclusionIndex()
        assertFalse(index.anyTrackIdExcluded(listOf(1, 2, 3), emptyMap()))
        assertFalse(index.isTrackExcluded(track("anything", "at all")))
        assertFalse(index.isPlaylistExcluded("Any Playlist"))
    }
}
