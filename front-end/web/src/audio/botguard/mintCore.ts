/**
 * audio/botguard/mintCore.ts — the browser-native BotGuard/PO mint (W1.0).
 *
 * Mirrors the provider's proven session flow (bgutil-ytdlp-pot-provider
 * session_manager.ts incl. the 2026-08 homepage-challenge patch) but runs
 * in the REAL page — the BotGuard VM scores its environment (canvas,
 * webgl, hardware entropy) and JSDOM/headless environments measure as
 * low-trust: provider-recipe Node mints with correct video-id binding were
 * measured NOT to lift the licensed-content wall from datacenter IPs
 * (2026-11-12, pot-lab matrix). Real browser hardware is the point.
 *
 * FLOW (every network leg through the relay — see protocol.ts):
 *
 *   1. GET /bg-session        visitorData + ytcfg + ytAtN (server-cached)
 *   2. parseLooseJSON(ytAtN)  → R.bgChallenge { program, globalName,
 *                                interpreterUrl }   (camelCase wrapped URL)
 *   3. GET /r?u=<interpreter> → the "trayride" VM script
 *   4. window.yt = { config_: ytcfg }        (BotGuard reads EVENT_ID)
 *   5. new Function(interpreterJS)()         → window[globalName] (the VM)
 *   6. BotGuardClient.create + snapshot({ webPoSignalOutput })
 *   7. POST /r?u=<GenerateIT> [REQUEST_KEY, snapshot] → integrity token
 *   8. WebPoMinter.create(itData, webPoSignalOutput) — LOCAL from here:
 *      mintAsWebsafeString(videoId) per video, no network, for the token's
 *      ~12 h life.
 *
 * ENVIRONMENT CHOICE: the VM is loaded into the MAIN window, not an iframe
 * and not a worker — the main page is the most authentic environment the
 * challenge can observe (real URL, history, visibility, canvas), which
 * maximizes the snapshot's trust score. The two globals it touches
 * (`window.yt`, `window[globalName]`) are unused by this app.
 */

import { parseLooseJSON, getHeaders } from 'bgutils-js/utils';
import { BotGuardClient } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import type { WebPoSignalOutput } from 'bgutils-js/shared-types';
import {
  WAA_GENERATE_IT_URL,
  WAA_REQUEST_KEY,
  MintError,
  relayUrl,
} from './protocol';
import type { BgChallenge, BgSessionInfo, MintPhase, PotSession } from './protocol';

export interface MintOptions {
  relayBase: string;
  signal?: AbortSignal | null;
  /** Progress sink (UI mirror). */
  onPhase?: (phase: MintPhase, detail: string) => void;
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
// The full session mint
// ---------------------------------------------------------------------------

/**
 * Run one complete mint session. Returns a PotSession whose mint functions
 * are LOCAL (WebPoMinter over the integrity token) — no further network
 * until the token expires (estimatedTtlSecs, typically 12 h).
 */
export async function runMintSession(opts: MintOptions): Promise<PotSession> {
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

  // 4 — yt config injection (BotGuard reads yt.config_.EVENT_ID)
  const w = window as unknown as Record<string, unknown>;
  let ytcfgObj: Record<string, unknown> = {};
  try {
    ytcfgObj = JSON.parse(bg.ytcfg) as Record<string, unknown>;
  } catch {
    /* EVENT_ID is best-effort; the mint proceeds without it */
  }
  w.yt = { config_: ytcfgObj };

  // 5 — VM into the main window (most authentic environment — see header)
  phase('vm', 'evaluating interpreter (main window)');
  try {
    // eslint-disable-next-line @typescript-eslint/no-implied-eval -- the interpreter IS the challenge
    new Function(interpreterJS)();
  } catch (err) {
    throw new MintError(
      'vm',
      `interpreter eval threw: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  if (w[challenge.globalName] === undefined) {
    throw new MintError('vm', `interpreter did not define window.${challenge.globalName}`);
  }
  phase('vm', `VM ready: window.${challenge.globalName}`);

  // 6 — BotGuard snapshot (THIS is where environment trust is measured)
  phase('snapshot', 'running BotGuard challenge program');
  let bgClient: BotGuardClient;
  try {
    bgClient = await BotGuardClient.create({
      program: challenge.program,
      globalName: challenge.globalName,
      globalObject: window,
    });
  } catch (err) {
    throw new MintError(
      'vm',
      `BotGuardClient.create failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  const webPoSignalOutput: WebPoSignalOutput = [];
  let snapshot: string;
  try {
    snapshot = await bgClient.snapshot({ webPoSignalOutput });
  } catch (err) {
    throw new MintError(
      'snapshot',
      `snapshot failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  if (webPoSignalOutput.length === 0) {
    // The VM refused to score this environment (headless/automation case).
    throw new MintError(
      'vm-score',
      'BotGuard VM scored the environment as untrusted (webPoSignalOutput empty) — ' +
        'headless/automated browsers cannot mint; real user hardware can',
    );
  }
  phase('snapshot', `snapshot ${snapshot.length}B · ${webPoSignalOutput.length} signal fn(s)`);

  // 7 — GenerateIT through the relay (origin stripped → WAA key valid)
  phase('generate-it', 'POST Waa/GenerateIT (relay, origin stripped)');
  let itRes: Response;
  try {
    itRes = await fetch(relayUrl(relayBase, WAA_GENERATE_IT_URL), {
      method: 'POST',
      // getHeaders(): content-type json+protobuf + the public WebPo GAPI key
      // + x-user-agent. The browser replaces the (forbidden) UA with its own.
      headers: getHeaders(),
      body: JSON.stringify([WAA_REQUEST_KEY, snapshot]),
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
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] =
    (await itRes.json()) as [string, number, number, string];
  if (typeof integrityToken !== 'string' || integrityToken.length === 0) {
    throw new MintError('empty-token', 'GenerateIT returned an empty integrity token');
  }
  phase('generate-it', `integrity token ${integrityToken.length}B · ttl ${estimatedTtlSecs}s`);

  // 8 — the LOCAL minter
  phase('minter', 'constructing WebPoMinter');
  const minter = await WebPoMinter.create(
    { integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken },
    webPoSignalOutput,
  );
  const videoBind = gvsBindsToVideoId(bg.ytcfg);
  phase('ready', `mint session live (${videoBind ? 'video-id' : 'visitorData'} GVS binding)`);

  return {
    kind: 'live',
    visitorData: bg.visitorData,
    expiresAtMs: Date.now() + estimatedTtlSecs * 1000,
    gvsBindsToVideoId: videoBind,
    mintPlayerToken: (videoId: string) => minter.mintAsWebsafeString(videoId),
    mintGvsToken: (videoId: string) =>
      minter.mintAsWebsafeString(videoBind ? videoId : bg.visitorData),
  };
}
