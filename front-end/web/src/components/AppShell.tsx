/**
 * components/AppShell.tsx — P3a: the app shell + routing root.
 *
 * Owns the page-lifetime concerns so audio and auth SURVIVE navigation:
 * the wasm engine stack boots here once (never per-route), the session
 * store initializes here, and the shell chrome (top header, NavRail,
 * MiniPlayerBar dock, toasts) renders around the routed screens.
 * Immersive routes (onboarding, profile selection) hide the chrome —
 * the Android immersiveRoutes policy.
 */

import { useEffect, useRef, useState } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { loadEngine, relayBaseCandidate } from '../lib/engine';
import { createEngineStack } from '../lib/wasmEngine';
import type { WasmEnginePort } from '../lib/wasmEngine';
import { usePlayerStore } from '../lib/playerStore';
import { useSessionStore } from '../lib/sessionStore';
import { useToastStore } from '../lib/toastStore';
import { cloudTrackId, recordPlayEvent } from '../lib/supabase';
import { recordTally } from '../lib/playTally';
import { NavRail } from './NavRail';
import { MiniPlayerBar } from './MiniPlayerBar';
import { Toasts } from './Toasts';
import { Icon } from './icons';
import { OnboardingGate } from '../screens/OnboardingGate';
import { YtOnboardingScreen } from '../screens/YtOnboardingScreen';
import { ProfileSelectionScreen } from '../screens/ProfileSelectionScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { UserProfileScreen } from '../screens/UserProfileScreen';
import { UpdatesHubScreen } from '../screens/UpdatesHubScreen';
import { LibraryNoticeScreen } from '../screens/PortingNoticeScreen';
import { DownloadsNoticeScreen } from '../screens/PortingNoticeScreen';

/** Android immersiveRoutes: chrome + mini-player hidden. */
const IMMERSIVE = new Set(['/onboarding', '/profile-selection']);

export function AppShell(): JSX.Element {
  const location = useLocation();
  const immersive = IMMERSIVE.has(location.pathname);

  const bind = usePlayerStore((s) => s.bind);
  const setResolveError = usePlayerStore((s) => s.setResolveError);
  const pushToast = useToastStore((s) => s.push);
  const initSession = useSessionStore((s) => s.init);
  const aliveRef = useRef(true);
  const [engineFailed, setEngineFailed] = useState(false);

  // ---- boot: search engine + wasm control plane + session (once) -------
  useEffect(() => {
    aliveRef.current = true;
    const relay = relayBaseCandidate();
    void loadEngine(relay);
    void createEngineStack({ relayBase: relay, egressMode: 'element' })
      .then((stack) => {
        if (!aliveRef.current) {
          stack.sink.dispose();
          return;
        }
        bind(stack.port);
        // Resolve failures surface on the store lane + a toast (the P2 bug
        // swallowed them — rows stuck on "playing…" with nothing honest).
        stack.port.onResolveError = (_videoId, message) => {
          setResolveError(message);
          pushToast(message, 'error');
        };
        (window as unknown as { __enginePort: WasmEnginePort }).__enginePort = stack.port;
      })
      .catch((err: unknown) => {
        console.error('[shell] engine boot failed', err);
        if (aliveRef.current) setEngineFailed(true);
      });
    void initSession();
    return () => {
      aliveRef.current = false;
    };
  }, [bind, initSession, setResolveError, pushToast]);

  // ---- play-event telemetry (cloud when signed in; local tally always) --
  const session = useSessionStore((s) => s.session);
  const snapshot = usePlayerStore((s) => s.snapshot);
  const port = usePlayerStore((s) => s.port);
  const reportedRef = useRef<string | null>(null);
  useEffect(() => {
    const current = snapshot?.currentTrack?.id ?? null;
    const prev = reportedRef.current;
    if (prev !== null && prev !== current) {
      const row = port?.getQueue().rows.find((r) => r.track.id === prev)?.track;
      if (row !== undefined) {
        const playedSec = (snapshot?.positionMs ?? 0) / 1000;
        const event = {
          trackSig: cloudTrackId(row.title, row.artist),
          title: row.title,
          artist: row.artist,
          durationPlayedSec: playedSec,
          completionRatio: row.durationMs > 0 ? Math.min(1, playedSec / (row.durationMs / 1000)) : 0,
        };
        recordTally(event);
        if (session !== null) {
          void recordPlayEvent(event);
        }
      }
    }
    reportedRef.current = current;
  }, [snapshot?.currentTrack?.id, snapshot?.positionMs, session, port]);

  const enginePhase: 'pending' | 'loaded' | 'error' = engineFailed
    ? 'error'
    : port !== null && snapshot !== null
      ? 'loaded'
      : 'pending';

  return (
    <div className={`shell ${immersive ? 'shell--immersive' : ''}`}>
      {!immersive && (
        <header className="topheader">
          <button
            type="button"
            className="topheader__brand"
            onClick={() => window.location.assign('#/')}
            aria-label="Streamify home"
          >
            <span className="topheader__logo" aria-hidden="true">
              <Icon name="playArrow" size={26} />
            </span>
            <span className="topheader__word">Streamify</span>
          </button>
          <span className={`chip chip--${enginePhase}`} role="status" aria-label="Engine status">
            {enginePhase === 'pending'
              ? 'engine: loading…'
              : enginePhase === 'loaded'
                ? 'engine: ready'
                : 'engine: error'}
          </span>
          <nav className="topheader__actions" aria-label="Account and updates">
            <a className="topheader__icon" href="#/updates" aria-label="Your updates">
              <Icon name="notifications" size={22} />
            </a>
            <a className="topheader__icon" href="#/profile" aria-label="Profile">
              <Icon name="album" size={22} />
            </a>
          </nav>
        </header>
      )}

      <main className="shell__main">
        <div key={location.pathname} className="shell__page">
          <Routes>
            <Route path="/" element={<OnboardingGate />} />
            <Route path="/onboarding" element={<YtOnboardingScreen />} />
            <Route path="/profile-selection" element={<ProfileSelectionScreen />} />
            <Route path="/settings" element={<SettingsScreen />} />
            <Route path="/profile" element={<UserProfileScreen />} />
            <Route path="/updates" element={<UpdatesHubScreen />} />
            <Route path="/library" element={<LibraryNoticeScreen />} />
            <Route path="/downloads" element={<DownloadsNoticeScreen />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </main>

      {!immersive && (
        <footer className="site-footer">
          <p>
            WS-W P3a — the UI port begins · G3a · canary:{' '}
            <a className="footer-link" href="canary.html">
              /canary.html
            </a>
          </p>
        </footer>
      )}

      {!immersive && <MiniPlayerBar />}
      {!immersive && <NavRail />}
      <Toasts />
    </div>
  );
}
