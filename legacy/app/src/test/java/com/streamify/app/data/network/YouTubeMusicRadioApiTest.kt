package com.streamify.app.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTubeMusicRadioApi JVM unit suite (Phase 2 — Gap #15).
 *
 * Locks the three mandated resilience pillars:
 *  1. Defensive recursive JSON traversal — hostile renderer trees yield
 *     partial results, never JSONException.
 *  2. Query→radio fallback chain — empty radio falls through to the
 *     SONGS-search top result and re-invokes the radio.
 *  3. Stale-while-revalidate cache — fresh hits return inline, stale
 *     entries serve immediately while refreshing in the background.
 */
class YouTubeMusicRadioApiTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ─────────────────────────────────────────────── realistic /next shapes

    /** Classic WEB twoColumnWatchNextResults playlist panel (radio lane). */
    private fun webRadioNext(videoIds: List<String>, continuation: String?): JSONObject {
        val contents = JSONArray()
        videoIds.forEachIndexed { i, vid ->
            contents.put(
                JSONObject().put(
                    "playlistPanelVideoRenderer",
                    JSONObject()
                        .put("videoId", vid)
                        .put(
                            "title",
                            JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "Song ${i + 1}")))
                        )
                        .put(
                            "longBylineText",
                            JSONObject().put(
                                "runs",
                                JSONArray().put(JSONObject().put("text", "Artist ${i + 1} - Topic"))
                            )
                        )
                        .put(
                            "lengthText",
                            JSONObject().put("simpleText", "3:0${(i % 9) + 1}")
                        )
                        .put(
                            "thumbnail",
                            JSONObject().put(
                                "thumbnails",
                                JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/$vid/hqdefault.jpg"))
                            )
                        )
                )
            )
        }
        val panel = JSONObject().put("contents", contents)
        if (continuation != null) {
            panel.put(
                "continuations",
                JSONArray().put(
                    JSONObject().put(
                        "nextContinuationData",
                        JSONObject().put("continuation", continuation)
                    )
                )
            )
        }
        return JSONObject().put(
            "contents",
            JSONObject().put(
                "twoColumnWatchNextResults",
                JSONObject().put(
                    "playlist",
                    JSONObject().put("playlist", JSONObject().put("playlistPanelRenderer", panel))
                )
            )
        )
    }

    /** Music-app style: shelves + musicResponsiveListItemRenderer rows. */
    private fun musicShelfNext(): JSONObject {
        fun flexColumn(text: String) = JSONObject().put(
            "musicResponsiveListItemFlexColumnRenderer",
            JSONObject().put("text", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", text))))
        )

        val similarItems = JSONArray()
        for (i in 1..3) {
            similarItems.put(
                JSONObject().put(
                    "musicResponsiveListItemRenderer",
                    JSONObject()
                        .put("playlistItemData", JSONObject().put("videoId", "SIMILAR$i".padEnd(11, 'x')))
                        .put(
                            "flexColumns",
                            JSONArray()
                                .put(flexColumn("Deep Cut $i"))
                                .put(flexColumn("Seed Artist"))
                        )
                        .put(
                            "fixedColumns",
                            JSONArray().put(
                                JSONObject().put(
                                    "musicResponsiveListItemFixedColumnRenderer",
                                    JSONObject().put(
                                        "text",
                                        JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "4:12")))
                                    )
                                )
                            )
                        )
                )
            )
        }

        return JSONObject().put(
            "contents",
            JSONObject().put(
                "singleColumnMusicWatchNextResultsRenderer",
                JSONObject().put(
                    "sectionListRenderer",
                    JSONObject().put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "musicCarouselShelfRenderer",
                                JSONObject()
                                    .put(
                                        "header",
                                        JSONObject().put(
                                            "musicCarouselShelfBasicHeaderRenderer",
                                            JSONObject().put(
                                                "title",
                                                JSONObject().put(
                                                    "runs",
                                                    JSONArray().put(JSONObject().put("text", "Similar artists"))
                                                )
                                            )
                                        )
                                    )
                                    .put("contents", similarItems)
                            )
                        )
                    )
                )
            )
        )
    }

    // ─────────────────────────────────────────────────────── parser tests

    @Test
    fun `parses web radio lane tracks and continuation`() {
        val page = RadioResponseParser.parse(
            webRadioNext(listOf("dQw4w9WgXcQ", "AAAAAAAAAAA"), "cont_token_1"),
            maxTracks = 50
        )
        assertEquals(2, page.tracks.size)
        assertEquals("dQw4w9WgXcQ", page.tracks[0].videoId)
        assertEquals("Song 1", page.tracks[0].title)
        assertEquals("Artist 1", page.tracks[0].artist) // "- Topic" cleaned
        assertEquals(181, page.tracks[0].durationSec)
        assertEquals("cont_token_1", page.continuationToken)
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.RADIO_LANE, page.tracks[0].category)
    }

    @Test
    fun `parses music shelves with category heuristic`() {
        val page = RadioResponseParser.parse(musicShelfNext(), maxTracks = 50)
        assertEquals(3, page.tracks.size)
        val shelf = page.shelves.first()
        assertEquals("Similar artists", shelf.title)
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.SIMILAR_ARTISTS, shelf.category)
        assertEquals(3, shelf.tracks.size)
        // flex column 1 → artist, fixed column → duration
        assertEquals("Seed Artist", shelf.tracks[0].artist)
        assertEquals(252, shelf.tracks[0].durationSec)
    }

    @Test
    fun `shelf category keyword heuristic buckets live titles`() {
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.SIMILAR_ARTISTS, YouTubeMusicRadioApi.ShelfCategory.categorize("Fans like these too"))
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.DISCOVER_DEEP_CUTS, YouTubeMusicRadioApi.ShelfCategory.categorize("Discover new releases"))
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.MIXED_FOR_YOU, YouTubeMusicRadioApi.ShelfCategory.categorize("Recommended for you"))
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.OTHER, YouTubeMusicRadioApi.ShelfCategory.categorize("Totally unlabelled"))
    }

    @Test
    fun `duplicate videoIds are deduped across renderer kinds`() {
        val root = webRadioNext(listOf("dQw4w9WgXcQ", "dQw4w9WgXcQ", "BBBBBBBBBBB"), null)
        val page = RadioResponseParser.parse(root, maxTracks = 50)
        assertEquals(2, page.tracks.size)
        assertTrue(page.tracks.map { it.videoId }.distinct().size == page.tracks.size)
    }

    @Test
    fun `hostile malformed trees never throw and yield partial tracks`() {
        val hostile = JSONObject()
            .put("contents", JSONObject()
                .put("junk", JSONArray()
                    .put(JSONObject().put("videoRenderer", JSONObject().put("videoId", "CCC")))
                    .put(JSONObject().put("videoRenderer", JSONObject().put("videoId", 12345)))
                    .put(JSONObject().put("playlistPanelVideoRenderer", "string-not-object"))
                    .put(JSONArray().put(JSONObject.NULL).put(42.0))
                    .put(JSONObject.NULL))
            )
            .put("continuationItemRenderer", JSONObject().put("continuationCommand", JSONObject().put("token", "tk")))
        val page = RadioResponseParser.parse(hostile, maxTracks = 10)
        // Partial extraction without exception; blank-title renderer dropped.
        assertTrue(page.tracks.size <= 1)
        // continuationCommand tokens are harvested as shelf continuations.
        assertEquals("tk", page.continuationToken)
    }

    @Test
    fun `deeply nested hostile payload hits budget without stack overflow`() {
        var node: Any = JSONObject().put(
            "playlistPanelVideoRenderer",
            JSONObject().put("videoId", "DDDDDDDDDDD").put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "x"))))
        )
        // 200-level nesting stays inside the depth budget → track found.
        repeat(200) { node = JSONObject().put("wrap", node) }
        val page = RadioResponseParser.parse(node as JSONObject, maxTracks = 5)
        assertEquals(1, page.tracks.size)
        assertEquals("DDDDDDDDDDD", page.tracks[0].videoId)
    }

    @Test
    fun `nesting beyond the depth cap is dropped without stack overflow`() {
        var node: Any = JSONObject().put(
            "playlistPanelVideoRenderer",
            JSONObject().put("videoId", "EEEEEEEEEEE").put("title", JSONObject().put("runs", JSONArray().put(JSONObject().put("text", "x"))))
        )
        // 600 levels far exceeds MAX_DEPTH=256 → nothing extracted, no crash.
        repeat(600) { node = JSONObject().put("wrap", node) }
        val page = RadioResponseParser.parse(node as JSONObject, maxTracks = 5)
        assertTrue(page.tracks.isEmpty())
    }

    @Test
    fun `clock parser handles all duration shapes`() {
        assertEquals(225, RadioResponseParser.parseClock("3:45"))
        assertEquals(3723, RadioResponseParser.parseClock("1:02:03"))
        assertEquals(0, RadioResponseParser.parseClock("garbage"))
        assertEquals(0, RadioResponseParser.parseClock("99:99"))
        assertEquals(0, RadioResponseParser.parseClock(""))
    }

    @Test
    fun `short or malformed videoIds are rejected`() {
        val root = webRadioNext(listOf("short", ""), null)
        val page = RadioResponseParser.parse(root, maxTracks = 10)
        assertTrue(page.tracks.isEmpty())
    }

    // ──────────────────────────────────── query→radio fallback chain (Gap #3)

    @Test
    fun `query falls back to songs-search top result when radio is empty`() = runBlocking {
        val radioCalls = mutableListOf<String>()
        val page = YouTubeMusicRadioApi.resolveRadioForQuery(
            query = "obscure bedroom artist",
            maxTracks = 20,
            search = { q ->
                assertEquals("obscure bedroom artist", q)
                listOf("FIRSTRESULT1", "SECONDRESULT")
            },
            radio = { vid ->
                radioCalls.add(vid)
                if (vid == "FIRSTRESULT1") {
                    YouTubeMusicRadioApi.RadioPage(
                        tracks = listOf(
                            YouTubeMusicRadioApi.RadioTrack(videoId = "FIRSTRESULT1", title = "Hit", artist = "A")
                        )
                    )
                } else {
                    YouTubeMusicRadioApi.RadioPage()
                }
            }
        )
        // First the direct attempt (query is not an 11-char id → blank),
        // then the top search candidate.
        assertTrue(radioCalls.contains("FIRSTRESULT1"))
        assertEquals(1, page.tracks.size)
    }

    @Test
    fun `query fallback advances to second candidate when first is empty`() = runBlocking {
        val page = YouTubeMusicRadioApi.resolveRadioForQuery(
            query = "q",
            maxTracks = 20,
            search = { listOf("AAAAAAAAAAA", "BBBBBBBBBBB") },
            radio = { vid ->
                if (vid == "BBBBBBBBBBB") {
                    YouTubeMusicRadioApi.RadioPage(
                        tracks = listOf(YouTubeMusicRadioApi.RadioTrack(videoId = vid, title = "t", artist = "a"))
                    )
                } else {
                    YouTubeMusicRadioApi.RadioPage()
                }
            }
        )
        assertEquals("BBBBBBBBBBB", page.tracks.first().videoId)
    }

    @Test
    fun `total fallback failure yields empty page not exception`() = runBlocking {
        val page = YouTubeMusicRadioApi.resolveRadioForQuery(
            query = "q",
            maxTracks = 20,
            search = { throw java.io.IOException("offline") },
            radio = { YouTubeMusicRadioApi.RadioPage() }
        )
        assertTrue(page.tracks.isEmpty())
    }

    // ─────────────────────────────────────────── SWR cache resilience (Gap #4)

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test
    fun `swr fresh hit avoids network`() = runBlocking {
        var now = 0L
        val fetches = AtomicInteger(0)
        val cache = SwrCache(dir = tmp.newFolder(), clock = { now }, scope = testScope())
        cache.write("k", "v1")
        val out = cache.swr("k", ttlMs = 1000, fetch = { fetches.incrementAndGet(); "v2" })
        assertEquals("v1", out)
        assertEquals(0, fetches.get())
    }

    @Test
    fun `swr stale entry serves immediately and refreshes in background`() = runBlocking {
        var now = 0L
        val fetches = AtomicInteger(0)
        val cache = SwrCache(dir = tmp.newFolder(), clock = { now }, scope = testScope())
        cache.write("k", "stale")
        now = 5000 // entry is 5000ms old, TTL 1000 → stale
        var refreshedTo: String? = null
        val out = cache.swr(
            "k", ttlMs = 1000,
            fetch = { fetches.incrementAndGet(); "fresh" },
            onRefresh = { refreshedTo = it }
        )
        assertEquals("stale", out)          // served instantly
        assertEquals(1, fetches.get())      // background revalidation ran
        assertEquals("fresh", refreshedTo)  // caller notified
        assertEquals("fresh", cache.read("k")?.value) // write-through
    }

    @Test
    fun `swr cold cache fetches synchronously and persists`() = runBlocking {
        val dir: File = tmp.newFolder()
        var now = 42L
        val cache = SwrCache(dir = dir, clock = { now }, scope = testScope())
        val out = cache.swr("k", ttlMs = 1000, fetch = { "net" })
        assertEquals("net", out)
        // Persisted: a brand-new instance over the same dir reads it back.
        val reborn = SwrCache(dir = dir, clock = { now }, scope = testScope())
        assertEquals("net", reborn.read("k")?.value)
        assertEquals(42L, reborn.read("k")?.storedAtMs)
    }

    @Test
    fun `swr network failure on cold cache returns null without throwing`() = runBlocking {
        val cache = SwrCache(dir = tmp.newFolder(), clock = { 0L }, scope = testScope())
        val out = cache.swr("k", ttlMs = 1000, fetch = { throw java.io.IOException("offline") })
        assertNull(out)
    }

    @Test
    fun `swr stale entry survives failed revalidation`() = runBlocking {
        var now = 0L
        val cache = SwrCache(dir = tmp.newFolder(), clock = { now }, scope = testScope())
        cache.write("k", "keepme")
        now = 10_000
        val out = cache.swr("k", ttlMs = 1000, fetch = { throw java.io.IOException("offline") })
        assertEquals("keepme", out)
        assertEquals("keepme", cache.read("k")?.value)
    }

    @Test
    fun `swr corrupt disk entry is treated as a miss`() = runBlocking {
        val dir: File = tmp.newFolder()
        val cache = SwrCache(dir = dir, clock = { 0L }, scope = testScope())
        cache.write("k", "good")
        // Corrupt the on-disk payload behind the cache's back.
        dir.listFiles()!!.first().writeText("}{ not json")
        val reborn = SwrCache(dir = dir, clock = { 0L }, scope = testScope())
        assertNull(reborn.read("k"))
    }

    @Test
    fun `fleet covers three distinct InnerTube clients`() {
        val fleet = YouTubeMusicRadioApi.clientFleet()
        assertEquals(3, fleet.size)
        assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC", "WEB"), fleet.map { it.label })
        assertTrue(fleet.map { it.endpointUrl }.distinct().size >= 2)
        assertTrue(fleet.all { it.clientVersion.isNotBlank() })
        assertTrue(fleet.all { it.clientHeaderId.isNotBlank() })
    }

    @Test
    fun `canonical shelf grouping preserves category mapping`() {
        // Round-trip through groupShelves used by the cache decoder path.
        val shelves = YouTubeMusicRadioApi.groupShelves(
            listOf(
                YouTubeMusicRadioApi.RadioTrack(
                    videoId = "dQw4w9WgXcQ", title = "T", artist = "A",
                    shelfTitle = "Mixed for you"
                )
            )
        )
        assertEquals(1, shelves.size)
        assertEquals(YouTubeMusicRadioApi.ShelfCategory.MIXED_FOR_YOU, shelves.first().category)
        assertNotNull(shelves)
    }
}
