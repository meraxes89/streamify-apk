package com.streamify.sync.v5

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Reflection-free echo suppression guard shared across Android, JVM, iOS, and WasmJS.
 */
interface SyncWriteGuard {
    suspend fun <T> withRemoteApply(block: suspend () -> T): T
    suspend fun isRemoteApplying(): Boolean
}

/**
 * Pure Kotlin CoroutineContext marker present on all 4 KMP targets during remote sync apply.
 */
object StreamifyRemoteApplyContext :
    AbstractCoroutineContextElement(StreamifyRemoteApplyContext),
    CoroutineContext.Key<StreamifyRemoteApplyContext>

expect fun createPlatformSyncWriteGuard(): SyncWriteGuard

internal suspend fun hasRemoteApplyContext(): Boolean =
    coroutineContext[StreamifyRemoteApplyContext] != null
