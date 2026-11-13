/**
 * audio/botguard/protocol.ts — W1.0 wire types & endpoints.
 *
 * The minting stack speaks three protocols, all relayed same-origin (see
 * front-end/relay/README.md "P1 / W1.0 — the minting surface" for why every
 * leg must egress through the relay):
 *
 *   1. GET  <relay>/bg-session            → { visitorData, ytcfg, ytAtN }
 *   2. GET  <relay>/r?u=<interpreter-url> → BotGuard interpreter JS
 *   3. POST <relay>/r?u=<GenerateIT>      → integrity token (WAA RPC)
 *
 * BINDING RULES (yt-dlp pot/utils.py get_webpo_content_binding, verified
 * against the live homepage 2026-11-12):
 *   • player request  serviceIntegrityDimensions.poToken ← VIDEO_ID binding
 *   • googlevideo     &pot=                               ← VIDEO_ID binding
 *     while the homepage's html5_generate_content_po_token experiment flag
 *     is set (it is), else VISITOR_DATA binding.
 *
 * This module holds NO secrets: the WAA REQUEST_KEY below is a public,
 * years-stable identifier embedded in bgutils-js and served inside every
 * youtube.com page (same class as the innertube client ids). The GAPI key
 * for GenerateIT comes from bgutils-js's own getHeaders() at call time and
 * never appears in this source.
 */

/**
 * The bgutils WebPo "request key" — the first element of the GenerateIT
 * JSON+protobuf body. Public constant (bgutils-js README / provider source).
 */
export const WAA_REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';

/** Waa/GenerateIT — mints the integrity token from a BotGuard snapshot. */
export const WAA_GENERATE_IT_URL =
  'https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT';

/** The innertube player endpoint (keyless www host — repo pattern). */
export const PLAYER_ENDPOINT = 'https://www.youtube.com/youtubei/v1/player';

/** WEB client spec (clientName 1) — the PO-token-capable rung. */
export const WEB_CLIENT = {
  clientName: 'WEB',
  clientVersion: '2.20261111.01.00',
  clientNumber: '1',
} as const;

// ---------------------------------------------------------------------------
// Shapes
// ---------------------------------------------------------------------------

/** GET /bg-session response (relay-extracted homepage triple). */
export interface BgSessionInfo {
  visitorData: string;
  /** Raw ytcfg JSON (strict) — injected as window.yt.config_ before the VM. */
  ytcfg: string;
  /** Raw window.ytAtN(...) payload — parseLooseJSON → R.bgChallenge. */
  ytAtN: string;
  fetchedAt: number;
  expiresAt?: number;
  cache?: 'hit' | 'miss';
}

/** The descrambled challenge from ytAtN (camelCase TrustedResourceUrl!). */
export interface BgChallenge {
  program: string;
  globalName: string;
  interpreterHash?: string;
  interpreterUrl: string;
}

/** Waa/GenerateIT response tuple. */
export type IntegrityTokenData = {
  integrityToken: string;
  estimatedTtlSecs: number;
  mintRefreshThreshold: number;
  websafeFallbackToken: string;
};

/** A ready-to-use mint session (live or dev-seeded). */
export interface PotSession {
  kind: 'live' | 'seeded';
  visitorData: string;
  /** Integrity-token expiry (live) or seed horizon (seeded), epoch ms. */
  expiresAtMs: number;
  /**
   * GVS &pot= binding: true → video id (html5_generate_content_po_token
   * experiment set on the homepage), false → visitorData.
   */
  gvsBindsToVideoId: boolean;
  /** PLAYER-context poToken (serviceIntegrityDimensions). */
  mintPlayerToken: (videoId: string) => Promise<string>;
  /** GVS-context poToken (googlevideo &pot=) — binding per the flag above. */
  mintGvsToken: (videoId: string) => Promise<string>;
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
  | 'bg-session'
  | 'challenge'
  | 'interpreter'
  | 'vm'
  | 'snapshot'
  | 'generate-it'
  | 'minter'
  | 'ready';

export interface MintStatus {
  phase: MintPhase;
  detail: string;
  /** Set when the relay itself is the old P0 deployment (no minting surface). */
  relayTooOld: boolean;
}

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** Mint failure with a machine-readable code the UI can branch on. */
export class MintError extends Error {
  readonly code:
    | 'relay-too-old'
    | 'bg-session'
    | 'no-challenge'
    | 'interpreter'
    | 'vm'
    | 'vm-score'
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
