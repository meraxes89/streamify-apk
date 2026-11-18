package com.streamify.app.jam

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * JAM TOPOLOGY STATE MACHINE — Phase 1 (BEHIND.md Gap #13)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * A Jam room runs in exactly one of two render topologies:
 *
 *  MULTI_RENDER   — every member renders phase-locked audio locally
 *                  (the v4 default: SyncAudioProcessor + resampler + Kalman
 *                  per device). This is "listen together remotely".
 *
 *  SINGLE_RENDER  — Party Mode. Exactly ONE renderer: the host device (or
 *                  the speaker it is routed to). Every guest suppresses its
 *                  local audio output and becomes an interactive remote
 *                  controller (add / reorder / vote / seek). This matches the
 *                  in-person party reality: one aux cable, one speaker, zero
 *                  multi-room echo, massive battery savings on guests.
 *
 * The machine is PURE — no Android, no coroutines, no JNI — so the full
 * transition contract is provable in JVM unit tests:
 *
 *   INACTIVE     : no session. Any topology request is refused.
 *   MULTI_RENDER : stable remote listening.
 *                    host     -> may switch to SINGLE_RENDER  (party start)
 *                    guest    -> refused (host authority, invariant #1)
 *   SINGLE_RENDER: party mode running.
 *                    host     -> may switch back to MULTI_RENDER (party end)
 *                    guest    -> refused; follows the host's broadcast
 *   Any state    : host loss (Death Pivot) -> the SUCCESSOR inherits the
 *                  topology unchanged (a topology flip mid-party would
 *                  deafen the room; the new host keeps rendering).
 *
 * Guest-side audio discipline under SINGLE_RENDER:
 *   • Local ExoPlayer output is MUTED (volume 0 on the media player, not the
 *     stream — the PLL keeps running silently so a mid-party switch back to
 *     MULTI_RENDER re-locks audio in <50 ms with zero handshake).
 *   • Queue/transport intents still route to the host over the mesh — the
 *     guest UI becomes the remote controller.
 *   • tickIntervalMs stays authoritative — the party clock never stops.
 */
enum class JamTopology {
    /** N phase-locked renderers — remote listening. Default. */
    MULTI_RENDER,

    /** ONE renderer (host speaker) — Party Mode. Guests are remotes. */
    SINGLE_RENDER;

    val isPartyMode: Boolean get() = this == SINGLE_RENDER
}

/**
 * Result of a topology mutation attempt — carries enough detail for both the
 * engine ingress (enforcement) and the UI (why my tap did nothing).
 */
sealed class TopologyDecision {
    /** Applied: the room moved to [topology] at [epoch]. */
    data class Applied(val topology: JamTopology, val epoch: Long) : TopologyDecision()

    /** Refused: the sender is not the leader (invariant #1 host authority). */
    data class RefusedNotHost(val requestedBy: String, val leaderNonce: String?) : TopologyDecision()

    /** Refused: no active session to flip. */
    object RefusedNoSession : TopologyDecision()

    /** Refused: redundant request (room already in that topology). */
    data class RefusedNoOp(val current: JamTopology) : TopologyDecision()

    /** Refused: epoch regression — a stale frame from a superseded regime. */
    data class RefusedStaleEpoch(val frameEpoch: Long, val appliedEpoch: Long) : TopologyDecision()
}

/**
 * Pure transition table. The engine owns the volatile state and calls into
 * this on every local toggle and every inbound TOPOLOGY frame; tests drive
 * the same paths the mesh does.
 */
object JamTopologyMachine {

    const val MAX_MEMBERS_HARD_CAP: Int = 32

    /**
     * Host toggles the room topology (local intent — engine then broadcasts).
     *
     * @param current      the engine's live topology
     * @param isActive     whether a session is live
     * @param isHost       whether THIS device is the leader
     * @param target       requested topology
     * @param nextEpoch    the epoch to stamp on the regime change
     */
    fun request(
        current: JamTopology,
        isActive: Boolean,
        isHost: Boolean,
        target: JamTopology,
        nextEpoch: Long,
        leaderNonce: String?,
        requesterNonce: String = ""
    ): TopologyDecision = when {
        !isActive -> TopologyDecision.RefusedNoSession
        !isHost -> TopologyDecision.RefusedNotHost(requesterNonce, leaderNonce)
        current == target -> TopologyDecision.RefusedNoOp(current)
        else -> TopologyDecision.Applied(target, nextEpoch)
    }

    /**
     * Guest/host ingests a remote TOPOLOGY frame.
     *
     * @param appliedEpoch the highest topology regime epoch this device has
     *                     applied — guards against stale out-of-order frames
     */
    fun ingest(
        current: JamTopology,
        isActive: Boolean,
        senderIsLeader: Boolean,
        frameTopology: JamTopology,
        frameEpoch: Long,
        appliedEpoch: Long
    ): TopologyDecision = when {
        !isActive -> TopologyDecision.RefusedNoSession
        !senderIsLeader -> TopologyDecision.RefusedNotHost("", null)
        frameEpoch <= appliedEpoch && frameTopology != current ->
            TopologyDecision.RefusedStaleEpoch(frameEpoch, appliedEpoch)
        frameTopology == current -> TopologyDecision.RefusedNoOp(current)
        else -> TopologyDecision.Applied(frameTopology, frameEpoch)
    }

    /**
     * Audio-output directive for THIS device under [topology].
     *
     * Hosts always render. Guests render only in MULTI_RENDER. In
     * SINGLE_RENDER a guest keeps its PLL silently warm (see class doc) so
     * the party can flip back with zero audio pause.
     */
    fun rendersLocally(topology: JamTopology, isHost: Boolean): Boolean =
        isHost || topology == JamTopology.MULTI_RENDER

    /**
     * UI directive: does this device show the "remote controller" surface
     * (Playing on host's speaker) instead of local transport?
     */
    fun isRemoteController(topology: JamTopology, isHost: Boolean): Boolean =
        topology == JamTopology.SINGLE_RENDER && !isHost

    /**
     * Roster admission policy (Gap #11): rooms are hard-capped at 32 —
     * Spotify-grade scale. Late arrivals beyond the cap are refused and told
     * the room is full.
     */
    fun admitsMember(currentCount: Int): Boolean = currentCount < MAX_MEMBERS_HARD_CAP
}
