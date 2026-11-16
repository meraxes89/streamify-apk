/**
 * lib/playback/queueMachine.ts — P2-B: the queue state machine (PURE).
 *
 * "play / next / prev / shuffle / repeat / seek are not six handlers,
 * they're transitions on one queue object." This module IS that object:
 * a framework-free, side-effect-free state machine over the queue's
 * SEMANTICS — order, cursor, shuffle, repeat — with every transition
 * returning the next state plus an INTENT describing what the transport
 * (the wasm engine port) should do. The machine never touches the
 * engine, the DOM, storage, or timers; the controller in queueStore.ts
 * executes intents. That separation is what makes the edge cases below
 * testable without a browser.
 *
 * THE MODEL (one invariant, everything follows):
 *
 *   rows       — the queue's rows in INSERTION order (identity = track
 *                id; duplicates are refused at insert time so ids stay
 *                a key — the engine's by-id play lookup, persistence,
 *                and the scrobble session all rely on it).
 *   playOrder  — a permutation of row indices: THE effective order.
 *                Shuffle-off: the identity permutation. Shuffle-on:
 *                [actual play history …, current, …shuffled rest] —
 *                eager Fisher-Yates over the un-played rows, so "up
 *                next" is a real, reorderable list (the Spotify shape)
 *                and `prev` walks back through what ACTUALLY played,
 *                because the playOrder prefix IS the play history.
 *   cursor     — position IN playOrder (null = nothing selected).
 *   repeat     — 'off' | 'all' | 'one'.
 *   finished   — natural end with repeat off at the last row: the
 *                machine is parked-done until an explicit play.
 *
 * RULES THAT MAKE THE EDGE CASES NON-SURPRISING (each has a test):
 *
 *   • Toggling shuffle ON keeps the current track playing (no play
 *     intent) and keeps the played prefix — history is seeded, never
 *     re-rolled.
 *   • Toggling shuffle OFF restores the original order with the current
 *     track in place (cursor = its base position).
 *   • `prev` walks back through play order; at the top (or deeper than
 *     3 s into the track) it restarts the current track — the standard
 *     "previous" that never surprises.
 *   • Repeat-one beats repeat-all: a natural end under 'one' restarts
 *     the SAME row.
 *   • Manual `next` during repeat-one ADVANCES — "manual" means the
 *     user asked for the next row, not another loop.
 *   • Manual next at the last row wraps (the engine's own manual
 *     advance wraps — rem_euclid — and so does every mainstream
 *     player); only NATURAL end honors repeat-off by stopping.
 *   • Shuffle + repeat-all at pass end re-shuffles into a fresh pass
 *     (the played prefix resets — a pass boundary, documented).
 */

import type { TrackRow } from '../wasmEngine';

export type RepeatMode = 'off' | 'all' | 'one';

/** One queue row: the engine TrackRow + who enqueued it (diagnostics). */
export interface QueueRow {
  track: TrackRow;
  enqueuedBy: string;
}

export interface QueueMachineState {
  /** Insertion order; row index = position here. Never reordered. */
  rows: QueueRow[];
  /** Permutation of row indices — the effective play order. */
  playOrder: number[];
  /** Position in playOrder; null = nothing selected. */
  cursor: number | null;
  repeat: RepeatMode;
  shuffle: boolean;
  /** Natural end + repeat off: parked-done until an explicit play. */
  finished: boolean;
}

/**
 * What the controller should do with the transport after a transition.
 * 'play'     — make this row now-playing (fresh load; the controller
 *              decides resolve-vs-prepared per the gapless scheduler).
 * 'restart'  — replay the CURRENT row from 0 (repeat-one loop, the
 *              prev-at-top rule). Executed as a same-row play, not a
 *              bare seek: an ended element must reload, and a playing
 *              element must reload too (an in-place seek to 0 leaves
 *              an ended element paused — the desync trap).
 * 'stop'     — queue exhausted under repeat-off: release the pipeline.
 */
export type MachineIntent =
  | { type: 'play'; row: number }
  | { type: 'restart' }
  | { type: 'stop' };

export interface Transition {
  state: QueueMachineState;
  intent: MachineIntent | null;
  /** False = the transition was an acknowledged no-op. */
  changed: boolean;
}

// ---------------------------------------------------------------------------
// Construction + pure helpers
// ---------------------------------------------------------------------------

export function emptyQueueState(): QueueMachineState {
  return { rows: [], playOrder: [], cursor: null, repeat: 'off', shuffle: false, finished: false };
}

/** The row index at the cursor (null when nothing is selected). */
export function currentRow(s: QueueMachineState): number | null {
  if (s.cursor === null) return null;
  return s.playOrder[s.cursor] ?? null;
}

/** The TrackRow at the cursor (the now-playing / staged row). */
export function currentTrack(s: QueueMachineState): TrackRow | null {
  const row = currentRow(s);
  return row === null ? null : (s.rows[row]?.track ?? null);
}

/** Row indices strictly before the cursor, in play order (= history). */
export function playedRowIndices(s: QueueMachineState): number[] {
  if (s.cursor === null || s.cursor <= 0) return [];
  return s.playOrder.slice(0, s.cursor);
}

/** Row indices strictly after the cursor, in play order (= up next). */
export function upNextRowIndices(s: QueueMachineState): number[] {
  if (s.cursor === null) return s.playOrder.slice();
  return s.playOrder.slice(s.cursor + 1);
}

/** Position of a row in playOrder, or -1. */
export function positionOfRow(s: QueueMachineState, row: number): number {
  return s.playOrder.indexOf(row);
}

/** Row index for a track id, or -1 (ids are unique by construction). */
export function rowOfTrackId(s: QueueMachineState, trackId: string): number {
  return s.rows.findIndex((r) => r.track.id === trackId);
}

/**
 * Deterministic shuffle injection: the machine calls this indirection so
 * tests can pin the Fisher-Yates outcome. Production passes
 * Math.random-backed; the Fisher-Yates itself lives here so every
 * consumer gets the same algorithm.
 */
export type RandomUnit = () => number;

/** Fisher-Yates over a copy (never mutates the input). */
export function shuffled<T>(list: readonly T[], random: RandomUnit): T[] {
  const out = [...list];
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(random() * (i + 1));
    const tmp = out[i] as T;
    out[i] = out[j] as T;
    out[j] = tmp;
  }
  return out;
}

function t(state: QueueMachineState, intent: MachineIntent | null, changed: boolean): Transition {
  return { state, intent, changed };
}

function clone(s: QueueMachineState): QueueMachineState {
  return {
    rows: s.rows.map((r) => ({ track: { ...r.track }, enqueuedBy: r.enqueuedBy })),
    playOrder: [...s.playOrder],
    cursor: s.cursor,
    repeat: s.repeat,
    shuffle: s.shuffle,
    finished: s.finished,
  };
}

// ---------------------------------------------------------------------------
// The transitions
// ---------------------------------------------------------------------------

/**
 * Play a specific row (tap on a row anywhere in the queue). Jumps the
 * cursor; rows before it become history, rows after it up next. A jump
 * to an already-played row is a DELIBERATE REPLAY — the controller
 * opens a fresh scrobble session for it.
 */
export function startAt(s: QueueMachineState, row: number): Transition {
  const pos = positionOfRow(s, row);
  if (pos < 0) return t(s, null, false);
  const next = clone(s);
  next.cursor = pos;
  next.finished = false;
  return t(next, { type: 'play', row }, true);
}

/**
 * Manual next. Repeat-one does NOT loop here (manual means advance);
 * the last row wraps to the first (engine parity: rem_euclid).
 */
export function nextManual(s: QueueMachineState): Transition {
  if (s.rows.length === 0) return t(s, null, false);
  if (s.cursor === null) return startAt(s, s.playOrder[0] as number);
  const target = s.cursor + 1;
  if (target < s.playOrder.length) {
    return startAt(s, s.playOrder[target] as number);
  }
  // At the last row: wrap (manual next never stops — only natural end
  // honors repeat-off).
  return startAt(s, s.playOrder[0] as number);
}

/**
 * Manual previous. Deeper than PREV_RESTART_MS into the track it
 * restarts the current row (the universal player rule); at the top of
 * the queue it also restarts; otherwise it walks back through the play
 * order — which after a shuffle is the ACTUAL play history, because
 * the playOrder prefix is exactly that.
 */
export const PREV_RESTART_MS = 3_000;

export function prevManual(s: QueueMachineState, positionMs: number): Transition {
  if (s.rows.length === 0) return t(s, null, false);
  const row = currentRow(s);
  if (row === null) return t(s, null, false);
  if (positionMs > PREV_RESTART_MS || s.cursor === 0) {
    const next = clone(s);
    next.finished = false;
    return t(next, { type: 'restart' }, true);
  }
  return startAt(s, s.playOrder[(s.cursor as number) - 1] as number);
}

/**
 * Natural end-of-track. Repeat-one restarts the same row. Otherwise the
 * cursor advances; at the end of the order repeat-all wraps (re-shuffling
 * a fresh pass under shuffle), and repeat-off parks the machine
 * (`finished`) with a stop intent.
 */
export function naturalEnd(s: QueueMachineState, random: RandomUnit = Math.random): Transition {
  if (s.cursor === null) return t(s, { type: 'stop' }, false);
  if (s.repeat === 'one') {
    const next = clone(s);
    next.finished = false;
    return t(next, { type: 'restart' }, true);
  }
  const target = s.cursor + 1;
  if (target < s.playOrder.length) {
    return startAt(s, s.playOrder[target] as number);
  }
  if (s.repeat === 'all') {
    if (s.shuffle && s.rows.length > 1) {
      // Fresh pass: a new Fisher-Yates over every row, first one plays.
      // The played prefix resets — a pass boundary (prev will not cross
      // it; documented). The just-finished row may land anywhere,
      // including first — a fresh pass is a fresh roll.
      const next = clone(s);
      next.playOrder = shuffled(
        s.rows.map((_, i) => i),
        random,
      );
      next.cursor = 0;
      next.finished = false;
      return t(next, { type: 'play', row: next.playOrder[0] as number }, true);
    }
    return startAt(s, s.playOrder[0] as number);
  }
  // repeat 'off', last row: parked-done.
  const next = clone(s);
  next.finished = true;
  return t(next, { type: 'stop' }, true);
}

/**
 * Toggle shuffle. ON: the played prefix and the current row stay exactly
 * where they are (the current track KEEPS PLAYING — no play intent); the
 * un-played rest is Fisher-Yates'd into the up-next tail. OFF: the
 * original insertion order is restored with the current row in place.
 * Both directions are order-only: intent stays null.
 */
export function toggleShuffle(s: QueueMachineState, random: RandomUnit = Math.random): Transition {
  const next = clone(s);
  if (!s.shuffle) {
    next.shuffle = true;
    if (s.cursor === null) {
      next.playOrder = shuffled(
        s.rows.map((_, i) => i),
        random,
      );
    } else {
      const prefix = s.playOrder.slice(0, s.cursor + 1);
      const inPrefix = new Set(prefix);
      const rest = s.rows.map((_, i) => i).filter((i) => !inPrefix.has(i));
      next.playOrder = [...prefix, ...shuffled(rest, random)];
    }
  } else {
    next.shuffle = false;
    const row = currentRow(s);
    next.playOrder = s.rows.map((_, i) => i);
    next.cursor = row === null ? null : next.playOrder.indexOf(row);
  }
  return t(next, null, true);
}

/** Set the repeat mode. Order-only: no intent, playback untouched. */
export function setRepeat(s: QueueMachineState, mode: RepeatMode): Transition {
  if (s.repeat === mode) return t(s, null, false);
  const next = clone(s);
  next.repeat = mode;
  if (mode !== 'off') next.finished = false;
  return t(next, null, true);
}

// ---------------------------------------------------------------------------
// Mutations (queue contents)
// ---------------------------------------------------------------------------

/**
 * Append to the end of the queue (Add to Queue). Refuses duplicate ids
 * (identity stays a key — engine by-id play, persistence, scrobble).
 */
export function appendRow(s: QueueMachineState, row: QueueRow): Transition {
  if (row.track.id === '' || rowOfTrackId(s, row.track.id) >= 0) return t(s, null, false);
  const next = clone(s);
  const idx = next.rows.length;
  next.rows.push(row);
  next.playOrder.push(idx);
  return t(next, null, true);
}

/**
 * Insert as the next row (Play Next): spliced at cursor+1 in playOrder
 * (or the head when nothing is selected). No play intent — the current
 * track keeps playing; the row is simply next.
 */
export function insertNext(s: QueueMachineState, row: QueueRow): Transition {
  if (row.track.id === '' || rowOfTrackId(s, row.track.id) >= 0) return t(s, null, false);
  const next = clone(s);
  const idx = next.rows.length;
  next.rows.push(row);
  const at = next.cursor === null ? 0 : next.cursor + 1;
  next.playOrder.splice(at, 0, idx);
  if (next.cursor !== null && at <= next.cursor) next.cursor += 1;
  return t(next, null, true);
}

/**
 * Remove a row (by track id). Indices remap; the cursor follows the row
 * that now occupies its position. Removing the CURRENT row while it
 * plays does not interrupt playback — the removal lands in the order,
 * and the next boundary advances from whatever follows.
 */
export function removeRow(s: QueueMachineState, trackId: string): Transition {
  const row = rowOfTrackId(s, trackId);
  if (row < 0) return t(s, null, false);
  const next = clone(s);
  const pos = next.playOrder.indexOf(row);
  next.rows.splice(row, 1);
  next.playOrder = next.playOrder
    .filter((i) => i !== row)
    .map((i) => (i > row ? i - 1 : i));
  if (next.cursor !== null) {
    if (pos === next.cursor) {
      // The removed row was current: the cursor stays on the position,
      // now occupied by the row that followed (or clamps to the tail).
      next.cursor = Math.min(next.cursor, next.playOrder.length - 1);
      if (next.playOrder.length === 0) next.cursor = null;
    } else if (pos < next.cursor) {
      next.cursor -= 1;
    }
  }
  return t(next, null, true);
}

/**
 * Drag-reorder within the up-next region. `from`/`to` are positions in
 * playOrder, both strictly after the cursor (history + now-playing are
 * never reorderable — the Kotlin QueueScreen scope).
 */
export function reorderUpNext(s: QueueMachineState, from: number, to: number): Transition {
  const lo = s.cursor === null ? 0 : s.cursor + 1;
  if (from === to) return t(s, null, false);
  if (from < lo || to < lo) return t(s, null, false);
  if (from >= s.playOrder.length || to >= s.playOrder.length) return t(s, null, false);
  const next = clone(s);
  const [moved] = next.playOrder.splice(from, 1);
  if (moved === undefined) return t(s, null, false);
  next.playOrder.splice(to, 0, moved);
  return t(next, null, true);
}

/**
 * Replace the whole queue and play `startTrackId` (the collection play:
 * Search/Album/Artist/radio). Shuffle pre-materializes the order with
 * the start row first.
 */
export function replaceQueue(
  s: QueueMachineState,
  rows: QueueRow[],
  startTrackId: string,
  shuffle: boolean,
  random: RandomUnit = Math.random,
): Transition {
  // Dedupe by id, keep first occurrence, refuse blank ids.
  const seen = new Set<string>();
  const clean = rows.filter((r) => {
    if (r.track.id === '' || seen.has(r.track.id)) return false;
    seen.add(r.track.id);
    return true;
  });
  if (clean.length === 0) return t(s, null, false);
  const start = clean.findIndex((r) => r.track.id === startTrackId);
  if (start < 0) return t(s, null, false);
  const next = clone(s);
  next.rows = clean;
  const indices = clean.map((_, i) => i);
  if (shuffle) {
    const rest = indices.filter((i) => i !== start);
    next.playOrder = [start, ...shuffled(rest, random)];
    next.shuffle = true;
    next.cursor = 0;
  } else {
    next.playOrder = indices;
    next.shuffle = false;
    next.cursor = start;
  }
  next.finished = false;
  return t(next, { type: 'play', row: start }, true);
}

/** Clear the queue entirely (stops nothing — order-only). */
export function clearQueue(s: QueueMachineState): Transition {
  if (s.rows.length === 0) return t(s, null, false);
  const next = emptyQueueState();
  next.repeat = s.repeat;
  next.shuffle = s.shuffle;
  return t(next, null, true);
}
