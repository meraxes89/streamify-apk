package com.streamify.app.cast

import com.streamify.app.connect.ConnectRouteKind
import com.streamify.app.connect.DeviceOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CastMediaManagerTest (Phase 4 — Gaps #53/#16) — pure route logic:
 *  • device row classification per CastRoutePhase
 *  • stable receiver route ids across discovery refreshes
 *  • route-death transitions (fallback triggers)
 *  • route volume policy enforcement on the cast family
 */
class CastMediaManagerTest {

    // ─────────────────────────── device rows ─────────────────────────────

    @Test
    fun `no devices phase yields no picker row`() {
        assertNull(StreamifyMediaRouteProvider.castDeviceFor(CastRoutePhase.NO_DEVICES, "Family TV"))
    }

    @Test
    fun `connected phase yields a cast route row with handoff support`() {
        val device = StreamifyMediaRouteProvider.castDeviceFor(CastRoutePhase.CONNECTED, "Family TV")
        assertNotNull(device)
        assertEquals(ConnectRouteKind.CAST_RECEIVER, device!!.kind)
        assertEquals(DeviceOrigin.CAST, device.origin)
        assertTrue(device.supportsHandoff)
        assertEquals("Family TV", device.name)
    }

    @Test
    fun `connecting and transferring phases surface rows too`() {
        assertNotNull(StreamifyMediaRouteProvider.castDeviceFor(CastRoutePhase.CONNECTING, "Kitchen"))
        assertNotNull(StreamifyMediaRouteProvider.castDeviceFor(CastRoutePhase.TRANSFERRING, "Kitchen"))
    }

    @Test
    fun `idle phase keeps a row for remembered receivers`() {
        val device = StreamifyMediaRouteProvider.castDeviceFor(CastRoutePhase.IDLE, null)
        assertNotNull(device)
        assertEquals("Nearby Cast device", device!!.name)
    }

    // ─────────────────────────── stable ids ──────────────────────────────

    @Test
    fun `route ids are stable across case and spacing variants`() {
        val a = StreamifyMediaRouteProvider.routeIdFor("Family TV")
        val b = StreamifyMediaRouteProvider.routeIdFor("  family tv ")
        assertEquals(a, b)
        assertTrue(a.startsWith("cast-"))
    }

    // ─────────────────────────── route death ─────────────────────────────

    @Test
    fun `connected to nothing or idle is a route death`() {
        assertTrue(StreamifyMediaRouteProvider.isRouteDeath(CastRoutePhase.CONNECTED, CastRoutePhase.NO_DEVICES))
        assertTrue(StreamifyMediaRouteProvider.isRouteDeath(CastRoutePhase.CONNECTED, CastRoutePhase.IDLE))
    }

    @Test
    fun `transferring between receivers is not a death`() {
        assertFalse(StreamifyMediaRouteProvider.isRouteDeath(CastRoutePhase.CONNECTED, CastRoutePhase.TRANSFERRING))
    }

    @Test
    fun `pre-connected phases never report death`() {
        assertFalse(StreamifyMediaRouteProvider.isRouteDeath(CastRoutePhase.IDLE, CastRoutePhase.NO_DEVICES))
        assertFalse(StreamifyMediaRouteProvider.isRouteDeath(CastRoutePhase.CONNECTING, CastRoutePhase.IDLE))
        assertFalse(StreamifyMediaRouteProvider.isRouteDeath(CastRoutePhase.CONNECTED, CastRoutePhase.CONNECTED))
    }

    // ─────────────────────────── volume ACL ──────────────────────────────

    @Test
    fun `cast routes grant the shared remote slider`() {
        val acl = StreamifyMediaRouteProvider.volumeAclFor(CastRoutePhase.CONNECTED)
        assertTrue(acl.remoteSliderShared)
        assertFalse(acl.guestLocked)
    }

    @Test
    fun `volume acl is phase-independent on cast routes`() {
        for (phase in CastRoutePhase.values()) {
            assertTrue(StreamifyMediaRouteProvider.volumeAclFor(phase).remoteSliderShared)
        }
    }
}
