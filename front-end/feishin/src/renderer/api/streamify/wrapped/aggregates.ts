/**
 * api/streamify/wrapped/aggregates.ts — P4-A: the pure aggregation layer
 * behind Wrapped. No I/O, no clocks — everything is a function of
 * (events, window, now), so the tests pin the exact semantics.
 *
 * DISCOVERY (ADR'd): a play is a DISCOVERY when it is the first-ever
 * counted play of its track in the FULL merged ledger (a track first
 * played before the window and replayed inside it is a REPEAT). The
 * ratio over a window = discoveries-in-window / plays-in-window; null
 * when the window has no plays (the UI shows the empty state, never a
 * NaN percentage).
 */

import { currentStreamifyLibrary } from '../engine';
import { getLedger, type LedgerEvent, seedFromSnapshot } from './listening-ledger';

export type WrappedWindow = '7d' | '30d' | 'all' | 'ytd';

export const WRAPPED_WINDOWS: readonly WrappedWindow[] = ['7d', '30d', 'ytd', 'all'];

export interface WrappedStats {
    byHour: number[];
    /** Plays in window (0 → the UI renders zero states). */
    count: number;
    /** null when count === 0 — never NaN. */
    discoveryRatio: null | number;
    /** Oldest event in window (the "since" label); null when empty. */
    firstAt: null | number;
    topArtists: WrappedTopArtist[];
    topGenres: WrappedTopGenre[];
    topTracks: WrappedTopTrack[];
    totalSeconds: number;
    window: WrappedWindow;
}

export interface WrappedTopArtist {
    count: number;
    name: string;
    seconds: number;
    tracks: number;
}

export interface WrappedTopGenre {
    count: number;
    name: string;
}

export interface WrappedTopTrack {
    artist: string;
    count: number;
    id: string;
    seconds: number;
    title: string;
}

const TOP_N = 10;

export function computeWrappedStats(
    allEvents: LedgerEvent[],
    window: WrappedWindow,
    now: number,
): WrappedStats {
    const inWindow = eventsInWindow(allEvents, window, now);
    const byHour = new Array<number>(24).fill(0);
    let totalSeconds = 0;
    const perTrack = new Map<
        string,
        { artist: string; count: number; seconds: number; title: string }
    >();
    const perArtist = new Map<string, { count: number; seconds: number; tracks: Set<string> }>();
    const perGenre = new Map<string, number>();

    for (const e of inWindow) {
        byHour[new Date(e.ts).getHours()] += 1;
        totalSeconds += e.playedSec;

        const track = perTrack.get(e.trackId) ?? {
            artist: e.artist,
            count: 0,
            seconds: 0,
            title: e.title,
        };
        track.count += 1;
        track.seconds += e.playedSec;
        perTrack.set(e.trackId, track);

        const artist = perArtist.get(e.artist) ?? { count: 0, seconds: 0, tracks: new Set() };
        artist.count += 1;
        artist.seconds += e.playedSec;
        artist.tracks.add(e.trackId);
        perArtist.set(e.artist, artist);

        for (const g of e.genres) {
            perGenre.set(g, (perGenre.get(g) ?? 0) + 1);
        }
    }

    const topTracks: WrappedTopTrack[] = [...perTrack.entries()]
        .map(([id, t]) => ({
            artist: t.artist,
            count: t.count,
            id,
            seconds: t.seconds,
            title: t.title,
        }))
        .sort((a, b) => b.count - a.count || b.seconds - a.seconds)
        .slice(0, TOP_N);

    const topArtists: WrappedTopArtist[] = [...perArtist.entries()]
        .map(([name, a]) => ({ count: a.count, name, seconds: a.seconds, tracks: a.tracks.size }))
        .sort((a, b) => b.count - a.count || b.seconds - a.seconds)
        .slice(0, TOP_N);

    const topGenres: WrappedTopGenre[] = [...perGenre.entries()]
        .map(([name, count]) => ({ count, name }))
        .sort((a, b) => b.count - a.count)
        .slice(0, TOP_N);

    const discoveries = discoveriesInWindow(allEvents, window, now);
    return {
        byHour,
        count: inWindow.length,
        discoveryRatio:
            inWindow.length === 0 ? null : Math.round((discoveries / inWindow.length) * 100) / 100,
        firstAt: inWindow.length > 0 ? inWindow[0].ts : null,
        topArtists,
        topGenres,
        topTracks,
        totalSeconds,
        window,
    };
}

/**
 * Discovery count over a window: walk the FULL ledger in ts order (it is
 * sorted ascending); an event contributes when it is in-window AND is the
 * first-ever event of its track. First-ever events outside the window
 * mark the track as known, so later in-window plays of it are repeats.
 */
export function discoveriesInWindow(
    allEvents: LedgerEvent[],
    window: WrappedWindow,
    now: number,
): number {
    const start = windowStart(window, now);
    const seen = new Set<string>();
    let discoveries = 0;
    for (const e of allEvents) {
        const inWindow = start === null ? true : e.ts >= start;
        if (!seen.has(e.trackId)) {
            if (inWindow) discoveries += 1;
            seen.add(e.trackId);
        }
    }
    return discoveries;
}

export function eventsInWindow(
    events: LedgerEvent[],
    window: WrappedWindow,
    now: number,
): LedgerEvent[] {
    const start = windowStart(window, now);
    if (start === null) return events;
    return events.filter((e) => e.ts >= start);
}

/** The query entry (the wrapped route calls this per window switch). */
export function getWrappedStats(window: WrappedWindow, now: number = Date.now()): WrappedStats {
    const snapshot = currentStreamifyLibrary();
    if (snapshot !== null) seedFromSnapshot(snapshot);
    return computeWrappedStats(getLedger(), window, now);
}

/** Ledger introspection for the /debug panel. */
export function ledgerDebugInfo(): { cloud: number; local: number; total: number } {
    const all = getLedger();
    const local = all.filter((e) => e.source === 'local').length;
    return { cloud: all.length - local, local, total: all.length };
}

export function windowStart(window: WrappedWindow, now: number): null | number {
    switch (window) {
        case '7d':
            return now - 7 * 24 * 3600 * 1000;
        case '30d':
            return now - 30 * 24 * 3600 * 1000;
        case 'all':
            return null;
        case 'ytd': {
            const d = new Date(now);
            return Date.parse(`${d.getUTCFullYear()}-01-01T00:00:00.000Z`);
        }
    }
}
