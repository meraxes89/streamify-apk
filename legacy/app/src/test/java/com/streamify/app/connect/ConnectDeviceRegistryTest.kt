package com.streamify.app.connect

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ConnectDeviceRegistryTest (Phase 4 — Gap #52) — merge discipline:
 *  • THIS_PHONE is always row #0
 *  • provider priority wins on id collisions (remembered never shadows live)
 *  • family ordering (phone → cast/tv → watch → car → audio → wired)
 *  • latency probing (null = unreachable target)
 *  • link status overlay (CONNECTING / ACTIVE / LOST)
 */
class ConnectDeviceRegistryTest {

    private lateinit var audioDevices: MutableList<ConnectDevice>
    private lateinit var castDevices: MutableList<ConnectDevice>
    private lateinit var remembered: MutableList<ConnectDevice>

    @Before
    fun setUp() {
        audioDevices = mutableListOf()
        castDevices = mutableListOf()
        remembered = mutableListOf()
    }

    private fun registry(probe: LatencyProbe? = null) = ConnectDeviceRegistry(
        audioRoutes = { audioDevices },
        castRoutes = { castDevices },
        rememberedRoutes = { remembered },
        latencyProbe = probe
    )

    private fun speaker(id: String, name: String = id) = ConnectDevice(
        id = id, name = name, kind = ConnectRouteKind.SPEAKER, origin = DeviceOrigin.LAN_DISCOVERY
    )

    @Test
    fun `this phone is always the first row even with many targets`() {
        castDevices += ConnectDevice(
            id = "cast-tv", name = "Alpha TV", kind = ConnectRouteKind.CAST_RECEIVER, origin = DeviceOrigin.CAST
        )
        audioDevices += ConnectDevice(id = "bt-x", name = "BT", kind = ConnectRouteKind.BLUETOOTH, origin = DeviceOrigin.SYSTEM_AUDIO)

        val rows = registry().rows.value

        assertEquals(ConnectDevice.LOCAL_DEVICE_ID, rows.first().id)
        assertEquals("This Phone", rows.first().device.name)
    }

    @Test
    fun `live cast discovery shadows a remembered row with the same id`() {
        castDevices += ConnectDevice(
            id = "cast-tv", name = "Family TV (live)", kind = ConnectRouteKind.CAST_RECEIVER, origin = DeviceOrigin.CAST
        )
        remembered += ConnectDevice(
            id = "cast-tv", name = "Family TV (stale)", kind = ConnectRouteKind.SMART_TV, origin = DeviceOrigin.REMEMBERED
        )

        val rows = registry().rows.value
        val row = rows.first { it.id == "cast-tv" }
        assertEquals("Family TV (live)", row.device.name)
        assertEquals(1, rows.count { it.id == "cast-tv" })
    }

    @Test
    fun `family ordering puts cast before watches before audio before wired`() {
        castDevices += ConnectDevice(id = "cast-1", name = "A cast", kind = ConnectRouteKind.CAST_RECEIVER, origin = DeviceOrigin.CAST)
        audioDevices += ConnectDevice(id = "bt-1", name = "Z bt", kind = ConnectRouteKind.BLUETOOTH, origin = DeviceOrigin.SYSTEM_AUDIO)
        audioDevices += ConnectDevice(id = "wired-1", name = "A wired", kind = ConnectRouteKind.WIRED, origin = DeviceOrigin.SYSTEM_AUDIO)
        remembered += ConnectDevice(id = "watch-1", name = "B watch", kind = ConnectRouteKind.WATCH, origin = DeviceOrigin.LAN_DISCOVERY)

        val ids = registry().rows.value.map { it.id }
        // phone first, then cast/tv family, watch, bluetooth family, wired last
        assertTrue(ids.indexOf("cast-1") < ids.indexOf("watch-1"))
        assertTrue(ids.indexOf("watch-1") < ids.indexOf("bt-1"))
        assertTrue(ids.indexOf("bt-1") < ids.indexOf("wired-1"))
        assertEquals(0, ids.indexOf(ConnectDevice.LOCAL_DEVICE_ID))
    }

    @Test
    fun `names sort alphabetically inside a family`() {
        audioDevices += speaker("s-b", "Beta Speaker")
        audioDevices += speaker("s-a", "Alpha Speaker")

        val names = registry().rows.value.drop(1).map { it.device.name }
        assertEquals(listOf("Alpha Speaker", "Beta Speaker"), names)
    }

    @Test
    fun `blank ids never produce rows`() {
        audioDevices += speaker("")
        val rows = registry().rows.value
        assertEquals(1, rows.size) // only this phone
    }

    @Test
    fun `refresh probes latency and marks unreachable targets null`() = runBlocking {
        val probe = object : LatencyProbe {
            override suspend fun ping(device: ConnectDevice): Long? =
                if (device.id == "dead-speaker") null else 12L
        }
        audioDevices += speaker("live-speaker")
        audioDevices += speaker("dead-speaker")

        val registry = registry(probe)
        registry.refresh()

        val byId = registry.rows.value.associateBy { it.id }
        assertEquals(0L, byId.getValue(ConnectDevice.LOCAL_DEVICE_ID).device.latencyMs)
        assertEquals(12L, byId.getValue("live-speaker").device.latencyMs)
        assertNull(byId.getValue("dead-speaker").device.latencyMs)
    }

    @Test
    fun `link status overlay reflects connect and loss`() {
        // Device must be discovered BEFORE the registry snapshot — the
        // overlay only decorates rows the registry already knows.
        audioDevices += speaker("spk-1")
        val registry = registry()

        registry.markStatus("spk-1", DeviceLinkStatus.CONNECTING)
        assertEquals(DeviceLinkStatus.CONNECTING, registry.rows.value.first { it.id == "spk-1" }.status)

        registry.markStatus("spk-1", DeviceLinkStatus.ACTIVE)
        assertEquals(DeviceLinkStatus.ACTIVE, registry.rows.value.first { it.id == "spk-1" }.status)

        registry.markStatus("spk-1", DeviceLinkStatus.LOST)
        assertEquals(DeviceLinkStatus.LOST, registry.rows.value.first { it.id == "spk-1" }.status)

        registry.markStatus("spk-1", DeviceLinkStatus.IDLE)
        assertEquals(DeviceLinkStatus.IDLE, registry.rows.value.first { it.id == "spk-1" }.status)
    }
}
