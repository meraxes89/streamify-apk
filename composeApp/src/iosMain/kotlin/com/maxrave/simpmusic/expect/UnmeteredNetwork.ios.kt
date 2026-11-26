package com.maxrave.simpmusic.expect

/**
 * Fail safe on iOS until NWPathMonitor is wired: do not assume cellular/unknown
 * connections are unmetered.
 */
actual fun isOnUnmeteredNetwork(): Boolean = false
