/**
 * audio/botguard/protocol.ts — G3b wire constants & session shapes.
 *
 * The P3b custom mint stack (hidden srcdoc iframe, 3-layer origin
 * spoofing, hand-rolled WAA legs) is REPLACED by bgutils-js running in
 * the MAIN page realm (potMinter.ts) — the same flow kira and the
 * bgutils browser examples use, proven against real mobile browsers.
 * This module keeps only what the open-source stack still needs:
 *
 *   • WAA_REQUEST_KEY / relayUrl — the mint legs (WAA Create +
 *     GenerateIT on jnn-pa.googleapis.com) ride the relay for the
 *     single-egress session envelope (the TSG IP/ASN binding rule).
 *   • PLAYER_ENDPOINT + SAFARI_UA_OVERRIDE — the WEB_SAFARI HLS rung
 *     (a raw player POST: youtubei.js has no web_safari client, and the
 *     Safari-class UA must come from the relay's X-Relay-UA override).
 *   • The PotSession family — the potSession.ts cache/manager contract,
 *     unchanged in shape so the resolver/UI keep working.
 *
 * This module holds NO secrets: the WAA REQUEST_KEY is a public,
 * years-stable identifier embedded in bgutils-js and served inside every
 * youtube.com page (same class as the innertube client ids).
 */

/**
 * The bgutils WebPo "request key" — the first element of the Create and
 * GenerateIT JSON+protobuf bodies. Public constant (bgutils-js source).
 */
export const WAA_REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';

/** The innertube player endpoint (keyless www host — repo pattern). */
export const PLAYER_ENDPOINT = 'https://www.youtube.com/youtubei/v1/player';

/**
 * G4 — the googleapis innertube surface (youtubei.googleapis.com, the API
 * host youtubei.js can address). CANARY-MEASURED 2026-11-14 (run
 * 43564752070): its CORS preflight serves NO access-control-allow-origin
 * to foreign origins — a browser page cannot fetch it directly, so these
 * player POSTs ride the RELAY (directPlayerPost: one direct attempt per
 * page session, then the negative cache routes straight to the relay).
 * The surface is still the RIGHT target host: the googleapis frontend
 * (unlike the www one) answers IOS playability OK for licensed content
 * even from the relay's burned Cloudflare egress — the G4 matrix.
 *
 * The key is youtubei.js's public API key (Constants.API_KEY).
 */
export const GAPI_PLAYER_ENDPOINT =
  'https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false';

/**
 * The WEB_SAFARI rung's relay UA override (P3b, still the deployed
 * mechanism): browsers cannot set a Safari User-Agent (fetch forbidden
 * header), so the relay applies the Safari-class matrix UA via this
 * control header (and, per the envelope rule, strips the browser's
 * Chrome client hints — Safari sends none). Safari-flavored WEB calls
 * receive HLS (m3u8) manifests that need no GVS PO token.
 */
export const SAFARI_UA_OVERRIDE = 'safari-mac' as const;

/**
 * GVS &pot= binding: true → videoId binding (the homepage's
 * html5_generate_content_po_token experiment flag — measured set on
 * 2026-11-12 and re-confirmed by the P3b rung behavior), false →
 * visitorData. Re-verify monthly alongside the relay's UA matrix.
 */
export const GVS_BINDS_TO_VIDEO_ID = true as boolean;

// ---------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------

/**
 * A ready-to-use mint session (live, dev-seeded, or a fallback).
 * Minted by potMinter.ts (bgutils-js, main realm) — the minter lives
 * only for the page load; its TOKENS persist via potSession's cache.
 */
export interface PotSession {
  /**
   * 'live'             — a real WebPoMinter in the main realm (both
   *                      token classes, visitorData-bound envelope).
   * 'seeded'           — dev seed override (video-scoped, SpineConsole).
   * 'websafe-fallback' — GenerateIT answered but the minter path failed
   *                      (Brave-class privacy buffering / empty signal
   *                      output): the server's websafeFallbackToken
   *                      rides in place of client mints (weaker
   *                      attestation, honest label).
   * 'coldstart'        — GenerateIT itself failed; fully-local XOR cold
   *                      start tokens (sps=2 unlock of the first ~1–2 MB).
   */
  kind: 'live' | 'seeded' | 'websafe-fallback' | 'coldstart';
  visitorData: string;
  /** Integrity-token expiry (live) or fallback horizon, epoch ms. */
  expiresAtMs: number;
  /**
   * GVS &pot= binding: true → video id (html5_generate_content_po_token
   * experiment set on the homepage), false → visitorData.
   */
  gvsBindsToVideoId: boolean;
  /** PLAYER-context poToken (serviceIntegrityDimensions) — videoId-bound. */
  mintPlayerToken: (videoId: string) => Promise<string>;
  /** GVS-context poToken (googlevideo &pot=) — binding per the flag above. */
  mintGvsToken: (videoId: string) => Promise<string>;
  /**
   * The SESSION-bound token (visitorData), minted eagerly and persisted
   * to localStorage (~6 h). Null when the kind has no minter
   * (websafe-fallback/coldstart carry their token inline).
   */
  sessionToken: string | null;
}

/**
 * The localStorage pot-session cache. Restoring a fresh entry skips
 * BotGuard for replayed tracks (playerTokens map) and serves &pot=
 * (sessionToken) instantly; a NEW video still triggers a full live mint
 * (the minter itself cannot cross page loads — only its tokens can).
 * A cache whose visitorData no longer matches the live YouTube.js
 * session is DROPPED (binding drift = split-brain risk).
 */
export interface PotSessionCache {
  schema: 1;
  kind: 'live' | 'websafe-fallback' | 'coldstart';
  visitorData: string;
  sessionToken: string | null;
  gvsBindsToVideoId: boolean;
  mintedAt: number;
  ttlSecs: number;
  /** Recently minted player tokens (videoId → token), LRU-capped. */
  playerTokens: Record<string, string>;
}

/** Dev seed (from pot-lab's provider-recipe Node mint) — video-scoped. */
export interface PotSeed {
  videoId: string;
  visitorData: string;
  playerToken: string;
  gvsToken?: string;
  /** Seconds the seed stays trustworthy after minting (default 6 h). */
  ttlSecs?: number;
  mintedAt?: number;
}

/** Mint pipeline phases (surfaced to the UI 1:1). */
export type MintPhase =
  | 'idle'
  | 'challenge'
  | 'interpreter'
  | 'vm'
  | 'snapshot'
  | 'generate-it'
  | 'minter'
  | 'session-token'
  | 'cache'
  | 'ready';

export interface MintStatus {
  phase: MintPhase;
  detail: string;
  /**
   * P3b/G3b: the privacy-buffer fallback fired (Brave-class
   * fingerprinting blocks left webPoSignalOutput empty and the mint
   * continued on skipPrivacyBuffer / websafeFallbackToken). The UI
   * surfaces the Brave Shields advisory when set.
   */
  braveShieldsAdvice: boolean;
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** Mint failure with a machine-readable code the UI can branch on. */
export class MintError extends Error {
  readonly code:
    | 'no-challenge'
    | 'interpreter'
    | 'vm'
    | 'snapshot'
    | 'generate-it'
    | 'empty-token';

  constructor(code: MintError['code'], message: string) {
    super(message);
    this.name = 'MintError';
    this.code = code;
  }
}

// ---------------------------------------------------------------------------
// Relay URL builders
// ---------------------------------------------------------------------------

/** Wrap an upstream URL through the relay's /r passthrough. */
export function relayUrl(relayBase: string, target: string): string {
  return `${relayBase.replace(/\/$/, '')}/r?u=${encodeURIComponent(target)}`;
}
