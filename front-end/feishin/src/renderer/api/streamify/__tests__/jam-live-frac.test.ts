/**
 * jam-live-frac tests (P4B) - the vendored fractional ordering engine:
 * Kotlin-anchored constants, midpoint walls, collision detection, the
 * UI insert helper, and the respace grid contract the host compaction
 * relies on.
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
} from '../jam-live/jam-live-frac';

describe('jam-live-frac: anchors', () => {
    it('uses the frozen Kotlin constants', () => {
        expect(FRAC_HEAD).toBe(0.0625);
        expect(FRAC_FIRST_INDEX).toBe(0.5);
    });

    it('tail append from empty lands on FIRST_INDEX', () => {
        expect(fracAfter(null)).toEqual({ needsRebalance: false, value: FRAC_FIRST_INDEX });
    });

    it('prepend from empty lands on HEAD', () => {
        expect(fracBefore(null)).toEqual({ needsRebalance: false, value: FRAC_HEAD });
    });

    it('tail append steps by 1.0', () => {
        expect(fracAfter(5)).toEqual({ needsRebalance: false, value: 6 });
    });

    it('hits the underflow wall at denormal anchors', () => {
        // The minimum subnormal can no longer move by halving.
        expect(fracBefore(Number.MIN_VALUE).needsRebalance).toBe(true);
        // Well above the wall, halving always moves.
        expect(fracBefore(0.5).needsRebalance).toBe(false);
        expect(fracBefore(1e-200).needsRebalance).toBe(false);
    });
});

describe('jam-live-frac: midpoints', () => {
    it('midpoints between neighbours', () => {
        expect(fracBetween(1, 2)).toEqual({ needsRebalance: false, value: 1.5 });
        expect(fracBetween(0.5, 1.5)).toEqual({ needsRebalance: false, value: 1 });
    });

    it('flags the mantissa wall', () => {
        const tiny = 0.5;
        let mid = fracBetween(tiny, tiny + ulpOf(tiny) * 4);
        // Enough room for a few probes, then exhausted:
        let guard = 0;
        while (!mid.needsRebalance && guard < 10) {
            mid = fracBetween(mid.value, tiny + ulpOf(tiny) * 4);
            guard += 1;
        }
        expect(mid.needsRebalance).toBe(true);
    });

    it('handles inverted input (host should fix)', () => {
        expect(fracBetween(2, 1).needsRebalance).toBe(true);
    });

    it('one-sided calls delegate to append/prepend', () => {
        expect(fracBetween(null, 4)).toEqual(fracBefore(4));
        expect(fracBetween(4, null)).toEqual(fracAfter(4));
        expect(fracBetween(null, null)).toEqual(fracAfter(null));
    });
});

describe('jam-live-frac: collisions and the insert helper', () => {
    it('collides within a ULP', () => {
        const x = 1.5;
        expect(fracCollide(x, x + ulpOf(x))).toBe(true);
        expect(fracCollide(x, x)).toBe(true);
        expect(fracCollide(x, x + ulpOf(x) * 4)).toBe(false);
        expect(fracCollide(1, 2)).toBe(false);
    });

    it('clamps the insert position', () => {
        const fracs = [0.5, 1.5, 2.5];
        expect(fracIndexForInsertAt(-5, fracs).value).toBeCloseTo(0.25, 12);
        expect(fracIndexForInsertAt(99, fracs).value).toBeCloseTo(3.5, 12);
        expect(fracIndexForInsertAt(1, fracs).value).toBeCloseTo(1, 12);
    });
});
