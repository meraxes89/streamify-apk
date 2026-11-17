/**
 * W2-B — the 500 MB OPFS Smart Sync engine, network-free: N+1/N+2 queue
 * lookahead, strict LRU eviction under the cap (manual downloads are NEVER
 * evicted), the save-data skip, and the immediate circuit-breaker halt on
 * 403 / 429 / bot-gate / solver errors. The download pipeline itself is
 * P4-A's tested surface — this lane drives the engine over a recording
 * fake manager (the W2b contract it consumes: startDownload with source
 * 'smart-sync', DownloadOutcome, listEntries, remove, touchPlayed).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

// The engine logs breaker trips through the real logger — node-safe mock.
vi.mock('/@/renderer/utils/logger', () => ({
    logger: { debug: () => undefined, error: () => undefined, warn: () => undefined },
}));

import type { DownloadManager, DownloadOutcome, DownloadSpec } from '../downloads/download-manager';

import { sourceOfMeta } from '../downloads/meta';

import { queueLookahead } from '/@/renderer/features/streamify/smart-sync/queue-source';
import {
    createSmartSyncEngine,
    estimateIncomingBytes,
    isBreakerTrip,
    lruKeyOf,
    SMART_SYNC_BREAKER_MS,
    SMART_SYNC_CAP_BYTES,
    type SmartSyncEngine,
} from '/@/renderer/features/streamify/smart-sync/smart-sync-engine';
import {
    getStreamifySmartSyncState,
    resetStreamifySmartSyncStoreForTests,
} from '/@/renderer/store/streamify-smart-sync.store';

// ---------------------------------------------------------------------------
// The fake download manager (records every call; outcomes scripted per test)
// ---------------------------------------------------------------------------

/** One fake OPFS sidecar entry (bytes + the LRU inputs). */
type FakeEntry = {
    bytes: number;
    completedAt?: null | string;
    lastPlayedAt?: null | string;
    source?: 'manual' | 'smart-sync';
    videoId: string;
};

type FakeManager = Pick<
    DownloadManager,
    'listEntries' | 'remove' | 'startDownload' | 'touchPlayed'
>;

interface RecordedCall {
    spec: DownloadSpec;
}

function fakeManager(opts: {
    entries?: FakeEntry[];
    onStart?: (spec: DownloadSpec) => void;
    outcomes?: DownloadOutcome[];
}): { calls: RecordedCall[]; manager: FakeManager; removed: string[]; touched: string[] } {
    const calls: RecordedCall[] = [];
    const removed: string[] = [];
    const touched: string[] = [];
    // The live entry set — remove() drops from it (the real manager does).
    const live = new Map((opts.entries ?? []).map((e) => [e.videoId, e]));
    const manager: FakeManager = {
        listEntries: async () =>
            [...live.values()].map((e) => ({
                album: null,
                artist: 'A',
                chunkCount: 1,
                chunkSize: 65536,
                ciphertextBytes: e.bytes,
                codecs: null,
                completedAt: e.completedAt ?? null,
                createdAt: '2026-01-01T00:00:00.000Z',
                durationSec: 200,
                key: 'a2V5',
                lastPlayedAt: e.lastPlayedAt ?? null,
                mimeType: 'audio/mp4',
                rung: null,
                songId: `song-${e.videoId}`,
                source: e.source ?? 'manual',
                status: 'complete' as const,
                title: 'T',
                totalPlainLen: e.bytes,
                videoId: e.videoId,
            })),
        remove: async (videoId) => {
            removed.push(videoId);
            live.delete(videoId);
        },
        startDownload: async (spec) => {
            calls.push({ spec });
            opts.onStart?.(spec);
            const outcome = opts.outcomes?.[calls.length - 1] ?? { alreadyActive: true, ok: true };
            if (outcome.ok && 'bytes' in outcome && outcome.bytes > 0) {
                // A completed download becomes a real smart-sync entry (the
                // real manager writes the sidecar; the next budget check
                // must see it).
                live.set(spec.videoId, {
                    bytes: outcome.bytes,
                    lastPlayedAt: null,
                    source: spec.source ?? 'smart-sync',
                    videoId: spec.videoId,
                });
            }
            return outcome;
        },
        touchPlayed: async (videoId) => {
            touched.push(videoId);
        },
    };
    return { calls, manager, removed, touched };
}

function okOutcome(bytes: number): DownloadOutcome {
    return {
        bytes,
        meta: {
            ciphertextBytes: bytes,
            source: 'smart-sync',
            status: 'complete',
            videoId: 'x',
        } as never,
        ok: true,
    };
}

const SPEC = (videoId: string, durationSec: null | number = 240): DownloadSpec => ({
    album: null,
    artist: 'A',
    durationSec,
    songId: `song-${videoId}`,
    source: 'smart-sync',
    title: 'T',
    videoId,
});

// ---------------------------------------------------------------------------
// The engine fixture
// ---------------------------------------------------------------------------

function engine(over: {
    entries?: FakeEntry[];
    outcomes?: DownloadOutcome[];
    saveData?: () => boolean;
    settings?: () => { enabled: boolean; saveDataOverride: boolean };
    storageEstimate?: () => null | Promise<null | { quota: number; usage: number }>;
    upcoming?: DownloadSpec[];
}): { calls: RecordedCall[]; engine: SmartSyncEngine; removed: string[] } {
    const fake = fakeManager({ entries: over.entries, outcomes: over.outcomes });
    const e = createSmartSyncEngine({
        connectionSaveData: over.saveData ?? (() => false),
        isPlaying: () => true,
        manager: fake.manager,
        readQueue: () => ({
            currentVideoId: 'v0',
            upcoming: over.upcoming ?? [SPEC('n1'), SPEC('n2')],
        }),
        settings: over.settings ?? (() => ({ enabled: true, saveDataOverride: false })),
        storageEstimate: over.storageEstimate ?? (() => null),
    });
    return { calls: fake.calls, engine: e, removed: fake.removed };
}

beforeEach(() => {
    resetStreamifySmartSyncStoreForTests();
});

// ---------------------------------------------------------------------------
// N+1 / N+2 lookahead + pre-fetch
// ---------------------------------------------------------------------------

describe('w2b smart-sync — the N+1/N+2 pass', () => {
    it('pre-fetches exactly the next two queue tracks, sequentially, tagged smart-sync', async () => {
        const { calls, engine: e } = engine({ outcomes: [okOutcome(1), okOutcome(1)] });
        const pass = await e.sync();
        expect(pass.prefetched).toEqual(['n1', 'n2']);
        expect(calls.map((c) => c.spec.videoId)).toEqual(['n1', 'n2']);
        for (const call of calls) {
            expect(call.spec.source).toBe('smart-sync');
        }
    });

    it('halts the pass after a tripping failure — N+2 is never attempted', async () => {
        const { calls, engine: e } = engine({
            outcomes: [
                { ok: false, reason: 'upstream responded 403', stage: 'fetch', status: 403 },
            ],
        });
        const pass = await e.sync();
        expect(pass.prefetched).toEqual([]);
        expect(calls).toHaveLength(1); // n1 burned the pass; n2 never ran
    });

    it('skips the whole pass while the breaker cooldown runs', async () => {
        const { calls, engine: e } = engine({});
        // Trip the breaker via a 403 pass first.
        const first = createSmartSyncEngine({
            manager: fakeManager({
                outcomes: [
                    { ok: false, reason: 'upstream responded 403', stage: 'fetch', status: 403 },
                ],
            }).manager,
            readQueue: () => ({ currentVideoId: 'v0', upcoming: [SPEC('n1'), SPEC('n2')] }),
        });
        await first.sync();
        expect(getStreamifySmartSyncState().breaker.openUntilMs).toBeGreaterThan(0);
        const pass = await e.sync();
        expect(pass.halted).toBe('breaker-open');
        expect(calls).toHaveLength(0);
    });

    it('is a no-op when disabled, save-data, not playing, or under storage pressure', async () => {
        const cases: Array<[string, ReturnType<typeof engine>['engine']]> = [
            [
                'disabled',
                engine({ settings: () => ({ enabled: false, saveDataOverride: false }) }).engine,
            ],
            ['save-data', engine({ saveData: () => true }).engine],
            [
                'not-playing',
                createSmartSyncEngine({
                    isPlaying: () => false,
                    manager: fakeManager({}).manager,
                    readQueue: () => ({ currentVideoId: 'v0', upcoming: [SPEC('n1')] }),
                }),
            ],
            [
                'pressure',
                engine({
                    storageEstimate: () => Promise.resolve({ quota: 100, usage: 95 }),
                }).engine,
            ],
        ];
        for (const [name, e] of cases) {
            const pass = await e.sync();
            expect(pass.halted, name).toMatch(/disabled|save-data|not-playing|pressure/);
        }
    });

    it('save-data skip is escaped by the explicit user override', async () => {
        const { calls, engine: e } = engine({
            outcomes: [okOutcome(1), okOutcome(1)],
            saveData: () => true,
            settings: () => ({ enabled: true, saveDataOverride: true }),
        });
        const pass = await e.sync();
        expect(pass.halted).toBe('done');
        expect(calls).toHaveLength(2);
    });
});

// ---------------------------------------------------------------------------
// The circuit breaker classification
// ---------------------------------------------------------------------------

describe('w2b smart-sync — the breaker classification', () => {
    const fail = (reason: string, stage: 'fetch' | 'resolve' | 'seal', status: null | number) =>
        ({ ok: false, reason, stage, status }) as DownloadOutcome;

    it('trips on 403 / 429 / bot-gate vocabulary / solver (resolve-stage) errors', () => {
        expect(isBreakerTrip(fail('upstream responded 403', 'fetch', 403))).toBe(true);
        expect(isBreakerTrip(fail('upstream responded 429', 'fetch', 429))).toBe(true);
        expect(isBreakerTrip(fail('bot-gate challenge required', 'fetch', 200))).toBe(true);
        expect(isBreakerTrip(fail('PO token minting failed', 'seal', null))).toBe(true);
        expect(isBreakerTrip(fail('the solver ladder exhausted', 'resolve', null))).toBe(true);
        expect(isBreakerTrip(okOutcome(1))).toBe(false);
    });

    it('does NOT trip on quota, abort, or plain network failures', () => {
        expect(isBreakerTrip(fail('quota exceeded', 'seal', null))).toBe(false);
        expect(isBreakerTrip(fail('aborted', 'fetch', null))).toBe(false);
        expect(isBreakerTrip(fail('network error', 'fetch', null))).toBe(false);
        expect(isBreakerTrip(fail('upstream responded 500', 'fetch', 500))).toBe(false);
    });

    it('429 and 403 both open the 15-minute cooldown', async () => {
        for (const status of [403, 429]) {
            resetStreamifySmartSyncStoreForTests();
            const e = engine({
                outcomes: [{ ok: false, reason: 'x', stage: 'fetch', status }],
            }).engine;
            await e.sync();
            const breaker = getStreamifySmartSyncState().breaker;
            expect(breaker.openUntilMs).toBeGreaterThan(0);
            expect(breaker.reason).toBe('x');
            expect(e.breakerOpen(breaker.openUntilMs - 1)).toBe(true);
            expect(e.breakerOpen(breaker.openUntilMs + 1)).toBe(false);
        }
    });
});

// ---------------------------------------------------------------------------
// The strict 500 MB LRU eviction engine
// ---------------------------------------------------------------------------

describe('w2b smart-sync — the 500 MB LRU ceiling', () => {
    const MB = 1024 * 1024;
    const iso = (minutesAgo: number) => new Date(Date.now() - minutesAgo * 60 * 1000).toISOString();

    it('evicts least-recently-played smart-sync entries to fit the cap — manual survives', async () => {
        // 495 MB of smart-sync entries (the cap counts ONLY smart-sync) + a
        // 60 MB manual download that must never be touched.
        const entries: FakeEntry[] = [
            { bytes: 200 * MB, lastPlayedAt: iso(60), source: 'smart-sync', videoId: 'old-played' },
            {
                bytes: 200 * MB,
                completedAt: iso(6000),
                source: 'smart-sync',
                videoId: 'old-cached',
            },
            { bytes: 95 * MB, lastPlayedAt: iso(1), source: 'smart-sync', videoId: 'fresh' },
            { bytes: 60 * MB, lastPlayedAt: iso(30), source: 'manual', videoId: 'user-download' },
        ];
        const {
            calls,
            engine: e,
            removed,
        } = engine({
            entries,
            outcomes: [okOutcome(10 * MB), okOutcome(1)],
            // Two 240 s tracks ≈ 2 × ~7 MB estimates: 495 + 14 > 500 → evict.
            upcoming: [SPEC('n1'), SPEC('n2')],
        });
        const pass = await e.sync();
        expect(pass.prefetched).toContain('n1');
        // LRU order: old-cached (no play stamp, oldest completion) is the
        // least-recently-used; evicting its 200 MB frees enough for both.
        expect(removed).toEqual(['old-cached']);
        // The manual download and the fresh smart-sync entry survive.
        expect(removed).not.toContain('user-download');
        expect(removed).not.toContain('fresh');
        // And the new entries were still downloaded (budget made room).
        expect(calls.map((c) => c.spec.videoId)).toEqual(['n1', 'n2']);
    });

    it('evicts deeper when one track is not enough', async () => {
        const entries: FakeEntry[] = [
            { bytes: 490 * MB, lastPlayedAt: iso(500), source: 'smart-sync', videoId: 'huge-old' },
            { bytes: 9 * MB, lastPlayedAt: iso(400), source: 'smart-sync', videoId: 'small-old' },
        ];
        const { engine: e, removed } = engine({
            entries,
            outcomes: [okOutcome(1)],
            upcoming: [SPEC('n1', 300)], // ~8.4 MB estimate
        });
        await e.sync();
        // 499 MB used + ~8.7 MB needed > 500 MB: huge-old (LRU) is evicted;
        // its 490 MB alone free enough — small-old survives.
        expect(removed).toEqual(['huge-old']);
    });

    it('prefers lastPlayedAt over completedAt in the LRU order', () => {
        expect(
            lruKeyOf({ completedAt: '2026-01-01T00:00:00Z', lastPlayedAt: '2026-06-01T00:00:00Z' }),
        ).toBe(Date.parse('2026-06-01T00:00:00Z'));
        expect(lruKeyOf({ completedAt: '2026-01-01T00:00:00Z', lastPlayedAt: null })).toBe(
            Date.parse('2026-01-01T00:00:00Z'),
        );
        expect(lruKeyOf({ completedAt: null, lastPlayedAt: null })).toBe(0);
    });

    it('clearCache removes ONLY smart-sync entries', async () => {
        const entries: FakeEntry[] = [
            { bytes: 10 * MB, source: 'smart-sync', videoId: 's1' },
            { bytes: 10 * MB, source: 'smart-sync', videoId: 's2' },
            { bytes: 10 * MB, source: 'manual', videoId: 'm1' },
            // A P4-A sidecar without the source field reads as manual.
            { bytes: 10 * MB, source: undefined, videoId: 'legacy' },
        ];
        const fake = fakeManager({ entries });
        const e = createSmartSyncEngine({
            manager: fake.manager,
            readQueue: () => ({ currentVideoId: 'v0', upcoming: [] }),
        });
        const result = await e.clearCache();
        expect(result.entries).toBe(2);
        expect(fake.removed).toEqual(['s1', 's2']);
        expect(fake.removed).not.toContain('m1');
        expect(fake.removed).not.toContain('legacy');
        expect(sourceOfMeta(null)).toBe('manual');
    });

    it('usage() counts only smart-sync bytes (the honest meter)', async () => {
        const entries: FakeEntry[] = [
            { bytes: 10 * MB, source: 'smart-sync', videoId: 's1' },
            { bytes: 25 * MB, source: 'manual', videoId: 'm1' },
        ];
        const e = engine({ entries }).engine;
        const usage = await e.usage();
        expect(usage).toEqual({ entries: 1, usedBytes: 10 * MB });
    });

    it('caps the cap at exactly 524,288,000 bytes and 15 minutes', () => {
        expect(SMART_SYNC_CAP_BYTES).toBe(524_288_000);
        expect(SMART_SYNC_BREAKER_MS).toBe(15 * 60 * 1000);
    });

    it('estimates incoming bytes from duration (the budget input)', () => {
        expect(estimateIncomingBytes(SPEC('x', 240))).toBeGreaterThan(0);
        expect(estimateIncomingBytes(SPEC('x', null))).toBe(6 * 1024 * 1024);
    });
});

// ---------------------------------------------------------------------------
// The queue lookahead (player store mocked at the module boundary)
// ---------------------------------------------------------------------------

describe('w2b smart-sync — the queue lookahead', () => {
    it('returns N+1/N+2 in play order from the mocked player store', () => {
        // Driven through queueLookahead's real module with the store mocked.
        // The mock lives in __mocks__ via vi.mock below (hoisted).
        const look = queueLookahead(2);
        expect(look.upcoming.map((s) => s.videoId)).toEqual(['q2', 'q3']);
        expect(look.currentVideoId).toBe('q1');
    });

    it('marks every upcoming spec as smart-sync with resolved videoIds', () => {
        const look = queueLookahead(2);
        for (const spec of look.upcoming) {
            expect(spec.source).toBe('smart-sync');
            expect(spec.videoId).toMatch(/^q\d$/);
        }
    });
});

// The player store is not node-importable (env-settings-overrides touches
// document) — the offline-bypass.test.ts idiom: mock at the module boundary.
vi.mock('/@/renderer/store/player.store', () => {
    const queueSong = (id: string, name: string) => ({
        _uniqueId: `u-${id}`,
        album: null,
        artistName: 'Artist',
        duration: 240000,
        id,
        name,
    });
    const state = {
        getCurrentSong: () => queueSong('trk1', 'Current'),
        player: {
            index: 0,
            repeat: 'none',
            shuffle: 'none',
            status: 'playing',
        },
        queue: {
            default: ['u-trk1', 'u-trk2', 'u-trk3', 'u-trk4'],
            shuffled: [] as number[],
            songs: {
                'u-trk1': queueSong('trk1', 'One'),
                'u-trk2': queueSong('trk2', 'Two'),
                'u-trk3': queueSong('trk3', 'Three'),
                'u-trk4': queueSong('trk4', 'Four'),
            },
        },
    };
    return {
        mapShuffledToQueueIndex: (shuffledIndex: number, shuffled: number[]) =>
            shuffledIndex >= 0 && shuffledIndex < shuffled.length ? shuffled[shuffledIndex] : -1,
        usePlayerStoreBase: { getState: () => state, subscribe: () => () => undefined },
    };
});

// The snapshot lookup lane: trk1..trk4 resolve to videoIds q1..q4.
vi.mock('/@/renderer/api/streamify/engine', () => ({
    currentStreamifyLibrary: () => ({
        songById: new Map(
            ['trk1', 'trk2', 'trk3', 'trk4'].map((id, i) => [id, { id, streamUrl: `q${i + 1}` }]),
        ),
    }),
}));
