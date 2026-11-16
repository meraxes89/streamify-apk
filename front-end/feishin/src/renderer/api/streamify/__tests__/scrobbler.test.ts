/**
 * P3-B — the #187 scrobble domain module, re-locked in the feishin tree:
 * the threshold rule (min(half, 4 min)), replay re-arming, and the
 * outbox (offline drain, 200 cap, 5-attempt poison drop, flap reset).
 * Network-free by construction (injected fake storage + senders).
 */
import { describe, expect, it, vi } from 'vitest';

import {
    createOutboxStore,
    MAX_OUTBOX_ATTEMPTS,
    MAX_TICK_MS,
    OUTBOX_CAP,
    type OutboxEntry,
    type OutboxStore,
    PlaySessionTracker,
    type ScrobbleEvent,
    ScrobbleOutbox,
    thresholdFor,
} from '../playback/scrobbler';

const facts = {
    album: 'Album',
    artist: 'Artist',
    durationMs: 300_000,
    rung: 'visionos-direct',
    title: 'Title',
};

function event(trackId: string, overrides: Partial<ScrobbleEvent> = {}): ScrobbleEvent {
    return {
        album: 'Album',
        artist: 'Artist',
        durationMs: 300_000,
        firedAt: 1_000,
        playedMs: 150_000,
        rung: null,
        thresholdMs: 150_000,
        title: 'Title',
        trackId,
        ...overrides,
    };
}

describe('thresholdFor (min(half-duration, 4 min))', () => {
    it('uses half the duration for tracks under 8 minutes', () => {
        expect(thresholdFor(300_000)).toBe(150_000); // 5 min track → 2.5 min
    });

    it('caps at 4 minutes for long tracks', () => {
        expect(thresholdFor(20 * 60_000)).toBe(240_000);
        expect(thresholdFor(Number.MAX_SAFE_INTEGER)).toBe(240_000);
    });

    it('falls back to the cap for unknown durations', () => {
        expect(thresholdFor(0)).toBe(240_000);
        expect(thresholdFor(-1)).toBe(240_000);
        expect(thresholdFor(Number.NaN)).toBe(240_000);
        expect(thresholdFor(Number.POSITIVE_INFINITY)).toBe(240_000);
    });
});

describe('PlaySessionTracker', () => {
    it('fires exactly once, on the crossing tick, carrying the facts', () => {
        const tracker = new PlaySessionTracker();
        tracker.start('trk_1', 0);

        // Ticks (the runtime feeds ≤ MAX_TICK_MS per call — the bridge
        // chunks sparse deltas to honor the same cap).
        tracker.accrueTrack('trk_1', 60_000, 60_000, facts);
        tracker.accrueTrack('trk_1', 60_000, 120_000, facts);
        expect(tracker.accrueTrack('trk_1', 29_999, 149_999, facts)).toBeNull();

        const crossing = tracker.accrueTrack('trk_1', 1, 150_001, facts);
        expect(crossing).not.toBeNull();
        expect(crossing).toMatchObject({
            durationMs: 300_000,
            firedAt: 150_001,
            playedMs: 150_000,
            thresholdMs: 150_000,
            trackId: 'trk_1',
        });

        // Never twice for the same listen.
        expect(tracker.accrueTrack('trk_1', 60_000, 210_000, facts)).toBeNull();
    });

    it('uses the 4-minute cap for unknown durations', () => {
        const tracker = new PlaySessionTracker();
        tracker.start('trk_1', 0);
        const unknown = { ...facts, durationMs: 0 };
        for (let tick = 0; tick < 3; tick += 1) {
            expect(tracker.accrueTrack('trk_1', 60_000, (tick + 1) * 60_000, unknown)).toBeNull();
        }
        // The 4th minute crosses the 240 s cap.
        expect(tracker.accrueTrack('trk_1', 60_000, 240_000, unknown)).not.toBeNull();
    });

    it('a deliberate replay re-arms the threshold', () => {
        const tracker = new PlaySessionTracker();
        tracker.start('trk_1', 0);
        for (let tick = 0; tick < 3; tick += 1) {
            tracker.accrueTrack('trk_1', 50_000, (tick + 1) * 50_000, facts);
        }
        expect(tracker.current).toMatchObject({ fired: true, playedMs: 150_000 });

        tracker.start('trk_1', 200_000); // replay
        expect(tracker.firedFor('trk_1')).toBe(false);
        for (let tick = 0; tick < 3; tick += 1) {
            tracker.accrueTrack('trk_1', 50_000, 200_000 + (tick + 1) * 50_000, facts);
        }
        expect(tracker.current).toMatchObject({ fired: true, playedMs: 150_000 });
    });

    it('a skip (abandon) never fires and clears the session', () => {
        const tracker = new PlaySessionTracker();
        tracker.start('trk_1', 0);
        tracker.accrueTrack('trk_1', 100_000, 100_000, facts);
        tracker.abandon();
        expect(tracker.current).toBeNull();
        expect(tracker.accrueTrack('trk_1', 60_000, 160_000, facts)).toBeNull();
    });

    it('bounds any single tick to MAX_TICK_MS (the stall guard)', () => {
        const tracker = new PlaySessionTracker();
        tracker.start('trk_1', 0);
        // A 10-minute dt in one tick accrues only one minute...
        tracker.accrueTrack('trk_1', 600_000, 600_000, facts);
        expect(tracker.playedMsOf('trk_1')).toBe(MAX_TICK_MS);
    });

    it('accrues only for the session track, and ignores non-positive dt', () => {
        const tracker = new PlaySessionTracker();
        tracker.start('trk_1', 0);
        expect(tracker.accrueTrack('trk_2', 60_000, 60_000, facts)).toBeNull();
        expect(tracker.accrueTrack('trk_1', 0, 60_000, facts)).toBeNull();
        expect(tracker.accrueTrack('trk_1', -5, 60_000, facts)).toBeNull();
        expect(tracker.playedMsOf('trk_1')).toBe(0);
    });

    it('continue() restores a persisted session — a fired session stays fired', () => {
        const tracker = new PlaySessionTracker();
        tracker.continue({ playedMs: 240_000, scrobbled: true, startedAt: 5, trackId: 'trk_9' });
        expect(tracker.firedFor('trk_9')).toBe(true);
        expect(tracker.accrueTrack('trk_9', 60_000, 999, facts)).toBeNull();
        expect(tracker.playedMsOf('trk_9')).toBe(240_000);
    });

    it('continue() restores an in-flight session that still crosses later', () => {
        const tracker = new PlaySessionTracker();
        tracker.continue({ playedMs: 100_000, scrobbled: false, startedAt: 5, trackId: 'trk_9' });
        const fired = tracker.accrueTrack('trk_9', 50_000, 1_000_000, facts);
        expect(fired).not.toBeNull();
        expect(fired!.playedMs).toBe(150_000);
    });
});

describe('ScrobbleOutbox', () => {
    function memStore(seed: OutboxEntry[] = []): OutboxStore & { dump(): OutboxEntry[] } {
        let entries: null | OutboxEntry[] = seed.length > 0 ? seed : null;
        return {
            dump: () => (entries === null ? [] : [...entries]),
            load: () => entries,
            save: (next: OutboxEntry[]) => {
                entries = [...next];
            },
        };
    }

    it('drains every entry when the send succeeds, and persists the empty state', async () => {
        const store = memStore();
        const outbox = new ScrobbleOutbox(store);
        outbox.push(event('a'));
        outbox.push(event('b'));

        const send = vi.fn(async () => true);
        const sent = await outbox.drain(send, () => true);

        expect(sent).toBe(2);
        expect(outbox.size).toBe(0);
        expect(store.dump()).toHaveLength(0);
    });

    it('offline (canSend false) is a no-op — entries survive untouched', async () => {
        const outbox = new ScrobbleOutbox(memStore());
        outbox.push(event('a'));
        const send = vi.fn(async () => true);
        const sent = await outbox.drain(send, () => false);
        expect(sent).toBe(0);
        expect(send).not.toHaveBeenCalled();
        expect(outbox.size).toBe(1);
    });

    it('failed sends keep entries with attempts++ (the offline flap)', async () => {
        const outbox = new ScrobbleOutbox(memStore());
        outbox.push(event('a'));

        await outbox.drain(
            async () => false,
            () => true,
        );
        const pending = outbox.pending();
        expect(pending).toHaveLength(1);
        expect(pending[0]!.attempts).toBe(1);

        await outbox.drain(
            async () => false,
            () => true,
        );
        expect(outbox.pending()[0]!.attempts).toBe(2);
    });

    it('poison-drops an entry after MAX_OUTBOX_ATTEMPTS failed sends', async () => {
        const outbox = new ScrobbleOutbox(memStore());
        outbox.push(event('poison'));

        for (let i = 0; i < MAX_OUTBOX_ATTEMPTS; i += 1) {
            await outbox.drain(
                async () => false,
                () => true,
            );
        }
        expect(outbox.size).toBe(0); // dropped, bounded loss
    });

    it('flap reset: any success resets every attempt counter', async () => {
        const outbox = new ScrobbleOutbox(memStore());
        outbox.push(event('a'));
        outbox.push(event('b'));

        // Both flap twice.
        await outbox.drain(
            async () => false,
            () => true,
        );
        await outbox.drain(
            async () => false,
            () => true,
        );
        expect(outbox.pending().every((e) => e.attempts === 2)).toBe(true);

        // Then connectivity returns: a becomes healthy, b stays broken.
        let first = true;
        await outbox.drain(
            async () => {
                if (first) {
                    first = false;
                    return true;
                }
                return false;
            },
            () => true,
        );

        const pending = outbox.pending();
        expect(pending).toHaveLength(1); // a drained
        expect(pending[0]!.attempts).toBe(0); // b's counter was RESET by a's success
    });

    it('a throwing send counts as a failure, never crashes the drain', async () => {
        const outbox = new ScrobbleOutbox(memStore());
        outbox.push(event('a'));
        const sent = await outbox.drain(
            () => {
                throw new Error('network vanished mid-send');
            },
            () => true,
        );
        expect(sent).toBe(0);
        expect(outbox.pending()[0]!.attempts).toBe(1);
    });

    it('caps at OUTBOX_CAP entries — the oldest drop', () => {
        const outbox = new ScrobbleOutbox(memStore(), OUTBOX_CAP);
        for (let i = 0; i < OUTBOX_CAP + 25; i += 1) {
            outbox.push(event(`trk_${i}`));
        }
        expect(outbox.size).toBe(OUTBOX_CAP);
        const ids = outbox.pending().map((e) => e.trackId);
        expect(ids[0]).toBe(`trk_${25}`); // the newest OUTBOX_CAP survive
    });

    it('entries pushed DURING a drain join the next one (snapshot iteration)', async () => {
        const outbox = new ScrobbleOutbox(memStore());
        outbox.push(event('a'));

        let pushedDuring = false;
        const sent = await outbox.drain(
            async () => {
                if (!pushedDuring) {
                    pushedDuring = true;
                    outbox.push(event('late'));
                }
                return true;
            },
            () => true,
        );

        expect(sent).toBe(1); // only 'a' was in the snapshot batch
        expect(outbox.size).toBe(1); // 'late' survived for the next drain
    });

    it('re-loads persisted entries from the store on construction', async () => {
        const seed: OutboxEntry[] = [{ ...event('seeded'), attempts: 3 }];
        const outbox = new ScrobbleOutbox(memStore(seed));
        expect(outbox.size).toBe(1);
        await outbox.drain(
            async () => true,
            () => true,
        );
        expect(outbox.size).toBe(0);
    });
});

describe('createOutboxStore (localStorage)', () => {
    it('filters corrupt rows on load and tolerates a throwing storage', () => {
        const backing = new Map<string, string>();
        const storage = {
            getItem: (k: string) => backing.get(k) ?? null,
            removeItem: (k: string) => void backing.delete(k),
            setItem: (k: string, v: string) => {
                backing.set(k, v);
            },
        };
        vi.stubGlobal('localStorage', storage);

        try {
            const store = createOutboxStore('k');
            expect(store.load()).toBeNull(); // nothing yet

            store.save([{ ...event('a'), attempts: 0 }, 'garbage' as unknown as OutboxEntry]);
            const loaded = store.load();
            expect(loaded).toHaveLength(1);
            expect(loaded![0]!.trackId).toBe('a');

            backing.set('k', 'not json');
            expect(store.load()).toBeNull();
        } finally {
            vi.unstubAllGlobals();
        }
    });
});
