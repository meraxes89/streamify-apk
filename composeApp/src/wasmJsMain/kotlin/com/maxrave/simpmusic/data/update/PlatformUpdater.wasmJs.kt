package com.maxrave.simpmusic.data.update

import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object PlatformUpdater {
    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    actual val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    actual fun startDownload(tag: String, url: String, expectedSha256: String) {
        _state.value = UpdateDownloadState.Ready("web")
    }

    actual fun canAutoInstall(): Boolean = false

    actual fun installOrApply(onFallbackOpenUrl: (String) -> Unit, fallbackUrl: String) {
        window.location.reload()
    }

    actual fun reset() {
        _state.value = UpdateDownloadState.Idle
    }
}
