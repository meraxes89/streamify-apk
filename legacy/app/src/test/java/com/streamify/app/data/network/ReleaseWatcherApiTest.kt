package com.streamify.app.data.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ReleaseWatcherApi JVM unit suite (Phase 3 — release watcher & pre-saves).
 *
 * Locks:
 *  • Countdown decoding — ISO-8601 durations (P1DT12H30M / PT10M / P14D /
 *    P2W), HUD ("3D 2H"), wordy ("in 5 days"), tomorrow/tonight, and the
 *    live guards ("Out now" / "released" never decode as durations).
 *  • /browse parsing — musicTwoRowItemRenderer upcoming + live releases.
 *  • PreSaveStore — add / remove / isPreSaved / JSON persistence round-trip.
 */
class ReleaseWatcherApiTest {

    // ────────────────────────────────────────────────── countdown decoding

    @Test
    fun `iso durations decode to millis`() {
        assertEquals(131_400_000L, ReleaseWatcherApi.parseCountdownMillis("P1DT12H30M")) // 1d 12h 30m
        assertEquals(600_000L, ReleaseWatcherApi.parseCountdownMillis("PT10M"))
        assertEquals(14L * 24 * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("P14D"))
        assertEquals(14L * 24 * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("P2W"))
        assertEquals(90_000L, ReleaseWatcherApi.parseCountdownMillis("PT1M30S"))
    }

    @Test
    fun `hud and wordy countdowns decode`() {
        assertEquals(3L * 24 * 3600 * 1000 + 2L * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("3D 2H"))
        assertEquals(48L * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("48H"))
        assertEquals(5L * 24 * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("in 5 days"))
        assertEquals(2L * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("2 hours left"))
        assertEquals(30L * 60 * 1000, ReleaseWatcherApi.parseCountdownMillis("30 minutes"))
    }

    @Test
    fun `tomorrow and tonight map to fixed horizons`() {
        assertEquals(24L * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("Tomorrow"))
        assertEquals(12L * 3600 * 1000, ReleaseWatcherApi.parseCountdownMillis("Tonight"))
    }

    @Test
    fun `live text never decodes as a duration`() {
        assertNull(ReleaseWatcherApi.parseCountdownMillis("Out now"))
        assertNull(ReleaseWatcherApi.parseCountdownMillis("New album released"))
        assertNull(ReleaseWatcherApi.parseCountdownMillis(""))
        assertNull(ReleaseWatcherApi.parseCountdownMillis("Coming soon"))
    }

    @Test
    fun `countdown label formatting is spotify style`() {
        assertEquals("3d 02h", ReleaseWatcherApi.formatCountdown(3L * 24 * 3600 * 1000 + 2L * 3600 * 1000))
        assertEquals("02h 14m", ReleaseWatcherApi.formatCountdown(2L * 3600 * 1000 + 14 * 60 * 1000))
        assertEquals("14m 09s", ReleaseWatcherApi.formatCountdown(14 * 60 * 1000 + 9_000))
        assertEquals("09s", ReleaseWatcherApi.formatCountdown(9_000))
        assertEquals("Out now", ReleaseWatcherApi.formatCountdown(0))
    }

    // ─────────────────────────────────────────────────── /browse parsing

    private fun twoRowItem(title: String, subtitleLast: String, browseId: String = "MPREb_$title"): JSONObject {
        return JSONObject()
            .put(
                "title",
                JSONObject().put("runs", JSONArray().put(JSONObject().put("text", title)))
            )
            .put(
                "subtitle",
                JSONObject().put(
                    "runs",
                    JSONArray()
                        .put(JSONObject().put("text", "Album"))
                        .put(JSONObject().put("text", " • "))
                        .put(JSONObject().put("text", subtitleLast))
                )
            )
            .put(
                "navigationEndpoint",
                JSONObject().put("browseEndpoint", JSONObject().put("browseId", browseId))
            )
    }

    private fun browseTree(vararg items: JSONObject): JSONObject {
        val contents = JSONArray()
        items.forEach { contents.put(JSONObject().put("musicTwoRowItemRenderer", it)) }
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

    @Test
    fun `upcoming release with iso countdown is parsed with eta`() {
        val before = System.currentTimeMillis()
        val tree = browseTree(twoRowItem("Midnight Frequencies", "P1DT12H"))
        val releases = ReleaseResponseParser.parseBrowse(tree, "Nova Ray")
        val after = System.currentTimeMillis()
        assertEquals(1, releases.size)
        val release = releases.first()
        assertEquals("Midnight Frequencies", release.title)
        assertEquals("Nova Ray", release.artistName)
        assertEquals("Album", release.releaseType)
        // Countdowns anchor to the epoch at parse time: now + 36h.
        val eta = release.expectedAtMs
        org.junit.Assert.assertNotNull(eta)
        assertTrue(eta!! in (before + 129_600_000L)..(after + 129_600_000L))
        assertTrue(!release.isLive)
        assertEquals(129_600_000L, release.millisRemaining(eta - 129_600_000L))
    }

    @Test
    fun `live release parses without eta`() {
        val tree = browseTree(twoRowItem("Golden Hour", "Out now"))
        val releases = ReleaseResponseParser.parseBrowse(tree, "Solene")
        assertEquals(1, releases.size)
        assertNull(releases.first().expectedAtMs)
        assertTrue(releases.first().isLive)
    }

    @Test
    fun `release rows without any release signal are skipped`() {
        // "Album • 2019" is a catalog row — no countdown, no live hint.
        val tree = browseTree(twoRowItem("Old Catalog", "2019"))
        assertTrue(ReleaseResponseParser.parseBrowse(tree, "Artist").isEmpty())
    }

    @Test
    fun `hostile browse trees return empty never throw`() {
        assertTrue(ReleaseResponseParser.parseBrowse(JSONObject(), "A").isEmpty())
        assertTrue(
            ReleaseResponseParser.parseBrowse(JSONObject().put("musicTwoRowItemRenderer", 7), "A").isEmpty()
        )
    }

    @Test
    fun `next upcoming picks the soonest eta`() {
        val tree = browseTree(
            twoRowItem("Later", "P10D"),
            twoRowItem("Sooner", "P1D")
        )
        val watch = ReleaseWatcherApi.ReleaseWatch(
            artistName = "A",
            releases = ReleaseResponseParser.parseBrowse(tree, "A")
        )
        assertEquals("Sooner", watch.nextUpcoming?.title)
    }

    // ──────────────────────────────────────────────────── PreSaveStore

    @Test
    fun `pre-save store add remove and persistence round trip`() {
        val dir = Files.createTempDirectory("presave_test").toFile()
        val file = File(dir, "presaves.json")
        try {
            PreSaveStore.initWith(file)
            PreSaveStore.addPreSave(
                PreSaveStore.PreSave(
                    releaseId = "rel-1",
                    artistName = "Nova Ray",
                    title = "Midnight Frequencies",
                    expectedAtMs = 123L
                )
            )
            assertTrue(PreSaveStore.isPreSaved("rel-1"))
            assertEquals(1, PreSaveStore.loadAll().size)

            // Round-trip through a fresh store instance on the same file.
            PreSaveStore.initWith(file)
            assertTrue(PreSaveStore.isPreSaved("rel-1"))
            val loaded = PreSaveStore.loadAll().first()
            assertEquals("Nova Ray", loaded.artistName)
            assertEquals("Midnight Frequencies", loaded.title)
            assertEquals(123L, loaded.expectedAtMs)

            PreSaveStore.removePreSave("rel-1")
            assertTrue(!PreSaveStore.isPreSaved("rel-1"))
            assertTrue(PreSaveStore.loadAll().isEmpty())
        } finally {
            file.delete(); dir.delete()
        }
    }

    @Test
    fun `pre-save store with no backing file is empty and safe`() {
        PreSaveStore.initWith(null)
        assertTrue(PreSaveStore.loadAll().isEmpty())
        assertTrue(!PreSaveStore.isPreSaved("anything"))
    }
}
