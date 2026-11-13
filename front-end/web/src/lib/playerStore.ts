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
