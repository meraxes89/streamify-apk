package com.streamify.app.connect

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ConnectSessionCoordinatorTest (Phase 4 — Gap #52) — the full transfer
 * state machine matrix on the JVM:
 *
 *  • LOCAL → REMOTE_ACTIVE with position+queue-preserving handoff
 *  • silent remote-controller mode entry/exit
 *  • one-tap "transfer to this phone" resuming at the freshest position
 *  • automatic fallback to local playback on route death
 *  • failed hops roll back WITHOUT killing the previous sink
 *  • volume ACL enforcement per route kind
 */
class ConnectSessionCoordinatorTest {

    private lateinit var gateway: ScriptedGateway
    private lateinit var hooks: RecordingHooks
    private lateinit var registry: ConnectDeviceRegistry

    private val speaker = ConnectDevice(
        id = "lan-living-room",
        name = "Living Room Speaker",
        kind = ConnectRouteKind.SPEAKER,
        origin = DeviceOrigin.LAN_DISCOVERY
    )
    private val tv = ConnectDevice(
        id = "cast-family-tv",
        name = "Family TV",
        kind = ConnectRouteKind.CAST_RECEIVER,
        origin = DeviceOrigin.CAST
    )
    private val btHeadphones = ConnectDevice(
        id = "bt-px7",
        name = "PX7",
        kind = ConnectRouteKind.BLUETOOTH,
        origin = DeviceOrigin.SYSTEM_AUDIO,
        supportsHandoff = false
    )

    private fun snapshot(positionMs: Long = 42_000L, playing: Boolean = true) = PlaybackSnapshot(
        queueTitles = listOf("Alpha", "Beta", "Gamma"),
        currentIndex = 1,
        positionMs = positionMs,
        isPlaying = playing
    )

    @Before
    fun setUp() {
        gateway = ScriptedGateway()
        hooks = RecordingHooks()
        registry = ConnectDeviceRegistry()
    }

    private fun coordinator() = ConnectSessionCoordinator(
        gatewayFor = { gateway },
        hooks = hooks,
        registry = registry,
        clockMs = { 1_000L }
    )

    // ─────────────────────────────── transfers ───────────────────────────

    @Test
    fun `transfer to remote enters REMOTE_ACTIVE with handoff snapshot`() = runBlocking {
        val c = coordinator()
        val result = c.transferTo(speaker, snapshot())

        assertEquals(ConnectSessionPhase.REMOTE_ACTIVE, result.phase)
        assertEquals(speaker.id, result.activeDevice?.id)
        assertEquals(42_000L, result.snapshotAtTransfer?.positionMs)
        assertEquals(1, result.snapshotAtTransfer?.currentIndex)
        assertEquals(3, result.snapshotAtTransfer?.queueSize)
        assertTrue(hooks.enteredSilent)
        assertEquals(speaker.id, gateway.boundDeviceId)
    }

    @Test
    fun `transfer to this phone exits silent mode at freshest position`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot(positionMs = 10_000L))

        // Sink reports progress beyond the local clock.
        c.onGatewayEvent(ConnectGatewayEvent.RemotePosition(31_000L, isPlaying = true))
        // The user taps a new snapshot AFTER the sink moved on.
        val result = c.transferTo(ConnectDevice.THIS_PHONE, snapshot(positionMs = 12_000L))

        assertEquals(ConnectSessionPhase.LOCAL_ONLY, result.phase)
        assertNull(result.activeDevice)
        assertTrue(hooks.exitedSilent)
        // Freshest known position wins — never a rewind to the local clock.
        assertEquals(31_000L, hooks.lastExitPositionMs)
        assertTrue(hooks.lastExitPlay)
        assertNull(gateway.boundDeviceId)
    }

    @Test
    fun `failed connect from local stays local with error`() = runBlocking {
        gateway.connectResult = false
        val c = coordinator()
        val result = c.transferTo(speaker, snapshot())

        assertEquals(ConnectSessionPhase.LOCAL_ONLY, result.phase)
        assertNotNull(result.lastError)
        assertFalse(hooks.enteredSilent)
        assertNull(gateway.boundDeviceId)
    }

    @Test
    fun `failed sink-to-sink hop keeps the previous sink alive`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot(positionMs = 5_000L))
        gateway.sent.clear()
        val sinkReported = ConnectGatewayEvent.RemotePosition(50_000L, true)
        c.onGatewayEvent(sinkReported)

        // Second gateway declined for the TV.
        val failing = ScriptedGateway(connectResult = false)
        val hop = ConnectSessionCoordinator(
            gatewayFor = { if (it.id == tv.id) failing else gateway },
            hooks = hooks,
            registry = registry
        )
        // Seed the hop coordinator with an active remote session.
        hop.transferTo(speaker, snapshot(positionMs = 5_000L))
        hop.onGatewayEvent(sinkReported)
        val result = hop.transferTo(tv, snapshot(positionMs = 51_000L))

        // Rollback: still on the speaker, error surfaced, local unmute
        // must NOT have happened (the speaker kept rendering).
        assertEquals(ConnectSessionPhase.REMOTE_ACTIVE, result.phase)
        assertEquals(speaker.id, result.activeDevice?.id)
        assertNotNull(result.lastError)
        assertFalse(hooks.exitedSilent)
        assertEquals(speaker.id, gateway.boundDeviceId)
    }

    @Test
    fun `successful hop carries the freshest sink position`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot(positionMs = 10_000L))
        c.onGatewayEvent(ConnectGatewayEvent.RemotePosition(88_000L, isPlaying = true))

        val result = c.transferTo(tv, snapshot(positionMs = 20_000L))

        assertEquals(ConnectSessionPhase.REMOTE_ACTIVE, result.phase)
        assertEquals(tv.id, result.activeDevice?.id)
        // Donor's reported position beats the stale local snapshot.
        assertEquals(88_000L, result.snapshotAtTransfer?.positionMs)
        // The old sink got a graceful goodbye.
        assertTrue(gateway.disconnectCalled)
    }

    // ─────────────────────────────── fallback ────────────────────────────

    @Test
    fun `route death falls back to local at last sink position`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot(positionMs = 10_000L, playing = true))
        c.onGatewayEvent(ConnectGatewayEvent.RemotePosition(64_000L, isPlaying = true))
        hooks.exitedSilent = false

        c.onGatewayEvent(ConnectGatewayEvent.Disconnected)

        val s = c.state.value
        assertEquals(ConnectSessionPhase.LOCAL_ONLY, s.phase)
        assertTrue(hooks.exitedSilent)
        assertEquals(64_000L, hooks.lastExitPositionMs)
        assertTrue(hooks.lastExitPlay)
        // FALLING_BACK was traversed (observable transition log).
        assertTrue(c.history.value.any { it.phase == ConnectSessionPhase.FALLING_BACK })
        // Registry marks the dead route LOST so the picker shows it.
        assertEquals(DeviceLinkStatus.LOST, registry.linkStatus[speaker.id])
    }

    @Test
    fun `position heartbeats update drift telemetry`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot())
        c.onGatewayEvent(ConnectGatewayEvent.RemotePosition(123_456L, isPlaying = false))

        assertEquals(123_456L, c.state.value.lastRemotePositionMs)
        assertFalse(c.state.value.remoteIsPlaying)
    }

    // ─────────────────────────────── dispatch ────────────────────────────

    @Test
    fun `transport commands forward to the active gateway while remote`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot())

        assertTrue(c.dispatch(ConnectCommand.PlayPause(true)))
        assertTrue(c.dispatch(ConnectCommand.Seek(5_000L)))
        assertTrue(c.dispatch(ConnectCommand.QueueReorder(2, 0)))
        assertTrue(c.dispatch(ConnectCommand.SkipNext))

        assertEquals(
            listOf(
                ConnectCommand.PlayPause(true),
                ConnectCommand.Seek(5_000L),
                ConnectCommand.QueueReorder(2, 0),
                ConnectCommand.SkipNext
            ),
            gateway.sent
        )
    }

    @Test
    fun `commands execute locally while LOCAL_ONLY`() = runBlocking {
        val c = coordinator()
        assertFalse(c.dispatch(ConnectCommand.PlayPause(true)))
        assertTrue(gateway.sent.isEmpty())
    }

    @Test
    fun `volume dispatch is allowed on cast receivers`() = runBlocking {
        val c = coordinator()
        c.transferTo(tv, snapshot())
        assertTrue(c.dispatch(ConnectCommand.SetVolume(0.4f)))
        assertEquals(listOf(ConnectCommand.SetVolume(0.4f)), gateway.sent)
    }

    @Test
    fun `volume dispatch is locked on bluetooth routes`() = runBlocking {
        val c = coordinator()
        // Even when a BT route somehow becomes the active sink, the ACL
        // must refuse remote volume (OS owns master gain).
        c.transferTo(btHeadphones, snapshot())
        assertFalse(c.dispatch(ConnectCommand.SetVolume(0.4f)))
        assertTrue(gateway.sent.isEmpty())
    }

    // ─────────────────────────────── registry marks ──────────────────────

    @Test
    fun `registry tracks connecting then active then idle`() = runBlocking {
        val c = coordinator()
        c.transferTo(speaker, snapshot())
        assertEquals(DeviceLinkStatus.ACTIVE, registry.linkStatus[speaker.id])
        c.transferTo(ConnectDevice.THIS_PHONE, snapshot())
        assertFalse(registry.linkStatus.containsKey(speaker.id))
    }
}

/** Scriptable gateway double. */
private class ScriptedGateway(var connectResult: Boolean = true) : ConnectGateway {
    private val _events = MutableSharedFlow<ConnectGatewayEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<ConnectGatewayEvent> = _events

    override val boundDeviceId: String?
        get() = bound

    var bound: String? = null
        private set
    var disconnectCalled = false
        private set
    val sent = mutableListOf<ConnectCommand>()

    override suspend fun connect(device: ConnectDevice, snapshot: PlaybackSnapshot): Boolean {
        if (!connectResult) return false
        bound = device.id
        return true
    }

    override suspend fun disconnect() {
        disconnectCalled = true
        bound = null
        _events.tryEmit(ConnectGatewayEvent.Disconnected)
    }

    override suspend fun send(command: ConnectCommand): Boolean {
        sent.add(command)
        return true
    }
}

/** Hook recorder: silent-mode transitions + resume arguments. */
private class RecordingHooks : LocalPlaybackHooks {
    var enteredSilent = false
    var exitedSilent = false
    var lastExitPositionMs = -1L
    var lastExitPlay = false

    override fun enterSilentController() {
        enteredSilent = true
    }

    override fun exitSilentController(positionMs: Long, play: Boolean) {
        exitedSilent = true
        lastExitPositionMs = positionMs
        lastExitPlay = play
    }
}
