/**
 * lib/wrappedStore.ts — W-W wrapped port: the StatsWrappedScreen store
 * (the YtStatsTelemetryEngine port, P2 discipline).
 *
 * The APK computes Wrapped from on-device telemetry (listening seconds,
 * per-track play counts, BPM from track metadata, the likes/library
 * corpus). The web's honest sources today:
 *   • the LOCAL tally (lib/playTally.ts — seconds, per-track rotations,
 *     the hour-of-day histogram; every play event lands here);
 *   • the CLOUD likes list (sessionStore — Supabase, cross-device);
 *   • the signed-in user's own user_play_events rows (cloud mirror of
 *     the same events the APK syncs).
 *
 * Engine-side gaps (NO fakes, store slots ready — the facade need is
 * listed in the PR for A's tail):
 *   • averageBpm / the BPM persona: track BPM lives in the taste-engine
 *     metadata the wasm facade does not expose yet. The persona card
 *     therefore derives from the CHRONOTYPE histogram (real data) and
 *     the BPM slot renders an explicit honest placeholder.
 *   • topGenres: the APK keyword model (same genreKeywords, same
 *     first-match-wins, same fallback) over the real web corpus
 *     (likes + rotations) — a true port of the derivation, not a stub.
 *
 * MERGE DISCIPLINE: every play lands in both the local tally and the
 * cloud log, so the two sources merge MONOTONICALLY (max per metric)
 * — never summed, never double-counted; cloud adds cross-device plays,
 * local covers POST failures.
 */

import { create } from 'zustand';
import { chronotype, readTally, type PlayTally } from './playTally';
import { fetchOwnPlayEvents, type OwnPlayEventRow } from './supabase';

export interface WrappedTopTrack {
  trackSig: string;
  title: string;
  artist: string;
  /** Rotation count (local tally + cloud rows merged, max per sig). */
  plays: number;
  coverUrl: string | null;
}

export interface WrappedStats {
  totalMinutes: number;
  totalTracks: number;
  likedSongs: number;
  topPlayedCount: number;
  /** null = the taste-engine facade gap (rendered honestly, never faked). */
  averageBpm: number | null;
  /** Chronotype-derived persona (real hour-of-day histogram data). */
  personaName: string;
  personaEmoji: string;
  personaDescription: string;
  /** Which derivation produced the persona — stated on the card. */
  personaSource: 'chronotype' | 'bpm-engine';
  topGenres: Array<{ genre: string; share: number }>;
  top5Tracks: WrappedTopTrack[];
  topArtists: Array<{ artist: string; count: number }>;
  /** True when cloud rows contributed (signed-in, synced). */
  fromCloud: boolean;
}

export type WrappedPhase = 'idle' | 'computing' | 'ready';

interface WrappedState {
  phase: WrappedPhase;
  stats: WrappedStats | null;
  compute: () => Promise<void>;
}

// The APK's genre keyword table (YtStatsTelemetryEngine), verbatim.
const GENRE_KEYWORDS: Array<[string, string[]]> = [
  ['Electronic & Synthwave', ['electronic', 'synth', 'dance', 'club', 'house', 'techno', 'edm']],
  ['Pop & Modern Hits', ['pop', 'hit', 'radio', 'deluxe', 'remix']],
  ['Hip-Hop & R&B', ['hip-hop', 'rap', 'trap', 'r&b', 'soul', 'urban']],
  ['Indie & Rock', ['rock', 'indie', 'alternative', 'punk', 'metal', 'guitar']],
  ['Chill Lo-Fi & Ambient', ['chill', 'lo-fi', 'lofi', 'ambient', 'sleep', 'focus', 'piano', 'acoustic']],
];

function genreOf(metadata: string): string {
  const m = metadata.toLowerCase();
  for (const [genre, keywords] of GENRE_KEYWORDS) {
    if (keywords.some((k) => m.includes(k))) return genre;
  }
  return 'Pop & Modern Hits'; // the APK's unmatched fallback
}

function personaFromChronotype(title: string): { name: string; emoji: string; description: string } {
  if (title.includes('Night')) {
    return {
      name: 'Midnight Lofi Dreamer',
      emoji: '🌙',
      description: 'Acoustic, Ambient, and Chill-hop dominant mellow listening signature.',
    };
  }
  if (title.includes('Morning')) {
    return {
      name: 'Kinetic Pulse Runner',
      emoji: '⚡',
      description: 'High-Energy Electronic & Rock dominant acoustic profile with dynamic tempo transitions.',
    };
  }
  if (title.includes('Afternoon')) {
    return {
      name: 'Harmonic Groove Weaver',
      emoji: '🌌',
      description: 'Groove Pop, Synthwave, and balanced harmonic frequency clusters.',
    };
  }
  return {
    name: 'Evening Unwinder',
    emoji: '🌆',
    description: 'Balanced evening listening with a wind-down acoustic signature.',
  };
}

export function computeWrapped(
  tally: PlayTally,
  likes: Array<{ title: string; artist: string; album: string | null; coverUrl: string | null }>,
  cloudEvents: OwnPlayEventRow[],
): WrappedStats {
  // -- the rotation corpus, MONOTONICALLY merged (never double-counted) --
  // Every play lands in BOTH the local tally and (when signed in + POST
  // succeeded) the cloud log, so the merge takes the MAX per key — the
  // same never-regress discipline as the APK's GREATEST RPCs. Cloud rows
  // add cross-device plays the local tally cannot see; local rows cover
  // POST failures the cloud cannot.
  const rotations = new Map<string, WrappedTopTrack>();
  for (const [sig, row] of Object.entries(tally.topTracks)) {
    rotations.set(sig, {
      trackSig: sig,
      title: row.title,
      artist: row.artist,
      plays: row.count,
      coverUrl: null,
    });
  }
  const cloudByHour = new Array<number>(24).fill(0);
  const cloudCountBySig = new Map<string, { title: string; artist: string; count: number }>();
  for (const ev of cloudEvents) {
    if (ev.hourOfDay >= 0 && ev.hourOfDay < 24) cloudByHour[ev.hourOfDay] += 1;
    const prev = cloudCountBySig.get(ev.trackSig);
    if (prev === undefined) {
      cloudCountBySig.set(ev.trackSig, { title: ev.title, artist: ev.artist, count: 1 });
    } else {
      prev.count += 1;
    }
  }
  // Per-sig monotonic merge: max(local tally, cloud log).
  for (const [sig, cloud] of cloudCountBySig) {
    const local = rotations.get(sig);
    if (local === undefined) {
      rotations.set(sig, {
        trackSig: sig,
        title: cloud.title,
        artist: cloud.artist,
        plays: cloud.count,
        coverUrl: null,
      });
    } else {
      local.plays = Math.max(local.plays, cloud.count);
    }
  }
  const cloudSeconds = cloudEvents.reduce((acc, ev) => acc + ev.durationPlayedSec, 0);
  // Monotonic: max(local, cloud) — see the merge note above.
  const totalSeconds = Math.max(tally.seconds, cloudSeconds);

  // Attach covers from the likes list where they match.
  for (const like of likes) {
    for (const t of rotations.values()) {
      if (t.coverUrl === null && t.title === like.title && t.artist === like.artist) {
        t.coverUrl = like.coverUrl;
        break;
      }
    }
  }

  const topPlayed = [...rotations.values()].sort((a, b) => b.plays - a.plays);
  const totalMinutes = Math.round(totalSeconds / 60);
  const topPlayedCount = topPlayed.reduce((acc, t) => acc + t.plays, 0);

  // Top 5: rotations first, else likes, else nothing (the APK's fallback
  // chain — library tracks are the web's likes list).
  const top5 =
    topPlayed.slice(0, 5).map((t) => ({ ...t }));
  if (top5.length === 0) {
    for (const like of likes.slice(0, 5)) {
      top5.push({
        trackSig: '',
        title: like.title,
        artist: like.artist,
        plays: 0,
        coverUrl: like.coverUrl,
      });
    }
  }

  // Genres: the APK keyword model over the real corpus (likes + rotations).
  const genreScores = new Map<string, number>();
  const corpus: string[] = [];
  for (const like of likes) corpus.push(`${like.title} ${like.artist} ${like.album ?? ''}`);
  for (const t of topPlayed) corpus.push(`${t.title} ${t.artist}`);
  for (const meta of corpus) {
    const g = genreOf(meta);
    genreScores.set(g, (genreScores.get(g) ?? 0) + 1);
  }
  const totalMatches = Math.max(1, [...genreScores.values()].reduce((a, b) => a + b, 0));
  const topGenres = [...genreScores.entries()]
    .filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 4)
    .map(([genre, n]) => ({ genre, share: n / totalMatches }));

  // Artists: group the same corpus by artist (the APK's topArtists).
  const artistCounts = new Map<string, number>();
  for (const like of likes) {
    const a = like.artist.trim() || 'Unknown Artist';
    artistCounts.set(a, (artistCounts.get(a) ?? 0) + 1);
  }
  for (const t of topPlayed) {
    const a = t.artist.trim() || 'Unknown Artist';
    artistCounts.set(a, (artistCounts.get(a) ?? 0) + 1);
  }
  const topArtists = [...artistCounts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 5)
    .map(([artist, count]) => ({ artist, count }));

  // Chronotype persona (real histogram data — monotonic hour merge).
  const merged = {
    ...tally,
    byHour: tally.byHour.map((n, h) => Math.max(n, cloudByHour[h] ?? 0)),
  };
  const chromo = chronotype(merged);
  const persona = personaFromChronotype(chromo.title);

  return {
    totalMinutes,
    totalTracks: rotations.size,
    likedSongs: likes.length,
    topPlayedCount,
    averageBpm: null, // the taste-engine facade gap — rendered honestly
    personaName: persona.name,
    personaEmoji: persona.emoji,
    personaDescription: persona.description,
    personaSource: 'chronotype',
    topGenres,
    top5Tracks: top5,
    topArtists,
    fromCloud: cloudEvents.length > 0,
  };
}

export const useWrappedStore = create<WrappedState>((set) => ({
  phase: 'idle',
  stats: null,
  compute: async () => {
    set({ phase: 'computing' });
    // Cloud read is best-effort: anonymous/local stays honest with zero rows.
    const cloudEvents = await fetchOwnPlayEvents().catch(() => [] as OwnPlayEventRow[]);
    // sessionStore is read directly (not via a hook) — the store computes
    // outside React's render cycle.
    const { useSessionStore } = await import('./sessionStore');
    const likes = useSessionStore.getState().likes;
    const stats = computeWrapped(readTally(), likes, cloudEvents);
    set({ stats, phase: 'ready' });
  },
}));
