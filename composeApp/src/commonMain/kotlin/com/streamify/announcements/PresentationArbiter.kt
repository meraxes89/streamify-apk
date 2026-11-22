package com.streamify.announcements

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single presentation slot coordinator. Context-aware deferral ensures
 * active music playback, onboarding, or existing dialogs are never interrupted.
 */
class PresentationArbiter {

    private val _activeCommand = MutableStateFlow<Announcement?>(null)
    val activeCommand: StateFlow<Announcement?> = _activeCommand.asStateFlow()

    private val pendingQueue = mutableListOf<Announcement>()

    fun tryPresent(announcement: Announcement, ctx: UiContext): Boolean {
        if (ctx.isPlaying || ctx.isModalActive || ctx.isOnboarding) {
            if (!pendingQueue.contains(announcement)) {
                pendingQueue.add(announcement)
            }
            return false
        }

        if (_activeCommand.value == null) {
            _activeCommand.value = announcement
            return true
        }

        return false
    }

    fun dismissActive() {
        _activeCommand.value = null
    }

    fun onContextChanged(ctx: UiContext): Announcement? {
        if (!ctx.isPlaying && !ctx.isModalActive && !ctx.isOnboarding && _activeCommand.value == null) {
            if (pendingQueue.isNotEmpty()) {
                val next = pendingQueue.removeAt(0)
                _activeCommand.value = next
                return next
            }
        }
        return null
    }
}
