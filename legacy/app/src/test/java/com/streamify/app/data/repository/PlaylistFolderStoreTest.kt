package com.streamify.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.junit.BeforeClass
import com.streamify.app.testkit.JvmSLogSink

/**
 * PlaylistFolderStore + LibraryOrganizer JVM unit suite (Phase 3 —
 * playlist folder hierarchy and library sort/filter).
 *
 * Locks:
 *  • Folder CRUD, idempotent moves, delete-promotes-children semantics.
 *  • Pin toggling with recency ordering + JSON persistence round-trips.
 *  • LibraryOrganizer: three sort keys, pinned-first floating, natural
 *    key normalization (articles, embedded numerics), and filters.
 */
class PlaylistFolderStoreTest {


    companion object {
        // Strict-mock migration (P1): SLog reaches android.util.Log on this
        // path. Install the explicit JVM log fake instead of relying on
        // isReturnDefaultValues = true.
        @BeforeClass
        @JvmStatic
        fun installJvmSLogSink() {
            JvmSLogSink.install()
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private fun freshStore(): File = File(tmp.newFolder(), "playlist_folders.json")

    // ─────────────────────────────────────────────────── folder hierarchy

    @Test
    fun `create move and query folders`() {
        PlaylistFolderStore.initWith(freshStore())
        val folder = PlaylistFolderStore.createFolder("Workout Mixes")
        assertEquals("Workout Mixes", folder.name)

        assertTrue(PlaylistFolderStore.movePlaylistToFolder("pl-1", folder.id))
        assertTrue(PlaylistFolderStore.movePlaylistToFolder("pl-2", folder.id))
        assertEquals(2, PlaylistFolderStore.folderById(folder.id)!!.playlistIds.size)
        assertEquals(folder.id, PlaylistFolderStore.folderOf("pl-1")!!.id)

        // Moving to root removes membership.
        assertTrue(PlaylistFolderStore.movePlaylistToFolder("pl-1", null))
        assertNull(PlaylistFolderStore.folderOf("pl-1"))
        assertEquals(1, PlaylistFolderStore.folderById(folder.id)!!.playlistIds.size)
    }

    @Test
    fun `move is idempotent - no double membership`() {
        PlaylistFolderStore.initWith(freshStore())
        val folder = PlaylistFolderStore.createFolder("Focus")
        PlaylistFolderStore.movePlaylistToFolder("pl-1", folder.id)
        PlaylistFolderStore.movePlaylistToFolder("pl-1", folder.id)
        assertEquals(1, PlaylistFolderStore.folderById(folder.id)!!.playlistIds.size)
    }

    @Test
    fun `move to unknown folder fails safely`() {
        PlaylistFolderStore.initWith(freshStore())
        assertTrue(!PlaylistFolderStore.movePlaylistToFolder("pl-1", "no-such-folder"))
    }

    @Test
    fun `deleting a folder promotes members to root`() {
        PlaylistFolderStore.initWith(freshStore())
        val folder = PlaylistFolderStore.createFolder("Temp")
        PlaylistFolderStore.movePlaylistToFolder("pl-9", folder.id)
        assertTrue(PlaylistFolderStore.deleteFolder(folder.id))
        assertNull(PlaylistFolderStore.folderOf("pl-9"))
        assertTrue(PlaylistFolderStore.foldersSnapshot().isEmpty())
        // The playlist itself is untouched — it lives at root now.
    }

    @Test
    fun `folder persistence round-trips through the JSON file`() {
        val file = freshStore()
        PlaylistFolderStore.initWith(file)
        val folder = PlaylistFolderStore.createFolder("Road Trip")
        PlaylistFolderStore.movePlaylistToFolder("pl-a", folder.id)
        PlaylistFolderStore.movePlaylistToFolder("pl-b", folder.id)
        PlaylistFolderStore.togglePin("pl-c")

        // Fresh instance over the same file must see identical state.
        PlaylistFolderStore.initWith(file)
        val reloaded = PlaylistFolderStore.folderById(folder.id)
        assertNotNull(reloaded)
        assertEquals(listOf("pl-a", "pl-b"), reloaded!!.playlistIds)
        assertEquals("Road Trip", reloaded.name)
        assertEquals(listOf("pl-c"), PlaylistFolderStore.pinnedSnapshot())
    }

    @Test
    fun `corrupt store file starts clean instead of throwing`() {
        val file = freshStore()
        file.writeText("this is not json {{{")
        PlaylistFolderStore.initWith(file)
        assertTrue(PlaylistFolderStore.foldersSnapshot().isEmpty())
        assertTrue(PlaylistFolderStore.pinnedSnapshot().isEmpty())
    }

    // ──────────────────────────────────────────────────────────── the pins

    @Test
    fun `pin toggle flips membership and recency orders first`() {
        PlaylistFolderStore.initWith(freshStore())
        PlaylistFolderStore.togglePin("pl-1")
        PlaylistFolderStore.togglePin("pl-2")
        assertEquals(listOf("pl-2", "pl-1"), PlaylistFolderStore.pinnedSnapshot()) // most recent first
        // Toggling a pinned id UNPINS it → returns false (new state).
        assertTrue(!PlaylistFolderStore.togglePin("pl-1"))
        assertTrue(!PlaylistFolderStore.isPinned("pl-1"))
        assertEquals(listOf("pl-2"), PlaylistFolderStore.pinnedSnapshot())
        // Re-pin → true, and pl-1 floats back to the front.
        assertTrue(PlaylistFolderStore.togglePin("pl-1"))
        assertEquals(listOf("pl-1", "pl-2"), PlaylistFolderStore.pinnedSnapshot())
    }

    // ─────────────────────────────────────────────── LibraryOrganizer sort

    private fun item(
        id: String,
        name: String,
        creator: String = "",
        createdAt: Long = 0L,
        downloaded: Boolean = false,
        kind: String = "playlist"
    ) = LibraryOrganizer.Organizable(
        id = id, name = name, creator = creator, createdAtMs = createdAt,
        isDownloaded = downloaded,
        isPlaylist = kind == "playlist",
        isAlbum = kind == "album",
        isArtist = kind == "artist"
    )

    @Test
    fun `recently added sorts newest first`() {
        val items = listOf(
            item("a", "Old", createdAt = 100L),
            item("b", "New", createdAt = 900L),
            item("c", "Mid", createdAt = 500L)
        )
        val sorted = LibraryOrganizer.organize(items, sort = LibraryOrganizer.LibrarySort.RECENTLY_ADDED)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `alphabetical ignores leading articles and sorts numerics naturally`() {
        val items = listOf(
            item("1", "The Beatles"),
            item("2", "Abbey Road"),
            item("3", "Vol 10 Anthology"),
            item("4", "Vol 2 Highlights")
        )
        val sorted = LibraryOrganizer.organize(items, sort = LibraryOrganizer.LibrarySort.ALPHABETICAL)
        // "The Beatles" → "beatles"; "Vol 2" < "Vol 10" numerically.
        assertEquals(listOf("2", "1", "4", "3"), sorted.map { it.id })
    }

    @Test
    fun `creator sort groups by creator then name`() {
        val items = listOf(
            item("a", "Zeta", creator = "bob"),
            item("b", "Alpha", creator = "alice"),
            item("c", "Beta", creator = "bob"),
            item("d", "Omega", creator = "alice")
        )
        val sorted = LibraryOrganizer.organize(items, sort = LibraryOrganizer.LibrarySort.CREATOR)
        assertEquals(listOf("b", "d", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `pinned items float above the sort regardless of key`() {
        val items = listOf(
            item("old-pin", "Ancient", createdAt = 10L),
            item("newest", "Fresh", createdAt = 9_000L)
        )
        val sorted = LibraryOrganizer.organize(
            items,
            sort = LibraryOrganizer.LibrarySort.RECENTLY_ADDED,
            pinnedIds = listOf("old-pin")
        )
        assertEquals("old-pin", sorted.first().id)
        assertEquals("newest", sorted[1].id)
    }

    @Test
    fun `most recently pinned ranks first among pins`() {
        // Store semantics: the pinned list is most-recent-first (togglePin
        // prepends), so [p2, p1] means p2 was pinned AFTER p1.
        val sorted = LibraryOrganizer.organize(
            listOf(item("p1", "One"), item("p2", "Two")),
            sort = LibraryOrganizer.LibrarySort.ALPHABETICAL,
            pinnedIds = listOf("p2", "p1")
        )
        assertEquals(listOf("p2", "p1"), sorted.map { it.id })
    }

    @Test
    fun `filters select by kind`() {
        val items = listOf(
            item("pl", "Mix", kind = "playlist"),
            item("al", "Record", kind = "album"),
            item("ar", "Performer", kind = "artist"),
            item("dl", "Offline", downloaded = true)
        )
        assertEquals(
            "al",
            LibraryOrganizer.organize(items, filter = LibraryOrganizer.LibraryFilter.ALBUMS).single().id
        )
        assertEquals(
            "ar",
            LibraryOrganizer.organize(items, filter = LibraryOrganizer.LibraryFilter.ARTISTS).single().id
        )
        assertEquals(
            "dl",
            LibraryOrganizer.organize(items, filter = LibraryOrganizer.LibraryFilter.DOWNLOADED).single().id
        )
        assertEquals(4, LibraryOrganizer.organize(items, filter = LibraryOrganizer.LibraryFilter.ALL).size)
    }

    @Test
    fun `natural key strips articles and pads numerics`() {
        assertEquals("beatles", LibraryOrganizer.naturalKey("The Beatles"))
        assertEquals("beatles 000003", LibraryOrganizer.naturalKey("Beatles 3"))
        assertEquals("---", LibraryOrganizer.naturalKey("---"))
    }
}
