package com.streamify.sync

import platform.Foundation.NSLock
import platform.Foundation.NSRecursiveLock

private val registryLock = NSLock()
private val lockRegistry = mutableMapOf<Any, NSRecursiveLock>()

@PublishedApi
internal fun acquireNativeLock(lock: Any): NSRecursiveLock {
    registryLock.lock()
    return try {
        lockRegistry.getOrPut(lock) { NSRecursiveLock() }
    } finally {
        registryLock.unlock()
    }
}

internal actual inline fun <R> synchronizedLock(lock: Any, block: () -> R): R {
    val nativeLock = acquireNativeLock(lock)
    nativeLock.lock()
    return try {
        block()
    } finally {
        nativeLock.unlock()
    }
}
