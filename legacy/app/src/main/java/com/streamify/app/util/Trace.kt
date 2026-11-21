package com.streamify.app.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight correlation IDs for the admin terminal.
 *
 * A new trace starts at each meaningful user intent (search submit, track tap)
 * and every subsequent log line can embed [current] so a full request chain —
 * search -> tap -> hydrate -> resolve race -> gate -> ExoPlayer — is greppable
 * as one unit: `[7f3a] ...`.
 */
object Trace {

    private val counter = AtomicLong(0)

    /** Most recent active trace. Volatile: written on main, read on IO threads. */
    @Volatile
    var current: String = "-"
        private set

    fun new(seed: String = ""): String {
        val n = counter.incrementAndGet()
        val id = String.format("%04x", (n * 0x9E3779B9L ushr 16) xor (seed.hashCode().toLong() and 0xFFFF))
        current = id
        return id
    }

    /** Prefix helper for call sites: SLog.d(TAG, "${Trace.pfx()}message") */
    fun pfx(): String = "[$current] "
}
