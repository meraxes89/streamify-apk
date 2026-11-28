package com.streamify.sync.v5

import com.streamify.sync.withSyncApplying
import kotlinx.coroutines.withContext

class AndroidSyncWriteGuard : SyncWriteGuard {
    override suspend fun <T> withRemoteApply(block: suspend () -> T): T =
        withContext(withSyncApplying() + StreamifyRemoteApplyContext) { block() }

    override suspend fun isRemoteApplying(): Boolean =
        hasRemoteApplyContext()
}

actual fun createPlatformSyncWriteGuard(): SyncWriteGuard = AndroidSyncWriteGuard()
