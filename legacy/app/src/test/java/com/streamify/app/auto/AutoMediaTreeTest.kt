package com.streamify.app.auto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AutoMediaTreeTest (Phase 4 — Gap #54) — the driving-safe browsing
 * hierarchy Android Auto renders:
 *  • root structure + shelf order (Favorites → Daily Mixes → [Jam] → Downloaded)
 *  • playable leaf conversion + prefix-addressed routing
 *  • the 12-row distraction cap
 *  • jam row appears only while a session is live
 *  • unknown parents degrade to empty
 */
class AutoMediaTreeTest {

    private class FakeSource(
        var favorites: List<AutoMediaNode> = emptyList(),
        var mixes: List<AutoMediaNode> = emptyList(),
        var jam: AutoMediaNode? = null,
        var downloaded: List<AutoMediaNode> = emptyList()
    ) : AutoContentSource {
        override suspend fun favoriteTracks(): List<AutoMediaNode> = favorites
        override suspend fun dailyMixTracks(): List<AutoMediaNode> = mixes
        override suspend fun jamQuickJoin(): AutoMediaNode? = jam
        override suspend fun downloadedTracks(): List<AutoMediaNode> = downloaded
    }

    private fun trackNode(id: String, title: String = id) = AutoMediaNode(
        mediaId = id,
        title = title,
        subtitle = "Artist of $title",
        artworkUri = "https://img/$id.jpg",
        playbackKey = id.removePrefix("fav-").removePrefix("mix-").removePrefix("dl-")
    )

    @Test
    fun `root node is browsable and not playable`() {
        val root = AutoRootMediaTree().root()
        assertEquals(AutoIds.ROOT, root.mediaId)
        assertTrue(root.browsable)
        assertTrue(!root.playable)
    }

    @Test
    fun `shelf order is favorites then mixes then optional jam then downloaded`() = runBlocking {
        val jam = AutoMediaNode(mediaId = AutoIds.JAM_QUICK_JOIN, title = "Join Jam")
        val tree = AutoRootMediaTree(FakeSource(jam = jam))
        val shelves = tree.childrenOf(AutoIds.ROOT).map { it.mediaId }

        assertEquals(
            listOf(AutoIds.FAVORITES, AutoIds.DAILY_MIXES, AutoIds.JAM_QUICK_JOIN, AutoIds.DOWNLOADED),
            shelves
        )
    }

    @Test
    fun `jam shelf is absent without a live session`() = runBlocking {
        val shelves = AutoRootMediaTree(FakeSource()).childrenOf(AutoIds.ROOT).map { it.mediaId }
        assertEquals(
            listOf(AutoIds.FAVORITES, AutoIds.DAILY_MIXES, AutoIds.DOWNLOADED),
            shelves
        )
    }

    @Test
    fun `shelf nodes are browsable and not playable`() = runBlocking {
        val shelves = AutoRootMediaTree(FakeSource()).childrenOf(AutoIds.ROOT)
        assertTrue(shelves.all { it.browsable && !it.playable })
    }

    @Test
    fun `favorite leaves are playable with metadata`() = runBlocking {
        val tree = AutoRootMediaTree(
            FakeSource(favorites = listOf(trackNode(AutoRootMediaTree.favoriteId("vid-1"), "Believer")))
        )
        val children = tree.childrenOf(AutoIds.FAVORITES)

        assertEquals(1, children.size)
        val leaf = children.first()
        assertTrue(leaf.playable)
        assertTrue(!leaf.browsable)
        assertEquals("Believer", leaf.title)
        assertEquals("Artist of Believer", leaf.subtitle)
        assertEquals("https://img/${AutoRootMediaTree.favoriteId("vid-1")}.jpg", leaf.artworkUri)
    }

    @Test
    fun `browse levels cap at 12 distraction-safe rows`() = runBlocking {
        val many = (1..50).map { trackNode(AutoRootMediaTree.favoriteId("v$it"), "Song $it") }
        val tree = AutoRootMediaTree(FakeSource(favorites = many))

        assertEquals(AutoRootMediaTree.MAX_CHILDREN, tree.childrenOf(AutoIds.FAVORITES).size)
        // Stable truncation: first 12 in source order.
        assertEquals(
            (1..12).map { "Song $it" },
            tree.childrenOf(AutoIds.FAVORITES).map { it.title }
        )
    }

    @Test
    fun `itemFor resolves leaves through their owning shelf`() = runBlocking {
        val tree = AutoRootMediaTree(
            FakeSource(
                favorites = listOf(trackNode(AutoRootMediaTree.favoriteId("vid-9"), "Fav Song")),
                mixes = listOf(trackNode(AutoRootMediaTree.dailyMixId("vid-2"), "Mix Song"))
            )
        )
        assertEquals("Fav Song", tree.itemFor(AutoRootMediaTree.favoriteId("vid-9"))?.title)
        assertEquals("Mix Song", tree.itemFor(AutoRootMediaTree.dailyMixId("vid-2"))?.title)
        assertEquals(AutoIds.ROOT, tree.itemFor(AutoIds.ROOT)?.mediaId)
    }

    @Test
    fun `unknown parents and ids degrade gracefully`() = runBlocking {
        val tree = AutoRootMediaTree(FakeSource())
        assertTrue(tree.childrenOf("no-such-shelf").isEmpty())
        assertNull(tree.itemFor("fav-does-not-exist"))
    }

    @Test
    fun `parentIdOf routes leaves by prefix`() {
        val tree = AutoRootMediaTree()
        assertEquals(AutoIds.FAVORITES, tree.parentIdOf(AutoRootMediaTree.favoriteId("x")))
        assertEquals(AutoIds.DAILY_MIXES, tree.parentIdOf(AutoRootMediaTree.dailyMixId("x")))
        assertEquals(AutoIds.DOWNLOADED, tree.parentIdOf(AutoRootMediaTree.downloadedId("x")))
        assertEquals(AutoIds.ROOT, tree.parentIdOf(AutoRootMediaTree.jamId("ROOM")))
        assertEquals(AutoIds.ROOT, tree.parentIdOf("anything-else"))
    }

    @Test
    fun `id builders are prefix-consistent`() {
        assertEquals("fav-k", AutoRootMediaTree.favoriteId("k"))
        assertEquals("mix-k", AutoRootMediaTree.dailyMixId("k"))
        assertEquals("dl-k", AutoRootMediaTree.downloadedId("k"))
        assertEquals("jam-9AB3", AutoRootMediaTree.jamId("9AB3"))
    }

    @Test
    fun `jam quick join row carries live member count`() = runBlocking {
        val jam = AutoMediaNode(
            mediaId = AutoRootMediaTree.jamId("AB12CD"),
            title = "Join Jam",
            subtitle = "4 listening together",
            browsable = false,
            playable = false
        )
        val tree = AutoRootMediaTree(FakeSource(jam = jam))
        val row = tree.childrenOf(AutoIds.ROOT).first { it.mediaId == AutoRootMediaTree.jamId("AB12CD") }
        assertEquals("4 listening together", row.subtitle)
    }
}
