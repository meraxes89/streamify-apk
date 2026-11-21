package com.streamify.app.jam

import kotlin.math.abs
import kotlin.math.ulp

/**
 * PHASE 2 — Fractional ordering for the Jam CRDT queue.
 *
 * Generates IEEE 754 double indices that sort between neighbours, so inserts
 * never require renumbering. Doubles survive ~50 mid-inserts before mantissa
 * exhaustion; [Midpoint.needsRebalance] flags the wall so the host can run a
 * compaction pass (re-space all indices) rather than silently degrading.
 *
 * All values are strictly positive finite doubles — required by the native
 * composite-key contract ((frac.to_bits(), add_op_id)), where bit order equals
 * numeric order exactly for this domain.
 */
object FractionalIndexEngine {

    /** Default spacing when the queue is empty / extending at the tail. */
    private const val HEAD: Double = 0.0625
    const val FIRST_INDEX: Double = 0.5

    data class Midpoint(
        val value: Double,
        val needsRebalance: Boolean
    )

    /** Index AFTER the last element (tail append). */
    fun after(last: Double?): Midpoint = when {
        last == null || last <= 0.0 -> Midpoint(FIRST_INDEX, false)
        java.lang.Double.isInfinite(last + 1.0) -> Midpoint(last, true)
        else -> Midpoint(last + 1.0, false)
    }

    /** Index BEFORE the first element (prepend). */
    fun before(first: Double?): Midpoint = when {
        first == null || first <= 0.0 -> Midpoint(HEAD, false)
        first / 2.0 <= 0.0 || first == first / 2.0 -> Midpoint(first, true) // underflow wall
        else -> Midpoint(first / 2.0, false)
    }

    /**
     * Index strictly BETWEEN two neighbours. Returns [Midpoint] with
     * needsRebalance=true when the gap has collapsed below usable precision
     * (midpoint equals an endpoint in float space).
     */
    fun between(prev: Double?, next: Double?): Midpoint {
        return when {
            prev == null && next == null -> Midpoint(FIRST_INDEX, false)
            prev == null -> before(next)
            next == null -> after(prev)
            next <= prev -> Midpoint(prev, true) // inverted/gapped input — host should fix
            else -> {
                val mid = prev + (next - prev) / 2.0
                when {
                    !mid.isFinite() -> Midpoint(prev, true)
                    mid == prev || mid == next -> Midpoint(mid, true) // mantissa exhausted
                    else -> Midpoint(mid, false)
                }
            }
        }
    }

    /**
     * Insert helper used by the UI layer: picks a slot relative to the current
     * CRDT view (already ordered by frac).
     *
     * @param position target list position (0..size); clamped internally.
     */
    fun indexForInsertAt(position: Int, orderedFracs: List<Double>): Midpoint {
        val pos = position.coerceIn(0, orderedFracs.size)
        val prev = if (pos == 0) null else orderedFracs[pos - 1]
        val next = if (pos >= orderedFracs.size) null else orderedFracs[pos]
        return if (prev == null && next == null) after(null) else between(prev, next)
    }

    /** True when two fracs are indistinguishable in float space. */
    fun collide(a: Double, b: Double): Boolean =
        a == b || abs(a - b) <= maxOf(a, b).ulp
}
