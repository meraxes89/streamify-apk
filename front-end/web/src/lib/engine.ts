/// <reference types="vite/client" />
/**
 * lib/engine.ts — THE BRIDGE CONTRACT between the React proof console and the
 * Rust wasm engine (front-end/crates/streamify-wasm, wasm-pack `--target web`).
 *
 * The wasm facade output (wasm-pack `pkg`) is copied into `src/wasm/` by build
 * tooling; a committed stub (`src/wasm/streamify_wasm.js`) keeps the web build
 * green until the real facade lands. All UI code must talk to the engine
 * through this module — never import `src/wasm/*` directly from components.
 *
 * Expected wasm module shape:
 *   - `export default init(): Promise<void>` (instantiate the module, once)
 *   - `ping(): string`
 *   - `set_relay_base_url(url: string): void`
 *   - `search_music(query: string): Promise<string>`      — JSON string of Track[]
 *   - `resolve_stream(videoId: string): Promise<string>` — JSON string of stream info
 */

export interface Track {
  videoId: string;
  title: string;
  artist: string;
  album?: string;
  durationSec?: number;
  thumbnailUrl?: string;
  isLicensed?: boolean;
  viewCount?: number;
}

export interface StreamInfo {
  streamUrl: string;
  itag: number;
  mimeType: string;
  durationSec?: number;
}

export interface EngineStatus {
  loaded: boolean;
  ping: string | null;
  error?: string;
  relayBase: string | null;
}

export class EngineNotReadyError extends Error {}

const WASM_MISSING_ERROR =
  'wasm module not built — wasm-pack output missing from src/wasm (see front-end/crates/streamify-wasm)';

/** Shape of the wasm facade module (stub now, wasm-pack pkg output later). */
type WasmModule = typeof import('../wasm/streamify_wasm.js');

let relayCandidate: string | null = null;
let relayBase: string | null = null;
let wasmModule: WasmModule | null = null;
let wasmPromise: Promise<WasmModule> | null = null;

/**
 * Relay base, read once (memoized): `?relay=URL` query param wins, then
 * `VITE_RELAY_BASE_URL`, then the dev-only default `http://localhost:8787`.
 */
export function relayBaseCandidate(): string {
  if (relayCandidate === null) {
    relayCandidate =
      new URLSearchParams(window.location.search).get('relay') ||
      (import.meta.env.VITE_RELAY_BASE_URL as string | undefined) ||
      (import.meta.env.DEV ? 'http://localhost:8787' : '');
  }
  return relayCandidate;
}

/** Import the facade + run init() — memoized so init happens at most once. */
function ensureWasm(): Promise<WasmModule> {
  if (wasmPromise === null) {
    wasmPromise = import('../wasm/streamify_wasm.js').then(async (mod) => {
      await mod.default(); // init() — memoized by this promise
      return mod;
    });
    // A failed load must not poison later retries (e.g. after the real pkg lands).
    wasmPromise.catch(() => {
      wasmPromise = null;
    });
  }
  return wasmPromise;
}

/**
 * Load the wasm engine (init runs once, memoized) and point it at the relay.
 * Never rejects — failures come back as `{ loaded: false, error }`.
 */
export async function loadEngine(base: string): Promise<EngineStatus> {
  relayBase = base;
  try {
    const mod = await ensureWasm();
    if (base !== '') {
      mod.set_relay_base_url(base);
    }
    wasmModule = mod;
    return { loaded: true, ping: mod.ping(), relayBase: base };
  } catch {
    wasmModule = null;
    return { loaded: false, ping: null, relayBase: base, error: WASM_MISSING_ERROR };
  }
}

/**
 * Search YouTube Music.
 *
 * G4: the PRIMARY lane is the YouTube.js session (youtubei.js
 * `yt.search`) — its innertube calls ride the dual-lane fetch
 * (lib/ytSession.ts), i.e. they egress DIRECT from the phone to
 * youtubei.googleapis.com (the CORS-open API surface) instead of the
 * relay's burned Cloudflare egress. This also puts search and playback on
 * ONE session envelope (the G3b doc's "natural follow-up"), and kills the
 * stale-results failure mode: a youtubei.js search either resolves fresh
 * or throws honestly — it never replays a cached shelf for a new query.
 *
 * The Rust engine's `search_music` (rustypipe via the relay) stays as the
 * FALLBACK lane for environments where the direct lane is unreachable —
 * degraded (relay egress) but alive, same policy as the resolver.
 */
export async function searchMusic(query: string): Promise<Track[]> {
  try {
    return await ytSearchTracks(query);
  } catch (err) {
    if (wasmModule === null) {
      throw err instanceof Error ? err : new EngineNotReadyError(String(err));
    }
    const raw = await wasmModule.search_music(query);
    return parseTracks(raw);
  }
}

// ---------------------------------------------------------------------------
// G4: the YouTube.js search lane (direct egress)
// ---------------------------------------------------------------------------

/**
 * Search via the app's YouTube.js session and map the result into Track[].
 * Written defensively: youtubei.js's parsed nodes vary by revision, so
 * every field read is shape-guarded — a partial row is dropped, never
 * thrown. Video results only (music consumption), capped at 20.
 */
async function ytSearchTracks(query: string): Promise<Track[]> {
  const { getYt } = await import('./ytSession');
  const yt = await getYt();
  const results = await yt.search(query);
  const videos: unknown[] = Array.isArray(
    (results as { videos?: unknown[] }).videos,
  )
    ? ((results as { videos: unknown[] }).videos ?? [])
    : [];
  const tracks: Track[] = [];
  for (const raw of videos) {
    if (tracks.length >= 20) break;
    const track = mapYtVideoToTrack(raw);
    if (track !== null) tracks.push(track);
  }
  if (tracks.length === 0) {
    throw new Error(`youtubei.js search returned no video results for "${query}"`);
  }
  return tracks;
}

/** One youtubei.js search video node → Track (null when unusable). */
function mapYtVideoToTrack(raw: unknown): Track | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const node = raw as Record<string, unknown>;
  const videoId =
    typeof node.id === 'string'
      ? node.id
      : typeof (node.video_id as unknown) === 'string'
        ? (node.video_id as string)
        : '';
  if (videoId === '') return null;
  const title = textOf(node.title);
  const author = node.author;
  const artist =
    typeof author === 'object' && author !== null
      ? textOf((author as Record<string, unknown>).name)
      : textOf(node.author);
  const duration = node.duration;
  const durationSec =
    typeof duration === 'object' && duration !== null
      ? numOrUndefined((duration as Record<string, unknown>).seconds)
      : numOrUndefined(node.duration);
  const thumbs = node.thumbnails;
  let thumbnailUrl: string | undefined;
  if (Array.isArray(thumbs) && thumbs.length > 0) {
    const first = thumbs[thumbs.length - 1] as Record<string, unknown>;
    if (typeof first.url === 'string') thumbnailUrl = first.url;
  }
  return {
    videoId,
    title: title === '' ? videoId : title,
    artist,
    durationSec,
    thumbnailUrl,
    viewCount: numOrUndefined(node.view_count),
  };
}

function textOf(value: unknown): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'object' && value !== null) {
    const text = (value as Record<string, unknown>).text;
    if (typeof text === 'string') return text;
    const runs = (value as Record<string, unknown>).runs;
    if (Array.isArray(runs)) {
      return runs
        .map((r) => textOf((r as Record<string, unknown>).text))
        .join('');
    }
  }
  return '';
}

function numOrUndefined(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

/**
 * Resolve a playable stream for a video. The engine returns a JSON STRING;
 * returns null when the video is unresolvable. Throws EngineNotReadyError when
 * the engine has not been loaded.
 */
export async function resolveStream(videoId: string): Promise<StreamInfo | null> {
  if (wasmModule === null) {
    throw new EngineNotReadyError('engine not loaded');
  }
  const raw = await wasmModule.resolve_stream(videoId);
  return parseStreamInfo(raw);
}

/**
 * Wrap a direct (googlevideo) URL for the CORS relay: `${relayBase}/r?u=<encoded>`.
 * Callers skip the wrap when the URL already starts with the relay base.
 */
export function relayWrap(url: string): string {
  const base = relayBase ?? relayBaseCandidate();
  return `${base}/r?u=${encodeURIComponent(url)}`;
}

function parseTracks(raw: string): Track[] {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) {
    return [];
  }
  const tracks: Track[] = [];
  for (const entry of parsed) {
    if (!isRecord(entry)) {
      continue;
    }
    const track: Track = {
      videoId: readString(entry.videoId),
      title: readString(entry.title),
      artist: readString(entry.artist),
      album: readOptionalString(entry.album),
      durationSec: readOptionalNumber(entry.durationSec),
      thumbnailUrl: readOptionalString(entry.thumbnailUrl),
      isLicensed: readOptionalBoolean(entry.isLicensed),
      viewCount: readOptionalNumber(entry.viewCount),
    };
    if (track.videoId === '') {
      continue; // not playable without a videoId
    }
    tracks.push(track);
  }
  return tracks;
}

function parseStreamInfo(raw: string): StreamInfo | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(parsed)) {
    return null;
  }
  const streamUrl = readString(parsed.streamUrl);
  if (streamUrl === '') {
    return null;
  }
  return {
    streamUrl,
    itag: readOptionalNumber(parsed.itag) ?? 0,
    mimeType: readString(parsed.mimeType),
    durationSec: readOptionalNumber(parsed.durationSec),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function readString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function readOptionalString(value: unknown): string | undefined {
  return typeof value === 'string' ? value : undefined;
}

function readOptionalNumber(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function readOptionalBoolean(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}
