package com.maxrave.simpmusic.expect.ui

import androidx.compose.runtime.Composable
import kotlinx.browser.window

@Composable
actual fun LibrarySyncDialog(onDismiss: () -> Unit) {
    onDismiss()
}

actual fun restartAfterSyncedSettings() {
    window.location.reload()
}
