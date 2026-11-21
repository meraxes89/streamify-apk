// ─────────────────────────────────────────────────────────────────────────────
// VENDORED into Streamify from zephyr4289/Weft @ 08e3a53
// (android/weft-core/src/main/kotlin/dev/weft/Steward.kt) per the Engineering
// Directive: Real-Device Integration of the Weft Continuous-State Plane.
//
// Kernel semantics are FROZEN — only the package declaration changed.
// ─────────────────────────────────────────────────────────────────────────────

// Steward.kt — Lifecycle manager for Wefts (Kotlin/JVM)
//
// WHY EXISTS: Manages the lifetime of Weft instances — allocates, binds to
// scope, frees on scope exit, detects leaks. Per 02-KERNEL §7 and the
// corrected boundary (WHITEPAPER §2.1). The Steward is ViewModel-scoped;
// it survives configuration change. Per 05-CONTRACTS AXIOM T: telemetry
// is advisory. See docs/PORTS.md §1 for the I6 mapping table.
//
// STATUS: SOURCE-ONLY, PENDING REAL-DEVICE VERIFICATION.

package com.streamify.app.weft

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/// The Steward: owns Wefts, frees them on scope exit.
/// Per WHITEPAPER §7.3: ViewModel-scoped (survives config change).
/// OPEN for the same reason ViewModel is: tests and previews subclass to
/// simulate onCleared() disposal (the ViewModel pattern's own testing idiom
/// — a final class here broke the JVM test gate's compilation; see the
/// android CI log for 8d6eebf).
open class Steward : ViewModel() {

    private val wefts: MutableMap<Long, Weft> = mutableMapOf()
    private var nextId: Long = 1
    private var released: Boolean = false

    /// Allocate a Weft for elements of type [T] with the given capacity.
    inline fun <reified T> weft(capacity: Int, align: Int = 16): Weft {
        val elemSize = when (T::class) {
            FloatArray::class -> 4
            IntArray::class -> 4
            ShortArray::class -> 2
            ByteArray::class -> 1
            DoubleArray::class -> 8
            LongArray::class -> 8
            else -> throw IllegalArgumentException("Weft<T> supports primitive arrays")
        }
        return weftSized(capacity * elemSize)
    }

    fun weftSized(totalPayloadBytes: Int): Weft {
        check(!released) { "Steward is released" }
        val w = Weft(totalPayloadBytes)
        val id = nextId++
        wefts[id] = w
        return w
    }

    /// Release a single Weft ahead of scope exit. Per the I6 contract (RFC-0001
    /// §6), the writer is revoked BEFORE the buffer becomes unreachable — the
    /// next publish on a revoked Weft is a no-op that ACKs via the epoch
    /// handshake (DROPPED_REVOKED), so a late producer can never write into a
    /// buffer nobody owns. On the JVM, dropping the last reference is the
    /// deallocation; revoke-first is what makes that safe. Idempotent.
    fun release(w: Weft) {
        w.revoke()
        wefts.values.remove(w)
    }

    /// Release all Wefts. Idempotent. Each is revoked before its reference is
    /// dropped (I6 ordering — see [release]).
    fun releaseAll() {
        if (released) return
        released = true
        for (w in wefts.values) w.revoke()
        wefts.clear() // JVM GC handles the buffers
    }

    /// Aggregate stats (advisory per AXIOM T).
    fun stats(): StewardStats {
        var totalPub = 0L
        var totalRead = 0L
        for (w in wefts.values) {
            totalPub += w.tPublishCount()
            totalRead += w.tClaimCount()
        }
        return StewardStats(wefts.size, totalPub, totalRead)
    }

    /// Debug leak detection (advisory).
    fun dumpLeaks(): List<String> {
        return wefts.keys.map { "Weft $it still bound" }
    }

    override fun onCleared() {
        releaseAll()
        super.onCleared()
    }
}

data class StewardStats(val weftCount: Int, val totalPublishes: Long, val totalReads: Long)
