/**
 * jam-live-protocol tests (P4B) - room codes, invite links, the drift
 * corrector, and the untrusted-wire message validators.
 */

import { describe, expect, it } from 'vitest';

import {
    buildJamInviteLink,
    computeDriftCorrection,
    decodeJamControlMessage,
    decodeJamMetaMessage,
    decodeJamSyncMessage,
    estimateHostPositionMs,
    generateJamRoomCode,
    inviteRoomCode,
    JAM_CODE_ALPHABET,
    JAM_CODE_LENGTH,
    JAM_DRIFT_HARD_MS,
    type JamSnapshotMessage,
    type JamSyncMessage,
    type JamTrackPayload,
    normalizeJamRoomCode,
} from '../jam-live/jam-live-protocol';

const syncMessage = (overrides: Partial<JamSyncMessage> = {}): JamSyncMessage => ({
    emittedAt: 10_000,
    hostEpoch: 1,
    hostUserId: 'host-1',
    kind: 'tick',
    positionMs: 30_000,
    track: null,
    trackAddOpId: null,
    v: 1,
    ...overrides,
});

describe('jam-live-protocol: room codes', () => {
    it('mints 6-char codes from the unambiguous alphabet', () => {
        const code = generateJamRoomCode();
        expect(code).toHaveLength(JAM_CODE_LENGTH);
        for (const ch of code) {
            expect(JAM_CODE_ALPHABET).toContain(ch);
        }
        expect(code).toBe(code.toUpperCase());
        expect(code).not.toMatch(/[ILO01]/);
    });

    it('is deterministic with a pinned rng stream', () => {
        const lcg = (seed: number) => () => {
            seed = (seed * 16807) % 2147483647;
            return seed / 2147483647;
        };
        expect(generateJamRoomCode(lcg(42))).toBe(generateJamRoomCode(lcg(42)));
        expect(generateJamRoomCode(lcg(42))).not.toBe(generateJamRoomCode(lcg(43)));
    });

    it('normalizes user input (case, whitespace) and rejects garbage', () => {
        expect(normalizeJamRoomCode('  strm9x ')).toBe('STRM9X');
        expect(normalizeJamRoomCode('STRM9')).toBeNull();
        expect(normalizeJamRoomCode('STRM9XX')).toBeNull();
        expect(normalizeJamRoomCode('STRM9I')).toBeNull(); // ambiguous glyph
        expect(normalizeJamRoomCode('STRM90')).toBeNull(); // ambiguous glyph
        expect(normalizeJamRoomCode('')).toBeNull();
    });

    it('extracts codes from invite links and bare input', () => {
        expect(inviteRoomCode('https://app.example.com/#/?jam=strm9x')).toBe('STRM9X');
        expect(inviteRoomCode('/?foo=1&jam=STRM9X')).toBe('STRM9X');
        expect(inviteRoomCode('strm9x')).toBe('STRM9X');
        expect(inviteRoomCode('https://app.example.com/?jam=bad')).toBeNull();
    });

    it('builds invite links with the canonical code', () => {
        expect(buildJamInviteLink('https://app.example.com/', 'strm9x')).toBe(
            'https://app.example.com/?jam=STRM9X',
        );
        expect(buildJamInviteLink('https://app.example.com/?x=1', 'STRM9X')).toBe(
            'https://app.example.com/?x=1&jam=STRM9X',
        );
        expect(buildJamInviteLink('https://app.example.com/', 'nope!')).toBe(
            'https://app.example.com/',
        );
    });
});

describe('jam-live-protocol: drift math', () => {
    it('advances the host position with elapsed time while playing', () => {
        const msg = syncMessage({ emittedAt: 10_000, kind: 'tick', positionMs: 30_000 });
        expect(estimateHostPositionMs(msg, 10_500)).toBe(30_500);
        expect(estimateHostPositionMs(msg, 9_000)).toBe(30_000); // clock skew clamped
    });

    it('freezes the position for pause/end', () => {
        expect(estimateHostPositionMs(syncMessage({ kind: 'pause' }), 60_000)).toBe(30_000);
        expect(estimateHostPositionMs(syncMessage({ kind: 'end' }), 60_000)).toBe(30_000);
    });

    it('corrects hard drift immediately', () => {
        expect(computeDriftCorrection(30_000, 30_000 + JAM_DRIFT_HARD_MS, 0)).toEqual({
            action: 'seek',
            seekToMs: 32_000,
        });
        expect(computeDriftCorrection(30_000, 30_000 + JAM_DRIFT_HARD_MS - 1, 0).action).toBe(
            'none',
        );
    });

    it('corrects soft drift only after consecutive offences', () => {
        const host = 30_000 + 1_000; // inside the soft window
        expect(computeDriftCorrection(30_000, host, 0).action).toBe('none');
        expect(computeDriftCorrection(30_000, host, 1).action).toBe('none');
        expect(computeDriftCorrection(30_000, host, 2)).toEqual({
            action: 'seek',
            seekToMs: host,
        });
        // tiny drift never corrects
        expect(computeDriftCorrection(30_000, 30_100, 99).action).toBe('none');
    });
});

describe('jam-live-protocol: wire validators', () => {
    it('decodes valid sync messages and rejects malformed ones', () => {
        const msg = decodeJamSyncMessage(syncMessage({ kind: 'track', track: fakeTrack() }));
        expect(msg?.kind).toBe('track');
        expect(msg?.track?.id).toBe('t1');
        expect(msg?.hostOnlySkipSeek).toBe(false);

        expect(decodeJamSyncMessage(null)).toBeNull();
        expect(decodeJamSyncMessage({ kind: 'tick', v: 2 })).toBeNull();
        expect(decodeJamSyncMessage({ kind: 'bogus', v: 1 })).toBeNull();
        expect(decodeJamSyncMessage({ ...syncMessage(), positionMs: -1 })).toBeNull();
        expect(decodeJamSyncMessage({ ...syncMessage(), emittedAt: 'x' })).toBeNull();
        expect(decodeJamSyncMessage({ ...syncMessage(), kind: 'track', track: null })).toBeNull();
    });

    it('decodes meta messages', () => {
        const meta = decodeJamMetaMessage({ cadId: '123', track: fakeTrack(), v: 1 });
        expect(meta?.cadId).toBe('123');
        expect(decodeJamMetaMessage({ cadId: '', track: fakeTrack(), v: 1 })).toBeNull();
        expect(decodeJamMetaMessage({ cadId: '1', track: { id: 4 }, v: 1 })).toBeNull();
    });

    it('decodes control messages (request + snapshot)', () => {
        const request = decodeJamControlMessage({
            kind: 'request',
            positionMs: 5,
            request: 'seek',
            v: 1,
        });
        expect(request).toEqual({ kind: 'request', positionMs: 5, request: 'seek', v: 1 });
        expect(
            decodeJamControlMessage({ kind: 'request', positionMs: 5, request: 'nope', v: 1 }),
        ).toBeNull();

        const snapshot = decodeJamControlMessage({
            hostOnlySkipSeek: true,
            kind: 'snapshot',
            ordered: [
                { addOpId: '1', cadId: '10', frac: 0.5, track: fakeTrack() },
                { addOpId: '2', cadId: '20', frac: 1.5, track: null },
                { addOpId: 'x', cadId: '30' }, // filtered out
            ],
            playback: {
                currentAddOpId: '1',
                currentTrack: fakeTrack(),
                isPlaying: true,
                positionMs: 1_000,
            },
            v: 1,
        });
        expect(snapshot?.kind).toBe('snapshot');
        const decoded: JamSnapshotMessage | null = snapshot as JamSnapshotMessage | null;
        if (decoded?.kind === 'snapshot') {
            expect(decoded.ordered).toHaveLength(2);
            expect(decoded.playback.isPlaying).toBe(true);
            expect(decoded.hostOnlySkipSeek).toBe(true);
        }
        expect(
            decodeJamControlMessage({ kind: 'snapshot', ordered: [], playback: null, v: 1 }),
        ).toBeNull();
        expect(decodeJamControlMessage({ v: 1 })).toBeNull();
    });
});

function fakeTrack(): JamTrackPayload {
    return {
        _itemType: 'song',
        _serverId: 'streamify',
        _serverType: 'streamify',
        album: null,
        albumArtistName: 'Artist',
        albumArtists: [],
        albumId: '',
        artistName: 'Artist',
        artists: [],
        bitDepth: null,
        bitRate: 128,
        blurHash: null,
        bpm: null,
        channels: null,
        codec: null,
        comment: null,
        compilation: null,
        container: null,
        createdAt: '',
        date: null,
        discNumber: 0,
        discSubtitle: null,
        duration: 200,
        explicitStatus: null,
        folderId: null,
        gain: null,
        genres: [],
        id: 't1',
        imageId: null,
        imageUrl: null,
        lastPlayedAt: null,
        libraryId: null,
        libraryName: null,
        lyrics: null,
        mbzAlbumId: null,
        mbzAlbumType: null,
        mbzRecordingId: null,
        mbzReleaseGroupId: null,
        mbzTrackId: null,
        missing: null,
        name: 'Track One',
        originalDate: null,
        originalYear: null,
        participants: null,
        path: null,
        peak: null,
        playCount: 0,
        releaseDate: null,
        size: null,
        updatedAt: '',
        year: null,
    } as unknown as JamTrackPayload;
}
