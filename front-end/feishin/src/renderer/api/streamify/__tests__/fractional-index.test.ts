/**
 * fractional-index.test.ts - the Kotlin-anchored behavior lock (P3A).
 *
 * The same expectations the Rust port pins (fractional_index.rs tests):
 * these values are frozen semantics shared by Android, wasm, and this TS
 * mirror - a drift here means divergent ordering across runtimes.
 */

import { describe, expect, it } from 'vitest';

import {
    FRAC_FIRST_INDEX,
    FRAC_HEAD,
    fracAfter,
    fracBefore,
    fracBetween,
    fracCollide,
    fracIndexForInsertAt,
    ulpOf,
} from '../jam/fractional-index';

describe('fractional-index — the frozen anchors', () => {
    it('anchors match Kotlin: after(null)=0.5, before(null)=0.0625', () => {
        expect(fracAfter(null)).toEqual({ needsRebalance: false, value: FRAC_FIRST_INDEX });
        expect(fracAfter(null).value).toBe(0.5);
        expect(fracBefore(null)).toEqual({ needsRebalance: false, value: FRAC_HEAD });
        expect(fracBefore(null).value).toBe(0.0625);
        // Tail append: last + 1.
        expect(fracAfter(3.0)).toEqual({ needsRebalance: false, value: 4 });
        // Prepend: first / 2.
        expect(fracBefore(0.5)).toEqual({ needsRebalance: false, value: 0.25 });
        // between(both null) == after(null).
        expect(fracBetween(null, null).value).toBe(FRAC_FIRST_INDEX);
    });

    it('midpoint and rebalance walls', () => {
        // Clean midpoint between 0.25 and 0.75.
        expect(fracBetween(0.25, 0.75)).toEqual({ needsRebalance: false, value: 0.5 });
        // Inverted / gapped input flags the wall at the LOWER anchor.
        expect(fracBetween(0.75, 0.25)).toEqual({ needsRebalance: true, value: 0.75 });
        // Single-sided: after(prev) / before(next).
        expect(fracBetween(1.0, null).value).toBe(2.0);
        expect(fracBetween(null, 1.0).value).toBe(0.5);
        // Mantissa exhaustion: a run of mid-splits between two ULP-close
        // neighbours collapses - the documented ~50 wall.
        const lo = 0.5;
        let hi = 0.5000000000000002; // two ulps above lo
        let tripped = false;
        for (let i = 0; i < 60; i += 1) {
            const mid = fracBetween(lo, hi);
            if (mid.needsRebalance) {
                tripped = true;
                break;
            }
            hi = mid.value; // keep splitting the upper half
        }
        expect(tripped).toBe(true);
        // Non-positive / underflow anchors fall back or flag.
        expect(fracBefore(0.0).value).toBe(FRAC_HEAD);
        expect(fracAfter(0.0).value).toBe(FRAC_FIRST_INDEX);
    });

    it('insert_at clamps and slices (the UI helper)', () => {
        const fracs = [0.5, 1.0, 2.0];
        // Head insert (position 0) halves the first anchor.
        expect(fracIndexForInsertAt(0, fracs).value).toBe(0.25);
        // Between rows 1 and 2 (position 2): (1.0 + 2.0)/2.
        expect(fracIndexForInsertAt(2, fracs).value).toBe(1.5);
        // Tail append (position 3 == len): last + 1.
        expect(fracIndexForInsertAt(3, fracs).value).toBe(3.0);
        // Out-of-range position clamps to the tail.
        expect(fracIndexForInsertAt(99, fracs).value).toBe(3.0);
        // Empty view: the 0.5 anchor.
        expect(fracIndexForInsertAt(0, []).value).toBe(FRAC_FIRST_INDEX);
    });

    it('collide uses ULP spacing', () => {
        expect(fracCollide(0.5, 0.5)).toBe(true);
        // One ulp apart at 0.5: ulp(0.5) = 2^-53.
        const a = 0.5;
        const b = fracFromBitsSafe(0x3fe0000000000001n);
        expect(fracCollide(a, b)).toBe(true);
        expect(fracCollide(a, b + 1e-9)).toBe(false);
        // Zero-vs-zero collides; zero-vs-anything-real does not.
        expect(fracCollide(0, 0)).toBe(true);
        expect(fracCollide(0, 0.01)).toBe(false);
    });

    it('ulp matches the Kotlin spacing semantics', () => {
        expect(ulpOf(0)).toBe(Number.MIN_VALUE); // 5e-324
        expect(ulpOf(0.5)).toBe(1.1102230246251565e-16); // 2^-53
        expect(ulpOf(1)).toBe(2.220446049250313e-16); // 2^-52
        expect(ulpOf(Number.NaN)).toBeNaN();
    });
});

function fracFromBitsSafe(bits: bigint): number {
    const view = new DataView(new ArrayBuffer(8));
    view.setBigUint64(0, bits, true);
    return view.getFloat64(0, true);
}
