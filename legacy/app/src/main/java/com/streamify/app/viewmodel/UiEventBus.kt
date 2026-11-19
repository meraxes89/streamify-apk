package com.streamify.app.viewmodel

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class UiEvent {
    data class ShowSnackbar(val message: String) : UiEvent()
}

object UiEventBus {
    // Buffered + DROP_OLDEST: the old zero-buffer flow SUSPENDED emitters when
    // no collector was attached (e.g. during splash/onboarding) and dropped
    // events under burst. Emitters are now never blocked; collectors see the
    // most recent events.
    private val _events = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun emitEvent(event: UiEvent) {
        _events.tryEmit(event)
    }
}
