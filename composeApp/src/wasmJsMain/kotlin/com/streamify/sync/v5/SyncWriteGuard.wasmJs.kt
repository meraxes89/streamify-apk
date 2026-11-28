package com.streamify.sync.v5

import kotlinx.coroutines.withContext

class WasmSyncWriteGuard : SyncWriteGuard {
    override suspend fun <T> withRemoteApply(block: suspend () -> T): T =
        withContext(StreamifyRemoteApplyContext) { block() }

    override suspend fun isRemoteApplying(): Boolean = hasRemoteApplyContext()
}

actual fun createPlatformSyncWriteGuard(): SyncWriteGuard = WasmSyncWriteGuard()
