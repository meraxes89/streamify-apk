import { useCallback, useEffect, useRef, useState } from 'react';
import { loadEngine, relayBaseCandidate, relayWrap, resolveStream, searchMusic } from './lib/engine';
import type { EngineStatus, StreamInfo, Track } from './lib/engine';
import { SearchPanel } from './components/SearchPanel';
import type { SearchPhase } from './components/SearchPanel';
import { StatusCard } from './components/StatusCard';
import type { RowPlayback } from './components/TrackRow';

const PROOF_MS = 10_000; // the P0 audio proof plays 10 seconds, then auto-pauses

/** G0.4's spec video — standard content (Rick Astley), the canonical
 * 10-second playback proof target. Licensed search rows stay bot-walled
 * from datacenter IPs (the canary's W0.5 question); this button runs the
 * gate's exact test. */
const G04_VIDEO_ID = 'dQw4w9WgXcQ';

export function App(): JSX.Element {
  const [engineStatus, setEngineStatus] = useState<EngineStatus | null>(null);
  const [searchPhase, setSearchPhase] = useState<SearchPhase>('idle');
  const [query, setQuery] = useState<string>('');
  const [results, setResults] = useState<Track[]>([]);
  const [searchError, setSearchError] = useState<string | null>(null);
  const [lastSearchCount, setLastSearchCount] = useState<number | null>(null);
  const [playback, setPlayback] = useState<Record<string, RowPlayback>>({});
  const [g04State, setG04State] = useState<RowPlayback | null>(null);

  const aliveRef = useRef<boolean>(true);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const activeVideoRef = useRef<string | null>(null);

  // Boot the engine once with the relay candidate (?relay= > env > dev default).
  useEffect(() => {
    aliveRef.current = true;
    let cancelled = false;
    void loadEngine(relayBaseCandidate()).then((status: EngineStatus) => {
      if (!cancelled) {
        setEngineStatus(status);
      }
    });
    return () => {
      cancelled = true;
      aliveRef.current = false;
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      if (audioRef.current !== null) {
        audioRef.current.pause();
        audioRef.current.removeAttribute('src');
        audioRef.current = null;
      }
    };
  }, []);

  const setRow = useCallback((videoId: string, row: RowPlayback) => {
    setPlayback((prev) => ({ ...prev, [videoId]: row }));
  }, []);

  // Stop whatever is playing: clear the proof timer, pause/release the audio,
  // and reset the previous row to its idle state.
  const stopPlayback = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
    if (audioRef.current !== null) {
      audioRef.current.pause();
      audioRef.current.removeAttribute('src');
      audioRef.current = null;
    }
    const previous = activeVideoRef.current;
    if (previous !== null) {
      activeVideoRef.current = null;
      setPlayback((prev) => {
        if (!(previous in prev)) {
          return prev;
        }
        const next = { ...prev };
        delete next[previous];
        return next;
      });
    }
  }, []);

  const runSearch = useCallback(async () => {
    const q = query.trim();
    if (q === '') {
      return;
    }
    setSearchPhase('loading');
    setSearchError(null);
    try {
      const tracks = await searchMusic(q);
      if (!aliveRef.current) {
        return;
      }
      setResults(tracks);
      setLastSearchCount(tracks.length);
      setSearchPhase('results');
    } catch (err) {
      if (!aliveRef.current) {
        return;
      }
      setSearchPhase('error');
      setSearchError(err instanceof Error ? err.message : String(err));
    }
  }, [query]);

  const handlePlay = useCallback(
    async (track: Track) => {
      // Only one proof at a time — stop whatever was playing before.
      stopPlayback();

      const videoId = track.videoId;
      activeVideoRef.current = videoId;
      setRow(videoId, { status: 'resolving' });

      let info: StreamInfo | null;
      try {
        info = await resolveStream(videoId);
      } catch (err) {
        if (aliveRef.current && activeVideoRef.current === videoId) {
          setRow(videoId, {
            status: 'failed',
            message: err instanceof Error ? err.message : String(err),
          });
        }
        return;
      }
      if (!aliveRef.current || activeVideoRef.current !== videoId) {
        return;
      }
      if (info === null || info.streamUrl === '') {
        setRow(videoId, { status: 'failed', message: 'no playable stream resolved' });
        return;
      }

      // Direct googlevideo URLs go through the relay; already-relayed URLs stay as-is.
      const base = engineStatus?.relayBase ?? '';
      const src = info.streamUrl.startsWith(base) ? info.streamUrl : relayWrap(info.streamUrl);

      const audio = new Audio(src);
      audioRef.current = audio;
      audio.addEventListener('error', () => {
        if (timerRef.current !== null) {
          clearTimeout(timerRef.current);
          timerRef.current = null;
        }
        if (aliveRef.current && activeVideoRef.current === videoId) {
          setRow(videoId, {
            status: 'failed',
            message: 'stream error — relay unreachable or URL rejected',
          });
        }
      });
      audio.addEventListener('ended', () => {
        if (timerRef.current !== null) {
          clearTimeout(timerRef.current);
          timerRef.current = null;
        }
        if (aliveRef.current && activeVideoRef.current === videoId) {
          setRow(videoId, {
            status: 'failed',
            message: 'stream ended before the 10s proof completed',
          });
        }
      });

      try {
        await audio.play();
      } catch (err) {
        if (aliveRef.current && activeVideoRef.current === videoId) {
          setRow(videoId, {
            status: 'failed',
            message: err instanceof Error ? err.message : String(err),
          });
        }
        return;
      }
      if (!aliveRef.current || activeVideoRef.current !== videoId) {
        return;
      }

      setRow(videoId, { status: 'playing' });
      timerRef.current = setTimeout(() => {
        timerRef.current = null;
        audio.pause();
        if (aliveRef.current && activeVideoRef.current === videoId) {
          setRow(videoId, { status: 'passed' });
        }
      }, PROOF_MS);
    },
    [engineStatus, setRow, stopPlayback],
  );

  // G0.4: the gate's canonical 10-second playback proof on standard content
  // (dQw4w9WgXcQ) — resolves through the wasm engine + relay, plays 10s of
  // audio, then PASSes. Rendered in the status area because it is a gate
  // affordance, not a search result (licensed rows are bot-walled from
  // datacenter IPs — expected, measured by the canary).
  const runG04Proof = useCallback(async () => {
    if (g04State?.status === 'resolving' || g04State?.status === 'playing') {
      return;
    }
    stopPlayback();
    setG04State({ status: 'resolving' });
    let info: StreamInfo | null;
    try {
      info = await resolveStream(G04_VIDEO_ID);
    } catch (err) {
      setG04State({
        status: 'failed',
        message: err instanceof Error ? err.message : String(err),
      });
      return;
    }
    if (info === null || info.streamUrl === '') {
      setG04State({ status: 'failed', message: 'no playable stream resolved' });
      return;
    }
    const base = engineStatus?.relayBase ?? '';
    const src = info.streamUrl.startsWith(base) ? info.streamUrl : relayWrap(info.streamUrl);
    const audio = new Audio(src);
    audioRef.current = audio;
    activeVideoRef.current = G04_VIDEO_ID;
    audio.addEventListener('error', () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      setG04State({ status: 'failed', message: 'stream error — relay unreachable or URL rejected' });
    });
    audio.addEventListener('ended', () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
      setG04State({ status: 'failed', message: 'stream ended before the 10s proof completed' });
    });
    try {
      await audio.play();
    } catch (err) {
      setG04State({ status: 'failed', message: err instanceof Error ? err.message : String(err) });
      return;
    }
    setG04State({ status: 'playing' });
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      audio.pause();
      setG04State({ status: 'passed' });
    }, PROOF_MS);
  }, [engineStatus, g04State, stopPlayback]);

  const enginePhase = engineStatus === null ? 'pending' : engineStatus.loaded ? 'loaded' : 'error';

  return (
    <>
      <header className="site-header">
        <div className="titles">
          <h1>Streamify Web — P0 Proof Phase</h1>
          <p className="subtitle">Rust engine → wasm → relay → YouTube, in a browser</p>
        </div>
        <span className={`chip chip--${enginePhase}`} role="status" aria-label="Engine status">
          {enginePhase === 'pending'
            ? 'engine: loading…'
            : enginePhase === 'loaded'
              ? `engine: ${engineStatus?.ping ?? 'ready'}`
              : 'engine: error'}
        </span>
      </header>

      <main className="app-main">
        <div className="stack">
          <StatusCard status={engineStatus} lastSearchCount={lastSearchCount} />
          <section className="card g04-card" aria-label="G0.4 playback proof">
            <h2 className="card-title">G0.4 — 10s playback proof (standard content)</h2>
            <p className="muted">
              Resolves <code>dQw4w9WgXcQ</code> through the wasm engine + relay and plays 10 seconds of audio.
              Licensed search rows are bot-walled from datacenter IPs — expected baseline; the canary measures it
              from residential IPs.
            </p>
            <div className="g04-actions">
              <button
                type="button"
                className="btn"
                onClick={() => {
                  void runG04Proof();
                }}
                disabled={g04State?.status === 'resolving' || g04State?.status === 'playing'}
              >
                Run G0.4 proof (dQw4w9WgXcQ)
              </button>
              {g04State?.status === 'resolving' && <span className="badge">resolving…</span>}
              {g04State?.status === 'playing' && <span className="badge">▶ playing 10s…</span>}
              {g04State?.status === 'passed' && <span className="badge badge--pass">PASS: 10s audio played</span>}
              {g04State?.status === 'failed' && (
                <span className="badge badge--fail">FAIL: {g04State.message ?? 'unknown error'}</span>
              )}
            </div>
          </section>
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
          />
        </div>
      </main>

      <footer>
        <p>
          WS-W P0 Proof · one branch, one PR · G0.2 / G0.3 / G0.4 · canary:{' '}
          <a className="footer-link" href="canary.html">
            /canary.html
          </a>
        </p>
      </footer>
    </>
  );
}
