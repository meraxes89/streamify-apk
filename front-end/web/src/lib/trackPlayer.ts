/**
 * lib/trackPlayer.ts — P2 browse: the shared browse-surface playback lane.
 *
 * One extraction of HomeScreen's play/queue handlers (the Android
 * onTrackClick/playCollection wiring) so every browse surface — Search,
 * Library, Album, Artist, the home shelves — drives the engine the same
 * honest way:
 *
 *   • tap on the current row → toggle (resume/pause);
 *   • fresh play → unlockSink INSIDE the gesture (mobile autoplay policy),
 *     resolve (slow network) + playNow, surfacing the resolve verdict on
 *     the row + a toast (bot-gate included, GSF-aware copy);
 *   • playCollection → clearQueue + enqueue the whole list + resolve/play
 *     the first row (the Android playCollection/shuffled lowering — the
 *     engine queue is the collection context, so next/previous walk it).
 */

import { useCallback } from 'react';
import { usePlayerStore, toTrackRow } from './playerStore';
import type { TrackRow } from './wasmEngine';
import { toast } from './toastStore';
import { isBotGateError } from '../audio/resolve';
import { gsfConfigured } from './gsfLane';
// WS-W engine facade breadth (work item 3): the Start Radio lane.
import { searchMusic, scoreAndRankRadioCandidates, type Track } from './engine';

/** The playable shape shared by engine Tracks and cloud library rows. */
export interface PlayableTrack {
  videoId: string;
  title: string;
  artist: string;
  album?: string | null;
  durationSec?: number | null;
  thumbnailUrl?: string | null;
}

export interface TrackPlayer {
  /** Single-track play (toggle when it is the current row). */
  play(track: PlayableTrack): Promise<void>;
  /** Append to the engine queue. */
  queue(track: PlayableTrack): void;
  /** Replace the queue with a collection and play its first row. */
  playCollection(tracks: PlayableTrack[], shuffle?: boolean): Promise<void>;
  /** Per-row playback state from the ENGINE snapshot (one source of truth). */
  playbackFor(track: PlayableTrack): import('../components/TrackRow').RowPlayback | undefined;
  /**
   * Start Radio (WS-W engine facade breadth, work item 3): candidates via
   * the yt session search over the seed, ranked through the ENGINE's
   * anti-drift scorer (lib/engine.ts — the bridge contract), then either
   * an up-next fill (when the seed is the now-playing row — no
   * interruption, the P1 discipline) or a fresh collection play.
   */
  startRadio(seed: PlayableTrack): Promise<void>;
}

function shuffled<T>(list: T[]): T[] {
  const out = [...list];
  for (let i = out.length - 1; i > 0; i -= 1) {
    const j = Math.floor(Math.random() * (i + 1));
    const tmp = out[i] as T;
    out[i] = out[j] as T;
    out[j] = tmp;
  }
  return out;
}

/** PlayableTrack → engine TrackRow (null-tolerant fields normalized). */
function rowOf(track: PlayableTrack): TrackRow {
  return toTrackRow({
    videoId: track.videoId,
    title: track.title,
    artist: track.artist,
    album: track.album ?? undefined,
    durationSec: track.durationSec ?? undefined,
    thumbnailUrl: track.thumbnailUrl ?? undefined,
  });
}

export function useTrackPlayer(): TrackPlayer {
  const port = usePlayerStore((s) => s.port);
  const snapshot = usePlayerStore((s) => s.snapshot);
  const send = usePlayerStore((s) => s.send);

  const play = useCallback(
    async (track: PlayableTrack): Promise<void> => {
      if (port === null) return;
      if (track.videoId === '') {
        toast(`No stream source for "${track.title}" (liked from another client)`, 'error');
        return;
      }
      const currentId = snapshot?.currentTrack?.id ?? null;
      const status = snapshot?.status ?? 'Idle';
      // The current playing track pauses; the current paused track resumes.
      if (track.videoId === currentId && (status === 'Playing' || status === 'Paused')) {
        port.unlockSink();
        send({ type: 'toggle' });
        return;
      }
      // Fresh play: unlock INSIDE this gesture, then resolve + play.
      port.unlockSink();
      try {
        await port.playNow(rowOf(track));
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        // The raw verdict already toasted via the shell's onResolveError
        // hook — this lane adds only the ACTIONABLE bot-gate copy (the
        // GSF egress exists to un-burn exactly this wall).
        if (isBotGateError(message)) {
          toast(
            gsfConfigured()
              ? 'Bot-gated — the GSF egress refused this track (see Engine Egress: Test)'
              : 'Bot-gated — the GSF egress (Home → Engine Egress) unlocks unlimited licensed plays',
          );
        }
      }
    },
    [port, snapshot, send],
  );

  const queue = useCallback(
    (track: PlayableTrack): void => {
      if (port === null) return;
      if (track.videoId === '') {
        toast(`No stream source for "${track.title}" (liked from another client)`, 'error');
        return;
      }
      port.enqueue(rowOf(track));
      toast(`Queued: ${track.title}`);
    },
    [port],
  );

  const playCollection = useCallback(
    async (tracks: PlayableTrack[], shuffle = false): Promise<void> => {
      if (port === null) return;
      const playable = tracks.filter((t) => t.videoId !== '');
      if (playable.length === 0) {
        toast('Nothing playable in this collection yet', 'error');
        return;
      }
      const list = shuffle ? shuffled(playable) : playable;
      port.unlockSink();
      port.clearQueue();
      for (const track of list) {
        port.enqueue(rowOf(track));
      }
      const first = list[0] as PlayableTrack;
      // A null stream already reported its verdict through the shell's
      // onResolveError toast — the actionable bot-gate copy rides on top.
      const stream = await port.resolveAndStage(first.videoId);
      if (stream !== null) {
        port.playTrack(first.videoId);
      } else {
        const message = usePlayerStore.getState().resolveError ?? '';
        if (isBotGateError(message)) {
          toast(
            gsfConfigured()
              ? 'Bot-gated — the GSF egress refused this track (see Engine Egress: Test)'
              : 'Bot-gated — the GSF egress (Home → Engine Egress) unlocks unlimited licensed plays',
          );
        }
      }
    },
    [port],
  );

  const playbackFor = useCallback(
    (track: PlayableTrack): import('../components/TrackRow').RowPlayback | undefined => {
      if (track.videoId === '' || track.videoId !== (snapshot?.currentTrack?.id ?? null)) {
        return undefined;
      }
      const status = snapshot?.status ?? 'Idle';
      if (status === 'Buffering') return { status: 'resolving' };
      if (status === 'Playing') return { status: 'playing' };
      if (status === 'Paused') return { status: 'passed' }; // → "resume"
      return undefined;
    },
    [snapshot],
  );

  // -- Start Radio (work item 3) --------------------------------------------
  const startRadio = useCallback(
    async (seed: PlayableTrack): Promise<void> => {
      if (port === null) return;
      if (seed.videoId === '') {
        toast(`No stream source for "${seed.title}" (liked from another client)`, 'error');
        return;
      }
      port.unlockSink();
      try {
        // 1. The candidate pool: the yt session search over the seed.
        const pool = await searchMusic(`${seed.artist} ${seed.title}`.trim());
        const candidates = pool.filter((t) => t.videoId !== seed.videoId);
        if (candidates.length === 0) {
          toast('The radio search found no candidates for this seed', 'error');
          return;
        }
        // 2. Rank through the engine (anti-drift). id = pool index so the
        //    ranked rows map back to playable tracks 1:1.
        const activeQueue = port
          .getQueue()
          .rows.filter((r) => r.track.id !== seed.videoId)
          .map(
            (r): import('./engine').RadioCandidate => ({
              id: 0,
              title: r.track.title,
              artist: r.track.artist,
              album: '',
              duration_sec: Math.round(r.track.durationMs / 1000),
              filepath: '',
              cover_art_path: r.track.thumbnailUrl ?? '',
              bpm: 0,
              key: '',
            }),
          );
        const ranked = scoreAndRankRadioCandidates(
          candidates.map((t, i): import('./engine').RadioCandidate => {
            const dur = Math.round(t.durationSec ?? 0);
            return {
              id: i,
              title: t.title,
              artist: t.artist,
              album: '',
              duration_sec: dur,
              filepath: '',
              cover_art_path: t.thumbnailUrl ?? '',
              bpm: 0,
              key: '',
            };
          }),
          {
            bpm: 0,
            key: '',
            durationSec: seed.durationSec ?? 0,
            signature: `${seed.title}|${seed.artist}|${Math.round(seed.durationSec ?? 0)}`,
          },
          activeQueue,
        );
        const playable = ranked
          .map((rc) => pool[rc.id] as Track | undefined)
          .filter((t): t is Track => t !== undefined && t.videoId !== '');
        if (playable.length === 0) {
          toast('The radio ladder filtered every candidate (junk/dedupe)', 'error');
          return;
        }
        // 3. Land it. Seed already the now-playing row → up-next fill via
        //    the Play Next op (reversed order; the projection contract is
        //    playNext's). Otherwise a fresh collection play led by the seed.
        const currentId = snapshot?.currentTrack?.id ?? null;
        const status = snapshot?.status ?? 'Idle';
        if (seed.videoId === currentId && (status === 'Playing' || status === 'Paused' || status === 'Buffering')) {
          const store = usePlayerStore.getState();
          let landed = 0;
          for (const t of [...playable].reverse()) {
            if (store.playNext(t)) landed += 1;
          }
          toast(
            landed > 0 ? `Radio: ${landed} tracks up next` : 'Engine busy — try again',
            landed > 0 ? 'success' : 'error',
          );
          return;
        }
        await playCollection([seed, ...playable], false);
      } catch (err) {
        toast(err instanceof Error ? err.message : String(err), 'error');
      }
    },
    [port, snapshot, playCollection],
  );

  return { play, queue, playCollection, playbackFor, startRadio };
}
