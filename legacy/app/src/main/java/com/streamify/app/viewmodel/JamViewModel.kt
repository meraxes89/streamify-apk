package com.streamify.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamify.app.data.models.Track
import com.streamify.app.data.supabase.SupabaseClient
import com.streamify.app.jam.jamTrackFromJson
import com.streamify.app.jam.JamEngine
import com.streamify.app.jam.JamPairing
import com.streamify.app.jam.JamTopology
import com.streamify.app.jam.JoinFabric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed class JamUiState {
    object Idle : JamUiState()
    object Loading : JamUiState()
    /** [androidx.compose.runtime.Immutable] — pure value state for the jam surface. */
    @androidx.compose.runtime.Immutable
    data class Active(val session: JamEngine.JamSession, val isHost: Boolean) : JamUiState()
    @androidx.compose.runtime.Immutable
    data class Error(val message: String) : JamUiState()
}

/**
 * Guest-side PLL — Phase 1.4: delegates every decision to the native 1D
 * Kalman filter (`kalman_pll.rs`) over the skew-free synced clock.
 *
 * Decision bands (native):
 *   ≤150ms drift  → smooth speed scalar 0.98x–1.02x (inaudible micro-stretch)
 *   >150ms drift  → feed-forward HARD SEEK to host position + full state reset
 *   PAUSED regime → velocity clamped / state wiped (zero integral windup)
 *
 * Gap-repaired ticks arrive pre-synthesized by `tick_matrix`, so the filter
 * never mistakes a dropped packet for a stall.
 */
class JamPhaseLockedLoop(
    private val playerViewModel: PlayerViewModel
) {

    fun evaluatePhaseError(
        reportedPositionMs: Long,
        hostMonoMs: Long,
        durationMs: Long,
        @Suppress("UNUSED_PARAMETER") rttMs: Long = 60L
    ) {
        val nb = com.streamify.app.data.NativeBridge
        val nowSync = nb.getSyncedJamMonotonicMs()

        // Bound the measurement to the live track so a stale tick can never
        // seek past the end during transition races.
        val z = if (durationMs > 0) reportedPositionMs.coerceIn(0L, durationMs) else reportedPositionMs

        val decision = nb.kalmanPllDecide(z, nowSync, hostMonoMs, playing = true)

        when (decision[0].toInt()) {
            DECISION_SEEK -> {
                playerViewModel.isApplyingJamSync = true
                try {
                    playerViewModel.seekTo(decision[2])
                    playerViewModel.setPlaybackSpeed(1.0f)
                } finally {
                    playerViewModel.isApplyingJamSync = false
                }
                com.streamify.app.util.SLog.d(TAG_PLL, "feed-forward seek → ${decision[2]}ms")
            }
            DECISION_SPEED -> {
                val scalarMilli = decision[1]
                if (scalarMilli in 980..1020) {
                    playerViewModel.setPlaybackSpeed(scalarMilli / 1000f)
                    // Secondary path: micro PCM stretch when the processor is
                    // attached to a render chain (no-op on stock ExoPlayer).
                    com.streamify.app.media.audio.SyncAudioProcessor.setSpeedScalar(scalarMilli / 1000f)
                }
            }
            else -> {
                // HOLD: inside lock band.
                if (playerViewModel.playbackSpeed() != 1.0f) {
                    playerViewModel.setPlaybackSpeed(1.0f)
                    com.streamify.app.media.audio.SyncAudioProcessor.setSpeedScalar(1.0f)
                }
            }
        }
    }

    fun reset() {
        com.streamify.app.data.NativeBridge.kalmanPllReset()
        playerViewModel.setPlaybackSpeed(1.0f)
    }

    companion object {
        private const val TAG_PLL = "KalmanPll"
        private const val DECISION_SEEK = 2
        private const val DECISION_SPEED = 1
    }
}

/**
 * JAM VIEWMODEL v4 — thin executor over [JamEngine] (SERVERLESS).
 *
 * The protocol brain lives in the process-wide engine singleton so sessions
 * survive navigation; this class binds it to a live PlayerViewModel, executes
 * protocol commands against playback, and drives the presentation state.
 *
 * v4 decoupling: room lifecycle mirrors the ENGINE's serverless session flow
 * (no Supabase rows); inbound frames are collected by the engine itself off
 * the P2P mesh; the join handshake asks the live leader for state instead of
 * fetching a database snapshot.
 */
class JamViewModel(
    private val appContext: android.content.Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<JamUiState>(JamUiState.Idle)
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    // Canonical shared queue mirror (fed by engine protocol decisions).
    val jamQueue: StateFlow<List<Track>> = JamEngine.queue
    val members: StateFlow<List<JamEngine.Member>> = JamEngine.members
    val connStatus: StateFlow<JamEngine.ConnStatus> = JamEngine.connStatus
    val policy: StateFlow<JamEngine.ControlPolicy> = JamEngine.policy

    // Mesh radar + acoustic gauge feeds for the Jam UI.
    val meshPeers: StateFlow<List<JamEngine.MeshPeer>> = JamEngine.meshPeers
    val syncTelemetry: StateFlow<JamEngine.SyncTelemetry> = JamEngine.syncTelemetry

    // ═══ Phase 1: Topology (Gap #13) + Governance (Gaps #14/#18) ═══
    val topology: StateFlow<JamTopology> = JamEngine.topology
    val governanceRows: StateFlow<List<com.streamify.app.jam.JamGovernance.MemberAcl>> = JamEngine.governanceRows
    val memberReports: StateFlow<List<com.streamify.app.jam.JamGovernance.MemberReport>> = JamEngine.memberReports

    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()

    private val _rosterCount = MutableStateFlow(0)
    val rosterCount: StateFlow<Int> = _rosterCount.asStateFlow()

    /** RTT lookup for roster health chips (radar by nonce). */
    fun rttForNonce(nonce: String): Float =
        JamEngine.meshPeers.value.firstOrNull { it.nonce == nonce }?.rttMs ?: -1f

    private var pll: JamPhaseLockedLoop? = null
    private var attachedPlayer: PlayerViewModel? = null
    private var executorsStarted = false

    init {
        // Room lifecycle mirrors the ENGINE's serverless session (no DB row).
        viewModelScope.launch {
            JamEngine.activeSession.collect { session ->
                if (session != null) {
                    _uiState.value = JamUiState.Active(session, JamEngine.isHost())
                    com.streamify.app.media.audio.SyncAudioProcessor.setJamSyncActive(true)
                } else if (_uiState.value is JamUiState.Active) {
                    com.streamify.app.media.audio.SyncAudioProcessor.setJamSyncActive(false)
                    _uiState.value = JamUiState.Idle
                }
            }
        }
        // Phase 1: host role + roster size mirrors for the governance UI.
        viewModelScope.launch {
            JamEngine.members.collect { list ->
                _rosterCount.value = list.size
            }
        }
        viewModelScope.launch {
            JamEngine.topology.collect { _topology ->
                // Hosting rails mirror live room state (party/full/queue)
                // into BLE adverts + LAN announcements.
                if (JamEngine.isActive()) {
                    JoinFabric.updateHostingState(
                        partyMode = _topology.isPartyMode,
                        hasSpace = _rosterCount.value < 32,
                        queueNonEmpty = JamEngine.queue.value.isNotEmpty()
                    )
                }
            }
        }
        viewModelScope.launch {
            JamEngine.meshPeers.collect { _peers ->
                _isHost.value = JamEngine.isHost()
            }
        }
        // Identity enrichment (local cache read only — never a server call):
        // the roster shows the profile name/avatar when one is signed in.
        SupabaseClient.currentUser.value?.let { user ->
            JamEngine.myDisplayName = user.displayName ?: "Listener"
            JamEngine.myAvatarUrl = user.avatarUrl
        }
    }

    fun startJam(currentTrack: Track?, currentPosition: Long) {
        if (currentTrack == null) {
            _uiState.value = JamUiState.Error("Play a track before starting a Jam session")
            return
        }
        viewModelScope.launch {
            _uiState.value = JamUiState.Loading
            val session = JamEngine.createServerlessRoom()
            _uiState.value = JamUiState.Active(session, isHost = true)
            JamEngine.noteSelf()
            // Gap #12: the fresh host immediately lights the join-fabric rails
            // (BLE advertiser + LAN announcements) so nearby friends see the
            // one-tap prompt within a second.
            JoinFabric.startHosting(session.sessionCode, JamEngine.myDisplayName)
        }
    }

    fun joinJam(code: String, playerViewModel: PlayerViewModel) {
        val clean = code.trim().uppercase()
        viewModelScope.launch {
            _uiState.value = JamUiState.Loading
            // Accepts the full pairing payload (QR / NFC / paste) OR a bare PIN.
            val ok = JamEngine.joinServerlessRoom(clean)
            if (!ok) {
                _uiState.value = JamUiState.Error("Could not join — check the code or pairing link")
                return@launch
            }
            attachedPlayer = playerViewModel
            pll = JamPhaseLockedLoop(playerViewModel)
            startExecutors(playerViewModel)
            // LOCKSTEP HANDSHAKE: adopt the leader's exact position via the
            // mesh (STATE_REQ → extrapolated TRACK_CHANGE).
            performHandshake()
        }
    }

    /** Explicit detach used by the Leave button (never by lifecycle death). */
    fun leaveJam(endForEveryone: Boolean = false) {
        JamEngine.leaveSession(endForEveryone)
        JoinFabric.stopHosting()
        pll?.reset()
        pll = null
        _uiState.value = JamUiState.Idle
    }

    // ═══ Phase 1: Party Mode (Gap #13) + Governance (Gaps #14/#18) ═══

    /** Host toggle: Party Mode (single renderer) ⇄ Multi-render synced. */
    fun togglePartyMode() {
        val target = if (JamEngine.topology.value == JamTopology.MULTI_RENDER)
            JamTopology.SINGLE_RENDER else JamTopology.MULTI_RENDER
        JamEngine.setTopology(target)
    }

    /** Host: per-member playback control permission. */
    fun setMemberAcl(nonce: String, allowControl: Boolean? = null, allowVolume: Boolean? = null) {
        JamEngine.setMemberAcl(nonce, allowControl, allowVolume)
    }

    /** Host: co-host promotion / demotion. */
    fun setMemberRole(nonce: String, role: com.streamify.app.jam.JamGovernance.Role) {
        JamEngine.setMemberRole(nonce, role)
    }

    /** Host: kick + block for the room lifetime. */
    fun kickMember(nonce: String) {
        JamEngine.kickMember(nonce)
    }

    /** Host: ban without removal. */
    fun banMember(nonce: String) {
        JamEngine.banMember(nonce)
    }

    /** Guest: report a disruptive member to the host. */
    fun reportMember(nonce: String, reason: com.streamify.app.jam.JamGovernance.ReportReason) {
        JamEngine.reportMember(nonce, reason)
    }

    /** Host: clear the report inbox. */
    fun clearReports() {
        JamEngine.clearMemberReports()
    }

    // ═══════════════ Shared queue (routed through the engine protocol) ═══════════════

    fun addToJamQueue(track: Track) {
        val name = JamEngine.myDisplayName.ifBlank { "Someone" }
        JamEngine.addToQueue(track, addedByName = name)
    }

    fun removeFromJamQueue(track: Track) {
        JamEngine.removeFromQueue(track)
    }

    /** Fractional-index reorder of the shared queue (Gap #11). */
    fun moveInJamQueue(track: Track, toPosition: Int) {
        JamEngine.moveInQueue(track, toPosition)
    }

    // ── Gap #37: democratic queue votes ─────────────────────────────────

    /** addOpId → voter nonces; recomposes queue rows the moment a vote lands. */
    val queueVotes: StateFlow<Map<Long, Set<String>>> = JamEngine.queueVotes

    fun voteCountFor(track: Track): Int = JamEngine.voteCountFor(track)

    fun hasVotedFor(track: Track): Boolean = JamEngine.hasVotedFor(track)

    fun castUpvote(track: Track): Boolean = JamEngine.castUpvote(track)

    fun cycleControlPolicy() {
        val next = if (JamEngine.policy.value == JamEngine.ControlPolicy.EVERYONE)
            JamEngine.ControlPolicy.HOST_ONLY else JamEngine.ControlPolicy.EVERYONE
        JamEngine.setPolicy(next)
    }

    fun inviteShareText(): String {
        val session = (uiState.value as? JamUiState.Active)?.session ?: return ""
        val payload = JamPairing.encodePayload(session)
        return "🎵 Join my Streamify Jam!\nCode: ${session.sessionCode}\n" +
            "Offline pairing: $payload\nOr tap: streamify://jam/${session.sessionCode}"
    }

    // ═══════════════ Protocol executors ═══════════════

    private fun startExecutors(playerViewModel: PlayerViewModel) {
        if (executorsStarted) return
        executorsStarted = true
        pll = pll ?: JamPhaseLockedLoop(playerViewModel)

        // PHASE 4: engine-owned FGS loops read playhead state via probes.
        JamEngine.attachPlaybackProbe {
            val ctrl = playerViewModel.getController()
                ?: return@attachPlaybackProbe longArrayOf(0L, 0L, 0L)
            val pos = ctrl.currentPosition.coerceAtLeast(0L)
            val dur = ctrl.duration.takeIf { it > 0 } ?: 0L
            val playing = if (ctrl.isPlaying) 1L else 0L
            longArrayOf(pos, dur, playing)
        }

        // 1. Execute protocol decisions against live playback.
        viewModelScope.launch {
            JamEngine.commands.collect { cmd ->
                val pvm = attachedPlayer ?: return@collect
                when (cmd) {
                    is JamEngine.Command.ApplyTrack -> {
                        pvm.isApplyingJamSync = true
                        try {
                            // PHASE 3 (P9): playTrack resolves async; suspend on
                            // Media3 STATE_READY instead of a magic sleep, then
                            // pin position — seeks can never land on the
                            // previous item anymore.
                            val ctrl = pvm.getController()
                            if (ctrl != null) {
                                com.streamify.app.jam.PlaybackReadyGate.awaitReadyThenSeek(
                                    player = ctrl,
                                    positionMs = cmd.positionMs,
                                    play = cmd.play,
                                    tag = "ApplyTrack:${cmd.track.title.take(16)}"
                                )
                                com.streamify.app.jam.JamEngine.markRegimeChange()
                            } else {
                                pvm.playTrack(cmd.track, listOf(cmd.track), autoHydrateRadio = false)
                            }
                        } finally {
                            pvm.isApplyingJamSync = false
                        }
                    }
                    is JamEngine.Command.ApplySeek -> {
                        pvm.isApplyingJamSync = true
                        pvm.seekTo(cmd.positionMs)
                        pvm.isApplyingJamSync = false
                    }
                    is JamEngine.Command.ApplyPlayPause -> {
                        pvm.isApplyingJamSync = true
                        if (cmd.play) pvm.play() else pvm.pause()
                        pvm.isApplyingJamSync = false
                    }
                    is JamEngine.Command.ApplyPllTick -> {
                        pll?.evaluatePhaseError(
                            reportedPositionMs = cmd.hostPositionMs,
                            hostMonoMs = cmd.hostEpochMs,
                            durationMs = cmd.durationMs
                        )
                        if (pvm.playerState.value.isPlaying != cmd.play) {
                            pvm.isApplyingJamSync = true
                            if (cmd.play) pvm.play() else pvm.pause()
                            pvm.isApplyingJamSync = false
                        }
                    }
                    is JamEngine.Command.ApplyRenderSuppression -> {
                        // Party Mode audio discipline (Gap #13): guests mute
                        // LOCAL output — the host speaker is the renderer.
                        // Volume-level muting keeps the PLL + decoder warm,
                        // so flipping back to MULTI_RENDER re-locks audio
                        // with zero handshake latency.
                        pvm.setJamRenderSuppressed(cmd.suppressed)
                    }
                    JamEngine.Command.SessionEnded -> {
                        UiEventBus.emitEvent(UiEvent.ShowSnackbar("Jam ended by host"))
                    }
                    JamEngine.Command.Rehandshake -> {
                        // Authority changed hands (Death Pivot / partition
                        // heal): re-adopt the new leader's state.
                        if (JamEngine.isActive() && !JamEngine.isHost()) performHandshake()
                    }
                }
            }
        }

        // 2. Inbound frames: the ENGINE collects NativeBridge.incomingFrames
        // itself (serverless mesh ingress) — no Supabase channel here anymore.

        // 3.6 Zero-gap handoff (P3): leader NEXT_IS → guest shadow pre-buffer.
        JamEngine.onNextIsListener = { nextTrack ->
            com.streamify.app.media.cache.PredictivePreBufferManager.JamPreBuffer.notifyNextIs(nextTrack)
        }
    }

    /**
     * Serverless join/reconnect handshake: ask the LIVE LEADER where the room
     * is (extrapolated through the synced clock) and adopt that exact state.
     * The leader's TRACK_CHANGE arrives as a normal protocol command, so the
     * executor path above applies it with the readiness gate.
     */
    private suspend fun performHandshake() {
        val pvm = attachedPlayer ?: return
        val snap = JamEngine.awaitLeaderHandshake(timeoutMs = 5_000L) ?: return
        val track = jamTrackFromJson(snap.trackJson) ?: return

        val current = pvm.playerState.value.currentTrack
        val sameTrack = current?.title?.equals(track.title, ignoreCase = true) == true &&
                current.artist.equals(track.artist, ignoreCase = true)

        pvm.isApplyingJamSync = true
        try {
            if (!sameTrack) {
                pvm.playTrack(track, listOf(track), autoHydrateRadio = false)
                // PHASE 3 (P9): event-driven readiness replaces the sleep.
                val ctrl = pvm.getController()
                if (ctrl != null) {
                    com.streamify.app.jam.PlaybackReadyGate.awaitReadyThenSeek(
                        player = ctrl,
                        positionMs = snap.positionMs,
                        play = snap.isPlaying,
                        tag = "Handshake"
                    )
                }
            } else {
                if (snap.positionMs > 0L) pvm.seekTo(snap.positionMs)
                if (snap.isPlaying != pvm.playerState.value.isPlaying) {
                    if (snap.isPlaying) pvm.play() else pvm.pause()
                }
            }
        } finally {
            pvm.isApplyingJamSync = false
        }
    }
}
