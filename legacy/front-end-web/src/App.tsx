/**
 * App.tsx — P3a: the root — splash gate + router + shell.
 *
 * The Android root flow (MainActivity): PrismaticSplashScreen on cold
 * start (once per browser session), then the gated app shell. The
 * engine/session boot lives INSIDE AppShell so playback survives
 * navigation; the splash is a pure overlay that dissolves into the
 * already-booting app (the APK's parallel pre-warm, same shape).
 */

import { useCallback, useEffect, useState } from 'react';
import { HashRouter } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { PrismaticSplashScreen } from './screens/PrismaticSplashScreen';

const SPLASH_SEEN_KEY = 'streamify.splash.v1';

function splashSeen(): boolean {
  try {
    return sessionStorage.getItem(SPLASH_SEEN_KEY) === 'seen';
  } catch {
    return true; // storage unavailable — never trap the user
  }
}

function markSplashSeen(): void {
  try {
    sessionStorage.setItem(SPLASH_SEEN_KEY, 'seen');
  } catch {
    /* private mode */
  }
}

export function App(): JSX.Element {
  const [showSplash, setShowSplash] = useState(() => !splashSeen());

  // Reduced-motion users skip the 4.4 s choreography (the screen itself
  // also honors this, but skipping the mount avoids the flash entirely).
  useEffect(() => {
    if (showSplash && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      markSplashSeen();
      setShowSplash(false);
    }
  }, [showSplash]);

  const onSplashComplete = useCallback(() => {
    markSplashSeen();
    setShowSplash(false);
  }, []);

  return (
    <>
      <HashRouter>
        <AppShell />
      </HashRouter>
      {showSplash && <PrismaticSplashScreen onComplete={onSplashComplete} />}
    </>
  );
}
