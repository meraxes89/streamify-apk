package com.streamify.shared

import com.streamify.shared.engine.EnginePort
import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.model.PlayerCommandResult
import com.streamify.shared.model.positionMsFromDacClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [EnginePort] — the hermetic stand-in proving the shared
 * presentation layer against a scripted engine WITHOUT any native library
 * (the shared suite runs on both compile seams and never needs a .so).
 *
 * StateFlow-backed: the [controller] drives pushes by hand (mirroring how
 * the real engine's listener bridge updates its StateFlow from the
 * dispatcher thread), and [dispatchedCommands] records the reverse-plane
 * traffic for assertion.
 */
class FakeEnginePort(
    initial: PlaybackSnapshot = PlaybackSnapshot.IDLE,
) : EnginePort {

    private val _snapshots = MutableStateFlow(initial)

    /** Drive engine pushes by hand (the "dispatcher thread" stand-in). */
    val controller: MutableStateFlow<PlaybackSnapshot> = _snapshots

    /**
     * Pushes a snapshot onto the flow, deriving
     * [PlaybackSnapshot.positionMs] from [PlaybackSnapshot.clock] exactly
     * the way the real desktop gateway lowers engine snapshots
     * (see `DesktopEnginePort`'s lowering) — the port contract itself,
     * not a test convenience, so UI-state assertions see the same shape
     * production code renders.
     */
    fun push(snapshot: PlaybackSnapshot) {
        val clock = snapshot.clock
        _snapshots.value =
            if (snapshot.positionMs == null && clock != null) {
                snapshot.copy(positionMs = positionMsFromDacClock(clock))
            } else {
                snapshot
            }
    }

    /** Commands dispatched through [send], in arrival order. */
    val dispatchedCommands = mutableListOf<PlayerCommand>()

    /** Result reported by the next [send] call (default APPLIED). */
    var nextResult: PlayerCommandResult = PlayerCommandResult.APPLIED

    /** When true, [send]/reads behave like an unavailable engine lane. */
    var failAll: Boolean = false

    override val snapshots: StateFlow<PlaybackSnapshot> = _snapshots.asStateFlow()

    override suspend fun send(command: PlayerCommand): PlayerCommandResult {
        dispatchedCommands.add(command)
        return if (failAll) PlayerCommandResult.FAILED else nextResult
    }

    override suspend fun currentSnapshot(): PlaybackSnapshot? =
        if (failAll) null else _snapshots.value

    override suspend fun createJamSession(sessionName: String): String? =
        if (failAll) null else sessionName

    override suspend fun jamStateHash(): String? =
        if (failAll) null else TEST_STATE_HASH

    override fun close() = Unit

    companion object {
        const val TEST_STATE_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
