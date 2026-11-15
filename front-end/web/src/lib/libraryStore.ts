/**
 * lib/libraryStore.ts — P2 browse: the LibraryViewModel port (Zustand).
 *
 * The Android LibraryViewModel.loadLibrary() reads the TrackRepository's
 * local library (liked + all tracks) plus PlaylistRepository's playlists.
 * The web engine surface is narrower, so this store is shaped EXACTLY
 * like the ViewModel but fed by the sources that are real on the web:
 *
 *   likedTracks / tracks → the Supabase cloud library (user_likes ⋈
 *     tracks, plus every playlist-membership track — the union is the
 *     web's "allTracks", and the grouping base for Albums/Artists);
 *   playlists → public.playlists + playlist_tracks (schema reused
 *     as-is, plan §11; RLS is client-agnostic).
 *
 * ⚠ ENGINE GAP (documented, not faked): the wasm facade currently
 * exposes ping / set_relay_base_url / search_music / resolve_stream —
 * the local TrackRepository library (scanned files, folders, downloads)
 * is NOT queryable from JS yet. When it lands, its rows merge into
 * `tracks` here (same CloudTrack-compatible shape) and the Songs/
 * Albums/Artists tabs light up with local content — no screen changes.
 */

import { create } from 'zustand';
import {
  addTrackToPlaylist as cloudAddTrack,
  cloudTrackId,
  createPlaylist as cloudCreatePlaylist,
  deletePlaylist as cloudDeletePlaylist,
  fetchAllPlaylistTracks,
  fetchPlaylists,
  fetchTracksByIds,
  removeTrackFromPlaylist as cloudRemoveTrack,
  renamePlaylist as cloudRenamePlaylist,
  type CloudPlaylist,
  type CloudTrack,
} from './supabase';
import { useSessionStore } from './sessionStore';
import { toast } from './toastStore';
import type { PlayableTrack } from './trackPlayer';

export type LibraryPhase = 'idle' | 'loading' | 'ready' | 'error';

export type LibrarySort = 'RECENT' | 'TITLE' | 'ARTIST';

export interface LibraryState {
  phase: LibraryPhase;
  error: string | null;
  /** The union: cloud likes ∪ playlist tracks (the web "allTracks"). */
  tracks: CloudTrack[];
  /** The cloud likes mirror (ordered most-recent first). */
  likedTracks: CloudTrack[];
  playlists: CloudPlaylist[];
  /** playlistId → ordered tracks (loaded with the library). */
  playlistTracks: Record<string, CloudTrack[]>;
  /** Load the whole library (likes come from the session store). */
  load(): Promise<void>;
  createPlaylist(name: string, description: string | null): Promise<CloudPlaylist | null>;
  renamePlaylist(playlistId: string, name: string): Promise<boolean>;
  deletePlaylist(playlistId: string): Promise<boolean>;
  addTrackToPlaylist(playlistId: string, track: PlayableTrack): Promise<boolean>;
  removeTrackFromPlaylist(playlistId: string, trackId: string): Promise<boolean>;
}

function dedupeTracks(list: CloudTrack[]): CloudTrack[] {
  const seen = new Set<string>();
  const out: CloudTrack[] = [];
  for (const track of list) {
    if (track.id === '' || seen.has(track.id)) continue;
    seen.add(track.id);
    out.push(track);
  }
  return out;
}

export const useLibraryStore = create<LibraryState>((set) => ({
  phase: 'idle',
  error: null,
  tracks: [],
  likedTracks: [],
  playlists: [],
  playlistTracks: {},

  async load() {
    const session = useSessionStore.getState().session;
    if (session === null) {
      // Signed out: an honest, empty, READY library (the sign-in empty
      // states render from the session store, not from fake data).
      set({ phase: 'ready', error: null, tracks: [], likedTracks: [], playlists: [], playlistTracks: {} });
      return;
    }
    set({ phase: 'loading', error: null });
    const epoch = ++loadEpoch;
    try {
      const likedTracks = useSessionStore.getState().likes;
      const playlists = await fetchPlaylists();
      const playlistTracks: Record<string, CloudTrack[]> = {};
      let membership: CloudTrack[] = [];
      if (playlists.length > 0) {
        const rows = await fetchAllPlaylistTracks(playlists.map((p) => p.id));
        const distinctIds = Array.from(new Set(rows.map((r) => r.trackId)));
        const trackById = new Map((await fetchTracksByIds(distinctIds)).map((t) => [t.id, t]));
        for (const playlist of playlists) {
          playlistTracks[playlist.id] = rows
            .filter((r) => r.playlistId === playlist.id)
            .map((r) => trackById.get(r.trackId))
            .filter((t): t is CloudTrack => t !== undefined);
          membership = membership.concat(playlistTracks[playlist.id] ?? []);
        }
      }
      if (epoch !== loadEpoch) return; // superseded by a newer load
      set({
        phase: 'ready',
        likedTracks,
        playlists,
        playlistTracks,
        tracks: dedupeTracks([...likedTracks, ...membership]),
      });
    } catch (err) {
      if (epoch !== loadEpoch) return;
      set({
        phase: 'error',
        error: err instanceof Error ? err.message : String(err),
      });
    }
  },

  async createPlaylist(name, description) {
    const created = await cloudCreatePlaylist(name, description);
    if (created !== null) {
      set((prev) => ({
        playlists: [created, ...prev.playlists],
        playlistTracks: { ...prev.playlistTracks, [created.id]: [] },
      }));
    } else {
      toast('Could not create the playlist', 'error');
    }
    return created;
  },

  async renamePlaylist(playlistId, name) {
    const ok = await cloudRenamePlaylist(playlistId, name);
    if (ok) {
      set((prev) => ({
        playlists: prev.playlists.map((p) => (p.id === playlistId ? { ...p, name } : p)),
      }));
    }
    return ok;
  },

  async deletePlaylist(playlistId) {
    const ok = await cloudDeletePlaylist(playlistId);
    if (ok) {
      set((prev) => {
        const playlistTracks = { ...prev.playlistTracks };
        delete playlistTracks[playlistId];
        return {
          playlists: prev.playlists.filter((p) => p.id !== playlistId),
          playlistTracks,
          tracks: dedupeTracks([
            ...prev.likedTracks,
            ...Object.values(playlistTracks).flat(),
          ]),
        };
      });
    }
    return ok;
  },

  async addTrackToPlaylist(playlistId, track) {
    const ok = await cloudAddTrack(playlistId, {
      title: track.title,
      artist: track.artist,
      album: track.album ?? null,
      durationSec: track.durationSec ?? null,
      coverUrl: track.thumbnailUrl ?? null,
      videoId: track.videoId !== '' ? track.videoId : null,
    });
    if (ok) {
      set((prev) => {
        const row: CloudTrack = {
          id: cloudTrackId(track.title, track.artist),
          title: track.title,
          artist: track.artist,
          album: track.album ?? null,
          durationSec: track.durationSec ?? null,
          coverUrl: track.thumbnailUrl ?? null,
          streamUrl: track.videoId !== '' ? track.videoId : null,
        };
        const existing = prev.playlistTracks[playlistId] ?? [];
        return {
          playlists: prev.playlists.map((p) =>
            p.id === playlistId ? { ...p, trackCount: p.trackCount + 1 } : p,
          ),
          playlistTracks: {
            ...prev.playlistTracks,
            [playlistId]: [...existing, row],
          },
          tracks: dedupeTracks([...prev.tracks, row]),
        };
      });
    }
    return ok;
  },

  async removeTrackFromPlaylist(playlistId, trackId) {
    const ok = await cloudRemoveTrack(playlistId, trackId);
    if (ok) {
      set((prev) => {
        const next = (prev.playlistTracks[playlistId] ?? []).filter((t) => t.id !== trackId);
        return {
          playlists: prev.playlists.map((p) =>
            p.id === playlistId ? { ...p, trackCount: Math.max(0, p.trackCount - 1) } : p,
          ),
          playlistTracks: { ...prev.playlistTracks, [playlistId]: next },
          tracks: dedupeTracks([...prev.likedTracks, ...Object.values({
            ...prev.playlistTracks,
            [playlistId]: next,
          }).flat()]),
        };
      });
    }
    return ok;
  },
}));

/** Monotonic load epoch — a slow older load never lands over a newer one. */
let loadEpoch = 0;

// ---------------------------------------------------------------------------
// Pure helpers (the Kotlin remember{} derivations — screens call these in
// useMemo; kept pure so they stay testable and engine-backed data can slot
// into the same pipelines later).
// ---------------------------------------------------------------------------

/** CloudTrack → the playable shape (streamUrl is the videoId lane). */
export function toPlayable(track: CloudTrack): PlayableTrack {
  return {
    videoId: track.streamUrl ?? '',
    title: track.title,
    artist: track.artist,
    album: track.album,
    durationSec: track.durationSec,
    thumbnailUrl: track.coverUrl,
  };
}

/** CloudTrack → the engine Track shape (TrackRow's row model). */
export function toEngineTrack(track: CloudTrack): import('./engine').Track {
  return {
    videoId: track.streamUrl ?? '',
    title: track.title,
    artist: track.artist,
    album: track.album ?? undefined,
    durationSec: track.durationSec ?? undefined,
    thumbnailUrl: track.coverUrl ?? undefined,
  };
}

export interface TrackGroup {
  key: string;
  tracks: CloudTrack[];
}

/** Group by album (LibraryScreen's albums derivation, "Unknown Album" bucket). */
export function groupByAlbum(tracks: CloudTrack[]): TrackGroup[] {
  const map = new Map<string, CloudTrack[]>();
  for (const track of tracks) {
    const key = (track.album ?? '').trim() !== '' ? (track.album ?? '').trim() : 'Unknown Album';
    const bucket = map.get(key);
    if (bucket === undefined) map.set(key, [track]);
    else bucket.push(track);
  }
  return Array.from(map.entries()).map(([key, list]) => ({ key, tracks: list }));
}

/** Group by artist (the artists derivation, "Unknown Artist" bucket). */
export function groupByArtist(tracks: CloudTrack[]): TrackGroup[] {
  const map = new Map<string, CloudTrack[]>();
  for (const track of tracks) {
    const key = track.artist.trim() !== '' ? track.artist.trim() : 'Unknown Artist';
    const bucket = map.get(key);
    if (bucket === undefined) map.set(key, [track]);
    else bucket.push(track);
  }
  return Array.from(map.entries()).map(([key, list]) => ({ key, tracks: list }));
}

/** The LibraryOrganizer sort lowering over the cloud library. */
export function sortTracks(tracks: CloudTrack[], sort: LibrarySort): CloudTrack[] {
  const list = [...tracks];
  if (sort === 'TITLE') {
    list.sort((a, b) => a.title.localeCompare(b.title));
  } else if (sort === 'ARTIST') {
    list.sort(
      (a, b) =>
        a.artist.localeCompare(b.artist) || a.title.localeCompare(b.title),
    );
  }
  // RECENT = the store's arrival order (likes newest-first).
  return list;
}

/** The filter over a playlist's rows (O(n) membership — the Kotlin HashSet). */
export function tracksByIds(tracks: CloudTrack[], ids: Set<string>): CloudTrack[] {
  return tracks.filter((track) => ids.has(track.id));
}
