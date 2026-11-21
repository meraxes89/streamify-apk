package com.streamify.app.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ConnectViewModel (Gap #52) — thin UI-state adapter over the runtime
 * singletons so the picker sheet stays dumb. All decision logic lives in
 * [ConnectSessionCoordinator] (JVM-tested); this class only:
 *  • refreshes the registry while the sheet is open,
 *  • forwards transfer taps with the caller-supplied session snapshot,
 *  • owns the transient "connecting to …" row highlight.
 */
class ConnectViewModel(
    private val registry: ConnectDeviceRegistry = ConnectRuntime.registry,
    private val coordinator: ConnectSessionCoordinator = ConnectRuntime.coordinator
) : ViewModel() {

    val rows: StateFlow<List<ConnectDeviceRow>> = registry.rows

    val session: StateFlow<ConnectSessionState> = coordinator.state

    /** Set while a transfer handshake is in flight (spinner on the row). */
    private val _pendingTransfer = MutableStateFlow<String?>(null)
    val pendingTransfer: StateFlow<String?> = _pendingTransfer.asStateFlow()

    fun onSheetOpened() {
        viewModelScope.launch {
            runCatching { registry.refresh() }
        }
    }

    fun requestTransfer(target: ConnectDevice, snapshot: PlaybackSnapshot) {
        if (_pendingTransfer.value != null) return
        _pendingTransfer.value = target.id
        viewModelScope.launch {
            try {
                coordinator.transferTo(target, snapshot)
                if (!target.isLocal) {
                    ConnectRuntime.rememberDevice(target)
                }
            } finally {
                _pendingTransfer.value = null
            }
        }
    }

    /** Volume changes from the shared slider, ACL-filtered by the coordinator. */
    fun setRemoteVolume(volume: Float) {
        viewModelScope.launch {
            coordinator.dispatch(ConnectCommand.SetVolume(volume))
        }
    }
}
