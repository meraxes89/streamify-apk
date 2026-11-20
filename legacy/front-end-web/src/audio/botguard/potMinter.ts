/**
 * audio/botguard/potMinter.ts — G3b: the bgutils-js mint (MAIN REALM).
 *
 * This REPLACES the P3b custom harness (mintCore + mintHarness +
 * mintIframe — deleted): no hidden srcdoc iframe, no 3-layer origin
 * spoofing, no hand-rolled WAA wire. LuanRT's bgutils-js IS the
 * attestation library — the same flow kira (LuanRT's SABR reference
 * app) and the official bgutils browser example run, proven against
 * real mobile browsers. The VM runs as a plain <script> in the page:
 * the BotGuard interpreter expects a real browser environment, and a
 * real page (localhost or github.io — the official example minted from
 * localhost:5173 unpenalized) is exactly that.
 *
 * THE FLOW (bgutils-js v4):
 *   1. getChallenge({ fetchFunction: waaFetch, requestKey }) → WAA
 *      Create on jnn-pa.googleapis.com (through the relay: single-egress
 *      envelope; the relay pins the browser's real UA + sec-ch-ua* on
 *      this leg — the mint envelope must match the minting browser).
 *   2. The interpreter arrives INLINE in the challenge — inject as
 *      <script id={interpreterHash}> in <head> (deduped by hash).
 *   3. BotGuardClient.create({ program, globalName, globalObject }) —
 *      the VM lands on window[globalName] ("trayride" today).
 *   4. snapshot({ webPoSignalOutput }) → the attestation string. Brave's
 *      privacy buffering can empty the signal output (measured in P3b):
 *      one retry with skipPrivacyBuffer: true, then the fallback ladder.
 *   5. GenerateIT (WAA, via the relay) →
 *      [integrityToken, ttlSecs, mintRefreshThreshold, websafeFallbackToken].
 *   6. WebPoMinter.create(integrityTokenData, webPoSignalOutput) →
 *      mintAsWebsafeString(binding) — videoId for the player token,
 *      visitorData for the session (&pot=) token.
 *
 * THE HONEST DEGRADATION LADDER (kept from P3b):
 *   live minter → websafeFallbackToken (server-issued, low-trust) →
 *   createColdStartToken (local XOR, sps=2 window ~1–2 MB).
 *
 * TOKEN BINDING (yt-dlp pot rules + the TSG IP/ASN lesson):
 *   • visitorData comes from the YouTube.js session (ytSession.ts) — the
 *     SAME visitorData the /player calls carry; minted through the SAME
 *     relay egress. Both token classes bind coherently.
 *   • player request serviceIntegrityDimensions.poToken ← videoId.
 *   • googlevideo &pot= ← videoId while the homepage experiment flag is
 *     set (GVS_BINDS_TO_VIDEO_ID), else visitorData.
 */

import { BotGuardClient, getChallenge } from 'bgutils-js/botguard';
import {
  WebPoMinter,
  createColdStartToken,
} from 'bgutils-js/webpo';
import { buildURL, getHeaders } from 'bgutils-js/utils';
import type {
  IBotguardClientSideBgChallenge,
  WebPoSignalOutput,
} from 'bgutils-js/shared-types';
import { getYt } from '../../lib/ytSession';
import { relayBaseCandidate } from '../../lib/engine';
import { relayUrl } from './protocol';
import {
  GVS_BINDS_TO_VIDEO_ID,
  WAA_REQUEST_KEY,
  MintError,
} from './protocol';
import type { PotSession, MintPhase } from './protocol';

/** How long a non-live fallback session stays "usable" (ms). */
const FALLBACK_SESSION_TTL_MS = 15 * 60 * 1000;

/**
 * G4.2 — the WAA fetch (single-envelope rule, MEASURED 2026-11-14).
 *
 * The canary-measured browser posture: youtubei.googleapis.com serves NO
 * ACAO to foreign origins, so EVERY player call and every SABR POST the
 * app makes egresses from the RELAY's Cloudflare IP. A PO token minted on
 * a DIFFERENT egress (jnn-pa direct from the phone) is a split-brain
 * envelope — the exact class the G4 doc warned about, and the reason both
 * reference stacks keep mint-IP == use-IP (bgutil-ytdlp-pot-provider runs
 * its minting server on the consuming host; kira mints and SABRs from the
 * same browser). The lab verified the coherent path end-to-end:
 * relay-minted tokens → relay player calls → playability OK + licensed
 * media flowing. The WAA legs (Create / GenerateIT) therefore ride the
 * RELAY, ALWAYS — the relay pins the browser's real UA + sec-ch-ua* on
 * jnn-pa hops, so the BotGuard telemetry envelope stays coherent with the
 * minting browser while the egress matches every consumer of the token.
 */
async function waaFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
): Promise<Response> {
  const url =
    input instanceof URL
      ? input.toString()
      : typeof input === 'string'
        ? input
        : input.url;
  return fetch(relayUrl(relayBaseCandidate(), url), init);
}

export interface MintSessionResult {
  session: PotSession;
  /** A privacy-buffer fallback fired (Brave-class) — UI advisory. */
  braveShieldsAdvice: boolean;
  /** Which snapshot attempt produced the attestation. */
  snapshotAttempt: 'primary' | 'skip-privacy-buffer';
}

export interface RunMintOptions {
  /**
   * Unused by the minter itself (the session layer resolves the relay);
   * kept for call-site symmetry with the P3b contract.
   */
  relayBase: string;
  signal?: AbortSignal | null;
  onPhase?: (phase: MintPhase, detail: string) => void;
}

// ---------------------------------------------------------------------------
// The mint
// ---------------------------------------------------------------------------

export async function runMintSession(
  opts: RunMintOptions,
): Promise<MintSessionResult> {
  const { signal, onPhase } = opts;
  const phase = (p: MintPhase, detail: string): void => {
    onPhase?.(p, detail);
  };

  // -- 0. the session envelope: the YouTube.js session's visitorData ------
  // Every token this minter produces binds against THIS visitorData —
  // the same value the resolver's player calls carry (context + header).
  const yt = await getYt();
  const visitorData = yt.session.context.client.visitorData ?? '';
  if (visitorData.length === 0) {
    throw new MintError(
      'no-challenge',
      'YouTube.js session has no visitorData — cannot bind tokens',
    );
  }

  // -- 1. the challenge (WAA Create, inline interpreter) --------------------
  phase('challenge', 'WAA Create → challenge (jnn-pa, single-envelope lane)');
  let challenge: IBotguardClientSideBgChallenge;
  try {
    challenge = await getChallenge({
      fetchFunction: waaFetch,
      requestKey: WAA_REQUEST_KEY,
      // no interpreterHash → the interpreter arrives INLINE (no extra
      // asset fetch, no CORS question on /s/player/... at all).
    });
  } catch (err) {
    throw new MintError(
      'no-challenge',
      `WAA Create failed: ${err instanceof Error ? err.message : String(err)}`,
    );
  }
  if (
    typeof challenge.program !== 'string' ||
    challenge.program.length === 0 ||
    typeof challenge.globalName !== 'string' ||
    challenge.globalName.length === 0
  ) {
    throw new MintError('no-challenge', 'challenge is missing program/globalName');
  }

  // -- 2. the interpreter (main realm, deduped by hash) ----------------------
  phase(
    'interpreter',
    `injecting interpreter ${challenge.interpreterHash ?? '(unhashed)'}`,
  );
  injectInterpreter(challenge);

  // -- 3. the VM -------------------------------------------------------------
  phase('vm', `booting VM (globalName ${challenge.globalName})`);
  const botGuard = await BotGuardClient.create({
    program: challenge.program,
    globalName: challenge.globalName,
    globalObject: globalThis,
  });

  // -- 4. snapshot (Brave privacy-buffer retry ladder) ------------------------
  // G4.2 MEASURED (2026-11-14, agent-browser lab against the live relay):
  // the CURRENT trayride program family reads the signal output from the
  // GLOBAL scope — without `globalThis.webPoSignalOutput` the snapshot
  // throws `ReferenceError: webPoSignalOutput is not defined` before any
  // attestation runs (the args-based older family ignores the global, so
  // setting it is safe across both). The primary snapshot also leaves
  // `skipPrivacyBuffer` UNSPECIFIED (the VM's default path, kira's recipe);
  // only the Brave-class retry passes `true` (the documented escape hatch).
  // These two details were the whole broken-mint bug: every "live" mint
  // since the trayride rotation died here and fell to fallbacks.
  phase('snapshot', 'snapshotting the VM');
  let snapshotAttempt: MintSessionResult['snapshotAttempt'] = 'primary';
  let signalOutput: WebPoSignalOutput = [];
  let botguardResponse: string;
  // The global must point at the CURRENT attempt's output array.
  const exposeSignalOutput = (): void => {
    (globalThis as unknown as Record<string, unknown>).webPoSignalOutput =
      signalOutput;
  };
  exposeSignalOutput();
  try {
    signalOutput = [];
    exposeSignalOutput();
    botguardResponse = await snapshotWith(botGuard, signalOutput, undefined, signal);
  } catch (err) {
    // Brave-class privacy buffering (measured in P3b): the primary
    // snapshot throws or leaves the signal output empty. One retry
    // with skipPrivacyBuffer: true — the documented escape hatch.
    phase('snapshot', `primary snapshot failed (${describe(err)}) — retrying skipPrivacyBuffer`);
    snapshotAttempt = 'skip-privacy-buffer';
    signalOutput = [];
    exposeSignalOutput();
    botguardResponse = await snapshotWith(botGuard, signalOutput, true, signal);
  }
  if (typeof botguardResponse !== 'string' || botguardResponse.length === 0) {
    throw new MintError('snapshot', 'snapshot produced no attestation response');
  }

  // -- 5. GenerateIT (WAA, via the relay — same egress as the player legs) ----
  phase('generate-it', 'GenerateIT → integrity token');
  const integrityRes = await waaFetch(buildURL('GenerateIT'), {
    method: 'POST',
    headers: getHeaders(),
    body: JSON.stringify([WAA_REQUEST_KEY, botguardResponse]),
    signal: signal ?? undefined,
  });
  if (!integrityRes.ok) {
    throw new MintError(
      'generate-it',
      `GenerateIT relay call ${integrityRes.status}`,
    );
  }
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] =
    (await integrityRes.json()) as [string, number, number, string];

  // -- 6. the verdict ladder ----------------------------------------------------
  const hasMinterPath =
    typeof integrityToken === 'string' &&
    integrityToken.length > 0 &&
    typeof signalOutput[0] === 'function';
  if (hasMinterPath) {
    phase('minter', 'WebPoMinter.create (live)');
    try {
      const minter = await WebPoMinter.create(
        {
          integrityToken,
          estimatedTtlSecs,
          mintRefreshThreshold,
          websafeFallbackToken,
        },
        signalOutput,
      );
      // The session token (visitorData-bound) — minted EAGERLY: it also
      // proves the minter end-to-end before we declare 'live'.
      phase('session-token', 'minting the visitorData-bound session token');
      const sessionToken = await minter.mintAsWebsafeString(visitorData);
      const expiresAtMs =
        Date.now() + Math.max(estimatedTtlSecs ?? 21600, 300) * 1000;
      phase('minter', 'live mint session ready');
      return {
        session: {
          kind: 'live',
          visitorData,
          expiresAtMs,
          gvsBindsToVideoId: GVS_BINDS_TO_VIDEO_ID,
          mintPlayerToken: (videoId: string) =>
            minter.mintAsWebsafeString(videoId),
          mintGvsToken: (videoId: string) =>
            GVS_BINDS_TO_VIDEO_ID
              ? minter.mintAsWebsafeString(videoId)
              : Promise.resolve(sessionToken),
          sessionToken,
        },
        braveShieldsAdvice: snapshotAttempt === 'skip-privacy-buffer',
        snapshotAttempt,
      };
    } catch (err) {
      // The minter path is dead (PMD/APF class failures) — degrade
      // honestly through the fallback ladder below.
      phase('minter', `live minter failed (${describe(err)}) — degrading`);
    }
  } else {
    phase(
      'minter',
      `no live minter (integrityToken ${typeof integrityToken === 'string' && integrityToken.length > 0 ? 'present' : 'EMPTY'}, signal ${hasMinterPath ? 'ok' : 'EMPTY'}) — degrading`,
    );
  }

  // -- 6b. websafe fallback (server-issued; GenerateIT answered) ---------------
  if (typeof websafeFallbackToken === 'string' && websafeFallbackToken.length > 0) {
    phase('minter', 'websafeFallbackToken session (low-trust)');
    return {
      session: {
        kind: 'websafe-fallback',
        visitorData,
        expiresAtMs: Date.now() + FALLBACK_SESSION_TTL_MS,
        gvsBindsToVideoId: GVS_BINDS_TO_VIDEO_ID,
        mintPlayerToken: async () => websafeFallbackToken,
        mintGvsToken: async () =>
          GVS_BINDS_TO_VIDEO_ID ? websafeFallbackToken : websafeFallbackToken,
        sessionToken: websafeFallbackToken,
      },
      braveShieldsAdvice: true,
      snapshotAttempt,
    };
  }

  // -- 6c. cold start (fully local, sps=2 window) --------------------------------
  phase('minter', 'cold-start token session (local XOR)');
  return {
    session: {
      kind: 'coldstart',
      visitorData,
      expiresAtMs: Date.now() + FALLBACK_SESSION_TTL_MS,
      gvsBindsToVideoId: GVS_BINDS_TO_VIDEO_ID,
      mintPlayerToken: (videoId: string) =>
        Promise.resolve(createColdStartToken(videoId)),
      mintGvsToken: (videoId: string) =>
        Promise.resolve(
          createColdStartToken(GVS_BINDS_TO_VIDEO_ID ? videoId : visitorData),
        ),
      sessionToken: createColdStartToken(visitorData),
    },
    braveShieldsAdvice: true,
    snapshotAttempt,
  };
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

/**
 * Snapshot with an optional abort signal (VM ops race their own timer).
 * `skipPrivacyBuffer` MUST stay undefined on the primary attempt — the
 * current trayride VM throws on an explicit `false` (see the G4.2 note at
 * the call site); only the Brave retry passes `true`.
 */
async function snapshotWith(
  botGuard: BotGuardClient,
  webPoSignalOutput: WebPoSignalOutput,
  skipPrivacyBuffer: true | undefined,
  signal?: AbortSignal | null,
): Promise<string> {
  if (signal?.aborted === true) {
    throw new MintError('snapshot', 'aborted before snapshot');
  }
  return botGuard.snapshot(
    skipPrivacyBuffer === true
      ? { webPoSignalOutput, skipPrivacyBuffer: true }
      : { webPoSignalOutput },
    // Generous timeout: mobile devices run the VM slower than desktop.
    10_000,
  );
}

/**
 * Inject the (inline) BotGuard interpreter as a script tag in the MAIN
 * page — deduped by interpreterHash (the official browser pattern). The
 * VM global (e.g. window.trayride) lands on the page; BotGuardClient
 * reads it from globalThis.
 */
function injectInterpreter(challenge: IBotguardClientSideBgChallenge): void {
  const interpreterJavascript =
    challenge.interpreterJavascript
      ?.privateDoNotAccessOrElseSafeScriptWrappedValue;
  if (typeof interpreterJavascript !== 'string' || interpreterJavascript.length === 0) {
    throw new MintError(
      'interpreter',
      'challenge has no inline interpreter javascript',
    );
  }
  const id = challenge.interpreterHash ?? 'streamify-botguard-interpreter';
  if (document.getElementById(id) === null) {
    const script = document.createElement('script');
    script.id = id;
    script.type = 'text/javascript';
    script.textContent = interpreterJavascript;
    document.head.appendChild(script);
  }
}

function describe(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
