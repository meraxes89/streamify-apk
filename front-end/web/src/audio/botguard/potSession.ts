/**
 * audio/botguard/potSession.ts — the W1.0 session manager.
 *
 * One mint session per integrity-token lifetime (~12 h): the session is a
 * module singleton with in-flight dedupe (five parts of the app asking for
 * a token simultaneously must trigger exactly ONE BotGuard run).
 *
 * Two sources of tokens:
 *   • 'live'  — browser-native mint (mintCore.runMintSession): the
 *     production path; needs the W1.0 relay (deployed with the minting
 *     allowlist + /bg-session).
 *   • 'seeded' — a dev override (pot-lab's provider-recipe Node mint),
 *     video-scoped: proves the PLAYER-side wiring (serviceIntegrityDimensions
 *     + X-Goog-Visitor-Id + &pot=) even where the environment cannot mint
 *     (headless verification). Seeded tokens answer only for their videoId.
 *
 * The seeded path is DEV-ONLY by construction: it is never consulted unless
 * seedPotSession() ran first (the SpineConsole seed box), and it reports
 * its kind through potSnapshot()/subscribe() so the UI always labels it.
 */

import { runMintSession } from './mintCore';
import type { MintStatus, PotSeed, PotSession } from './protocol';

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

let session: PotSession | null = null;
let inFlight: Promise<PotSession> | null = null;
let seed: PotSeed | null = null;

let status: MintStatus = { phase: 'idle', detail: 'no mint yet', relayTooOld: false };
const listeners = new Set<(s: MintStatus) => void>();

function setStatus(next: Partial<MintStatus>): void {
  status = { ...status, ...next };
  for (const l of [...listeners]) l(status);
}

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
  };
  setStatus({
    phase: 'ready',
    detail: `SEEDED token for ${parsed.videoId} (expires ${new Date(expiresAtMs).toLocaleTimeString()})`,
    relayTooOld: false,
  });
  return { videoId: parsed.videoId };
}

/** Drop the seed (back to live minting). */
export function clearSeed(): void {
  seed = null;
  session = null;
  setStatus({ phase: 'idle', detail: 'seed cleared', relayTooOld: false });
}

/** The seeded video id, if any (UI label). */
export function seededVideoId(): string | null {
  return seed !== null ? seed.videoId : null;
}

// ---------------------------------------------------------------------------
// Session acquisition
// ---------------------------------------------------------------------------

function sessionUsable(): boolean {
  return session !== null && Date.now() < session.expiresAtMs - 5 * 60 * 1000;
}

/**
 * Ensure a LIVE mint session exists (seeds do not trigger mints). Deduped:
 * concurrent callers share one mint. Set force to re-mint (retry button).
 */
export async function ensureLiveSession(
  relayBase: string,
  opts: { force?: boolean; signal?: AbortSignal | null } = {},
): Promise<PotSession> {
  if (session !== null && session.kind === 'seeded') {
    // An explicit live request overrides a seed (user clicked "Mint").
    if (opts.force !== true) return session;
  }
  if (sessionUsable() && opts.force !== true) return session as PotSession;
  if (inFlight !== null) return inFlight;

  inFlight = (async () => {
    // A fresh live mint replaces any seed.
    seed = null;
    let lastDetail = 'mint session live';
    try {
      const minted = await runMintSession({
        relayBase,
        signal: opts.signal ?? null,
        onPhase: (phase, detail) => {
          lastDetail = detail;
          setStatus({ phase, detail, relayTooOld: false });
        },
      });
      session = minted;
      setStatus({ phase: 'ready', detail: lastDetail, relayTooOld: false });
      return minted;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      const code = (err as { code?: string }).code;
      setStatus({
        phase: 'idle',
        detail: `mint failed: ${message}`,
        relayTooOld: code === 'relay-too-old',
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
  kind: 'live' | 'seeded';
}

/**
 * PLAYER-context poToken for a video (serviceIntegrityDimensions).
 * Uses a usable session (live or seeded — a seed answers only its video);
 * optionally mints one first (auto: the licensed rung's lazy path).
 * Returns null when no token is available (caller falls down the ladder).
 */
export async function playerPoToken(
  videoId: string,
  relayBase: string,
  opts: { autoMint?: boolean; signal?: AbortSignal | null } = {},
): Promise<PlayerToken | null> {
  if (sessionUsable()) {
    const s = session as PotSession;
    if (s.kind === 'seeded' && s !== null && seed !== null && seed.videoId !== videoId) {
      return null; // seeded token is video-scoped — no false positives
    }
    return { poToken: await s.mintPlayerToken(videoId), visitorData: s.visitorData, kind: s.kind };
  }
  if (opts.autoMint !== true) return null;
  try {
    const s = await ensureLiveSession(relayBase, { signal: opts.signal });
    return { poToken: await s.mintPlayerToken(videoId), visitorData: s.visitorData, kind: s.kind };
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
    return await s.mintGvsToken(videoId);
  } catch {
    return null;
  }
}
