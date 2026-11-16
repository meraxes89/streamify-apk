/**
 * playback/queueMachine.test.ts — the P2-B edge cases, named.
 *
 * Every rule the kickoff called out has a test here; the machine is
 * pure, so these run headless in CI without a browser, an engine, or
 * timers. When a queue surprise shows up in review, it gets reproduced
 * here first.
 */

import { describe, expect, it } from 'vitest';
import {
  appendRow,
  clearQueue,
  currentRow,
  currentTrack,
  emptyQueueState,
  insertNext,
  naturalEnd,
  nextManual,
  playedRowIndices,
  prevManual,
  removeRow,
  reorderUpNext,
  replaceQueue,
  rowOfTrackId,
  setRepeat,
  shuffled,
  startAt,
  toggleShuffle,
  upNextRowIndices,
  type QueueMachineState,
  type QueueRow,
  type RandomUnit,
  type RepeatMode,
} from './queueMachine';

// ---------------------------------------------------------------------------
// fixtures
// ---------------------------------------------------------------------------

function track(id: string, durationMs = 200_000): QueueRow['track'] {
  return {
    id,
    title: `Track ${id}`,
    artist: `Artist ${id}`,
    album: 'Album',
    durationMs,
    thumbnailUrl: null,
    isExplicit: false,
  };
}

function row(id: string, durationMs = 200_000): QueueRow {
  return { track: track(id, durationMs), enqueuedBy: 'test' };
}

/** A queue [a,b,c,d,e] with `start` playing (identity order). */
function fiveTrackQueue(startId = 'a'): QueueMachineState {
  const rows = ['a', 'b', 'c', 'd', 'e'].map((id) => row(id));
  const tr = replaceQueue(emptyQueueState(), rows, startId, false);
  return tr.state;
}

/** A random unit that always returns r ∈ [0,1) — pins Fisher-Yates. */
function fixedRandom(r: number): RandomUnit {
  return () => r;
}

/** Plays through the queue by ids via startAt (helper for history setup). */
function playIds(s: QueueMachineState, ids: string[]): QueueMachineState {
  let st = s;
  for (const id of ids) {
    const r = rowOfTrackId(st, id);
    st = startAt(st, r).state;
  }
  return st;
}

// ---------------------------------------------------------------------------
// the model itself
// ---------------------------------------------------------------------------

describe('queue machine model', () => {
  it('starts empty with nothing selected', () => {
    const s = emptyQueueState();
    expect(s.rows).toHaveLength(0);
    expect(s.cursor).toBeNull();
    expect(currentTrack(s)).toBeNull();
  });

  it('replaceQueue plays the start row and materializes identity order', () => {
    const s = fiveTrackQueue('c');
    expect(s.playOrder).toEqual([0, 1, 2, 3, 4]);
    expect(s.cursor).toBe(2);
    expect(currentTrack(s)?.id).toBe('c');
    expect(playedRowIndices(s)).toEqual([0, 1]);
    expect(upNextRowIndices(s)).toEqual([3, 4]);
  });

  it('refuses duplicate ids on insert (identity stays a key)', () => {
    let s = fiveTrackQueue();
    s = appendRow(s, row('a')).state;
    expect(s.rows).toHaveLength(5);
    expect(appendRow(s, row('a')).changed).toBe(false);
    expect(insertNext(s, row('a')).changed).toBe(false);
    s = appendRow(s, row('f')).state;
    expect(s.rows).toHaveLength(6);
    expect(s.playOrder).toHaveLength(6);
  });

  it('refuses blank ids', () => {
    const s = fiveTrackQueue();
    expect(appendRow(s, row('')).changed).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// shuffle — seeded with a history, not a re-roll
// ---------------------------------------------------------------------------

describe('shuffle semantics', () => {
  it('toggling shuffle ON keeps the current track playing (no play intent)', () => {
    const s = playIds(fiveTrackQueue(), ['a', 'b', 'c']); // history a,b; current c
    const tr = toggleShuffle(s, fixedRandom(0));
    expect(tr.intent).toBeNull(); // order-only — nothing re-plays
    expect(currentTrack(tr.state)?.id).toBe('c');
    expect(tr.state.cursor).toBe(s.cursor);
  });

  it('shuffle keeps the played prefix (history is seeded, not re-rolled)', () => {
    const s = playIds(fiveTrackQueue(), ['a', 'b', 'c']);
    const tr = toggleShuffle(s, fixedRandom(0.99));
    // prefix [a,b,c] intact; the tail is the shuffled rest.
    expect(tr.state.playOrder.slice(0, 3)).toEqual([0, 1, 2]);
    expect(tr.state.shuffle).toBe(true);
    // tail contains exactly the un-played rows
    const tail = tr.state.playOrder.slice(3);
    expect(tail.sort()).toEqual([3, 4]);
  });

  it('next draws from the un-played pool, never a played row (until wrap)', () => {
    const s = toggleShuffle(playIds(fiveTrackQueue(), ['a', 'b', 'c']), fixedRandom(0.99)).state;
    const tr = nextManual(s);
    expect(tr.intent?.type).toBe('play');
    const nextId = s.rows[(tr.intent as { row: number }).row]?.track.id;
    expect(['d', 'e']).toContain(nextId);
    expect(playedRowIndices(tr.state)).toContain(rowOfTrackId(s, 'c'));
  });

  it('prev after a shuffle walks back through actual play order', () => {
    // Play a,b,c under shuffle (order materialized [a,b,c,X,Y]).
    let s = toggleShuffle(fiveTrackQueue(), fixedRandom(0.99)).state;
    s = playIds(s, ['a', 'b', 'c']); // walks forward inside the shuffled order
    // prev from c (position < 3s) → the row ACTUALLY played before c = b
    const tr = prevManual(s, 1_000);
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('b');
    // and again → a (the walk is the play history, not a re-roll)
    const tr2 = prevManual(tr.state, 1_000);
    expect(currentTrack(tr2.state)?.id).toBe('a');
  });

  it('toggling shuffle OFF restores the original order with the current track in place', () => {
    let s = toggleShuffle(playIds(fiveTrackQueue(), ['a', 'b', 'c']), fixedRandom(0.99)).state;
    s = nextManual(s).state; // now somewhere in the shuffled tail
    const currentId = currentTrack(s)?.id;
    const tr = toggleShuffle(s); // OFF
    expect(tr.intent).toBeNull();
    expect(tr.state.playOrder).toEqual([0, 1, 2, 3, 4]); // original order
    expect(currentTrack(tr.state)?.id).toBe(currentId); // current stays put
    expect(tr.state.shuffle).toBe(false);
  });

  it('shuffle with nothing selected shuffles the whole queue', () => {
    let s = emptyQueueState();
    for (const id of ['a', 'b', 'c', 'd']) s = appendRow(s, row(id)).state;
    const tr = toggleShuffle(s, fixedRandom(0.99));
    expect(tr.state.shuffle).toBe(true);
    expect([...tr.state.playOrder].sort()).toEqual([0, 1, 2, 3]);
  });

  it('shuffle + repeat-all at pass end starts a fresh shuffled pass', () => {
    let s = fiveTrackQueue('a');
    s = setRepeat(s, 'all').state;
    s = toggleShuffle(s, fixedRandom(0.99)).state;
    // exhaust the pass: natural-end through every row
    for (let i = 0; i < 4; i += 1) s = naturalEnd(s, fixedRandom(0.99)).state;
    const lastId = currentTrack(s)?.id;
    const tr = naturalEnd(s, fixedRandom(0.99)); // pass boundary
    expect(tr.intent?.type).toBe('play');
    expect(tr.state.cursor).toBe(0); // fresh pass: first row of the new roll
    expect(currentTrack(tr.state)?.id).not.toBe(lastId); // r=0.99 → identity, 'e'→'a'
    expect(tr.state.playOrder).toHaveLength(5);
  });
});

// ---------------------------------------------------------------------------
// repeat
// ---------------------------------------------------------------------------

describe('repeat semantics', () => {
  it('repeat-one beats repeat-all: natural end restarts the SAME row', () => {
    const s = setRepeat(fiveTrackQueue('c'), 'one').state;
    const tr = naturalEnd(s);
    expect(tr.intent).toEqual({ type: 'restart' });
    expect(currentTrack(tr.state)?.id).toBe('c');
    expect(tr.state.cursor).toBe(s.cursor);
  });

  it('manual next during repeat-one ADVANCES (that is what manual means)', () => {
    const s = setRepeat(fiveTrackQueue('c'), 'one').state;
    const tr = nextManual(s);
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('d');
  });

  it('repeat-all wraps at the natural end of the queue', () => {
    const s = setRepeat(fiveTrackQueue('e'), 'all').state;
    const tr = naturalEnd(s);
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('a');
    expect(tr.state.cursor).toBe(0);
  });

  it('repeat-off natural end at the last row parks the machine (finished)', () => {
    const s = fiveTrackQueue('e');
    const tr = naturalEnd(s);
    expect(tr.intent).toEqual({ type: 'stop' });
    expect(tr.state.finished).toBe(true);
    // an explicit play clears the parked state
    const again = nextManual(tr.state);
    expect(again.state.finished).toBe(false);
    expect(again.intent?.type).toBe('play');
  });

  it('natural end mid-queue advances regardless of mode', () => {
    for (const mode of ['off', 'all', 'one'] as RepeatMode[]) {
      if (mode === 'one') continue; // covered above
      const s = setRepeat(fiveTrackQueue('b'), mode).state;
      const tr = naturalEnd(s);
      expect(currentTrack(tr.state)?.id).toBe('c');
    }
  });

  it('setRepeat is order-only (never interrupts playback)', () => {
    const s = fiveTrackQueue('c');
    expect(setRepeat(s, 'all').intent).toBeNull();
    expect(setRepeat(s, 'off').intent).toBeNull();
    expect(setRepeat(s, 'off').changed).toBe(false); // same mode = no-op
  });
});

// ---------------------------------------------------------------------------
// prev / next
// ---------------------------------------------------------------------------

describe('prev semantics', () => {
  it('prev deeper than 3s restarts the current track', () => {
    const s = fiveTrackQueue('c');
    const tr = prevManual(s, 5_000);
    expect(tr.intent).toEqual({ type: 'restart' });
    expect(currentTrack(tr.state)?.id).toBe('c');
  });

  it('prev near the track top walks back one row', () => {
    const s = fiveTrackQueue('c');
    const tr = prevManual(s, 1_000);
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('b');
  });

  it('prev at the top of the queue restarts the current track', () => {
    const s = fiveTrackQueue('a');
    const tr = prevManual(s, 1_000);
    expect(tr.intent).toEqual({ type: 'restart' });
    expect(currentTrack(tr.state)?.id).toBe('a');
  });

  it('prev from an unstarted queue is an acknowledged no-op', () => {
    let s = emptyQueueState();
    s = appendRow(s, row('a')).state;
    const tr = prevManual(s, 0);
    expect(tr.changed).toBe(false);
    expect(tr.intent).toBeNull();
  });
});

describe('next semantics', () => {
  it('manual next at the last row wraps to the first (engine parity)', () => {
    const s = fiveTrackQueue('e');
    const tr = nextManual(s);
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('a');
  });

  it('next from an unstarted queue starts at the top', () => {
    let s = emptyQueueState();
    for (const id of ['a', 'b']) s = appendRow(s, row(id)).state;
    const tr = nextManual(s);
    expect(currentTrack(tr.state)?.id).toBe('a');
  });

  it('next on an empty queue is an acknowledged no-op', () => {
    const tr = nextManual(emptyQueueState());
    expect(tr.changed).toBe(false);
    expect(tr.intent).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// the one-track queue (the edge case with a name)
// ---------------------------------------------------------------------------

describe('one-track queue', () => {
  function single(): QueueMachineState {
    return replaceQueue(emptyQueueState(), [row('only')], 'only', false).state;
  }

  it('manual next wraps to itself', () => {
    const tr = nextManual(single());
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('only');
  });

  it('repeat-one natural end restarts itself', () => {
    const s = setRepeat(single(), 'one').state;
    expect(naturalEnd(s).intent).toEqual({ type: 'restart' });
  });

  it('repeat-all natural end wraps to itself', () => {
    const s = setRepeat(single(), 'all').state;
    const tr = naturalEnd(s);
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('only');
  });

  it('repeat-off natural end stops (N+1 does not exist)', () => {
    const tr = naturalEnd(single());
    expect(tr.intent).toEqual({ type: 'stop' });
    expect(tr.state.finished).toBe(true);
  });

  it('prev restarts the track', () => {
    const tr = prevManual(single(), 1_000);
    expect(tr.intent).toEqual({ type: 'restart' });
  });
});

// ---------------------------------------------------------------------------
// mutations
// ---------------------------------------------------------------------------

describe('queue mutations', () => {
  it('insertNext lands right after the cursor and never interrupts', () => {
    const s = fiveTrackQueue('c');
    const tr = insertNext(s, row('x'));
    expect(tr.intent).toBeNull();
    expect(upNextRowIndices(tr.state)[0]).toBe(rowOfTrackId(tr.state, 'x'));
    // and the NEXT advance plays it
    const adv = nextManual(tr.state);
    expect(currentTrack(adv.state)?.id).toBe('x');
  });

  it('insertNext on an unstarted queue becomes the head', () => {
    let s = emptyQueueState();
    for (const id of ['a', 'b']) s = appendRow(s, row(id)).state;
    const tr = insertNext(s, row('x'));
    expect(tr.state.playOrder[0]).toBe(rowOfTrackId(tr.state, 'x'));
  });

  it('removeRow remaps indices and keeps the cursor on the following row when the current is removed', () => {
    const s = fiveTrackQueue('c');
    const tr = removeRow(s, 'c');
    expect(tr.intent).toBeNull();
    expect(currentTrack(tr.state)?.id).toBe('d'); // the row that followed
    expect(tr.state.rows).toHaveLength(4);
    expect([...tr.state.playOrder].sort()).toEqual([0, 1, 2, 3]); // still a permutation
  });

  it('removeRow before the cursor shifts the cursor back', () => {
    const s = fiveTrackQueue('c');
    const tr = removeRow(s, 'a');
    expect(currentTrack(tr.state)?.id).toBe('c');
    expect(playedRowIndices(tr.state)).toEqual([rowOfTrackId(tr.state, 'b')]);
  });

  it('removeRow up-next does not disturb playback position', () => {
    const s = fiveTrackQueue('c');
    const tr = removeRow(s, 'e');
    expect(currentTrack(tr.state)?.id).toBe('c');
    expect(tr.state.cursor).toBe(s.cursor);
  });

  it('reorderUpNext moves within the up-next slice only', () => {
    const s = fiveTrackQueue('b'); // up next = d(3), e(4) at playOrder pos 3,4... wait c,d,e
    // playOrder [a,b,c,d,e], cursor 1 → up next positions 2..4 = [c,d,e]
    const tr = reorderUpNext(s, 4, 2); // move e before c
    expect(tr.changed).toBe(true);
    expect(tr.state.playOrder).toEqual([0, 1, 4, 2, 3]);
    expect(currentTrack(tr.state)?.id).toBe('b'); // untouched
  });

  it('reorderUpNext refuses history/now-playing positions', () => {
    const s = fiveTrackQueue('c');
    expect(reorderUpNext(s, 2, 0).changed).toBe(false); // into history
    expect(reorderUpNext(s, 0, 4).changed).toBe(false); // out of history
    expect(reorderUpNext(s, 2, 2).changed).toBe(false); // same slot
  });

  it('reorderUpNext under shuffle reorders the materialized tail', () => {
    let s = toggleShuffle(fiveTrackQueue('a'), fixedRandom(0.99)).state;
    const tailBefore = [...s.playOrder.slice(1)];
    const tr = reorderUpNext(s, 1, s.playOrder.length - 1);
    expect(tr.changed).toBe(true);
    expect(tr.state.playOrder[tr.state.playOrder.length - 1]).toBe(tailBefore[0]);
  });

  it('clearQueue keeps mode flags but empties everything else', () => {
    let s = setRepeat(fiveTrackQueue('c'), 'all').state;
    s = toggleShuffle(s, fixedRandom(0.5)).state;
    const tr = clearQueue(s);
    expect(tr.state.rows).toHaveLength(0);
    expect(tr.state.cursor).toBeNull();
    expect(tr.state.repeat).toBe('all');
    expect(tr.state.shuffle).toBe(true);
    expect(clearQueue(tr.state).changed).toBe(false);
  });

  it('replaceQueue with shuffle puts the start row first', () => {
    const rows = ['a', 'b', 'c', 'd'].map((id) => row(id));
    const tr = replaceQueue(emptyQueueState(), rows, 'c', true, fixedRandom(0.99));
    expect(tr.state.playOrder[0]).toBe(2); // row c
    expect(tr.state.shuffle).toBe(true);
    expect(tr.state.cursor).toBe(0);
    expect(currentTrack(tr.state)?.id).toBe('c');
  });

  it('replaceQueue dedupes by id', () => {
    const rows = ['a', 'b', 'a', 'c', 'b'].map((id) => row(id));
    const tr = replaceQueue(emptyQueueState(), rows, 'a', false);
    expect(tr.state.rows).toHaveLength(3);
  });

  it('startAt on an unknown row is an acknowledged no-op', () => {
    const s = fiveTrackQueue('a');
    expect(startAt(s, 99).changed).toBe(false);
  });

  it('a deliberate replay (startAt on a played row) re-arms the machine', () => {
    const s = playIds(fiveTrackQueue(), ['a', 'b', 'c']);
    const tr = startAt(s, rowOfTrackId(s, 'a'));
    expect(tr.intent?.type).toBe('play');
    expect(currentTrack(tr.state)?.id).toBe('a');
    expect(tr.state.finished).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// invariants (property-ish checks every transition must keep)
// ---------------------------------------------------------------------------

describe('invariants', () => {
  it('playOrder is always a permutation of row indices', () => {
    let s = fiveTrackQueue('b');
    s = toggleShuffle(s, fixedRandom(0.3)).state;
    s = nextManual(s).state;
    s = insertNext(s, row('x')).state;
    s = removeRow(s, 'b').state;
    s = reorderUpNext(s, 1, 3).state;
    s = naturalEnd(s, fixedRandom(0.7)).state;
    expect([...s.playOrder].sort((a, b) => a - b)).toEqual(s.rows.map((_, i) => i));
    expect(new Set(s.playOrder).size).toBe(s.playOrder.length);
  });

  it('cursor always points inside playOrder (or is null)', () => {
    let s = fiveTrackQueue('b');
    s = removeRow(s, 'b').state;
    expect(s.cursor === null || (s.cursor >= 0 && s.cursor < s.playOrder.length)).toBe(true);
    let s2 = fiveTrackQueue('a');
    for (const id of ['a', 'b', 'c', 'd', 'e']) s2 = removeRow(s2, id).state;
    expect(s2.cursor).toBeNull();
    expect(s2.rows).toHaveLength(0);
  });

  it('transitions never mutate their input (immutability discipline)', () => {
    const s = fiveTrackQueue('c');
    const before = JSON.stringify({ rows: s.rows, playOrder: s.playOrder, cursor: s.cursor });
    toggleShuffle(s, fixedRandom(0.5));
    nextManual(s);
    prevManual(s, 10_000);
    naturalEnd(s, fixedRandom(0.5));
    setRepeat(s, 'all');
    removeRow(s, 'a');
    reorderUpNext(s, 3, 4);
    expect(JSON.stringify({ rows: s.rows, playOrder: s.playOrder, cursor: s.cursor })).toBe(before);
  });

  it('shuffled() is a permutation and never mutates its input', () => {
    const input = [1, 2, 3, 4, 5, 6, 7, 8];
    const out = shuffled(input, Math.random);
    expect([...out].sort((a, b) => a - b)).toEqual(input);
    expect(input).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  it('currentRow is consistent with currentTrack', () => {
    const s = fiveTrackQueue('d');
    const r = currentRow(s);
    expect(r).not.toBeNull();
    expect(s.rows[r as number]?.track.id).toBe(currentTrack(s)?.id);
  });
});
