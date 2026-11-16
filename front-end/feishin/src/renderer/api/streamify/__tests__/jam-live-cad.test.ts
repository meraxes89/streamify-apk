/**
 * jam-live-cad tests (P4B) - the content-hash track identity: pinned
 * vectors (independently minted), the normalization contract (case, parens,
 * non-ASCII, punctuation), the 3-second duration bucket, and the stable
 * device nonce.
 */

import { describe, expect, it } from 'vitest';

import {
    deviceNonceHex,
    deviceNonceOf,
    fnv1a32Of,
    generateCadIdU64,
    normalizeCadField,
} from '../jam-live/jam-live-cad';

describe('jam-live-cad: pinned vectors', () => {
    it('mints stable cad ids (frozen: any drift breaks cross-client identity)', () => {
        expect(generateCadIdU64('Never Gonna Give You Up', 'Rick Astley', 213).toString(10)).toBe(
            '7653545310833162045',
        );
        expect(generateCadIdU64('Blinding Lights', 'The Weeknd', 200).toString(10)).toBe(
            '9883876317575249700',
        );
        expect(generateCadIdU64('same title', 'Artist A', 9).toString(10)).toBe(
            '7708864013923660364',
        );
    });

    it('buckets duration in 3-second steps', () => {
        // 9s and 11s share the bucket 3; 12s crosses into bucket 4.
        expect(generateCadIdU64('same title', 'Artist A', 11)).toBe(
            generateCadIdU64('same title', 'Artist A', 9),
        );
        expect(generateCadIdU64('same title', 'Artist A', 12)).not.toBe(
            generateCadIdU64('same title', 'Artist A', 11),
        );
    });

    it('normalizes case, punctuation and non-ASCII away', () => {
        expect(generateCadIdU64('SAME TITLE!', 'artist a', 9)).toBe(
            generateCadIdU64('same title', 'Artist A', 9),
        );
        // Parentheses survive in titles only.
        const withParens = normalizeCadField('Remix (Live)', true);
        const withoutParens = normalizeCadField('Remix (Live)', false);
        expect(Array.from(withParens)).toEqual(Array.from(new TextEncoder().encode('remix(live')));
        expect(Array.from(withoutParens)).toEqual(
            Array.from(new TextEncoder().encode('remixlive')),
        );
        // Non-ASCII drops entirely (per-BYTE mirror of the C++ semantics).
        expect(Array.from(normalizeCadField('café', true))).toEqual(
            Array.from(new TextEncoder().encode('caf')),
        );
    });
});

describe('jam-live-cad: device nonce', () => {
    it('is a stable 4-byte FNV of the device id', () => {
        expect(deviceNonceHex('dev-test-1')).toBe('612661ed');
        expect(deviceNonceOf('dev-test-1')).toHaveLength(4);
        expect(deviceNonceHex('dev-test-1')).toBe(deviceNonceHex('dev-test-1'));
        expect(deviceNonceHex('dev-test-2')).not.toBe(deviceNonceHex('dev-test-1'));
    });

    it('fnv1a32 pins its offset basis', () => {
        expect(fnv1a32Of('hello')).toBe(1335831723);
    });
});
