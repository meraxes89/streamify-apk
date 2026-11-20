/**
 * lib/jamStore.ts — the jam session store (WS-W engine facade breadth,
 * work item 1). The Android JamViewModel's web lowering: the session
 * lifecycle (engine session + realtime transport), inbound op folding and
 * the room state view, with the P2 honest-empty-state discipline —
 * nothing is ever faked; every degradation surfaces its verdict.
 *
 * The engine session lives on the app's ONE WasmEnginePort (the playback
 * engine's jam lane — the UDL mirror). The transport rides the §11
 * posture (lib/jam/transport.ts — the ADR note lives there).
 *
 * Honest scope (documented, not faked): outbound op BUILDING (queue
 * add/remove/reorder with fractional indexing over the live fold) rides
 * with the jam screens — it needs the CRDT queue view read, which the
 * facade grows at that point. The transport's sendOp lane is ready for it.
 */

import { create } from 'zustand';
import { usePlayerStore } from './playerStore';
import { ensureJamEngine, type JamSessionHandle, type JamSyncResult } from './jamEngine';
import {
  JamRealtimeTransport,
  rowToWireBytes,
  type JamTransportStatus,
  type JamOpsRow,
} from './jam/transport';

export interface JamState {
  /** The transport status (verbatim from the realtime client). */
  status: JamTransportStatus;
  statusDetail: string | null;
  /** The engine session handle (null until host/join lands). */
  handle: JamSessionHandle | null;
  /** The room code the transport is bound to (uppercase). */
  sessionCode: string | null;
  /** The room state view (the local replica's). */
  roomCode: string | null;
  isHost: boolean;
  /** The BLAKE3 fold hash after the latest applied batch. */
  stateHash: string | null;
  /** Ops folded (applied/rejected) since the session opened. */
  opsApplied: number;
  opsRejected: number;
  /** The honest error lane (never cleared silently). */
  error: string | null;
  /**
   * Host or join a room by code. Creates the ENGINE session (the local
   * replica), then connects the realtime transport. Never throws —
   * verdicts land on the error lane.
   */
  join(code: string): Promise<void>;
  /** Leave: transport closed, session view reset (the engine keeps truth). */
  leave(): void;
  /** Inbound rows from the transport (fold through the engine). */
  applyInbound(rows: JamOpsRow[]): Promise<void>;
}

let transport: JamRealtimeTransport | null = null;

export const useJamStore = create<JamState>((set, get) => ({
  status: 'idle',
  statusDetail: null,
  handle: null,
  sessionCode: null,
  roomCode: null,
  isHost: false,
  stateHash: null,
  opsApplied: 0,
  opsRejected: 0,
  error: null,

  async join(code) {
    const trimmed = code.trim();
    if (trimmed === '') {
      set({ error: 'empty room code' });
      return;
    }
    const port = usePlayerStore.getState().port;
    if (port === null) {
      set({ error: 'engine not ready — try again in a moment' });
      return;
    }
    try {
      await ensureJamEngine();
      // The local replica: the engine session (the UDL's create lane).
      const handle = port.createJamSession(`Room ${trimmed.toUpperCase()}`);
      set({
        handle,
        sessionCode: trimmed.toUpperCase(),
        roomCode: handle.sessionName,
        isHost: true,
        stateHash: port.getJamStateHash(),
        opsApplied: 0,
        opsRejected: 0,
        error: null,
      });
      transport?.close();
      transport = new JamRealtimeTransport(trimmed, {
        onStatus: (status, detail) => {
          set({ status, statusDetail: detail });
        },
        onOps: (rows) => {
          void get().applyInbound(rows);
        },
      });
      transport.connect();
    } catch (err) {
      set({ error: err instanceof Error ? err.message : String(err) });
    }
  },

  leave() {
    transport?.close();
    transport = null;
    set({
      status: 'closed',
      statusDetail: null,
      handle: null,
      sessionCode: null,
      roomCode: null,
      isHost: false,
      stateHash: null,
      opsApplied: 0,
      opsRejected: 0,
    });
  },

  async applyInbound(rows) {
    const port = usePlayerStore.getState().port;
    if (port === null || rows.length === 0) return;
    // Rows → 48-byte wire rows through the engine's pack lane (the
    // transport's single conversion step — see lib/jam/transport.ts).
    const batches: Uint8Array[] = [];
    for (const row of rows) {
      const bytes = await rowToWireBytes(row);
      if (bytes !== null) batches.push(bytes);
    }
    if (batches.length === 0) return;
    const total = batches.reduce((n, b) => n + b.length, 0);
    const delta = new Uint8Array(total);
    let at = 0;
    for (const b of batches) {
      delta.set(b, at);
      at += b.length;
    }
    try {
      const verdict: JamSyncResult = port.applyJamDelta(delta);
      const room = port.getJamRoomState();
      set((prev) => ({
        stateHash: verdict.stateHash,
        opsApplied: prev.opsApplied + verdict.opsApplied,
        opsRejected: prev.opsRejected + verdict.opsRejected,
        roomCode: room.roomCode,
        isHost: room.isHost,
        error: verdict.needsRebalance
          ? 'queue indices need a rebalance pass (the room host should compact)'
          : prev.error,
      }));
    } catch (err) {
      set({ error: err instanceof Error ? err.message : String(err) });
    }
  },
}));

/** The invite-link room code (HashRouter: the query rides the hash). */
export function inviteRoomCode(search: string): string | null {
  const code = new URLSearchParams(search).get('jam');
  if (code === null) return null;
  const trimmed = code.trim();
  return trimmed === '' ? null : trimmed;
}

/** Per-room dismissal memory (the Kotlin sheet's remember-dismissed rule). */
const DISMISS_KEY = 'streamify.jam.dismissed';

export function isRoomDismissed(code: string): boolean {
  try {
    const raw = window.localStorage.getItem(DISMISS_KEY);
    if (raw === null) return false;
    const list = JSON.parse(raw) as string[];
    return list.includes(code.toUpperCase());
  } catch {
    return false;
  }
}

export function dismissRoom(code: string): void {
  try {
    const key = code.toUpperCase();
    const raw = window.localStorage.getItem(DISMISS_KEY);
    const list = raw !== null ? (JSON.parse(raw) as string[]) : [];
    if (!list.includes(key)) {
      list.push(key);
      window.localStorage.setItem(DISMISS_KEY, JSON.stringify(list));
    }
  } catch {
    /* storage unavailable — the prompt just re-shows; never fatal */
  }
}
