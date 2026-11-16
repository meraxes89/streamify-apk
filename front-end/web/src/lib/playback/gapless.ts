/**
 * lib/playback/gapless.ts — P2-B: the gapless/crossfade SCHEDULER (PURE).
 *
 * Engineer B owns WHEN, Engineer A owns the mechanics: the preload
 * trigger (a fresh resolve for track N+1 at T-15s) fires from the queue
 * state machine based on the engine's remaining time; the crossfade
 * itself executes on A's dual engine through a documented seam. This
 * module computes the WHEN — never touches the network, the engine, or
 * the audio graph — so every trigger rule below is a headless test.
 *
 * THE PRELOAD TRIGGER (the resolve-ahead):
 *
 *   remaining = trackDuration − playhead. When remaining ≤ preloadLead
 *   (default 15 s) the next row's stream resolves NOW, so the track
 *   boundary switch is a staged-URL play — no multi-second resolve wait
 *   between songs. The prepared URL lives on the ENGINE row
 *   (stage_stream_url); this scheduler only tracks the bookkeeping.
 *
 * THE CROSSFADE HANDOFF (the seam — A's contract, P2):
 *
 *   Crossfade duration is a config value (default ~3 s, the settings
 *   surface's crossfadeSec). Inside the window (remaining ≤ crossfadeMs)
 *   the scheduler ARMS the handoff; at the natural boundary the runtime
 *   calls the dual-engine seam:
 *
 *     WasmEnginePort.crossfadeTo?(trackId: string, crossfadeMs: number):
 *       boolean
 *
 *   — fade the current element out while fading the prepared one in.
 *   Until that method exists on the port, the boundary falls back to
 *   the plain staged-URL play_track (near-gapless: the resolve wait is
 *   already gone; only the element swap remains). The seam is checked
 *   by duck-type at the call site — no engine change required to ship.
 *
 * EDGE CASES, EACH WITH A NAME AND A TEST:
 *
 *   • repeat-one        — N+1 is N again: nothing to prepare (the
 *                         current stream IS the next stream); the
 *                         crossfade target is the current track.
 *   • one-track queue   — N+1 is N itself (repeat-all): identical to
 *                         repeat-one; repeat-off has no N+1 at all.
 *   • shuffle mid-preload — the preloaded track may no longer be next:
 *                         emit the invalidation, resolve the new one,
 *                         drop the stale bookkeeping (the stale staged
 *                         URL on the other row is harmless — it is
 *                         per-row metadata that either gets replayed
 *                         warm or rotates if stale).
 *   • failed resolve    — a retry backs off (PRELOAD_RETRY_MS) so a
 *                         dead rung cannot mint a request storm.
 */

/** Default preload lead: resolve N+1 this long before end-of-track. */
export const PRELOAD_LEAD_MS = 15_000;

/** A failed preload waits this long before retrying. */
export const PRELOAD_RETRY_MS = 30_000;

export interface GaplessConfig {
  preloadLeadMs: number;
  /** 0 = crossfade off (plain boundary switch). */
  crossfadeMs: number;
}

export interface PreparedTrack {
  trackId: string;
  preparedAt: number;
  /** True when the resolve attempt failed (backoff applies). */
  failed: boolean;
}

export interface GaplessOps {
  /** Resolve this track's stream NOW (the T-lead trigger crossed). */
  preload: string | null;
  /** A previously-prepared track stopped being next — stale, drop it. */
  invalidated: string | null;
  /** Inside the crossfade window with a prepared (or warm) target. */
  crossfadeArmed: boolean;
  /** The track the crossfade would hand off to. */
  crossfadeTarget: string | null;
}

const NO_OPS: GaplessOps = {
  preload: null,
  invalidated: null,
  crossfadeArmed: false,
  crossfadeTarget: null,
};

export class GaplessScheduler {
  private prepared: PreparedTrack | null = null;
  private config: GaplessConfig;

  constructor(config: GaplessConfig = { preloadLeadMs: PRELOAD_LEAD_MS, crossfadeMs: 0 }) {
    this.config = config;
  }

  setConfig(config: GaplessConfig): void {
    this.config = config;
  }

  /** The current prepared row (diagnostics / tests). */
  get currentPrepared(): PreparedTrack | null {
    return this.prepared === null ? null : { ...this.prepared };
  }

  /** The live config (the runtime surfaces crossfadeMs from settings). */
  get currentConfig(): GaplessConfig {
    return { ...this.config };
  }

  /**
   * The runtime calls this per snapshot tick. `nextTrackId` is the
   * MACHINE's next row (repeat/shuffle/reorder-aware — the controller
   * computes it, the scheduler never guesses).
   */
  tick(args: {
    now: number;
    playing: boolean;
    currentTrackId: string | null;
    positionMs: number;
    durationMs: number;
    nextTrackId: string | null;
  }): GaplessOps {
    const { now, playing, currentTrackId, positionMs, durationMs, nextTrackId } = args;

    // -- bookkeeping reconciliation ------------------------------------------
    // Three cases: the boundary CONSUMED the preparation (the prepared
    // track is now current — success, silent), the next row MOVED under
    // it (shuffle/reorder mid-preload — invalidate, then prepare the
    // new next in this same tick), or nothing changed.
    let invalidated: string | null = null;
    if (this.prepared !== null) {
      if (currentTrackId === this.prepared.trackId) {
        this.prepared = null; // consumed by the boundary
      } else if (nextTrackId === null || nextTrackId !== this.prepared.trackId) {
        const stale = this.prepared;
        this.prepared = null;
        if (currentTrackId === null || nextTrackId === null) return { ...NO_OPS };
        invalidated = stale.trackId; // shuffle mid-preload / reorder
      }
    }

    if (!playing || currentTrackId === null || durationMs <= 0) {
      return { ...NO_OPS, invalidated };
    }
    if (nextTrackId === null) return { ...NO_OPS, invalidated }; // queue exhausted (repeat-off tail)

    // repeat-one / one-track repeat-all: N+1 is N — the warm stream IS
    // next; nothing to prepare, and the crossfade target is the current
    // track itself.
    const sameTrack = nextTrackId === currentTrackId;

    const remaining = durationMs - positionMs;
    const inPreloadWindow = remaining <= this.config.preloadLeadMs;
    const inCrossfadeWindow =
      this.config.crossfadeMs > 0 && remaining <= this.config.crossfadeMs && remaining > 0;

    let preload: string | null = null;
    if (!sameTrack && inPreloadWindow) {
      // Fire when nothing is prepared, or when a FAILED preparation's
      // backoff has elapsed. A healthy preparation never re-fires.
      const retryDue =
        this.prepared === null ||
        (this.prepared.failed && now - this.prepared.preparedAt >= PRELOAD_RETRY_MS);
      if (retryDue) {
        preload = nextTrackId;
        this.prepared = { trackId: nextTrackId, preparedAt: now, failed: false };
      }
    }

    const crossfadeReady =
      sameTrack ||
      (this.prepared !== null && !this.prepared.failed && this.prepared.trackId === nextTrackId);

    return {
      preload,
      invalidated,
      crossfadeArmed: inCrossfadeWindow && crossfadeReady,
      crossfadeTarget: inCrossfadeWindow && crossfadeReady ? nextTrackId : null,
    };
  }

  /**
   * The runtime reports the preload resolve's verdict. A failure arms
   * the backoff (no storm); success just confirms the bookkeeping.
   */
  reportResult(trackId: string, ok: boolean): void {
    if (this.prepared === null || this.prepared.trackId !== trackId) return;
    this.prepared.failed = !ok;
    if (ok) this.prepared.preparedAt = Date.now();
  }

  /** Full reset (queue replaced / restore / teardown). */
  reset(): void {
    this.prepared = null;
  }
}
