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

import { Suspense, lazy, useEffect, useRef, useState } from 'react';
import { Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { loadEngine, relayBaseCandidate } from '../lib/engine';
import { createEngineStack } from '../lib/wasmEngine';
import type { WasmEnginePort } from '../lib/wasmEngine';
import { usePlayerStore } from '../lib/playerStore';
// P2-B: the queue state machine — bound beside the transport mirror so
// the machine owns boundaries, scrobble, gapless, and restore from boot.
import { useQueueStore } from '../lib/playback/queueStore';
import { useSessionStore } from '../lib/sessionStore';
import { useToastStore } from '../lib/toastStore';
import { initSlog, slog, slogThrow } from '../lib/slog';
import { APP_BUILD, APP_VERSION } from '../version';
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
// P2 (browse) screens:
import { LibraryScreen } from '../screens/LibraryScreen';
import { SearchScreen } from '../screens/SearchScreen';
import { AlbumRoute } from '../screens/AlbumScreen';
import { ArtistRoute } from '../screens/ArtistScreen';
import { DownloadsNoticeScreen } from '../screens/PortingNoticeScreen';
// W-W admin/stats port: AdminDashboard + AdminTerminal + StatsWrapped
// (routes mirror AppNavGraph.kt: admin / admin_terminal / wrapped).
import { AdminDashboardScreen } from '../screens/AdminDashboardScreen';
import { AdminTerminalScreen } from '../screens/AdminTerminalScreen';
import { StatsWrappedScreen } from '../screens/StatsWrappedScreen';
// P1 (listen loop) — kept as a compact, self-contained block so concurrent
// PRs touching this file stay conflict-free.
import { FullPlayerSheet } from '../screens/FullPlayerSheet';
import { QueueScreen } from '../screens/QueueScreen';
import { LyricsScreen } from '../screens/LyricsScreen';
import { useUiStore } from '../lib/uiStore';

/** Android immersiveRoutes: chrome + mini-player hidden. */
const IMMERSIVE = new Set(['/onboarding', '/profile-selection']);

/**
 * W-W home-teardown: the /debug dev-console route is BUILD-TIME GATED.
 * `import.meta.env.DEV` is statically replaced at build time, so in
 * production builds the dynamic import below sits in dead code and Rollup
 * never emits the debug chunk — the console cannot leak into shipped
 * bundles. `VITE_DEBUG_ROUTE=1` is the opt-in for branch deploys that need
 * the diagnostic surface on a Pages URL (soak drills; web-deploy.yml
 * already ships per-branch).
 */
const DEBUG_ROUTE =
  import.meta.env.DEV || (import.meta.env.VITE_DEBUG_ROUTE as string | undefined) === '1';

const DebugScreen = DEBUG_ROUTE
  ? // eslint-disable-next-line @typescript-eslint/no-unnecessary-condition -- build-time constant
    lazy(() => import('../screens/DebugScreen'))
  : null;

export function AppShell(): JSX.Element {
  const location = useLocation();
  const immersive = IMMERSIVE.has(location.pathname);

  // P1: the FullPlayerSheet overlay visibility (layer 3 above the shell).
  const fullPlayerOpen = useUiStore((s) => s.fullPlayerOpen);
  const closeFullPlayer = useUiStore((s) => s.closeFullPlayer);

  const bind = usePlayerStore((s) => s.bind);
  const bindQueue = useQueueStore((s) => s.bind);
  const setResolveError = usePlayerStore((s) => s.setResolveError);
  const pushToast = useToastStore((s) => s.push);
  const initSession = useSessionStore((s) => s.init);
  const aliveRef = useRef(true);
  const [engineFailed, setEngineFailed] = useState(false);

  // ---- boot: search engine + wasm control plane + session (once) -------
  useEffect(() => {
    aliveRef.current = true;
    // The diagnostic ring boots with the shell: opt-in capture, crash hook,
    // boot banner (see lib/slog.ts — the util/SLog.kt port).
    initSlog(`web v${APP_VERSION} build ${APP_BUILD}`);
    slog.i('shell', 'boot — loading search engine + wasm control plane');
    const relay = relayBaseCandidate();
    void loadEngine(relay);
    void createEngineStack({ relayBase: relay, egressMode: 'element' })
      .then((stack) => {
        if (!aliveRef.current) {
          stack.sink.dispose();
          return;
        }
        bind(stack.port);
        bindQueue(stack.port);
        slog.i('shell', 'engine ready — control plane + queue machine bound');
        // Resolve failures surface on the store lane + a toast (the P2 bug
        // swallowed them — rows stuck on "playing…" with nothing honest).
        stack.port.onResolveError = (_videoId, message) => {
          setResolveError(message);
          pushToast(message, 'error');
          slog.w('resolve', message);
        };
        (window as unknown as { __enginePort: WasmEnginePort }).__enginePort = stack.port;
      })
      .catch((err: unknown) => {
        console.error('[shell] engine boot failed', err);
        slogThrow('shell', 'engine boot failed', err);
        if (aliveRef.current) setEngineFailed(true);
      });
    void initSession();
    return () => {
      aliveRef.current = false;
    };
  }, [bind, bindQueue, initSession, setResolveError, pushToast]);

  // P2-B: play-event telemetry lives in the queue store's session
  // runtime now — the scrobbler (min(half, 4 min) of played time, fired
  // once per session, offline outbox) replaced the old transition-
  // based effect. Nothing to wire here; the snapshot subscription in
  // queueStore.bind owns accrual + the local tally + the cloud lane.
  const snapshot = usePlayerStore((s) => s.snapshot);
  const port = usePlayerStore((s) => s.port);

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
            <Route path="/search" element={<SearchScreen />} />
            <Route path="/library" element={<LibraryScreen />} />
            <Route path="/artist/:artistName" element={<ArtistRoute />} />
            <Route path="/album/:albumName" element={<AlbumRoute />} />
            <Route path="/downloads" element={<DownloadsNoticeScreen />} />
            <Route path="/queue" element={<QueueScreen />} />
            <Route path="/lyrics" element={<LyricsScreen />} />
            {/* W-W admin/stats port — AppNavGraph: admin / admin_terminal /
                wrapped. Entries live in Settings + Profile (activation is
                part of the deliverable — the P1 lesson). */}
            <Route path="/admin" element={<AdminDashboardScreen />} />
            <Route path="/admin-terminal" element={<AdminTerminalScreen />} />
            <Route path="/wrapped" element={<StatsWrappedScreen />} />
            {/* W-W home-teardown: the dev console route — build-time gated
                (see DEBUG_ROUTE above); never emitted in production. */}
            {DebugScreen !== null && (
              <Route
                path="/debug"
                element={
                  <Suspense fallback={<div className="shell__pageloading" role="status" />}>
                    <DebugScreen />
                  </Suspense>
                }
              />
            )}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </div>
      </main>

      {!immersive && (
        <footer className="site-footer">
          <p>
            Streamify Web · WS-W · canary:{' '}
            <a className="footer-link" href="canary.html">
              /canary.html
            </a>
            {DebugScreen !== null && (
              <>
                {' '}
                ·{' '}
                <a className="footer-link" href="#/debug">
                  /debug
                </a>
              </>
            )}
          </p>
        </footer>
      )}

      {!immersive && <MiniPlayerBar />}
      {!immersive && <NavRail />}
      {/* P1: the full-player overlay — layer 3 above the shell chrome;
          null until a track exists (the Kotlin `isPlayerExpanded && hasTrack`). */}
      {!immersive && (
        <FullPlayerSheet open={fullPlayerOpen} onCollapse={closeFullPlayer} />
      )}
      <Toasts />
    </div>
  );
}
