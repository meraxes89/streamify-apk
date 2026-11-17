/**
 * W3-B — Streamify Wrapped extension: the listening-rhythm folds (peak
 * hour / weekday), the streak math, the discovery-origin ledger lane
 * (origin-hints registry + recordCountedPlay annotation + the Automix
 * claim), the discovery-graduation stat, and the share-card data
 * contract. Network-free: injectable storages, frozen clocks, pure folds.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { MetadataProvider, YtmSongRef } from '../metadata/provider';
import type { LedgerEvent } from '../wrapped/listening-ledger';

import { automixRadio } from '../catalog/automix';
import { computeWrappedStats } from '../wrapped/aggregates';
import {
    getLedger,
    recordCountedPlay,
    resetLedgerForTests,
    setLedgerStorageForTests,
} from '../wrapped/listening-ledger';
import {
    ORIGIN_AUTOMIX,
    originOf,
    rememberOrigins,
    resetOriginHintsForTests,
    setOriginHintsStorageForTests,
} from '../wrapped/origin-hints';

import {
    computeDiscoveryGraduation,
    computeListeningStreak,
    computeWrappedRhythm,
    formatWrappedShareCardData,
    WRAPPED_RECOMMENDATION_ORIGINS,
} from '/@/renderer/features/streamify/wrapped/wrapped-engine';

const NOW = Date.parse('2026-11-16T12:00:00.000Z');
const DAY = 86_400_000;

function event(trackId: string, ts: number, over: Partial<LedgerEvent> = {}): LedgerEvent {
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
        ts,
        ...over,
    };
}

beforeEach(() => {
    resetLedgerForTests();
    setLedgerStorageForTests({ read: () => null, write: () => undefined });
    resetOriginHintsForTests();
    setOriginHintsStorageForTests({ read: () => null, write: () => undefined });
});

afterEach(() => {
    resetLedgerForTests();
    resetOriginHintsForTests();
});

// ---------------------------------------------------------------------------
// Listening rhythm
// ---------------------------------------------------------------------------

describe('w3b wrapped — listening rhythm', () => {
    it('derives peak hour / peak weekday from local time (earliest-index ties)', () => {
        // Hour-of-day / weekday expectations are derived with the same
        // local-time API the engine uses — the test is TZ-agnostic.
        const at = (hour: number, weekday: number): number => {
            const d = new Date(NOW);
            const currentDay = d.getDay();
            d.setDate(d.getDate() + ((weekday - currentDay + 7) % 7));
            d.setHours(hour, 0, 0, 0);
            return d.getTime();
        };
        const events = [
            event('a', at(21, 1)),
            event('b', at(21, 3)),
            event('c', at(21, 5)),
            event('d', at(9, 3)),
        ];
        const rhythm = computeWrappedRhythm(events, 'all', NOW);
        expect(rhythm.peakHour).toBe(21);
        expect(rhythm.byWeekday[3]).toBe(2);
        expect(rhythm.peakWeekday).toBe(3);

        // Ties resolve to the earliest slot (hour 0 vs hour 0).
        const tie = computeWrappedRhythm(
            [event('x', at(0, 2)), event('y', at(0, 4)), event('z', at(23, 6))],
            'all',
            NOW,
        );
        expect(tie.peakHour).toBe(0);
    });

    it('windows filter the rhythm fold (7d vs all)', () => {
        const events = [event('old', NOW - 40 * DAY), event('new', NOW - 1 * DAY)];
        expect(computeWrappedRhythm(events, '7d', NOW).byWeekday.reduce(sum, 0)).toBe(1);
        expect(computeWrappedRhythm(events, 'all', NOW).byWeekday.reduce(sum, 0)).toBe(2);
    });

    it('empty ledger degrades to honest nulls (never NaN)', () => {
        const rhythm = computeWrappedRhythm([], '30d', NOW);
        expect(rhythm.peakHour).toBeNull();
        expect(rhythm.peakWeekday).toBeNull();
        expect(rhythm.byWeekday).toEqual([0, 0, 0, 0, 0, 0, 0]);
    });
});

function sum(a: number, b: number): number {
    return a + b;
}

// ---------------------------------------------------------------------------
// Streaks
// ---------------------------------------------------------------------------

describe('w3b wrapped — listening streaks (local days)', () => {
    it('counts the longest run of consecutive listening days', () => {
        const day = (n: number): number => new Date(NOW - n * DAY).setHours(20, 0, 0, 0);
        const events = [
            event('a', day(10)),
            event('b', day(9)),
            event('c', day(8)), // run of 3
            event('d', day(5)),
            event('e', day(4)), // run of 2
            event('f', day(0)), // today (run of 1)
        ];
        const streak = computeListeningStreak(events, NOW);
        expect(streak.longestDays).toBe(3);
        expect(streak.currentDays).toBe(1);
    });

    it('the current streak survives one calendar rollover (yesterday)', () => {
        const yesterday = new Date(NOW - DAY).setHours(20, 0, 0, 0);
        const twoDays = new Date(NOW - 2 * DAY).setHours(20, 0, 0, 0);
        const streak = computeListeningStreak([event('a', twoDays), event('b', yesterday)], NOW);
        expect(streak.currentDays).toBe(2);
        expect(streak.longestDays).toBe(2);
    });

    it('an older last listen breaks the current streak but keeps the longest', () => {
        const threeDaysAgo = new Date(NOW - 3 * DAY).setHours(20, 0, 0, 0);
        const fourDaysAgo = new Date(NOW - 4 * DAY).setHours(20, 0, 0, 0);
        const streak = computeListeningStreak(
            [event('a', fourDaysAgo), event('b', threeDaysAgo)],
            NOW,
        );
        expect(streak.currentDays).toBe(0);
        expect(streak.longestDays).toBe(2);
    });

    it('empty ledger is a zero streak', () => {
        expect(computeListeningStreak([], NOW)).toEqual({ currentDays: 0, longestDays: 0 });
    });
});

// ---------------------------------------------------------------------------
// The origin lane (hints registry + ledger annotation + the Automix claim)
// ---------------------------------------------------------------------------

describe('w3b wrapped — the discovery-origin lane', () => {
    it('origin hints are first-writer-wins and persist through the injected storage', () => {
        const blob = { raw: null as null | string };
        setOriginHintsStorageForTests({
            read: () => blob.raw,
            write: (value) => {
                blob.raw = value;
            },
        });
        rememberOrigins(['vid-1', 'vid-2'], ORIGIN_AUTOMIX);
        rememberOrigins(['vid-1', 'vid-3'], 'discover-mix'); // vid-1 keeps automix
        expect(originOf('vid-1')).toBe(ORIGIN_AUTOMIX);
        expect(originOf('vid-2')).toBe(ORIGIN_AUTOMIX);
        expect(originOf('vid-3')).toBe('discover-mix');
        expect(originOf('unknown')).toBeNull();

        // A fresh module load (simulated by reset + same storage) re-reads.
        resetOriginHintsForTests();
        expect(originOf('vid-2')).toBe(ORIGIN_AUTOMIX);
    });

    it('recordCountedPlay annotates the ledger row with the resolved origin', () => {
        rememberOrigins(['track-9'], 'discover-mix');
        recordCountedPlay({
            album: 'A',
            artist: 'B',
            durationMs: 200000,
            origin: 'discover-mix',
            playedMs: 150000,
            rung: 'visionos-relay',
            title: 'T',
            trackId: 'track-9',
        });
        recordCountedPlay({
            album: 'A2',
            artist: 'B2',
            durationMs: 200000,
            playedMs: 150000, // no origin → honest null
            rung: null,
            title: 'T2',
            trackId: 'track-10',
        });
        const ledger = getLedger();
        expect(ledger).toHaveLength(2);
        expect(ledger[0]?.origin).toBe('discover-mix');
        expect(ledger[1]?.origin).toBeNull();
        // The invariant is untouched: playedSec is the crossing's playedMs/1000.
        expect(ledger[0]?.playedSec).toBe(150);
    });

    it('the Automix engine claims origins for the tracks it surfaces', async () => {
        const songs: YtmSongRef[] = [
            { artist: 'Ar', title: 'One', videoId: 'vid-a' },
            { artist: 'Ar', title: 'Two', videoId: 'vid-b' },
        ];
        const provider = {
            getUpNext: vi.fn(async () => ({ continuation: null, songs })),
        } as unknown as MetadataProvider;
        const out = await automixRadio(provider, 'seed', 2);
        expect(out.map((s) => s.videoId)).toEqual(['vid-a', 'vid-b']);
        expect(originOf('vid-a')).toBe(ORIGIN_AUTOMIX);
        expect(originOf('vid-b')).toBe(ORIGIN_AUTOMIX);
        expect(originOf('seed')).toBeNull(); // the seed is never claimed
    });
});

// ---------------------------------------------------------------------------
// Discovery graduation
// ---------------------------------------------------------------------------

describe('w3b wrapped — discovery graduation (mix → repeat)', () => {
    it('graduates origin-tagged first plays with >= 2 plays, ranked by count', () => {
        const events = [
            event('mix-hit', NOW - 5 * DAY, { origin: 'discover-mix' }),
            event('mix-hit', NOW - 4 * DAY, { origin: 'discover-mix' }),
            event('mix-hit', NOW - 3 * DAY, { origin: 'discover-mix' }),
            event('automix-hit', NOW - 2 * DAY, { origin: ORIGIN_AUTOMIX }),
            event('automix-hit', NOW - 1 * DAY, { origin: ORIGIN_AUTOMIX }),
            event('mix-once', NOW - 1 * DAY, { origin: 'discover-mix' }), // never repeated
            event('organic-repeat', NOW - 1 * DAY), // organic first play
            event('organic-repeat', NOW - 1 * DAY),
        ];
        const graduation = computeDiscoveryGraduation(events, 'all', NOW);
        expect(graduation.graduated.map((g) => g.title)).toEqual([
            'Title mix-hit',
            'Title automix-hit',
        ]);
        expect(graduation.top?.count).toBe(3);
        expect(graduation.top?.origin).toBe('discover-mix');
    });

    it('a first play before the window is not a window discovery', () => {
        const events = [
            event('old-grad', NOW - 40 * DAY, { origin: ORIGIN_AUTOMIX }),
            event('old-grad', NOW - 1 * DAY),
        ];
        expect(computeDiscoveryGraduation(events, '30d', NOW).graduated).toHaveLength(0);
        expect(computeDiscoveryGraduation(events, 'all', NOW).graduated).toHaveLength(1);
    });

    it('honest empty state on a cold ledger', () => {
        const graduation = computeDiscoveryGraduation([], '7d', NOW);
        expect(graduation.top).toBeNull();
        expect(graduation.graduated).toHaveLength(0);
        expect(WRAPPED_RECOMMENDATION_ORIGINS.has('automix')).toBe(true);
        expect(WRAPPED_RECOMMENDATION_ORIGINS.has('discover-mix')).toBe(true);
    });
});

// ---------------------------------------------------------------------------
// The share-card data contract
// ---------------------------------------------------------------------------

describe('w3b wrapped — share-card data formatting', () => {
    it('formats minutes, plays, top-5 tracks and the streak deterministically', () => {
        const stats = computeWrappedStats(
            [
                event('t1', NOW - DAY, { playedSec: 3600 }),
                event('t2', NOW - DAY),
                event('t3', NOW - DAY),
                event('t4', NOW - DAY),
                event('t5', NOW - DAY),
                event('t6', NOW - DAY),
                event('t7', NOW - DAY),
            ],
            '30d',
            NOW,
        );
        const data = formatWrappedShareCardData({
            minutesWord: 'minutes listened',
            playsTemplate: (count) => `${count} verified plays`,
            stats,
            streak: { currentDays: 1, longestDays: 6 },
            streakTemplate: (days) => `${days}-day listening streak`,
            windowLabel: 'Last 30 days',
        });
        expect(data.windowLabel).toBe('Last 30 days');
        expect(data.minutesLabel).toBe('72 minutes listened'); // 3600s + 6 × 120s = 4320s
        expect(data.playsLabel).toBe('7 verified plays');
        expect(data.streakLabel).toBe('6-day listening streak');
        expect(data.topTracks).toHaveLength(5); // top 5 of 7, ranked
        expect(data.topTracks[0]?.rank).toBe(1);
        expect(data.topArtistsLabel).toContain('Artist t1');
    });

    it('a sub-2-day streak renders no streak label', () => {
        const stats = computeWrappedStats([event('t1', NOW - DAY)], '7d', NOW);
        const data = formatWrappedShareCardData({
            minutesWord: 'minutes',
            playsTemplate: (count) => `${count} plays`,
            stats,
            streak: { currentDays: 1, longestDays: 1 },
            streakTemplate: (days) => `${days} days`,
            windowLabel: 'W',
        });
        expect(data.streakLabel).toBeNull();
    });
});
