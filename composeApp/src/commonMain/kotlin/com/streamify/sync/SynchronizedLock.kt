package com.streamify.sync

internal expect inline fun <R> synchronizedLock(lock: Any, block: () -> R): R
