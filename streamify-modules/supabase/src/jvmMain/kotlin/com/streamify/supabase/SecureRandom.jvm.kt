package com.streamify.supabase

import java.security.SecureRandom

private val csprng = SecureRandom()

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    csprng.nextBytes(bytes)
    return bytes
}
