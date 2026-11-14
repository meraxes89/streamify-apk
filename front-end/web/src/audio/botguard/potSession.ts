/**
 * audio/botguard/potSession.ts — the session manager (G3b).
 *
 * One mint session per integrity-token lifetime (~12 h): the session is a
 * module singleton with in-flight dedupe (five parts of the app asking for
 * a token simultaneously must trigger exactly ONE BotGuard run). The
 * mint itself now lives in potMinter.ts (bgutils-js, main realm).
 *
 * Sources of tokens (strongest first):
 *   • 'live'             — the bgutils-js mint (potMinter.runMintSession):
 *                          dual tokens (player = videoId, session =
 *                          visitorData) from the real browser hardware.
 *   • 'cached'           — a restored localStorage session: replayed
 *                          videos serve their cached player tokens and &pot=
 *                          serves the cached session token with ZERO
 *                          BotGuard; a NEW video triggers a full live mint.
 *   • 'seeded'           — a dev override (pot-lab's provider-recipe Node
 *                          mint), video-scoped.
 *   • 'websafe-fallback' — the server's websafeFallbackToken in place of
 *                          mints (Brave-class degradation).
 *   • 'coldstart'        — local XOR cold start tokens (sps=2 window).
 *
 * BINDING-DRIFT GUARD (G3b): a restored cache whose visitorData no longer
 * matches the live YouTube.js session (ytSession.ts owns the visitorData)
 * is DROPPED — tokens minted for another session's visitor are worse than
 * no tokens (the split-brain class of failure). The check runs lazily on
 * the first token access, once the session is bootable.
 */

import { runMintSession } from './potMinter';
import { getYt } from '../../lib/ytSession';
import type { MintStatus, PotSeed, PotSession, PotSessionCache } from './protocol';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

let session: PotSession | null = null;
let inFlight: Promise<PotSession> | null = null;
let seed: PotSeed | null = null;
/**
 * True ONLY when THIS page load ran a real mint (a minter exists for NEW
 * videos). A restored cache session serves its cached tokens but cannot
 * mint fresh ones — ensureLiveSession must replace it on demand.
 */
let sessionHasMinter = false;

let status: MintStatus = {
  phase: 'idle',
  detail: 'no mint yet',
  braveShieldsAdvice: false,
};
const listeners = new Set<(s: MintStatus) => void>();

function setStatus(next: Partial<MintStatus>): void {
  status = { ...status, ...next };
  for (const l of [...listeners]) l(status);
}

// ---------------------------------------------------------------------------
// The localStorage cache (replays skip BotGuard)
// ---------------------------------------------------------------------------

const CACHE_KEY = 'streamify.pot.session.v1';
/** LRU cap for cached per-video player tokens. */
const CACHE_PLAYER_TOKENS_MAX = 16;
/** Never trust a cached entry closer than this to its expiry. */
const CACHE_FRESHNESS_MARGIN_MS = 5 * 60 * 1000;

function readCache(): PotSessionCache | null {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (raw === null) return null;
    const parsed = JSON.parse(raw) as PotSessionCache;
    if (
      parsed.schema !== 1 ||
      typeof parsed.visitorData !== 'string' ||
      parsed.visitorData.length === 0 ||
      typeof parsed.mintedAt !== 'number' ||
      typeof parsed.ttlSecs !== 'number'
    ) {
      return null;
    }
    return parsed;
  } catch {
    return null; // private mode / corrupted entry — mint from scratch
  }
}

function cacheFresh(cache: PotSessionCache): boolean {
  return Date.now() < cache.mintedAt + cache.ttlSecs * 1000 - CACHE_FRESHNESS_MARGIN_MS;
}

function writeCache(session: PotSession, playerTokens: Record<string, string>): void {
  if (session.kind === 'seeded' || session.kind === 'coldstart') return; // never persist
  const ttlSecs = Math.min(
    Math.max(Math.round((session.expiresAtMs - Date.now()) / 1000), 60),
    6 * 3600, // conservative cache horizon: 6 of the measured ~12 h
  );
  const cache: PotSessionCache = {
    schema: 1,
    kind: session.kind,
    visitorData: session.visitorData,
    sessionToken: session.sessionToken,
    gvsBindsToVideoId: session.gvsBindsToVideoId,
    mintedAt: Date.now(),
    ttlSecs,
    playerTokens,
  };
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(cache));
  } catch {
    /* private mode — the live session still serves this page load */
  }
}

function dropCache(): void {
  try {
    localStorage.removeItem(CACHE_KEY);
  } catch {
    /* best-effort */
  }
}

/** Per-video player tokens minted this page load (cache write-through). */
const playerTokens = new Map<string, string>();

function recordPlayerToken(videoId: string, token: string): void {
  playerTokens.set(videoId, token);
  if (playerTokens.size > CACHE_PLAYER_TOKENS_MAX) {
    // LRU: drop the oldest entry (insertion order).
    const oldest = playerTokens.keys().next();
    if (!oldest.done) playerTokens.delete(oldest.value);
  }
  if (session !== null && (session.kind === 'live' || session.kind === 'websafe-fallback')) {
    writeCache(session, Object.fromEntries(playerTokens));
  }
}

/**
 * A session restored from localStorage — token-serving only (no minter for
 * new videos): cached player tokens + the session token. A cache miss on
 * mintPlayerToken escalates to a full live mint via the caller's autoMint.
 */
function cachedSession(cache: PotSessionCache): PotSession {
  const tokens = new Map(Object.entries(cache.playerTokens));
  return {
    kind: cache.kind,
    visitorData: cache.visitorData,
    expiresAtMs: cache.mintedAt + cache.ttlSecs * 1000,
    gvsBindsToVideoId: cache.gvsBindsToVideoId,
    mintPlayerToken: async (videoId: string) => {
      const hit = tokens.get(videoId);
      if (hit !== undefined) return hit;
      throw new Error('cache miss — a fresh live mint is required for this video');
    },
    mintGvsToken: async (videoId: string) => {
      if (cache.gvsBindsToVideoId) {
        const hit = tokens.get(videoId);
        if (hit !== undefined) return hit;
      }
      if (cache.sessionToken !== null) return cache.sessionToken;
      throw new Error('cache miss — no session token cached');
    },
    sessionToken: cache.sessionToken,
  };
}

// Restore at module load: a fresh cache instantly serves &pot= and replays.
// The binding-drift guard (dropIfDrifted) re-validates against the live
// YouTube.js session lazily, on first token access.
(function restoreCache(): void {
  const cache = readCache();
  if (cache === null || !cacheFresh(cache)) return;
  session = cachedSession(cache);
  sessionHasMinter = false;
  for (const [videoId, token] of Object.entries(cache.playerTokens)) {
    playerTokens.set(videoId, token);
  }
  setStatus({
    phase: 'cache',
    detail: `restored ${cache.kind} session from cache (visitorData ${cache.visitorData.slice(0, 12)}…, ${Object.keys(cache.playerTokens).length} cached token(s))`,
  });
})();

// ---------------------------------------------------------------------------
// Subscriptions (UI mirror)
// ---------------------------------------------------------------------------

/** Subscribe to mint status; returns an unsubscribe fn. */
export function subscribeMint(fn: (s: MintStatus) => void): () => void {
  listeners.add(fn);
  fn(status);
  return () => listeners.delete(fn);
}

/** Current status snapshot. */
export function potSnapshot(): MintStatus {
  return status;
}

// ---------------------------------------------------------------------------
// Seeds (dev)
// ---------------------------------------------------------------------------

/**
 * Install a dev seed (video-scoped). Accepts the pot-lab JSON shape:
 * { videoId, visitorData, playerToken, gvsToken?, ttlSecs?, mintedAt? }.
 * Throws on a malformed seed — the UI surfaces it.
 */
export function seedPotSession(raw: string): { videoId: string } {
  const parsed = JSON.parse(raw) as Partial<PotSeed>;
  if (
    typeof parsed.videoId !== 'string' ||
    typeof parsed.visitorData !== 'string' ||
    typeof parsed.playerToken !== 'string' ||
    parsed.videoId.length === 0 ||
    parsed.visitorData.length === 0 ||
    parsed.playerToken.length === 0
  ) {
    throw new Error('seed needs { videoId, visitorData, playerToken } (pot-lab pot-seed.json)');
  }
  const mintedAt = typeof parsed.mintedAt === 'number' ? parsed.mintedAt : Date.now();
  const ttlSecs = typeof parsed.ttlSecs === 'number' ? parsed.ttlSecs : 6 * 3600;
  seed = {
    videoId: parsed.videoId,
    visitorData: parsed.visitorData,
    playerToken: parsed.playerToken,
    gvsToken: parsed.gvsToken,
    ttlSecs,
    mintedAt,
  };
  const expiresAtMs = mintedAt + ttlSecs * 1000;
  session = {
    kind: 'seeded',
    visitorData: parsed.visitorData,
    expiresAtMs,
    // The pot-lab mints follow the live homepage flag → video-id binding.
    gvsBindsToVideoId: true,
    mintPlayerToken: async () => parsed.playerToken as string,
    mintGvsToken: async () => parsed.gvsToken ?? (parsed.playerToken as string),
    sessionToken: parsed.gvsToken ?? null,
  };
  sessionHasMinter = false; // seeds answer only their own video
  setStatus({
    phase: 'ready',
    detail: `SEEDED token for ${parsed.videoId} (expires ${new Date(expiresAtMs).toLocaleTimeString()})`,
    braveShieldsAdvice: false,
  });
  return { videoId: parsed.videoId };
}

/** Drop the seed (back to live minting). */
export function clearSeed(): void {
  seed = null;
  session = null;
  sessionHasMinter = false;
  setStatus({
    phase: 'idle',
    detail: 'seed cleared',
    braveShieldsAdvice: false,
  });
}

/** The seeded video id, if any (UI label). */
export function seededVideoId(): string | null {
  return seed !== null ? seed.videoId : null;
}

/** Drop the mint session AND the localStorage cache (dev reset). */
export function resetPotSession(): void {
  session = null;
  seed = null;
  sessionHasMinter = false;
  playerTokens.clear();
  dropCache();
  setStatus({
    phase: 'idle',
    detail: 'session + cache dropped',
    braveShieldsAdvice: false,
  });
}

// ---------------------------------------------------------------------------
// Session acquisition
// ---------------------------------------------------------------------------

function sessionUsable(): boolean {
  return session !== null && Date.now() < session.expiresAtMs - 5 * 60 * 1000;
}

/**
 * The binding-drift guard (G3b): the live YouTube.js session's
 * visitorData is the envelope every token must bind against. A restored
 * cache (or seed) minted for a DIFFERENT visitorData is dropped — its
 * tokens would poison the player call (split-brain). Returns the current
 * visitorData, or null when the session is not (yet) bootable — callers
 * treat that as "no coherent token available".
 */
async function dropIfDrifted(): Promise<string | null> {
  const cached = session;
  if (cached === null || cached.kind === 'seeded') {
    return cached?.visitorData ?? null;
  }
  try {
    const current = await getYt();
    const visitorData = current.session.context.client.visitorData ?? '';
    if (visitorData.length === 0) return null;
    if (cached.visitorData !== visitorData) {
      setStatus({
        phase: 'idle',
        detail: `dropped ${cached.kind} cache — visitorData drifted (${cached.visitorData.slice(0, 8)}… → ${visitorData.slice(0, 8)}…)`,
        braveShieldsAdvice: false,
      });
      session = null;
      sessionHasMinter = false;
    }
    return visitorData;
  } catch {
    // The session layer is unreachable (relay down?) — the cached
    // session is the only thing we have; let it serve (best-effort).
    return cached.visitorData;
  }
}

/**
 * Ensure a LIVE mint session exists (seeds and stale caches do not count).
 * Deduped: concurrent callers share one mint. Set force to re-mint (retry
 * button) — a fresh live mint replaces any seed/cache and rewrites the
 * localStorage cache.
 */
export async function ensureLiveSession(
  relayBase: string,
  opts: { force?: boolean; signal?: AbortSignal | null } = {},
): Promise<PotSession> {
  void relayBase; // the session layer resolves the relay (G3b)
  if (session !== null && session.kind === 'seeded') {
    // An explicit live request overrides a seed (user clicked "Mint").
    if (opts.force !== true) return session;
  }
  const usable =
    session !== null &&
    sessionUsable() &&
    sessionHasMinter &&
    (session.kind === 'live' || session.kind === 'websafe-fallback' || session.kind === 'coldstart');
  if (usable && opts.force !== true) return session as PotSession;
  if (inFlight !== null) return inFlight;

  inFlight = (async () => {
    // A fresh live mint replaces any seed or cache.
    seed = null;
    playerTokens.clear();
    let lastDetail = 'mint session live';
    let brave = false;
    try {
      const { session: minted, braveShieldsAdvice } = await runMintSession({
        relayBase: '',
        signal: opts.signal ?? null,
        onPhase: (phase, detail) => {
          lastDetail = detail;
          setStatus({ phase, detail });
        },
      });
      brave = braveShieldsAdvice;
      session = minted;
      sessionHasMinter = true; // this page load owns a real minter/fallback
      writeCache(minted, {});
      setStatus({
        phase: 'ready',
        detail: lastDetail,
        braveShieldsAdvice: brave,
      });
      return minted;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      setStatus({
        phase: 'idle',
        detail: `mint failed: ${message}`,
        braveShieldsAdvice: brave,
      });
      throw err;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

// ---------------------------------------------------------------------------
// Token access (resolve.ts rungs)
// ---------------------------------------------------------------------------

export interface PlayerToken {
  poToken: string;
  visitorData: string;
  kind: PotSession['kind'];
}

/**
 * PLAYER-context poToken for a video (serviceIntegrityDimensions).
 * Ladder: usable session (live/cached/seeded — a seed answers only its
 * video) → lazy live mint → null (caller falls down the resolver ladder).
 * Minted tokens are write-through recorded for the localStorage cache.
 */
export async function playerPoToken(
  videoId: string,
  relayBase: string,
  opts: { autoMint?: boolean; signal?: AbortSignal | null } = {},
): Promise<PlayerToken | null> {
  if (sessionUsable()) {
    const s = session as PotSession;
    if (s.kind === 'seeded') {
      if (seed !== null && seed.videoId !== videoId) {
        return null; // seeded token is video-scoped — no false positives
      }
    } else {
      // Binding-drift guard: a cache minted for another visitorData is
      // worse than no cache (split-brain). Drops the session on drift.
      const current = await dropIfDrifted();
      if (current === null) return null;
    }
    try {
      const poToken = await s.mintPlayerToken(videoId);
      recordPlayerToken(videoId, poToken);
      return { poToken, visitorData: s.visitorData, kind: s.kind };
    } catch {
      // A cached session's cache miss (new video) or a dead minter —
      // fall through to the lazy live mint below.
    }
  }
  if (opts.autoMint !== true) return null;
  try {
    const s = await ensureLiveSession(relayBase, { signal: opts.signal });
    const poToken = await s.mintPlayerToken(videoId);
    recordPlayerToken(videoId, poToken);
    return { poToken, visitorData: s.visitorData, kind: s.kind };
  } catch {
    return null; // status already carries the failure detail
  }
}

/**
 * GVS-context poToken for a video (googlevideo &pot=). Requires a session
 * that matches the player token's origin (same live session, or the seed's
 * video). Returns null when unavailable — callers then use the bare URL.
 */
export async function gvsPoToken(videoId: string): Promise<string | null> {
  if (!sessionUsable()) return null;
  const s = session as PotSession;
  if (s.kind === 'seeded' && seed !== null && seed.videoId !== videoId) return null;
  try {
    const token = await s.mintGvsToken(videoId);
    return typeof token === 'string' && token.length > 0 ? token : null;
  } catch {
    return null;
  }
}
