/**
 * audio/store.ts — the P1 spine's observable state (Zustand).
 *
 * One store carries everything the React console renders and every
 * subsystem reports into. It is deliberately MAIN-THREAD-ONLY: the
 * worklet and the demux worker never touch it; their telemetry crosses
 * the MessagePort as plain data (SinkStats wire snapshots, chunk
 * health) and the transport layer posts it in. This keeps the store
 * free of locking/ordering questions and keeps the render path cheap
 * (selectors over a flat object).
 *
 * The state machine (SpinePhase) is linear and failure-explicit:
 *
 *      idle → resolving → demuxing → streaming → playing ⇄ paused
 *                                        ↘ errored ← anywhere
 *
 * `demuxing` = header bytes arriving, format not yet published;
 * `streaming` = format known, PCM flowing into the sink ring but the
 * sink not yet started (prefill); `playing` = AudioWorklet consumer
 * live. G1 gate metric (underrunRatio < 0.1%) is derived from the
 * latest stats snapshot, not stored raw.
 */

import { create } from 'zustand';
import type { AudioCodec } from './types';
import type { SinkStats } from './statsWire';
import { underrunRatio } from './statsWire';

/** Linear spine lifecycle; `errored` is terminal (new session → idle). */
export type SpinePhase =
  | 'idle'
  | 'resolving'
  | 'demuxing'
  | 'streaming'
  | 'playing'
  | 'paused'
  | 'errored';

/** PO token minting lifecycle (W1.0 potSession reports in). */
export type PotPhase =
  | 'off' // session doesn't need / hasn't started minting
  | 'visitor' // fetching visitorData
  | 'minting' // BotGuard challenge running (worker or iframe)
  | 'ready' // integrity + PO token cached
  | 'failed'; // minting refused; ladder degraded (documented)

/** Backend the decoder factory selected (W1.2 ladder). */
export type DecoderBackend = 'webcodecs' | 'wasm' | 'none';

/** One resolved stream candidate (the resolver ladder's winner). */
export interface SessionTrack {
  videoId: string;
  title: string;
  artist: string;
  itag: number;
  codec: AudioCodec;
  /** Stream byte length when known (Range probe), else null. */
  contentLength: number | null;
  /** ms since epoch when the session was opened. */
  startedAt: number;
}

/** Fetch-loop health, posted in by the transport (W1.4). */
export interface FetchHealth {
  chunksRequested: number;
  chunksSucceeded: number;
  bytesServed: number;
  redirectHops: number;
  retries: number;
  /** Last upstream status that was NOT 200/206 (0 = none seen). */
  lastBadStatus: number;
}

export interface SpineState {
  // -- lifecycle --------------------------------------------------------
  phase: SpinePhase;
  /** Terminal-phase explanation; null unless phase === 'errored'. */
  error: string | null;

  // -- session ----------------------------------------------------------
  session: SessionTrack | null;
  decoderBackend: DecoderBackend;
  decoderDetail: string | null;

  // -- PO token (W1.0) ---------------------------------------------------
  potPhase: PotPhase;
  potDetail: string | null;

  // -- telemetry ---------------------------------------------------------
  /** Latest stats snapshot from the sink (wire image parsed). */
  stats: SinkStats | null;
  /** Playhead position (µs) as reported by the sink (epoch-rebased). */
  positionUs: number;
  fetch: FetchHealth;
  /** Packets emitted by the demuxer so far this session. */
  packetsDemuxed: number;
  /** Bytes accepted by the demuxer so far this session. */
  bytesFed: number;

  // -- actions (called by spine.ts / transport / UI) ----------------------
  setPhase: (phase: SpinePhase, error?: string) => void;
  startSession: (session: SessionTrack, backend: DecoderBackend, detail: string) => void;
  endSession: () => void;
  setDecoder: (backend: DecoderBackend, detail: string) => void;
  setPot: (phase: PotPhase, detail?: string) => void;
  setStats: (stats: SinkStats, positionUs: number) => void;
  addFetch: (delta: Partial<FetchHealth>) => void;
  addDemux: (packets: number, bytes: number) => void;
}

/** Fresh fetch-health image (a session start resets the counters). */
const ZERO_FETCH: FetchHealth = {
  chunksRequested: 0,
  chunksSucceeded: 0,
  bytesServed: 0,
  redirectHops: 0,
  retries: 0,
  lastBadStatus: 0,
};

export const useSpineStore = create<SpineState>((set) => ({
  phase: 'idle',
  error: null,
  session: null,
  decoderBackend: 'none',
  decoderDetail: null,
  potPhase: 'off',
  potDetail: null,
  stats: null,
  positionUs: 0,
  fetch: ZERO_FETCH,
  packetsDemuxed: 0,
  bytesFed: 0,

  setPhase: (phase, error) =>
    set((s) => ({
      phase,
      error: phase === 'errored' ? (error ?? 'unknown error') : s.error === undefined ? null : s.error,
      // pausing/playing keep the error clear; errored is the only phase
      // that carries one.
    })),

  startSession: (session, backend, detail) =>
    set({
      session,
      decoderBackend: backend,
      decoderDetail: detail,
      phase: 'demuxing',
      error: null,
      fetch: ZERO_FETCH,
      packetsDemuxed: 0,
      bytesFed: 0,
      stats: null,
    }),

  endSession: () =>
    set({
      phase: 'idle',
      error: null,
      session: null,
      stats: null,
      positionUs: 0,
      fetch: ZERO_FETCH,
      packetsDemuxed: 0,
      bytesFed: 0,
    }),

  setDecoder: (backend, detail) => set({ decoderBackend: backend, decoderDetail: detail }),

  setPot: (phase, detail) =>
    set((s) => ({ potPhase: phase, potDetail: detail ?? s.potDetail })),

  setStats: (stats, positionUs) => set({ stats, positionUs }),

  addFetch: (delta) =>
    set((s) => ({
      fetch: {
        chunksRequested: s.fetch.chunksRequested + (delta.chunksRequested ?? 0),
        chunksSucceeded: s.fetch.chunksSucceeded + (delta.chunksSucceeded ?? 0),
        bytesServed: s.fetch.bytesServed + (delta.bytesServed ?? 0),
        redirectHops: s.fetch.redirectHops + (delta.redirectHops ?? 0),
        retries: s.fetch.retries + (delta.retries ?? 0),
        lastBadStatus: delta.lastBadStatus ?? s.fetch.lastBadStatus,
      },
    })),

  addDemux: (packets, bytes) =>
    set((s) => ({
      packetsDemuxed: s.packetsDemuxed + packets,
      bytesFed: s.bytesFed + bytes,
    })),
}));

/**
 * G1 gate check over the live store state: TRUE (pass) requires a
 * drained stream and underrun events < 0.1% of frames read. Returns
 * 'insufficient' until enough frames flowed (>= 10s of canonical audio).
 */
export function g1UnderrunGate(stats: SinkStats | null): 'pass' | 'fail' | 'insufficient' {
  if (!stats) return 'insufficient';
  if (stats.framesRead < CANONICAL_MIN_FRAMES) return 'insufficient';
  const ratio = underrunRatio(stats);
  return ratio !== null && ratio < 0.001 ? 'pass' : 'fail';
}

/** 10 seconds of canonical 48 kHz audio. */
const CANONICAL_MIN_FRAMES = 48000 * 10;
