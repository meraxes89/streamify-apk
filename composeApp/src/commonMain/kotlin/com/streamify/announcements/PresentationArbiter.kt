package com.streamify.announcements

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single presentation slot coordinator. Context-aware deferral ensures
 * active music playback, onboarding, or existing dialogs are never interrupted.
 */
class PresentationArbiter {

    private val mutex = Mutex()

    private val _activeCommand = MutableStateFlow<Announcement?>(null)
    val activeCommand: StateFlow<Announcement?> = _activeCommand.asStateFlow()

    private val pendingQueue = mutableListOf<Announcement>()

    suspend fun tryPresent(announcement: Announcement, ctx: UiContext): Boolean = mutex.withLock {
        if (ctx.isPlaying || ctx.isModalActive || ctx.isOnboarding) {
            if (!pendingQueue.contains(announcement)) {
                pendingQueue.add(announcement)
            }
            return@withLock false
        }

        if (_activeCommand.value == null) {
            _activeCommand.value = announcement
            return@withLock true
        }

        false
    }

    suspend fun dismissActive() = mutex.withLock {
        _activeCommand.value = null
    }

    suspend fun onContextChanged(ctx: UiContext): Announcement? = mutex.withLock {
        if (!ctx.isPlaying && !ctx.isModalActive && !ctx.isOnboarding && _activeCommand.value == null) {
            if (pendingQueue.isNotEmpty()) {
                val next = pendingQueue.removeAt(0)
                _activeCommand.value = next
                return@withLock next
            }
        }
        null
    }
}
