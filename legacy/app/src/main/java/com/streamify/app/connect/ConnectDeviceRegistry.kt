package com.streamify.app.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One row of the Connect device picker: the immutable target plus its live
 * link decoration. Splitting the two keeps [ConnectDevice] value-stable
 * (identity) while status flips drive recomposition cheaply.
 */
data class ConnectDeviceRow(
    val device: ConnectDevice,
    val status: DeviceLinkStatus = DeviceLinkStatus.IDLE
) {
    val id: String get() = device.id
    val isLocal: Boolean get() = device.isLocal
}

/**
 * ConnectDeviceRegistry — merged, deduped view of every Connect target the
 * phone knows about (Gap #52).
 *
 * Sources, in merge priority order:
 *  1. THIS_PHONE — always row #0.
 *  2. System audio routes (BT / wired / speaker) via AudioDeviceManager.
 *  3. Cast routes discovered by the Cast framework.
 *  4. LAN/cloud advertised targets (registry hook from discovery).
 *  5. Remembered devices persisted from previous sessions.
 *
 * Pure Kotlin: every source is an injected provider so the JVM shard can
 * exercise merge/dedup/priority/latency rules with fakes. The Android
 * wiring lives in [ConnectRuntime].
 */
class ConnectDeviceRegistry(
    private val audioRoutes: () -> List<ConnectDevice> = { emptyList() },
    private val castRoutes: () -> List<ConnectDevice> = { emptyList() },
    private val advertisedRoutes: () -> List<ConnectDevice> = { emptyList() },
    private val rememberedRoutes: () -> List<ConnectDevice> = { emptyList() },
    private val latencyProbe: LatencyProbe? = null
) {

    private val _rows = MutableStateFlow<List<ConnectDeviceRow>>(emptyList())
    val rows: StateFlow<List<ConnectDeviceRow>> = _rows.asStateFlow()

    /** Coordinator-owned link overlay (CONNECTING / ACTIVE / LOST). */
    @Volatile
    var linkStatus: Map<String, DeviceLinkStatus> = emptyMap()
        private set

    init {
        rebuild(collectProviders())
    }

    fun markStatus(deviceId: String, status: DeviceLinkStatus) {
        linkStatus = if (status == DeviceLinkStatus.IDLE) {
            linkStatus - deviceId
        } else {
            linkStatus + (deviceId to status)
        }
        rebuild(_rows.value.map { it.device })
    }

    /**
     * Re-harvest all providers and refresh latency badges.
     * Called on sheet open, on route broadcasts, and periodically while the
     * picker is visible (driven by the ViewModel).
     */
    suspend fun refresh() {
        val probed = probeLatencies(collectProviders())
        rebuild(probed)
    }

    private fun collectProviders(): List<ConnectDevice> =
        audioRoutes() + castRoutes() + advertisedRoutes() + rememberedRoutes()

    private fun rebuild(raw: List<ConnectDevice>) {
        val seen = LinkedHashMap<String, ConnectDevice>()
        // Priority: earlier provider wins on id collision; a remembered row
        // may never shadow a live discovery of the same target.
        for (device in listOf(ConnectDevice.THIS_PHONE) + raw) {
            if (device.id.isBlank()) continue
            seen.putIfAbsent(device.id, device)
        }
        // Family order: this phone first, then strongest handoff routes
        // (cast/tv), then watches, then everything else — name-ordered inside
        // each family so the sheet never reshuffles between refreshes.
        val familyRank: (ConnectDevice) -> Int = {
            when (it.kind) {
                ConnectRouteKind.LOCAL_PHONE -> 0
                ConnectRouteKind.CAST_RECEIVER, ConnectRouteKind.SMART_TV -> 1
                ConnectRouteKind.WATCH -> 2
                ConnectRouteKind.CAR -> 3
                ConnectRouteKind.SPEAKER, ConnectRouteKind.BLUETOOTH, ConnectRouteKind.A2DP_LE -> 4
                ConnectRouteKind.WIRED -> 5
            }
        }
        _rows.value = seen.values
            .sortedWith(compareBy(familyRank, { it.name.lowercase() }))
            .map { ConnectDeviceRow(it, linkStatus[it.id] ?: DeviceLinkStatus.IDLE) }
    }

    private suspend fun probeLatencies(devices: List<ConnectDevice>): List<ConnectDevice> {
        val probe = latencyProbe ?: return devices
        return devices.map { device ->
            if (device.isLocal) {
                device.copy(latencyMs = 0L)
            } else {
                val rtt = runCatching { probe.ping(device) }.getOrNull()
                device.copy(latencyMs = rtt)
            }
        }
    }
}
