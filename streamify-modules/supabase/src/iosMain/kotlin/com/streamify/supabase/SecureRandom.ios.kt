package com.streamify.supabase

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import platform.posix.arc4random_buf

@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (size > 0) {
        arc4random_buf(bytes.refTo(0), size.toULong())
    }
    return bytes
}
