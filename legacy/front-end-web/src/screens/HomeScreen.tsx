/**
 * screens/HomeScreen.tsx — the production home.
 *
 * The P3a home was the engine dogfood surface (search + queue + account
 * + player bar + GSF panel + spine console stacked under the feed) —
 * the APK's embedded dev console pattern. It is now a clean product surface:
 * the jam join prompt rail (WS-W engine facade breadth — the invite-link
 * lowering of JoinPromptSheet.kt, see components/JoinPromptRail.tsx) plus
 * the UniversalHomeScreen shelves feed (the UniversalHomeScreen.kt /
 * HomeScreen.kt port in lib/homeStore.ts) plus the honest resolve-error
 * banner. Everything diagnostic moved to the /debug route
 * (screens/DebugScreen.tsx, build-time gated out of production bundles).
 *
 * Playback surfaces that replaced the dogfood rows: MiniPlayerBar dock +
 * FullPlayerSheet (P1), /search (P2), /queue (P1), /settings and
 * /profile for the account lane.
 */

import { usePlayerStore } from '../lib/playerStore';
import { JoinPromptRail } from '../components/JoinPromptRail';
import { UniversalHomeScreen } from './UniversalHomeScreen';

export function HomeScreen(): JSX.Element {
  const resolveError = usePlayerStore((s) => s.resolveError);

  return (
    <div className="stack home-stack">
      {/* WS-W engine facade breadth: the jam join prompt (the invite-link
          lowering of JoinPromptSheet.kt — self-contained rail). */}
      <JoinPromptRail />
      {/* P2 browse: the YT-Music-style shelves feed (the UniversalHomeScreen
          port) — the home's entire production surface. */}
      <UniversalHomeScreen />
      {/* The P3a honesty lane stays: resolve failures surface on the home,
          never silently swallowed. */}
      {resolveError !== null && <p className="home-resolve-error">{resolveError}</p>}
    </div>
  );
}
