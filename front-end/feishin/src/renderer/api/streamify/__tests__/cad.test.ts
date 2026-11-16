/**
 * cad.test.ts - the CAD-ID hasher lock (P3A).
 *
 * The relational expectations are the Rust suite's (cad.rs): determinism,
 * the 3-second duration bucket edges, the paren rules, non-ASCII dropping.
 * The absolute constants were minted by an INDEPENDENT transcription of
 * the Rust algorithm (not this module) - they pin the exact bytes.
 */

import { describe, expect, it } from 'vitest';

import {
    deviceNonceHex,
    deviceNonceOf,
    fnv1a32Of,
    generateCadIdU64,
    normalizeCadField,
    playlistTrackCadId,
} from '../jam/cad';

describe('cad — the canonical content hasher', () => {
    it('matches the independently minted golden vectors', () => {
        // Minted from a hand transcription of cad.rs (see PR description):
        // FNV-1a-64 over normalized (title[parens], artist) + bucket LE bytes.
        expect(generateCadIdU64('Shape of You', 'Ed Sheeran', 232)).toBe(16632706428785836730n);
        expect(generateCadIdU64('Song (Remix)', 'A', 100)).toBe(3800865396235254017n);
        expect(generateCadIdU64('A', 'Song (Remix)', 100)).toBe(15188317382850285487n);
        expect(generateCadIdU64('Béla', 'Lugosi', 10)).toBe(738399954313172036n);
    });

    it('deterministic, and the 3-second bucket edges behave', () => {
        const a = generateCadIdU64('Shape of You', 'Ed Sheeran', 232);
        expect(generateCadIdU64('Shape of You', 'Ed Sheeran', 232)).toBe(a);
        // 232 and 233 share the 77 bucket: identical id.
        expect(generateCadIdU64('Shape of You', 'Ed Sheeran', 233)).toBe(a);
        // 234 crosses into the 78 bucket: different id.
        expect(generateCadIdU64('Shape of You', 'Ed Sheeran', 234)).not.toBe(a);
    });

    it('title keeps parens, artist drops them, non-ASCII drops entirely', () => {
        expect(generateCadIdU64('Song (Remix)', 'A', 100)).not.toBe(
            generateCadIdU64('Song Remix', 'A', 100),
        );
        expect(generateCadIdU64('A', 'Song (Remix)', 100)).toBe(
            generateCadIdU64('A', 'Song Remix', 100),
        );
        expect(generateCadIdU64('Béla', 'Lugosi', 10)).toBe(generateCadIdU64('bla', 'Lugosi', 10));
        expect(Array.from(normalizeCadField('A b-C_9 (x)', true))).toEqual([
            'a'.charCodeAt(0),
            'b'.charCodeAt(0),
            'c'.charCodeAt(0),
            '9'.charCodeAt(0),
            '('.charCodeAt(0),
            'x'.charCodeAt(0),
        ]);
    });

    it('the playlist-element cad is FNV-1a-64 over the track id bytes', () => {
        expect(playlistTrackCadId('dQw4w9WgXcQ')).toBe(11573948961743118813n);
        expect(playlistTrackCadId('trk_-1234567890')).toBe(9678215896440823169n);
        // Deterministic + distinct per id.
        expect(playlistTrackCadId('a')).not.toBe(playlistTrackCadId('b'));
    });

    it('the 4-byte device nonce derives stably from the device id', () => {
        expect(deviceNonceHex('device-a')).toBe(deviceNonceHex('device-a'));
        expect(deviceNonceHex('device-a')).not.toBe(deviceNonceHex('device-b'));
        expect(deviceNonceOf('device-a').length).toBe(4);
        expect(typeof fnv1a32Of('device-a')).toBe('number');
    });
});
