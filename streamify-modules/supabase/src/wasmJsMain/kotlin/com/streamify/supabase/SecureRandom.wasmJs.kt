package com.streamify.supabase

private fun nextCryptoRandomByte(): Int =
    js("crypto.getRandomValues(new Uint8Array(1))[0]")

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    for (i in 0 until size) {
        bytes[i] = nextCryptoRandomByte().toByte()
    }
    return bytes
}
