package com.streamify.app.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ConnectCommandCodecTest (Phase 4) — wire round-trips for every command
 * shape plus hostile payloads (the LAN transport must never crash on a
 * malformed frame).
 */
class ConnectCommandCodecTest {

    private fun roundTrip(command: ConnectCommand) {
        val decoded = ConnectCommandCodec.decode(ConnectCommandCodec.encode(command))
        assertEquals(command, decoded)
    }

    @Test
    fun `play pause round trip`() = roundTrip(ConnectCommand.PlayPause(true))

    @Test
    fun `seek round trip`() = roundTrip(ConnectCommand.Seek(91_234L))

    @Test
    fun `scrub round trip`() = roundTrip(ConnectCommand.Scrub(0L))

    @Test
    fun `queue reorder round trip`() = roundTrip(ConnectCommand.QueueReorder(7, 2))

    @Test
    fun `skip commands round trip`() {
        roundTrip(ConnectCommand.SkipNext)
        roundTrip(ConnectCommand.SkipPrevious)
    }

    @Test
    fun `volume round trip clamps to unit range`() {
        val decoded = ConnectCommandCodec.decode(
            ConnectCommandCodec.encode(ConnectCommand.SetVolume(4.2f))
        )
        assertEquals(1.0f, (decoded as? ConnectCommand.SetVolume)?.volume)
    }

    @Test
    fun `empty and garbage frames decode to null`() {
        assertNull(ConnectCommandCodec.decode(""))
        assertNull(ConnectCommandCodec.decode("not-json-at-all"))
        assertNull(ConnectCommandCodec.decode("""{"op":"unknownOp"}"""))
        assertNull(ConnectCommandCodec.decode("""{"nope":1}"""))
    }
}

/**
 * ConnectVolumePolicyTest (Phase 4 — Gap #16) — the route → volume
 * capability matrix. Cast/TV routes share a remote slider; Bluetooth,
 * A2DP_LE and car routes lock guest volume (OS hardware owns master gain);
 * wired/speaker/watch/local routes use the local slider only.
 */
class ConnectVolumePolicyTest {

    @Test
    fun `cast and smart tv routes expose shared remote sliders`() {
        for (kind in listOf(ConnectRouteKind.CAST_RECEIVER, ConnectRouteKind.SMART_TV)) {
            val acl = ConnectVolumePolicy.forRoute(kind)
            assertTrue(kind.name, acl.remoteSliderShared)
            assertTrue(kind.name, !acl.guestLocked)
        }
    }

    @Test
    fun `bluetooth a2dp-le and car routes lock guest volume`() {
        for (kind in listOf(
            ConnectRouteKind.BLUETOOTH,
            ConnectRouteKind.A2DP_LE,
            ConnectRouteKind.CAR
        )) {
            val acl = ConnectVolumePolicy.forRoute(kind)
            assertTrue(kind.name, acl.guestLocked)
            assertTrue(kind.name, !acl.remoteSliderShared)
        }
    }

    @Test
    fun `wired speaker watch and local routes are local-only`() {
        for (kind in listOf(
            ConnectRouteKind.WIRED,
            ConnectRouteKind.SPEAKER,
            ConnectRouteKind.WATCH,
            ConnectRouteKind.LOCAL_PHONE
        )) {
            val acl = ConnectVolumePolicy.forRoute(kind)
            assertTrue(kind.name, !acl.guestLocked)
            assertTrue(kind.name, !acl.remoteSliderShared)
        }
    }

    @Test
    fun `every policy carries a human reason`() {
        for (kind in ConnectRouteKind.values()) {
            assertTrue(
                ConnectVolumePolicy.forRoute(kind).reason.isNotBlank()
            )
        }
    }
}
