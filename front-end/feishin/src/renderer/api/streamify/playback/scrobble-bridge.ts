/**
 * api/streamify/playback/scrobble-bridge.ts — P3-B: the feishin runtime
 * wiring for the #187 scrobble subsystem.
 *
 * WHAT THIS BRIDGES: Feishin's playback reporting (use-scrobble.ts — the
 * stock hook, untouched) sends controller scrobble events; this module
 * turns that sparse event stream into ACTUAL PLAYED TIME for
 * PlaySessionTracker, which owns the threshold ruling
 * (min(half-duration, 4 min)) and fires exactly once per deliberate
 * listen. Fired events land in the ScrobbleOutbox and drain to
 * recordPlayEventStrict with the resolved-stream rung attached.
 *
 * THE EVENT STREAM (what a Streamify server actually receives — the
 * Jellyfin-only progress/seek lanes are feature-gated upstream and never
 * fire for us):
 *   • 'start' (position 0)   — a track began playing (song change, repeat,
 *     resume-after-stop). Arms a FRESH session (replay re-arm).
 *   • 'stop' (position = abandon playhead) — the previous track ended
 *     early (skip) or finished. Accrues, then abandons: a skip that never
 *     crossed is not a scrobble.
 *   • 'pause' / 'unpause' (position = playhead) — transport pauses.
 *   • submission: true (position = track duration) — Feishin's own
 *     listen-time accounting crossed ITS threshold (default 75% or 240 s,
 *     which dominates our min(50%, 240 s) for every track length).
 *
 * ACTUAL PLAYED TIME from a sparse stream: every playing-context sample
 * accrues dt = min(positionDelta, wallClockDelta) — the playhead's real
 * advance, bounded by the wall time elapsed since the previous report
 * (the #187 stall-guard principle applied cross-signal: played time can
 * never exceed real time, so a forward seek without listening accrues
 * only the seconds it really took). Pauses accrue nothing; seeks
 * backwards clamp to zero; the delta is fed to the tracker in
 * MAX_TICK_MS chunks so the ported tick cap keeps its meaning.
 *
 * REPLAY RE-ARMING: a 'start' always opens a fresh session. A submission
 * arriving for an ALREADY-FIRED session means Feishin re-armed its own
 * flag (repeat / restart-from-zero — the only paths that re-submit for
 * the same unique track id): that is a deliberate replay, and it opens a
 * fresh session too.
 *
 * THE FIRE PATH (mirrors the web queueStore): recordTally (the local
 * counter — STRICTLY on threshold crossing, never on skips) →
 * applyPlayToSnapshot (live playCount/lastPlayedAt bump so Home shelves
 * and FREQUENT/RECENT sorts refresh without a snapshot reload) →
 * outbox.push → drain. Scrobble failure never blocks playback.
 */

import type { LibrarySnapshot } from '../library';
import type { PersistedScrobbleSession } from './queue-persistence';

import { applyPlayToSnapshot, recordTally, type TallyStorage } from './play-tally';
import {
    createOutboxStore,
    MAX_TICK_MS,
    PlaySessionTracker,
    type ScrobbleEvent,
    ScrobbleOutbox,
    type ScrobbleSession,
} from './scrobbler';

export interface ScrobbleBridgeOptions {
    clock?: () => number;
    /** The live snapshot (playCount bumps); null when not loaded. */
    getSnapshot?: () => LibrarySnapshot | null;
    outboxKey?: string;
    /** Resolved-rung lookup (stream-source's lastRungForVideo in prod). */
    resolveRung?: (videoId: string) => null | string;
    tallyStorage?: TallyStorage;
}

/** One controller scrobble report (ScrobbleQuery, position in ms). */
export interface ScrobbleReport {
    event?: 'pause' | 'start' | 'stop' | 'unpause';
    id: string;
    /** Playhead / reported position, milliseconds (non-Jellyfin units). */
    position?: number;
    submission: boolean;
}

/** The cloud send adapters (the controller re-binds these per call). */
export interface ScrobbleSendAdapters {
    /** False while offline / signed out — draining waits, entries survive. */
    canSend: () => boolean;
    /** recordPlayEventStrict bound to the live session. */
    send: (event: ScrobbleEvent) => Promise<boolean>;
}

/** The track facts the tracker needs (from the library snapshot row). */
export interface ScrobbleTrackFacts {
    album: string;
    artist: string;
    durationMs: number;
    title: string;
    /** The stream videoId — used to attach the resolved rung at fire time. */
    videoId: null | string;
}

const OUTBOX_KEY = 'streamify.feishin.scrobble.v1';

export class StreamifyScrobbleBridge {
    /** The live session (queue persistence reads this at save time). */
    get session(): null | ScrobbleSession {
        return this.tracker.current;
    }
    /** Latest adapters (re-bound by every controller call). */
    private adapters: null | ScrobbleSendAdapters = null;
    /** Playhead baseline for the live session (ms); null when none. */
    private baselineMs: null | number = null;
    private readonly clock: () => number;
    private readonly getSnapshot: () => LibrarySnapshot | null;
    /** Wall-clock of the previous report; null until the first sample. */
    private lastReportAt: null | number = null;

    private readonly outbox: ScrobbleOutbox;
    /** The transport was paused at the last report. */
    private paused = false;
    private readonly resolveRung: (videoId: string) => null | string;
    private readonly tallyStorage: TallyStorage;

    private readonly tracker = new PlaySessionTracker();

    constructor(options: ScrobbleBridgeOptions = {}) {
        this.clock = options.clock ?? Date.now;
        this.resolveRung = options.resolveRung ?? (() => null);
        this.getSnapshot = options.getSnapshot ?? (() => null);
        this.tallyStorage = options.tallyStorage ?? defaultTallyStorage();
        this.outbox = new ScrobbleOutbox(createOutboxStore(options.outboxKey ?? OUTBOX_KEY));
    }

    /** The restore path: continue a persisted session (getPlayQueue). */
    continueSession(restored: null | PersistedScrobbleSession, positionMs: number): void {
        this.baselineMs = null;
        this.lastReportAt = null;
        this.paused = false;
        if (restored === null) {
            this.tracker.abandon();
            return;
        }
        this.tracker.continue(restored);
        // Seed the playhead baseline from the restored offset so the first
        // post-restore sample accrues from where the playhead actually is.
        this.baselineMs = positionMs >= 0 ? positionMs : 0;
    }

    /** Drain the outbox with the latest adapters (never throws). */
    async drain(adapters?: ScrobbleSendAdapters): Promise<number> {
        if (adapters !== undefined) this.adapters = adapters;
        const bound = this.adapters;
        if (bound === null) return 0;
        return this.outbox.drain(bound.send, bound.canSend);
    }

    /**
     * Handle one playback report. Synchronous by contract (the controller
     * endpoint returns immediately; the cloud send rides the outbox).
     */
    handleReport(
        report: ScrobbleReport,
        facts: ScrobbleTrackFacts,
        adapters: ScrobbleSendAdapters,
    ): void {
        this.adapters = adapters;
        const now = this.clock();
        const position = normalizePosition(report.position);

        if (report.submission) {
            this.handleSubmission(report.id, position, now, facts);
        } else {
            switch (report.event) {
                case 'pause':
                    this.accrue(report.id, position, now, facts);
                    this.baselineMs = position ?? this.baselineMs;
                    this.lastReportAt = now;
                    this.paused = true;
                    break;
                case 'start':
                    // A deliberate (re)start — always a FRESH, re-armed session.
                    this.tracker.start(report.id, now);
                    this.baselineMs = position ?? 0;
                    this.lastReportAt = now;
                    this.paused = false;
                    break;
                case 'stop':
                    // Accrue what really played, then end the session — a skip
                    // that never crossed the threshold is not a scrobble.
                    this.accrue(report.id, position, now, facts);
                    if (this.tracker.isFor(report.id)) {
                        this.tracker.abandon();
                        this.baselineMs = null;
                        this.lastReportAt = null;
                        this.paused = false;
                    }
                    break;
                case 'unpause':
                default:
                    // Resume / bare progress sample. While paused the playhead
                    // may have moved (a seek while paused) — re-baseline only.
                    if (this.paused) {
                        this.baselineMs = position ?? this.baselineMs;
                    } else {
                        this.accrue(report.id, position, now, facts);
                        this.baselineMs = position ?? this.baselineMs;
                    }
                    this.lastReportAt = now;
                    this.paused = false;
                    break;
            }
        }

        // Opportunistic drain (the web spine drains on online/visibility/
        // heartbeat; the sparse feishin stream makes every report a drain
        // point — a no-op when the outbox is empty or offline).
        void this.drain().catch(() => undefined);
    }

    /** Pending outbox entries (diagnostics / tests). */
    pendingScrobbles() {
        return this.outbox.pending();
    }

    /**
     * The queue-persistence projection of the live session: included only
     * when it belongs to the track the queue names as current.
     */
    sessionForQueue(currentSongId: null | string): null | PersistedScrobbleSession {
        const session = this.tracker.current;
        if (session === null || currentSongId === null || session.trackId !== currentSongId) {
            return null;
        }
        return {
            playedMs: session.playedMs,
            scrobbled: session.fired,
            startedAt: session.startedAt,
            trackId: session.trackId,
        };
    }

    // -------------------------------------------------------------------------
    // The submission path
    // -------------------------------------------------------------------------

    private accrue(
        trackId: string,
        position: null | number,
        now: number,
        facts: ScrobbleTrackFacts,
    ): void {
        if (position === null || this.baselineMs === null) {
            this.lastReportAt = now;
            return;
        }
        const positionDelta = position - this.baselineMs;
        if (positionDelta <= 0) {
            // Backwards (a seek back): clamp to zero — the session keeps its
            // accumulated time, per the #187 seek ruling.
            this.lastReportAt = now;
            return;
        }
        // Wall-clock bound: actual played time can never exceed real time
        // since the previous report (null = first sample after a fresh
        // arm/restore — trust the playhead once).
        const wallDelta =
            this.lastReportAt === null ? positionDelta : Math.max(0, now - this.lastReportAt);
        const dt = Math.min(positionDelta, wallDelta);
        this.lastReportAt = now;

        // Feed the tracker in MAX_TICK_MS chunks so the ported per-tick cap
        // keeps its meaning for sparse, large deltas.
        let remaining = dt;
        while (remaining > 0) {
            const chunk = Math.min(remaining, MAX_TICK_MS);
            const event = this.tracker.accrueTrack(trackId, chunk, now, {
                album: facts.album,
                artist: facts.artist,
                durationMs: facts.durationMs,
                rung: null,
                title: facts.title,
            });
            if (event !== null) {
                this.fire(event, facts);
                return; // fired — the session is spent for this listen
            }
            remaining -= chunk;
        }
    }

    // -------------------------------------------------------------------------
    // Accrual (position delta, wall-clock bounded, tick-chunked)
    // -------------------------------------------------------------------------

    private fire(event: ScrobbleEvent, facts: ScrobbleTrackFacts): void {
        const fired: ScrobbleEvent = {
            ...event,
            rung: facts.videoId !== null ? this.resolveRung(facts.videoId) : null,
        };

        // The local counter increments STRICTLY here — on the threshold
        // crossing — never on starts or skips.
        recordTally(
            {
                artist: fired.artist,
                durationPlayedSec: fired.playedMs / 1000,
                title: fired.title,
                trackSig: fired.trackId,
            },
            new Date(this.clock()),
            this.tallyStorage,
        );

        const snapshot = this.getSnapshot();
        if (snapshot !== null) {
            applyPlayToSnapshot(snapshot, fired.trackId, new Date(this.clock()));
        }

        this.outbox.push(fired);
        void this.drain().catch(() => undefined);
    }

    // -------------------------------------------------------------------------
    // The fire path (tally + live snapshot + outbox + drain)
    // -------------------------------------------------------------------------

    private handleSubmission(
        trackId: string,
        position: null | number,
        now: number,
        facts: ScrobbleTrackFacts,
    ): void {
        if (!this.tracker.isFor(trackId)) {
            // No live session (restored mid-track, missed the start): a
            // submission still proves a deliberate listen — arm fresh.
            this.tracker.start(trackId, now);
            this.baselineMs = 0;
            this.lastReportAt = null;
            this.paused = false;
        } else if (this.tracker.firedFor(trackId)) {
            // Feishin re-armed its own flag (repeat / restart-from-zero):
            // a deliberate replay re-arms ours too.
            this.tracker.start(trackId, now);
            this.baselineMs = 0;
            this.lastReportAt = null;
            this.paused = false;
        }
        this.accrue(trackId, position, now, facts);
        this.lastReportAt = now;
    }
}

function defaultTallyStorage(): TallyStorage {
    // Local mirror of play-tally's default storage (kept inline so this
    // module never imports localStorage at module scope in node tests).
    const KEY = 'streamify.playtally.v1';
    return {
        read(): null | string {
            try {
                return localStorage.getItem(KEY);
            } catch {
                return null;
            }
        },
        write(value: string): void {
            try {
                localStorage.setItem(KEY, value);
            } catch {
                /* best-effort by contract */
            }
        },
    };
}

// -------------------------------------------------------------------------
// Module wiring
// -------------------------------------------------------------------------

/** Normalize a reported position into a finite non-negative ms value. */
function normalizePosition(position: null | number | undefined): null | number {
    if (typeof position !== 'number' || !Number.isFinite(position) || position < 0) {
        return null;
    }
    return position;
}

/** The singleton the controller endpoints wire into. */
let bridgeInstance: null | StreamifyScrobbleBridge = null;

export function getStreamifyScrobbleBridge(
    options?: ScrobbleBridgeOptions,
): StreamifyScrobbleBridge {
    if (bridgeInstance === null) {
        bridgeInstance = new StreamifyScrobbleBridge(options);
    }
    return bridgeInstance;
}

/** Test seam: drop the singleton (vitest isolation). */
export function resetStreamifyScrobbleBridge(): void {
    bridgeInstance = null;
}
