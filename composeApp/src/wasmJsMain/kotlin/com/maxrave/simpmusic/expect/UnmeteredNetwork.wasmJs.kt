package com.maxrave.simpmusic.expect

/**
 * Fail safe on Wasm: browsers do not reliably expose unmetered status across
 * engines, so do not assume the connection is unmetered.
 */
actual fun isOnUnmeteredNetwork(): Boolean = false
