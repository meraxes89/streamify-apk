/**
 * lib/playerStore.ts — the React binding for the WasmEnginePort (W2.1),
 * extended in P1 (listen loop) with the PlaybackController port (§9.3:
 * shared/…/state/PlaybackController.kt → a Zustand slice that commands
 * WasmEnginePort).
 *
 * The port pushes rev-guarded EngineSnapshots (4 Hz telemetry cadence);
 * this zustand store mirrors the latest one + the queue read for the UI.
 * Commands flow the other way through the port's send() (debounced in
 * wasm — the UDL contract).
 *
 * P1 ADDITIONS (all additive — the W2.1 surface above is unchanged):
 *
 *   • PlaybackController vocabulary — togglePlayPause / next / previous /
 *     seekTo / setVolume, the exact intent names the shared components
 *     (PlaybackSeekbar / VolumeSlider / PlayerControls) lower onto. A
 *     translation, not a redesign: fire-and-forget sends; the push flow
 *     repaints the surface (round-trip discipline, never local echo).
 *
 *   • Deferred queue reorder — the wasm engine exposes append/clear/play
 *     but NO move/remove verb (and rust/ is read-only for UI PRs), so a
 *     Kotlin-style instant reorder cannot touch engine truth mid-track
 *     without clear_queue's stop pipeline killing the audio. The honest
 *     web adaptation: `reorderQueue()` reorders the up-next slice in this
 *     store instantly (every queue surface renders the projection), and
 *     `onTrackEndedHook` applies it by rebuilding the engine queue at the
 *     track boundary — the one moment a rebuild is silent (the element
 *     has already ended; the next row resolves lazily exactly as the
 *     engine's own 'resolve' intent would).
 */

import { create } from 'zustand';
import type { EngineSnapshot, PlayerCommand, QueueRead, TrackRow, WasmEnginePort } from './wasmEngine';
import type { ResolvedStream } from '../audio/resolve';

export interface PlayerState {
  port: WasmEnginePort | null;
  snapshot: EngineSnapshot | null;
  /**
   * Engine queue truth (getQueue() verbatim). Reorder math and the
   * boundary application operate on this; the UI never renders it.
   */
  baseQueue: QueueRead;
  /**
   * The queue every surface renders: baseQueue with the pending user
   * order projected onto the up-next slice (rows before/at the cursor
   * are history + now-playing and are never reordered — the Kotlin
   * QueueScreen scope).
   */
  queue: QueueRead;
  /** Pending up-next order (track ids). Null = engine order as-is. */
  queueOrderOverride: string[] | null;
  /** The rung behind the current/last resolve (diagnostics chip). */
  lastRung: ResolvedStream['rung'] | null;
  /** Resolve failures surfaced for the queue row that failed. */
  resolveError: string | null;
}

export interface PlayerActions {
  bind(port: WasmEnginePort): void;
  unbind(): void;
  send(command: PlayerCommand): void;
  setLastRung(rung: ResolvedStream['rung']): void;
  setResolveError(message: string | null): void;
  /**
   * The primary transport action (the big play/pause button everywhere):
   * toggle during a session; from Idle (queue exhausted / stopped) RESTART
   * the cursor row — the Rust command plane's toggle is an acknowledged
   * no-op from Idle, so the dock would otherwise be dead after a queue
   * runs out (the P3a gap: end-of-queue → Play did nothing).
   */
  playOrToggle(): void;
  /** PlaybackController convenience wrappers (fire-and-forget sends). */
  togglePlayPause(): void;
  next(): void;
  previous(): void;
  seekTo(positionMs: number): void;
  setVolume(volume: number): void;
  /**
   * Moves an up-next row (drag-reorder). Both indices are positions in
   * the PROJECTED queue (what the user sees and drags). Reorders only
   * rows strictly after the cursor — the Kotlin QueueScreen scope. The
   * projection updates instantly; engine truth follows at the next track
   * boundary. Returns false (no-op) when the move is out of scope.
   */
  reorderQueue(fromProjected: number, toProjected: number): boolean;
  /** Drops a pending order (engine order restored instantly). */
  clearQueueOrder(): void;
  /**
   * The boundary application (wired as port.onTrackEndedHook by bind).
   * Rebuilds the engine queue in the pending order and advances to the
   * row after the just-ended track. Returns false when there is nothing
   * to apply (the default engine advance then runs).
   */
  applyOrderAtBoundary(): boolean;
}

const IDLE_QUEUE: QueueRead = { rows: [], cursor: null };

// ---------------------------------------------------------------------------
// The projection: base engine order → user-ordered up-next
// ---------------------------------------------------------------------------

/**
 * Reorders the up-next slice (rows strictly after the cursor) according to
 * `override`: ids present in the override list come first in that order;
 * ids absent from it (rows enqueued after the reorder) keep their engine
 * order after them. History/now-playing rows are untouched. Mirrors the
 * Kotlin QueueScreen slices: played = [0, cursor), up next = (cursor, end].
 */
export function projectedQueueRows(
  base: QueueRead,
  override: string[] | null,
): QueueRead['rows'] {
  if (override === null || override.length === 0) return base.rows;
  const cursor = base.cursor;
  if (cursor === null || cursor < 0) return base.rows;
  const head = base.rows.slice(0, cursor + 1);
  const upNext = base.rows.slice(cursor + 1);
  if (upNext.length === 0) return base.rows;
  const inOverride: QueueRead['rows'] = [];
  const tail: QueueRead['rows'] = [];
  // Index the up-next rows by id (ids are unique in the engine queue).
  const byId = new Map<string, QueueRead['rows'][number]>();
  for (const row of upNext) byId.set(row.track.id, row);
  const consumed = new Set<string>();
  for (const id of override) {
    const row = byId.get(id);
    if (row !== undefined && !consumed.has(id)) {
      inOverride.push(row);
      consumed.add(id);
    }
  }
  for (const row of upNext) {
    if (!consumed.has(row.track.id)) tail.push(row);
  }
  return [...head, ...inOverride, ...tail];
}

function projectQueue(base: QueueRead, override: string[] | null): QueueRead {
  return { rows: projectedQueueRows(base, override), cursor: base.cursor };
}

export const usePlayerStore = create<PlayerState & PlayerActions>((set, get) => ({
  port: null,
  snapshot: null,
  baseQueue: IDLE_QUEUE,
  queue: IDLE_QUEUE,
  queueOrderOverride: null,
  lastRung: null,
  resolveError: null,

  bind(port) {
    set({ port, snapshot: port.currentSnapshot(), baseQueue: port.getQueue(), queue: port.getQueue() });
    // The push lane: every rev-bumped snapshot lands in the store; queue
    // reads piggyback on cursor/queueLength changes only (cheap JSON).
    let lastQueueFingerprint = '';
    port.subscribe((snap) => {
      const fingerprint = `${snap.queueLength}:${snap.cursor ?? '-'}`;
      const queueChanged = fingerprint !== lastQueueFingerprint;
      lastQueueFingerprint = fingerprint;
      const baseQueue = queueChanged ? port.getQueue() : get().baseQueue;
      set((prev) => ({
        snapshot: snap,
        baseQueue,
        queue: projectQueue(baseQueue, prev.queueOrderOverride),
        // A healthy Playing snapshot retires the last resolve verdict.
        resolveError: snap.status === 'Playing' ? null : prev.resolveError,
      }));
    });
    // P1: the track-boundary interceptor — applies a pending queue order
    // by rebuilding the engine queue (silent: the element just ended).
    // Returns false for the default engine advance in every other case.
    port.onTrackEndedHook = () => get().applyOrderAtBoundary();
  },

  unbind() {
    const { port } = get();
    if (port !== null) port.onTrackEndedHook = null;
    set({ port: null, snapshot: null, baseQueue: IDLE_QUEUE, queue: IDLE_QUEUE, queueOrderOverride: null });
  },

  send(command) {
    get().port?.send(command);
  },

  setLastRung(rung) {
    set({ lastRung: rung });
  },

  setResolveError(message) {
    set({ resolveError: message });
  },

  playOrToggle() {
    const { port, snapshot } = get();
    if (port === null) return;
    port.unlockSink();
    const status = snapshot?.status ?? 'Idle';
    if (status === 'Idle' || status === 'Error') {
      const queue = port.getQueue();
      const row = queue.rows[queue.cursor ?? snapshot?.cursor ?? 0];
      if (row === undefined) return;
      if (!port.playTrack(row.track.id)) {
        // URLless row (e.g. a resolve that never landed): resolve then play.
        void port.resolveAndStage(row.track.id).then((stream) => {
          if (stream !== null) port.playTrack(row.track.id);
        });
      }
      return;
    }
    port.send({ type: 'toggle' });
  },

  togglePlayPause() {
    get().playOrToggle();
  },

  next() {
    get().send({ type: 'next' });
  },

  previous() {
    get().send({ type: 'previous' });
  },

  seekTo(positionMs) {
    get().send({ type: 'seek', positionMs: Math.max(0, Math.round(positionMs)) });
  },

  setVolume(volume) {
    get().send({ type: 'setVolume', volume: Math.min(1, Math.max(0, volume)) });
  },

  reorderQueue(fromProjected, toProjected) {
    const { baseQueue, queueOrderOverride, port } = get();
    const cursor = baseQueue.cursor;
    const rows = projectedQueueRows(baseQueue, queueOrderOverride);
    if (fromProjected === toProjected) return false;
    if (cursor !== null && (fromProjected <= cursor || toProjected <= cursor)) return false; // up-next only
    if (fromProjected < 0 || toProjected < 0) return false;
    if (fromProjected >= rows.length || toProjected >= rows.length) return false;
    // Standard array move on the projected list.
    const moved = rows.slice();
    const [row] = moved.splice(fromProjected, 1);
    if (row === undefined) return false;
    moved.splice(toProjected, 0, row);
    if (cursor === null) {
      // NOTHING SELECTED YET: the whole queue is up-next and nothing is
      // playing, so the engine rebuild is silent RIGHT NOW (clear_queue on
      // a non-playing pipeline emits no audible stop) — the order becomes
      // engine truth immediately instead of deferring to a boundary that
      // may never come (the user might not press play for an hour).
      if (port === null) return false;
      port.clearQueue();
      for (const r of moved) port.enqueue(r.track, r.enqueuedBy);
      const fresh = port.getQueue();
      set({ baseQueue: fresh, queue: fresh, queueOrderOverride: null });
      return true;
    }
    // A track is selected: defer to the next boundary (the override is
    // the projected up-next ids; base truth is never touched here).
    const override = moved.slice(cursor + 1).map((r) => r.track.id);
    set({
      queueOrderOverride: override,
      queue: projectQueue(baseQueue, override),
    });
    return true;
  },

  clearQueueOrder() {
    const { baseQueue } = get();
    set({ queueOrderOverride: null, queue: baseQueue });
  },

  /** The boundary application (wired as port.onTrackEndedHook in bind). */
  applyOrderAtBoundary(): boolean {
    const { port, baseQueue, queueOrderOverride } = get();
    if (port === null || queueOrderOverride === null) return false;
    const cursor = baseQueue.cursor;
    if (cursor === null) {
      set({ queueOrderOverride: null, queue: baseQueue });
      return false;
    }
    const rows = projectedQueueRows(baseQueue, queueOrderOverride);
    const ended = rows[cursor];
    const nextRow = rows[cursor + 1] ?? null;
    if (ended === undefined) {
      set({ queueOrderOverride: null, queue: baseQueue });
      return false;
    }
    // 1. Rebuild the engine queue in the user's order. clear_queue stops
    //    the (already ended) pipeline — silent by construction.
    port.clearQueue();
    for (const row of rows) port.enqueue(row.track, row.enqueuedBy);
    // 2. Drop the override BEFORE the async play (the projection would
    //    otherwise double-apply over the fresh base on the next push).
    set({ queueOrderOverride: null, baseQueue: port.getQueue(), queue: port.getQueue() });
    // 3. Advance to the row after the just-ended track — the standard
    //    lazy pattern (resolve then play), identical to the engine's own
    //    'resolve' intent path for a URLless row.
    if (nextRow !== null) {
      void port.resolveAndStage(nextRow.track.id).then((stream) => {
        if (stream !== null) port.playTrack(nextRow.track.id);
      });
    }
    return true;
  },
}));

// ---------------------------------------------------------------------------
// The store-level queue slices (the QueueList.kt derivations, store-shaped)
// ---------------------------------------------------------------------------

/** Rows before the cursor (play history), in play order. */
export function playedRows(queue: QueueRead): QueueRead['rows'] {
  const c = queue.cursor;
  if (c === null || c <= 0 || queue.rows.length === 0) return [];
  return queue.rows.slice(0, Math.min(c, queue.rows.length));
}

/** Rows strictly after the cursor (up next), in play order. */
export function upNextRows(queue: QueueRead): QueueRead['rows'] {
  const c = queue.cursor;
  if (c === null || c < 0) return queue.rows;
  if (c + 1 >= queue.rows.length) return [];
  return queue.rows.slice(c + 1);
}

/** The row at the cursor (now playing), when one is selected. */
export function currentRow(queue: QueueRead): QueueRead['rows'][number] | null {
  const c = queue.cursor;
  if (c === null || c < 0 || c >= queue.rows.length) return null;
  return queue.rows[c] ?? null;
}

/** Track-row helper: search Track → engine TrackRow. */
export function toTrackRow(track: {
  videoId: string;
  title: string;
  artist: string;
  album?: string;
  durationSec?: number;
  thumbnailUrl?: string;
}): TrackRow {
  return {
    id: track.videoId,
    title: track.title,
    artist: track.artist,
    album: track.album ?? '',
    durationMs: Math.round((track.durationSec ?? 0) * 1000),
    thumbnailUrl: track.thumbnailUrl ?? null,
    isExplicit: false,
  };
}
