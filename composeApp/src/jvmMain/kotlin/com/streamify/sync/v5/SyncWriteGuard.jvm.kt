package com.streamify.sync.v5

import com.maxrave.data.librarysync.SyncApplying
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class JvmSyncWriteGuard : SyncWriteGuard {
    override suspend fun <T> withRemoteApply(block: suspend () -> T): T =
        withContext(SyncApplying + StreamifyRemoteApplyContext) { block() }

    override suspend fun isRemoteApplying(): Boolean =
        coroutineContext[SyncApplying] != null || hasRemoteApplyContext()
}

actual fun createPlatformSyncWriteGuard(): SyncWriteGuard = JvmSyncWriteGuard()
