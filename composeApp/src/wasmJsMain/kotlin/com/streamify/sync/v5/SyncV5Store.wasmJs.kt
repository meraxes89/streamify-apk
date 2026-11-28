package com.streamify.sync.v5

import kotlinx.browser.window

/**
 * WasmJS [SyncV5Store] with real `window.localStorage` persistence and [LongAsStringSerializer]
 * 64-bit HLC/cursor safety.
 */
class WasmSyncV5Store : SyncV5Store by DataStoreSyncV5Store(
    readSnapshot = { runCatching { window.localStorage.getItem(STORAGE_KEY) }.getOrNull() },
    writeSnapshot = { payload ->
        runCatching {
            if (payload.isEmpty()) {
                window.localStorage.removeItem(STORAGE_KEY)
            } else {
                window.localStorage.setItem(STORAGE_KEY, payload)
            }
        }
    },
) {
    companion object {
        const val STORAGE_KEY = "streamify_sync_v5_store"
    }
}

actual fun createPlatformSyncV5Store(): SyncV5Store = WasmSyncV5Store()
