package com.streamify.desktop.shell

import com.streamify.shared.engine.EnginePort
import com.streamify.shared.model.PlaybackSnapshot
import com.streamify.shared.model.PlayerCommand
import com.streamify.shared.model.PlayerCommandResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * :desktop-local test double for [EnginePort] (its own tiny copy — the
 * shared tree's FakeEnginePort lives in :shared's commonTest source set,
 * which does not publish to consumers).
 *
 * Mirrors the shared double's semantics: StateFlow-driven pushes, command
 * recording, configurable results. Pure JVM — the shell smoke suite never
 * touches the native engine.
 */
class FakeEnginePort(
    initial: PlaybackSnapshot = PlaybackSnapshot.IDLE,
) : EnginePort {

    private val _snapshots = MutableStateFlow(initial)

    val controller: MutableStateFlow<PlaybackSnapshot> = _snapshots

    val dispatchedCommands = mutableListOf<PlayerCommand>()

    var nextResult: PlayerCommandResult = PlayerCommandResult.APPLIED

    override val snapshots: StateFlow<PlaybackSnapshot> = _snapshots.asStateFlow()

    override suspend fun send(command: PlayerCommand): PlayerCommandResult {
        dispatchedCommands.add(command)
        return nextResult
    }

    override suspend fun currentSnapshot(): PlaybackSnapshot? = _snapshots.value

    override suspend fun createJamSession(sessionName: String): String? = sessionName

    override suspend fun jamStateHash(): String? = TEST_STATE_HASH

    override fun close() = Unit

    companion object {
        const val TEST_STATE_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
