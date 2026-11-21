/**
 * playback/gapless.test.ts — the WHEN of gapless: the T-15s trigger,
 * the named edge cases, and the crossfade window arithmetic.
 */

import { describe, expect, it } from 'vitest';
import { GaplessScheduler, PRELOAD_LEAD_MS, PRELOAD_RETRY_MS } from './gapless';

const NOW = 1_000_000;

function tick(overrides: Partial<Parameters<GaplessScheduler['tick']>[0]> = {}) {
  return {
    now: NOW,
    playing: true,
    currentTrackId: 'a',
    positionMs: 0,
    durationMs: 200_000,
    nextTrackId: 'b',
    ...overrides,
  };
}

describe('the preload trigger (T-15s)', () => {
  it('does not fire while more than the lead remains', () => {
    const s = new GaplessScheduler();
    const ops = s.tick(tick({ positionMs: 200_000 - PRELOAD_LEAD_MS - 1 }));
    expect(ops.preload).toBeNull();
  });

  it('fires exactly when remaining crosses the lead', () => {
    const s = new GaplessScheduler();
    const ops = s.tick(tick({ positionMs: 200_000 - PRELOAD_LEAD_MS }));
    expect(ops.preload).toBe('b');
  });

  it('fires once — the second tick in-window is a no-op', () => {
    const s = new GaplessScheduler();
    expect(s.tick(tick({ positionMs: 185_000 })).preload).toBe('b');
    expect(s.tick(tick({ positionMs: 186_000 })).preload).toBeNull();
  });

  it('does not fire while paused', () => {
    const s = new GaplessScheduler();
    const ops = s.tick(tick({ playing: false, positionMs: 190_000 }));
    expect(ops.preload).toBeNull();
  });

  it('does not fire with an unknown duration', () => {
    const s = new GaplessScheduler();
    const ops = s.tick(tick({ durationMs: 0, positionMs: 0 }));
    expect(ops.preload).toBeNull();
  });

  it('does not fire with nothing next (queue exhausted under repeat-off)', () => {
    const s = new GaplessScheduler();
    const ops = s.tick(tick({ positionMs: 190_000, nextTrackId: null }));
    expect(ops.preload).toBeNull();
  });
});

describe('edge case: repeat-one (N+1 is N again)', () => {
  it('prepares nothing — the warm stream IS next', () => {
    const s = new GaplessScheduler();
    const ops = s.tick(tick({ positionMs: 195_000, nextTrackId: 'a' }));
    expect(ops.preload).toBeNull();
  });

  it('arms the crossfade with the current track as its own target', () => {
    const s = new GaplessScheduler({ preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 3_000 });
    const ops = s.tick(tick({ positionMs: 198_000, nextTrackId: 'a' }));
    expect(ops.crossfadeArmed).toBe(true);
    expect(ops.crossfadeTarget).toBe('a');
  });
});

describe('edge case: one-track queue (N+1 is N itself under repeat-all)', () => {
  it('behaves exactly like repeat-one', () => {
    const s = new GaplessScheduler();
    expect(s.tick(tick({ positionMs: 195_000, nextTrackId: 'a' })).preload).toBeNull();
  });

  it('with repeat-off there is no N+1 at all — nothing fires', () => {
    const s = new GaplessScheduler();
    expect(s.tick(tick({ positionMs: 195_000, nextTrackId: null })).preload).toBeNull();
  });
});

describe('edge case: shuffle mid-preload (the preloaded track is no longer next)', () => {
  it('invalidates the stale preparation and resolves the new next', () => {
    const s = new GaplessScheduler();
    // T-15s crossed: prepare b
    expect(s.tick(tick({ positionMs: 190_000 })).preload).toBe('b');
    // the user toggles shuffle: the machine's next is now c
    const ops = s.tick(tick({ positionMs: 188_000, nextTrackId: 'c' }));
    expect(ops.invalidated).toBe('b');
    expect(ops.preload).toBe('c');
    // and the following tick is quiet again
    const ops2 = s.tick(tick({ positionMs: 186_000, nextTrackId: 'c' }));
    expect(ops2.preload).toBeNull();
    expect(ops2.invalidated).toBeNull();
  });

  it('a track change consumes the preparation silently (no false invalidation)', () => {
    const s = new GaplessScheduler();
    expect(s.tick(tick({ positionMs: 190_000 })).preload).toBe('b');
    // the boundary happened: b is now current, c is next
    const ops = s.tick(tick({ currentTrackId: 'b', nextTrackId: 'c', positionMs: 1_000 }));
    expect(ops.invalidated).toBeNull();
    expect(ops.preload).toBeNull(); // far from the tail
  });
});

describe('the crossfade window', () => {
  it('arms inside the window with a prepared target', () => {
    const s = new GaplessScheduler({ preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 3_000 });
    // preload first
    s.tick(tick({ positionMs: 190_000 }));
    // inside the 3s window
    const ops = s.tick(tick({ positionMs: 198_000 }));
    expect(ops.crossfadeArmed).toBe(true);
    expect(ops.crossfadeTarget).toBe('b');
  });

  it('does not arm outside the window', () => {
    const s = new GaplessScheduler({ preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 3_000 });
    s.tick(tick({ positionMs: 190_000 }));
    const ops = s.tick(tick({ positionMs: 196_000 })); // 4s remain
    expect(ops.crossfadeArmed).toBe(false);
  });

  it('does not arm without a prepared target (preload not yet run)', () => {
    const s = new GaplessScheduler({ preloadLeadMs: 0, crossfadeMs: 3_000 });
    const ops = s.tick(tick({ positionMs: 198_000 }));
    expect(ops.crossfadeArmed).toBe(false);
  });

  it('crossfade 0 = off (never arms)', () => {
    const s = new GaplessScheduler({ preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 0 });
    s.tick(tick({ positionMs: 190_000 }));
    const ops = s.tick(tick({ positionMs: 199_000 }));
    expect(ops.crossfadeArmed).toBe(false);
  });

  it('does not arm at the exact tail (remaining must be > 0)', () => {
    const s = new GaplessScheduler({ preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 3_000 });
    s.tick(tick({ positionMs: 190_000 }));
    const ops = s.tick(tick({ positionMs: 200_000 }));
    expect(ops.crossfadeArmed).toBe(false); // the boundary owns the handoff now
  });
});

describe('failed preloads back off', () => {
  it('a failed resolve does not retry every tick', () => {
    const s = new GaplessScheduler();
    expect(s.tick(tick({ positionMs: 190_000 })).preload).toBe('b');
    s.reportResult('b', false);
    expect(s.tick(tick({ positionMs: 191_000 })).preload).toBeNull(); // backed off
  });

  it('the retry fires after the backoff elapses', () => {
    const s = new GaplessScheduler();
    expect(s.tick(tick({ now: NOW, positionMs: 190_000 })).preload).toBe('b');
    s.reportResult('b', false);
    const ops = s.tick(tick({ now: NOW + PRELOAD_RETRY_MS, positionMs: 195_000 }));
    expect(ops.preload).toBe('b');
  });

  it('a successful resolve reports clean', () => {
    const s = new GaplessScheduler();
    s.tick(tick({ positionMs: 190_000 }));
    s.reportResult('b', true);
    expect(s.currentPrepared).toEqual({ trackId: 'b', preparedAt: expect.any(Number), failed: false });
  });

  it('reportResult for an unknown track is ignored', () => {
    const s = new GaplessScheduler();
    s.reportResult('zzz', false);
    expect(s.currentPrepared).toBeNull();
  });
});

describe('reset', () => {
  it('clears all bookkeeping', () => {
    const s = new GaplessScheduler();
    s.tick(tick({ positionMs: 190_000 }));
    s.reset();
    expect(s.currentPrepared).toBeNull();
    // the next tick re-arms fresh
    expect(s.tick(tick({ positionMs: 191_000 })).preload).toBe('b');
  });
});
