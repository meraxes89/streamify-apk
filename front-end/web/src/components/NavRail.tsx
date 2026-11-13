/**
 * components/NavRail.tsx — P3a: the navigation chrome.
 *
 * The Android app's YtBottomNavBar port (4 tabs, sliding pill, filled vs
 * outlined icons, tint + scale-pop transitions) with the §9.3 web
 * adaptation: bottom bar on phones, left rail on ≥1024px desktops — the
 * ScreenConfiguration.centerInLargeScreen(1100dp) layout surfaced as CSS.
 */

import { NavLink } from 'react-router-dom';
import { Icon, type IconName } from './icons';
import { usePlayerStore } from '../lib/playerStore';

interface NavItem {
  to: string;
  label: string;
  icon: IconName;
  activeIcon: IconName;
}

const ITEMS: NavItem[] = [
  { to: '/', label: 'Home', icon: 'homeOutline', activeIcon: 'home' },
  // Explore/Search lands with the SearchScreen route port (P3b) — the
  // Home surface carries search until then.
  { to: '/library', label: 'Library', icon: 'libraryMusicOutline', activeIcon: 'libraryMusic' },
  { to: '/downloads', label: 'Downloads', icon: 'downloadOutline', activeIcon: 'download' },
  { to: '/settings', label: 'Settings', icon: 'settings', activeIcon: 'settings' },
];

export function NavRail(): JSX.Element {
  // A live mini-player or queue keeps the dock tall — the rail sits above
  // it on mobile (CSS handles the stacking; this just toggles the class).
  // NOTE: both selectors are UNCONDITIONAL — a `||` short-circuit here
  // would skip the second hook when the first is truthy and flip React's
  // hook order (the classic conditional-hooks crash).
  const currentTrack = usePlayerStore((s) => s.snapshot?.currentTrack ?? null);
  const queueLength = usePlayerStore((s) => s.queue.rows.length);
  const hasTrack = currentTrack !== null || queueLength > 0;

  return (
    <nav
      className={`navrail ${hasTrack ? 'navrail--raised' : ''}`}
      aria-label="Main navigation"
    >
      <div className="navrail__hairline" aria-hidden="true" />
      <ul className="navrail__items">
        {ITEMS.map((item) => (
          <li key={item.to} className="navrail__itemwrap">
            <NavLink
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => `navrail__item ${isActive ? 'navrail__item--active' : ''}`}
              aria-label={item.label}
            >
              {({ isActive }) => (
                <>
                  <span className="navrail__pill" aria-hidden="true" />
                  <Icon
                    name={isActive ? item.activeIcon : item.icon}
                    size={22}
                    className="navrail__icon"
                  />
                  <span className="navrail__label">{item.label}</span>
                </>
              )}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  );
}
