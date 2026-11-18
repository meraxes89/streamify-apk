package com.streamify.app.data.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CanvasScraperApi JVM unit suite (Phase 3 — Canvas 8s loops).
 *
 * Locks the scraper resilience pillars against realistic InnerTube shapes:
 *  1. /player auxiliaryAssets fast path (CANVAS-typed asset wins).
 *  2. Defensive recursive traversal — canvasMediaRenderer at rotating
 *     unknown paths is still harvested.
 *  3. Hostile / malformed trees yield null loops, never exceptions.
 *  4. Search-tree videoId extraction for the query fallback chain.
 */
class CanvasScraperApiTest {

    private val videoId = "dQw4w9WgXcQ"

    // ─────────────────────────────────────────────────── fixture builders

    /** Canonical /player auxiliaryAssets CANVAS payload. */
    private fun playerWithAuxCanvas(url: String = "https://rr1---sn.example.googlevideo.com/canvas/loop.mp4"): JSONObject {
        return JSONObject()
            .put(
                "auxiliaryAssets",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "auxiliaryAssetRenderer",
                            JSONObject()
                                .put("url", url)
                                .put("type", "CANVAS")
                                .put("durationMs", 8_000)
                        )
                    )
            )
    }

    /** A canvasMediaRenderer buried at an arbitrary rotating path. */
    private fun treeWithBuriedCanvas(url: String): JSONObject {
        return JSONObject()
            .put(
                "playerOverlays",
                JSONObject().put(
                    "decoratedPlayerBarRenderer",
                    JSONObject().put(
                        "decoratedPlayerBarRenderer",
                        JSONObject().put(
                            "playerBar",
                            JSONObject().put(
                                "multiMarkersPlayerBarRenderer",
                                JSONObject().put(
                                    "canvasMediaRenderer",
                                    JSONObject()
                                        .put("loopUrl", url)
                                        .put("mimeType", "video/webm")
                                        .put("durationMs", 9_500)
                                        .put("width", 720)
                                        .put("height", 1280)
                                )
                            )
                        )
                    )
                )
            )
    }

    /** SONGS search response with a playlistPanelVideoRenderer top result. */
    private fun searchWithSong(vid: String): JSONObject =
        JSONObject()
            .put(
                "contents",
                JSONObject().put(
                    "sectionListRenderer",
                    JSONObject().put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "musicShelfRenderer",
                                JSONObject().put(
                                    "contents",
                                    JSONArray().put(
                                        JSONObject().put(
                                            "musicResponsiveListItemRenderer",
                                            JSONObject().put("videoId", vid)
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )

    // ─────────────────────────────────────────────────────── /player path

    @Test
    fun `auxiliaryAssets canvas fast path is parsed`() {
        val root = playerWithAuxCanvas()
        val loop = CanvasResponseParser.parsePlayer(root, videoId)
        assertNotNull(loop)
        assertEquals(videoId, loop!!.videoId)
        assertEquals("https://rr1---sn.example.googlevideo.com/canvas/loop.mp4", loop.loopUrl)
        assertEquals(8_000, loop.durationMs)
        assertTrue(loop.mimeType.startsWith("video/"))
    }

    @Test
    fun `non-canvas auxiliary assets are skipped`() {
        val root = JSONObject()
            .put(
                "auxiliaryAssets",
                JSONArray()
                    .put(
                        JSONObject().put(
                            "auxiliaryAssetRenderer",
                            JSONObject()
                                .put("url", "https://example.com/not-a-video.bin")
                                .put("type", "CAPTION")
                        )
                    )
            )
        val loop = CanvasResponseParser.parsePlayer(root, videoId)
        assertNull(loop)
    }

    @Test
    fun `buried canvasMediaRenderer at unknown path is still found`() {
        val root = treeWithBuriedCanvas("https://rr2.example.googlevideo.com/loopy.webm")
        val loop = CanvasResponseParser.parseNext(root, videoId)
        assertNotNull(loop)
        assertEquals("https://rr2.example.googlevideo.com/loopy.webm", loop!!.loopUrl)
        assertEquals("video/webm", loop.mimeType)
        assertEquals(9_500, loop.durationMs)
        assertTrue(loop.isVertical)
    }

    @Test
    fun `theme color is decoded into palette argb`() {
        val root = JSONObject()
            .put(
                "canvasMediaRenderer",
                JSONObject()
                    .put("loopUrl", "https://x.googlevideo.com/c.mp4")
                    .put("themeColor", "#1ED760")
            )
        val loop = CanvasResponseParser.parsePlayer(root, videoId)
        assertNotNull(loop)
        assertEquals(0xFF1ED760L, loop!!.paletteColor)
    }

    // ─────────────────────────────────────────────────── hostile payloads

    @Test
    fun `empty and malformed roots return null never throw`() {
        assertNull(CanvasResponseParser.parsePlayer(JSONObject(), videoId))
        assertNull(CanvasResponseParser.parsePlayer(JSONObject().put("auxiliaryAssets", "not-an-array"), videoId))
        assertNull(CanvasResponseParser.parseNext(JSONObject().put("canvas", 42), videoId))
    }

    @Test
    fun `canvas url without video signature is rejected`() {
        val root = JSONObject()
            .put("canvasMediaRenderer", JSONObject().put("loopUrl", "https://example.com/page.html"))
        assertNull(CanvasResponseParser.parsePlayer(root, videoId))
    }

    // ───────────────────────────────────────────── search fallback chain

    @Test
    fun `firstSongVideoId extracts the first renderer videoId`() {
        assertEquals("aaaaaaaaaaa", CanvasResponseParser.firstSongVideoId(searchWithSong("aaaaaaaaaaa")))
    }

    @Test
    fun `firstSongVideoId ignores non-renderer videoIds`() {
        // videoId directly under a non-renderer parent must NOT count.
        val root = JSONObject()
            .put("videoId", "zzzzzzzzzzz")
            .put("shelf", searchWithSong("bbbbbbbbbbb"))
        assertEquals("bbbbbbbbbbb", CanvasResponseParser.firstSongVideoId(root))
    }

    @Test
    fun `firstSongVideoId on empty tree is null`() {
        assertNull(CanvasResponseParser.firstSongVideoId(JSONObject()))
    }
}
