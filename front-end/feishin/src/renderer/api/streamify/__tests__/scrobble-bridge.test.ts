/**
 * P3-B — the feishin scrobble bridge: Feishin's sparse playback-report
 * stream → ACTUAL PLAYED TIME → PlaySessionTracker threshold → tally +
 * live snapshot + outbox. Network-free (fake tally storage, injected
 * clock, adapter fakes).
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { LibrarySnapshot, SongRow } from '../library';
import type { TallyStorage } from '../playback/play-tally';
import type { ScrobbleSendAdapters } from '../playback/scrobble-bridge';

import { readTally } from '../playback/play-tally';
import { StreamifyScrobbleBridge } from '../playback/scrobble-bridge';

// -- fixtures ----------------------------------------------------------------

function memTally(): TallyStorage & { dump(): null | string } {
    let value: null | string = null;
    return { dump: () => value, read: () => value, write: (v: string) => void (value = v) };
}

function snapshotOf(songs: SongRow[]): LibrarySnapshot {
    const songById = new Map(songs.map((s) => [s.id, s]));
    return {
        albumById: new Map(),
        albums: [],
        artistById: new Map(),
        artists: [],
        likedIds: [],
        loadedAt: '2026-01-01T00:00:00.000Z',
        playEvents: [],
        playlistById: new Map(),
        playlists: [],
        sessionKey: 'test',
        songById,
        songs,
    };
}

function songRow(id: string, overrides: Partial<SongRow> = {}): SongRow {
    return {
        addedRank: 0,
        album: 'Album',
        artist: 'Artist',
        coverUrl: null,
        durationSec: 300,
        id,
        lastPlayedAt: null,
        liked: false,
        playCount: 0,
        streamUrl: 'vid_1',
        title: `Title ${id}`,
        ...overrides,
    };
}

const MINUTE = 60_000;

function makeBridge(overrides: {
    clock: () => number;
    resolveRung?: (videoId: string) => null | string;
    snapshot?: LibrarySnapshot;
    tally: TallyStorage;
}) {
    let snapshot = overrides.snapshot;
    const bridge = new StreamifyScrobbleBridge({
        clock: overrides.clock,
        getSnapshot: () => snapshot ?? null,
        outboxKey: `test-outbox-${Math.random().toString(36).slice(2)}`,
        resolveRung: overrides.resolveRung ?? (() => null),
        tallyStorage: overrides.tally,
    });
    return {
        bridge,
        setSnapshot(next: LibrarySnapshot) {
            snapshot = next;
        },
    };
}

function onlineAdapters(calls: { send: (payload: unknown) => void }): ScrobbleSendAdapters {
    return {
        canSend: () => true,
        send: async (event) => {
            calls.send(event);
            return true;
        },
    };
}

// ----------------------------------------------------------------------------

describe('StreamifyScrobbleBridge — actual played time from the report stream', () => {
    let now: number;
    let clock: () => number;
    let sendCalls: unknown[];
    let adapters: ScrobbleSendAdapters;

    beforeEach(() => {
        now = 1_000_000;
        clock = () => now;
        sendCalls = [];
        adapters = onlineAdapters({ send: (payload) => void sendCalls.push(payload) });
    });

    it('crosses the threshold from accumulated start/unpause positions and fires once', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        // Track is 5 min → threshold = 2.5 min.
        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 1 * MINUTE;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 1 * MINUTE, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 1 * MINUTE;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 2 * MINUTE, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        expect(sendCalls).toHaveLength(0); // not yet

        now += 30_000;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 2.5 * MINUTE, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        expect(sendCalls).toHaveLength(1);
        const fired = sendCalls[0] as { playedMs: number; thresholdMs: number; trackId: string };
        expect(fired.trackId).toBe('trk_1');
        expect(fired.thresholdMs).toBe(150_000);
        expect(fired.playedMs).toBe(150_000);

        // Once only — further accrual is dead.
        now += MINUTE;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 3.5 * MINUTE, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        expect(sendCalls).toHaveLength(1);
    });

    it('pauses do not accrue (wall-clock bound)', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 2 * MINUTE;
        bridge.handleReport(
            { event: 'pause', id: 'trk_1', position: 2 * MINUTE, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 30 * MINUTE; // paused for half an hour (accrues nothing)
        // A seek-while-paused (playhead moved back) then unpause: the
        // baseline re-seeds; the backwards jump accrues nothing.
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 30_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 20_000; // 20 real seconds of playing
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 50_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        // 2 min (played) + 20 s (played) = 140 s < the 150 s threshold: the
        // half-hour pause and the backwards seek never counted.
        expect(sendCalls).toHaveLength(0);
    });

    it('a skip never scrobbles: stop before the threshold abandons the session', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1'), songRow('trk_2')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 60_000;
        bridge.handleReport(
            { event: 'stop', id: 'trk_1', position: 60_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        // Next track starts — the abandoned session is gone.
        bridge.handleReport(
            { event: 'start', id: 'trk_2', position: 0, submission: false },
            factsOf(songs[1]!),
            adapters,
        );
        now += 3 * MINUTE;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_2', position: 3 * MINUTE, submission: false },
            factsOf(songs[1]!),
            adapters,
        );

        expect(sendCalls).toHaveLength(1);
        expect((sendCalls[0] as { trackId: string }).trackId).toBe('trk_2');
    });

    it('a forward seek accrues only the wall time it really took', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 5_000;
        // Position teleports from 0 to 4 minutes in 5 real seconds.
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 4 * MINUTE, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        expect(sendCalls).toHaveLength(0); // 5s < 2.5min threshold — the seek gap did not count
    });

    it('the submission event completes a short listen that Feishin counted but we had not', async () => {
        const tally = memTally();
        const songs = [songRow('trk_short', { durationSec: 60 })];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        // 60 s track → threshold = 30 s. Feishin's default is 75% = 45 s.
        bridge.handleReport(
            { event: 'start', id: 'trk_short', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 45_000;
        bridge.handleReport(
            { id: 'trk_short', position: 60_000, submission: true },
            factsOf(songs[0]!),
            adapters,
        );

        expect(sendCalls).toHaveLength(1);
        const fired = sendCalls[0] as { playedMs: number; trackId: string };
        expect(fired.trackId).toBe('trk_short');
        expect(fired.playedMs).toBe(45_000);
    });

    it('a repeat/restart submission re-arms and fires again (deliberate replay)', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 3 * MINUTE;
        bridge.handleReport(
            { id: 'trk_1', position: 5 * MINUTE, submission: true },
            factsOf(songs[0]!),
            adapters,
        );
        expect(sendCalls).toHaveLength(1);

        // Repeat: feishin re-arms its flag and re-submits the same track
        // after another real listen. (The wait also lets the first fire's
        // outbox drain finish before the second fire's drain races it —
        // drain-in-progress skips, the next trigger ships.)
        await vi.waitFor(() => expect(sendCalls).toHaveLength(1));
        now += MINUTE;
        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 3 * MINUTE;
        bridge.handleReport(
            { id: 'trk_1', position: 5 * MINUTE, submission: true },
            factsOf(songs[0]!),
            adapters,
        );

        await vi.waitFor(() => expect(sendCalls).toHaveLength(2));
    });

    it('fires attach the resolved rung for the track video', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1', { streamUrl: 'vid_xyz' })];
        const { bridge } = makeBridge({
            clock,
            resolveRung: (videoId) => (videoId === 'vid_xyz' ? 'web-pot-direct' : null),
            snapshot: snapshotOf(songs),
            tally,
        });

        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 3 * MINUTE;
        bridge.handleReport(
            { id: 'trk_1', position: 5 * MINUTE, submission: true },
            factsOf(songs[0]!),
            adapters,
        );

        expect((sendCalls[0] as { rung: null | string }).rung).toBe('web-pot-direct');
    });
});

describe('StreamifyScrobbleBridge — the fire path side effects', () => {
    let now: number;
    let clock: () => number;
    let sendCalls: unknown[];
    let adapters: ScrobbleSendAdapters;

    beforeEach(() => {
        now = 1_000_000;
        clock = () => now;
        sendCalls = [];
        adapters = onlineAdapters({ send: (payload) => void sendCalls.push(payload) });
    });

    it('increments the playTally counter strictly on the crossing (never on skips)', () => {
        const tally = memTally();
        const songs = [songRow('trk_1'), songRow('trk_2')];
        const snap = snapshotOf(songs);
        const { bridge } = makeBridge({ clock, snapshot: snap, tally });

        // A skipped track accrues real played time but never crosses.
        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 60_000;
        bridge.handleReport(
            { event: 'stop', id: 'trk_1', position: 60_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        expect(readTally(tally).tracks).toBe(0);
        expect(snap.songById.get('trk_1')!.playCount).toBe(0);

        // A counted listen crosses.
        bridge.handleReport(
            { event: 'start', id: 'trk_2', position: 0, submission: false },
            factsOf(songs[1]!),
            adapters,
        );
        now += 3 * MINUTE;
        bridge.handleReport(
            { id: 'trk_2', position: 5 * MINUTE, submission: true },
            factsOf(songs[1]!),
            adapters,
        );

        const after = readTally(tally);
        expect(after.tracks).toBe(1);
        expect(after.topTracks['trk_2']!.count).toBe(1);
        expect(after.seconds).toBe(180);
        expect(snap.songById.get('trk_2')!.playCount).toBe(1);
        expect(snap.songById.get('trk_2')!.lastPlayedAt).not.toBeNull();
    });

    it('bumps playCount on the live snapshot only for KNOWN songs (unknown ids are a no-op)', () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const snap = snapshotOf(songs);
        const { bridge } = makeBridge({ clock, snapshot: snap, tally });

        // A scrobble for an id the snapshot lacks (stale queue row): the
        // tally still counts, the snapshot does not crash.
        bridge.handleReport(
            { event: 'start', id: 'trk_ghost', position: 0, submission: false },
            { album: '', artist: '', durationMs: 300_000, title: '', videoId: null },
            adapters,
        );
        now += 3 * MINUTE;
        bridge.handleReport(
            { id: 'trk_ghost', position: 5 * MINUTE, submission: true },
            { album: '', artist: '', durationMs: 300_000, title: '', videoId: null },
            adapters,
        );

        expect(readTally(tally).tracks).toBe(1);
        expect(sendCalls).toHaveLength(1);
    });

    it('offline fires queue in the outbox and drain later sends them', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1'), songRow('trk_2')];
        const snap = snapshotOf(songs);
        const { bridge } = makeBridge({ clock, snapshot: snap, tally });

        const offline: ScrobbleSendAdapters = {
            canSend: () => false,
            send: async () => {
                throw new Error('offline');
            },
        };
        for (const song of songs) {
            bridge.handleReport(
                { event: 'start', id: song.id, position: 0, submission: false },
                factsOf(song),
                offline,
            );
            now += 3 * MINUTE;
            bridge.handleReport(
                { id: song.id, position: 5 * MINUTE, submission: true },
                factsOf(song),
                offline,
            );
        }

        expect(sendCalls).toHaveLength(0);
        expect(bridge.pendingScrobbles()).toHaveLength(2);

        // Connectivity returns: the next report binds the online adapters
        // and the pending outbox drains.
        now += MINUTE;
        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        await vi.waitFor(() => expect(sendCalls.length).toBe(2));
    });

    it('sessionForQueue carries the in-flight session for the current song only', () => {
        const tally = memTally();
        const songs = [songRow('trk_1'), songRow('trk_2')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.handleReport(
            { event: 'start', id: 'trk_1', position: 0, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 60_000;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 60_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        const session = bridge.sessionForQueue('trk_1');
        expect(session).toMatchObject({ playedMs: 60_000, scrobbled: false, trackId: 'trk_1' });
        expect(bridge.sessionForQueue('trk_2')).toBeNull();
    });

    it('continueSession() restores an in-flight session across a queue restore', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.continueSession(
            { playedMs: 100_000, scrobbled: false, startedAt: 999_000, trackId: 'trk_1' },
            100_000,
        );

        // Post-restore samples continue the accrual toward 150_000.
        now += 25_000;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 125_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );
        now += 25_000;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 150_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        expect(sendCalls).toHaveLength(1);
        expect((sendCalls[0] as { playedMs: number }).playedMs).toBe(150_000);
    });

    it('continueSession() with a fired session never re-fires on plain progress', async () => {
        const tally = memTally();
        const songs = [songRow('trk_1')];
        const { bridge } = makeBridge({ clock, snapshot: snapshotOf(songs), tally });

        bridge.continueSession(
            { playedMs: 240_000, scrobbled: true, startedAt: 999_000, trackId: 'trk_1' },
            240_000,
        );
        now += MINUTE;
        bridge.handleReport(
            { event: 'unpause', id: 'trk_1', position: 300_000, submission: false },
            factsOf(songs[0]!),
            adapters,
        );

        expect(sendCalls).toHaveLength(0); // fired sessions never re-fire
    });
});

function factsOf(row: SongRow) {
    return {
        album: row.album ?? '',
        artist: row.artist,
        durationMs: Math.round((row.durationSec ?? 0) * 1000),
        title: row.title,
        videoId: row.streamUrl,
    };
}
