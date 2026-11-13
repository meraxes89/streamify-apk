/**
 * theme/tokens.ts — P3a: the StreamifyTheme tokens, TS side.
 *
 * Mirrors theme/tokens.css (the values are the Android ui/theme package
 * ported verbatim). Use for inline styles / canvas drawing where CSS
 * custom properties are awkward; everything else should consume the CSS
 * vars so re-theming stays one-file.
 */

export const colors = {
  bgBase: '#030303',
  bgSurface: '#0f0f0f',
  bgSurfaceElevated: '#212121',
  bgCard: '#181818',
  bgCardHover: '#282828',
  bgPlayer: '#0a0a0a',
  bgChipInactive: '#212121',
  bgChipActive: '#ffffff',
  bgMiniPlayer: '#212121',
  bgSplash: '#07070a',
  bgUpdates: '#0a0a0f',
  bgProfileCard: '#141418',
  bgUpdatesRow: '#16161e',
  bgUpdatesChip: '#23232e',
  primary: '#ff0000',
  primaryAccent: '#ff0033',
  primaryHover: '#ff3333',
  primaryDark: '#cc0000',
  activeControl: '#ffffff',
  accentBlue: '#3ea6ff',
  textMain: '#ffffff',
  textSecondary: '#aaaaaa',
  textTertiary: '#717171',
  textOnActiveChip: '#030303',
  borderSubtle: '#282828',
  borderChip: '#383838',
  divider: '#212121',
  errorRed: '#ff4d4d',
  explicit: '#888888',
  updatesGreen: '#1ed760',
  spotify: '#1db954',
  terminalGreen: '#4af626',
  danger: '#d32f2f',
  dangerBorder: '#e53935',
  dangerCard: '#200a0a',
  dangerText: '#ef9a9a',
} as const;

export const splashRibbons = [
  '#8b5cf6',
  '#a855f7',
  '#06b6d4',
  '#10b981',
  '#f59e0b',
  '#ff0000',
  '#ec4899',
] as const;

export const radius = {
  sm: 4,
  md: 8,
  lg: 12,
  xl: 16,
  full: 50,
  search: 24,
} as const;

export const spacing = {
  xxs: 2,
  xs: 4,
  sm: 8,
  md: 12,
  lg: 16,
  xl: 20,
  xxl: 24,
  huge: 32,
  giant: 48,
} as const;

export const sizes = {
  bottomNavHeight: 58,
  miniPlayerHeight: 64,
  miniPlayerArt: 48,
  miniProgressHeight: 2,
  trackRowHeight: 56,
  trackRowArt: 48,
  chipHeight: 32,
  playerActionPill: 36,
  screenMaxWidth: 1100,
} as const;

export const animation = {
  fast: 150,
  normal: 300,
  slow: 500,
  superSlow: 800,
  /** YtBottomNavBar's spring (DampingRatioMediumBouncy/StiffnessLow feel). */
  springBouncy: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
  easeStandard: 'cubic-bezier(0.4, 0, 0.2, 1)',
} as const;
