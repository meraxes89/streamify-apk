/**
 * element-id.test.ts - the CRDT <-> playlist_tracks row-id mapping lock
 * (P3A): packing round-trips, block ranges, bytewise-uuid ordering, and
 * the legacy v4 separation.
 */

import { describe, expect, it } from 'vitest';

import { fracBitsOf } from '../jam/jam-op';
import {
    elementBlockRange,
    isLegacyV4RowId,
    packElementUuid,
    unpackElementUuid,
} from '../playlist-sync/element-id';

describe('element-id — the durable row mapping', () => {
    it('packs (add_op_id, frac_bits) big-endian into the 8-4-4-4-12 layout', () => {
        const addOpId = 0x0019cd123456789an;
        const fracBits = 0x3fe0000000000000n; // 0.5
        const id = packElementUuid(addOpId, fracBits);
        expect(id).toBe('0019cd12-3456-789a-3fe0-000000000000');
        const back = unpackElementUuid(id);
        expect(back).toEqual({ addOpId, fracBits });
    });

    it('round-trips arbitrary 64-bit halves exactly (u64 precision)', () => {
        const cases: Array<[bigint, bigint]> = [
            [1n, fracBitsOf(0.5)],
            [1770000000000000123n, fracBitsOf(0.0625)],
            [0xfffffffffffffffen, 0x412e848000000000n],
            [0n, 0n],
        ];
        for (const [addOpId, fracBits] of cases) {
            const id = packElementUuid(addOpId, fracBits);
            expect(unpackElementUuid(id)).toEqual({ addOpId, fracBits });
        }
    });

    it('block ranges cover every frac of one element and nothing else', () => {
        const addOpId = 0x0019cd123456789an;
        const range = elementBlockRange(addOpId);
        expect(range.gte).toBe(packElementUuid(addOpId, 0n));
        expect(range.lt).toBe(packElementUuid(addOpId + 1n, 0n));
        // Every frac of the element falls inside the half-open range.
        for (const frac of [0.0625, 0.5, 1.5, 2.25, 1048576.0]) {
            const id = packElementUuid(addOpId, fracBitsOf(frac));
            expect(id >= range.gte).toBe(true);
            expect(id < range.lt).toBe(true);
        }
        // The next element's rows start exactly at the exclusive bound.
        expect(packElementUuid(addOpId + 1n, 0n) >= range.lt).toBe(true);
        expect(packElementUuid(addOpId - 1n, fracBitsOf(1e9)) < range.gte).toBe(true);
    });

    it('string order == u64 numeric order (the Postgres bytewise guarantee)', () => {
        // Postgres compares uuid values bytewise; big-endian hex packing
        // means plain string comparison of the undashed hex equals the
        // (addOpId, fracBits) numeric pair order - which is what makes the
        // gte/lt range filters exact.
        const a = packElementUuid(100n, fracBitsOf(1.0));
        const b = packElementUuid(100n, fracBitsOf(2.0));
        const c = packElementUuid(101n, fracBitsOf(0.5));
        expect(a < b).toBe(true);
        expect(b < c).toBe(true);
        const undash = (id: string) => id.replace(/-/g, '');
        expect(undash(a) < undash(b)).toBe(true);
        expect(undash(b) < undash(c)).toBe(true);
    });

    it('separates legacy v4 rows from packed rows with zero false positives', () => {
        // v4: version nibble '4' at hex[12], variant 10xx at hex[16].
        expect(isLegacyV4RowId('a0eec1a3-9c9c-4d7b-93f2-1a2b3c4d5e6f')).toBe(true);
        expect(isLegacyV4RowId('00000000-0000-4000-8000-000000000000')).toBe(true);
        // Packed rows: the frac half is a positive finite f64's bits - its
        // top nibble is the sign+exponent 0x3/0x4, never the 10xx variant.
        for (const frac of [0.0625, 0.5, 1.5, 2.0, 32768.0, 1048576.0]) {
            const id = packElementUuid(0x0019cd1234564aaan, fracBitsOf(frac));
            expect(isLegacyV4RowId(id)).toBe(false);
        }
        // Even a packed id whose add-op half carries '4' nibbles everywhere.
        const hostile = packElementUuid(0x4444444444444444n, fracBitsOf(1.5));
        expect(isLegacyV4RowId(hostile)).toBe(false);
        // Malformed ids are not legacy.
        expect(isLegacyV4RowId('not-a-uuid')).toBe(false);
    });
});
