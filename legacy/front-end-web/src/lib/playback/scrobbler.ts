/**
 * lib/playback/scrobbler.ts — P2-B: the scrobble subsystem (PURE).
 *
 * THE PLAY EVENT, PICKED ONCE AND DOCUMENTED: a play session counts when
 * it accumulates min(half the track's duration, 4 minutes) of actual
 * PLAYED time — wall-clock while the transport is Playing, so pauses
 * never accrue. Unknown duration → the 4-minute cap alone.
 *
 * NO RE-SCROBBLE, EVER, FOR:
 *   • seek     — a seek moves the playhead; the session and its
 *                accumulated time are untouched.
 *   • reload / restore — the session (trackId + playedMs + fired flag)
 *                persists with the queue state and is CONTINUED across
 *                the restore; a fired session never fires again.
 *   • pause    — accrual simply stops while paused.
 * A DELIBERATE REPLAY *does* scrobble again: starting the same track
 * anew (fresh play, restart after end, jump back to a played row) opens
 * a FRESH session with a re-armed threshold.
 *
 * THE OUTBOX: fire-and-forget with a bounded retry queue. Scrobble
 * failure never blocks, pauses, or visually errors playback — events
 * land in a persisted outbox and drain on reconnect / revisibility /
 * sign-in. Poison protection: after MAX_ATTEMPTS failed sends an entry
 * is dropped (logged by the runtime); any successful drain resets the
 * attempt counters (a success proves connectivity, so earlier failures
 * were offline flaps, not poison).
 *
 * The module is transport-agnostic: clocks, storage, and the cloud send
 * are injected. The runtime wiring lives in queueStore.ts.
 */

import type { PersistedScrobbleSession } from './persistence';

/** The 4-minute cap (the "whichever is shorter" half of the rule). */
export const SCROBBLE_CAP_MS = 240_000;

/** A single tick may accrue at most this much (stall guard: bounds any
 * single-tick over-count to one minute while leaving background-tab
 * listening intact — media elements keep playing while interval timers
 * throttle to ~1/min, and that dt is REAL played time). */
export const MAX_TICK_MS = 60_000;

/** Poison protection: drop an outbox entry after this many failed sends. */
export const MAX_OUTBOX_ATTEMPTS = 5;

/** Outbox ceiling — oldest entries drop past it (bounded memory + storage). */
export const OUTBOX_CAP = 200;

/**
 * The threshold rule: min(duration/2, 4 min); unknown duration → the cap.
 */
export function thresholdFor(durationMs: number, capMs: number = SCROBBLE_CAP_MS): number {
  if (!Number.isFinite(durationMs) || durationMs <= 0) return capMs;
  return Math.min(durationMs / 2, capMs);
}

/** The live scrobble session state. */
export interface ScrobbleSession {
  trackId: string;
  /** Epoch ms when the session started. */
  startedAt: number;
  /** Accumulated PLAYED time (pause does not accrue). */
  playedMs: number;
  /** Crossed the threshold and fired — never fires again. */
  fired: boolean;
}

/** One scrobble event (the outbox payload). */
export interface ScrobbleEvent {
  trackId: string;
  title: string;
  artist: string;
  album: string;
  durationMs: number;
  /** Accumulated played time at fire time. */
  playedMs: number;
  thresholdMs: number;
  /** Epoch ms of the fire. */
  firedAt: number;
  /** Resolved-stream metadata from the engine (the rung that served it). */
  rung: string | null;
}

// ---------------------------------------------------------------------------
// The play-session tracker
// ---------------------------------------------------------------------------

export class PlaySessionTracker {
  private session: ScrobbleSession | null = null;
  private readonly capMs: number;

  constructor(capMs: number = SCROBBLE_CAP_MS) {
    this.capMs = capMs;
  }

  /** The live session (for persistence — restore feeds it back). */
  get current(): ScrobbleSession | null {
    return this.session === null ? null : { ...this.session };
  }

  /** A deliberate (re)start of a track — always a FRESH session. */
  start(trackId: string, now: number): void {
    this.session = { trackId, startedAt: now, playedMs: 0, fired: false };
  }

  /** The restore path: continue a persisted session in place. */
  continue(restored: PersistedScrobbleSession): void {
    this.session = {
      trackId: restored.trackId,
      startedAt: restored.startedAt,
      playedMs: restored.playedMs,
      fired: restored.scrobbled,
    };
  }

  /** The session ended without crossing (skip/abandon) — discard it. */
  abandon(): void {
    this.session = null;
  }

  /** True when the live session belongs to this track (the accrue guard). */
  isFor(trackId: string): boolean {
    return this.session !== null && this.session.trackId === trackId;
  }

  /**
   * The full accrual with track facts (the runtime calls this per
   * snapshot tick while the transport is Playing): accrues, then checks
   * the threshold with the track's real duration. Returns the event on
   * the crossing tick, else null.
   */
  accrueTrack(
    trackId: string,
    dtMs: number,
    now: number,
    facts: { title: string; artist: string; album: string; durationMs: number; rung: string | null },
  ): ScrobbleEvent | null {
    const s = this.session;
    if (s === null || s.trackId !== trackId || s.fired || dtMs <= 0) return null;
    s.playedMs += Math.min(dtMs, MAX_TICK_MS);
    const thresholdMs = thresholdFor(facts.durationMs, this.capMs);
    if (s.playedMs < thresholdMs) return null;
    s.fired = true;
    return {
      trackId,
      title: facts.title,
      artist: facts.artist,
      album: facts.album,
      durationMs: facts.durationMs,
      playedMs: s.playedMs,
      thresholdMs,
      firedAt: now,
      rung: facts.rung,
    };
  }

  /** The current played-time snapshot (for persistence throttles). */
  playedMsOf(trackId: string): number {
    const s = this.session;
    return s !== null && s.trackId === trackId ? s.playedMs : 0;
  }

  /** Whether this track's session already fired (restore continuity). */
  firedFor(trackId: string): boolean {
    const s = this.session;
    return s !== null && s.trackId === trackId && s.fired;
  }
}

// ---------------------------------------------------------------------------
// The outbox (bounded, persisted, drain-on-reconnect)
// ---------------------------------------------------------------------------

export interface OutboxEntry extends ScrobbleEvent {
  attempts: number;
}

/** Injected persistence for the outbox (localStorage in production). */
export interface OutboxStore {
  load(): OutboxEntry[] | null;
  save(entries: OutboxEntry[]): void;
}

export function createOutboxStore(key: string): OutboxStore {
  return {
    load(): OutboxEntry[] | null {
      try {
        const raw = localStorage.getItem(key);
        if (raw === null) return null;
        const parsed = JSON.parse(raw) as unknown;
        if (!Array.isArray(parsed)) return null;
        return parsed.filter(
          (e): e is OutboxEntry =>
            typeof e === 'object' &&
            e !== null &&
            typeof (e as OutboxEntry).trackId === 'string' &&
            typeof (e as OutboxEntry).firedAt === 'number',
        );
      } catch {
        return null;
      }
    },
    save(entries: OutboxEntry[]): void {
      try {
        localStorage.setItem(key, JSON.stringify(entries));
      } catch {
        /* private mode / quota — the outbox is best-effort by contract */
      }
    },
  };
}

export class ScrobbleOutbox {
  private entries: OutboxEntry[];
  private readonly store: OutboxStore;
  private readonly cap: number;
  private draining = false;

  constructor(store: OutboxStore, cap: number = OUTBOX_CAP) {
    this.store = store;
    this.cap = cap;
    this.entries = store.load() ?? [];
  }

  get size(): number {
    return this.entries.length;
  }

  pending(): OutboxEntry[] {
    return this.entries.map((e) => ({ ...e }));
  }

  /** Enqueue an event (drops the oldest past the cap — newest wins). */
  push(event: ScrobbleEvent): void {
    this.entries.push({ ...event, attempts: 0 });
    if (this.entries.length > this.cap) {
      this.entries = this.entries.slice(this.entries.length - this.cap);
    }
    this.persist();
  }

  /**
   * Drain the outbox. `canSend` false → no-op (entries untouched — being
   * offline is not a failure). Per-entry failures keep the entry (attempts
   *++); MAX_OUTBOX_ATTEMPTS drops it; any success resets every attempt
   * counter. Never throws — scrobble failure must not block anything.
   */
  async drain(send: (e: ScrobbleEvent) => Promise<boolean>, canSend: () => boolean): Promise<number> {
    if (this.draining || !canSend() || this.entries.length === 0) return 0;
    this.draining = true;
    let sent = 0;
    let anySuccess = false;
    try {
      // Snapshot-iterate: send() may be slow; pushes during a drain join
      // the NEXT one.
      const batch = this.entries.map((e) => ({ ...e }));
      const remaining: OutboxEntry[] = [];
      for (const entry of batch) {
        let ok = false;
        try {
          ok = await send(entry);
        } catch {
          ok = false;
        }
        if (ok) {
          sent += 1;
          anySuccess = true;
        } else {
          const next = { ...entry, attempts: entry.attempts + 1 };
          if (next.attempts < MAX_OUTBOX_ATTEMPTS) remaining.push(next);
          // else: poison-dropped (the runtime logs it — bounded loss)
        }
      }
      // Entries pushed DURING the drain append after the survivors.
      const pushedDuringDrain = this.entries.slice(batch.length);
      this.entries = [...remaining, ...pushedDuringDrain];
      if (anySuccess) {
        for (const e of this.entries) e.attempts = 0;
      }
      this.persist();
    } finally {
      this.draining = false;
    }
    return sent;
  }

  private persist(): void {
    this.store.save(this.entries);
  }
}
