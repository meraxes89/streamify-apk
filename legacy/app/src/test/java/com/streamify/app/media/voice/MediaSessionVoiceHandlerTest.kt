package com.streamify.app.media.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaSessionVoiceHandlerTest (Phase 4 — Gap #56) — Assistant intent
 * matching:
 *  • "Play my Daylist on Streamify" and daily-mix variants
 *  • liked-songs shuffle variants
 *  • "<name> playlist" extraction with filler stripping
 *  • free-text search with shuffle flag
 *  • join-jam recognition incl. room codes
 *  • precedence + hostile inputs
 */
class MediaSessionVoiceHandlerTest {

    private fun resolve(query: String?) = MediaSessionVoiceHandler.resolve(query)

    // ───────────────────────────── daylist ───────────────────────────────

    @Test
    fun `play my daylist resolves to the daylist command`() {
        assertEquals(VoiceCommand.PlayDaylist, resolve("Play my Daylist on Streamify"))
    }

    @Test
    fun `daily mix variants resolve to the daylist command`() {
        assertEquals(VoiceCommand.PlayDaylist, resolve("play my daily mix"))
        assertEquals(VoiceCommand.PlayDaylist, resolve("daylist please"))
    }

    @Test
    fun `daylist beats playlist phrasing when both match`() {
        // "daylist playlist" — daylist precedence wins.
        assertEquals(VoiceCommand.PlayDaylist, resolve("play my daylist playlist"))
    }

    // ───────────────────────────── likes ─────────────────────────────────

    @Test
    fun `shuffle my likes resolves to liked shuffle`() {
        assertEquals(VoiceCommand.PlayLikedShuffle, resolve("shuffle my likes"))
        assertEquals(VoiceCommand.PlayLikedShuffle, resolve("play my favorites"))
        assertEquals(VoiceCommand.PlayLikedShuffle, resolve("play my liked songs"))
        assertEquals(VoiceCommand.PlayLikedShuffle, resolve("play my favourites"))
    }

    // ───────────────────────────── playlists ─────────────────────────────

    @Test
    fun `named playlist extraction strips filler words`() {
        val cmd = resolve("play my Focus Flow playlist")
        assertTrue(cmd is VoiceCommand.PlayPlaylist)
        assertEquals("focus flow", (cmd as VoiceCommand.PlayPlaylist).nameQuery)
    }

    @Test
    fun `playlist without a usable name falls through to search`() {
        val cmd = resolve("play the playlist")
        // No name survives stripping → treated as a plain search query.
        assertTrue(cmd is VoiceCommand.PlaySearch)
    }

    // ───────────────────────────── search ────────────────────────────────

    @Test
    fun `generic queries become searches with the shuffle flag`() {
        val cmd = resolve("play worship music on streamify")
        assertTrue(cmd is VoiceCommand.PlaySearch)
        assertEquals("worship music", (cmd as VoiceCommand.PlaySearch).query)
        assertTrue(!cmd.shuffle)

        val shuffled = resolve("shuffle lo-fi beats") as VoiceCommand.PlaySearch
        assertTrue(shuffled.shuffle)
        assertEquals("lo-fi beats", shuffled.query)
    }

    @Test
    fun `bare shuffle resolves as a search command`() {
        val cmd = resolve("shuffle")
        assertTrue(cmd is VoiceCommand.PlaySearch)
        assertEquals("", (cmd as VoiceCommand.PlaySearch).query)
    }

    // ───────────────────────────── jam ───────────────────────────────────

    @Test
    fun `join the jam recognizes the intent`() {
        val cmd = resolve("join the jam")
        assertTrue(cmd is VoiceCommand.JoinJam)
    }

    @Test
    fun `join jam extracts code-like room tokens`() {
        val cmd = resolve("join the jam AB12CD")
        assertTrue(cmd is VoiceCommand.JoinJam)
        assertEquals("ab12cd", (cmd as VoiceCommand.JoinJam).roomCode)
    }

    @Test
    fun `jam without join verb is a plain search`() {
        // "play jam music" — mentions jam but has no join intent.
        val cmd = resolve("play jam music")
        assertTrue(cmd is VoiceCommand.PlaySearch)
    }

    // ───────────────────────────── hostile ───────────────────────────────

    @Test
    fun `null blank and filler-only queries resolve to nothing`() {
        assertNull(resolve(null))
        assertNull(resolve(""))
        assertNull(resolve("   "))
    }
}
