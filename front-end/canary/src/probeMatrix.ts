/**
 * Streamify Web — W0.1b residential canary probe matrix (WS-W P0).
 *
 * Browser translation of tools/probe-clients.sh (the 10-client bare-client ×
 * content matrix) plus the four R-C condition probes from the WS-W P0 brief —
 * all measured from the VISITOR's own residential network, which is the whole
 * point: a server-side probe would measure a datacenter egress, not the fleet
 * conditions the product actually ships into.
 *
 * Relay contract (front-end/relay/src/index.ts): browsers cannot set
 * User-Agent or POST cross-origin to youtube.com, so every youtubei call goes
 * through the relay via relayWrap() (front-end/web/src/wasm.ts) with the app
 * UA swapped in through X-Streamify-UA and the client number/version carried
 * on X-YouTube-Client-Name / -Version. The two probes that measure the
 * BROWSER's direct relationship to the CDN (RC-3's range fetch, RC-4's
 * <audio> playback) deliberately bypass the relay — that is the measurement.
 *
 * Privacy (deliberate, W0.1b brief): NO IP address is read or recorded, NO
 * geolocation API is touched, NO fingerprinting beyond the user agent string
 * (which postResults.ts records verbatim as one `user_agent` column).
 * "Residential" is a fact about whose network loaded the page — known by who
 * the PO sent the URL to — never something this code classifies or collects.
 */
import { relayWrap } from "../../web/src/wasm";

// ─────────────────────────── probe parameters ────────────────────────────

/** tools/probe-clients.sh default ids: standard content + licensed/official. */
export const STANDARD_VIDEO_ID = "dQw4w9WgXcQ";
export const LICENSED_VIDEO_ID = "4NRXx6U8ABQ";

export const CANARY_VIDEOS = [
  { id: STANDARD_VIDEO_ID, label: "standard" },
  { id: LICENSED_VIDEO_ID, label: "licensed" },
] as const;

export type CanaryVideo = (typeof CANARY_VIDEOS)[number];

/**
 * Client-side politeness gap between probes (the bash sleeps 2s between
 * requests; a page holding a visitor's attention stays lighter).
 */
const PROBE_SPACING_MS = 300;

/** curl --max-time 20 parity for each player POST. */
const REQUEST_TIMEOUT_MS = 20_000;

/** RC-4 window: how long to wait for timeupdate past 0.2s. */
const DIRECT_PLAY_TIMEOUT_MS = 4_000;

/**
 * RC-3's ANDROID_VR facade resolution pins the fleet signatureTimestamp,
 * exactly like crates/streamify-wasm/src/clients.rs (fleet-config.json,
 * 19850 as of version 20261025).
 */
const SIGNATURE_TIMESTAMP = 19850;

const PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player";

// ─────────────────────────── the 10-client table ──────────────────────────
//
// Translated row-for-row from the CLIENTS array in tools/probe-clients.sh
// (values sourced from yt-dlp master yt_dlp/extractor/youtube/_base.py
// INNERTUBE_CLIENTS — versions rot monthly, re-verify before trusting).
// Field order there: name|host|clientNumber|clientName|version|userAgent|
// extraClientJson|extraTopJson. Empty extraClientJson/extraTopJson become {}.

export interface ClientSpec {
  /** Row name, verbatim from the bash (e.g. "VR_1.65.10"). */
  name: string;
  /** Host the player POST targets (relay allowlist member). */
  host: string;
  /** X-YouTube-Client-Name value. */
  clientNumber: string;
  /** context.client.clientName value. */
  clientName: string;
  clientVersion: string;
  /** Outbound User-Agent (swapped in by the relay via X-Streamify-UA). */
  userAgent: string;
  /** Merged into context.client after the base fields (jq `+` semantics:
   * these fields win on key collision — none of the 10 rows collide). */
  extraClientJson: Record<string, unknown>;
  /** Merged into the top-level body (WEB_EMBEDDED's thirdParty). */
  extraTopJson: Record<string, unknown>;
}

export const PROBE_CLIENTS: readonly ClientSpec[] = [
  {
    name: "VR_1.60.19",
    host: "www.youtube.com",
    clientNumber: "28",
    clientName: "ANDROID_VR",
    clientVersion: "1.60.19",
    userAgent:
      "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    extraClientJson: {
      deviceMake: "Oculus",
      deviceModel: "Quest 3",
      androidSdkVersion: 32,
      osName: "Android",
      osVersion: "12L",
    },
    extraTopJson: {},
  },
  {
    name: "VR_1.65.10",
    host: "www.youtube.com",
    clientNumber: "28",
    clientName: "ANDROID_VR",
    clientVersion: "1.65.10",
    userAgent:
      "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
    extraClientJson: {
      deviceMake: "Oculus",
      deviceModel: "Quest 3",
      androidSdkVersion: 32,
      osName: "Android",
      osVersion: "12L",
    },
    extraTopJson: {},
  },
  {
    name: "ANDROID",
    host: "www.youtube.com",
    clientNumber: "3",
    clientName: "ANDROID",
    clientVersion: "21.26.364",
    userAgent: "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
    extraClientJson: { androidSdkVersion: 30, osName: "Android", osVersion: "11" },
    extraTopJson: {},
  },
  {
    name: "IOS",
    host: "www.youtube.com",
    clientNumber: "5",
    clientName: "IOS",
    clientVersion: "21.26.4",
    userAgent:
      "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
    extraClientJson: {
      deviceMake: "Apple",
      deviceModel: "iPhone16,2",
      osName: "iPhone",
      osVersion: "18.3.2.22D82",
    },
    extraTopJson: {},
  },
  {
    name: "VISIONOS",
    host: "www.youtube.com",
    clientNumber: "101",
    clientName: "VISIONOS",
    clientVersion: "1.02",
    userAgent:
      "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
    extraClientJson: {
      deviceMake: "Apple",
      deviceModel: "RealityDevice17,1",
      osName: "visionOS",
      osVersion: "26.5.23O471",
    },
    extraTopJson: {},
  },
  {
    name: "MWEB",
    host: "www.youtube.com",
    clientNumber: "2",
    clientName: "MWEB",
    clientVersion: "2.20260708.05.00",
    userAgent:
      "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
    extraClientJson: {},
    extraTopJson: {},
  },
  {
    name: "TVHTML5",
    host: "www.youtube.com",
    clientNumber: "7",
    clientName: "TVHTML5",
    clientVersion: "7.20260707.07.00",
    userAgent:
      "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)",
    extraClientJson: {},
    extraTopJson: {},
  },
  {
    name: "TV_DOWNGRADED",
    host: "www.youtube.com",
    clientNumber: "7",
    clientName: "TVHTML5",
    clientVersion: "5.20260707",
    userAgent: "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
    extraClientJson: {},
    extraTopJson: {},
  },
  {
    name: "WEB_EMBEDDED",
    host: "www.youtube.com",
    clientNumber: "56",
    clientName: "WEB_EMBEDDED_PLAYER",
    clientVersion: "2.20260708.00.00",
    userAgent: "Mozilla/5.0",
    extraClientJson: {},
    extraTopJson: { thirdParty: { embedUrl: "https://www.reddit.com/" } },
  },
  {
    name: "WEB_REMIX",
    host: "music.youtube.com",
    clientNumber: "67",
    clientName: "WEB_REMIX",
    clientVersion: "1.20260707.12.00",
    userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
    extraClientJson: {},
    extraTopJson: {},
  },
];

// ─────────────────────────── result types ────────────────────────────────

/**
 * One client × video matrix row (jq-equivalent of the bash's parse).
 * NB the bash's own column labels drifted from its jq (its CIPHERED column
 * was always empty); the field names here follow the W0.1b brief.
 */
export interface ClientProbeResult {
  /** Row name from PROBE_CLIENTS (e.g. "TV_DOWNGRADED"). */
  client: string;
  videoId: string;
  /** "standard" | "licensed" (human label for the videoId). */
  videoLabel: string;
  /** .playabilityStatus.status, "NO-RESPONSE" when absent (bash parity),
   * or "ERROR" when the request/parse itself failed. */
  status: string;
  /** .playabilityStatus.reason, "-" when absent (bash parity); for ERROR
   * rows, the failure message; relay error bodies are surfaced too. */
  reason: string;
  /** adaptiveFormats with itag 140 (aac audio). */
  itag140: number;
  /** adaptiveFormats with itag 251 (opus audio). */
  itag251: number;
  /** Formats (adaptiveFormats ∪ formats — the engine InnertubeParser pool;
   * the bash jq only scanned adaptiveFormats) with a non-empty url and a
   * mimeType starting with audio/. */
  directAudio: number;
  /** adaptiveFormats carrying a signatureCipher field. */
  ciphered: number;
  /** Round-trip of the player POST in ms (fetch → body parsed). */
  latencyMs: number;
  /** HTTP status the relay answered with (0 = request threw; the bash's
   * curl -s could not see this, the browser can). */
  httpStatus: number;
}

/** One of the four R-C condition verdicts from the WS-W P0 brief. */
export interface RcConditionResult {
  id: "RC-1" | "RC-2" | "RC-3" | "RC-4";
  label: string;
  verdict: string;
  detail: string;
  /** Structured measurement facts (per-client rows, CDN host, MediaError
   * code, …) — never an IP address or anything beyond the W0.1b scope. */
  facts?: Record<string, unknown>;
}

/** Live progress for the run button / status line. */
export type ProbeProgress =
  | {
      phase: "matrix";
      /** Matrix requests completed before this one. */
      done: number;
      total: number;
      /** 1-based index of the client being probed within the 10-client
       * table ("probing client 7/10"). */
      clientIndex: number;
      clientCount: number;
      client: string;
      videoId: string;
    }
  | { phase: "rc"; step: string };

export interface RunProbeMatrixOptions {
  onProgress?: (progress: ProbeProgress) => void;
}

export interface CanaryRunResult {
  matrix: ClientProbeResult[];
  rc: RcConditionResult[];
  startedAt: string;
  finishedAt: string;
  /** Environment note posted as the `notes` column (RC-1 facts). */
  note: string;
}

// ─────────────────────────── request building ────────────────────────────

/** Minimal player-response shape the probes read. */
interface PlayerFormat {
  itag?: number;
  url?: string;
  mimeType?: string;
  signatureCipher?: string;
}
interface PlayerResponse {
  playabilityStatus?: { status?: string; reason?: string };
  streamingData?: { adaptiveFormats?: PlayerFormat[]; formats?: PlayerFormat[] };
}

const sleep = (ms: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, ms));

function isDirectAudioFormat(f: PlayerFormat): boolean {
  return (
    typeof f.url === "string" &&
    f.url !== "" &&
    (f.mimeType ?? "").startsWith("audio/")
  );
}

/**
 * jq-equivalent of the payload tools/probe-clients.sh builds:
 *   {context: {client: ({clientName, clientVersion, hl:"en", gl:"US"}
 *               + extraClientJson
 *               + (if extraClientJson lacks userAgent then {userAgent}
 *                  else {}))},
 *    videoId, contentCheckOk:true, racyCheckOk:true} + extraTopJson
 */
export function buildPlayerBody(
  client: ClientSpec,
  videoId: string,
): Record<string, unknown> {
  const clientFields: Record<string, unknown> = {
    clientName: client.clientName,
    clientVersion: client.clientVersion,
    hl: "en",
    gl: "US",
    ...client.extraClientJson,
  };
  if (!("userAgent" in client.extraClientJson)) {
    clientFields.userAgent = client.userAgent;
  }
  return {
    context: { client: clientFields },
    videoId,
    contentCheckOk: true,
    racyCheckOk: true,
    ...client.extraTopJson,
  };
}

/** POST one player request through the relay (headers per the relay contract). */
async function postPlayer(
  client: ClientSpec,
  videoId: string,
): Promise<{ json: unknown; httpStatus: number }> {
  const resp = await fetch(
    relayWrap(`https://${client.host}/youtubei/v1/player`),
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-YouTube-Client-Name": client.clientNumber,
        "X-YouTube-Client-Version": client.clientVersion,
        // Browsers cannot set User-Agent; the relay swaps this header into it.
        "X-Streamify-UA": client.userAgent,
      },
      body: JSON.stringify(buildPlayerBody(client, videoId)),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    },
  );
  let json: unknown = null;
  try {
    json = await resp.json();
  } catch {
    json = null;
  }
  return { json, httpStatus: resp.status };
}

/** Surface a relay JSON error body (e.g. 502 upstream) in the reason column. */
function describeBody(json: unknown): string {
  const body = json as { error?: unknown } | null;
  if (body && typeof body.error === "string" && body.error !== "") {
    return `relay: ${body.error}`;
  }
  return "-";
}

function summarizePlayerResponse(
  client: ClientSpec,
  video: CanaryVideo,
  json: unknown,
  httpStatus: number,
  latencyMs: number,
): ClientProbeResult {
  const body = (json ?? {}) as PlayerResponse;
  const adaptive = body.streamingData?.adaptiveFormats ?? [];
  const progressive = body.streamingData?.formats ?? [];
  return {
    client: client.name,
    videoId: video.id,
    videoLabel: video.label,
    status: body.playabilityStatus?.status ?? "NO-RESPONSE",
    reason: body.playabilityStatus?.reason ?? describeBody(json),
    itag140: adaptive.filter((f) => f.itag === 140).length,
    itag251: adaptive.filter((f) => f.itag === 251).length,
    directAudio: [...adaptive, ...progressive].filter(isDirectAudioFormat).length,
    ciphered: adaptive.filter((f) => f.signatureCipher !== undefined).length,
    latencyMs,
    httpStatus,
  };
}

async function probeClientVideo(
  client: ClientSpec,
  video: CanaryVideo,
): Promise<ClientProbeResult> {
  const started = performance.now();
  try {
    const { json, httpStatus } = await postPlayer(client, video.id);
    return summarizePlayerResponse(
      client,
      video,
      json,
      httpStatus,
      Math.round(performance.now() - started),
    );
  } catch (err) {
    return {
      client: client.name,
      videoId: video.id,
      videoLabel: video.label,
      status: "ERROR",
      reason: err instanceof Error ? err.message : String(err),
      itag140: 0,
      itag251: 0,
      directAudio: 0,
      ciphered: 0,
      latencyMs: Math.round(performance.now() - started),
      httpStatus: 0,
    };
  }
}

// ─────────────────────── RC-3/RC-4 ANDROID_VR resolution ─────────────────
//
// One quick ANDROID_VR 1.65.10 player POST for dQw4w9WgXcQ, mirroring the
// wasm facade recipe (crates/streamify-wasm/src/clients.rs PlayerClient::Vr):
// clientNumber 28, the VR_1.65.10 UA from the probe table above, and
// playbackContext.contentPlaybackContext with the pinned signatureTimestamp.
// Unlike the probe rows, the facade body carries timeZone/utcOffsetMinutes
// and no gl — kept verbatim from the facade.

type VrResolveOutcome =
  | { ok: true; url: string; mimeType: string; itag: number }
  | { ok: false; status: string; reason: string };

function buildVrFacadeBody(
  userAgent: string,
  videoId: string,
): Record<string, unknown> {
  return {
    context: {
      client: {
        clientName: "ANDROID_VR",
        clientVersion: "1.65.10",
        deviceMake: "Oculus",
        deviceModel: "Quest 3",
        androidSdkVersion: 32,
        userAgent,
        osName: "Android",
        osVersion: "12L",
        hl: "en",
        timeZone: "UTC",
        utcOffsetMinutes: 0,
      },
    },
    videoId,
    playbackContext: {
      contentPlaybackContext: {
        html5Preference: "HTML5_PREF_WANTS",
        signatureTimestamp: SIGNATURE_TIMESTAMP,
      },
    },
    contentCheckOk: true,
    racyCheckOk: true,
  };
}

async function resolveVrDirectAudio(
  videoId: string,
): Promise<VrResolveOutcome> {
  const vr = PROBE_CLIENTS.find((c) => c.name === "VR_1.65.10");
  if (!vr) {
    return {
      ok: false,
      status: "CONFIG",
      reason: "VR_1.65.10 row missing from the probe table",
    };
  }
  try {
    const resp = await fetch(relayWrap(PLAYER_ENDPOINT), {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-YouTube-Client-Name": vr.clientNumber,
        "X-YouTube-Client-Version": vr.clientVersion,
        "X-Streamify-UA": vr.userAgent,
      },
      body: JSON.stringify(buildVrFacadeBody(vr.userAgent, videoId)),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    let json: unknown = null;
    try {
      json = await resp.json();
    } catch {
      json = null;
    }
    const body = (json ?? {}) as PlayerResponse;
    const adaptive = body.streamingData?.adaptiveFormats ?? [];
    const progressive = body.streamingData?.formats ?? [];
    const first = [...adaptive, ...progressive].find(isDirectAudioFormat);
    if (!first || typeof first.url !== "string") {
      return {
        ok: false,
        status: body.playabilityStatus?.status ?? `HTTP ${resp.status}`,
        reason:
          body.playabilityStatus?.reason ??
          "no direct audio url in the ANDROID_VR 1.65.10 player response",
      };
    }
    return {
      ok: true,
      url: first.url,
      mimeType: first.mimeType ?? "",
      itag: first.itag ?? 0,
    };
  } catch (err) {
    return {
      ok: false,
      status: "ERROR",
      reason: err instanceof Error ? err.message : String(err),
    };
  }
}

// ─────────────────────────── the R-C probes ──────────────────────────────

/**
 * RC-1 "ip class" — environment facts ONLY. NO IP address, NO geolocation
 * API, NO fingerprinting beyond the user agent string (recorded separately
 * as the user_agent column by postResults.ts). The residential class of the
 * visitor's network is established out-of-band by who the PO sent the URL
 * to; this probe never tries to classify the IP itself.
 */
function probeIpClass(): RcConditionResult {
  return {
    id: "RC-1",
    label: "ip class",
    verdict: "no-ip-collected",
    detail:
      "Results are measured from the visitor's own network and browser. No IP " +
      "address, geolocation, or fingerprinting beyond the user agent string is " +
      "collected — the residential class is known from who the PO sent the URL to.",
    facts: {
      language: navigator.language,
      timezoneOffsetMinutes: new Date().getTimezoneOffset(),
    },
  };
}

/**
 * RC-2 "po-token need" — derived from the matrix: for the licensed video,
 * per fleet client, LOGIN_REQUIRED (or a bot-check reason) means that client
 * now demands a po-token from this network.
 */
function probePoTokenNeed(matrix: ClientProbeResult[]): RcConditionResult {
  const perClient = matrix
    .filter((r) => r.videoId === LICENSED_VIDEO_ID)
    .map((r) => ({
      client: r.client,
      status: r.status,
      reason: r.reason,
      verdict:
        r.status === "LOGIN_REQUIRED" || /\bbot\b/i.test(r.reason)
          ? "po-required"
          : "po-free",
    }));
  const poRequired = perClient.filter((r) => r.verdict === "po-required").length;
  return {
    id: "RC-2",
    label: "po-token need",
    verdict: perClient.length > 0 && poRequired > 0 ? "po-required" : "po-free",
    detail:
      `licensed video ${LICENSED_VIDEO_ID}: ${poRequired}/${perClient.length} ` +
      "fleet clients hit a LOGIN_REQUIRED/bot-check wall from this network " +
      "(per-client verdicts in facts).",
    facts: { videoId: LICENSED_VIDEO_ID, perClient },
  };
}

function cdnHost(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "(unparseable)";
  }
}

/**
 * RC-3 "CDN ACAO" — from the browser, range-fetch the first DIRECT
 * googlevideo audio url (no relay): if the fetch resolves with 200/206 the
 * CDN serves ACAO to browser origins; a thrown TypeError means CORS blocked
 * it; any other status is recorded verbatim.
 */
async function probeCdnAcao(
  resolve: VrResolveOutcome,
  onProgress?: (progress: ProbeProgress) => void,
): Promise<RcConditionResult> {
  if (!resolve.ok) {
    return {
      id: "RC-3",
      label: "CDN ACAO",
      verdict: "no-direct-url",
      detail:
        `ANDROID_VR 1.65.10 resolution returned ${resolve.status}` +
        `${resolve.reason ? ` (${resolve.reason})` : ""} — no direct audio ` +
        "url to range-fetch from the browser this run.",
      facts: { resolutionStatus: resolve.status },
    };
  }
  onProgress?.({
    phase: "rc",
    step: "RC-3: direct CDN range fetch (no relay)",
  });
  const host = cdnHost(resolve.url);
  try {
    const resp = await fetch(resolve.url, {
      headers: { Range: "bytes=0-1023" },
      mode: "cors",
    });
    return {
      id: "RC-3",
      label: "CDN ACAO",
      verdict:
        resp.status === 200 || resp.status === 206
          ? "acao-present"
          : `http-${resp.status}`,
      detail:
        'direct fetch(url, { headers: { Range: "bytes=0-1023" }, mode: "cors" }) ' +
        `resolved with HTTP ${resp.status} from ${host}` +
        `${resolve.itag ? ` (itag ${resolve.itag} ${resolve.mimeType})` : ""}.`,
      facts: {
        cdnHost: host,
        httpStatus: resp.status,
        itag: resolve.itag,
        mimeType: resolve.mimeType,
      },
    };
  } catch (err) {
    const name = err instanceof Error ? `${err.name}: ${err.message}` : String(err);
    return {
      id: "RC-3",
      label: "CDN ACAO",
      verdict: "acao-absent-cors-blocked",
      detail:
        `direct range fetch threw (${name}) — the CDN did not deliver a ` +
        `CORS-clean response to the browser for ${host}.`,
      facts: { cdnHost: host, fetchError: name },
    };
  }
}

/**
 * RC-4 "ip-pin tolerance" — a fresh <audio> element with the DIRECT
 * googlevideo url (no relay), play(), up to 4s: timeupdate past 0.2s means
 * the CDN serves media to this IP directly; error/stall/timeout (with the
 * MediaError code, if any) means it does not.
 */
async function probeIpPinTolerance(
  resolve: VrResolveOutcome,
  onProgress?: (progress: ProbeProgress) => void,
): Promise<RcConditionResult> {
  if (!resolve.ok) {
    return {
      id: "RC-4",
      label: "ip-pin tolerance",
      verdict: "no-direct-url",
      detail:
        `no direct audio url from the RC-3 resolution (${resolve.status}) — ` +
        "direct playback not measurable this run.",
      facts: { resolutionStatus: resolve.status },
    };
  }
  onProgress?.({
    phase: "rc",
    step: "RC-4: direct <audio> playback (no relay)",
  });
  const outcome = await new Promise<{
    verdict: string;
    detail: string;
    mediaErrorCode: number | null;
  }>((resolveOutcome) => {
    const audio = document.createElement("audio");
    let settled = false;
    let timer = 0;
    const finish = (
      verdict: string,
      detail: string,
      mediaErrorCode: number | null = null,
    ): void => {
      if (settled) {
        return;
      }
      settled = true;
      window.clearTimeout(timer);
      audio.pause();
      audio.removeAttribute("src");
      try {
        audio.load();
      } catch {
        // Best-effort teardown of a detached element.
      }
      resolveOutcome({ verdict, detail, mediaErrorCode });
    };
    timer = window.setTimeout(() => {
      finish(
        "direct-play-failed",
        `timeout: no timeupdate past 0.2s within 4s (currentTime=${audio.currentTime.toFixed(2)}s)`,
      );
    }, DIRECT_PLAY_TIMEOUT_MS);
    audio.addEventListener("timeupdate", () => {
      if (audio.currentTime > 0.2) {
        finish(
          "direct-play-ok",
          `timeupdate fired at currentTime=${audio.currentTime.toFixed(2)}s`,
        );
      }
    });
    audio.addEventListener("error", () => {
      const code = audio.error ? audio.error.code : null;
      finish(
        "direct-play-failed",
        `error event${code !== null ? ` (MediaError code ${code})` : ""}`,
        code,
      );
    });
    audio.addEventListener("stalled", () => {
      finish(
        "direct-play-failed",
        "stalled event before currentTime advanced past 0.2s",
      );
    });
    audio.preload = "auto";
    audio.src = resolve.ok ? resolve.url : ""; // DIRECT googlevideo, no relay
    void audio.play().catch((err: unknown) => {
      finish(
        "direct-play-failed",
        `play() rejected: ${err instanceof Error ? err.message : String(err)}`,
      );
    });
  });
  return {
    id: "RC-4",
    label: "ip-pin tolerance",
    verdict: outcome.verdict,
    detail: outcome.detail,
    facts: {
      cdnHost: cdnHost(resolve.url),
      mediaErrorCode: outcome.mediaErrorCode,
    },
  };
}

// ─────────────────────────── the run ─────────────────────────────────────

function buildNote(): string {
  return (
    "W0.1b residential canary: results are from the visitor's own network and " +
    `browser. navigator.language=${navigator.language}; timezone offset=` +
    `${new Date().getTimezoneOffset()} minutes. No IP address, geolocation, or ` +
    "fingerprinting beyond the user agent string was collected."
  );
}

/**
 * Run the full canary: the 10-client × 2-video matrix (probe-clients.sh
 * order — videos outer, clients inner, ~300ms apart) followed by the four
 * R-C condition probes. Returns everything the page renders and
 * postResults.ts posts.
 */
export async function runProbeMatrix(
  opts: RunProbeMatrixOptions = {},
): Promise<CanaryRunResult> {
  const onProgress = opts.onProgress;
  const startedAt = new Date().toISOString();
  const matrix: ClientProbeResult[] = [];

  for (let vi = 0; vi < CANARY_VIDEOS.length; vi++) {
    const video = CANARY_VIDEOS[vi];
    for (let ci = 0; ci < PROBE_CLIENTS.length; ci++) {
      const client = PROBE_CLIENTS[ci];
      if (vi > 0 || ci > 0) {
        await sleep(PROBE_SPACING_MS);
      }
      onProgress?.({
        phase: "matrix",
        done: matrix.length,
        total: CANARY_VIDEOS.length * PROBE_CLIENTS.length,
        clientIndex: ci + 1,
        clientCount: PROBE_CLIENTS.length,
        client: client.name,
        videoId: video.id,
      });
      matrix.push(await probeClientVideo(client, video));
    }
  }

  // RC-1 / RC-2 are instant and derived; RC-3 resolves once more (ANDROID_VR
  // facade recipe) and RC-4 reuses that direct url. Array stays RC-1..4.
  const rc1 = probeIpClass();
  const rc2 = probePoTokenNeed(matrix);

  await sleep(PROBE_SPACING_MS);
  onProgress?.({
    phase: "rc",
    step: "RC-3: ANDROID_VR 1.65.10 facade resolution of dQw4w9WgXcQ",
  });
  const resolve = await resolveVrDirectAudio(STANDARD_VIDEO_ID);
  const rc3 = await probeCdnAcao(resolve, onProgress);

  await sleep(PROBE_SPACING_MS);
  const rc4 = await probeIpPinTolerance(resolve, onProgress);

  return {
    matrix,
    rc: [rc1, rc2, rc3, rc4],
    startedAt,
    finishedAt: new Date().toISOString(),
    note: buildNote(),
  };
}
