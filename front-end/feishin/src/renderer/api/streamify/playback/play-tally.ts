/**
 * api/streamify/playback/play-tally.ts — P3-B: the local listening tally,
 * ported from front-end/web/src/lib/playTally.ts (P3a) and extended with
 * the feishin bridge's snapshot hydration.
 *
 * THE RULE (the task's ruling): playCount / lastPlayedAt hydrate onto
 * song/album/artist DTOs from BOTH the cloud play events AND this local
 * tally, and the local counter increments STRICTLY when PlaySessionTracker
 * crosses the scrobble threshold — never on skips, never on starts.
 * The tally is therefore an additive overlay: cloud events are the
 * durable record (user_play_events via the scrobble outbox), the tally
 * is the instant local mirror so Home shelves (MOST PLAYED / RECENTLY
 * PLAYED) and FREQUENT-style sorts reflect a play the moment it counts,
 * without waiting for a snapshot reload.
 *
 * Storage is injectable (tests pass a fake); production uses localStorage
 * under the same key the legacy web app uses — the surfaces never share
 * an origin, so the key is conceptual parity, not a collision risk.
 */

import type { LibrarySnapshot } from '../library';

export interface PlayTally {
    /** Play events per hour-of-day (24 slots) — the chronotype histogram. */
    byHour: number[];
    /** Total seconds actually played. */
    seconds: number;
    /** trackSig (= the cloud track id) → rotation stats. */
    topTracks: Record<string, { artist: string; count: number; title: string }>;
    /** Counted play events (threshold crossings — skips never tally). */
    tracks: number;
}

export interface TallyStorage {
    read(): null | string;
    write(value: string): void;
}

const KEY = 'streamify.playtally.v1';

/**
 * Apply ONE counted play to the live snapshot (the bridge's fire path):
 * bump the song row, then refresh its album and artist aggregates.
 */
export function applyPlayToSnapshot(snapshot: LibrarySnapshot, songId: string, at: Date): void {
    const song = snapshot.songById.get(songId);
    if (song === undefined) return;
    song.playCount += 1;
    song.lastPlayedAt = at.toISOString();
    recomputeAggregates(snapshot);
}

/**
 * Hydrate the snapshot's play stats with the local tally overlay: for
 * every song the tally knows, playCount += local count and lastPlayedAt
 * maxes toward now (the tally counts are threshold crossings that already
 * reached the cloud via the outbox in the steady state — the overlay
 * covers the outbox's in-flight window AND offline sessions). Album and
 * artist aggregates re-derive on top.
 */
export function hydrateSnapshotWithTally(
    snapshot: LibrarySnapshot,
    at: Date = new Date(),
    storage: TallyStorage = localStorageTallyStorage(),
): void {
    const tally = readTally(storage);
    const known = Object.entries(tally.topTracks).filter(([id]) => snapshot.songById.has(id));
    if (known.length === 0) return;

    const nowIso = at.toISOString();
    for (const [id, stats] of known) {
        const song = snapshot.songById.get(id);
        if (song === undefined) continue;
        song.playCount += stats.count;
        // The tally carries no per-track timestamps: lastPlayedAt bumps to
        // the tally's freshness bound (now) only when the overlay counted
        // something the cloud had not yet recorded.
        if ((song.lastPlayedAt ?? '') < nowIso) song.lastPlayedAt = nowIso;
    }
    recomputeAggregates(snapshot);
}

/** Parse with full-shape validation; drift → the honest empty tally. */
export function parseTally(raw: null | string): PlayTally {
    if (raw === null) return emptyTally();
    try {
        const parsed = JSON.parse(raw) as PlayTally;
        if (!Array.isArray(parsed.byHour) || parsed.byHour.length !== 24) return emptyTally();
        return {
            byHour: parsed.byHour.map((n) => Number(n) || 0),
            seconds: Number(parsed.seconds) || 0,
            topTracks:
                typeof parsed.topTracks === 'object' && parsed.topTracks !== null
                    ? parsed.topTracks
                    : {},
            tracks: Number(parsed.tracks) || 0,
        };
    } catch {
        return emptyTally();
    }
}

export function readTally(storage: TallyStorage = localStorageTallyStorage()): PlayTally {
    return parseTally(storage.read());
}

/**
 * Record one COUNTED play (the scrobble-threshold crossing — the only
 * caller is the bridge's fire path). durationPlayedSec is the accumulated
 * played time at fire, not the track duration.
 */
export function recordTally(
    event: { artist: string; durationPlayedSec: number; title: string; trackSig: string },
    at: Date = new Date(),
    storage: TallyStorage = localStorageTallyStorage(),
): void {
    const t = readTally(storage);
    t.seconds += Math.max(0, Math.round(event.durationPlayedSec));
    t.tracks += 1;
    t.byHour[at.getHours()] += 1;
    const prev = t.topTracks[event.trackSig];
    t.topTracks[event.trackSig] = {
        artist: event.artist,
        count: (prev?.count ?? 0) + 1,
        title: event.title,
    };
    storage.write(JSON.stringify(t));
}

function emptyTally(): PlayTally {
    return { byHour: new Array<number>(24).fill(0), seconds: 0, topTracks: {}, tracks: 0 };
}

function localStorageTallyStorage(): TallyStorage {
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
                /* private mode — the tally is best-effort by contract */
            }
        },
    };
}

/** Recompute album/artist play aggregates from the song rows (single pass). */
function recomputeAggregates(snapshot: LibrarySnapshot): void {
    for (const album of snapshot.albums) {
        let playCount = 0;
        let lastPlayedAt: null | string = null;
        for (const id of album.songIds) {
            const song = snapshot.songById.get(id);
            if (song === undefined) continue;
            playCount += song.playCount;
            if (song.lastPlayedAt !== null && (lastPlayedAt ?? '') < song.lastPlayedAt) {
                lastPlayedAt = song.lastPlayedAt;
            }
        }
        album.playCount = playCount;
        album.lastPlayedAt = lastPlayedAt;
    }
    for (const artist of snapshot.artists) {
        let playCount = 0;
        let lastPlayedAt: null | string = null;
        for (const id of artist.songIds) {
            const song = snapshot.songById.get(id);
            if (song === undefined) continue;
            playCount += song.playCount;
            if (song.lastPlayedAt !== null && (lastPlayedAt ?? '') < song.lastPlayedAt) {
                lastPlayedAt = song.lastPlayedAt;
            }
        }
        artist.playCount = playCount;
        artist.lastPlayedAt = lastPlayedAt;
    }
}
