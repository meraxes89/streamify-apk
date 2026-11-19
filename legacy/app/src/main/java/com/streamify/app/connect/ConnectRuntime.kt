package com.streamify.app.connect

import android.content.Context
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * ConnectRuntime — process-wide Android wiring for the Connect stack.
 *
 * Owns:
 *  • the [ConnectDeviceRegistry] fed by system audio + cast + remembered rows,
 *  • the [LanConnectGateway] (LAN/cloud sinks),
 *  • the [ConnectSessionCoordinator] every UI surface reads,
 *  • picker-sheet visibility (any pill anywhere opens the same sheet).
 *
 * Cast route discovery plugs in via [castRouteProvider] the moment the Cast
 * module initializes (Phase 4 commit 2) — the registry pulls it on every
 * refresh so no direct coupling is needed.
 */
object ConnectRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Wired by CastMediaManager when the Cast framework wakes up. */
    @Volatile
    var castRouteProvider: () -> List<ConnectDevice> = { emptyList() }

    /** Wired by the Wear companion when a watch is paired. */
    @Volatile
    var wearRouteProvider: () -> List<ConnectDevice> = { emptyList() }

    /** Hook fired whenever a transfer/fallback moves local rendering. */
    @Volatile
    var playbackHooks: LocalPlaybackHooks = NoopLocalPlaybackHooks

    private val lanGateway = LanConnectGateway()

    val registry: ConnectDeviceRegistry by lazy {
        ConnectDeviceRegistry(
            audioRoutes = ConnectRuntime::systemAudioDevices,
            castRoutes = { castRouteProvider() },
            advertisedRoutes = { wearRouteProvider() + rememberedDevices() },
            rememberedRoutes = { rememberedDevices() },
            latencyProbe = HttpLatencyProbe(lanGateway)
        )
    }

    val coordinator: ConnectSessionCoordinator by lazy {
        ConnectSessionCoordinator(
            gatewayFor = ConnectRuntime::gatewayFor,
            hooks = object : LocalPlaybackHooks {
                override fun enterSilentController() = playbackHooks.enterSilentController()
                override fun exitSilentController(positionMs: Long, play: Boolean) =
                    playbackHooks.exitSilentController(positionMs, play)
            },
            registry = registry
        )
    }

    /** Picker sheet visibility — pills and the FAB all toggle this. */
    private val _pickerVisible = MutableStateFlow(false)
    val pickerVisible: StateFlow<Boolean> = _pickerVisible.asStateFlow()

    fun openDevicePicker() {
        _pickerVisible.value = true
    }

    fun closeDevicePicker() {
        _pickerVisible.value = false
    }

    private fun gatewayFor(device: ConnectDevice): ConnectGateway = when {
        device.isLocal -> LoopbackConnectGateway()
        device.origin == DeviceOrigin.CAST -> CastConnectGatewayHolder.bound()
        else -> lanGateway
    }

    // ── System audio routes → Connect vocabulary ────────────────────────

    private fun systemAudioDevices(): List<ConnectDevice> = runCatching {
        val current = com.streamify.app.media.audio.AudioDeviceManager.currentDevice.value
        val device = when {
            current.isBluetooth && current.name.lowercase().let {
                it.contains("car") || it.contains("auto")
            } -> ConnectDevice(
                id = "bt-${current.name}",
                name = current.name,
                kind = ConnectRouteKind.CAR,
                origin = DeviceOrigin.SYSTEM_AUDIO,
                supportsHandoff = false
            )
            current.isBluetooth -> ConnectDevice(
                id = "bt-${current.name}",
                name = current.name,
                kind = ConnectRouteKind.BLUETOOTH,
                origin = DeviceOrigin.SYSTEM_AUDIO,
                supportsHandoff = false
            )
            current.isHeadphones -> ConnectDevice(
                id = "wired",
                name = "Headphones / DAC",
                kind = ConnectRouteKind.WIRED,
                origin = DeviceOrigin.SYSTEM_AUDIO,
                supportsHandoff = false
            )
            else -> null
        }
        listOfNotNull(device)
    }.getOrElse { t ->
        SLog.st("ConnectRuntime", "systemAudioDevices failed", t)
        emptyList()
    }

    // ── Remembered devices (SharedPreferences JSON) ─────────────────────

    private const val PREFS = "streamify_connect_prefs"
    private const val KEY_REMEMBERED = "remembered_devices"
    private const val MAX_REMEMBERED = 12

    fun rememberDevice(device: ConnectDevice) {
        runCatching {
            val prefs = prefs() ?: return
            if (device.isLocal) return
            val next = (rememberedDevices().filter { it.id != device.id } + device).take(MAX_REMEMBERED)
            val arr = JSONArray()
            next.forEach { arr.put(JSONObject().put("id", it.id)) }
            prefs.edit().putString(KEY_REMEMBERED, arr.toString()).apply()
        }
    }

    private fun rememberedDevices(): List<ConnectDevice> = runCatching {
        val raw = prefs()?.getString(KEY_REMEMBERED, null) ?: return emptyList()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            if (id.isBlank() || id == ConnectDevice.LOCAL_DEVICE_ID) null
            else rememberedDevice(id)
        }
    }.getOrElse { emptyList() }

    private fun rememberedDevice(id: String): ConnectDevice = ConnectDevice(
        id = id,
        name = id.removePrefix("lan-").replace('-', ' ').replaceFirstChar { it.uppercase() },
        kind = ConnectRouteKind.SPEAKER,
        origin = DeviceOrigin.REMEMBERED
    )

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        coordinator.attach(scope)
    }
}

/**
 * Indirection so `connect/` never imports the Cast framework at class-load
 * time: CastMediaManager installs itself here on first CastContext wake.
 * Kept in this file so the seam is impossible to miss.
 */
object CastConnectGatewayHolder {
    @Volatile
    private var gateway: ConnectGateway? = null

    fun install(gateway: ConnectGateway) {
        this.gateway = gateway
    }

    /** Fallback: no cast framework → LAN gateway declines everything. */
    fun bound(): ConnectGateway = gateway ?: LoopbackConnectGateway()
}
