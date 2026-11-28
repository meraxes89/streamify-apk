package com.maxrave.simpmusic.utils

import com.maxrave.domain.data.model.home.HomeItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeItemExtTest {

    private fun shelf(title: String, itemsPerColumn: Int? = null): HomeItem = HomeItem(
        contents = emptyList(),
        title = title,
        itemsPerColumn = itemsPerColumn,
    )

    @Test
    fun pinQuickPicksToTopIgnoresOtherFourRowSongShelves() {
        val listenAgain = shelf("Listen again")
        val trendingSongs = shelf("Trending songs", itemsPerColumn = 4)
        val quickPicks = shelf("Quick picks", itemsPerColumn = 4)

        val pinned = listOf(listenAgain, trendingSongs, quickPicks).pinQuickPicksToTop("Quick picks")

        assertEquals(listOf("Quick picks", "Listen again", "Trending songs"), pinned.map { it.title })
        assertTrue(trendingSongs.isQuickPicksShelf("Quick picks"))
        assertFalse(trendingSongs.isExactQuickPicksShelf("Quick picks"))
    }

    @Test
    fun pinQuickPicksToTopMatchesLocalizedTitle() {
        val listenAgain = shelf("Listen again")
        val localized = shelf("Sélection Rapide", itemsPerColumn = 4)

        val pinned = listOf(listenAgain, localized).pinQuickPicksToTop("Sélection Rapide")

        assertEquals(listOf("Sélection Rapide", "Listen again"), pinned.map { it.title })
    }

    @Test
    fun pinQuickPicksToTopLeavesOrderUnchangedWhenQuickPicksMissing() {
        val listenAgain = shelf("Listen again")
        val heardInShorts = shelf("Heard in Shorts", itemsPerColumn = 4)

        val pinned = listOf(listenAgain, heardInShorts).pinQuickPicksToTop("Quick picks")

        assertEquals(listOf("Listen again", "Heard in Shorts"), pinned.map { it.title })
    }
}
