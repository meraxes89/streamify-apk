package com.streamify.supabase

/**
 * Cryptographically secure random bytes generator for PKCE verifiers and CSRF state nonces.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray
