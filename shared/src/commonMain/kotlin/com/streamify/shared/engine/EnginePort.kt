package com.streamify.shared.engine

import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.model.PlayerCommandResult
import kotlinx.coroutines.flow.Flow

/**
 * Phase 5 (MP-UI03 / Beta): the platform-agnostic engine seam.
 *
 * Everything the shared presentation layer needs from a sovereign Rust core
 * crosses this ONE interface, in the shared presentation models — no
 * `uniffi.*`, `android.*` or AWT type leaks into commonMain. Each platform
 * implements it with its own binding mode (MP-DEC-02):
 *
 *  * desktop: `com.streamify.shared.desktop.DesktopEnginePort` over the
 *    JNA-mode UniFFI proxies (JVM lane, `streamify-jvm`);
 *  * android: the app's reflective `StreamifyEngineBridge` backend (direct
 *    JNI lane) can back the same contract when the app migrates its player
 *    UI onto the shared controllers — the dogfood cutover (MP-CUT01) is the
 *    staged on-ramp;
 *  * iOS: `EngineGateway` (Swift mirror of this contract) over the direct
 *    Swift UniFFI bindings.
 *
 * Push, event-driven contract (MP-DEC-04): [snapshots] is a cold [Flow]
 * that emits coalesced engine snapshots with strictly monotonic revisions;
 * UI shells collect it and NEVER poll a one-shot getter. [currentSnapshot]
 * exists solely for cold-start hydration (the UDL sanctions exactly one
 * such read: "one-shot read for cold-start UI hydration").
 */
interface EnginePort {

    /**
     * Cold flow of coalesced engine snapshots (strictly monotonic `rev`).
     * The flow is engineered to be collection-safe from any dispatcher;
     * emissions originate on the port's internal bridge off the real-time
     * audio thread.
     */
    val snapshots: Flow<PlaybackSnapshot>

    /**
     * Dispatches one reverse-plane command (MP-C03 / MP-DEC-07). The engine
     * applies its 50-150 ms hardware debounce to identical duplicates — a
     * [PlayerCommandResult.DEBOUNCED] response is a success path. Never
     * throws: failures lower to [PlayerCommandResult.FAILED].
     */
    suspend fun send(command: PlayerCommand): PlayerCommandResult

    /**
     * Cold-start hydration read: the latest coalesced snapshot, or null when
     * the lane cannot answer (engine unavailable / hermetic host).
     */
    suspend fun currentSnapshot(): PlaybackSnapshot?

    /**
     * Creates (or replaces) the engine's local Jam session mirror and
     * returns its name — the real `create_jam_session` surface for the
     * shell's Jam pane. Returns null when the lane is unavailable or the
     * engine rejected the name; never throws.
     */
    suspend fun createJamSession(sessionName: String): String?

    /**
     * Deterministic BLAKE3 state hash of the active Jam session's canonical
     * CRDT fold (64 hex chars), or null when no session is live / the lane
     * is unavailable.
     */
    suspend fun jamStateHash(): String?

    /** Releases the port's bridge resources (idempotent). */
    fun close()
}
