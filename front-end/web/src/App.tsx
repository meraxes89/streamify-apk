import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { loadEngine, relayBaseCandidate, searchMusic } from './lib/engine';
import type { EngineStatus, Track } from './lib/engine';
import { createEngineStack } from './lib/wasmEngine';
import type { WasmEnginePort, EgressMode } from './lib/wasmEngine';
import { toTrackRow, usePlayerStore } from './lib/playerStore';
import { SearchPanel } from './components/SearchPanel';
import type { SearchPhase } from './components/SearchPanel';
import type { RowPlayback } from './components/TrackRow';
import { PlayerBar } from './components/PlayerBar';
import { QueueList } from './components/QueueList';
import { AuthPanel } from './components/AuthPanel';
import { SpineConsole } from './components/SpineConsole';
import {
  cloudTrackId,
  getSession,
  likeTrack,
  recordPlayEvent,
  supabaseConfigured,
  type SupabaseSession,
} from './lib/supabase';

/**
 * App — the P2 mobile-first shell (W2.0 + W2.1 + W2.2 + W2.3).
 *
 * The wasm ENGINE (queue, state machine, commands — front-end/crates/
 * streamify-engine-wasm) owns playback; the DIRECT-EGRESS element sink
 * (W2.0) rides the user's IP for media bytes; the relay serves only the
 * tiny resolve/mint legs. The P1 spine (WASM DSP pipeline) stays one tap
 * away as the opt-in "engine path" (desktop quality mode, G1 gate).
 */

function detectDefaultEgressMode(): EgressMode {
  // Coarse pointer (phones/tablets) → direct egress by default; the relay
  // byte path is a datacenter IP and dies after a handful of plays there.
  const coarse = window.matchMedia?.('(pointer: coarse)').matches ?? false;
  return coarse ? 'element' : 'element';
}

export function App(): JSX.Element {
  const [engineStatus, setEngineStatus] = useState<EngineStatus | null>(null);
  const [searchPhase, setSearchPhase] = useState<SearchPhase>('idle');
  const [query, setQuery] = useState<string>('');
  const [results, setResults] = useState<Track[]>([]);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [playback, setPlayback] = useState<Record<string, RowPlayback>>({});
  const [port, setPort] = useState<WasmEnginePort | null>(null);
  const [egressMode, setEgressMode] = useState<EgressMode>(detectDefaultEgressMode);
  const [advancedOpen, setAdvancedOpen] = useState<boolean>(false);
  const [session, setSession] = useState<SupabaseSession | null>(null);
  const [likedIds, setLikedIds] = useState<Set<string>>(() => new Set());

  const aliveRef = useRef<boolean>(true);
  const bind = usePlayerStore((s) => s.bind);
  const setLastRung = usePlayerStore((s) => s.setLastRung);
  const snapshot = usePlayerStore((s) => s.snapshot);
  const lastResolve = port?.lastResolved ?? null;

  // ---- boot: resolver engine (search) + the wasm control plane --------
  useEffect(() => {
    aliveRef.current = true;
    const relay = relayBaseCandidate();
    void loadEngine(relay).then((status: EngineStatus) => {
      if (aliveRef.current) setEngineStatus(status);
    });
    void createEngineStack({ relayBase: relay, egressMode: 'element' }).then((stack) => {
      if (!aliveRef.current) {
        stack.sink.dispose();
        return;
      }
      bind(stack.port);
      setPort(stack.port);
      // Debug handle (the __spine convention): the live port for console
      // diagnostics — queue inspection, command injection.
      (window as unknown as { __enginePort: WasmEnginePort }).__enginePort = stack.port;
    });
    // Supabase session boot (best-effort, parallel).
    if (supabaseConfigured()) {
      void getSession().then((s) => {
        if (aliveRef.current) setSession(s);
      });
    }
    return () => {
      aliveRef.current = false;
    };
  }, [bind]);

  // Egress-mode switches flow into the port (which data plane the next
  // play intent drives).
  useEffect(() => {
    port?.setEgressMode(egressMode);
  }, [port, egressMode]);

  // Rung chip sync (the port's last resolve, polled on snapshot pushes).
  useEffect(() => {
    if (lastResolve !== null) setLastRung(lastResolve.rung);
  }, [lastResolve, snapshot?.rev, setLastRung]);

  // Play-event telemetry (W2.2, best-effort, signed-in only): report when
  // a track LEAVES the cursor — the row's meta comes from the queue read,
  // the played seconds from the engine position at that moment.
  const reportedRef = useRef<string | null>(null);
  useEffect(() => {
    const current = snapshot?.currentTrack?.id ?? null;
    const prev = reportedRef.current;
    if (prev !== null && prev !== current && session !== null && port !== null) {
      const row = port
        .getQueue()
        .rows.find((r) => r.track.id === prev)?.track;
      if (row !== undefined) {
        void recordPlayEvent({
          trackSig: cloudTrackId(row.title, row.artist),
          title: row.title,
          artist: row.artist,
          durationPlayedSec: (snapshot?.positionMs ?? 0) / 1000,
          completionRatio:
            row.durationMs > 0 ? Math.min(1, (snapshot?.positionMs ?? 0) / row.durationMs) : 0,
        });
      }
    }
    reportedRef.current = current;
  }, [snapshot?.currentTrack?.id, snapshot?.positionMs, session, port]);

  // ---- search ----------------------------------------------------------

  const runSearch = useCallback(async () => {
    const q = query.trim();
    if (q === '') return;
    setSearchPhase('loading');
    setSearchError(null);
    try {
      const tracks = await searchMusic(q);
      if (!aliveRef.current) return;
      setResults(tracks);
      setSearchPhase('results');
    } catch (err) {
      if (!aliveRef.current) return;
      setSearchPhase('error');
      setSearchError(err instanceof Error ? err.message : String(err));
    }
  }, [query]);

  const setRow = useCallback((videoId: string, row: RowPlayback) => {
    setPlayback((prev) => ({ ...prev, [videoId]: row }));
  }, []);

  const handlePlay = useCallback(
    async (track: Track) => {
      if (port === null) return;
      setRow(track.videoId, { status: 'resolving' });
      try {
        await port.playNow(toTrackRow(track));
        setRow(track.videoId, { status: 'playing' });
      } catch (err) {
        setRow(track.videoId, {
          status: 'failed',
          message: err instanceof Error ? err.message : String(err),
        });
      }
    },
    [port, setRow],
  );

  const handleQueue = useCallback(
    (track: Track) => {
      port?.enqueue(toTrackRow(track));
    },
    [port],
  );

  const handleLike = useCallback(
    async (track: Track) => {
      if (session === null) return;
      const ok = await likeTrack({
        title: track.title,
        artist: track.artist,
        album: track.album ?? null,
        durationSec: track.durationSec ?? null,
        coverUrl: track.thumbnailUrl ?? null,
        videoId: track.videoId,
      });
      if (ok) {
        setLikedIds((prev) => new Set(prev).add(track.videoId));
      }
    },
    [session],
  );

  const currentId = snapshot?.currentTrack?.id ?? null;

  const enginePhase =
    engineStatus === null ? 'pending' : engineStatus.loaded ? 'loaded' : 'error';

  const egressLabel = useMemo(
    () => (egressMode === 'element' ? 'direct (user IP)' : 'spine (relay bytes)'),
    [egressMode],
  );

  return (
    <div className="app-shell">
      <header className="site-header">
        <div className="titles">
          <h1>Streamify</h1>
          <p className="subtitle">
            wasm engine · direct egress · {egressLabel}
          </p>
        </div>
        <span
          className={`chip chip--${enginePhase}`}
          role="status"
          aria-label="Engine status"
        >
          {enginePhase === 'pending'
            ? 'engine: loading…'
            : enginePhase === 'loaded'
              ? `engine: ${engineStatus?.ping ?? 'ready'}`
              : 'engine: error'}
        </span>
      </header>

      <main className="app-main">
        <div className="stack">
          <PlayerBar egressMode={egressMode} onEgressModeChange={setEgressMode} />
          <SearchPanel
            phase={searchPhase}
            query={query}
            onQueryChange={setQuery}
            onSubmit={() => {
              void runSearch();
            }}
            error={searchError}
            results={results}
            playback={playback}
            onPlay={(track) => {
              void handlePlay(track);
            }}
            onQueue={handleQueue}
            onLike={(track) => {
              void handleLike(track);
            }}
            likedIds={session !== null ? likedIds : undefined}
            currentId={currentId}
          />
          <QueueList port={port} />
          <AuthPanel port={port} />

          <details
            className="advanced"
            open={advancedOpen}
            onToggle={(e) => setAdvancedOpen((e.target as HTMLDetailsElement).open)}
          >
            <summary>
              Engine console — the P1 WASM spine (G1 verification, relay bytes)
            </summary>
            <p className="muted advanced__hint">
              The opt-in engine path: media bytes flow through the relay into the WASM
              demux → WebCodecs → worklet pipeline (LUFS-normalized, underrun-gated).
              Datacenter relay IPs throttle after a handful of plays — that is the
              wall the direct path above sidesteps.
            </p>
            <SpineConsole />
          </details>
        </div>
      </main>

      <footer className="site-footer">
        <p>
          WS-W P2 — engine breadth &amp; egress · G0.2/G0.3/G0.4/G1 · canary:{' '}
          <a className="footer-link" href="canary.html">
            /canary.html
          </a>
        </p>
      </footer>
    </div>
  );
}
