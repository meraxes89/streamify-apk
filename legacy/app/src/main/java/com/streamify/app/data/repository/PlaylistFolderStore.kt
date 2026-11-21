package com.streamify.app.data.repository

import android.content.Context
import com.streamify.app.util.SLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PlaylistFolderStore — folders, pins & library organization (Phase 3, 4)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The Power Library data layer:
 *
 *  • Hierarchical playlist folders — one nesting level (Spotify-style
 *    "folders of playlists"); deleting a folder promotes its children to
 *    the root, never orphans them.
 *  • Pins — pinned playlists/albums render above everything; pin order is
 *    the recency of pinning (most recently pinned first).
 *  • LibraryOrganizer — the pure multi-criteria sort/filter engine used by
 *    the Library sort bar: RECENTLY_ADDED / ALPHABETICAL / CREATOR with
 *    pinned-first stability.
 *
 * Persistence: one JSON file in filesDir, same atomic-load pattern as
 * PlaylistRepository. Injectable file path for JVM unit tests. All mutations
 * are synchronous + serialized on a single lock — the library UI reads
 * snapshots, never partial state.
 */
object PlaylistFolderStore {

    private const val TAG = "PlaylistFolderStore"
    private const val FILE_NAME = "playlist_folders.json"

    /** One library folder containing playlists (single nesting level). */
    data class Folder(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        /** Playlist ids inside this folder, in user order. */
        val playlistIds: List<String> = emptyList(),
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val lock = Any()
    private var storeFile: File? = null

    private val _folders = MutableStateFlow<List<Folder>>(emptyList())
    val folders: StateFlow<List<Folder>> = _folders.asStateFlow()

    private val _pinnedIds = MutableStateFlow<List<String>>(emptyList())
    val pinnedIds: StateFlow<List<String>> = _pinnedIds.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            storeFile = File(context.filesDir, FILE_NAME)
            load()
        }
    }

    /** Test / alternate-location injection. */
    fun initWith(file: File?) {
        synchronized(lock) {
            storeFile = file
            load()
        }
    }

    // ────────────────────────────────────────────────────── folder mutating

    fun createFolder(name: String): Folder = synchronized(lock) {
        val clean = name.trim()
        val folder = Folder(name = clean.ifBlank { "New Folder" })
        _folders.value = _folders.value + folder
        persist()
        folder
    }

    fun renameFolder(folderId: String, newName: String): Boolean = synchronized(lock) {
        val clean = newName.trim()
        if (clean.isBlank()) return false
        val updated = _folders.value.map {
            if (it.id == folderId) it.copy(name = clean) else it
        }
        if (updated == _folders.value) return false
        _folders.value = updated
        persist()
        true
    }

    /**
     * Deletes a folder; member playlists are promoted to the root level
     * (they are never lost).
     */
    fun deleteFolder(folderId: String): Boolean = synchronized(lock) {
        val before = _folders.value
        _folders.value = before.filterNot { it.id == folderId }
        if (_folders.value == before) return false
        persist()
        true
    }

    /** Moves a playlist into a folder (null = root). Idempotent. */
    fun movePlaylistToFolder(playlistId: String, folderId: String?): Boolean = synchronized(lock) {
        // Remove from every folder first (idempotency).
        val cleared = _folders.value.map { folder ->
            if (folder.playlistIds.contains(playlistId)) {
                folder.copy(playlistIds = folder.playlistIds - playlistId)
            } else folder
        }
        val after = if (folderId != null) {
            val target = cleared.find { it.id == folderId } ?: return false
            if (target.playlistIds.contains(playlistId)) {
                cleared
            } else {
                cleared.map { folder ->
                    if (folder.id == folderId) folder.copy(playlistIds = folder.playlistIds + playlistId) else folder
                }
            }
        } else {
            cleared
        }
        _folders.value = after
        persist()
        true
    }

    fun folderOf(playlistId: String): Folder? =
        _folders.value.find { it.playlistIds.contains(playlistId) }

    fun folderById(folderId: String): Folder? =
        _folders.value.find { it.id == folderId }

    fun foldersSnapshot(): List<Folder> = _folders.value

    // ──────────────────────────────────────────────────────────── the pins

    /** Pin order = recency: most recently pinned first. */
    fun togglePin(playlistId: String): Boolean = synchronized(lock) {
        val current = _pinnedIds.value
        _pinnedIds.value = if (current.contains(playlistId)) {
            current - playlistId
        } else {
            listOf(playlistId) + current
        }
        persist()
        _pinnedIds.value.contains(playlistId)
    }

    fun isPinned(playlistId: String): Boolean =
        _pinnedIds.value.contains(playlistId)

    fun pinnedSnapshot(): List<String> = _pinnedIds.value

    // ──────────────────────────────────────────────────── JSON persistence

    private fun load() {
        val file = storeFile ?: return
        runCatching {
            if (!file.exists()) {
                _folders.value = emptyList()
                _pinnedIds.value = emptyList()
                return
            }
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val foldersArr = root.optJSONArray("folders") ?: JSONArray()
            val loaded = ArrayList<Folder>(foldersArr.length())
            for (i in 0 until foldersArr.length()) {
                val o = foldersArr.optJSONObject(i) ?: continue
                val ids = ArrayList<String>()
                val idsArr = o.optJSONArray("playlistIds") ?: JSONArray()
                for (j in 0 until idsArr.length()) {
                    ids.add(idsArr.optString(j, ""))
                }
                loaded.add(
                    Folder(
                        id = o.optString("id", ""),
                        name = o.optString("name", ""),
                        playlistIds = ids.filter { it.isNotBlank() },
                        createdAtMs = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            _folders.value = loaded.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            val pinnedArr = root.optJSONArray("pinned") ?: JSONArray()
            val pinned = ArrayList<String>(pinnedArr.length())
            for (i in 0 until pinnedArr.length()) {
                pinned.add(pinnedArr.optString(i, ""))
            }
            _pinnedIds.value = pinned.filter { it.isNotBlank() }
        }.onFailure {
            SLog.d(TAG, "load failed (${it.message}) — starting clean")
            _folders.value = emptyList()
            _pinnedIds.value = emptyList()
        }
    }

    private fun persist() {
        val file = storeFile ?: return
        runCatching {
            val root = JSONObject()
            val foldersArr = JSONArray()
            _folders.value.forEach { folder ->
                foldersArr.put(
                    JSONObject()
                        .put("id", folder.id)
                        .put("name", folder.name)
                        .put("playlistIds", JSONArray(folder.playlistIds))
                        .put("createdAt", folder.createdAtMs)
                )
            }
            root.put("folders", foldersArr)
            root.put("pinned", JSONArray(_pinnedIds.value))
            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }.onFailure {
            SLog.d(TAG, "persist failed (${it.message})")
        }
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LibraryOrganizer — pure multi-criteria sort/filter for the Power Library
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * No Android, no IO — 100% JVM unit-testable ordering semantics:
 *
 *  • Sort keys: RECENTLY_ADDED (createdAt desc), ALPHABETICAL (name asc,
 *    case-insensitive, numeric-aware), CREATOR (creator then name).
 *  • Pinned-first: pinned items float above unpinned within the SAME sort,
 *    most-recently-pinned first among pins.
 *  • Folders NEVER break pin logic: a pinned playlist renders at the top
 *    even if its folder is not expanded.
 */
object LibraryOrganizer {

    enum class LibrarySort(val label: String) {
        RECENTLY_ADDED("Recently added"),
        ALPHABETICAL("Alphabetical"),
        CREATOR("Creator")
    }

    enum class LibraryFilter(val label: String) {
        ALL("All"),
        PLAYLISTS("Playlists"),
        ALBUMS("Albums"),
        ARTISTS("Artists"),
        DOWNLOADED("Downloaded")
    }

    data class Organizable(
        val id: String,
        val name: String,
        val creator: String = "",
        val createdAtMs: Long = 0L,
        val isDownloaded: Boolean = false,
        val isPlaylist: Boolean = true,
        val isAlbum: Boolean = false,
        val isArtist: Boolean = false
    )

    /**
     * Sort + filter a library snapshot. Pure: same inputs → same outputs,
     * stable for equal keys (backing insertion order preserved).
     */
    fun organize(
        items: List<Organizable>,
        filter: LibraryFilter = LibraryFilter.ALL,
        sort: LibrarySort = LibrarySort.RECENTLY_ADDED,
        pinnedIds: List<String> = emptyList()
    ): List<Organizable> {
        val filtered = when (filter) {
            LibraryFilter.PLAYLISTS -> items.filter { it.isPlaylist }
            LibraryFilter.ALBUMS -> items.filter { it.isAlbum }
            LibraryFilter.ARTISTS -> items.filter { it.isArtist }
            LibraryFilter.DOWNLOADED -> items.filter { it.isDownloaded }
            LibraryFilter.ALL -> items
        }

        val pinRank = HashMap<String, Int>(pinnedIds.size)
        pinnedIds.forEachIndexed { index, id -> pinRank[id] = pinnedIds.size - index }

        val comparator = compareByDescending<Organizable> { item ->
            pinRank[item.id] ?: 0
        }.thenComparator { a, b ->
            when (sort) {
                LibrarySort.RECENTLY_ADDED -> b.createdAtMs.compareTo(a.createdAtMs)
                LibrarySort.ALPHABETICAL -> naturalKey(a.name).compareTo(naturalKey(b.name))
                LibrarySort.CREATOR -> {
                    val byCreator = naturalKey(a.creator).compareTo(naturalKey(b.creator))
                    if (byCreator != 0) byCreator else naturalKey(a.name).compareTo(naturalKey(b.name))
                }
            }
        }
        return filtered.sortedWith(comparator)
    }

    /**
     * Case-insensitive, leading-article-aware ("The Beatles" → "Beatles"),
     * numeric-aware ("Vol 2" < "Vol 10") sort key.
     */
    internal fun naturalKey(name: String): String {
        var key = name.trim().lowercase()
        for (article in listOf("the ", "a ", "an ")) {
            if (key.startsWith(article) && key.length > article.length) {
                key = key.removePrefix(article)
                break
            }
        }
        return key.padNumericSegments()
    }

    /** Zero-pads embedded numbers so lexicographic order matches numeric. */
    private fun String.padNumericSegments(): String {
        val sb = StringBuilder(length + 8)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isDigit()) {
                val start = i
                while (i < length && this[i].isDigit()) i++
                val digits = substring(start, i)
                sb.append(digits.padStart(NUMERIC_PAD, '0'))
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private const val NUMERIC_PAD = 6
}
