/**
 * lib/playerStore.ts — the React binding for the WasmEnginePort (W2.1),
 * slimmed in P2-B to the TRANSPORT mirror.
 *
 * The queue semantics (order, cursor, shuffle, repeat — the state
 * machine) moved to lib/playback/queueStore.ts, the P2-B single source
 * of truth. What remains here is the engine's transport reality:
 * snapshots (status/position/volume — what is actually playing), the
 * command passthrough (send), and the resolve-verdict lanes the shell
 * surfaces. Every queue surface renders queueStore's projections; this
 * store answers "what is the transport doing right now".
 */

import { create } from 'zustand';
import type { EngineSnapshot, PlayerCommand, TrackRow, WasmEnginePort } from './wasmEngine';
import type { ResolvedStream } from '../audio/resolve';

export interface PlayerState {
  port: WasmEnginePort | null;
  snapshot: EngineSnapshot | null;
  /** The rung behind the current/last resolve (diagnostics chip). */
  lastRung: ResolvedStream['rung'] | null;
  /** Resolve failures surfaced for the failing row. */
  resolveError: string | null;
}

export interface PlayerActions {
  bind(port: WasmEnginePort): void;
  unbind(): void;
  send(command: PlayerCommand): void;
  setVolume(volume: number): void;
  setLastRung(rung: ResolvedStream['rung']): void;
  setResolveError(message: string | null): void;
}

export const usePlayerStore = create<PlayerState & PlayerActions>((set, get) => ({
  port: null,
  snapshot: null,
  lastRung: null,
  resolveError: null,

  bind(port) {
    set({ port, snapshot: port.currentSnapshot() });
    // The push lane: every rev-bumped snapshot lands in the store. The
    // queue store owns its own subscription (scrobble/gapless/persist);
    // this one is the surfaces' transport truth.
    port.subscribe((snap) => {
      set((prev) => ({
        snapshot: snap,
        // A healthy Playing snapshot retires the last resolve verdict.
        resolveError: snap.status === 'Playing' ? null : prev.resolveError,
      }));
    });
  },

  unbind() {
    set({ port: null, snapshot: null });
  },

  send(command) {
    get().port?.send(command);
  },

  setVolume(volume) {
    get().port?.send({ type: 'setVolume', volume: Math.min(1, Math.max(0, volume)) });
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
