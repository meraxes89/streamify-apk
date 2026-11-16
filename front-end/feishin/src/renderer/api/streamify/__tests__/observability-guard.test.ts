/**
 * P5-B — the observability guard: the PRODUCTION diagnostic hooks (scrobble
 * outbox depth + flush timestamps, resolver rung telemetry, and the /debug
 * health read model) serialize cleanly and never leak tokens or signed
 * stream URLs. Network-free: the resolver pipeline is vi.mock'ed one level
 * under get-stream-url (the offline-bypass pattern), the range probe is
 * injected, the bridge runs on a fake clock, and the ledger + playlist
 * persistence ride memory storage.
 *
 * What is pinned here:
 *   - pendingScrobbles() / the persisted outbox row / sessionForQueue()
 *     round-trip JSON with a PINNED field set — no field can ever smuggle
 *     a resolved URL or a session token onto a diagnostic surface;
 *   - recentResolveLog() records stay URL-free even though the resolution
 *     they describe carries a signed googlevideo URL (proven by fixture);
 *   - the /debug route's assembled health model (scrobble + resolver +
 *     ledger + CRDT panels) round-trips as one JSON blob, leak-free.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => {
    return {
        resolveCalls: [] as string[],
        /** The signed URL the mocked resolver "resolved" — realistic poison. */
        signedUrl:
            'https://rr3---sn-x.googlevideo.com/videoplayback?id=abc&access_token=SECRET_TOKEN_1&pot=SECRET_POT&signatureCipher=%3D',
    };
});

vi.mock('../playback/resolver/audio/resolve', () => ({
    resolveAudioStream: vi.fn(async (videoId: string) => {
        h.resolveCalls.push(videoId);
        return {
            artist: 'Fixture Artist',
            bitrate: 128_000,
            codec: 'aac',
            contentLength: 3_456_789,
            durationSec: 213,
            isHls: false,
            isSabr: false,
            itag: 140,
            mimeType: 'audio/mp4',
            potKind: 'none',
            rung: 'visionos-direct',
            title: 'Fixture Title',
            url: h.signedUrl,
            videoId,
        };
    }),
}));

vi.mock('../playback/resolver/audio/botguard/potSession', () => ({
    gvsPoToken: vi.fn(async () => null),
}));

vi.mock('../playlist-sync/realtime', () => ({
    playlistRealtimeStatus: () => ({ bound: true, channelLive: true, userId: 'user_1' }),
}));

import type { SongRow } from '../library';
import type { TallyStorage } from '../playback/play-tally';
import type { ScrobbleSendAdapters } from '../playback/scrobble-bridge';
import type { ScrobbleEvent } from '../playback/scrobbler';

import {
    clearStreamUrlCache,
    getStreamUrl,
    recentResolveLog,
} from '../playback/resolver/audio/get-stream-url';
import { StreamifyScrobbleBridge } from '../playback/scrobble-bridge';
import { playlistPersistTimestamp } from '../playlist-sync/persistence';
import { playlistRealtimeStatus } from '../playlist-sync/realtime';
import { ledgerDebugInfo } from '../wrapped/aggregates';
import {
    recordCountedPlay,
    resetLedgerForTests,
    setLedgerStorageForTests,
} from '../wrapped/listening-ledger';

import {
    getStreamifyPlaylistsState,
    setStreamifyPlaylistsState,
} from '/@/renderer/store/streamify-playlists.store';

// -- the leak scanner (the guard the guard tests) ----------------------------

const LEAK_PATTERNS: [name: string, re: RegExp][] = [
    ['an absolute URL', /https?:\/\//i],
    ['an auth token', /access_token|refresh_token|bearer\s|authorization/i],
    ['signed-stream material', /pot=|signaturecipher|ustreamerconfig/i],
];

function expectNoLeaks(blob: string, surface: string): void {
    for (const [name, re] of LEAK_PATTERNS) {
        expect(re.test(blob), `${surface} must not leak ${name}`).toBe(false);
    }
}

// The pinned diagnostic field sets — a new field here is a deliberate act.
const OUTBOX_ENTRY_FIELDS = new Set([
    'album',
    'artist',
    'attempts',
    'durationMs',
    'firedAt',
    'playedMs',
    'rung',
    'thresholdMs',
    'title',
    'trackId',
]);
const SESSION_PROJECTION_FIELDS = new Set(['playedMs', 'scrobbled', 'startedAt', 'trackId']);
const RESOLVE_LOG_FIELDS = new Set([
    'at',
    'audioOnly',
    'codec',
    'durationMs',
    'expiresInSeconds',
    'expirySource',
    'itag',
    'ladderDepth',
    'protocol',
    'rangeSupport',
    'rung',
    'sabr',
    'title',
    'trackId',
]);

function expectFieldsWithin(value: object, allowed: Set<string>, surface: string): void {
    for (const key of Object.keys(value)) {
        expect(allowed.has(key), `${surface} gained unexpected field '${key}'`).toBe(true);
    }
}

// -- fixtures ----------------------------------------------------------------

function memTally(): TallyStorage {
    let value: null | string = null;
    return { read: () => value, write: (v: string) => void (value = v) };
}

function songRow(id: string): SongRow {
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
    };
}

const MINUTE = 60_000;

/** Offline adapters: entries queue and SURVIVE (the depth surface). */
const offlineAdapters: ScrobbleSendAdapters = {
    canSend: () => false,
    send: async () => false,
};

/** Drive a bridge across the 2.5-min threshold of a 5-min track, offline. */
async function fireOfflineListen(
    bridge: StreamifyScrobbleBridge,
    song: SongRow,
    advance: (ms: number) => void,
): Promise<void> {
    const facts = {
        album: song.album ?? '',
        artist: song.artist,
        durationMs: Math.round((song.durationSec ?? 0) * 1000),
        title: song.title,
        videoId: song.streamUrl,
    };
    bridge.handleReport(
        { event: 'start', id: song.id, position: 0, submission: false },
        facts,
        offlineAdapters,
    );
    advance(MINUTE);
    bridge.handleReport(
        { event: 'unpause', id: song.id, position: 1 * MINUTE, submission: false },
        facts,
        offlineAdapters,
    );
    advance(MINUTE);
    bridge.handleReport(
        { event: 'unpause', id: song.id, position: 2 * MINUTE, submission: false },
        facts,
        offlineAdapters,
    );
    advance(30_000);
    bridge.handleReport(
        { event: 'unpause', id: song.id, position: 2.5 * MINUTE, submission: false },
        facts,
        offlineAdapters,
    );
    // Let the fire path's opportunistic drain microtasks settle.
    await new Promise((resolve) => {
        setTimeout(resolve, 0);
    });
}

// ----------------------------------------------------------------------------

describe('P5-B observability guard — diagnostics serialize clean, leak nothing', () => {
    let now: number;
    let clock: () => number;
    let backing: Map<string, string>;

    beforeEach(() => {
        now = 1_700_000_000_000;
        clock = () => now;
        backing = new Map();
        vi.stubGlobal('localStorage', {
            getItem: (k: string) => backing.get(k) ?? null,
            removeItem: (k: string) => void backing.delete(k),
            setItem: (k: string, v: string) => void backing.set(k, v),
        });
        setLedgerStorageForTests({
            read: () => backing.get('ledger') ?? null,
            write: (v: string) => void backing.set('ledger', v),
        });
        resetLedgerForTests();
        clearStreamUrlCache();
        h.resolveCalls.length = 0;
    });

    afterEach(() => {
        vi.unstubAllGlobals();
        resetLedgerForTests();
        clearStreamUrlCache();
    });

    it('the leak scanner itself catches smuggled material (guard self-test)', () => {
        expect(LEAK_PATTERNS.some(([, re]) => re.test(h.signedUrl))).toBe(true);
        expect(LEAK_PATTERNS.some(([, re]) => re.test('{"authorization":"Bearer x"}'))).toBe(true);
        expect(LEAK_PATTERNS.some(([, re]) => re.test('{"rung":"visionos-direct"}'))).toBe(false);
    });

    describe('scrobble outbox (depth + flush timestamps)', () => {
        it('pending entries round-trip JSON with the pinned field set', async () => {
            const bridge = new StreamifyScrobbleBridge({ clock, tallyStorage: memTally() });
            await fireOfflineListen(bridge, songRow('trk_1'), (ms) => {
                now += ms;
            });

            const pending = bridge.pendingScrobbles();
            expect(pending).toHaveLength(1); // the depth surface
            expectFieldsWithin(pending[0] as object, OUTBOX_ENTRY_FIELDS, 'outbox entry');

            const blob = JSON.stringify(pending);
            const back = JSON.parse(blob) as ScrobbleEvent[];
            expect(back).toHaveLength(1);
            expect(back[0]?.trackId).toBe('trk_1');
            expect(back[0]?.playedMs).toBe(150_000);
            expect(back[0]?.rung).toBe(null); // no resolveRung wired — honest null
            expectNoLeaks(blob, 'pendingScrobbles()');
        });

        it('the persisted localStorage row is the same clean payload', async () => {
            const bridge = new StreamifyScrobbleBridge({
                clock,
                outboxKey: 'streamify.feishin.scrobble.v1',
                tallyStorage: memTally(),
            });
            await fireOfflineListen(bridge, songRow('trk_1'), (ms) => {
                now += ms;
            });

            const raw = backing.get('streamify.feishin.scrobble.v1');
            expect(raw).toBeDefined();
            expectNoLeaks(raw as string, 'the persisted outbox row');
            expect(JSON.parse(raw as string)).toHaveLength(1);
        });

        it('lastFlushAt: null before the first report, finite epoch ms after', async () => {
            const bridge = new StreamifyScrobbleBridge({ clock, tallyStorage: memTally() });
            expect(bridge.lastFlushAt).toBe(null);

            await fireOfflineListen(bridge, songRow('trk_1'), (ms) => {
                now += ms;
            });
            const at = bridge.lastFlushAt;
            expect(typeof at).toBe('number');
            expect(Number.isFinite(at)).toBe(true);
            expect(at).toBeGreaterThanOrEqual(1_700_000_000_000);
        });

        it('the rung attaches at fire time but never carries the URL', async () => {
            const bridge = new StreamifyScrobbleBridge({
                clock,
                resolveRung: (videoId) => (videoId === 'vid_1' ? 'visionos-direct' : null),
                tallyStorage: memTally(),
            });
            await fireOfflineListen(bridge, songRow('trk_1'), (ms) => {
                now += ms;
            });

            const blob = JSON.stringify(bridge.pendingScrobbles());
            expect(blob).toContain('"rung":"visionos-direct"');
            expectNoLeaks(blob, 'the rung-carrying outbox entry');
        });

        it('sessionForQueue() (the queue-persistence projection) round-trips', () => {
            const bridge = new StreamifyScrobbleBridge({ clock, tallyStorage: memTally() });
            bridge.handleReport(
                { event: 'start', id: 'trk_1', position: 0, submission: false },
                { album: 'A', artist: 'B', durationMs: 300_000, title: 'T', videoId: 'vid_1' },
                offlineAdapters,
            );
            const projection = bridge.sessionForQueue('trk_1');
            expect(projection).not.toBeNull();
            expectFieldsWithin(
                projection as object,
                SESSION_PROJECTION_FIELDS,
                'session projection',
            );
            const back = JSON.parse(JSON.stringify(projection)) as typeof projection;
            expect(back?.trackId).toBe('trk_1');
            expect(back?.scrobbled).toBe(false);
        });
    });

    describe('resolver rung telemetry (recentResolveLog)', () => {
        it('records stay URL-free while the resolution itself carries the signed URL', async () => {
            const resolution = await getStreamUrl('trk_1', {
                probeImpl: (async () =>
                    new Response(null, {
                        headers: { 'content-range': 'bytes 0-0/3456789' },
                        status: 206,
                    })) as unknown as typeof fetch,
                relayBase: 'https://relay.test',
            });

            // The fixture is realistic: the RESOLUTION carries the poison.
            expect(resolution.url).toBe(h.signedUrl);
            expect(resolution.url).toContain('access_token');

            // ...and the telemetry record never sees it.
            const log = recentResolveLog();
            expect(log).toHaveLength(1);
            expect(h.resolveCalls).toEqual(['trk_1']); // the pipeline really ran

            const record = log[0] as unknown as Record<string, unknown>;
            expectFieldsWithin(record, RESOLVE_LOG_FIELDS, 'resolve log record');
            expect(record.rung).toBe('visionos-direct');
            expect(record.itag).toBe(140);
            expect(record.protocol).toBe('progressive');
            expect(record.rangeSupport).toBe(true);

            const blob = JSON.stringify(log);
            expect(JSON.parse(blob)).toHaveLength(1);
            expectNoLeaks(blob, 'recentResolveLog()');
        });

        it('the /debug slice (last 12, newest first) round-trips with ISO timestamps', async () => {
            await getStreamUrl('trk_1', {
                probeImpl: (async () =>
                    new Response(null, {
                        headers: { 'content-range': 'bytes 0-0/3456789' },
                        status: 206,
                    })) as unknown as typeof fetch,
                relayBase: 'https://relay.test',
            });
            const slice = recentResolveLog().slice(-12).reverse();
            expect(slice).toHaveLength(1);
            expect(typeof slice[0]?.at).toBe('string');
            expect(Number.isNaN(Date.parse(slice[0]?.at as string))).toBe(false);
            expectNoLeaks(JSON.stringify(slice), 'the /debug resolver slice');
        });
    });

    describe('/debug health state assembly', () => {
        it('the full read model round-trips as one JSON blob, leak-free', async () => {
            // Scrobble panel: depth + flush timestamp (offline fire).
            const bridge = new StreamifyScrobbleBridge({ clock, tallyStorage: memTally() });
            await fireOfflineListen(bridge, songRow('trk_1'), (ms) => {
                now += ms;
            });

            // Resolver panel: one rung record.
            await getStreamUrl('trk_1', {
                probeImpl: (async () =>
                    new Response(null, {
                        headers: { 'content-range': 'bytes 0-0/3456789' },
                        status: 206,
                    })) as unknown as typeof fetch,
                relayBase: 'https://relay.test',
            });

            // Ledger panel: one counted local play.
            recordCountedPlay({
                album: 'Album',
                artist: 'Artist',
                durationMs: 300_000,
                playedMs: 150_000,
                rung: 'visionos-direct',
                title: 'Fixture Title',
                trackId: 'trk_1',
            });

            // CRDT panel: a live synced state + a persisted timestamp.
            setStreamifyPlaylistsState({ syncedAt: 1_700_000_000_123, syncing: false });
            backing.set(
                'streamify.p3a.playlists.user_1',
                JSON.stringify({ savedAt: '2026-11-15T00:00:00.000Z', v: 1 }),
            );
            const playlists = getStreamifyPlaylistsState();

            // The route's exact read model (primitives + slices only — the
            // CRDT store's Maps/Sets are deliberately never serialized).
            const health = {
                crdt: {
                    lastPersist: playlistPersistTimestamp('user_1'),
                    outboxDepth: playlists.outbox.size,
                    syncedAt: playlists.syncedAt,
                    syncing: playlists.syncing,
                },
                ledger: ledgerDebugInfo(),
                realtime: playlistRealtimeStatus(),
                resolver: recentResolveLog().slice(-12).reverse(),
                scrobble: {
                    depth: bridge.pendingScrobbles().length,
                    lastFlushAt: bridge.lastFlushAt,
                },
            };

            expect(health.crdt.lastPersist).toBe('2026-11-15T00:00:00.000Z');
            expect(health.ledger).toEqual({ cloud: 0, local: 1, total: 1 });
            expect(health.scrobble.depth).toBe(1);
            expect(health.realtime).toEqual({ bound: true, channelLive: true, userId: 'user_1' });

            const blob = JSON.stringify(health);
            const back = JSON.parse(blob);
            expect(back.scrobble.depth).toBe(1);
            expect(back.resolver).toHaveLength(1);
            expect(back.crdt.outboxDepth).toBe(0);
            expectNoLeaks(blob, 'the /debug health read model');
        });
    });
});
