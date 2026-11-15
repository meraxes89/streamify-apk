/**
 * lib/homeStore.ts — P2 browse: the HomeViewModel port (Zustand).
 *
 * The Android HomeViewModel is a two-stage recommender:
 *   STAGE 1 (instant, local) — session/circadian/made-for-you shelves
 *     from the local repository;
 *   STAGE 2 (progressive, cloud) — multi-seed discovery: 5 genre seeds
 *     searched in parallel, deduped, feeding the Trending shelf.
 *
 * The web lowering keeps the SHAPE with the real web sources:
 *   • Stage 1: cloud likes (Liked Music shelf) + the local play tally
 *     (Heavy Rotation — playTally.topTracks, the honest local signal);
 *   • Stage 2: the same 5 seeds through searchMusic (the G4 session
 *     lane) → the Trending Global shelf, deduped by title::artist.
 *
 * The recommendation ENGINE surfaces (ReRanker, HybridGraphFetcher,
 * DaylistScheduler, DailyMixes) are not exposed to JS — they slot in as
 * extra shelves when the wasm facade grows them (documented gap, never
 * faked). Epoch-guarded: a slow Stage 2 never lands over a newer load.
 */

import { create } from 'zustand';
import { searchMusic } from './engine';
import type { Track } from './engine';
import { useSessionStore } from './sessionStore';
import { readTally } from './playTally';
import { toPlayable } from './libraryStore';
import type { CloudTrack } from './supabase';
import type { PlayableTrack } from './trackPlayer';

/** VirtualShelfTrack (ui/models/UiModels.kt), web lowering. */
export interface VirtualShelfTrack {
  cadId: string;
  title: string;
  artist: string;
  artworkUrl: string;
  durationSec: number;
  ytmVideoId: string;
  isLiked: boolean;
}

/** VirtualShelf (ui/models/UiModels.kt). */
export interface VirtualShelf {
  id: string;
  title: string;
  subtitle: string | null;
  tracks: VirtualShelfTrack[];
}

export type HomePhase = 'idle' | 'loading' | 'ready' | 'error';

/** The HomeViewModel's cloud seeds (Stage 2), verbatim. */
const CLOUD_SEEDS = ['Top Hits', 'Trending Hits', 'Synthwave', 'Lo-Fi Beats', 'Indie Chill'];

const LIKED_SHELF_MAX = 12;
const ROTATION_SHELF_MAX = 10;
const TRENDING_SHELF_MAX = 15;

function trackToShelfTrack(track: Track, isLiked: boolean): VirtualShelfTrack | null {
  if (track.videoId === '') return null;
  return {
    cadId: track.videoId,
    title: track.title,
    artist: track.artist,
    artworkUrl: track.thumbnailUrl ?? '',
    durationSec: track.durationSec ?? 0,
    ytmVideoId: track.videoId,
    isLiked,
  };
}

function cloudTrackToShelfTrack(track: CloudTrack): VirtualShelfTrack | null {
  if (track.streamUrl == null || track.streamUrl === '') return null;
  return {
    cadId: track.streamUrl,
    title: track.title,
    artist: track.artist,
    artworkUrl: track.coverUrl ?? '',
    durationSec: track.durationSec ?? 0,
    ytmVideoId: track.streamUrl,
    isLiked: true,
  };
}

export interface HomeState {
  phase: HomePhase;
  shelves: VirtualShelf[];
  /** Stage 2 (cloud discovery) still in flight. */
  cloudLoading: boolean;
  error: string | null;
  load(): Promise<void>;
}

export const useHomeStore = create<HomeState>((set, get) => ({
  phase: 'idle',
  shelves: [],
  cloudLoading: false,
  error: null,

  async load() {
    const epoch = ++loadEpoch;
    set({ phase: 'loading', error: null });

    // ---- STAGE 1: the instant local shelves (<5ms render) -------------
    const session = useSessionStore.getState();
    const likedShelf: VirtualShelf = {
      id: 'liked_music',
      title: 'Liked Music',
      subtitle: 'Your cloud library',
      tracks: session.likes
        .map(cloudTrackToShelfTrack)
        .filter((t): t is VirtualShelfTrack => t !== null)
        .slice(0, LIKED_SHELF_MAX),
    };

    // Heavy Rotation — the local play tally (real listening history).
    const tally = readTally();
    const top = Object.entries(tally.topTracks)
      .sort((a, b) => (b[1]?.count ?? 0) - (a[1]?.count ?? 0))
      .slice(0, ROTATION_SHELF_MAX)
      .map(([sig, info]) => ({ sig, info }));
    const heavyRotation: VirtualShelf = {
      id: 'heavy_rotation',
      title: 'Heavy Rotation',
      subtitle: 'Your highest play-count tracks',
      tracks: top.map(({ info }) => ({
        cadId: info.title,
        title: info.title,
        artist: info.artist,
        artworkUrl: '',
        durationSec: 0,
        ytmVideoId: '',
        isLiked: session.likedVideoIds.size > 0,
      })),
    };

    const localShelves = [likedShelf, heavyRotation].filter((s) => s.tracks.length > 0);
    set({ phase: 'ready', shelves: localShelves, cloudLoading: true });

    // ---- STAGE 2: the progressive cloud discovery shelves -------------
    try {
      const seedResults = await Promise.allSettled(
        CLOUD_SEEDS.map(async (seed) => {
          const tracks = await searchMusic(seed);
          return tracks.slice(0, 6).map((t) => trackToShelfTrack(t, false));
        }),
      );
      if (epoch !== loadEpoch) return;
      const pool: VirtualShelfTrack[] = [];
      const seen = new Set<string>();
      for (const settled of seedResults) {
        if (settled.status !== 'fulfilled') continue;
        for (const track of settled.value) {
          if (track === null) continue;
          const key = `${track.title}:::${track.artist}`.toLowerCase();
          if (seen.has(key)) continue;
          seen.add(key);
          pool.push(track);
        }
      }
      const trending: VirtualShelf = {
        id: 'trending_global',
        title: 'Trending Global',
        subtitle: 'Popular audio streams',
        tracks: pool.slice(0, TRENDING_SHELF_MAX),
      };
      const current = get().shelves;
      set({
        shelves:
          trending.tracks.length > 0 ? [...current, trending] : current,
        cloudLoading: false,
      });
    } catch (err) {
      if (epoch !== loadEpoch) return;
      set({
        cloudLoading: false,
        error: err instanceof Error ? err.message : String(err),
      });
    }
  },
}));

let loadEpoch = 0;

/** Shelf track → the playable shape (the onTrackSelected lowering). */
export function shelfTrackToPlayable(track: VirtualShelfTrack): PlayableTrack {
  return {
    videoId: track.ytmVideoId,
    title: track.title,
    artist: track.artist,
    durationSec: track.durationSec,
    thumbnailUrl: track.artworkUrl !== '' ? track.artworkUrl : null,
  };
}

/** Convenience: a whole shelf as a playable collection (row context). */
export function shelfToPlayables(shelf: VirtualShelf): PlayableTrack[] {
  return shelf.tracks
    .map(shelfTrackToPlayable)
    .filter((track) => track.videoId !== '');
}

/** Cloud tracks → shelf rows (the liked-shelf swap-in helper). */
export function playablesFromCloud(tracks: CloudTrack[]): PlayableTrack[] {
  return tracks.map(toPlayable);
}
