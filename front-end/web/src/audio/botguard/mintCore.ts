/**
 * audio/botguard/mintCore.ts — P3b Tier 2: the browser-native BotGuard/PO
 * mint, in a hidden srcdoc iframe with a spoofed youtube.com origin.
 *
 * WHY THE IFRAME CHANGED EVERYTHING (P3b post-mortem of the W1.0 mint):
 *   The W1.0 mint ran the VM in the MAIN window and measured
 *   `webPoSignalOutput empty` on anything that was not youtube.com — the
 *   BotGuard bytecode inspects window.location / document.URL and aborts
 *   minter initialization on a foreign origin. The fix (architect's Angle
 *   B/C): a SAME-ORIGIN srcdoc iframe on the Main UI Thread whose
 *   window/document/location globals are presented through Proxy wrappers
 *   reporting https://www.youtube.com, while canvas, WebGL, navigator,
 *   performance, and audio pass STRAIGHT THROUGH to the user's real
 *   (mobile) hardware. See mintHarness.ts (the in-frame realm) and
 *   mintIframe.ts (the host).
 *
 * FLOW (every network leg through the relay — see protocol.ts):
 *
 *   1. GET /bg-session        visitorData + ytcfg + ytAtN (server-cached)
 *   2. parseLooseJSON(ytAtN)  → R.bgChallenge { program, globalName,
 *                               interpreterUrl }
 *   3. GET /r?u=<interpreter> → the "trayride" VM script
 *   4. harness.bootVM(...)    shadow-eval under the spoofed origin (IFRAME)
 *   5. harness.runSnapshot()  BotGuard snapshot — attempt 1 (privacy buffer
 *      ON). Empty webPoSignalOutput (Brave-class farbling) → attempt 2
 *      with skipPrivacyBuffer: true (the VM's built-in bypass).
 *   6. POST /r?u=<GenerateIT> [REQUEST_KEY, snapshot] → the 4-tuple
 *      (integrityToken, ttl, refreshThreshold, websafeFallbackToken).
 *   7. Signal output present → harness.buildMinter + the DUAL tokens:
 *      the session token (visitorData-bound, minted eagerly, persisted to
 *      localStorage ~6 h) and the per-track player token (videoId-bound,
 *      <5 ms from the warm minter).
 *      Signal output empty (privacy buffer won) → the WEBSAFE FALLBACK
 *      session: the server's websafeFallbackToken rides in place of
 *      client mints (weaker attestation, honestly labeled).
 *   8. GenerateIT itself failed → the COLDSTART session: fully-local XOR
 *      cold start tokens (bgutils createColdStartToken — sps=2 unlock of
 *      the first ~1–2 MB while a full attestation is retried).
 *
 * BINDING (unchanged, yt-dlp pot/utils.py): the /player poToken is
 * VIDEO_ID-bound; the &pot= token follows the homepage experiment flag
 * (videoId while set, else visitorData — the session token).
 */

import { parseLooseJSON, getHeaders } from 'bgutils-js/utils';
import { createColdStartToken } from 'bgutils-js/webpo';
import { ensureMintIframe } from './mintIframe';
import {
  WAA_GENERATE_IT_URL,
  WAA_REQUEST_KEY,
  MintError,
  relayUrl,
} from './protocol';
import type {
  BgChallenge,
  BgSessionInfo,
  MintPhase,
  PotSession,
} from './protocol';

export interface MintOptions {
  relayBase: string;
  signal?: AbortSignal | null;
  /** Progress sink (UI mirror). */
  onPhase?: (phase: MintPhase, detail: string) => void;
}

/** runMintSession's verdict extras (status surfacing beyond the session). */
export interface MintSessionResult {
  session: PotSession;
  /** The privacy-buffer fallback fired (Brave-class environment). */
  braveShieldsAdvice: boolean;
  /** Which snapshot attempt produced the accepted bytes ('first' | 'skip-privacy-buffer' | 'none'). */
  snapshotAttempt: 'first' | 'skip-privacy-buffer' | 'none';
}

// ---------------------------------------------------------------------------
// Steps (exported for tests/diagnostics)
// ---------------------------------------------------------------------------

/** Step 1 — the homepage triple via the relay's cached extraction. */
export async function fetchBgSession(
  relayBase: string,
  opts: { fresh?: boolean; signal?: AbortSignal | null } = {},
): Promise<BgSessionInfo> {
  const base = relayBase.replace(/\/$/, '');
  const url = opts.fresh === true ? `${base}/bg-session?fresh=1` : `${base}/bg-session`;
  let res: Response;
  try {
    res = await fetch(url, { signal: opts.signal ?? undefined });
  } catch (err) {
    throw new MintError(
      'bg-session',
      `bg-session fetch failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  if (res.status === 404) {
    throw new MintError(
      'relay-too-old',
      'relay has no /bg-session — the deployed worker predates W1.0 (needs wrangler deploy)',
    );
  }
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    throw new MintError('bg-session', `bg-session ${res.status}: ${detail.slice(0, 160)}`);
  }
  const info = (await res.json()) as BgSessionInfo;
  if (typeof info.visitorData !== 'string' || info.visitorData.length === 0) {
    throw new MintError('bg-session', 'bg-session returned no visitorData');
  }
  return info;
}

/** Step 2 — descramble the challenge out of the raw ytAtN payload. */
export function parseChallenge(bg: BgSessionInfo): BgChallenge {
  const payload = parseLooseJSON(bg.ytAtN) as
    | { R?: { bgChallenge?: Record<string, unknown> } }
    | null;
  const bgc = payload?.R?.bgChallenge;
  const program = typeof bgc?.program === 'string' ? bgc.program : undefined;
  const globalName = typeof bgc?.globalName === 'string' ? bgc.globalName : undefined;
  // NOTE the camelCase wrapper key — the documented 2026 trap: the URL rides
  // inside interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue.
  const interpWrapped = bgc?.interpreterUrl as
    | { privateDoNotAccessOrElseTrustedResourceUrlWrappedValue?: unknown }
    | undefined;
  const interpreterUrl =
    typeof interpWrapped?.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue === 'string'
      ? interpWrapped.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue
      : undefined;
  if (program === undefined || globalName === undefined || interpreterUrl === undefined) {
    throw new MintError(
      'no-challenge',
      'ytAtN payload has no usable R.bgChallenge {program, globalName, interpreterUrl}',
    );
  }
  return {
    program,
    globalName,
    interpreterHash: typeof bgc?.interpreterHash === 'string' ? bgc.interpreterHash : undefined,
    // protocol-relative "//www.google.com/js/th/…" → absolute https URL
    interpreterUrl: interpreterUrl.startsWith('//')
      ? `https:${interpreterUrl}`
      : interpreterUrl,
  };
}

/** Step 3 — the interpreter JS through the relay (www.google.com/js/th/*). */
export async function fetchInterpreter(
  relayBase: string,
  challenge: BgChallenge,
  opts: { signal?: AbortSignal | null } = {},
): Promise<string> {
  let res: Response;
  try {
    res = await fetch(relayUrl(relayBase, challenge.interpreterUrl), {
      signal: opts.signal ?? undefined,
    });
  } catch (err) {
    throw new MintError(
      'interpreter',
      `interpreter fetch failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  if (res.status === 403) {
    // The OLD (P0) relay allowlist rejects www.google.com with exactly this.
    throw new MintError(
      'relay-too-old',
      'relay refuses www.google.com — the deployed worker predates W1.0 (needs wrangler deploy)',
    );
  }
  if (!res.ok) {
    throw new MintError('interpreter', `interpreter fetch ${res.status}`);
  }
  return await res.text();
}

/**
 * The GVS binding flag: true when the homepage experiment binds googlevideo
 * PO tokens to the video id (html5_generate_content_po_token) — yt-dlp's
 * gvs_bind_to_video_id rule. Parsed from the SAME ytcfg the mint consumed.
 */
export function gvsBindsToVideoId(ytcfgRaw: string): boolean {
  try {
    const cfg = JSON.parse(ytcfgRaw) as {
      WEB_PLAYER_CONTEXT_CONFIGS?: Record<string, { serializedExperimentFlags?: string }>;
    };
    const configs = cfg.WEB_PLAYER_CONTEXT_CONFIGS ?? {};
    for (const key of Object.keys(configs)) {
      const flags = configs[key]?.serializedExperimentFlags;
      if (typeof flags === 'string' && flags.includes('html5_generate_content_po_token=true')) {
        return true;
      }
    }
  } catch {
    /* unparseable ytcfg → conservative default (visitorData binding) */
  }
  return false;
}

// ---------------------------------------------------------------------------
// Fallback sessions (no minter — see the header's steps 7b/8)
// ---------------------------------------------------------------------------

/**
 * The WEBSAFE FALLBACK session: GenerateIT answered, but the privacy-buffer
 * path left no usable client minter (Brave-class farbling). The server's
 * websafeFallbackToken rides in place of both token lanes — weaker
 * attestation, honestly labeled 'websafe-fallback' up to the UI.
 */
function websafeFallbackSession(
  visitorData: string,
  itData: { estimatedTtlSecs: number; websafeFallbackToken: string },
): PotSession {
  const ttlSecs =
    typeof itData.estimatedTtlSecs === 'number' && itData.estimatedTtlSecs > 0
      ? Math.min(itData.estimatedTtlSecs, 6 * 3600)
      : 3600;
  return {
    kind: 'websafe-fallback',
    visitorData,
    expiresAtMs: Date.now() + ttlSecs * 1000,
    gvsBindsToVideoId: true, // the fallback token is a single opaque string
    mintPlayerToken: async () => itData.websafeFallbackToken,
    mintGvsToken: async () => itData.websafeFallbackToken,
    sessionToken: itData.websafeFallbackToken,
  };
}

/** Cold-start horizon: these are transitional tokens (sps=2 window). */
const COLDSTART_TTL_MS = 15 * 60 * 1000;

/**
 * The COLDSTART session: the full mint pipeline failed (VM, snapshot, or
 * GenerateIT) but the bg-session visitorData is in hand. Fully-local XOR
 * cold start tokens (bgutils createColdStartToken) — under Status 2
 * enforcement they unlock the first ~1–2 MB of media immediately while a
 * full attestation is retried on the next mint.
 */
function coldstartSession(visitorData: string, videoBind: boolean): PotSession {
  return {
    kind: 'coldstart',
    visitorData,
    expiresAtMs: Date.now() + COLDSTART_TTL_MS,
    gvsBindsToVideoId: videoBind,
    mintPlayerToken: async (videoId: string) => createColdStartToken(videoId),
    mintGvsToken: async (videoId: string) =>
      createColdStartToken(videoBind ? videoId : visitorData),
    sessionToken: null,
  };
}

// ---------------------------------------------------------------------------
// The full session mint
// ---------------------------------------------------------------------------

/**
 * Run one complete mint session in the hidden iframe. Returns the session
 * (live minter, websafe-fallback, or coldstart — the ladder degrades
 * honestly) plus the verdict extras for the status lane.
 */
export async function runMintSession(opts: MintOptions): Promise<MintSessionResult> {
  const { relayBase, signal, onPhase } = opts;
  const phase = (p: MintPhase, detail: string): void => onPhase?.(p, detail);

  // 1 — homepage triple
  phase('bg-session', 'fetching visitorData + challenge (relay-cached)');
  const bg = await fetchBgSession(relayBase, { signal });
  phase('bg-session', `visitorData ${bg.visitorData.slice(0, 16)}… (${bg.cache ?? '?'} cache)`);

  // 2 — challenge
  phase('challenge', 'descrambling ytAtN');
  const challenge = parseChallenge(bg);
  phase(
    'challenge',
    `program ${challenge.program.length}B · VM global "${challenge.globalName}"`,
  );

  // 3 — interpreter
  phase('interpreter', 'loading BotGuard interpreter (www.google.com/js/th)');
  const interpreterJS = await fetchInterpreter(relayBase, challenge, { signal });
  phase('interpreter', `${interpreterJS.length}B of VM`);

  // 4 — the hidden mint iframe (Main UI Thread, spoofed origin)
  phase('iframe', 'booting the srcdoc mint frame (spoofed youtube.com origin)');
  const harness = await ensureMintIframe();
  harness.reset();
  let ytcfgObj: Record<string, unknown> = {};
  try {
    ytcfgObj = JSON.parse(bg.ytcfg) as Record<string, unknown>;
  } catch {
    /* EVENT_ID is best-effort; the mint proceeds without it */
  }
  const probe = harness.environmentProbe();
  const boot = harness.bootVM({ interpreterJS, program: challenge.program, globalName: challenge.globalName, ytcfg: ytcfgObj });
  if (!boot.ok) {
    throw new MintError('vm', `interpreter eval failed in the mint frame: ${boot.error}`);
  }
  phase(
    'iframe',
    `VM ready under spoofed origin ${probe.origin} (${probe.url}) — top-level: ${probe.isTopLevel}`,
  );

  // 5 — BotGuard snapshot (attempt 1: privacy buffer on)
  phase('snapshot', 'running BotGuard challenge program (privacy buffer on)');
  let snapshotBytes = '';
  let signalReady = false;
  let attempt: MintSessionResult['snapshotAttempt'] = 'none';
  const first = await harness.runSnapshot();
  if (first.ok && first.snapshot !== undefined) {
    snapshotBytes = first.snapshot;
    signalReady = first.signalOutputReady;
    attempt = 'first';
    phase(
      'snapshot',
      signalReady
        ? `snapshot ${snapshotBytes.length}B · signal output live`
        : `snapshot ${snapshotBytes.length}B · signal output EMPTY (fingerprinting shield?)`,
    );
  } else {
    phase('snapshot', `attempt 1 failed: ${first.error ?? 'no bytes'}`);
  }

  // 5b — the Brave catch: empty signal output → retry with the VM's
  // privacy-buffer bypass (skipPrivacyBuffer: true).
  let braveShieldsAdvice = false;
  if (!signalReady) {
    braveShieldsAdvice = true;
    phase('snapshot', 'retrying with skipPrivacyBuffer (Brave-class farbling bypass)');
    const second = await harness.runSnapshot({ skipPrivacyBuffer: true });
    if (second.ok && second.snapshot !== undefined && second.snapshot.length > 0) {
      snapshotBytes = second.snapshot;
      signalReady = second.signalOutputReady;
      attempt = 'skip-privacy-buffer';
      phase(
        'snapshot',
        signalReady
          ? `retry snapshot ${snapshotBytes.length}B · signal output live (bypass)`
          : `retry snapshot ${snapshotBytes.length}B · signal output still empty — websafe fallback next`,
      );
    } else if (signalReady === false && snapshotBytes.length === 0) {
      // No usable bytes at all — the VM refused this environment outright.
      throw new MintError(
        'vm-score',
        'BotGuard VM produced no snapshot in the mint frame (webPoSignalOutput empty on both attempts) — ' +
          'a fingerprinting shield (Brave "Block Fingerprinting"?) may be breaking the challenge; ' +
          'toggling it off for this site usually restores the mint',
      );
    }
  }

  // 6 — GenerateIT through the relay (P3b relay forwards the browser's
  // exact UA + Sec-CH-UA-* on this leg — the envelope pinning)
  phase('generate-it', 'POST Waa/GenerateIT (relay, envelope pinned)');
  let itRes: Response;
  try {
    itRes = await fetch(relayUrl(relayBase, WAA_GENERATE_IT_URL), {
      method: 'POST',
      // getHeaders(): content-type json+protobuf + the public WebPo GAPI key
      // + x-user-agent. The browser replaces the (forbidden) UA with its own.
      headers: getHeaders(),
      body: JSON.stringify([WAA_REQUEST_KEY, snapshotBytes]),
      signal: signal ?? undefined,
    });
  } catch (err) {
    throw new MintError(
      'generate-it',
      `GenerateIT fetch failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  if (itRes.status === 403) {
    throw new MintError(
      'relay-too-old',
      'relay refuses jnn-pa.googleapis.com — the deployed worker predates W1.0 (needs wrangler deploy)',
    );
  }
  if (!itRes.ok) {
    const detail = await itRes.text().catch(() => '');
    throw new MintError('generate-it', `GenerateIT ${itRes.status}: ${detail.slice(0, 160)}`);
  }
  const [integrityTokenRaw, estimatedTtlSecsRaw, mintRefreshThresholdRaw, websafeFallbackTokenRaw] =
    (await itRes.json()) as [string, number, number, string];
  const estimatedTtlSecs =
    typeof estimatedTtlSecsRaw === 'number' && estimatedTtlSecsRaw > 0
      ? estimatedTtlSecsRaw
      : 3600;
  const integrityToken =
    typeof integrityTokenRaw === 'string' && integrityTokenRaw.length > 0
      ? integrityTokenRaw
      : null;
  const websafeFallbackToken =
    typeof websafeFallbackTokenRaw === 'string' && websafeFallbackTokenRaw.length > 0
      ? websafeFallbackTokenRaw
      : null;
  const videoBind = gvsBindsToVideoId(bg.ytcfg);

  // ---- The GenerateIT verdict ladder (P3b) ---------------------------------
  // An EMPTY integrity token is not a crash: it is the WAA's honest "this
  // snapshot scores too low" (headless-class environments measure exactly
  // this). The architect's degradation order applies:
  //   • integrityToken + signal output → the live dual-token minter
  //   • websafeFallbackToken (4th tuple element) → the websafe-fallback
  //     session — it exists precisely for degraded-environment mints
  //   • nothing usable server-side → the local coldstart session
  if (integrityToken === null) {
    phase(
      'generate-it',
      `integrity token REFUSED (low-trust snapshot) — websafe: ${websafeFallbackToken !== null ? `yes (${websafeFallbackToken.length}B)` : 'no'}`,
    );
    if (websafeFallbackToken !== null) {
      phase('ready', 'websafe-fallback session (WAA refused the full attestation)');
      return {
        session: websafeFallbackSession(bg.visitorData, { estimatedTtlSecs, websafeFallbackToken }),
        braveShieldsAdvice,
        snapshotAttempt: attempt,
      };
    }
    phase('ready', 'coldstart session (WAA refused everything — local XOR tokens)');
    return {
      session: coldstartSession(bg.visitorData, videoBind),
      braveShieldsAdvice,
      snapshotAttempt: attempt,
    };
  }
  phase('generate-it', `integrity token ${integrityToken.length}B · ttl ${estimatedTtlSecs}s`);

  const itData = { integrityToken, estimatedTtlSecs, mintRefreshThreshold: typeof mintRefreshThresholdRaw === 'number' ? mintRefreshThresholdRaw : 0, websafeFallbackToken: websafeFallbackToken ?? '' };

  // 7 — the LOCAL minter (live) or the fallback ladder
  if (signalReady) {
    phase('minter', 'constructing WebPoMinter (mint frame realm)');
    const built = await harness.buildMinter(itData);
    if (built.ok) {
      // 7a — the DUAL tokens: session (visitorData) eagerly, player
      // (videoId) per track from the warm minter.
      phase('session-token', 'minting the session-bound token (visitorData)');
      let sessionToken: string | null = null;
      try {
        const minted = await harness.mint(bg.visitorData);
        sessionToken = minted.ok ? (minted.token as string) : null;
        if (!minted.ok) {
          phase('session-token', `session token mint failed: ${minted.error}`);
        }
      } catch {
        sessionToken = null; // non-fatal: &pot= falls back per-track
      }
      phase(
        'ready',
        `live mint session (${videoBind ? 'video-id' : 'visitorData'} GVS binding` +
          `${sessionToken !== null ? ', session token cached' : ''})`,
      );
      return {
        session: {
          kind: 'live',
          visitorData: bg.visitorData,
          expiresAtMs: Date.now() + estimatedTtlSecs * 1000,
          gvsBindsToVideoId: videoBind,
          mintPlayerToken: (videoId: string) =>
            harness.mint(videoId).then((r) => {
              if (!r.ok || r.token === undefined) {
                throw new Error(`player token mint failed: ${r.error}`);
              }
              return r.token;
            }),
          mintGvsToken: (videoId: string) =>
            harness
              .mint(videoBind ? videoId : bg.visitorData)
              .then((r) => {
                if (!r.ok || r.token === undefined) {
                  throw new Error(`GVS token mint failed: ${r.error}`);
                }
                return r.token;
              }),
          sessionToken,
        },
        braveShieldsAdvice,
        snapshotAttempt: attempt,
      };
    }
    // buildMinter failed despite a signal output (PMD:Undefined race —
    // Brave's farbling can poison the minter hook AFTER the snapshot).
    braveShieldsAdvice = true;
    phase('minter', `minter construction failed (${built.error}) — websafe fallback`);
  }

  // 7b — WEBSAFE FALLBACK: the server's websafeFallbackToken in place of
  // client mints (the minter did not survive the privacy buffer).
  if (websafeFallbackToken !== null) {
    phase('ready', 'websafe-fallback session (privacy buffer won the minter)');
    return {
      session: websafeFallbackSession(bg.visitorData, itData),
      braveShieldsAdvice,
      snapshotAttempt: attempt,
    };
  }

  // 8 — COLDSTART: nothing server-side survived — local cold start tokens.
  phase('ready', 'coldstart session (local XOR tokens, first ~1–2 MB unlock)');
  return {
    session: coldstartSession(bg.visitorData, videoBind),
    braveShieldsAdvice,
    snapshotAttempt: attempt,
  };
}

/**
 * The coldstart-only mint (public helper): binds a local cold start token
 * to a videoId WITHOUT any network or VM — the instant, zero-dependency
 * rung for the resolver's last resort.
 */
export function coldStartTokenFor(binding: string): string {
  return createColdStartToken(binding);
}
