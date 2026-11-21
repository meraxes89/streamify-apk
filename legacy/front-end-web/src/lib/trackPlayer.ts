/**
 * lib/trackPlayer.ts — the shared browse-surface playback lane.
 *
 * One extraction of HomeScreen's play/queue handlers (the Android
 * onTrackClick/playCollection wiring) so every browse surface — Search,
 * Library, Album, Artist, the home shelves — drives the engine the same
 * honest way. P2-B: the verbs lower onto the QUEUE STORE (the state
 * machine) instead of the raw engine queue — shuffle/repeat/persisted
 * sessions ride along for free, and every play path keeps the honest
 * resolve-then-play shape (slow network + bot-gate verdicts surfaced).
 *
 *   • tap on the current row → toggle (resume/pause);
 *   • fresh play → unlockSink INSIDE the gesture (mobile autoplay policy),
 *     resolve + playNow through the machine, surfacing the resolve verdict
 *     on the row + a toast (bot-gate included, GSF-aware copy);
 *   • playCollection → the machine's replaceQueue (the engine queue is the
 *     collection context, so next/previous walk it — now shuffle-aware);
 *   • Start Radio → the engine's ranked candidates, landed as an up-next
 *     fill (no interruption) or a fresh collection play.
 */

import { useCallback } from 'react';
import { usePlayerStore, toTrackRow } from './playerStore';
import { nowPlaying, useQueueStore } from './playback/queueStore';
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
  /** Append to the queue. */
  queue(track: PlayableTrack): void;
  /** Replace the queue with a collection and play its first row. */
  playCollection(tracks: PlayableTrack[], shuffle?: boolean): Promise<void>;
  /** Per-row playback state from the transport snapshot (one truth). */
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
  const queueStore = useQueueStore;

  const play = useCallback(
    async (track: PlayableTrack): Promise<void> => {
      if (port === null) return;
      if (track.videoId === '') {
        toast(`No stream source for "${track.title}" (liked from another client)`, 'error');
        return;
      }
      const store = queueStore.getState();
      const currentId = nowPlaying(store)?.id ?? null;
      const status = snapshot?.status ?? 'Idle';
      // The current playing track pauses; the current paused track resumes.
      if (track.videoId === currentId && (status === 'Playing' || status === 'Paused')) {
        store.playOrToggle();
        return;
      }
      // Fresh play: the machine's replace-and-play for one row (unlock
      // + resolve + play, the honest slow-network shape).
      try {
        await store.replaceAndPlay([{ track: rowOf(track), enqueuedBy: 'web' }], track.videoId, false);
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
    [port, snapshot, queueStore],
  );

  const queue = useCallback(
    (track: PlayableTrack): void => {
      if (port === null) return;
      if (track.videoId === '') {
        toast(`No stream source for "${track.title}" (liked from another client)`, 'error');
        return;
      }
      const ok = queueStore.getState().append(rowOf(track));
      toast(ok ? `Queued: ${track.title}` : `Already in the queue: ${track.title}`);
    },
    [port, queueStore],
  );

  const playCollection = useCallback(
    async (tracks: PlayableTrack[], shuffle = false): Promise<void> => {
      if (port === null) return;
      const playable = tracks.filter((t) => t.videoId !== '');
      if (playable.length === 0) {
        toast('Nothing playable in this collection yet', 'error');
        return;
      }
      const rows = playable.map((t): { track: TrackRow; enqueuedBy: string } => ({
        track: rowOf(t),
        enqueuedBy: 'web',
      }));
      const firstId = rows[0]?.track.id ?? '';
      await queueStore.getState().replaceAndPlay(rows, firstId, shuffle);
      // The bot-gate verdict surfaces through the shell's onResolveError
      // toast; the actionable copy rides on top when it applies.
      const message = usePlayerStore.getState().resolveError ?? '';
      if (isBotGateError(message)) {
        toast(
          gsfConfigured()
            ? 'Bot-gated — the GSF egress refused this track (see Engine Egress: Test)'
            : 'Bot-gated — the GSF egress (Home → Engine Egress) unlocks unlimited licensed plays',
        );
      }
    },
    [port, queueStore],
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
        const store = queueStore.getState();
        const activeQueue = store.machine.rows
          .filter((r) => r.track.id !== seed.videoId)
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
        //    the machine's insertNext (reversed order). Otherwise a fresh
        //    collection play led by the seed.
        const currentId = nowPlaying(store)?.id ?? null;
        const status = snapshot?.status ?? 'Idle';
        if (seed.videoId === currentId && (status === 'Playing' || status === 'Paused' || status === 'Buffering')) {
          let landed = 0;
          for (const t of [...playable].reverse()) {
            if (store.insertNext(rowOf(t))) landed += 1;
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
    [port, snapshot, queueStore, playCollection],
  );

  return { play, queue, playCollection, playbackFor, startRadio };
}
