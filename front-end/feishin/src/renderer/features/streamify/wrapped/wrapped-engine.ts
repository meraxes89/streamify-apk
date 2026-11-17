/**
 * features/streamify/wrapped/wrapped-engine.ts — W3-B: the pure Wrapped
 * extension engine (listening rhythm, streaks, discovery graduation, and
 * the share-card data contract).
 *
 * Everything is a function of (events, window, now) over the SAME ledger
 * P4-A aggregates — the playTally-governed threshold crossings recorded by
 * listening-ledger.ts (min(half-duration, 4 min); never starts, never
 * skips). No parallel play counter is introduced anywhere: rhythm, streak
 * and graduation are additional folds over the very same LedgerEvent rows.
 *
 * DETERMINISM RULES (pinned by w3b-wrapped.test.ts):
 *   - peak hour / peak weekday: argmax with earliest-index tie-breaking;
 *   - streaks: local calendar days with at least one qualifying play,
 *     longest run + the run ending today-or-yesterday;
 *   - discovery graduation: a track whose FIRST-EVER ledger row carries a
 *     recommendation origin (automix / discover-mix) and which has ≥ 2
 *     total plays — "introduced by the mix, became a repeat". First plays
 *     outside the window demote later plays to repeats (the P4-A rule,
 *     applied to origins);
 *   - every stat degrades to an honest zero/null state on an empty ledger
 *     — never NaN, never a broken chart.
 */

import {
    eventsInWindow,
    getWrappedStats,
    windowStart,
    type WrappedStats,
    type WrappedWindow,
} from '/@/renderer/api/streamify/wrapped/aggregates';
import { getLedger, type LedgerEvent } from '/@/renderer/api/streamify/wrapped/listening-ledger';

/** The recommendation lanes that count as "discovered for you". */
export const WRAPPED_RECOMMENDATION_ORIGINS: ReadonlySet<string> = new Set([
    'automix',
    'discover-mix',
]);

// ---------------------------------------------------------------------------
// Listening rhythm (peak hour / day-of-week distribution)
// ---------------------------------------------------------------------------

export interface DiscoveryGraduation {
    /** Tracks first discovered via a recommendation lane that now repeat. */
    graduated: GraduatedDiscovery[];
    top: GraduatedDiscovery | null;
}

export interface GraduatedDiscovery {
    artist: string;
    count: number;
    origin: string;
    title: string;
}

// ---------------------------------------------------------------------------
// Listening streaks (consecutive local days with ≥ 1 qualifying play)
// ---------------------------------------------------------------------------

export interface ListeningStreak {
    /** The run of consecutive listening days ending today or yesterday. */
    currentDays: number;
    /** The longest run of consecutive listening days ever recorded. */
    longestDays: number;
}

export interface WrappedExtended {
    discovery: DiscoveryGraduation;
    rhythm: WrappedRhythm;
    stats: WrappedStats;
    streak: ListeningStreak;
}

export interface WrappedRhythm {
    /** Plays per local weekday, Sunday = 0 (matches Date.getDay). */
    byWeekday: number[];
    /** argmax over the window's hour histogram; null when no plays. */
    peakHour: null | number;
    /** argmax over byWeekday; null when no plays. */
    peakWeekday: null | number;
}

export interface WrappedShareCardData {
    /** Total verified minutes + unit ("1,240 minutes listened"). */
    minutesLabel: string;
    /** "{{count}} verified plays" — pre-rendered by the caller. */
    playsLabel: string;
    /** null when the longest streak is under 2 days. */
    streakLabel: null | string;
    /** Top 3 artist names joined with ' · '. */
    topArtistsLabel: string;
    topTracks: Array<{ artist: string; plays: number; rank: number; title: string }>;
    windowLabel: string;
}

// ---------------------------------------------------------------------------
// Discovery graduation (Discover Mix / Automix → repeat plays)
// ---------------------------------------------------------------------------

export function computeDiscoveryGraduation(
    allEvents: LedgerEvent[],
    window: WrappedWindow,
    now: number,
): DiscoveryGraduation {
    const start = windowStart(window, now);
    const perTrack = new Map<
        string,
        { artist: string; count: number; firstTs: number; origin: null | string; title: string }
    >();
    // The merged ledger is ts-ascending; the first row seen per track is the
    // first-ever counted play (the P4-A discovery walk, extended to origins).
    for (const e of allEvents) {
        const known = perTrack.get(e.trackId);
        if (known === undefined) {
            perTrack.set(e.trackId, {
                artist: e.artist,
                count: 1,
                firstTs: e.ts,
                origin: e.origin ?? null,
                title: e.title,
            });
        } else {
            known.count += 1;
        }
    }
    const graduated: GraduatedDiscovery[] = [];
    for (const stats of perTrack.values()) {
        if (stats.origin === null) continue; // organic first play — not a discovery
        if (!WRAPPED_RECOMMENDATION_ORIGINS.has(stats.origin)) continue;
        if (stats.count < 2) continue; // never graduated into a repeat
        if (start !== null && stats.firstTs < start) continue; // discovered before this window
        graduated.push({
            artist: stats.artist,
            count: stats.count,
            origin: stats.origin,
            title: stats.title,
        });
    }
    graduated.sort(
        (a, b) => b.count - a.count || (a.title < b.title ? -1 : a.title > b.title ? 1 : 0),
    );
    return { graduated, top: graduated[0] ?? null };
}

export function computeListeningStreak(allEvents: LedgerEvent[], now: number): ListeningStreak {
    const days = new Set<string>();
    for (const e of allEvents) days.add(localDayKey(e.ts));
    if (days.size === 0) return { currentDays: 0, longestDays: 0 };

    const keys = [...days]
        .map((key) => {
            const [y, m, d] = key.split('-').map(Number);
            return { d, key, m, y };
        })
        .sort((a, b) => a.y - b.y || a.m - b.m || a.d - b.d);

    let longest = 1;
    let run = 1;
    for (let i = 1; i < keys.length; i += 1) {
        run = consecutiveDays(keys[i - 1], keys[i]) ? run + 1 : 1;
        if (run > longest) longest = run;
    }

    // Current streak: only a run ENDING today or yesterday is still alive
    // (listening today, or last listened yesterday). Anything older broke.
    const nowDay = new Date(now);
    const todayKey = localDayKey(nowDay.getTime());
    const yesterdayKey = localDayKey(nowDay.getTime() - 86_400_000);
    if (!days.has(todayKey) && !days.has(yesterdayKey)) {
        return { currentDays: 0, longestDays: longest };
    }

    let current = 1;
    for (let i = keys.length - 1; i > 0; i -= 1) {
        if (!consecutiveDays(keys[i - 1], keys[i])) break;
        current += 1;
    }
    return { currentDays: current, longestDays: Math.max(longest, current) };
}

export function computeWrappedRhythm(
    allEvents: LedgerEvent[],
    window: WrappedWindow,
    now: number,
): WrappedRhythm {
    const inWindow = eventsInWindow(allEvents, window, now);
    const byWeekday = new Array<number>(7).fill(0);
    const byHour = new Array<number>(24).fill(0);
    for (const e of inWindow) {
        const at = new Date(e.ts);
        byWeekday[at.getDay()] += 1;
        byHour[at.getHours()] += 1;
    }
    const argmax = (slots: number[]): null | number => {
        let best: null | number = null;
        for (let i = 0; i < slots.length; i += 1) {
            if (slots[i] > 0 && (best === null || slots[i] > slots[best])) best = i;
        }
        return best;
    };
    return { byWeekday, peakHour: argmax(byHour), peakWeekday: argmax(byWeekday) };
}

// ---------------------------------------------------------------------------
// The share-card data contract (pure — the canvas layer stays dumb)
// ---------------------------------------------------------------------------

export function formatWrappedShareCardData(input: {
    minutesWord: string;
    playsTemplate: (count: number) => string;
    stats: WrappedStats;
    streak: ListeningStreak;
    streakTemplate: (days: number) => string;
    windowLabel: string;
}): WrappedShareCardData {
    const { stats, streak } = input;
    const minutes = Math.floor(stats.totalSeconds / 60);
    const topTracks = stats.topTracks.slice(0, 5).map((track, i) => ({
        artist: track.artist,
        plays: track.count,
        rank: i + 1,
        title: track.title,
    }));
    return {
        minutesLabel: `${minutes.toLocaleString()} ${input.minutesWord}`,
        playsLabel: input.playsTemplate(stats.count),
        streakLabel: streak.longestDays >= 2 ? input.streakTemplate(streak.longestDays) : null,
        topArtistsLabel: stats.topArtists
            .slice(0, 3)
            .map((artist) => artist.name)
            .join(' · '),
        topTracks,
        windowLabel: input.windowLabel,
    };
}

export function getWrappedExtended(
    window: WrappedWindow,
    now: number = Date.now(),
): WrappedExtended {
    const stats = getWrappedStats(window, now);
    const ledger = getLedger();
    return {
        discovery: computeDiscoveryGraduation(ledger, window, now),
        rhythm: computeWrappedRhythm(ledger, window, now),
        stats,
        streak: computeListeningStreak(ledger, now),
    };
}

// ---------------------------------------------------------------------------
// The route's single query entry (mirrors getWrappedStats's snapshot seeding)
// ---------------------------------------------------------------------------

function consecutiveDays(
    a: { d: number; m: number; y: number },
    b: { d: number; m: number; y: number },
): boolean {
    return new Date(b.y, b.m, b.d).getTime() - new Date(a.y, a.m, a.d).getTime() === 86_400_000;
}

/** Local calendar day key (y/m/d — no DST ambiguity inside one zone). */
function localDayKey(ts: number): string {
    const d = new Date(ts);
    return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
}
