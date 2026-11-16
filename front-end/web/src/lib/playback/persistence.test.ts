/**
 * playback/persistence.test.ts — the store decision, by size, and the
 * corruption policy. Lanes are injected fakes: no browser, no timers.
 */

import { describe, expect, it } from 'vitest';
import {
  createPersistence,
  deserializeQueue,
  LOCAL_MAX_CHARS,
  serializeQueue,
  type AsyncKV,
  type PersistedQueue,
  type SyncKV,
} from './persistence';
import type { TrackRow } from '../wasmEngine';

function track(id: string): TrackRow {
  return {
    id,
    title: `Track ${id}`,
    artist: `Artist ${id}`,
    album: 'Album',
    durationMs: 200_000,
    thumbnailUrl: null,
    isExplicit: false,
  };
}

function payload(overrides: Partial<PersistedQueue> = {}): PersistedQueue {
  return {
    v: 2,
    savedAt: 1_700_000_000_000,
    rows: [
      { track: track('a'), enqueuedBy: 'web' },
      { track: track('b'), enqueuedBy: 'web' },
    ],
    playOrder: [0, 1],
    cursor: 1,
    repeat: 'all',
    shuffle: true,
    positionMs: 42_500,
    session: { trackId: 'b', startedAt: 1_700_000_000_000, playedMs: 10_000, scrobbled: false },
    ...overrides,
  };
}

function memoryLocal(): SyncKV & { dump(): string | null } {
  let value: string | null = null;
  return {
    get: () => value,
    set: (v: string) => {
      value = v;
    },
    clear: () => {
      value = null;
    },
    dump: () => value,
  };
}

function memoryRemote(): AsyncKV & { dump(): string | null; failNext: boolean } {
  let value: string | null = null;
  let fail = false;
  return {
    get: async () => {
      if (fail) throw new Error('remote down');
      return value;
    },
    set: async (v: string) => {
      if (fail) throw new Error('remote down');
      value = v;
    },
    clear: async () => {
      value = null;
    },
    dump: () => value,
    get failNext() {
      return fail;
    },
    set failNext(v: boolean) {
      fail = v;
    },
  };
}

describe('round-trip', () => {
  it('serializes and restores the full state, scrobble session included', () => {
    const raw = serializeQueue(payload());
    const back = deserializeQueue(raw);
    expect(back).not.toBeNull();
    expect(back?.rows).toHaveLength(2);
    expect(back?.cursor).toBe(1);
    expect(back?.repeat).toBe('all');
    expect(back?.shuffle).toBe(true);
    expect(back?.positionMs).toBe(42_500);
    expect(back?.session).toEqual({
      trackId: 'b',
      startedAt: 1_700_000_000_000,
      playedMs: 10_000,
      scrobbled: false,
    });
  });

  it('never carries a URL field, by construction of the serializer', () => {
    const raw = serializeQueue(payload());
    expect(raw).not.toMatch(/streamUrl|resolvedStream|googlevideo/i);
    // and the explicit field list:
    const parsed = JSON.parse(raw) as { rows: Array<{ track: Record<string, unknown> }> };
    expect(Object.keys(parsed.rows[0]?.track ?? {}).sort()).toEqual([
      'album',
      'artist',
      'durationMs',
      'id',
      'isExplicit',
      'thumbnailUrl',
      'title',
    ]);
  });
});

describe('store routing by size', () => {
  it('small queues ride localStorage; the remote lane stays empty', async () => {
    const local = memoryLocal();
    const remote = memoryRemote();
    const p = createPersistence({ local, remote });
    await p.save(payload());
    expect(local.dump()).not.toBeNull();
    expect(remote.dump()).toBeNull();
  });

  it('oversized queues route to the remote lane and clear the small one', async () => {
    const local = memoryLocal();
    const remote = memoryRemote();
    const p = createPersistence({ local, remote, localMaxChars: 10 }); // tiny ceiling
    await p.save(payload());
    expect(remote.dump()).not.toBeNull();
    expect(local.dump()).toBeNull();
    // and it loads back from the remote lane
    const back = await p.load();
    expect(back?.rows).toHaveLength(2);
  });

  it('a quota-exceeded small lane falls through to the remote lane', async () => {
    const local: SyncKV = {
      get: () => null,
      set: () => {
        throw new DOMException('quota exceeded', 'QuotaExceededError');
      },
      clear: () => undefined,
    };
    const remote = memoryRemote();
    const p = createPersistence({ local, remote });
    await p.save(payload());
    expect(remote.dump()).not.toBeNull();
  });

  it('no remote lane + overflow = honest degradation (nothing saved, nothing thrown)', async () => {
    const local: SyncKV = {
      get: () => null,
      set: () => {
        throw new DOMException('quota exceeded', 'QuotaExceededError');
      },
      clear: () => undefined,
    };
    const p = createPersistence({ local, remote: null });
    await expect(p.save(payload())).resolves.toBeUndefined();
    expect(await p.load()).toBeNull();
  });

  it('clear wipes both lanes', async () => {
    const local = memoryLocal();
    const remote = memoryRemote();
    const p = createPersistence({ local, remote, localMaxChars: 10 });
    await p.save(payload()); // lands in remote
    await p.save(payload({ savedAt: 2 })); // still remote
    await p.clear();
    expect(local.dump()).toBeNull();
    expect(remote.dump()).toBeNull();
    expect(await p.load()).toBeNull();
  });

  it('the default ceiling is the documented one', () => {
    expect(LOCAL_MAX_CHARS).toBe(750_000);
  });
});

describe('corruption policy (discard, never crash)', () => {
  const cases: Array<[string, string]> = [
    ['not json at all', '{{{'],
    ['wrong version', JSON.stringify({ ...payload(), v: 1 })],
    ['empty rows', JSON.stringify({ ...payload(), rows: [] })],
    ['blank track id', JSON.stringify({ ...payload(), rows: [{ ...payload().rows[0], track: track('') }] })],
    [
      'duplicate ids',
      JSON.stringify({ ...payload(), rows: [...payload().rows, { ...payload().rows[0] }] }),
    ],
    ['order not a permutation', JSON.stringify({ ...payload(), playOrder: [0, 0] })],
    ['order out of range', JSON.stringify({ ...payload(), playOrder: [0, 5] })],
    ['order length drift', JSON.stringify({ ...payload(), playOrder: [0] })],
    ['cursor out of range', JSON.stringify({ ...payload(), cursor: 2 })],
    ['cursor negative', JSON.stringify({ ...payload(), cursor: -1 })],
    ['bad repeat mode', JSON.stringify({ ...payload(), repeat: 'sometimes' })],
    ['shuffle not boolean', JSON.stringify({ ...payload(), shuffle: 'yes' })],
    ['negative position', JSON.stringify({ ...payload(), positionMs: -1 })],
    [
      'session references unknown track',
      JSON.stringify({ ...payload(), session: { trackId: 'zzz', startedAt: 1, playedMs: 1, scrobbled: false } }),
    ],
    [
      'session playedMs negative',
      JSON.stringify({
        ...payload(),
        session: { trackId: 'b', startedAt: 1, playedMs: -5, scrobbled: false },
      }),
    ],
  ];

  for (const [name, raw] of cases) {
    it(`discards: ${name}`, () => {
      expect(deserializeQueue(raw)).toBeNull();
    });
  }

  it('a corrupt small-lane payload is cleared and the remote lane still loads', async () => {
    const local = memoryLocal();
    const remote = memoryRemote();
    const p = createPersistence({ local, remote });
    await p.save(payload({ savedAt: 5 })); // small lane
    local.set('not json at all'); // corrupt it in place
    const back = await p.load();
    expect(back).toBeNull(); // remote lane is empty — honest absence
    expect(local.dump()).toBeNull(); // corrupt copy cleared
  });

  it('enqueuedBy drift degrades to "web" instead of discarding', async () => {
    const raw = serializeQueue(payload());
    const tweaked = JSON.parse(raw) as PersistedQueue;
    (tweaked.rows[0] as unknown as { enqueuedBy: number }).enqueuedBy = 42;
    const back = deserializeQueue(JSON.stringify(tweaked));
    expect(back?.rows[0]?.enqueuedBy).toBe('web');
  });
});
