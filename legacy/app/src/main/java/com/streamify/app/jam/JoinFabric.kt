package com.streamify.app.jam

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ZERO-FRICTION JOIN FABRIC (BEHIND.md Gap #12, Phase 1)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Three discovery rails, all surfaced as one auto-prompt stream the Home
 * screen renders as a sleek bottom sheet:
 *
 *  1. BLE TAP-TO-JOIN — the host runs a lightweight BLE ADVERTISER whose
 *     manufacturer payload is [BleJoinPayload] (room code + host name +
 *     party/full/queue flags). Nearby guests run a scanner; when two phones
 *     are tapped together (RSSI ≥ [BleJoinPayload.PROXIMITY_RSSI_DBM] ≈ 1 m)
 *     the one-tap "Join [Host]'s Jam" prompt fires. Zero PIN, zero typing.
 *
 *  2. LAN AUTO-PROMPT — a UDP listener hears the local Rust mesh's 0x09
 *     BEACON broadcasts ([LanBeaconPacket], port 7777) → "a room is active
 *     on this Wi-Fi". For one-tap joinability the host additionally runs an
 *     app-layer ROOM ANNOUNCE datagram (port 7778) carrying the same
 *     [BleJoinPayload] bytes, so guests on the same network get the full
 *     room identity — no QR, no PIN — exactly the Spotify same-WiFi prompt.
 *
 *  3. SPEAKER CONNECT — watches [com.streamify.app.media.audio.AudioDeviceManager]
 *     route transitions; connecting a Bluetooth/smart speaker outside a Jam
 *     offers "Start a Jam on this speaker" with one tap.
 *
 * LIFECYCLE DISCIPLINE (directive §4): every scanner, listener socket and
 * coroutine job is torn down in [stopDiscovery]/[stopHosting]/[detach];
 * the Home screen drives them from DisposableEffect, and hosting follows
 * the JamEngine session lifecycle. Nothing leaks across rooms.
 *
 * DEGRADATION CONTRACT: every rail is an independent best-effort — missing
 * permissions, absent BT hardware, bind failures and airplane mode all
 * degrade to silent no-ops (logged once) rather than crashes. The manual
 * QR/PIN flow from [JamPairing] remains the offline fallback.
 */
object JoinFabric {

    // ═══════════════ Types ═══════════════

    enum class Source { BLE_TAP, LAN_BEACON, LAN_ANNOUNCE, SPEAKER_CONNECT }

    /**
     * One joinable thing the user can see. LAN_BEACON sightings carry no
     * session code (the Rust beacon only exposes the room hash) — they
     * deep-link into the Jam screen's join form.
     */
    data class JoinSighting(
        val source: Source,
        val sessionCode: String?,
        val hostName: String,
        val partyMode: Boolean = false,
        val hasSpace: Boolean = true,
        val queueNonEmpty: Boolean = false,
        val rssiDbm: Int? = null,
        val atMs: Long = System.currentTimeMillis()
    ) {
        val key: String get() = "${source.name}:${sessionCode ?: hostName}"

        /** One-line subtitle for the prompt sheet. */
        val subtitle: String
            get() = when (source) {
                Source.BLE_TAP -> BleJoinPayload.Decoded(
                    sessionCode = sessionCode ?: "",
                    hostName = hostName,
                    partyMode = partyMode,
                    hasSpace = hasSpace,
                    queueNonEmpty = queueNonEmpty
                ).promptSubtitle
                Source.LAN_BEACON -> "Active on this Wi-Fi network"
                Source.LAN_ANNOUNCE -> if (partyMode) "Party mode · $hostName's speaker"
                    else if (hasSpace) "$hostName is listening together"
                    else "Room is full (32/32)"
                Source.SPEAKER_CONNECT -> "Host your room on this speaker"
            }
    }

    /** Speaker-connect rail: the one-tap host prompt state. */
    data class SpeakerPrompt(
        val deviceName: String,
        val atMs: Long = System.currentTimeMillis()
    )

    // ═══════════════ Public state ═══════════════

    private val _sightings = MutableStateFlow<List<JoinSighting>>(emptyList())
    val sightings: StateFlow<List<JoinSighting>> = _sightings.asStateFlow()

    private val _speakerPrompt = MutableStateFlow<SpeakerPrompt?>(null)
    val speakerPrompt: StateFlow<SpeakerPrompt?> = _speakerPrompt.asStateFlow()

    /** True while this device advertises a room (BLE + LAN announce). */
    val isHosting = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appContext: Context? = null

    // ── Rail states ────────────────────────────────────────────────────────

    private var bleScanner: Any? = null        // ScanCallback reference
    private var bleAdvertiser: Any? = null     // AdvertiseCallback reference
    private var lanListenerJob: Job? = null
    private var announceJob: Job? = null
    private var speakerWatchJob: Job? = null
    private var lanSocket: DatagramSocket? = null

    /** Live host-side room state mirrored into every advert/announce tick. */
    @Volatile private var hostSessionCode: String? = null
    @Volatile private var hostName: String = ""
    @Volatile private var hostPartyMode: Boolean = false
    @Volatile private var hostHasSpace: Boolean = true
    @Volatile private var hostQueueNonEmpty: Boolean = false

    /** Sighting freshness window + dedup (per key). */
    private val sightingSeen = ConcurrentHashMap<String, Long>()
    private val dismissedKeys = ConcurrentHashMap.newKeySet<String>()

    // ═══════════════ Lifecycle ═══════════════

    /** Idempotent process-level attach (MainActivity onCreate). */
    fun attach(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        startSpeakerWatch()
        SLog.i("JoinFabric", "attached (BLE+LAN join fabric)")
    }

    /** Full teardown (MainActivity onDestroy) — every rail, every job. */
    fun detach() {
        stopDiscovery()
        stopHosting()
        speakerWatchJob?.cancel()
        speakerWatchJob = null
        _speakerPrompt.value = null
        appContext = null
    }

    // ═══════════════ Rail 3: speaker connect → "Start a Jam on this speaker" ═══════════════

    /**
     * Watches audio route transitions. A Bluetooth / smart-speaker connect
     * while NOT in a Jam raises the one-tap host prompt. In-Jam connects are
     * ignored (the room already exists; topology handles routing).
     */
    private fun startSpeakerWatch() {
        if (speakerWatchJob?.isActive == true) return
        speakerWatchJob = scope.launch {
            var previousWasBluetooth = false
            com.streamify.app.media.audio.AudioDeviceManager.currentDevice.collect { device ->
                val isBt = device.isBluetooth
                val isSpeakerish = device.isBluetooth || device.isSpeaker
                val justConnected = isBt && !previousWasBluetooth
                previousWasBluetooth = isBt
                if (justConnected && isSpeakerish && !JamEngine.isActive()) {
                    _speakerPrompt.value = SpeakerPrompt(deviceName = device.name)
                    SLog.i("JoinFabric", "speaker connect detected: ${device.name}")
                } else if (!isBt) {
                    _speakerPrompt.value = null
                }
            }
        }
    }

    /** Consumed by the UI's one-tap action (prompt clears, user stays free). */
    fun dismissSpeakerPrompt() {
        _speakerPrompt.value = null
    }

    // ═══════════════ Guest side: discovery ═══════════════

    /**
     * Starts all guest-side rails: BLE scanner + LAN beacon/announce
     * listener. Requires BLUETOOTH_SCAN (API 31+) / location (≤30) for BLE;
     * LAN listening needs no permission. Missing permissions degrade the BLE
     * rail to a no-op — the UI re-invokes this once permissions land.
     */
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val ctx = appContext ?: return
        startLanListener()
        if (canBleScan(ctx)) startBleScan(ctx)
        else SLog.d("JoinFabric", "BLE scan rail idle (permissions not granted yet)")
    }

    fun stopDiscovery() {
        stopBleScan()
        lanListenerJob?.cancel()
        lanListenerJob = null
        runCatching { lanSocket?.close() }
        lanSocket = null
    }

    /** BLE scan permission gate (API-level aware). */
    fun canBleScan(context: Context): Boolean {
        fun has(p: String) =
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            has(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            has(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    /** BLE advertise permission gate (API 31+). */
    fun canBleAdvertise(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * BLE SCANNER — parses every advert's manufacturer data looking for
     * [BleJoinPayload.MANUFACTURER_ID]; a hit within proximity raises the
     * one-tap join sighting.
     */
    @SuppressLint("MissingPermission")
    private fun startBleScan(context: Context) {
        if (bleScanner != null) return
        val adapter = bluetoothAdapter(context) ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val rssi = result.rssi
                if (rssi < BleJoinPayload.PROXIMITY_RSSI_DBM) return // not "tapped together"
                val decoded = BleJoinPayload.fromScanRecord(
                    result.scanRecord?.bytes, BleJoinPayload.MANUFACTURER_ID
                ) ?: return
                publish(
                    JoinSighting(
                        source = Source.BLE_TAP,
                        sessionCode = decoded.sessionCode,
                        hostName = decoded.hostName,
                        partyMode = decoded.partyMode,
                        hasSpace = decoded.hasSpace,
                        queueNonEmpty = decoded.queueNonEmpty,
                        rssiDbm = rssi
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) {
                SLog.w("JoinFabric", "BLE scan failed: $errorCode — rail down, LAN rail continues")
                bleScanner = null
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner.startScan(null, settings, callback)
            bleScanner = callback
            SLog.i("JoinFabric", "BLE scanner up (proximity ${BleJoinPayload.PROXIMITY_RSSI_DBM} dBm)")
        } catch (e: Exception) {
            SLog.st("JoinFabric", "BLE scanner start failed", e)
        }
    }

    private fun stopBleScan() {
        val ctx = appContext
        val callback = bleScanner as? ScanCallback
        if (callback != null && ctx != null && canBleScan(ctx)) {
            runCatching {
                bluetoothAdapter(ctx)?.bluetoothLeScanner?.stopScan(callback)
            }
        }
        bleScanner = null
    }

    // ═══════════════ Rail 2: LAN listener (Rust 0x09 beacon + room announce) ═══════════════

    /**
     * One socket, two feeds:
     *  • port 7777 (shared with the Rust mesh via SO_REUSEADDR — beacons are
     *    subnet broadcasts, every REUSEADDR socket gets a copy): Rust 0x09
     *    BEACON frames → "room active on this Wi-Fi" sightings.
     *  • port 7778 (app-layer): host ROOM ANNOUNCE datagrams carrying full
     *    [BleJoinPayload] identity → one-tap join sightings.
     */
    private fun startLanListener() {
        if (lanListenerJob?.isActive == true) return
        lanListenerJob = scope.launch {
            // ── 7778: app-layer room announcements (one-tap join data) ──────
            launch {
                val socket = try {
                    DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(LAN_ANNOUNCE_PORT))
                    }
                } catch (e: Exception) {
                    SLog.d("JoinFabric", "announce listener bind failed (7778): ${e.message}")
                    return@launch
                }
                val buf = ByteArray(256)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        val decoded = BleJoinPayload.decode(packet.data.copyOf(packet.length))
                        if (decoded != null) {
                            publish(
                                JoinSighting(
                                    source = Source.LAN_ANNOUNCE,
                                    sessionCode = decoded.sessionCode,
                                    hostName = decoded.hostName,
                                    partyMode = decoded.partyMode,
                                    hasSpace = decoded.hasSpace,
                                    queueNonEmpty = decoded.queueNonEmpty
                                )
                            )
                        }
                    } catch (e: Exception) {
                        if (socket.isClosed) break
                    }
                }
                runCatching { socket.close() }
            }

            // ── 7777: Rust mesh 0x09 beacons (room-active presence) ─────────
            val beaconSocket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(LanBeaconPacket.DEFAULT_MESH_PORT))
                }
            } catch (e: Exception) {
                // Port owned by the in-room Rust mesh with REUSEPORT semantics
                // differing per OEM — LAN beacon rail degrades silently.
                SLog.d("JoinFabric", "beacon listener bind failed (7777): ${e.message}")
                return@launch
            }
            lanSocket = beaconSocket
            val bbuf = ByteArray(128)
            val bpacket = DatagramPacket(bbuf, bbuf.size)
            val seenRooms = HashMap<String, Long>()
            while (isActive) {
                try {
                    beaconSocket.receive(bpacket)
                    val decoded = LanBeaconPacket.decode(bpacket.data.copyOf(bpacket.length)) ?: continue
                    val now = System.currentTimeMillis()
                    val last = seenRooms[decoded.sessionIdHex] ?: 0L
                    if (now - last < LAN_SIGHTING_THROTTLE_MS) continue
                    seenRooms[decoded.sessionIdHex] = now
                    // Own mesh's beacons: ignore (we are already in that room).
                    if (JamEngine.isActive()) continue
                    publish(
                        JoinSighting(
                            source = Source.LAN_BEACON,
                            sessionCode = null,
                            hostName = "Streamify Jam",
                            hasSpace = true
                        )
                    )
                } catch (e: Exception) {
                    if (beaconSocket.isClosed) break
                }
            }
            runCatching { beaconSocket.close() }
            lanSocket = null
        }
    }

    // ═══════════════ Host side: advertising + announcements ═══════════════

    /**
     * Starts hosting rails for the active session: BLE advertiser (payload =
     * [BleJoinPayload]) + LAN room-announce loop (port 7778 broadcast).
     * Call from the Jam screen the moment a room goes live.
     */
    @SuppressLint("MissingPermission")
    fun startHosting(sessionCode: String, hostDisplayName: String) {
        hostSessionCode = sessionCode
        hostName = hostDisplayName
        isHosting.set(true)
        appContext?.let { startBleAdvertiser(it) }
        startAnnounceLoop()
        SLog.i("JoinFabric", "hosting room $sessionCode as '$hostDisplayName'")
    }

    /**
     * Live host state refresh (topology / cap / queue) — mirrored into the
     * next advert + announce tick so prompts stay truthful.
     */
    fun updateHostingState(
        partyMode: Boolean? = null,
        hasSpace: Boolean? = null,
        queueNonEmpty: Boolean? = null,
        hostDisplayName: String? = null
    ) {
        partyMode?.let { hostPartyMode = it }
        hasSpace?.let { hostHasSpace = it }
        queueNonEmpty?.let { hostQueueNonEmpty = it }
        hostDisplayName?.let { hostName = it }
        if (isHosting.get()) {
            appContext?.let { restartBleAdvertiser(it) }
        }
    }

    fun stopHosting() {
        isHosting.set(false)
        stopBleAdvertiser()
        announceJob?.cancel()
        announceJob = null
        hostSessionCode = null
    }

    /**
     * BLE ADVERTISER — non-connectable, LOW_LATENCY (~100 ms) so a tap lands
     * in one second. Manufacturer data = full room identity.
     */
    @SuppressLint("MissingPermission")
    private fun startBleAdvertiser(context: Context) {
        if (!canBleAdvertise(context)) {
            SLog.d("JoinFabric", "BLE advertise rail idle (no permission)")
            return
        }
        val adapter = bluetoothAdapter(context) ?: return
        // A non-null BluetoothLeAdvertiser IS the capability contract; no
        // separate boolean probe exists on BluetoothAdapter.
        if (adapter.bluetoothLeAdvertiser == null) {
            SLog.d("JoinFabric", "BLE advertising unsupported on this device")
            return
        }
        restartBleAdvertiser(context)
    }

    @SuppressLint("MissingPermission")
    private fun restartBleAdvertiser(context: Context) {
        if (!isHosting.get() || !canBleAdvertise(context)) return
        stopBleAdvertiser()
        val adapter = bluetoothAdapter(context) ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val code = hostSessionCode ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(
                BleJoinPayload.MANUFACTURER_ID,
                BleJoinPayload.encode(code, hostName, hostPartyMode, hostHasSpace, hostQueueNonEmpty) ?: return
            )
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                SLog.w("JoinFabric", "BLE advertise start failed: $errorCode — LAN announce rail continues")
                bleAdvertiser = null
            }
        }
        try {
            advertiser.startAdvertising(settings, data, callback)
            bleAdvertiser = callback
        } catch (e: Exception) {
            SLog.st("JoinFabric", "BLE advertiser start threw", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleAdvertiser() {
        val ctx = appContext
        val callback = bleAdvertiser as? AdvertiseCallback
        if (callback != null && ctx != null && canBleAdvertise(ctx)) {
            runCatching {
                bluetoothAdapter(ctx)?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            }
        }
        bleAdvertiser = null
    }

    /**
     * LAN room-announce loop: broadcast [BleJoinPayload] bytes on port 7778
     * every 1.5 s while hosting. Guests hear the full room identity and can
     * one-tap join with zero PIN entry.
     */
    private fun startAnnounceLoop() {
        if (announceJob?.isActive == true) return
        announceJob = scope.launch {
            val socket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(0))
                }
            } catch (e: Exception) {
                SLog.d("JoinFabric", "announce socket failed: ${e.message}")
                return@launch
            }
            try {
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                while (isActive && isHosting.get()) {
                    val code = hostSessionCode
                    if (code != null) {
                        val payload = BleJoinPayload.encode(code, hostName, hostPartyMode, hostHasSpace, hostQueueNonEmpty)
                        if (payload != null) {
                            try {
                                socket.send(DatagramPacket(payload, payload.size, broadcastAddr, LAN_ANNOUNCE_PORT))
                            } catch (_: Exception) { /* transient — next tick retries */ }
                        }
                    }
                    delay(LAN_ANNOUNCE_INTERVAL_MS)
                }
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    // ═══════════════ Sighting aggregation ═══════════════

    private fun publish(sighting: JoinSighting) {
        val now = System.currentTimeMillis()
        // Own room / own announcements never prompt.
        if (JamEngine.isActive() && sighting.sessionCode == hostSessionCode) return
        if (sighting.key in dismissedKeys) return
        val last = sightingSeen[sighting.key] ?: 0L
        if (now - last < SIGHTING_DEDUP_MS) return
        sightingSeen[sighting.key] = now
        _sightings.value =
            (_sightings.value.filter { now - it.atMs < SIGHTING_FRESH_MS } + sighting)
                .sortedByDescending { it.atMs }
                .take(MAX_PROMPTS)
    }

    /** User rejected the prompt — suppressed for this room's lifetime. */
    fun dismiss(sighting: JoinSighting) {
        dismissedKeys.add(sighting.key)
        _sightings.value = _sightings.value.filterNot { it.key == sighting.key }
    }

    /** Clears stale entries (called from the sweeper cadence in the UI). */
    fun prune() {
        val now = System.currentTimeMillis()
        _sightings.value = _sightings.value.filter { now - it.atMs < SIGHTING_FRESH_MS }
    }

    // ── Helpers ═════════════════════════════════════════════════════════════

    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private const val LAN_ANNOUNCE_PORT = 7778
    private const val LAN_ANNOUNCE_INTERVAL_MS = 1_500L
    private const val LAN_SIGHTING_THROTTLE_MS = 8_000L
    private const val SIGHTING_DEDUP_MS = 4_000L
    private const val SIGHTING_FRESH_MS = 30_000L
    private const val MAX_PROMPTS = 3
}
