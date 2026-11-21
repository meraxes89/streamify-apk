package com.streamify.app.connect

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Local playback engine seam the coordinator drives during transfers.
 * Implemented by the app shell (PlayerViewModel wiring) over the ExoPlayer
 * controller; faked in the JVM suite.
 */
interface LocalPlaybackHooks {
    /**
     * Put the local engine into silent-remote-controller mode: pause/mute
     * local rendering while the queue and clock stay loaded, so a flip back
     * to this phone is a seek — never a reload.
     */
    fun enterSilentController()

    /**
     * Exit silent mode and resume local rendering at [positionMs] with the
     * given play intent (used both by "Transfer to this phone" and by
     * automatic fallback when a remote sink dies).
     */
    fun exitSilentController(positionMs: Long, play: Boolean)
}

/** No-op hooks — used when the coordinator is exercised without a player. */
object NoopLocalPlaybackHooks : LocalPlaybackHooks {
    override fun enterSilentController() {}
    override fun exitSilentController(positionMs: Long, play: Boolean) {}
}

/**
 * ConnectSessionCoordinator (Gap #52) — the single state machine behind
 * every "Listening on …" surface.
 *
 * Guarantees:
 *  • Transfers are position+queue preserving: the snapshot fingerprint is
 *    captured before the handshake and the receiving route resumes at the
 *    exact donor position ("Transfer Playback" never restarts the track).
 *  • The local ExoPlayer degrades to a SILENT remote controller while a
 *    sink renders — scrub/seek/play-pause/volume/reorders are forwarded
 *    via the active [ConnectGateway] instead of applied locally.
 *  • A dead route (Wi-Fi drop, speaker sleep) triggers automatic fallback:
 *    local rendering resumes at the last sink-reported position.
 *
 * Pure Kotlin + injectable seams (gateway factory, hooks, registry) — the
 * full matrix is covered by ConnectSessionCoordinatorTest on the JVM.
 */
class ConnectSessionCoordinator(
    private val gatewayFor: (ConnectDevice) -> ConnectGateway = { LoopbackConnectGateway() },
    private val hooks: LocalPlaybackHooks = NoopLocalPlaybackHooks,
    private val registry: ConnectDeviceRegistry? = null,
    private val clockMs: () -> Long = System::currentTimeMillis
) {

    private val _state = MutableStateFlow(ConnectSessionState())
    val state: StateFlow<ConnectSessionState> = _state.asStateFlow()

    /** Bounded transition log for debugging + test assertions. */
    private val _history = MutableStateFlow<List<ConnectSessionState>>(emptyList())
    val history: StateFlow<List<ConnectSessionState>> = _history.asStateFlow()

    private val transferMutex = Mutex()

    /** Gateway bound to the currently active remote sink, if any. */
    private var activeGateway: ConnectGateway? = null

    /** Collector job for the active gateway's event stream, if attached. */
    private var eventJob: kotlinx.coroutines.Job? = null

    /**
     * Begin collecting events from the active gateway. Called once from the
     * app shell with a long-lived scope; tests drive [onGatewayEvent]
     * directly instead. Gateway identity changes (sink hops) transparently
     * swap the collector job.
     */
    fun attach(scope: CoroutineScope) {
        scope.launch {
            var lastBoundId: String? = null
            state.collect { s ->
                val gw = if (s.isRemoteActive) {
                    activeGateway?.takeIf { it.boundDeviceId == s.activeDevice?.id }
                } else {
                    null
                }
                val boundId = gw?.boundDeviceId
                if (boundId != lastBoundId) {
                    lastBoundId = boundId
                    eventJob?.cancel()
                    eventJob = null
                    if (gw != null) {
                        eventJob = scope.launch { gw.events.collect { onGatewayEvent(it) } }
                    }
                }
            }
        }
    }

    /**
     * One-tap transfer of the live session to [target].
     *
     * @param snapshot fingerprint of the session at the tap moment.
     * @return the resulting session state (success or failure with reason).
     */
    suspend fun transferTo(target: ConnectDevice, snapshot: PlaybackSnapshot): ConnectSessionState =
        transferMutex.withLock {
            val current = _state.value
            if (current.phase == ConnectSessionPhase.CONNECTING) {
                return@withLock fail(current, "A transfer is already in flight")
            }

            // ── Transfer back to THIS phone ─────────────────────────────
            if (target.isLocal) {
                return@withLock finishLocalTransfer(current, snapshot)
            }

            // ── Transfer to a remote sink ───────────────────────────────
            registry?.markStatus(target.id, DeviceLinkStatus.CONNECTING)
            publish(current.copy(phase = ConnectSessionPhase.CONNECTING, activeDevice = target))

            val gateway = gatewayFor(target)
            val resumePosition = if (current.isRemoteActive) {
                // Sink-to-sink hop: the donor's freshest reported position
                // wins over the (now stale) local snapshot.
                maxOf(current.lastRemotePositionMs, snapshot.positionMs)
            } else {
                snapshot.positionMs
            }
            val handoff = snapshot.copy(positionMs = resumePosition)

            val accepted = runCatching { gateway.connect(target, handoff) }.getOrDefault(false)
            if (!accepted) {
                registry?.markStatus(target.id, DeviceLinkStatus.IDLE)
                // A failed hop rolls back to the PREVIOUS regime: if another
                // sink was already rendering it keeps rendering untouched
                // (no disconnect, no local unmute) — only the error surfaces.
                // From LOCAL_ONLY we simply stay local.
                publish(current.copy(lastError = "Could not reach ${target.name}"))
                return@withLock _state.value
            }

            // Releasing the previous sink is graceful: it keeps playing
            // until the new one takes over (double-glitch-free handoff).
            activeGateway?.let { old ->
                val oldId = old.boundDeviceId
                runCatching { old.disconnect() }
                if (!oldId.isNullOrBlank()) {
                    registry?.markStatus(oldId, DeviceLinkStatus.IDLE)
                }
            }
            activeGateway = gateway
            registry?.markStatus(target.id, DeviceLinkStatus.ACTIVE)

            hooks.enterSilentController()
            publish(
                _state.value.copy(
                    phase = ConnectSessionPhase.REMOTE_ACTIVE,
                    activeDevice = target,
                    snapshotAtTransfer = handoff,
                    lastTransferAtMs = clockMs(),
                    lastRemotePositionMs = resumePosition,
                    remoteIsPlaying = handoff.isPlaying,
                    lastError = null
                )
            )
            _state.value
        }

    /**
     * Restore LOCAL_ONLY rendering. MUST be called while [transferMutex]
     * is held (not reentrant). Resumes at the freshest known position —
     * the sink-reported one during remote sessions, the tap snapshot
     * otherwise — and preserves the donor's play intent.
     */
    private suspend fun finishLocalTransfer(
        current: ConnectSessionState,
        snapshot: PlaybackSnapshot,
        error: String? = null
    ): ConnectSessionState {
        val resumeAt = if (current.isRemoteActive) {
            maxOf(current.lastRemotePositionMs, snapshot.positionMs)
        } else {
            snapshot.positionMs
        }
        val play = if (current.isRemoteActive) current.remoteIsPlaying else snapshot.isPlaying

        activeGateway?.let { gw ->
            val oldId = gw.boundDeviceId
            runCatching { gw.disconnect() }
            if (!oldId.isNullOrBlank()) {
                registry?.markStatus(oldId, DeviceLinkStatus.IDLE)
            }
        }
        activeGateway = null

        hooks.exitSilentController(resumeAt, play)
        registry?.markStatus(ConnectDevice.LOCAL_DEVICE_ID, DeviceLinkStatus.IDLE)
        publish(
            current.copy(
                phase = ConnectSessionPhase.LOCAL_ONLY,
                activeDevice = null,
                snapshotAtTransfer = null,
                remoteIsPlaying = false,
                lastError = error
            )
        )
        return _state.value
    }

    /**
     * Dispatch one player command through the Connect discipline.
     *
     * @return true when the command was consumed by the remote sink;
     *   false = the caller must execute it on the LOCAL engine (covers
     *   LOCAL_ONLY mode, loopback targets and gateway delivery failures).
     *   Volume commands on OS-owned-gain routes are ALWAYS declined to the
     *   local path so hardware master gain can never double-attenuate.
     */
    suspend fun dispatch(command: ConnectCommand): Boolean {
        val s = _state.value
        if (!s.isRemoteActive) return false
        val device = s.activeDevice ?: return false
        if (device.isLocal) return false

        if (command is ConnectCommand.SetVolume) {
            val acl = ConnectVolumePolicy.forRoute(device.kind)
            if (acl.guestLocked || !acl.remoteSliderShared) return false
        }

        val gateway = activeGateway ?: return false
        return runCatching { gateway.send(command) }.getOrDefault(false)
    }

    /** Sink event ingestion (wired by [attach]; driven directly by tests). */
    fun onGatewayEvent(event: ConnectGatewayEvent) {
        val s = _state.value
        if (!s.isRemoteActive && event !is ConnectGatewayEvent.Disconnected) return
        when (event) {
            is ConnectGatewayEvent.RemotePosition -> {
                publish(s.copy(lastRemotePositionMs = event.positionMs, remoteIsPlaying = event.isPlaying))
            }
            is ConnectGatewayEvent.RemoteVolume -> {
                // Another controller on the same route moved the shared
                // slider; recorded for UI sync, never pushed to local gain.
                publish(s.copy(snapshotAtTransfer = s.snapshotAtTransfer?.copy(volume = event.volume)))
            }
            is ConnectGatewayEvent.Disconnected -> {
                if (!s.isRemoteActive) return
                handleRemoteDeath(s)
            }
        }
    }

    private fun handleRemoteDeath(s: ConnectSessionState) {
        publish(s.copy(phase = ConnectSessionPhase.FALLING_BACK, lastError = "${s.activeDeviceName ?: "Remote device"} disconnected"))
        activeGateway = null
        registry?.markStatus(s.activeDevice?.id ?: "", DeviceLinkStatus.LOST)
        // Automatic fallback: local rendering resumes at the last
        // sink-reported position — the listener never loses the song.
        hooks.exitSilentController(s.lastRemotePositionMs, s.remoteIsPlaying)
        publish(
            _state.value.copy(
                phase = ConnectSessionPhase.LOCAL_ONLY,
                activeDevice = null,
                snapshotAtTransfer = null,
                remoteIsPlaying = false
            )
        )
    }

    private fun publish(next: ConnectSessionState) {
        _state.value = next
        _history.value = (_history.value + next).takeLast(MAX_HISTORY)
    }

    private fun fail(current: ConnectSessionState, message: String): ConnectSessionState {
        publish(current.copy(lastError = message))
        return _state.value
    }

    companion object {
        private const val MAX_HISTORY = 24
    }
}
