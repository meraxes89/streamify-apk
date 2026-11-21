package com.streamify.app.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WearInputHandlerTest (Phase 4 — Gap #55) — wrist command routing:
 *  • every command round-trips through the binary codec
 *  • decoded commands land on the right sink method
 *  • hostile frames (truncated payloads, unknown opcodes, oversized
 *    strings) never reach the sink
 *  • volume steps clamp to the wrist-safe bound
 */
class WearInputHandlerTest {

    private lateinit var sink: RecordingSink
    private lateinit var handler: WearInputHandler

    @Before
    fun setUp() {
        sink = RecordingSink()
        handler = WearInputHandler(sink)
    }

    private fun roundTrip(command: WearCommand): WearCommand? =
        WearCommandCodec.decode(WearCommandCodec.encode(command))

    @Test
    fun `transport commands round trip`() {
        assertEquals(WearCommand.PlayPause, roundTrip(WearCommand.PlayPause))
        assertEquals(WearCommand.SkipNext, roundTrip(WearCommand.SkipNext))
        assertEquals(WearCommand.SkipPrevious, roundTrip(WearCommand.SkipPrevious))
        assertEquals(WearCommand.LaunchQuickPlaylist, roundTrip(WearCommand.LaunchQuickPlaylist))
        assertEquals(WearCommand.JamUpvote(4242), roundTrip(WearCommand.JamUpvote(4242)))
        assertEquals(WearCommand.QueueAdd("dQw4w9WgXcQ"), roundTrip(WearCommand.QueueAdd("dQw4w9WgXcQ")))
        assertEquals(WearCommand.VolumeStep(-0.05f), roundTrip(WearCommand.VolumeStep(-0.05f)))
    }

    @Test
    fun `decoded play pause reaches the sink`() {
        assertTrue(handler.handle(WearCommandCodec.encode(WearCommand.PlayPause)))
        assertEquals(1, sink.playPauseCalls)
    }

    @Test
    fun `decoded skip commands reach the sink`() {
        handler.handle(WearCommandCodec.encode(WearCommand.SkipNext))
        handler.handle(WearCommandCodec.encode(WearCommand.SkipPrevious))
        assertEquals(1, sink.skipNextCalls)
        assertEquals(1, sink.skipPreviousCalls)
    }

    @Test
    fun `jam upvote carries the track id`() {
        assertTrue(handler.handle(WearCommandCodec.encode(WearCommand.JamUpvote(77))))
        assertEquals(77, sink.lastUpvoteTrackId)
        assertTrue(sink.lastUpvoteAccepted)
    }

    @Test
    fun `queue add carries the video id`() {
        assertTrue(handler.handle(WearCommandCodec.encode(WearCommand.QueueAdd("abc123"))))
        assertEquals("abc123", sink.lastQueueAddVideoId)
    }

    @Test
    fun `volume steps clamp to the wrist bound`() {
        val decoded = WearCommandCodec.decode(
            WearCommandCodec.encode(WearCommand.VolumeStep(2.0f))
        ) as WearCommand.VolumeStep
        assertEquals(WearCommandCodec.MAX_VOLUME_STEP, decoded.delta)

        val negative = WearCommandCodec.decode(
            WearCommandCodec.encode(WearCommand.VolumeStep(-5.0f))
        ) as WearCommand.VolumeStep
        assertEquals(-WearCommandCodec.MAX_VOLUME_STEP, negative.delta)
    }

    @Test
    fun `empty frames are rejected`() {
        assertFalse(handler.handle(ByteArray(0)))
        assertNull(WearCommandCodec.decode(ByteArray(0)))
    }

    @Test
    fun `unknown opcodes are rejected`() {
        val frame = byteArrayOf(99, 1, 2, 3)
        assertNull(WearCommandCodec.decode(frame))
        assertFalse(handler.handle(frame))
    }

    @Test
    fun `truncated volume payload is rejected`() {
        assertNull(WearCommandCodec.decode(byteArrayOf(4, 0x3F)))
    }

    @Test
    fun `truncated jam upvote payload is rejected`() {
        assertNull(WearCommandCodec.decode(byteArrayOf(5, 0x00)))
    }

    @Test
    fun `queue add length mismatch is rejected`() {
        // Claim 128-byte string, provide none.
        val frame = byteArrayOf(6, 0, 0, 0, 127) + ByteArray(3)
        assertNull(WearCommandCodec.decode(frame))
    }

    @Test
    fun `sink rejection propagates as unhandled`() {
        sink.upvoteResult = false
        assertFalse(handler.handle(WearCommandCodec.encode(WearCommand.JamUpvote(1))))
    }
}

private class RecordingSink : WearActionSink {
    var playPauseCalls = 0
    var skipNextCalls = 0
    var skipPreviousCalls = 0
    var lastUpvoteTrackId = -1
    var lastUpvoteAccepted = false
    var lastQueueAddVideoId: String? = null
    var upvoteResult = true

    override fun playPause() {
        playPauseCalls++
    }

    override fun skipNext() {
        skipNextCalls++
    }

    override fun skipPrevious() {
        skipPreviousCalls++
    }

    override fun volumeStep(delta: Float): Boolean = delta != 0f

    override fun jamUpvote(trackId: Int): Boolean {
        lastUpvoteTrackId = trackId
        lastUpvoteAccepted = upvoteResult
        return upvoteResult
    }

    override fun queueAdd(videoId: String): Boolean {
        lastQueueAddVideoId = videoId
        return true
    }

    override fun launchQuickPlaylist(): Boolean = true
}

/**
 * WearNowPlaying state sanity — the wrist payload only carries what a
 * watch complication renders.
 */
class WearModelsTest {

    @Test
    fun `default state has no track`() {
        assertFalse(WearNowPlayingState().hasTrack)
    }

    @Test
    fun `jam rows carry vote tallies`() {
        val state = WearNowPlayingState(
            trackTitle = "Runaway",
            jamActive = true,
            jamQueueTop = listOf(
                WearQueueEntry(trackId = 1, title = "A", artist = "X", votes = 3),
                WearQueueEntry(trackId = 2, title = "B", artist = "Y", votes = 0)
            )
        )
        assertTrue(state.hasTrack)
        assertEquals(listOf(3, 0), state.jamQueueTop.map { it.votes })
    }

    @Test
    fun `paths are namespaced under streamify`() {
        assertTrue(WearPaths.NOW_PLAYING.startsWith("/streamify/"))
        assertTrue(WearPaths.COMMANDS.startsWith("/streamify/"))
        assertTrue(WearPaths.RUN_SYNC.startsWith("/streamify/"))
    }
}
