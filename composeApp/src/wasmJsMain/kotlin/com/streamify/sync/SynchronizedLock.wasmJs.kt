package com.streamify.sync

internal actual inline fun <R> synchronizedLock(lock: Any, block: () -> R): R = block()
