/**
 * P4-A — the Wrapped listening-ledger + aggregation contract.
 *
 * Pure semantics, network-free: window filtering (7d/30d/ytd/all),
 * the cloud-seed merge rule (no double-count), discovery semantics,
 * top-N sorting, zero-state handling (null, never NaN), and the
 * fire-path recording shape.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { LibrarySnapshot } from '../library';
import type { LedgerEvent } from '../wrapped/listening-ledger';

import {
    computeWrappedStats,
    discoveriesInWindow,
    eventsInWindow,
    type WrappedStats,
} from '../wrapped/aggregates';
import {
    getLedger,
    recordCountedPlay,
    resetLedgerForTests,
    seedFromSnapshot,
    setLedgerStorageForTests,
} from '../wrapped/listening-ledger';

const NOW = Date.parse('2026-11-16T12:00:00.000Z');
const DAY = 86_400_000;

function event(trackId: string, daysAgo: number, over: Partial<LedgerEvent> = {}): LedgerEvent {
    return {
        album: 'Album',
        artist: `Artist ${trackId.split('-')[0]}`,
        durationMs: 200000,
        genres: [],
        origin: null,
        playedSec: 120,
        rung: 'visionos-relay',
        source: 'local',
        title: `Title ${trackId}`,
        trackId,
        ts: NOW - daysAgo * DAY,
        ...over,
    };
}

beforeEach(() => {
    resetLedgerForTests();
    setLedgerStorageForTests({
        read: () => null,
        write: () => undefined,
    });
});

afterEach(() => {
    resetLedgerForTests();
});

describe('P4-A wrapped window filtering', () => {
    it('filters by 7d / 30d / ytd / all', () => {
        const events = [
            event('a', 1), // in 7d
            event('b', 10), // in 30d, not 7d
            event('c', 200), // in all (and ytd? Nov 2026 → Apr 2026 is THIS year)
            event('d', 400), // 2025 — outside ytd
        ];
        expect(eventsInWindow(events, '7d', NOW)).toHaveLength(1);
        expect(eventsInWindow(events, '30d', NOW)).toHaveLength(2);
        expect(eventsInWindow(events, 'ytd', NOW)).toHaveLength(3);
        expect(eventsInWindow(events, 'all', NOW)).toHaveLength(4);
    });

    it('ytd starts at Jan 1 UTC', () => {
        const jan1 = Date.parse('2026-01-01T00:00:00.000Z');
        const dec31 = Date.parse('2025-12-31T23:00:00.000Z');
        const stats = computeWrappedStats(
            [event('x', 0, { ts: dec31 }), event('y', 0, { ts: jan1 })],
            'ytd',
            NOW,
        );
        expect(stats.count).toBe(1);
    });
});

describe('P4-A wrapped aggregation', () => {
    it('computes totals, tops, and the hour histogram', () => {
        const events = [
            event('t1', 1, { genres: ['Rock'], playedSec: 100 }),
            event('t1', 2, { genres: ['Rock'], playedSec: 100 }),
            event('t1', 40, { genres: ['Rock'], playedSec: 100 }), // outside 7d
            event('t2', 3, { artist: 'Coltrane', genres: ['Jazz'], playedSec: 250 }),
        ];
        const stats: WrappedStats = computeWrappedStats(events, '7d', NOW);
        expect(stats.count).toBe(3);
        expect(stats.totalSeconds).toBe(450);
        expect(stats.topTracks[0]).toMatchObject({ count: 2, id: 't1', title: 'Title t1' });
        expect(stats.topTracks[1]).toMatchObject({ count: 1, id: 't2' });
        expect(stats.topArtists.map((a) => a.name)).toContain('Coltrane');
        expect(stats.topGenres[0]).toMatchObject({ count: 2, name: 'Rock' }); // the 40d-ago play is outside the window
        // Hour histogram: all three 7d plays land in their ts hour bucket.
        const total = stats.byHour.reduce((a, b) => a + b, 0);
        expect(total).toBe(3);
    });

    it('sorts top-N by count then seconds (deterministic, capped at 10)', () => {
        const events: LedgerEvent[] = [];
        for (let i = 0; i < 15; i += 1) {
            for (let c = 0; c <= i; c += 1) {
                events.push(event(`t${i}`, 1, { playedSec: 10 + i }));
            }
        }
        const stats = computeWrappedStats(events, 'all', NOW);
        expect(stats.topTracks).toHaveLength(10);
        expect(stats.topTracks[0]?.id).toBe('t14');
        expect(stats.topTracks[0]?.count).toBe(15);
        expect(stats.topTracks[9]?.id).toBe('t5');
    });

    it('zero state: count 0, discoveryRatio null — never NaN', () => {
        const stats = computeWrappedStats([], '30d', NOW);
        expect(stats.count).toBe(0);
        expect(stats.discoveryRatio).toBeNull();
        expect(stats.totalSeconds).toBe(0);
        expect(stats.byHour.every((n) => n === 0)).toBe(true);
        expect(stats.topTracks).toEqual([]);
        expect(Number.isNaN(stats.totalSeconds)).toBe(false);
    });
});

describe('P4-A discovery semantics', () => {
    it('counts only first-ever plays — earlier history demotes to repeat', () => {
        const events = [
            event('x', 40), // first-ever OUTSIDE the 7d window
            event('x', 2), // repeat (inside window)
            event('y', 1), // first-ever INSIDE the window
            event('y', 1), // repeat
        ];
        expect(discoveriesInWindow(events, '7d', NOW)).toBe(1);
        const stats = computeWrappedStats(events, '7d', NOW);
        expect(stats.count).toBe(3);
        expect(stats.discoveryRatio).toBeCloseTo(1 / 3, 2);
    });

    it('all-time window: every distinct track counts once', () => {
        const events = [event('x', 1), event('x', 2), event('y', 3)];
        expect(discoveriesInWindow(events, 'all', NOW)).toBe(2);
    });
});

describe('P4-A listening ledger (merge + persistence)', () => {
    it('records counted plays from the fire path (full facts)', () => {
        recordCountedPlay({
            album: 'A',
            artist: 'Ar',
            durationMs: 210000,
            playedMs: 190000,
            rung: 'ios-direct',
            title: 'T',
            trackId: 'trk_1',
        });
        const rows = getLedger();
        expect(rows).toHaveLength(1);
        expect(rows[0]?.playedSec).toBe(190);
        expect(rows[0]?.rung).toBe('ios-direct');
        expect(rows[0]?.source).toBe('local');
    });

    it('seeds cloud events older than the oldest local row (no double-count)', () => {
        const nowSpy = vi.spyOn(Date, 'now').mockReturnValue(NOW);
        try {
            recordCountedPlay({
                album: '',
                artist: 'Recent',
                durationMs: 1,
                playedMs: 60000,
                rung: null,
                title: 'Recent',
                trackId: 'trk_new',
            });
            const snapshot = {
                loadedAt: '2026-11-16T00:00:00.000Z',
                playEvents: [
                    {
                        artist: 'Old',
                        created_at: new Date(NOW - 10 * DAY).toISOString(),
                        duration_played_sec: 100,
                        hour_of_day: 10,
                        title: 'Old',
                        track_sig: 'trk_old',
                    },
                    {
                        // Created (drained) AFTER the local fire → beyond the
                        // oldest-local boundary → excluded (already a local row).
                        artist: 'DrainedLater',
                        created_at: new Date(NOW + 1 * DAY).toISOString(),
                        duration_played_sec: 100,
                        hour_of_day: 11,
                        title: 'Drained',
                        track_sig: 'trk_drained',
                    },
                ],
                sessionKey: 'test',
            } as unknown as LibrarySnapshot;
            seedFromSnapshot(snapshot);
            const merged = getLedger();
            expect(merged).toHaveLength(2);
            expect(merged.map((r) => r.trackId).sort()).toEqual(['trk_new', 'trk_old']);
            expect(merged[0]?.source).toBe('cloud'); // sorted by ts: cloud first
        } finally {
            nowSpy.mockRestore();
        }
    });

    it('an empty local ledger backfills the full cloud history', () => {
        const snapshot = {
            loadedAt: 'x',
            playEvents: [
                {
                    artist: 'A',
                    created_at: new Date(NOW - 30 * DAY).toISOString(),
                    duration_played_sec: 60,
                    hour_of_day: 3,
                    title: 'T1',
                    track_sig: 'c1',
                },
                {
                    artist: 'A',
                    created_at: new Date(NOW - 20 * DAY).toISOString(),
                    duration_played_sec: 60,
                    hour_of_day: 4,
                    title: 'T2',
                    track_sig: 'c2',
                },
            ],
            sessionKey: 'test',
        } as unknown as LibrarySnapshot;
        seedFromSnapshot(snapshot);
        expect(getLedger()).toHaveLength(2);
        const stats = computeWrappedStats(getLedger(), 'ytd', NOW);
        expect(stats.count).toBe(2);
        expect(stats.totalSeconds).toBe(120);
    });

    it('persists through the injected storage lane', () => {
        let written: null | string = null;
        setLedgerStorageForTests({
            read: () => written,
            write: (v) => {
                written = v;
            },
        });
        recordCountedPlay({
            album: '',
            artist: 'A',
            durationMs: 1,
            playedMs: 1000,
            rung: null,
            title: 'T',
            trackId: 'trk_p',
        });
        resetLedgerForTests(); // drops the in-memory cache only
        expect(getLedger()).toHaveLength(1); // rehydrated from the lane
        expect(getLedger()[0]?.trackId).toBe('trk_p');
    });
});
