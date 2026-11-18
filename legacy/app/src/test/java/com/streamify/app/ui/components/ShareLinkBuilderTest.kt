package com.streamify.app.ui.components

import com.streamify.app.data.models.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ShareLinkBuilder JVM unit suite (Phase 3 — QR share deep links).
 *
 * Locks the deep-link contract for tracks / albums / playlists / jams:
 * stable shapes, URL-safe slugs, and videoId preference for tracks.
 */
class ShareLinkBuilderTest {

    @Test
    fun `track link prefers the videoId when known`() {
        val track = Track(
            id = 1,
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            ytmVideoId = "dQw4w9WgXcQ"
        )
        assertEquals("https://open.streamify.app/track/dQw4w9WgXcQ", ShareLinkBuilder.trackLink(track))
    }

    @Test
    fun `track link falls back to a title-artist slug`() {
        val track = Track(id = 2, title = "Blue Monday", artist = "New Order")
        assertEquals(
            "https://open.streamify.app/track/blue-monday-new-order",
            ShareLinkBuilder.trackLink(track)
        )
    }

    @Test
    fun `album playlist and jam links are stable`() {
        assertEquals(
            "https://open.streamify.app/album/abbey-road",
            ShareLinkBuilder.albumLink("Abbey Road")
        )
        assertEquals(
            "https://open.streamify.app/playlist/7f2c9c0a-1234",
            ShareLinkBuilder.playlistLink("7f2c9c0a-1234")
        )
        assertEquals(
            "https://open.streamify.app/jam/jam-32-peers",
            ShareLinkBuilder.jamLink("JAM 32 Peers")
        )
    }

    @Test
    fun `slug normalizes punctuation and collapse`() {
        assertEquals("a-b-c", ShareLinkBuilder.slug("a  b\tc"))
        // 'é' IS a letter — it survives slug normalization.
        assertEquals("café-del-mar", ShareLinkBuilder.slug("Café Del Mar"))
        assertEquals("x", ShareLinkBuilder.slug("!!!"))
        assertEquals("x", ShareLinkBuilder.slug("   "))
    }

    // Note: QrBitmapFactory exercises android.graphics.Bitmap, which the
    // JVM unit shard stubs (isReturnDefaultValues=true) — its coverage
    // lives on the instrumented/emu shards, not here.
}
