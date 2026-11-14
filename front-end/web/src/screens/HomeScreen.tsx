/**
 * screens/HomeScreen.tsx — P3a: the home/search surface (the P2 app).
 *
 * The engine boots in AppShell (page lifetime); this screen is pure UI:
 * the full PlayerBar (seek/volume/stats/egress), search + results, the
 * engine queue, the account panel, and the P1 spine console.
 *
 * The P3a hotfix lives here too: row states derive from the ENGINE
 * SNAPSHOT (one source of truth with the bars) — the P2 bug kept a
 * local "playing" flag that never cleared, so rows lied while the
 * bottom bar said "Nothing queued".
 */

import { useCallback, useEffect, useState } from 'react';
import { usePlayerStore, toTrackRow } from '../lib/playerStore';
import type { EgressMode } from '../lib/wasmEngine';
import { useSearchStore } from '../lib/searchStore';
import { useSessionStore } from '../lib/sessionStore';
import { toast } from '../lib/toastStore';
import type { Track } from '../lib/engine';
import { SearchPanel } from '../components/SearchPanel';
import { PlayerBar } from '../components/PlayerBar';
import { QueueList } from '../components/QueueList';
import { AuthPanel } from '../components/AuthPanel';
import { SpineConsole } from '../components/SpineConsole';
import { IframeLanePanel } from '../components/IframeLanePanel';
import { useIframeLane } from '../audio/iframeLane';
import { isBotGateError } from '../audio/resolve';
import type { RowPlayback } from '../components/TrackRow';

export function HomeScreen(): JSX.Element {
  const port = usePlayerStore((s) => s.port);
  const snapshot = usePlayerStore((s) => s.snapshot);
  const send = usePlayerStore((s) => s.send);
  const resolveError = usePlayerStore((s) => s.resolveError);
  const search = useSearchStore();
  const session = useSessionStore((s) => s.session);
  const likedVideoIds = useSessionStore((s) => s.likedVideoIds);
  const toggleLike = useSessionStore((s) => s.toggleLike);

  const [egressMode, setEgressMode] = useState<EgressMode>('element');
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [rowLocal, setRowLocal] = useState<Record<string, RowPlayback>>({});
  const openIframeLane = useIframeLane((s) => s.open);

  const currentId = snapshot?.currentTrack?.id ?? null;
  const currentStatus = snapshot?.status ?? 'Idle';

  // Egress-mode switches flow into the port (which data plane the next
  // play intent drives).
  useEffect(() => {
    port?.setEgressMode(egressMode);
  }, [port, egressMode]);

  // Local row bookkeeping clears once the snapshot adopts the row (the
  // engine becomes the single source of truth from then on).
  useEffect(() => {
    if (currentId !== null) {
      setRowLocal((prev) => {
        if (!(currentId in prev)) return prev;
        const next = { ...prev };
        delete next[currentId];
        return next;
      });
    }
  }, [currentId]);

  const setRow = useCallback((videoId: string, row: RowPlayback) => {
    setRowLocal((prev) => ({ ...prev, [videoId]: row }));
  }, []);

  const handlePlay = useCallback(
    async (track: Track) => {
      if (port === null) return;
      // 1) The current playing track pauses; the current paused track
      //    resumes (engine toggle → the port's resume path).
      if (track.videoId === currentId) {
        if (currentStatus === 'Playing' || currentStatus === 'Paused') {
          port.unlockSink();
          send({ type: 'toggle' });
          return;
        }
      }
      // 2) Fresh play: unlock the element INSIDE this gesture (mobile
      //    autoplay policy), then resolve (slow network) + play.
      port.unlockSink();
      setRow(track.videoId, { status: 'resolving' });
      try {
        await port.playNow(toTrackRow(track));
        // Success: the snapshot takes over this row (effect above clears
        // the local entry) — no optimistic "playing" flag to go stale.
      } catch (err) {
        const message = err instanceof Error ? err.message : String(err);
        if (isBotGateError(message)) {
          // G4.3: the licensed bot-gate (LOGIN_REQUIRED on the worker
          // egress) — the official YouTube embed takes the track (its own
          // origin + attestation, the user's residential IP). The engine
          // parks; the row says where the track actually went.
          openIframeLane({
            videoId: track.videoId,
            title: track.title,
            artist: track.artist,
            durationSec: track.durationSec,
            thumbnailUrl: track.thumbnailUrl,
          });
          setRow(track.videoId, {
            status: 'failed',
            message: 'bot-gated — playing via the official YouTube embed below',
          });
          toast('Licensed track — playing via the official YouTube embed');
        } else {
          setRow(track.videoId, { status: 'failed', message });
        }
      }
    },
    [port, currentId, currentStatus, send, setRow, openIframeLane],
  );

  const handleQueue = useCallback(
    (track: Track) => {
      if (port === null) return;
      port.enqueue(toTrackRow(track));
      toast(`Queued: ${track.title}`);
    },
    [port],
  );

  const handleLike = useCallback(
    async (track: Track) => {
      if (session === null) return;
      const ok = await toggleLike({
        videoId: track.videoId,
        title: track.title,
        artist: track.artist,
        album: track.album ?? null,
        durationSec: track.durationSec ?? null,
        thumbnailUrl: track.thumbnailUrl ?? null,
      });
      if (ok) toast(`♥ ${track.title}`);
    },
    [session, toggleLike],
  );

  /** Per-row playback state: local (resolving/failed) → snapshot-derived. */
  const playbackFor = useCallback(
    (track: Track): RowPlayback | undefined => {
      const local = rowLocal[track.videoId];
      if (local !== undefined) return local;
      if (track.videoId === currentId) {
        if (currentStatus === 'Buffering') return { status: 'resolving' };
        if (currentStatus === 'Playing') return { status: 'playing' };
        if (currentStatus === 'Paused') return { status: 'passed' }; // → "resume"
      }
      return undefined;
    },
    [rowLocal, currentId, currentStatus],
  );

  return (
    <div className="stack home-stack">
      <PlayerBar egressMode={egressMode} onEgressModeChange={setEgressMode} />
      {resolveError !== null && <p className="home-resolve-error">{resolveError}</p>}
      <IframeLanePanel />
      <SearchPanel
        phase={search.phase}
        query={search.query}
        onQueryChange={search.setQuery}
        onSubmit={() => {
          void search.run();
        }}
        error={search.error}
        results={search.results}
        playback={playbackFor}
        onPlay={(track) => {
          void handlePlay(track);
        }}
        onQueue={handleQueue}
        onLike={(track) => {
          void handleLike(track);
        }}
        likedIds={session !== null ? likedVideoIds : undefined}
        currentId={currentId}
      />
      <QueueList port={port} />
      <AuthPanel port={port} />

      <details
        className="advanced"
        open={advancedOpen}
        onToggle={(e) => setAdvancedOpen((e.target as HTMLDetailsElement).open)}
      >
        <summary>Engine console — the P1 WASM spine (G1 verification, relay bytes)</summary>
        <p className="muted advanced__hint">
          The opt-in engine path: media bytes flow through the relay into the WASM
          demux → WebCodecs → worklet pipeline (LUFS-normalized, underrun-gated).
          Datacenter relay IPs throttle after a handful of plays — that is the
          wall the direct path above sidesteps.
        </p>
        <SpineConsole />
      </details>
    </div>
  );
}
