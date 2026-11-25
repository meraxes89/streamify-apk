package com.maxrave.simpmusic.data.update

import kotlinx.coroutines.flow.StateFlow

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateDownloadState {
        val progress: Float
            get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    }
    data object Verifying : UpdateDownloadState
    data class Ready(val filePath: String) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}

expect object PlatformUpdater {
    val state: StateFlow<UpdateDownloadState>
    fun startDownload(tag: String, url: String, expectedSha256: String = "")
    fun installOrApply(onFallbackOpenUrl: (String) -> Unit, fallbackUrl: String)
    fun reset()
}
