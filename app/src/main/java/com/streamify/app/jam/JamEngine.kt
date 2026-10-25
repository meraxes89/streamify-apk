package com.streamify.app.jam

import com.streamify.app.data.NativeBridge
import com.streamify.app.data.models.Track
import com.streamify.app.data.repository.TrackRepository
import com.streamify.app.util.SLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JAM LOCKSTEP ENGINE v4 — SERVERLESS EDITION (zero Postgres, zero RPC)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * The nervous system of Streamify's serverless listening engine. Every
 * PostgreSQL lease, RPC and realtime channel from v2/v3 is gone; authority,
 * state transfer and clock discipline now live entirely on the P2P mesh:
 *
 *  TRANSPORT   — all egress is [JamWire] binary frames over
 *               [NativeBridge.p2pBroadcast] / [NativeBridge.p2pSendToPeer];
 *               all ingress is [NativeBridge.incomingFrames] (a SharedFlow
 *               collecting the raw native upcall). Queue mutations ride the
 *               packed 48-byte [JamOpWire] — zero serialization overhead.
 *
 *  AUTHORITY   — [LeaderlessElectionEngine]: Blake3 deterministic minimization
 *               + the Death Pivot. A host whose battery dies is detected by
 *               1 200 ms of tick silence; every survivor independently
 *               computes the same successor and the new host continues the
 *               playback trajectory with zero audio pause.
 *
 *  STATE       — [MerkleStateReconciler]: late joiners exchange a 32-byte
 *               Merkle root and traverse only mismatching branches, converging
 *               on the shared queue in single-digit milliseconds.
 *
 *  IDENTITY    — rooms are created locally: a 6-char PIN plus an ephemeral
 *               32-byte pairing key distributed offline (QR / NFC / paste).
 *               No server ever learns a room existed.
 *
 * Protocol invariants preserved from v2 (they were correct and are load-bearing):
 *
 *  1. HOST AUTHORITY — only the leader emits TICK / control intents that
 *     mutate room playback state. Guests emit REQUESTS; the leader ratifies.
 *  2. EPOCH ORDERING — every leader intent carries a monotonically increasing
 *     epoch; receivers drop anything older than the newest applied regime.
 *  3. SENDER IDENTITY — every frame carries the 8-char device nonce; own
 *     echoes are ignored; policy is enforced on who may control playback.
 *  4. JOIN HANDSHAKE — authoritative state comes from the live leader's
 *     STATE response (extrapolated through the synced clock), never guessed.
 *  5. LOSSLESS IDENTITY — track payloads carry ytmVideoId/isrc so guests pin
 *     the exact same upload.
 *  6. CRDT QUEUE — every mutation is a sealed 48-byte op folded natively and
 *     journaled to the WAL outbox; late joiners converge via the Merkle DAG.
 *  7. HOST-DRIVEN AUTO-ADVANCE — only the leader advances the room; guests
 *     deliberately idle awaiting TRACK_CHANGE.
 */
object JamEngine {

    // ═══════════════ Types ═══════════════

    /** [androidx.compose.runtime.Immutable] — pure value row for the roster UI. */
    @androidx.compose.runtime.Immutable
    data class Member(
        val userId: String,
        val name: String,
        val avatarUrl: String?,
        val isHost: Boolean,
        val lastSeenMs: Long,
        val nonce: String = "",
        val role: JamGovernance.Role = JamGovernance.Role.MEMBER,
        val allowControl: Boolean = true,
        val allowVolume: Boolean = true
    ) {
        val isCoHost: Boolean get() = role == JamGovernance.Role.CO_HOST
    }

    enum class ConnStatus { LIVE, DEGRADED, OFFLINE }
    enum class ControlPolicy { HOST_ONLY, EVERYONE }

    /**
     * Serverless room descriptor — replaces the Supabase listening_sessions
     * row. Lives only on the mesh; the pairing key is handed out offline.
     * [androidx.compose.runtime.Immutable] — pure value row for the UI.
     */
    @androidx.compose.runtime.Immutable
    data class JamSession(
        val id: String,
        val sessionCode: String,
        val pairingKeyHex: String,
        val hostNonce: String,
        val createdAtMs: Long
    )

    /** One node on the mesh radar (UI topology view).
     * [androidx.compose.runtime.Immutable] — pure value row for the radar UI. */
    @androidx.compose.runtime.Immutable
    data class MeshPeer(
        val peerIdHex: String,
        val nonce: String,
        val name: String,
        val avatarUrl: String?,
        val isHost: Boolean,
        val linkType: Int,
        val rttMs: Float,
        val lastSeenMs: Long
    )

    /** Live sync health surfaced to the acoustic gauge UI.
     * [androidx.compose.runtime.Immutable] — pure value row for the gauge UI. */
    @androidx.compose.runtime.Immutable
    data class SyncTelemetry(
        val clockDriftNanos: Long = 0L,
        val rttNanos: Long = 0L,
        val ptpLocked: Boolean = false,
        val resamplerRateScalar: Float = 1.0f,
        val merkleConvergenceNanos: Long = -1L,
        val authorityEpoch: Long = 0L,
        val electionState: String = "BOOTSTRAP"
    )

    /** Authoritative state a joining guest adopts (replaces the DB snapshot). */
    data class HandshakeSnapshot(
        val trackJson: JSONObject?,
        val positionMs: Long,
        val isPlaying: Boolean,
        val queue: List<Track>,
        val hostNonce: String
    )

    /** Decisions made by the protocol brain; executed by the player owner. */
    sealed class Command {
        data class ApplyTrack(val track: Track, val positionMs: Long, val play: Boolean) : Command()
        data class ApplySeek(val positionMs: Long) : Command()
        data class ApplyPlayPause(val play: Boolean) : Command()
        data class ApplyPllTick(
            val hostPositionMs: Long,
            val hostEpochMs: Long,
            val durationMs: Long,
            val play: Boolean
        ) : Command()
        /**
         * Party Mode audio discipline: guests in SINGLE_RENDER suppress
         * local audio output (the host speaker is the only renderer) while
         * the PLL keeps running silently for an instant flip back.
         */
        data class ApplyRenderSuppression(val suppressed: Boolean) : Command()
        object SessionEnded : Command()
        object Rehandshake : Command()
    }

    /** Live-player facade attached by the app shell. */
    interface Bridge {
        fun loadTrack(track: Track, positionMs: Long, play: Boolean)
        fun setPlaying(play: Boolean)
    }

    // ═══════════════ State ═══════════════

    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members.asStateFlow()

    private val _connStatus = MutableStateFlow(ConnStatus.OFFLINE)
    val connStatus: StateFlow<ConnStatus> = _connStatus.asStateFlow()

    private val _policy = MutableStateFlow(ControlPolicy.EVERYONE)
    val policy: StateFlow<ControlPolicy> = _policy.asStateFlow()

    // ── Phase 1: Topology (Gap #13) + Governance (Gaps #14/#18) ──────────

    /** Room render topology — MULTI_RENDER default, SINGLE_RENDER = Party. */
    private val _topology = MutableStateFlow(JamTopology.MULTI_RENDER)
    val topology: StateFlow<JamTopology> = _topology.asStateFlow()

    /** Highest TOPOLOGY regime epoch applied — stale-frame guard. */
    @Volatile private var latestTopologyEpoch = 0L

    /** Governance table: member ACLs, block list, report inbox. */
    val governance = JamGovernance.Table()

    private val _governanceRows = MutableStateFlow<List<JamGovernance.MemberAcl>>(emptyList())
    val governanceRows: StateFlow<List<JamGovernance.MemberAcl>> = _governanceRows.asStateFlow()

    private val _memberReports = MutableStateFlow<List<JamGovernance.MemberReport>>(emptyList())
    val memberReports: StateFlow<List<JamGovernance.MemberReport>> = _memberReports.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    // ── Gap #37: democratic queue votes ─────────────────────────────────
    /** Queue-element ADD op id → live voter nonces (converges via VOTE gossip). */
    private val _queueVotes = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    val queueVotes: StateFlow<Map<Long, Set<String>>> = _queueVotes.asStateFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 64)
    val commands: SharedFlow<Command> = _commands

    private val _session = MutableStateFlow<JamSession?>(null)
    val activeSession: StateFlow<JamSession?> = _session.asStateFlow()

    private val _meshPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val meshPeers: StateFlow<List<MeshPeer>> = _meshPeers.asStateFlow()

    private val _syncTelemetry = MutableStateFlow(SyncTelemetry())
    val syncTelemetry: StateFlow<SyncTelemetry> = _syncTelemetry.asStateFlow()

    /** trackId -> "Added by X" attribution, learned from queue payloads. */
    private val addedByMap = ConcurrentHashMap<String, String>()
    fun addedBy(trackId: Int): String? = addedByMap[trackId.toString()]

    /** Deep-link invite consumed by the Jam screen on open (PIN form). */
    @Volatile var pendingInviteCode: String? = null

    /** Full pairing URI (QR/NFC/paste) — richer than the PIN-only invite. */
    @Volatile var pendingPairingPayload: String? = null

    /** Non-suspend signal so the shell can auto-navigate into the room. */
    val inviteNavigationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Per-process device nonce: two devices signed into one account stay distinct. */
    val deviceId: String = UUID.randomUUID().toString().take(8)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val epochCounter = AtomicLong(0)

    // ── Sync-core state ────────────────────────────────────────────────────

    /** Host-only: monotonic wire sequence for lossless tick ordering. */
    private val tickSeqCounter = AtomicLong(0)

    /** Synced-clock stamp of the last playback regime change (play/pause/seek/track). */
    @Volatile private var lastRegimeChangeSyncedMs = 0L

    /** Guest-side: track announced by host NEXT_IS, awaiting zero-gap handoff. */
    @Volatile var pendingNextIsTrack: Track? = null
        private set

    /** Guest-side: last SYNC_REQ fire time (synced domain) — retry pacing. */
    @Volatile private var lastSyncReqAtMs = 0L

    /** Host-side: identity of the queue head already announced via NEXT_IS. */
    @Volatile var announcedNextId: String? = null

    private val syncProbeCounter = AtomicLong(0)

    // ── CRDT + outbox state ────────────────────────────────────────────────

    /** Element identity index: cad_id -> add_op_id. */
    private val elementIndex = ConcurrentHashMap<Long, Long>()

    /** cad_id -> Track object cache for rebuilding the UI view post-fold. */
    private val cadTrackCache = ConcurrentHashMap<Long, Track>()

    /** cad_id -> live fractional index from the latest authoritative fold. */
    private val fracByCad = ConcurrentHashMap<Long, Double>()

    @Volatile var outboxReady: Boolean = false
        private set

    /** Last verified host tick — the Death Pivot extrapolation source. */
    @Volatile private var lastVerifiedHostTick: LeaderlessElectionEngine.HostTickSource.Snapshot? = null

    @Volatile private var latestAppliedEpoch = Long.MIN_VALUE
    @Volatile private var lastHostTickAt = 0L
    @Volatile private var lastSelfHeartbeatAt = 0L
    @Volatile private var sessionEndedLocally = false
    private var sweeperJob: Job? = null
    private var ingressJob: Job? = null
    private val consensusJobs = mutableListOf<Job>()

    /** device nonce -> userId, learned from PRESENCE; drives authority checks. */
    private val deviceUserMap = ConcurrentHashMap<String, String>()

    /** nonce -> radar view row (link type, rtt, presence freshness). */
    private val peerRadar = ConcurrentHashMap<String, MeshPeer>()

    // ═══════════════ Identity (serverless: no auth backend required) ═══════

    /** Locally persisted stable user identity (SharedPreferences). */
    @Volatile private var cachedLocalUserId: String? = null

    fun myUserId(): String {
        cachedLocalUserId?.let { return it }
        val generated = "local-" + UUID.randomUUID().toString().take(12)
        cachedLocalUserId = generated
        runCatching {
            val ctx = TrackRepository.appContext ?: return generated
            val prefs = ctx.getSharedPreferences("jam_identity", android.content.Context.MODE_PRIVATE)
            val stored = prefs.getString("user_id", null)
            if (stored != null) {
                cachedLocalUserId = stored
                return stored
            }
            prefs.edit().putString("user_id", generated).apply()
        }
        return generated
    }

    /** Display identity — enriched by the app shell when a profile is cached. */
    @Volatile var myDisplayName: String = "Listener"
    @Volatile var myAvatarUrl: String? = null

    fun isActive(): Boolean = _session.value != null && !sessionEndedLocally

    fun isHost(): Boolean = election?.isLeader == true

    fun activeSession(): JamSession? = _session.value

    fun setPolicy(next: ControlPolicy) {
        _policy.value = next
        broadcastFrame(
            JamWire.Msg.POLICY,
            JamWire.encodePolicy(deviceId, currentEpoch(), if (next == ControlPolicy.EVERYONE) 1 else 0)
        )
    }

    // ═══════════════ Phase 1: Topology + Governance host actions ═══════════

    /**
     * Host flips the room topology (Party Mode on/off). Guests adopt the
     * broadcast TOPOLOGY frame; this device applies the local audio-output
     * directive immediately through the command channel.
     */
    fun setTopology(target: JamTopology): TopologyDecision {
        val decision = JamTopologyMachine.request(
            current = _topology.value,
            isActive = isActive(),
            isHost = isHost(),
            target = target,
            nextEpoch = epochCounter.incrementAndGet(),
            leaderNonce = election?.leaderNonce?.value,
            requesterNonce = deviceId
        )
        if (decision is TopologyDecision.Applied) {
            latestTopologyEpoch = decision.epoch
            _topology.value = decision.topology
            markRegimeChange()
            broadcastFrame(
                JamWire.Msg.TOPOLOGY,
                JamWire.encodeTopology(deviceId, decision.epoch, if (decision.topology == JamTopology.SINGLE_RENDER) 1 else 0)
            )
            // Local audio-output discipline: guests mute; host keeps rendering.
            if (!isHost()) {
                _commands.tryEmit(
                    Command.ApplyRenderSuppression(JamTopologyMachine.rendersLocally(decision.topology, isHost()) == false)
                )
            }
            SLog.i("JamTopology", "room topology -> ${decision.topology} @ epoch ${decision.epoch}")
        }
        return decision
    }

    /** True when this device is a Party Mode remote controller (guest in SINGLE_RENDER). */
    fun isRemoteController(): Boolean =
        JamTopologyMachine.isRemoteController(_topology.value, isHost())

    /** True when this device should render audio under the current topology. */
    fun rendersLocally(): Boolean =
        JamTopologyMachine.rendersLocally(_topology.value, isHost())

    /** Host publishes one member-ACL row (control / volume / co-host). */
    fun setMemberAcl(targetNonce: String, allowControl: Boolean? = null, allowVolume: Boolean? = null) {
        val hostNonce = deviceId
        val decision1 = allowControl?.let {
            governance.setAllowControl(hostNonce, deviceId, targetNonce, it)
        }
        val decision2 = allowVolume?.let {
            governance.setAllowVolume(hostNonce, deviceId, targetNonce, it)
        }
        if (decision1 is JamGovernance.Decision.Refused && decision2 == null) return
        publishGovernanceRow(targetNonce)
    }

    /** Host grants/revokes co-host (secondary leader-intent emitter). */
    fun setMemberRole(targetNonce: String, role: JamGovernance.Role) {
        val decision = governance.setRole(deviceId, deviceId, targetNonce, role)
        if (decision is JamGovernance.Decision.Refused) return
        publishGovernanceRow(targetNonce)
    }

    /**
     * Kick (Gap #14): targeted removal + block for the room lifetime. The
     * kicked device receives the KICK frame and ends locally; peers block.
     */
    fun kickMember(targetNonce: String) {
        val decision = governance.kick(deviceId, deviceId, targetNonce)
        if (decision is JamGovernance.Decision.Refused) return
        broadcastFrame(
            JamWire.Msg.GOVERNANCE,
            JamWire.encodeGovernance(
                deviceId, epochCounter.incrementAndGet(), targetNonce,
                roleOrdinal = JamGovernance.Role.MEMBER.ordinal,
                allowControl = false, allowVolume = false,
                actionOrdinal = JamWire.GovernanceBody.GOV_ACTION_KICK
            )
        )
        broadcastFrame(JamWire.Msg.KICK, JamWire.encodeKick(deviceId, epochCounter.get(), targetNonce))
        dropMemberLocally(targetNonce)
        SLog.i("JamGovernance", "kicked $targetNonce")
    }

    /** Ban without kick — queue spammers stay blocked until room end. */
    fun banMember(targetNonce: String) {
        val decision = governance.ban(deviceId, deviceId, targetNonce)
        if (decision is JamGovernance.Decision.Refused) return
        broadcastFrame(
            JamWire.Msg.GOVERNANCE,
            JamWire.encodeGovernance(
                deviceId, epochCounter.incrementAndGet(), targetNonce,
                roleOrdinal = JamGovernance.Role.MEMBER.ordinal,
                allowControl = false, allowVolume = false,
                actionOrdinal = JamWire.GovernanceBody.GOV_ACTION_BAN
            )
        )
        _governanceRows.value = governance.members()
    }

    /** Guest affordance (Gap #18): report a disruptive member to the host. */
    fun reportMember(targetNonce: String, reason: JamGovernance.ReportReason): Boolean {
        if (!isActive() || targetNonce.isBlank()) return false
        if (!governance.mayEmitControlIntent(deviceId, election?.leaderNonce?.value ?: deviceId, _policy.value) &&
            deviceId == targetNonce) return false
        val sent = broadcastFrame(
            JamWire.Msg.REPORT,
            JamWire.encodeReport(deviceId, targetNonce, reason.wireCode, nowSynced())
        )
        return sent
    }

    /** Host: clear the report inbox (read by the governance sheet). */
    fun clearMemberReports() {
        governance.clearReports()
        _memberReports.value = emptyList()
    }

    /** Host (or this device in single-member rooms) mirrors a row to the mesh. */
    private fun publishGovernanceRow(targetNonce: String) {
        val row = governance.aclOf(targetNonce) ?: return
        broadcastFrame(
            JamWire.Msg.GOVERNANCE,
            JamWire.encodeGovernance(
                deviceId, epochCounter.incrementAndGet(), targetNonce,
                roleOrdinal = row.role.ordinal,
                allowControl = row.allowControl,
                allowVolume = row.allowVolume,
                actionOrdinal = JamWire.GovernanceBody.GOV_ACTION_UPSERT
            )
        )
        _governanceRows.value = governance.members()
        refreshMemberGovernanceFields()
    }

    /** Removes a member row locally (kick echo / leave / timeout). */
    private fun dropMemberLocally(nonce: String) {
        governance.remove(nonce)
        _members.update { list -> list.filterNot { it.nonce == nonce || it.userId == myUserIdOf(nonce) } }
        _governanceRows.value = governance.members()
        refreshConnStatus()
    }

    /** Syncs governance rows into the roster view (badges + toggles). */
    private fun refreshMemberGovernanceFields() {
        _members.update { list ->
            list.map { m ->
                val row = governance.aclOf(m.nonce)
                if (row == null) m else m.copy(role = row.role, allowControl = row.allowControl, allowVolume = row.allowVolume)
            }
        }
    }

    private fun currentEpoch(): Long = epochCounter.get()

    /** THE time source for all Jam math (millis, legacy-compatible domain). */
    fun nowSynced(): Long = NativeBridge.getSyncedJamMonotonicMs()

    /** THE atomic room clock (nanos, PTP domain — v4 wire + pivot math). */
    fun nowSyncedNanos(): Long = NativeBridge.synchronizedClockNanos()

    /**
     * Adaptive host tick interval: steady 1000 ms, 250 ms convergence burst for
     * 2 s after any regime change, 50 ms during the final 15 s of a track so
     * every device lands the transition frame-perfectly. Paused rooms tick at
     * 2 500 ms — heartbeat loss detection (1 200 ms) never fires on a live
     * host, paused or not.
     */
    fun tickIntervalMs(positionMs: Long, durationMs: Long, playing: Boolean): Long {
        if (!playing) return 2_500L
        if (durationMs > 0 && positionMs >= 0 && durationMs - positionMs <= 15_000) return 50L
        if (lastRegimeChangeSyncedMs > 0 && nowSynced() - lastRegimeChangeSyncedMs < 2_000) return 250L
        return 1_000L
    }

    internal fun markRegimeChange() {
        lastRegimeChangeSyncedMs = nowSynced()
        NativeBridge.kalmanPllReset()
    }

    val senderPacked: Long by lazy {
        try {
            // SHA-256 (not MD5 — CodeQL security gate): we only need a stable
            // 64-bit identity pack of the device nonce, first 8 bytes.
            java.nio.ByteBuffer.wrap(
                java.security.MessageDigest.getInstance("SHA-256").digest(deviceId.toByteArray())
            ).long
        } catch (_: Throwable) {
            deviceId.hashCode().toLong()
        }
    }

    // ═══════════════ Consensus + reconciliation subsystems ═══════════════

    @Volatile private var election: LeaderlessElectionEngine? = null
    @Volatile private var reconciler: MerkleStateReconciler? = null

    private fun wireConsensus(session: JamSession) {
        val electionEngine = LeaderlessElectionEngine(
            scope = scope,
            selfNonce = deviceId,
            clock = ::nowSyncedNanos,
            // ELECTION DOMAIN: the session CODE is the only room identifier every
            // join modality shares (QR joiners also know the UUID, PIN joiners
            // only the code) — so Blake3(deviceId + code) is bit-identical on
            // every peer regardless of how they entered the room.
            sessionId = { _session.value?.sessionCode },
            transport = object : LeaderlessElectionEngine.Transport {
                override fun broadcastContinuation(body: JamWire.ContinuationBody): Boolean =
                    broadcastFrame(
                        JamWire.Msg.CONTINUATION,
                        JamWire.encodeContinuation(
                            deviceId, currentEpoch(), body.newEpoch, body.successorNonce,
                            body.pivotPosMs, body.pivotSyncedNanos, body.durationMs,
                            body.playing, body.trackMatches
                        )
                    )
            },
            tickSource = object : LeaderlessElectionEngine.HostTickSource {
                override fun lastVerifiedTick() = lastVerifiedHostTick
            }
        )
        val merkle = MerkleStateReconciler(
            scope = scope,
            selfNonce = deviceId,
            transport = object : MerkleStateReconciler.Transport {
                override fun sendToPeer(peerIdHex: String, msgType: Int, payload: ByteArray): Boolean =
                    NativeBridge.p2pSendToPeer(peerIdHex, msgType, payload)
            },
            applyDeltaOp = ::applyWireOp
        )
        election = electionEngine
        reconciler = merkle
        electionEngine.start()
        merkle.start()

        // All consensus collectors start UNDISPATCHED so their subscriptions
        // latch before wireConsensus() returns — createServerlessRoom()
        // fires becomeFoundingLeader() (and its LeaderElected emission) on
        // the very next line, and a replay-0 flow drops anything emitted
        // before subscription.
        consensusJobs += scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            electionEngine.events.collect { event ->
                when (event) {
                    is LeaderlessElectionEngine.ElectionEvent.DeathPivotExecuted ->
                        onDeathPivotExecuted(event)
                    is LeaderlessElectionEngine.ElectionEvent.LeaderElected -> {
                        refreshConnStatus()
                        bumpTelemetry { it.copy(authorityEpoch = event.epoch) }
                    }
                    is LeaderlessElectionEngine.ElectionEvent.Demoted -> {
                        refreshConnStatus()
                        _commands.tryEmit(Command.Rehandshake)
                    }
                    is LeaderlessElectionEngine.ElectionEvent.ElectionStarted ->
                        refreshConnStatus()
                }
            }
        }
        consensusJobs += scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            merkle.reports.collect { report ->
                bumpTelemetry { it.copy(merkleConvergenceNanos = report.latencyNanos) }
                if (report.converged) refreshQueueFromCrdt()
            }
        }
        consensusJobs += scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            NativeBridge.peerEvents.collect { ev ->
                if (ev.joined) {
                    merkle.onPeerJoined(ev.peerIdHex)
                    // New peer: announce ourselves + ask the leader for state.
                    requestJoinState()
                } else {
                    merkle.onPeerLeft(ev.peerIdHex)
                    electionEngine.notePeerLeft(nonceOfPeerId(ev.peerIdHex))
                    peerRadar.remove(nonceOfPeerId(ev.peerIdHex))
                }
                refreshConnStatus()
            }
        }
    }

    /** Mesh peer id (hex) → announced device nonce, best effort. */
    private fun nonceOfPeerId(peerIdHex: String): String =
        peerRadar.entries.firstOrNull { it.value.peerIdHex == peerIdHex }?.key ?: peerIdHex.take(8)

    private fun onDeathPivotExecuted(event: LeaderlessElectionEngine.ElectionEvent.DeathPivotExecuted) {
        // THIS device is the successor. Authority is already ours (the
        // election engine bumped the epoch and broadcast CONTINUATION); now
        // continue the trajectory on the live player through the normal PLL
        // path — the room never paused, so nothing needs resuming, only
        // re-anchoring if the extrapolated position drifted past tolerance.
        latestAppliedEpoch = event.newEpoch
        markRegimeChange()
        val dur = playbackProbe?.invoke()?.getOrNull(1) ?: 0L
        if (event.beyondTrackEnd) {
            // Position extrapolated past the end: the room needs the next
            // queue head. Host auto-advance owns that decision.
            interceptAdvance()
        } else {
            _commands.tryEmit(Command.ApplyPllTick(event.extrapolatedPosMs, event.pivotNanos / 1_000_000L, dur, true))
        }
        SLog.i("JamPivot", "successor continuation anchored at ${event.extrapolatedPosMs}ms")
    }

    // ═══════════════ Outgoing (binary frames on the mesh) ═══════════════

    /** Single egress funnel — every frame the engine emits goes through here. */
    private fun broadcastFrame(msgType: Int, payload: ByteArray): Boolean =
        isActive() && NativeBridge.p2pBroadcast(msgType, payload)

    /**
     * Local playback intent. Leaders broadcast authoritative epochs; members
     * under EVERYONE policy also broadcast intents (ratified receiver-side by
     * role); members under HOST_ONLY are downgraded to self-only requests.
     */
    fun onLocalPlaybackAction(action: String, track: Track?, positionMs: Long, isPlaying: Boolean) {
        if (!isActive()) return
        when (action) {
            "TRACK_CHANGE" -> {
                val epoch = epochCounter.incrementAndGet()
                broadcastFrame(
                    JamWire.Msg.TRACK_CHANGE,
                    JamWire.encodeTrackChange(deviceId, epoch, positionMs, isPlaying, jamTrackToJson(track ?: return).toString())
                )
                markRegimeChange()
            }
            "SEEK" -> {
                val epoch = epochCounter.incrementAndGet()
                broadcastFrame(JamWire.Msg.SEEK, JamWire.encodeSeek(deviceId, epoch, positionMs))
                markRegimeChange()
            }
            "PLAY" -> playPauseIntent(true)
            "PAUSE" -> playPauseIntent(false)
            "TICK" -> heartbeatTick(track, positionMs, isPlaying)
        }
    }

    private fun playPauseIntent(play: Boolean) {
        val epoch = epochCounter.incrementAndGet()
        broadcastFrame(
            if (play) JamWire.Msg.PLAY else JamWire.Msg.PAUSE,
            JamWire.encodePlayPause(deviceId, epoch, play)
        )
        markRegimeChange()
    }

    /** High-resolution position heartbeat — LEADER ONLY by protocol contract. */
    fun heartbeatTick(track: Track?, positionMs: Long, isPlaying: Boolean) {
        if (!isActive() || !isHost()) return
        val now = System.currentTimeMillis()
        lastHostTickAt = now
        lastSelfHeartbeatAt = now
        if (track != null) currentHostTrack = track
        val durMs = track?.durationSec?.toLong()?.times(1000L)
            ?: playbackProbe?.invoke()?.getOrNull(1) ?: 0L
        val hostSyncedNanos = nowSyncedNanos()

        // Own-tick bookkeeping keeps the election watchdog satisfied and the
        // Death Pivot source warm even when no one else is listening.
        lastVerifiedHostTick = LeaderlessElectionEngine.HostTickSource.Snapshot(
            hostNonce = deviceId, positionMs = positionMs,
            syncedNanos = hostSyncedNanos, durationMs = durMs, playing = isPlaying
        )
        election?.noteLeaderTick(deviceId, positionMs, hostSyncedNanos, durMs, isPlaying)

        broadcastFrame(
            JamWire.Msg.TICK,
            JamWire.encodeTick(
                deviceId, currentEpoch(),
                seq = tickSeqCounter.incrementAndGet(),
                hostSyncedNanos = hostSyncedNanos,
                positionMs = positionMs, durationMs = durMs,
                playing = isPlaying,
                policy = if (_policy.value == ControlPolicy.EVERYONE) 1 else 0
            )
        )
    }

    /**
     * Predictive JIT pre-hydration. The leader announces the upcoming queue
     * head ~30 s before the current track ends so every guest resolves and
     * pre-buffers it BEFORE TRACK_CHANGE lands.
     */
    fun announceNextIs(nextTrack: Track?) {
        if (!isActive() || !isHost()) return
        broadcastFrame(
            JamWire.Msg.NEXT_IS,
            if (nextTrack == null) JamWire.encodeNextIs(deviceId, currentEpoch(), isNull = true, trackJson = null)
            else JamWire.encodeNextIs(deviceId, currentEpoch(), isNull = false, trackJson = jamTrackToJson(nextTrack).toString())
        )
    }

    /**
     * PTP bootstrap: guest fires a Cristian handshake probe (t0 = raw local
     * monotonic nanos — synced values would double-count theta).
     */
    fun fireClockSyncProbe() {
        if (!isActive()) return
        if (isHost()) return
        lastSyncReqAtMs = nowSynced()
        broadcastFrame(
            JamWire.Msg.SYNC_REQ,
            JamWire.encodeSyncReq(deviceId, syncProbeCounter.incrementAndGet().toInt(), System.nanoTime())
        )
    }

    fun clockSyncProbeDue(): Boolean =
        isActive() && !isHost() &&
            (lastSyncReqAtMs == 0L || nowSynced() - lastSyncReqAtMs > probePacingMs())

    private fun probePacingMs(): Long =
        if (NativeBridge.getJamClockRttMs() >= 0L) 3_000L else 300L

    /** True once the native Cristian filter has locked (>= 3 good samples). */
    fun clockLocked(): Boolean = NativeBridge.getJamClockRttMs() >= 0L

    fun pulsePresence(name: String, avatarUrl: String?) {
        if (!isActive()) return
        noteMember(myUserId(), name, avatarUrl, isHost())
        broadcastFrame(
            JamWire.Msg.PRESENCE,
            JamWire.encodePresence(
                deviceId, currentEpoch(), nowSyncedNanos(), isHost(),
                linkTypeHint(), name, avatarUrl
            )
        )
    }

    /** Our mesh layer's dominant transport — surfaced on the radar. */
    private fun linkTypeHint(): Int = JamWire.LinkType.LAN_5GHZ_UDP

    // ═══════════════ Room lifecycle (serverless bootstrap) ═══════════════

    private val codeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private val secureRandom = SecureRandom()

    /** Creates a room locally: PIN + ephemeral pairing key, mesh boots, we lead. */
    fun createServerlessRoom(): JamSession {
        if (isActive()) return _session.value!!
        val code = buildString { repeat(6) { append(codeAlphabet[secureRandom.nextInt(codeAlphabet.length)]) } }
        val pairingKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        val session = JamSession(
            id = UUID.randomUUID().toString(),
            sessionCode = code,
            pairingKeyHex = Blake3.toHex(pairingKey),
            hostNonce = deviceId,
            createdAtMs = System.currentTimeMillis()
        )
        _session.value = session
        startRuntime(session)
        election?.becomeFoundingLeader()
        epochCounter.set(1L)
        // Synchronous telemetry anchor: the LeaderElected event is processed
        // asynchronously on the collector's dispatcher, and an assertion (or
        // UI frame) in the very next line must already observe epoch 1.
        bumpTelemetry { it.copy(authorityEpoch = election?.authorityEpoch?.value ?: 1L) }
        noteSelf()
        SLog.i("JamRoom", "room created: code=$code id=${session.id} (serverless)")
        return session
    }

    /**
     * Joins a room from a full pairing payload (QR / NFC / paste) or a bare
     * PIN (LAN-discovered room). Returns false when the payload is malformed.
     */
    fun joinServerlessRoom(payload: String): Boolean {
        val pairing = JamPairing.parsePayload(payload)
        if (pairing != null) {
            if (isActive()) return true
            _session.value = JamSession(
                id = pairing.sessionId,
                sessionCode = pairing.sessionCode,
                pairingKeyHex = Blake3.toHex(pairing.pairingKey),
                hostNonce = "",
                createdAtMs = System.currentTimeMillis()
            )
            startRuntime(_session.value!!)
            requestJoinState()
            SLog.i("JamRoom", "joining room ${pairing.sessionCode} via pairing payload")
            return true
        }
        val pin = payload.trim().uppercase()
        if (pin.length == 6) {
            if (isActive()) return true
            _session.value = JamSession(
                id = "pin-$pin",
                sessionCode = pin,
                pairingKeyHex = "",
                hostNonce = "",
                createdAtMs = System.currentTimeMillis()
            )
            startRuntime(_session.value!!)
            requestJoinState()
            return true
        }
        return false
    }

    /** Guest asks the live leader for authoritative playback state. */
    fun requestJoinState() {
        if (!isActive() || isHost()) return
        broadcastFrame(JamWire.Msg.STATE_REQ, JamWire.encodeStateReq(deviceId, currentEpoch()))
    }

    // ═══════════════ Shared queue operations ═══════════════

    private fun cadFor(track: Track): Long =
        NativeBridge.jamCanonicalCadId(track.title, track.artist, track.durationSec)

    /** Seals, applies, persists, and broadcasts one mutation op. */
    private fun mutate(
        type: Int,
        track: Track,
        fracIndexProvider: () -> Double,
        targetAddOpId: Long = 0L
    ): Boolean {
        if (!isActive()) return false
        val cadId = cadFor(track)
        if (cadId == 0L && type == JamOpWire.OP_ADD) return false

        val opId = NativeBridge.jamGenerateOpId()
        val frac = fracIndexProvider()
        val applied = NativeBridge.jamCrdtApplyLocalOp(
            opId, senderPacked, type, if (_policy.value == ControlPolicy.EVERYONE) 1 else 0,
            cadId, frac, targetAddOpId
        )
        if (!applied) return false
        localOpIds.add(opId)

        if (type == JamOpWire.OP_ADD) {
            elementIndex[cadId] = opId
            cadTrackCache[cadId] = track
        } else if (targetAddOpId != 0L) {
            elementIndex.entries.removeIf { it.value == targetAddOpId }
        }

        val op = JamOpWire(opId, senderPacked, type, if (_policy.value == ControlPolicy.EVERYONE) 1 else 0, cadId, frac, targetAddOpId)

        // Local-first persist (WAL outbox): survives partitions & process death.
        _session.value?.let { s ->
            NativeBridge.jamOutboxEnqueue(opId, senderPacked, type, 0, cadId, frac, targetAddOpId, s.sessionCode)
        }
        reconciler?.noteOpApplied(op, if (type == JamOpWire.OP_ADD) jamTrackToJson(track).toString() else null)

        refreshQueueFromCrdt()
        broadcastFrame(JamWire.Msg.OP, JamWire.encodeOp(deviceId, currentEpoch(), op))
        if (type == JamOpWire.OP_ADD) {
            // Metadata announce: lets receivers resolve cadId → Track without
            // waiting for a snapshot or Merkle delta.
            broadcastFrame(
                JamWire.Msg.TRACK_META,
                JamWire.encodeTrackMeta(deviceId, cadId, jamTrackToJson(track).toString())
            )
        }
        return true
    }

    fun addToQueue(track: Track, addedByName: String): Boolean {
        val tailFrac = fracByCad.values.maxOrNull()
            ?.let { FractionalIndexEngine.after(it).value }
            ?: FractionalIndexEngine.FIRST_INDEX
        addedByMap[track.id.toString()] = "Added by $addedByName"
        val ok = mutate(JamOpWire.OP_ADD, track, { tailFrac })
        if (!ok) {
            // Native CRDT unavailable → legacy best-effort path.
            _queue.update { current -> current + track }
            broadcastQueueSnapshot()
        }
        return ok
    }

    fun removeFromQueue(track: Track): Boolean {
        val cad = cadFor(track)
        val target = elementIndex[cad] ?: 0L
        val ok = target != 0L && mutate(JamOpWire.OP_REMOVE, track, { 0.0 }, target)
        if (ok) {
            // The element is gone — its democratic tally dies with it (Gap #37).
            _queueVotes.update { it - target }
        } else {
            _queue.update { current ->
                current.filterNot { it.id == track.id || (it.title == track.title && it.artist == track.artist) }
            }
            broadcastQueueSnapshot()
        }
        return ok
    }

    /**
     * Fractional-index reorder (Gap #11): moves [track] to [toPosition] in
     * the shared queue by emitting an OP_REORDER op whose frac is the
     * midpoint between the new neighbours — the classic Jepsen fractional
     * scheme, so concurrent reorders from 32 members interleave without
     * conflicts and every replica converges on the same order after the
     * CRDT fold. Falls back to the legacy snapshot path when the native
     * CRDT is unavailable.
     */
    fun moveInQueue(track: Track, toPosition: Int): Boolean {
        if (!isActive()) return false
        val current = _queue.value.toMutableList()
        val from = current.indexOfFirst { it.id == track.id }
        if (from < 0) return false
        val to = toPosition.coerceIn(0, current.size - 1)
        if (from == to) return true

        val cad = cadFor(track)
        if (cad == 0L) return false

        current.removeAt(from)
        current.add(to, track)

        val beforeFrac = if (to > 0) fracByCad[cadFor(current[to - 1])] else null
        val afterFrac = if (to < current.size - 1) fracByCad[cadFor(current[to + 1])] else null
        val newFrac = when {
            beforeFrac != null && afterFrac != null -> (beforeFrac + afterFrac) / 2.0
            afterFrac != null -> FractionalIndexEngine.before(afterFrac).value
            else -> FractionalIndexEngine.after(beforeFrac).value
        }
        val ok = mutate(JamOpWire.OP_REORDER, track, { newFrac })
        if (!ok) {
            // Legacy best-effort: optimistic local move + snapshot broadcast.
            _queue.value = current
            broadcastQueueSnapshot()
        }
        return true
    }

    /**
     * Democratic float (Gap #37): the track rises to just below every item
     * with a strictly higher tally; equal-tally items keep their current
     * relative order (stable). Only moves up — a retract never demotes. The
     * resulting OP_REORDER is a normal broadcast CRDT mutation, so peers and
     * late joiners converge on the same order through the Merkle fold.
     */
    private fun floatTrackToVoteRank(track: Track) {
        val current = _queue.value
        if (current.size < 2) return
        val idx = current.indexOfFirst { it.id == track.id }
        if (idx <= 0) return
        val myVotes = voteCountFor(track)
        if (myVotes <= 0) return
        val counts = current.map { voteCountFor(it) }
        val strictlyHigher = counts.count { it > myVotes }
        val equalBefore = (0 until idx).count { counts[it] == myVotes }
        val target = strictlyHigher + equalBefore
        if (target < idx) moveInQueue(track, target)
    }

    /** Live vote count for a queued track (0 when unknown / unvoted). */
    fun voteCountFor(track: Track): Int {
        val target = elementIndex[cadFor(track)] ?: 0L
        if (target == 0L) return 0
        return _queueVotes.value[target]?.size ?: 0
    }

    /** Whether the local member has an active upvote on [track]. */
    fun hasVotedFor(track: Track): Boolean {
        val target = elementIndex[cadFor(track)] ?: 0L
        return target != 0L && deviceId in (_queueVotes.value[target] ?: emptySet())
    }

    /**
     * One member, one toggleable vote per queue element (Gap #37). The
     * tally flip is optimistic-local and gossiped via a VOTE frame; the
     * float-up rides a fractional-index OP_REORDER so every replica —
     * including peers that never saw the VOTE frame — converges on the same
     * democratic order through the CRDT fold.
     */
    fun castUpvote(track: Track): Boolean {
        if (!isActive()) return false
        val target = elementIndex[cadFor(track)] ?: 0L
        if (target == 0L) return false

        val up = deviceId !in (_queueVotes.value[target] ?: emptySet())
        applyVoteTally(target, deviceId, up)
        broadcastFrame(
            JamWire.Msg.VOTE,
            JamWire.encodeVote(deviceId, currentEpoch(), target, up)
        )
        if (up) floatTrackToVoteRank(track)
        return true
    }

    /** Folds one vote toggle into the tally (idempotent set semantics). */
    private fun applyVoteTally(targetAddOpId: Long, voterNonce: String, up: Boolean) {
        _queueVotes.update { tally ->
            val voters = (tally[targetAddOpId] ?: emptySet()).toMutableSet()
            if (up) voters.add(voterNonce) else voters.remove(voterNonce)
            if (voters.isEmpty()) tally - targetAddOpId else tally + (targetAddOpId to voters.toSet())
        }
    }

    /** Reverse lookup: cadId of the queue element created by [addOpId]. */
    private fun cadOfElement(addOpId: Long): Long? =
        elementIndex.entries.firstOrNull { it.value == addOpId }?.key

    /**
     * Rebuilds the UI queue from the authoritative CRDT fold, resolving cad
     * identities back to Track objects via the cache.
     */
    private fun refreshQueueFromCrdt() {
        val fold = NativeBridge.jamCrdtFold() ?: return
        val (triples, _) = fold
        if (triples.isEmpty()) return
        val rebuilt = ArrayList<Track>(triples.size / 3 + 1)
        var i = 0
        while (i + 2 < triples.size) {
            val cad = triples[i + 2]
            cadTrackCache[cad]?.let { rebuilt.add(it) }
            i += 3
        }
        i = 0
        while (i + 2 < triples.size) {
            fracByCad[triples[i + 2]] = Double.fromBits(triples[i])
            i += 3
        }
        if (rebuilt.isNotEmpty()) _queue.value = rebuilt
    }

    /** Full-queue snapshot for the legacy (CRDT-less) convergence path. */
    private fun broadcastQueueSnapshot() {
        val arr = JSONArray()
        _queue.value.forEach { arr.put(jamTrackToJson(it)) }
        broadcastFrame(JamWire.Msg.QUEUE_SNAPSHOT, JamWire.encodeQueueSnapshot(deviceId, arr.toString()))
    }

    fun queueHead(): Track? = _queue.value.firstOrNull()

    /**
     * Called by the player before auto-advance/skip logic.
     *  - Not in a Jam          -> false (legacy behavior)
     *  - Leader                -> consumes the shared queue head via a CRDT
     *                            REMOVE (Merkle-consistent consumption!) and
     *                            broadcasts the authoritative TRACK_CHANGE.
     *  - Guest                 -> true (deliberately idles awaiting change)
     */
    fun interceptAdvance(): Boolean {
        if (!isActive()) return false
        if (!isHost()) return true
        val head = _queue.value.firstOrNull() ?: return true
        removeFromQueue(head) // consumption = REMOVE op; every peer converges
        bridge?.loadTrack(head, 0L, true)
        onLocalPlaybackAction("TRACK_CHANGE", head, 0L, true)
        return true
    }

    // ═══════════════ Incoming dispatcher (mesh → engine) ═══════════════

    /** The leader's own current track — captured from every heartbeat tick. */
    @Volatile private var currentHostTrack: Track? = null

    /** Latest leader-state snapshot for the join handshake. */
    private val _lastHandshake = MutableStateFlow<HandshakeSnapshot?>(null)

    /**
     * Raw ingress funnel: decodes every frame off the mesh and dispatches it.
     * Malformed frames die at the codec boundary — they never reach the brain.
     */
    internal fun onWireFrame(peerIdHex: String, frame: JamWire.Frame) {
        val sender = frame.senderNonce
        if (sender.isBlank() || sender == deviceId) return // own echo / junk

        election?.notePeerSeen(sender)

        when (frame.msgType) {
            JamWire.Msg.MERKLE_ROOT_REQ, JamWire.Msg.MERKLE_ROOT_ACK,
            JamWire.Msg.MERKLE_BRANCH_REQ, JamWire.Msg.MERKLE_BRANCH,
            JamWire.Msg.MERKLE_DELTA_REQ, JamWire.Msg.MERKLE_DELTA -> {
                reconciler?.onFrame(peerIdHex, frame)
                return
            }

            JamWire.Msg.PRESENCE -> {
                val p = JamWire.parsePresence(frame) ?: return
                lastHostTickAt = System.currentTimeMillis() // mesh + room alive
                deviceUserMap[sender] = p.name
                // Gap #11 + #18 admission gate: 32-member hard cap and the
                // room-lifetime block list. Refused nonces never join the
                // roster and their frames are ignored from here on.
                if (sender != deviceId && !governance.admits(sender, _members.value.size) &&
                    !senderIsLeader(sender)) {
                    SLog.d("JamRoom", "presence from $sender refused (cap/block)")
                    return
                }
                noteMember(myUserIdOf(sender), p.name, p.avatarUrl, p.isHost, sender)
                peerRadar[sender] = MeshPeer(
                    peerIdHex = peerIdHex,
                    nonce = sender,
                    name = p.name,
                    avatarUrl = p.avatarUrl,
                    isHost = p.isHost || senderIsLeader(sender),
                    linkType = p.linkType,
                    rttMs = peerRadar[sender]?.rttMs ?: -1f,
                    lastSeenMs = System.currentTimeMillis()
                )
                refreshConnStatus()
            }

            JamWire.Msg.LEAVE -> {
                _members.update { list -> list.filterNot { it.userId == myUserIdOf(sender) } }
                peerRadar.remove(sender)
                election?.notePeerLeft(sender)
                refreshConnStatus()
            }

            JamWire.Msg.SESSION_END -> {
                if (senderIsLeader(sender)) endLocally()
            }

            JamWire.Msg.POLICY -> {
                if (senderIsLeader(sender)) {
                    JamWire.parsePolicy(frame)?.let {
                        _policy.value = if (it == 1) ControlPolicy.EVERYONE else ControlPolicy.HOST_ONLY
                    }
                }
            }

            JamWire.Msg.TOPOLOGY -> {
                // Leader flips the room render topology (Gap #13). Guests
                // adopt epoch-gated; stale out-of-order frames are dropped.
                if (!senderIsLeader(sender)) return
                val ordinal = JamWire.parseTopology(frame) ?: return
                val target = if (ordinal == 1) JamTopology.SINGLE_RENDER else JamTopology.MULTI_RENDER
                val decision = JamTopologyMachine.ingest(
                    current = _topology.value,
                    isActive = isActive(),
                    senderIsLeader = true,
                    frameTopology = target,
                    frameEpoch = frame.epoch,
                    appliedEpoch = latestTopologyEpoch
                )
                when (decision) {
                    is TopologyDecision.Applied -> {
                        latestTopologyEpoch = decision.epoch
                        _topology.value = decision.topology
                        markRegimeChange()
                        if (frame.epoch > epochCounter.get()) epochCounter.set(frame.epoch)
                        // Party Mode audio discipline: this guest suppresses
                        // local render (PLL stays silently warm).
                        _commands.tryEmit(
                            Command.ApplyRenderSuppression(!JamTopologyMachine.rendersLocally(decision.topology, isHost()))
                        )
                        SLog.i("JamTopology", "adopted topology ${decision.topology} @ epoch ${decision.epoch}")
                    }
                    is TopologyDecision.RefusedStaleEpoch ->
                        SLog.d("JamTopology", "dropped stale topology frame (epoch ${frame.epoch})")
                    else -> Unit
                }
            }

            JamWire.Msg.GOVERNANCE -> {
                // Leader-published ACL row / kick / ban (Gaps #14 + #18).
                if (!senderIsLeader(sender)) return
                val body = JamWire.parseGovernance(frame) ?: return
                when {
                    body.isKickAction -> {
                        governance.kick(sender, sender, body.targetNonce)
                        dropMemberLocally(body.targetNonce)
                    }
                    body.isBanAction -> {
                        governance.ban(sender, sender, body.targetNonce)
                        _governanceRows.value = governance.members()
                    }
                    else -> {
                        governance.upsert(
                            JamGovernance.MemberAcl(
                                nonce = body.targetNonce,
                                role = JamGovernance.Role.entries[body.roleOrdinal],
                                allowControl = body.allowControl,
                                allowVolume = body.allowVolume
                            )
                        )
                        refreshMemberGovernanceFields()
                    }
                }
                _governanceRows.value = governance.members()
            }

            JamWire.Msg.KICK -> {
                // Targeted removal: the named device ends its session; other
                // peers drop the member and block re-admission.
                if (!senderIsLeader(sender)) return
                val target = JamWire.parseKick(frame) ?: return
                if (target == deviceId) {
                    SLog.w("JamRoom", "kicked by host — ending local session")
                    endLocally()
                    return
                }
                governance.kick(sender, sender, target)
                dropMemberLocally(target)
            }

            JamWire.Msg.REPORT -> {
                // Guest → host abuse report (Gap #18): rate-limited ingestion.
                if (!isHost()) return
                val body = JamWire.parseReport(frame) ?: return
                val reason = JamGovernance.ReportReason.fromWire(body.reasonWireCode) ?: return
                governance.fileReport(sender, body.targetNonce, reason, body.reportedAtMs)
                _memberReports.value = governance.pendingReports()
                SLog.i("JamGovernance", "report against ${body.targetNonce} (${reason.label})")
            }

            JamWire.Msg.VOTE -> {
                // Democratic queue vote (Gap #37): any member may vote. Only
                // the tally folds here — the float-up reorder arrives as a
                // regular broadcast OP_REORDER, so order + count converge on
                // every replica without an extra op storm.
                val body = JamWire.parseVote(frame) ?: return
                if (body.voterNonce.isBlank()) return
                applyVoteTally(body.targetAddOpId, body.voterNonce, body.up)
                val cad = cadOfElement(body.targetAddOpId) ?: 0L
                SLog.d(
                    "JamVotes",
                    "tally ${body.targetAddOpId}${if (cad != 0L) " (cad $cad)" else ""} " +
                        "${if (body.up) "+" else "-"}${body.voterNonce}"
                )
            }

            JamWire.Msg.OP -> {
                val op = JamWire.parseOp(frame) ?: return
                // Per-member ACL at intent ingress (Gap #14): blocked or
                // control-revoked senders' queue ops are dropped here.
                if (sender != deviceId &&
                    !governance.mayEmitControlIntent(sender, election?.leaderNonce?.value ?: deviceId, _policy.value)) {
                    SLog.d("JamGovernance", "OP from $sender dropped by ACL")
                    return
                }
                applyWireOp(JamWire.MerkleDeltaOp(op, null))
            }

            JamWire.Msg.TRACK_META -> {
                val meta = JamWire.parseTrackMeta(frame) ?: return
                runCatching { JSONObject(meta.trackJson) }.getOrNull()?.let { json ->
                    jamTrackFromJson(json)?.let { track ->
                        cadTrackCache[meta.cadId] = track
                        json.optString("addedBy", "").ifBlank { null }?.let {
                            addedByMap[track.id.toString()] = it
                        }
                    }
                }
                refreshQueueFromCrdt()
            }

            JamWire.Msg.CONTINUATION -> {
                val body = JamWire.parseContinuation(frame) ?: return
                handleContinuation(body)
            }

            JamWire.Msg.SYNC_REQ -> {
                // LEADER timestamps the probe and returns it to the asker.
                if (isHost()) {
                    val req = JamWire.parseSyncReq(frame) ?: return
                    val nowNanos = nowSyncedNanos()
                    NativeBridge.p2pSendToPeer(
                        peerIdHex, JamWire.Msg.SYNC_ACK,
                        JamWire.encodeSyncAck(deviceId, req.probeId, req.t0Nanos, nowNanos, nowNanos, sender)
                    )
                }
            }

            JamWire.Msg.SYNC_ACK -> {
                val ack = JamWire.parseSyncAck(frame) ?: return
                if (!isHost() && ack.targetNonce == deviceId) {
                    val t3 = System.nanoTime()
                    // PTP domain (nanos): drives the Sinc resampler drift target.
                    val offsetNanos = NativeBridge.ptpProcessTimestampsNanos(ack.t0Nanos, ack.t1Nanos, ack.t2Nanos, t3)
                    val rttNanos = (t3 - ack.t0Nanos).coerceAtLeast(0L)
                    NativeBridge.resamplerSetTargetDriftNanos(offsetNanos)
                    com.streamify.app.media.audio.SyncAudioProcessor.setClockDriftAdjustment(
                        (offsetNanos / 1_000_000.0).toFloat()
                    )
                    peerRadar[sender]?.let {
                        peerRadar[sender] = it.copy(rttMs = rttNanos / 1_000_000f)
                    }
                    // Legacy Cristian filter (ms domain): keeps the native
                    // synced-monotonic clock + PLL plumbing converging.
                    NativeBridge.jamClockApplySample(
                        ack.t0Nanos / 1_000_000L, ack.t1Nanos / 1_000_000L,
                        ack.t2Nanos / 1_000_000L, t3 / 1_000_000L
                    )
                    bumpTelemetry {
                        it.copy(
                            clockDriftNanos = offsetNanos,
                            rttNanos = rttNanos,
                            ptpLocked = clockLocked(),
                            resamplerRateScalar = com.streamify.app.media.audio.SyncAudioProcessor.kalmanSpeedScalar
                        )
                    }
                }
            }

            JamWire.Msg.NEXT_IS -> {
                if (!isHost() && senderIsLeader(sender)) {
                    val isNull = JamWire.parseNextIs(frame) ?: return
                    pendingNextIsTrack = frame.trackJson?.let {
                        runCatching { JSONObject(it) }.getOrNull()?.let { jamTrackFromJson(it) }
                    }?.takeIf { !isNull }
                    pendingNextIsTrack?.let { onNextIsListener?.invoke(it) }
                }
            }

            JamWire.Msg.STATE_REQ -> {
                if (isHost()) answerStateRequest(peerIdHex, sender)
            }

            JamWire.Msg.QUEUE_SNAPSHOT -> {
                if (senderIsLeader(sender)) {
                    val tail = JamWire.parseQueueSnapshot(frame) ?: return
                    runCatching {
                        val arr = JSONArray(tail)
                        val incoming = (0 until arr.length()).mapNotNull { jamTrackFromJson(arr.optJSONObject(it)) }
                        if (incoming.isNotEmpty()) _queue.value = incoming
                    }
                }
            }

            else -> handleControlIntent(frame, sender)
        }
    }

    /** Uniform user-id mapping (serverless: nonce-scoped identity). */
    private fun myUserIdOf(nonce: String): String = deviceUserMap[nonce] ?: nonce

    private fun senderIsLeader(senderNonce: String): Boolean {
        // NB: a null leaderNonce is the BOOTSTRAP window (no leader known
        // yet), not "nobody is leader" — the founding host's presence flag
        // rules until the first election/CONTINUATION formalizes authority.
        // Returning false on null here would drop every bootstrap host tick
        // and the room could never elect or pivot.
        val leader = election?.leaderNonce?.value
        if (leader == senderNonce) return true
        // Bootstrap window before any CONTINUATION: presence's host flag rules.
        return peerRadar[senderNonce]?.isHost == true && leader.isNullOrEmpty()
    }

    /** Applies a sealed op from the wire (direct OP frame or Merkle delta). */
    private fun applyWireOp(entry: JamWire.MerkleDeltaOp) {
        val op = entry.op
        if (op.sender == senderPacked && op.opId in localOpIds) return // own echo
        val applied = NativeBridge.jamCrdtApplyWireOp(
            op.opId, op.sender, op.type, op.policy, op.cadId, op.frac, op.target,
            checksum = -1L // sealed by wire structure; native validates shape
        )
        if (!applied) return
        if (op.type == JamOpWire.OP_ADD && op.cadId != 0L) {
            elementIndex[op.cadId] = op.opId
            entry.trackJson?.let { json ->
                runCatching { JSONObject(json) }.getOrNull()?.let { jamTrackFromJson(it) }
                    ?.let { track ->
                        cadTrackCache[op.cadId] = track
                        addedByMap[track.id.toString()] = json // echo for attribution
                    }
            }
        } else if (op.target != 0L) {
            elementIndex.entries.removeIf { it.value == op.target }
            // Remote REMOVE fold — the tally for that element dies too (Gap #37).
            _queueVotes.update { it - op.target }
        }
        reconciler?.noteOpApplied(op, entry.trackJson)
        refreshQueueFromCrdt()
    }

    /** Locally generated op ids (echo suppression without a native query). */
    private val localOpIds = ConcurrentHashMap.newKeySet<Long>()

    /**
     * CONTINUATION epoch — the Death Pivot landing on guests. The playhead
     * keeps gliding: the successor's authority simply re-anchors the PLL.
     */
    private fun handleContinuation(body: JamWire.ContinuationBody) {
        election?.noteContinuation(body)
        if (body.newEpoch <= latestAppliedEpoch) return
        latestAppliedEpoch = body.newEpoch
        if (body.newEpoch > epochCounter.get()) epochCounter.set(body.newEpoch)
        adoptTakeoverInternal()
        if (!isHost() && body.pivotPosMs >= 0) {
            // Zero-stutter continuity: PLL tick, never a pause.
            _commands.tryEmit(
                Command.ApplyPllTick(body.pivotPosMs, body.pivotSyncedNanos / 1_000_000L, body.durationMs, body.playing)
            )
            if (!body.trackMatches) requestJoinState() // pivot fell off the track end
        }
        refreshConnStatus()
    }

    /** New regime bookkeeping: wipe stale stream state, mark convergence. */
    private fun adoptTakeoverInternal() {
        NativeBridge.jamTickMatrixReset()
        NativeBridge.kalmanPllReset()
        tickSeqCounter.set(0)
        markRegimeChange()
        lastRegimeChangeSyncedMs = nowSynced()
    }

    /** Leader answered a STATE_REQ — targeted so other guests don't re-seek. */
    private fun answerStateRequest(peerIdHex: String, targetNonce: String) {
        val probe = playbackProbe?.invoke()
        val pos = extrapolateLeaderPositionMs(probe?.getOrNull(0) ?: 0L)
        val playing = (probe?.getOrNull(2) ?: 1L) == 1L
        val track = currentHostTrack
        val trackJson = track?.let { jamTrackToJson(it).toString() }
        val epoch = epochCounter.incrementAndGet()
        NativeBridge.p2pSendToPeer(
            peerIdHex, JamWire.Msg.TRACK_CHANGE,
            JamWire.encodeTrackChange(deviceId, epoch, pos, playing, trackJson ?: "{}")
        )
        SLog.d("JamRoom", "state answered for $targetNonce: pos=${pos}ms playing=$playing")
    }

    /**
     * Where the LEADER should be right now, extrapolated through the synced
     * clock from the last heartbeat — used to answer joiners crisply.
     */
    private fun extrapolateLeaderPositionMs(basePositionMs: Long): Long {
        val last = lastVerifiedHostTick ?: return basePositionMs
        if (!last.playing) return last.positionMs
        val elapsed = (nowSyncedNanos() - last.syncedNanos) / 1_000_000L
        if (elapsed <= 0L) return basePositionMs
        val extrapolated = last.positionMs + elapsed.coerceIn(0L, 600_000L)
        return if (last.durationMs > 0) extrapolated.coerceAtMost(last.durationMs) else extrapolated
    }

    private fun handleControlIntent(frame: JamWire.Frame, sender: String) {
        val senderIsHost = senderIsLeader(sender)

        // Authority gate: leaders always; others only under EVERYONE policy.
        if (!senderIsHost && _policy.value != ControlPolicy.EVERYONE) return

        // Phase 1 governance gate (Gap #14): per-member ACLs (kick/ban,
        // control-revoked, co-host) enforced at intent ingress — epoch-gated
        // drop before any playback mutation.
        if (sender != deviceId &&
            !governance.mayEmitControlIntent(sender, election?.leaderNonce?.value ?: deviceId, _policy.value)) {
            SLog.d("JamGovernance", "control intent from $sender dropped by ACL")
            return
        }

        val epoch = frame.epoch
        // Epoch gate: never regress to an older playback regime.
        if (frame.msgType != JamWire.Msg.TICK) {
            if (epoch in 1..latestAppliedEpoch) return
            latestAppliedEpoch = epoch
        } else if (epoch in 1 until latestAppliedEpoch) {
            return // tick from a superseded regime
        }

        when (frame.msgType) {
            JamWire.Msg.TRACK_CHANGE -> {
                val tc = JamWire.parseTrackChange(frame) ?: return
                if (isHost()) return // leader ignores foreign regimes outright
                val track = frame.trackJson?.let {
                    runCatching { JSONObject(it) }.getOrNull()?.let { j -> jamTrackFromJson(j) }
                } ?: return
                latestAppliedEpoch = maxOf(latestAppliedEpoch, epoch)
                markRegimeChange()
                _lastHandshake.value = HandshakeSnapshot(
                    trackJson = runCatching { JSONObject(frame.trackJson!!) }.getOrNull(),
                    positionMs = tc.positionMs,
                    isPlaying = tc.playing,
                    queue = _queue.value,
                    hostNonce = sender
                )
                _commands.tryEmit(Command.ApplyTrack(track, tc.positionMs, tc.playing))
            }

            JamWire.Msg.SEEK -> {
                if (isHost()) return
                val pos = JamWire.parseSeek(frame) ?: return
                markRegimeChange()
                _commands.tryEmit(Command.ApplySeek(pos))
            }

            JamWire.Msg.PLAY -> {
                if (isHost()) return
                markRegimeChange()
                _commands.tryEmit(Command.ApplyPlayPause(true))
            }

            JamWire.Msg.PAUSE -> {
                if (isHost()) return
                markRegimeChange()
                _commands.tryEmit(Command.ApplyPlayPause(false))
            }

            JamWire.Msg.TICK -> {
                val tick = frame.asTick ?: return
                if (!senderIsHost) return // guests never drive the room clock
                lastHostTickAt = System.currentTimeMillis()
                refreshConnStatus()
                if (isHost()) return

                val dur = tick.durationMs
                // Verified-tick bookkeeping: THE Death Pivot extrapolation source.
                lastVerifiedHostTick = LeaderlessElectionEngine.HostTickSource.Snapshot(
                    hostNonce = sender,
                    positionMs = tick.positionMs,
                    syncedNanos = tick.hostSyncedNanos,
                    durationMs = dur,
                    playing = tick.playing
                )
                election?.noteLeaderTick(sender, tick.positionMs, tick.hostSyncedNanos, dur, tick.playing)
                frame.trackJson?.let {
                    runCatching { JSONObject(it) }.getOrNull()?.let { j -> jamTrackFromJson(j) }
                }?.let { currentHostTrack = it }

                // Lossless sequence matrix: synthesize gap-fills so the PLL
                // never mistakes a dropped packet for a stall.
                val hostMonoMs = (tick.hostSyncedNanos / 1_000_000L).takeIf { it > 0 } ?: nowSynced()
                val state = if (tick.playing) 0 else 1
                val packedTicks = NativeBridge.jamTickIngest(
                    tick.seq.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    tick.positionMs, hostMonoMs, state,
                    if (_policy.value == ControlPolicy.EVERYONE) 1 else 0
                )
                for (packed in packedTicks) {
                    val sPos = packed and 0x7FFF_FFFFL
                    _commands.tryEmit(Command.ApplyPllTick(sPos, hostMonoMs, dur, tick.playing))
                }
            }
        }
    }

    // ═══════════════ Presence bookkeeping & connection status ═══════════════

    private fun noteMember(
        userId: String,
        name: String,
        avatarUrl: String?,
        isHostFlag: Boolean,
        nonce: String = ""
    ) {
        val now = System.currentTimeMillis()
        _members.update { list ->
            val existing = list.firstOrNull { it.userId == userId }
            val row = governance.rowOf(nonce)
            val member = Member(
                userId = userId, name = name, avatarUrl = avatarUrl,
                isHost = isHostFlag, lastSeenMs = now, nonce = nonce,
                role = if (isHostFlag) JamGovernance.Role.HOST else row.role,
                allowControl = if (isHostFlag) true else row.allowControl,
                allowVolume = if (isHostFlag) true else row.allowVolume
            )
            if (existing != null) list.map { if (it.userId == userId) member else it } else list + member
        }
    }

    /** Seeds self into roster + device map right after create/join. */
    fun noteSelf() {
        deviceUserMap[deviceId] = myUserId()
        noteMember(myUserId(), myDisplayName, myAvatarUrl, isHost(), deviceId)
        refreshConnStatus()
    }

    fun refreshConnStatus() {
        val electionState = election?.state?.value ?: LeaderlessElectionEngine.ElectionState.BOOTSTRAP
        _connStatus.value = when {
            !isActive() -> ConnStatus.OFFLINE
            !isHost() && System.currentTimeMillis() - lastHostTickAt > 6_000 -> ConnStatus.DEGRADED
            !isHost() && electionState == LeaderlessElectionEngine.ElectionState.ELECTION -> ConnStatus.DEGRADED
            else -> ConnStatus.LIVE
        }
        bumpTelemetry { it.copy(electionState = electionState.name) }
    }

    private fun bumpTelemetry(mutate: (SyncTelemetry) -> SyncTelemetry) {
        // CAS-retry update: telemetry is written from the test/UI thread, the
        // consensus collectors and the 1 s sweeper concurrently — a plain
        // read-modify-write interleaves and silently reverts fields (e.g. a
        // racing electionState write stomping a just-landed authorityEpoch).
        _syncTelemetry.update(mutate)
    }

    // ═══════════════ Runtime & loops (FGS-tied) ═══════════════

    /**
     * Public re-tether (PlaybackService self-healing boot): loops come back
     * up after a service recreation for an already-active session.
     */
    fun startRuntime() {
        if (sessionEndedLocally || _session.value == null) return
        startFgsLoops()
    }

    /** Mesh/frame plumbing boot. Called by create/join. */
    private fun startRuntime(session: JamSession) {
        sessionEndedLocally = false
        NativeBridge.jamClockReset()
        NativeBridge.kalmanPllReset()
        NativeBridge.jamCrdtReset()
        elementIndex.clear()
        cadTrackCache.clear()
        localOpIds.clear()
        peerRadar.clear()
        _lastHandshake.value = null
        openOutbox()
        tickSeqCounter.set(0)
        lastRegimeChangeSyncedMs = nowSynced()

        // Boot the Rust mesh (Engineer 2): LAN discovery + WebRTC overlay.
        val meshUp = NativeBridge.p2pStart(session.id, deviceId, enableLan = true, enableWebRtc = true)
        SLog.i("JamRoom", "mesh start (${if (meshUp) "up" else "unavailable — loopback mode"}): session=${session.id}")

        consensusJobs.forEach { it.cancel() }
        consensusJobs.clear()
        wireConsensus(session)

        // Ingress: raw mesh frames → binary decoder → dispatcher.
        // UNDISPATCHED start: the collect subscription latches synchronously
        // before startRuntime() returns, so a frame arriving in the very next
        // line (a synchronous mesh handshake, or the test mock's inject) can
        // never fall into the replay-0 emission window and vanish.
        ingressJob?.cancel()
        ingressJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            NativeBridge.incomingFrames.collect { ev ->
                val frame = JamWire.decode(ev.payload) ?: return@collect
                runCatching { onWireFrame(ev.peerIdHex, frame) }
                    .onFailure { SLog.w("JamWire", "frame dispatch failed (${JamWire.Msg.nameOf(frame.msgType)})", it) }
            }
        }

        startFgsLoops()
        sweeperJob?.cancel()
        sweeperJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                _members.update { list ->
                    list.filter { now - it.lastSeenMs < 15_000 }
                        .map { m -> if (m.userId == myUserId()) m.copy(lastSeenMs = now) else m }
                }
                // Radar freshness window + telemetry refresh.
                peerRadar.entries.removeIf { now - it.value.lastSeenMs > 15_000 }
                _meshPeers.value = peerRadar.values.sortedByDescending { it.isHost }
                bumpTelemetry {
                    it.copy(
                        ptpLocked = clockLocked(),
                        resamplerRateScalar = com.streamify.app.media.audio.SyncAudioProcessor.kalmanSpeedScalar,
                        authorityEpoch = election?.authorityEpoch?.value ?: 0L
                    )
                }
                refreshConnStatus()
            }
        }
    }

    private fun openOutbox() {
        val ctx = TrackRepository.appContext ?: return
        outboxReady = runCatching {
            val db = java.io.File(ctx.filesDir, "jam_outbox.db")
            NativeBridge.jamOutboxOpen(db.absolutePath)
        }.getOrDefault(false)
    }

    /**
     * Guest join handshake: asks the leader for authoritative state and
     * suspends until the TRACK_CHANGE lands (or timeout → null, caller may
     * retry). Replaces the v3 DB-row reconcile.
     */
    suspend fun awaitLeaderHandshake(timeoutMs: Long = 6_000L): HandshakeSnapshot? {
        if (!isActive() || isHost()) return null
        requestJoinState()
        return withContext(Dispatchers.Default) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                _lastHandshake.value?.let { return@withContext it }
                delay(50)
            }
            null
        }
    }

    // ═══════════════ Teardown ═══════════════

    fun leaveSession(endForEveryone: Boolean) {
        val s = activeSession()
        if (s != null) {
            if (endForEveryone && isHost()) {
                broadcastFrame(JamWire.Msg.SESSION_END, JamWire.encodeSessionEnd(deviceId))
            } else {
                broadcastFrame(JamWire.Msg.LEAVE, JamWire.encodeLeave(deviceId))
            }
        }
        endLocally()
    }

    private fun endLocally() {
        sessionEndedLocally = true
        stopFgsLoops()
        election?.stop()
        reconciler?.stop()
        election = null
        reconciler = null
        consensusJobs.forEach { it.cancel() }
        consensusJobs.clear()
        ingressJob?.cancel()
        ingressJob = null
        sweeperJob?.cancel()
        sweeperJob = null
        NativeBridge.p2pStop()
        NativeBridge.jamClockReset()
        NativeBridge.kalmanPllReset()
        NativeBridge.ptpReset()
        _members.value = emptyList()
        _queue.value = emptyList()
        _meshPeers.value = emptyList()
        peerRadar.clear()
        pendingNextIsTrack = null
        _connStatus.value = ConnStatus.OFFLINE
        _session.value = null
        latestAppliedEpoch = Long.MIN_VALUE
        lastHostTickAt = 0L
        lastVerifiedHostTick = null
        currentHostTrack = null
        announcedNextId = null
        // Phase 1 teardown: topology + governance mirror the room lifetime —
        // the block list dies with the room, exactly like Spotify rooms.
        _topology.value = JamTopology.MULTI_RENDER
        latestTopologyEpoch = 0L
        governance.reset()
        _governanceRows.value = emptyList()
        _memberReports.value = emptyList()
        // Restore local render in case we left while suppressed (guest in
        // SINGLE_RENDER) — the next session must never start muted.
        _commands.tryEmit(Command.ApplyRenderSuppression(false))
    }

    // ═══════════════ Player-bridge & FGS attachment ═══════════════

    /** Attached by the app shell; nullable-safe everywhere by design. */
    internal var bridge: Bridge? = null
    fun attachBridge(b: Bridge?) { bridge = b }

    /** Playback probes attached with the bridge: [positionMs, durationMs, playing]. */
    @Volatile var playbackProbe: (() -> LongArray)? = null
    fun attachPlaybackProbe(p: (() -> LongArray)?) { playbackProbe = p }

    private var fgsScope: CoroutineScope? = null
    private val runtimeJobs = mutableListOf<Job>()

    /**
     * The distributed loops are tethered to the PlaybackService
     * foreground-service scope — they must survive navigation and hold
     * network priority while music plays.
     */
    fun attachRuntimeScope(scope: CoroutineScope?) {
        fgsScope = scope
        if (scope != null && isActive()) startFgsLoops()
        if (scope == null) stopFgsLoops()
    }

    private fun startFgsLoops() {
        val loopScope = fgsScope ?: return
        if (runtimeJobs.any { it.isActive }) return
        runtimeJobs.clear()
        runtimeJobs += loopScope.launch { leaderLivenessLoop() }
        runtimeJobs += loopScope.launch { outboxFlushLoop() }
        runtimeJobs += loopScope.launch { presencePulseLoop() }
        runtimeJobs += loopScope.launch { clockSyncLoop() }
        SLog.i("JamRuntime", "FGS-tied loops started (${runtimeJobs.size})")
    }

    private fun stopFgsLoops() {
        runtimeJobs.forEach { it.cancel() }
        runtimeJobs.clear()
    }

    /**
     * Leader keep-alive: guarantees a heartbeat even when the UI process is
     * gone or playback is PAUSED (PlayerTicker only ticks while playing) —
     * guests must never mistake a paused-but-alive host for a dead one and
     * fire a spurious election.
     */
    private suspend fun leaderLivenessLoop() {
        while (true) {
            delay(1_000)
            if (!isActive() || !isHost()) continue
            if (System.currentTimeMillis() - lastSelfHeartbeatAt < 2_000) continue // ticker alive
            val probe = playbackProbe?.invoke()
            val pos = probe?.getOrNull(0)?.coerceAtLeast(0L) ?: 0L
            val playing = (probe?.getOrNull(2) ?: 0L) == 1L
            heartbeatTick(currentHostTrack, pos, playing)
        }
    }

    // ── Outbox flush loop: rebroadcast undelivered ops until the mesh acks ──

    private suspend fun outboxFlushLoop() {
        var gcCounter = 0
        while (true) {
            delay(1_500)
            if (!isActive() || !outboxReady) continue
            val session = activeSession() ?: continue

            NativeBridge.jamOutboxReplay(30_000L)
            val batch = NativeBridge.jamOutboxPoll(session.sessionCode, 32)
            if (batch.isEmpty()) {
                if (++gcCounter % 200 == 0) NativeBridge.jamOutboxGc(24L * 60 * 60 * 1000)
                continue
            }
            val ackIds = ArrayList<Long>(batch.size / 7)
            var i = 0
            while (i + 6 < batch.size) {
                val opId = batch[i]
                val meta = batch[i + 1]
                val oType = ((meta shr 8) and 0xFF).toInt()
                val oCad = batch[i + 2]
                val fracBits = batch[i + 3]
                val oTarget = batch[i + 4]
                val op = JamOpWire(
                    opId = opId, sender = senderPacked, type = oType, policy = 0,
                    cadId = oCad, frac = Double.fromBits(fracBits), target = oTarget
                )
                val sent = broadcastFrame(JamWire.Msg.OP, JamWire.encodeOp(deviceId, currentEpoch(), op))
                if (sent) ackIds.add(opId)
                i += 7
            }
            if (ackIds.isNotEmpty()) NativeBridge.jamOutboxAck(ackIds.toLongArray())
            delay(250)
        }
    }

    // ── Presence pulse: roster + radar freshness ────────────────────────────

    private suspend fun presencePulseLoop() {
        while (true) {
            // Gap #11 degraded-mode policy: past 16 members the presence
            // pulse backs off (3 s → 5 s) so 32-member rooms do not melt the
            // mesh with redundant identity frames.
            val rosterSize = _members.value.size
            delay(if (rosterSize >= 16) 5_000L else 3_000L)
            if (isActive()) pulsePresence(myDisplayName, myAvatarUrl)
        }
    }

    // ── Clock-sync probe loop (PTP over mesh) ──────────────────────────────

    private suspend fun clockSyncLoop() {
        while (true) {
            delay(1_500)
            if (clockSyncProbeDue()) fireClockSyncProbe()
        }
    }

    /** Guest-side shadow-prebuffer hook fired when a leader NEXT_IS lands. */
    @Volatile var onNextIsListener: ((Track) -> Unit)? = null

    // ═══════════════ Test seam ═══════════════

    /** Full state wipe between JVM tests (singleton engine). */
    internal fun resetForTest() {
        endLocally()
        localOpIds.clear()
        elementIndex.clear()
        cadTrackCache.clear()
        fracByCad.clear()
        addedByMap.clear()
        deviceUserMap.clear()
        epochCounter.set(0)
        syncProbeCounter.set(0)
        outboxReady = false
        pendingInviteCode = null
        pendingPairingPayload = null
        cachedLocalUserId = null
        latestTopologyEpoch = 0L
        governance.reset()
        _governanceRows.value = emptyList()
        _memberReports.value = emptyList()
        _topology.value = JamTopology.MULTI_RENDER
    }
}
