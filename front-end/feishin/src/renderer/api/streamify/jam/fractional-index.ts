/**
 * streamify/jam/fractional-index.ts - the fractional ordering engine,
 * ported 1:1 from `rust/crates/streamify-jam-crdt/src/fractional_index.rs`
 * (itself the Kotlin `FractionalIndexEngine` port - one frozen semantic,
 * three runtimes: Android, wasm, and now the feishin TS mirror).
 *
 * Generates IEEE 754 double indices that sort between neighbours, so
 * inserts never require renumbering. Doubles survive ~50 mid-inserts
 * before mantissa exhaustion; `needsRebalance` flags the wall so the host
 * can run a compaction pass (re-space all indices) rather than silently
 * degrading.
 *
 * All values are strictly positive finite doubles - required by the
 * composite-key contract ((frac.to_bits(), add_op_id)), where bit order
 * equals numeric order exactly for this domain.
 */

/** Default spacing when the queue is empty / extending at the tail.
 * Kotlin `HEAD` (the prepend anchor): 0.0625. */
export const FRAC_HEAD = 0.0625;
/** Kotlin `FIRST_INDEX`: the anchor for the first element / tail append
 * from empty. */
export const FRAC_FIRST_INDEX = 0.5;

/** Kotlin `FractionalIndexEngine.Midpoint`. */
export interface FracMidpoint {
    needsRebalance: boolean;
    value: number;
}

/** Index AFTER the last element (tail append). */
export function fracAfter(last: null | number): FracMidpoint {
    if (last === null || last === undefined) {
        return { needsRebalance: false, value: FRAC_FIRST_INDEX };
    }
    if (last <= 0) {
        return { needsRebalance: false, value: FRAC_FIRST_INDEX };
    }
    if (!Number.isFinite(last + 1)) {
        return { needsRebalance: true, value: last };
    }
    return { needsRebalance: false, value: last + 1 };
}

/** Index BEFORE the first element (prepend). */
export function fracBefore(first: null | number): FracMidpoint {
    if (first === null || first === undefined) {
        return { needsRebalance: false, value: FRAC_HEAD };
    }
    if (first <= 0) {
        return { needsRebalance: false, value: FRAC_HEAD };
    }
    // Kotlin: first / 2.0 <= 0.0 || first == first / 2.0 - the underflow
    // wall (halving a denormal-ish anchor no longer moves).
    const half = first / 2;
    if (half <= 0 || first === half) {
        return { needsRebalance: true, value: first };
    }
    return { needsRebalance: false, value: half };
}

/** Index strictly BETWEEN two neighbours. Returns the lower anchor with
 * `needsRebalance=true` when the gap has collapsed below usable precision. */
export function fracBetween(prev: null | number, next: null | number): FracMidpoint {
    const hasPrev = prev !== null && prev !== undefined;
    const hasNext = next !== null && next !== undefined;
    if (!hasPrev && !hasNext) {
        return fracAfter(null);
    }
    if (!hasPrev) {
        return fracBefore(next);
    }
    if (!hasNext) {
        return fracAfter(prev);
    }
    const p = prev as number;
    const n = next as number;
    if (n <= p) {
        // inverted/gapped input - host should fix
        return { needsRebalance: true, value: p };
    }
    const mid = p + (n - p) / 2;
    if (!Number.isFinite(mid)) {
        return { needsRebalance: true, value: p };
    }
    if (mid === p || mid === n) {
        // mantissa exhausted
        return { needsRebalance: true, value: mid };
    }
    return { needsRebalance: false, value: mid };
}

/** True when two fracs are indistinguishable in float space.
 * Kotlin: `a == b || abs(a - b) <= maxOf(a, b).ulp`. */
export function fracCollide(a: number, b: number): boolean {
    if (a === b) {
        return true;
    }
    const spacing = ulpOf(Math.max(a, b));
    return Math.abs(a - b) <= spacing;
}

/**
 * Insert helper used by the UI layer: picks a slot relative to the current
 * CRDT view (already ordered by frac). `position` is the target list
 * position (0..=len); clamped internally.
 */
export function fracIndexForInsertAt(position: number, orderedFracs: number[]): FracMidpoint {
    const pos = Math.min(Math.max(position, 0), orderedFracs.length);
    const prev = pos === 0 ? null : orderedFracs[pos - 1];
    const next = pos >= orderedFracs.length ? null : orderedFracs[pos];
    return fracBetween(prev, next);
}

/**
 * Kotlin ulp: the positive spacing to the next representable value at this
 * magnitude. For finite x, nextUp(x) - x is the ULP; at 0.0 this is the
 * minimum subnormal. JS exposes nextUp via DataView bit-pattern increments.
 */
export function ulpOf(x: number): number {
    if (Number.isNaN(x)) {
        return Number.NaN;
    }
    if (!Number.isFinite(x)) {
        return Number.POSITIVE_INFINITY;
    }
    if (x === 0) {
        return Number.MIN_VALUE; // 5e-324 - the minimum subnormal
    }
    if (x < 0) {
        return ulpOf(-x);
    }
    // Next representable double above x: increment the bit pattern.
    const view = new DataView(new ArrayBuffer(8));
    view.setFloat64(0, x, true);
    const bits = view.getBigUint64(0, true);
    view.setBigUint64(0, bits + 1n, true);
    return view.getFloat64(0, true) - x;
}
