/**
 * lib/playerStore.ts — the React binding for the WasmEnginePort (W2.1).
 *
 * The port pushes rev-guarded EngineSnapshots (4 Hz telemetry cadence);
 * this zustand store mirrors the latest one + the queue read for the UI.
 * Commands flow the other way through the port's send() (debounced in
 * wasm — the UDL contract).
 */

import { create } from 'zustand';
import type { EngineSnapshot, PlayerCommand, QueueRead, TrackRow, WasmEnginePort } from './wasmEngine';
import type { ResolvedStream } from '../audio/resolve';

export interface PlayerState {
  port: WasmEnginePort | null;
  snapshot: EngineSnapshot | null;
  queue: QueueRead;
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
}

const IDLE_QUEUE: QueueRead = { rows: [], cursor: null };

export const usePlayerStore = create<PlayerState & PlayerActions>((set, get) => ({
  port: null,
  snapshot: null,
  queue: IDLE_QUEUE,
  lastRung: null,
  resolveError: null,

  bind(port) {
    set({ port, snapshot: port.currentSnapshot(), queue: port.getQueue() });
    // The push lane: every rev-bumped snapshot lands in the store; queue
    // reads piggyback on cursor/queueLength changes only (cheap JSON).
    let lastQueueFingerprint = '';
    port.subscribe((snap) => {
      const fingerprint = `${snap.queueLength}:${snap.cursor ?? '-'}`;
      const queueChanged = fingerprint !== lastQueueFingerprint;
      lastQueueFingerprint = fingerprint;
      set((prev) => ({
        snapshot: snap,
        queue: queueChanged ? port.getQueue() : prev.queue,
        // A healthy Playing snapshot retires the last resolve verdict.
        resolveError: snap.status === 'Playing' ? null : prev.resolveError,
      }));
    });
  },

  unbind() {
    set({ port: null, snapshot: null, queue: IDLE_QUEUE });
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
}));

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
