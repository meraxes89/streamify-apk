/**
 * components/IframeLanePanel.tsx — G4.3: the official-player fallback UI.
 *
 * Renders when the resolver's bot-gate (LOGIN_REQUIRED × worker egress)
 * hands a licensed track to the IFrame lane (audio/iframeLane.ts): the
 * official YouTube embed — youtube.com's own origin, the user's residential
 * egress, YouTube's own attestation — plays the track while the custom
 * engine (kept for standard / non-gated content) parks honestly.
 */

import { useEffect, useRef, useState } from 'react';
import {
  useIframeLane,
  loadIframeApi,
  YT_ERROR_TEXT,
  type YTPlayer,
} from '../audio/iframeLane';

/** Player states (the official PlayerState enum values, inlined). */
const STATE = { UNSTARTED: -1, ENDED: 0, PLAYING: 1, PAUSED: 2, BUFFERING: 3, CUED: 5 } as const;

export function IframeLanePanel(): JSX.Element | null {
  const track = useIframeLane((s) => s.track);
  const phase = useIframeLane((s) => s.phase);
  const message = useIframeLane((s) => s.message);
  const setPhase = useIframeLane((s) => s.setPhase);
  const close = useIframeLane((s) => s.close);

  const mountRef = useRef<HTMLDivElement | null>(null);
  const playerRef = useRef<YTPlayer | null>(null);
  // The API call into `new YT.Player(...)` replaces the mount node with the
  // iframe — a stable PARENT keeps React's ref alive across that swap.
  const [apiFailed, setApiFailed] = useState<string | null>(null);

  useEffect(() => {
    if (track === null) return;
    let cancelled = false;
    setApiFailed(null);

    loadIframeApi()
      .then((YT) => {
        if (cancelled || mountRef.current === null) return;
        // The YT API REPLACES its mount element with an iframe — a fresh
        // child node under the STABLE React-rendered parent keeps the ref
        // valid across track switches (destroy() removes the old iframe).
        playerRef.current?.destroy();
        const host = mountRef.current;
        host.replaceChildren();
        const mountEl = document.createElement('div');
        host.appendChild(mountEl);
        playerRef.current = new YT.Player(mountEl, {
          videoId: track.videoId,
          playerVars: {
            autoplay: 1, // the play gesture that opened this lane blesses it
            playsinline: 1,
            controls: 1,
            rel: 0,
            origin: window.location.origin,
          },
          events: {
            onReady: (event) => {
              if (cancelled) return;
              event.target.playVideo();
            },
            onStateChange: (event) => {
              if (cancelled) return;
              const data = event.data;
              if (data === STATE.PLAYING) setPhase('playing');
              else if (data === STATE.PAUSED) setPhase('paused');
              else if (data === STATE.ENDED) setPhase('ended');
              else if (data === STATE.BUFFERING) setPhase('loading');
            },
            onError: (event) => {
              if (cancelled) return;
              const text =
                YT_ERROR_TEXT[event.data] ?? `player error ${event.data}`;
              setPhase('error', text);
            },
          },
        });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const text = err instanceof Error ? err.message : String(err);
        setApiFailed(text);
        setPhase('error', text);
      });

    return () => {
      cancelled = true;
      playerRef.current?.destroy();
      playerRef.current = null;
    };
  }, [track, setPhase]);

  if (track === null) return null;

  return (
    <section className="card iframe-lane" aria-label="Official YouTube player (licensed track fallback)">
      <div className="iframe-lane__head">
        <div>
          <h3 className="iframe-lane__title">{track.title}</h3>
          <p className="muted">
            {track.artist.length > 0 ? `${track.artist} · ` : ''}
            licensed — playing through the official YouTube embed (its own
            attestation, your network)
          </p>
        </div>
        <button type="button" onClick={close} aria-label="Close the YouTube embed">
          close
        </button>
      </div>
      <div className="iframe-lane__player">
        <div ref={mountRef} className="iframe-lane__mount" aria-label={`YouTube embed: ${track.title}`} />
      </div>
      <p className="sr-only" role="status">
        {phase === 'playing'
          ? 'Playing via the official YouTube embed'
          : phase === 'paused'
            ? 'Paused'
            : phase === 'ended'
              ? 'Playback finished'
              : phase === 'error'
                ? `Playback error: ${message ?? 'unknown'}`
                : 'Loading the official player…'}
      </p>
      {phase === 'error' && (
        <div className="error-box" role="alert">
          <span>
            YouTube embed: {message ?? 'unknown error'}.{' '}
            <a
              className="footer-link"
              href={`https://www.youtube.com/watch?v=${track.videoId}`}
              target="_blank"
              rel="noreferrer"
            >
              Open on YouTube
            </a>
          </span>
        </div>
      )}
      {apiFailed === null && phase !== 'error' && (
        <p className="muted iframe-lane__hint">
          {phase === 'ended'
            ? 'Track finished — close to return to search.'
            : 'The custom engine is parked for this track (the bot-gate is egress-level); the embed above is YouTube\u2019s own player.'}
        </p>
      )}
    </section>
  );
}
