/**
 * lib/playTally.ts — P3a: the local listening tally (Wrapped/chronotype data).
 *
 * The Android app's stats live in Supabase (user_play_events) + local
 * telemetry; the web P3a surfaces honest numbers from a local mirror
 * (every play event also tallies here) + the cloud likes count. P3b
 * replaces the read side with the real stats RPC.
 */

export interface PlayTally {
  /** Total seconds actually played. */
  seconds: number;
  /** Completed-ish track transitions (the same trigger as the cloud event). */
  tracks: number;
  /** Play events per hour-of-day (24 slots) — the chronotype histogram. */
  byHour: number[];
  /** trackSig → rotation count. */
  topTracks: Record<string, { title: string; artist: string; count: number }>;
}

const KEY = 'streamify.playtally.v1';

function empty(): PlayTally {
  return { seconds: 0, tracks: 0, byHour: new Array<number>(24).fill(0), topTracks: {} };
}

export function readTally(): PlayTally {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw === null) return empty();
    const parsed = JSON.parse(raw) as PlayTally;
    if (!Array.isArray(parsed.byHour) || parsed.byHour.length !== 24) return empty();
    return {
      seconds: Number(parsed.seconds) || 0,
      tracks: Number(parsed.tracks) || 0,
      byHour: parsed.byHour,
      topTracks: typeof parsed.topTracks === 'object' && parsed.topTracks !== null ? parsed.topTracks : {},
    };
  } catch {
    return empty();
  }
}

export function recordTally(event: {
  trackSig: string;
  title: string;
  artist: string;
  durationPlayedSec: number;
}): void {
  try {
    const t = readTally();
    t.seconds += Math.max(0, Math.round(event.durationPlayedSec));
    t.tracks += 1;
    const hour = new Date().getHours();
    t.byHour[hour] += 1;
    const prev = t.topTracks[event.trackSig];
    t.topTracks[event.trackSig] = {
      title: event.title,
      artist: event.artist,
      count: (prev?.count ?? 0) + 1,
    };
    localStorage.setItem(KEY, JSON.stringify(t));
  } catch {
    /* private mode — the tally is best-effort */
  }
}

export interface Chronotype {
  title: string;
  color: string;
  bpm: number;
}

/** The Android UserProfileScreen's chronotype bands, same thresholds. */
export function chronotype(tally: PlayTally): Chronotype {
  // Peak hour = the histogram's argmax (falls back to "now" when empty).
  let hour = new Date().getHours();
  let best = -1;
  for (let h = 0; h < 24; h += 1) {
    if (tally.byHour[h] > best) {
      best = tally.byHour[h];
      hour = h;
    }
  }
  if (hour >= 21 || hour <= 4) return { title: 'The Night Explorer 🦉', color: '#8B5CF6', bpm: 124 };
  if (hour <= 11) return { title: 'The Morning Motor 🌅', color: '#F59E0B', bpm: 124 };
  if (hour <= 16) return { title: 'The Afternoon Groover ☀️', color: '#3B82F6', bpm: 124 };
  return { title: 'The Evening Unwinder 🌆', color: '#EC4899', bpm: 124 };
}

export function topRotationCount(tally: PlayTally): number {
  let max = 0;
  for (const sig of Object.keys(tally.topTracks)) {
    max = Math.max(max, tally.topTracks[sig].count);
  }
  return max;
}
