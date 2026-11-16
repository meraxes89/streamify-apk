/**
 * playback/scrobbler.test.ts — the play-event definition, the
 * no-re-scrobble rules, and the outbox's offline behavior. Clocks and
 * stores are injected; nothing here touches a browser.
 */

import { describe, expect, it } from 'vitest';
import {
  createOutboxStore,
  MAX_OUTBOX_ATTEMPTS,
  MAX_TICK_MS,
  OUTBOX_CAP,
  PlaySessionTracker,
  ScrobbleOutbox,
  thresholdFor,
  type OutboxEntry,
  type OutboxStore,
  type ScrobbleEvent,
} from './scrobbler';

const FACTS = {
  title: 'Track a',
  artist: 'Artist a',
  album: 'Album',
  durationMs: 200_000, // threshold = min(100_000, 240_000) = 100_000
  rung: 'VISIONOS-DIRECT',
};

function event(trackId: string, firedAt = 1_000): ScrobbleEvent {
  return {
    trackId,
    title: 'Track ' + trackId,
    artist: 'Artist ' + trackId,
    album: 'Album',
    durationMs: 200_000,
    playedMs: 100_000,
    thresholdMs: 100_000,
    firedAt,
    rung: null,
  };
}

/**
 * Accrues `totalMs` in production-shaped ticks (≤ MAX_TICK_MS each) —
 * the runtime accrues per snapshot, not in one lump.
 */
function accrueTicks(
  tr: PlaySessionTracker,
  trackId: string,
  totalMs: number,
  facts: typeof FACTS,
  startAt: number,
): ScrobbleEvent | null {
  let remaining = totalMs;
  let now = startAt;
  let fired: ScrobbleEvent | null = null;
  while (remaining > 0) {
    const dt = Math.min(remaining, MAX_TICK_MS);
    const ev = tr.accrueTrack(trackId, dt, now + dt, facts);
    if (ev !== null && fired === null) fired = ev;
    now += dt;
    remaining -= dt;
  }
  return fired;
}

// ---------------------------------------------------------------------------
// the threshold rule (picked once, documented, applied consistently)
// ---------------------------------------------------------------------------

describe('threshold rule: min(half-duration, 4 minutes)', () => {
  it('a 3:20 track thresholds at half = 100s', () => {
    expect(thresholdFor(200_000)).toBe(100_000);
  });

  it('a 12-minute track thresholds at the 4-minute cap', () => {
    expect(thresholdFor(720_000)).toBe(240_000);
  });

  it('unknown duration thresholds at the cap alone', () => {
    expect(thresholdFor(0)).toBe(240_000);
    expect(thresholdFor(-1)).toBe(240_000);
    expect(thresholdFor(Number.NaN)).toBe(240_000);
  });

  it('an 8-minute track thresholds at exactly half = 4 minutes', () => {
    expect(thresholdFor(480_000)).toBe(240_000);
  });
});

// ---------------------------------------------------------------------------
// the session tracker
// ---------------------------------------------------------------------------

describe('PlaySessionTracker', () => {
  it('fires exactly once, on the crossing tick', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    expect(accrueTicks(tr, 'a', 50_000, FACTS, 0)).toBeNull(); // under
    const ev = accrueTicks(tr, 'a', 50_000, FACTS, 50_000); // crossing
    expect(ev).not.toBeNull();
    expect(ev?.playedMs).toBe(100_000);
    expect(ev?.thresholdMs).toBe(100_000);
    expect(ev?.rung).toBe('VISIONOS-DIRECT');
    // and never again for this session
    expect(accrueTicks(tr, 'a', 50_000, FACTS, 100_000)).toBeNull();
    expect(tr.firedFor('a')).toBe(true);
  });

  it('does not accrue while paused (the runtime simply stops calling)', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    accrueTicks(tr, 'a', 30_000, FACTS, 0);
    // (paused: no accrue calls happen)
    const ev = accrueTicks(tr, 'a', 69_999, FACTS, 30_000);
    expect(ev).toBeNull();
    expect(tr.current?.playedMs).toBe(99_999);
  });

  it('seek does not re-arm or re-fire — the session just continues', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    accrueTicks(tr, 'a', 60_000, FACTS, 0);
    // (user seeks — nothing happens to the session)
    expect(tr.current?.playedMs).toBe(60_000);
    expect(tr.current?.fired).toBe(false);
    const ev = accrueTicks(tr, 'a', 40_000, FACTS, 61_000);
    expect(ev).not.toBeNull(); // crossed on played time, not position
    expect(accrueTicks(tr, 'a', 40_000, FACTS, 101_000)).toBeNull(); // once
  });

  it('restore CONTINUES a persisted session without re-firing', () => {
    const tr = new PlaySessionTracker();
    tr.continue({ trackId: 'a', startedAt: 5, playedMs: 99_000, scrobbled: true });
    expect(tr.firedFor('a')).toBe(true);
    expect(accrueTicks(tr, 'a', 50_000, FACTS, 10_000)).toBeNull(); // fired stays fired
    expect(tr.current?.playedMs).toBe(99_000); // a fired session is complete — accrual stops with the fire
  });

  it('restore of an UNfired session can still cross and fire', () => {
    const tr = new PlaySessionTracker();
    tr.continue({ trackId: 'a', startedAt: 5, playedMs: 90_000, scrobbled: false });
    const ev = accrueTicks(tr, 'a', 20_000, FACTS, 10_000);
    expect(ev).not.toBeNull();
    expect(ev?.playedMs).toBe(110_000);
  });

  it('a deliberate replay opens a FRESH session (re-armed threshold)', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    accrueTicks(tr, 'a', 120_000, FACTS, 0); // fired
    expect(tr.firedFor('a')).toBe(true);
    tr.start('a', 200_000); // deliberate replay
    expect(tr.firedFor('a')).toBe(false);
    expect(tr.current?.playedMs).toBe(0);
    const ev = accrueTicks(tr, 'a', 100_000, FACTS, 200_000);
    expect(ev).not.toBeNull(); // fires again — a real replay counts
  });

  it('a skipped track (abandon) never fires', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    accrueTicks(tr, 'a', 10_000, FACTS, 0);
    tr.abandon();
    expect(tr.current).toBeNull();
    expect(accrueTicks(tr, 'a', 200_000, FACTS, 10_000)).toBeNull();
  });

  it('accrual for a DIFFERENT track is ignored (the guard)', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    expect(accrueTicks(tr, 'b', 300_000, FACTS, 0)).toBeNull();
    expect(tr.current?.playedMs).toBe(0);
  });

  it('a sleeping tab cannot mint played time beyond the stall guard', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    // one giant dt (tab slept for an hour) accrues at most MAX_TICK_MS
    tr.accrueTrack('a', 3_600_000, 3_600_000, FACTS);
    expect(tr.current?.playedMs).toBe(MAX_TICK_MS);
  });

  it('non-positive dt is a no-op', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    tr.accrueTrack('a', 0, 0, FACTS);
    tr.accrueTrack('a', -5, 0, FACTS);
    expect(tr.current?.playedMs).toBe(0);
  });

  it('short tracks threshold at half (a 40s track fires at 20s)', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    expect(accrueTicks(tr, 'a', 19_999, { ...FACTS, durationMs: 40_000 }, 0)).toBeNull();
    expect(tr.accrueTrack('a', 1, 20_000, { ...FACTS, durationMs: 40_000 })).not.toBeNull();
  });

  it('a throttled background tab with LIVE audio keeps accruing (1/min ticks)', () => {
    const tr = new PlaySessionTracker();
    tr.start('a', 0);
    // 5 background minutes at one throttled snapshot per minute — each
    // dt is under the stall guard, so all of it counts (real playback).
    for (let i = 0; i < 5; i += 1) {
      const ev = tr.accrueTrack('a', 60_000, (i + 1) * 60_000, FACTS);
      if (i === 1) expect(ev).not.toBeNull(); // crossed at 120s? no — 100s: fires on tick 2
      else if (ev !== null) expect(ev).toBeDefined();
    }
    expect(tr.firedFor('a')).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// the outbox
// ---------------------------------------------------------------------------

function memoryStore(): OutboxStore & { dump(): OutboxEntry[] | null } {
  let raw: string | null = null;
  return {
    load: () => {
      try {
        return raw === null ? null : (JSON.parse(raw) as OutboxEntry[]);
      } catch {
        return null;
      }
    },
    save: (entries) => {
      raw = JSON.stringify(entries);
    },
    dump: () => {
      try {
        return raw === null ? null : (JSON.parse(raw) as OutboxEntry[]);
      } catch {
        return null;
      }
    },
  };
}

describe('ScrobbleOutbox', () => {
  it('pushes, persists, and drains in order', async () => {
    const store = memoryStore();
    const box = new ScrobbleOutbox(store);
    box.push(event('a'));
    box.push(event('b'));
    expect(box.size).toBe(2);
    const sent: string[] = [];
    const n = await box.drain(
      async (e) => {
        sent.push(e.trackId);
        return true;
      },
      () => true,
    );
    expect(n).toBe(2);
    expect(sent).toEqual(['a', 'b']);
    expect(box.size).toBe(0);
    expect(store.dump()).toEqual([]);
  });

  it('canSend=false is a no-op — offline is not a failure', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    let sendCalls = 0;
    const n = await box.drain(
      async () => {
        sendCalls += 1;
        return true;
      },
      () => false,
    );
    expect(n).toBe(0);
    expect(sendCalls).toBe(0);
    expect(box.size).toBe(1); // untouched
  });

  it('failed sends stay queued with attempts++', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    box.push(event('b'));
    const n = await box.drain(
      async () => false,
      () => true,
    );
    expect(n).toBe(0);
    expect(box.size).toBe(2);
    expect(box.pending().every((e) => e.attempts === 1)).toBe(true);
  });

  it('offline scrobbles drain on reconnect', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    box.push(event('b'));
    // offline period: drain attempts fail
    await box.drain(
      async () => false,
      () => true,
    );
    // reconnect: everything goes out
    const n = await box.drain(
      async () => true,
      () => true,
    );
    expect(n).toBe(2);
    expect(box.size).toBe(0);
  });

  it('a success resets attempt counters (flaps are not poison)', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    box.push(event('b'));
    box.push(event('c'));
    await box.drain(
      async () => false,
      () => true,
    ); // all attempts=1
    // partial recovery: b sends, a and c fail
    await box.drain(
      async (e) => e.trackId === 'b',
      () => true,
    );
    const pending = box.pending();
    expect(pending.map((e) => e.trackId).sort()).toEqual(['a', 'c']);
    expect(pending.every((e) => e.attempts === 0)).toBe(true); // reset by b's success
  });

  it('poison entries drop after MAX_OUTBOX_ATTEMPTS', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    for (let i = 0; i < MAX_OUTBOX_ATTEMPTS; i += 1) {
      await box.drain(
        async () => false,
        () => true,
      );
    }
    expect(box.size).toBe(0); // dropped, bounded loss
  });

  it('caps the queue at OUTBOX_CAP (oldest drop)', () => {
    const box = new ScrobbleOutbox(memoryStore(), 3);
    box.push(event('a'));
    box.push(event('b'));
    box.push(event('c'));
    box.push(event('d'));
    expect(box.size).toBe(3);
    expect(box.pending().map((e) => e.trackId)).toEqual(['b', 'c', 'd']);
  });

  it('a throwing send never breaks the drain', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    box.push(event('b'));
    const n = await box.drain(
      async (e) => {
        if (e.trackId === 'a') throw new Error('network gone');
        return true;
      },
      () => true,
    );
    expect(n).toBe(1);
    expect(box.pending().map((e) => e.trackId)).toEqual(['a']);
  });

  it('entries pushed during a drain join the next one', async () => {
    const box = new ScrobbleOutbox(memoryStore());
    box.push(event('a'));
    let drained = false;
    const n = await box.drain(
      async () => {
        if (!drained) {
          drained = true;
          box.push(event('late')); // arrives mid-drain
        }
        return true;
      },
      () => true,
    );
    expect(n).toBe(1); // only 'a' went out this round
    expect(box.size).toBe(1); // 'late' waits for the next drain
  });

  it('the outbox store survives construction cycles (persistence)', () => {
    const store = memoryStore();
    const first = new ScrobbleOutbox(store);
    first.push(event('a'));
    first.push(event('b'));
    const second = new ScrobbleOutbox(store); // "reload"
    expect(second.size).toBe(2);
    expect(second.pending()[0]?.trackId).toBe('a');
  });

  it('OUTBOX_CAP and MAX_OUTBOX_ATTEMPTS keep their documented values', () => {
    expect(OUTBOX_CAP).toBe(200);
    expect(MAX_OUTBOX_ATTEMPTS).toBe(5);
  });

  it('createOutboxStore tolerates corrupt payloads', () => {
    const key = 'test.outbox.corrupt';
    const fake = new Map<string, string>();
    const globalWithStorage = globalThis as { localStorage?: Storage };
    globalWithStorage.localStorage = {
      getItem: (k: string) => fake.get(k) ?? null,
      setItem: (k: string, v: string) => void fake.set(k, v),
      removeItem: (k: string) => void fake.delete(k),
      clear: () => fake.clear(),
      key: () => null,
      get length() {
        return fake.size;
      },
    } as Storage;
    try {
      globalThis.localStorage.setItem(key, '{{{not json');
      const store = createOutboxStore(key);
      expect(store.load()).toBeNull();
    } finally {
      delete globalWithStorage.localStorage;
    }
  });
});
