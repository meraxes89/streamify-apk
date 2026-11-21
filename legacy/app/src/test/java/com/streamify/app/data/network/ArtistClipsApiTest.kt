package com.streamify.app.data.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ArtistClipsApi JVM unit suite (Phase 3 — vertical 30s Clips).
 *
 * Locks the four clip renderer signatures plus shelf-boost gating:
 *  • shortsLockupViewModel (modern Shorts) with entityId + overlay metadata
 *  • reelItemRenderer (legacy Shorts)
 *  • clipRenderer (curated watch-next Clips)
 *  • shortVideoRenderer ONLY with ≤45s badge AND a Clips shelf seen
 *  • dedup by clipId, duration badge decoding, hostile trees never throw.
 */
class ArtistClipsApiTest {

    // ─────────────────────────────────────────────────── fixture builders

    private fun shortsLockup(entityId: String, videoId: String, title: String, secondary: String): JSONObject {
        return JSONObject()
            .put("entityId", entityId)
            .put(
                "content",
                JSONObject()
                    .put(
                        "image",
                        JSONObject().put(
                            "sources",
                            JSONArray()
                                .put(JSONObject().put("url", "https://i.ytimg.com/vi/$videoId/hq720.jpg"))
                        )
                    )
                    .put(
                        "overlayMetadata",
                        JSONObject()
                            .put("primaryText", JSONObject().put("content", title))
                            .put("secondaryText", JSONObject().put("content", secondary))
                    )
                    .put(
                        "onTap",
                        JSONObject().put(
                            "watchEndpoint",
                            JSONObject().put("videoId", videoId)
                        )
                    )
            )
    }

    private fun reelItem(videoId: String, title: String): JSONObject =
        JSONObject()
            .put("videoId", videoId)
            .put("headline", JSONObject().put("simpleText", title))
            .put(
                "thumbnail",
                JSONObject().put(
                    "thumbnails",
                    JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/$videoId/default.jpg"))
                )
            )
            .put("viewCountText", JSONObject().put("simpleText", "1.2M views"))

    private fun clipRenderer(videoId: String, title: String, duration: String): JSONObject =
        JSONObject()
            .put("videoId", videoId)
            .put("title", JSONObject().put("simpleText", title))
            .put("durationText", duration)
            .put(
                "thumbnail",
                JSONObject().put(
                    "thumbnails",
                    JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/$videoId/maxres.jpg"))
                )
            )

    private fun browseTree(vararg renderers: Pair<String, JSONObject>): JSONObject {
        val contents = JSONArray()
        renderers.forEach { (key, value) -> contents.put(JSONObject().put(key, value)) }
        return JSONObject()
            .put(
                "contents",
                JSONObject().put(
                    "twoColumnBrowseResultsRenderer",
                    JSONObject().put(
                        "tabs",
                        JSONArray().put(
                            JSONObject().put(
                                "tabRenderer",
                                JSONObject().put(
                                    "content",
                                    JSONObject().put(
                                        "sectionListRenderer",
                                        JSONObject().put("contents", contents)
                                    )
                                )
                            )
                        )
                    )
                )
            )
    }

    // ───────────────────────────────────────────── shortsLockupViewModel

    @Test
    fun `shortsLockupViewModel parses with duration and views`() {
        val tree = browseTree(
            "shortsLockupViewModel" to shortsLockup(
                "clips entities 123",
                "clipVideoAAA",
                "Studio session teaser",
                "0:32 • 1.2M views"
            )
        )
        val clips = ClipsResponseParser.parseBrowse(tree, maxClips = 10)
        assertEquals(1, clips.size)
        val clip = clips.first()
        assertEquals("clipVideoAAA", clip.videoId)
        assertEquals("Studio session teaser", clip.title)
        assertEquals(32, clip.durationSec)
        assertTrue(clip.isVertical30s)
        assertEquals("1.2M views", clip.viewCountText)
    }

    @Test
    fun `thumbnail fallback recovers videoId when nav is missing`() {
        val lockup = shortsLockup("entity-x", "vid12345678", "T", "30s")
            .put("content", JSONObject().put("image", JSONObject().put(
                "sources", JSONArray().put(
                    JSONObject().put("url", "https://i.ytimg.com/vi/AAAAAAAAAAA/hq720.jpg")
                )
            ))) // onTap stripped
        val tree = browseTree("shortsLockupViewModel" to lockup)
        val clips = ClipsResponseParser.parseBrowse(tree, 10)
        assertEquals(1, clips.size)
        assertEquals("AAAAAAAAAAA", clips.first().videoId)
    }

    // ──────────────────────────────────────────────────── reelItemRenderer

    @Test
    fun `reelItemRenderer parses as 30s clip`() {
        val tree = browseTree("reelItemRenderer" to reelItem("reelVideoBB", "Live at the Rooftop"))
        val clips = ClipsResponseParser.parseBrowse(tree, 10)
        assertEquals(1, clips.size)
        assertEquals("reelVideoBB", clips.first().videoId)
        assertEquals("Live at the Rooftop", clips.first().title)
        assertEquals(30, clips.first().durationSec)
    }

    // ───────────────────────────────────────────────────── clipRenderer

    @Test
    fun `clipRenderer parses duration badge`() {
        val tree = browseTree("clipRenderer" to clipRenderer("clipCCC", "Best hook", "0:29"))
        val clips = ClipsResponseParser.parseBrowse(tree, 10)
        assertEquals(29, clips.first().durationSec)
    }

    // ───────────────────────────────── shortVideoRenderer shelf gating

    @Test
    fun `shortVideoRenderer requires clips shelf header`() {
        fun shortVideo(duration: String): JSONObject =
            JSONObject()
                .put("videoId", "shortDDD")
                .put("title", JSONObject().put("simpleText", "A short"))
                .put("lengthText", JSONObject().put("simpleText", duration))
                .put(
                    "thumbnail",
                    JSONObject().put("thumbnails", JSONArray().put(JSONObject().put("url", "https://x/vi/shortDDD/default.jpg")))
                )

        // 1) No Clips shelf header → gated out.
        val noShelf = browseTree("shortVideoRenderer" to shortVideo("0:40"))
        assertTrue(ClipsResponseParser.parseBrowse(noShelf, 10).isEmpty())

        // 2) With a Clips shelf header (title hint) → collected.
        val withShelf = browseTree("shortVideoRenderer" to shortVideo("0:40"))
            .put(
                "shelfTitle",
                JSONObject().put("title", "Clips from this artist")
            )
        assertEquals(1, ClipsResponseParser.parseBrowse(withShelf, 10).size)

        // 3) Long video even with shelf → excluded (not a 30s clip).
        val longVideo = browseTree("shortVideoRenderer" to shortVideo("4:12"))
            .put("shelfTitle", JSONObject().put("title", "Clips"))
        assertTrue(ClipsResponseParser.parseBrowse(longVideo, 10).isEmpty())
    }

    // ──────────────────────────────────────────── dedup + hostile inputs

    @Test
    fun `duplicate clipIds are deduped`() {
        val tree = browseTree(
            "reelItemRenderer" to reelItem("dupVideo", "First"),
            "clipRenderer" to clipRenderer("dupVideo", "Second", "0:30")
        )
        val clips = ClipsResponseParser.parseBrowse(tree, 10)
        assertEquals(1, clips.size)
    }

    @Test
    fun `empty and hostile trees return empty never throw`() {
        assertTrue(ClipsResponseParser.parseBrowse(JSONObject(), 10).isEmpty())
        assertTrue(ClipsResponseParser.parseBrowse(JSONObject().put("contents", 42), 10).isEmpty())
        val weird = JSONObject().put("shortsLockupViewModel", "not-an-object")
        assertTrue(ClipsResponseParser.parseBrowse(weird, 10).isEmpty())
    }

    @Test
    fun `maxClips bounds the harvest`() {
        val items = JSONArray()
        repeat(8) { i -> items.put(JSONObject().put("reelItemRenderer", reelItem("vid$i$i$i$i$i$i$i$i$i$i", "Clip $i"))) }
        val tree = JSONObject().put("contents", JSONObject().put("items", items))
        assertEquals(5, ClipsResponseParser.parseBrowse(tree, maxClips = 5).size)
    }

    // ─────────────────────────────────────────── duration badge decoding

    @Test
    fun `duration badge formats decode`() {
        assertEquals(32, ClipsResponseParser.parseDurationBadge("0:32"))
        assertEquals(195, ClipsResponseParser.parseDurationBadge("3:15"))
        assertEquals(30, ClipsResponseParser.parseDurationBadge("30s"))
        assertEquals(45, ClipsResponseParser.parseDurationBadge("45 sec"))
        assertNull(ClipsResponseParser.parseDurationBadge(""))
        assertNull(ClipsResponseParser.parseDurationBadge("tomorrow"))
    }

    // ────────────────────────────────────────── search resolution helpers

    @Test
    fun `firstArtistChannelId extracts UC channel`() {
        // YouTube channel ids are exactly 24 chars: "UC" + 22.
        val channelId = "UCabcdefghij1234567890AB"
        val tree = JSONObject()
            .put(
                "musicCardShelfRenderer",
                JSONObject()
                    .put(
                        "navigationEndpoint",
                        JSONObject()
                            .put(
                                "browseEndpoint",
                                JSONObject().put("browseId", channelId)
                            )
                    )
            )
        assertEquals(channelId, ClipsResponseParser.firstArtistChannelId(tree))
        assertNull(ClipsResponseParser.firstArtistChannelId(JSONObject()))
    }

    @Test
    fun `firstSongVideoId extracts renderer videoId`() {
        val tree = JSONObject()
            .put(
                "playlistPanelVideoRenderer",
                JSONObject().put("videoId", "songVideoEE")
            )
        assertEquals("songVideoEE", ClipsResponseParser.firstSongVideoId(tree))
    }
}
