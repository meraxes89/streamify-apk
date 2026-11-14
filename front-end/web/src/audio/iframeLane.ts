/**
 * audio/iframeLane.ts — G4.3: the official-player fallback lane (the senior
 * verdict's pure-web posture).
 *
 * WHY THIS LANE EXISTS (decisions/G4.3-egress-discipline.md): licensed
 * music is bot-gated at the EGRESS level — our shared Cloudflare Worker IP
 * earns LOGIN_REQUIRED ("Sign in to confirm you're not a bot") on Content-ID
 * tracks, and the BgUtils-documented ceiling says PO tokens do not bypass
 * IP-based login restrictions. The ONE surface that plays licensed music
 * from the user's own residential IP without our engine touching the
 * bytes is the OFFICIAL YouTube IFrame Player: it executes inside a
 * genuine https://www.youtube.com origin iframe, runs YouTube's own
 * attestation/SABR handshake natively, and streams media from the user's
 * own network. The custom WASM/element pipeline stays the lane for
 * standard / non-gated content; this lane takes what it cannot have.
 *
 * SCOPE (honest): a per-track delegate, NOT a queue participant — the
 * engine's queue/bar/media-session keep their own truth for element-lane
 * tracks; the IFrame panel owns its track while it is open. The embed may
 * show YouTube's own ads/branding — that is the price (and the point) of
 * the official player.
 */

import { create } from 'zustand';

// ---------------------------------------------------------------------------
// The store (the panel's state)
// ---------------------------------------------------------------------------

export interface IframeLaneTrack {
  videoId: string;
  title: string;
  artist: string;
  durationSec?: number;
  thumbnailUrl?: string;
}

export type IframeLanePhase = 'loading' | 'playing' | 'paused' | 'ended' | 'error';

export interface IframeLaneState {
  track: IframeLaneTrack | null;
  phase: IframeLanePhase;
  message: string | null;
  open(track: IframeLaneTrack): void;
  setPhase(phase: IframeLanePhase, message?: string | null): void;
  close(): void;
}

export const useIframeLane = create<IframeLaneState>((set) => ({
  track: null,
  phase: 'loading',
  message: null,
  open(track) {
    set({ track, phase: 'loading', message: null });
  },
  setPhase(phase, message = null) {
    set({ phase, message });
  },
  close() {
    set({ track: null, phase: 'loading', message: null });
  },
}));

// ---------------------------------------------------------------------------
// The IFrame API loader (the official script — a plain <script> tag, no CORS)
// ---------------------------------------------------------------------------

/**
 * Minimal typings for the official YouTube IFrame Player API
 * (developers.google.com/youtube/iframe_api_reference) — only the surface
 * this lane drives.
 */
interface YTPlayer {
  playVideo(): void;
  pauseVideo(): void;
  stopVideo(): void;
  destroy(): void;
}

interface YTPlayerEvent {
  target: YTPlayer;
  data: number;
}

interface YTPlayerOptions {
  videoId: string;
  playerVars?: Record<string, string | number>;
  events?: {
    onReady?: (event: YTPlayerEvent) => void;
    onStateChange?: (event: YTPlayerEvent) => void;
    onError?: (event: YTPlayerEvent) => void;
  };
}

interface YTNamespace {
  Player: new (el: HTMLElement | string, options: YTPlayerOptions) => YTPlayer;
  PlayerState: {
    UNSTARTED: number;
    ENDED: number;
    PLAYING: number;
    PAUSED: number;
    BUFFERING: number;
    CUED: number;
  };
}

declare global {
  interface Window {
    YT?: YTNamespace;
    onYouTubeIframeAPIReady?: () => void;
  }
}

let apiPromise: Promise<YTNamespace> | null = null;

/** Load (once) the official IFrame API and resolve its namespace. */
function loadIframeApi(): Promise<YTNamespace> {
  if (apiPromise !== null) return apiPromise;
  apiPromise = new Promise<YTNamespace>((resolve, reject) => {
    if (window.YT !== undefined && window.YT.Player !== undefined) {
      resolve(window.YT);
      return;
    }
    const prev = window.onYouTubeIframeAPIReady;
    window.onYouTubeIframeAPIReady = () => {
      prev?.();
      if (window.YT !== undefined) resolve(window.YT);
      else reject(new Error('YT iframe API loaded without its namespace'));
    };
    const script = document.createElement('script');
    script.src = 'https://www.youtube.com/iframe_api';
    script.async = true;
    script.onerror = () => {
      apiPromise = null; // a failed load must not poison retries
      reject(new Error('failed to load https://www.youtube.com/iframe_api'));
    };
    document.head.appendChild(script);
    // A hard timeout: the API script must answer within 10 s or the lane
    // reports honestly instead of hanging the panel in 'loading'.
    setTimeout(() => {
      if (window.YT === undefined) {
        apiPromise = null;
        reject(new Error('YT iframe API did not initialize within 10 s'));
      }
    }, 10_000);
  });
  return apiPromise;
}

/** The official error codes (the iframe_api_reference table). */
const YT_ERROR_TEXT: Record<number, string> = {
  2: 'invalid video parameters',
  5: 'HTML5 player error',
  100: 'video not found',
  101: 'the owner does not allow this video to be played embedded',
  150: 'the owner does not allow this video to be played embedded',
};

export { loadIframeApi, YT_ERROR_TEXT };
export type { YTPlayer, YTNamespace };
