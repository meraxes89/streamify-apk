package com.streamify.app.connect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ConnectViewModelTest (Phase 4 — Gap #52) — the thin UI adapter over the
 * coordinator: transfer requests flow through, remembered devices persist,
 * pending-transfer flags clear, and remote volume reaches the gateway.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {

    private lateinit var gateway: FakeGateway
    private lateinit var registry: ConnectDeviceRegistry
    private lateinit var coordinator: ConnectSessionCoordinator
    private lateinit var viewModel: ConnectViewModel

    private val tv = ConnectDevice(
        id = "cast-tv", name = "Family TV", kind = ConnectRouteKind.CAST_RECEIVER, origin = DeviceOrigin.CAST
    )
    private val speaker = ConnectDevice(
        id = "lan-spk", name = "Kitchen Speaker", kind = ConnectRouteKind.SPEAKER, origin = DeviceOrigin.LAN_DISCOVERY
    )

    private val snapshot = PlaybackSnapshot(
        queueTitles = listOf("Only Song"),
        currentIndex = 0,
        positionMs = 7_000L,
        isPlaying = true
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        gateway = FakeGateway()
        registry = ConnectDeviceRegistry(
            castRoutes = { listOf(tv) },
            advertisedRoutes = { listOf(speaker) }
        )
        coordinator = ConnectSessionCoordinator(gatewayFor = { gateway })
        viewModel = ConnectViewModel(registry = registry, coordinator = coordinator)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `rows expose registry devices with this phone first`() {
        val ids = viewModel.rows.value.map { it.id }
        // Cast family outranks speakers in the picker ordering.
        assertEquals(
            listOf(ConnectDevice.LOCAL_DEVICE_ID, "cast-tv", "lan-spk"),
            ids
        )
    }

    @Test
    fun `requestTransfer drives the coordinator to REMOTE_ACTIVE`() {
        viewModel.requestTransfer(tv, snapshot)

        // UnconfinedTestDispatcher: the launch completed synchronously.
        assertEquals(ConnectSessionPhase.REMOTE_ACTIVE, viewModel.session.value.phase)
        assertEquals(tv.id, viewModel.session.value.activeDevice?.id)
        assertNull(viewModel.pendingTransfer.value)
        assertEquals(tv.id, gateway.boundDeviceId)
    }

    @Test
    fun `transfer back to this phone returns LOCAL_ONLY`() {
        viewModel.requestTransfer(tv, snapshot)
        viewModel.requestTransfer(ConnectDevice.THIS_PHONE, snapshot)

        assertEquals(ConnectSessionPhase.LOCAL_ONLY, viewModel.session.value.phase)
        assertNull(viewModel.session.value.activeDevice)
    }

    @Test
    fun `failed transfer surfaces the error and stays local`() {
        gateway.connectResult = false
        viewModel.requestTransfer(speaker, snapshot)

        assertEquals(ConnectSessionPhase.LOCAL_ONLY, viewModel.session.value.phase)
        assertNotNull(viewModel.session.value.lastError)
    }

    @Test
    fun `remote volume reaches the gateway on shared-slider routes`() = runBlocking {
        viewModel.requestTransfer(tv, snapshot)
        viewModel.setRemoteVolume(0.35f)

        assertEquals(listOf(ConnectCommand.SetVolume(0.35f)), gateway.sent)
    }

    @Test
    fun `pending flag is visible mid-handshake and clears after completion`() {
        // Gate the handshake: the coroutine parks inside connect() while we
        // assert the in-flight flag, then completes synchronously when the
        // gate opens (Unconfined main resumes inline on the completer).
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        gateway.connectGate = gate
        viewModel.requestTransfer(tv, snapshot)

        assertEquals(tv.id, viewModel.pendingTransfer.value)

        gate.complete(Unit)

        assertEquals(ConnectSessionPhase.REMOTE_ACTIVE, viewModel.session.value.phase)
        assertNull(viewModel.pendingTransfer.value)
        assertEquals(tv.id, gateway.boundDeviceId)
    }
}

private class FakeGateway(var connectResult: Boolean = true) : ConnectGateway {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<ConnectGatewayEvent>()
    override val events: kotlinx.coroutines.flow.SharedFlow<ConnectGatewayEvent> get() = _events

    override val boundDeviceId: String?
        get() = bound

    /** Optional handshake gate for deterministic mid-flight assertions. */
    var connectGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    var bound: String? = null
        private set
    val sent = mutableListOf<ConnectCommand>()

    override suspend fun connect(device: ConnectDevice, snapshot: PlaybackSnapshot): Boolean {
        connectGate?.await()
        if (!connectResult) return false
        bound = device.id
        return true
    }

    override suspend fun disconnect() {
        bound = null
    }

    override suspend fun send(command: ConnectCommand): Boolean {
        sent.add(command)
        return true
    }
}
