/**
 * audio/hls/hlsRemux.ts — G3b: HLS fallback playback for the element path.
 *
 * WHY: the resolver's HLS rungs (visionos-hls / web-safari-hls) hand the
 * element an m3u8 manifest. Safari-class engines play m3u8 natively;
 * Chromium-class engines (Chrome/Brave on Android — the production
 * mobile target) DO NOT. Per the G3b directive, segmented formats the
 * element cannot play natively are remuxed in a dedicated Web Worker
 * (ffmpeg.wasm) into a single element-playable file.
 *
 * TWO LANES (both live in this module):
 *   • fmp4-concat (fast path, no download): YouTube's HLS audio variants
 *     are fragmented MP4 (an init segment + moof/mdat fragments). A
 *     byte concatenation [init][seg0][seg1]… is a valid fragmented MP4
 *     stream that every engine plays as one progressive file — the
 *     classic HLS-download trick, zero remux cost.
 *   • ffmpeg-remux (the directive's lane): MPEG-TS segments (live
 *     streams, older media) cannot byte-concat into anything the
 *     element plays. @ffmpeg/ffmpeg runs the remux
 *     (`-c copy`, NO transcode — AAC passes through untouched) inside
 *     its own Web Worker; the ~30 MB core wasm lazy-loads from unpkg
 *     ONLY when this lane is actually needed.
 *
 * ALL segment/manifest fetches go through the relay (googlevideo is
 * CORS-walled for foreign origins): one egress for the whole session,
 * consistent with the resolver's envelope.
 */

import { FFmpeg } from '@ffmpeg/ffmpeg';
import { toBlobURL } from '@ffmpeg/util';
import { relayUrl } from '../botguard/protocol';

/**
 * Vendored @ffmpeg/ffmpeg ESM worker (worker.js + const.js + errors.js,
 * verbatim from @ffmpeg/ffmpeg@0.12.15 dist/esm). The FFmpeg class
 * spawns its worker with {type:'module'} and, inside Vite's pre-bundled
 * deps, its own `new URL('./worker.js', import.meta.url)` cannot resolve
 * (the worker never spawns; load() hangs). `?worker&url` makes Vite
 * itself bundle the worker chain (dev: served module; build: emitted
 * chunk) and hands us a URL that works in BOTH.
 */
import ffmpegWorkerUrl from './ffmpeg-worker/worker.js?worker&url';

// ---------------------------------------------------------------------------
// Public surface
// ---------------------------------------------------------------------------

export interface RemuxOptions {
  relayBase: string;
  signal?: AbortSignal | null;
  /** Segment-granular progress (element UI keeps its Buffering state). */
  onProgress?: (loadedSegments: number, totalSegments: number, loadedBytes: number) => void;
}

export interface RemuxResult {
  /** An object (blob:) URL the element can load directly. */
  url: string;
  mime: string;
  bytes: number;
  /** Which lane produced the file (diagnostics + the decisions doc). */
  mode: 'fmp4-concat' | 'ffmpeg-remux';
}

/** Whether THIS engine plays m3u8 manifests natively (Safari-class). */
export function nativeHlsPlayback(): boolean {
  const probe = document.createElement('audio');
  return (
    probe.canPlayType('application/vnd.apple.mpegurl') !== '' ||
    probe.canPlayType('audio/mpegurl') !== ''
  );
}

/**
 * Fetch an HLS manifest (master → lowest-bandwidth audio variant when
 * needed) and produce ONE element-playable file for it.
 */
export async function remuxHlsForElement(
  manifestUrl: string,
  opts: RemuxOptions,
): Promise<RemuxResult> {
  const { signal, onProgress } = opts;

  // -- the media playlist ---------------------------------------------------
  let playlistUrl = manifestUrl;
  let playlistText = await fetchText(manifestUrl, opts.relayBase, signal);
  if (playlistText.includes('#EXT-X-STREAM-INF')) {
    // Master playlist: pick the lowest-bandwidth variant (for music the
    // cheapest variant is audio-only or near it — the element path plays
    // AUDIO; any video track is dropped by the remux/`-vn`).
    const variant = pickLowestBandwidthVariant(playlistText, manifestUrl);
    if (variant === null) {
      throw new Error('HLS master playlist has no variant');
    }
    playlistUrl = variant;
    playlistText = await fetchText(variant, opts.relayBase, signal);
  }

  // -- segments ----------------------------------------------------------------
  const { initUrl, segments } = parseMediaPlaylist(playlistText, playlistUrl);
  if (segments.length === 0) {
    throw new Error('HLS media playlist has no segments');
  }

  const initBytes =
    initUrl !== null ? new Uint8Array(await fetchBytes(initUrl, opts.relayBase, signal)) : null;

  const total = segments.length;
  let loaded = 0;
  let loadedBytes = initBytes?.byteLength ?? 0;

  // Ordered fetch with bounded concurrency (4): memory stays flat, order
  // is guaranteed by slot-indexed writes.
  const CONCURRENCY = 4;
  let nextIndex = 0;
  const results = new Array<Uint8Array>(total);
  const worker = async (): Promise<void> => {
    for (;;) {
      const i = nextIndex++;
      if (i >= total) return;
      const bytes = new Uint8Array(
        await fetchBytes(segments[i].url, opts.relayBase, signal),
      );
      results[i] = bytes;
      loaded += 1;
      loadedBytes += bytes.byteLength;
      onProgress?.(loaded, total, loadedBytes);
    }
  };
  await Promise.all(
    Array.from({ length: Math.min(CONCURRENCY, total) }, () => worker()),
  );
  const buffers: Uint8Array[] = initBytes !== null ? [initBytes] : [];
  for (const buf of results) buffers.push(buf);

  // -- lane pick -----------------------------------------------------------------
  if (isFragmentedMp4(buffers[0] as Uint8Array)) {
    // fMP4: byte-concat IS a valid progressive fragmented MP4 file.
    const blob = new Blob(buffers, { type: 'audio/mp4' });
    return {
      url: URL.createObjectURL(blob),
      mime: 'audio/mp4',
      bytes: blob.size,
      mode: 'fmp4-concat',
    };
  }

  // TS (or unknown): the ffmpeg.wasm remux lane (-c copy, audio only).
  const ffmpeg = await ensureFfmpeg();
  await ffmpeg.writeFile('input.ts', concatBuffers(buffers));
  try {
    await execWithBudget(ffmpeg, [
      '-i', 'input.ts',
      '-vn', // audio only (variants may carry a video track)
      '-c:a', 'copy', // pure remux — no transcode, seconds not minutes
      '-f', 'mp4',
      '-movflags', '+faststart',
      'out.m4a',
    ]);
  } finally {
    await ffmpeg.deleteFile('input.ts').catch(() => undefined);
  }
  const data = await ffmpeg.readFile('out.m4a');
  const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
  await ffmpeg.deleteFile('out.m4a').catch(() => undefined);
  const blob = new Blob([bytes], { type: 'audio/mp4' });
  return {
    url: URL.createObjectURL(blob),
    mime: 'audio/mp4',
    bytes: blob.size,
    mode: 'ffmpeg-remux',
  };
}

// ---------------------------------------------------------------------------
// ffmpeg.wasm — lazy, worker-resident (the FFmpeg class spawns its own
// dedicated Web Worker; the core wasm loads from unpkg on first need)
// ---------------------------------------------------------------------------

/**
 * The ESM core (NOT the UMD one): the FFmpeg class spawns type:'module'
 * workers, where importScripts is illegal — worker.js's load() falls
 * back to `await import(coreURL)`, which needs the Emscripten ES6
 * module. Pinned to the version @ffmpeg/ffmpeg@0.12.15 expects.
 */
const FFMPEG_CORE_BASE = 'https://unpkg.com/@ffmpeg/core@0.12.10/dist/esm';
/** Hard budget for the worker spawn + 32 MB core load (a hang must never wedge the element path). */
const FFMPEG_LOAD_BUDGET_MS = 60_000;

let ffmpegPromise: Promise<FFmpeg> | null = null;

/** Hard budget for ONE exec (a corrupt/adversarial input must never wedge the worker — or the element path). */
const FFMPEG_EXEC_BUDGET_MS = 120_000;

/** exec() with a wall-clock budget; on timeout the singleton is recycled (a stuck worker is useless). */
async function execWithBudget(ffmpeg: FFmpeg, args: string[]): Promise<number> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      ffmpeg.exec(args),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          ffmpegPromise = null; // recycle: this worker is stuck
          reject(new Error('ffmpeg exec budget exceeded — worker recycled'));
        }, FFMPEG_EXEC_BUDGET_MS);
      }),
    ]);
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/**
 * The lazy ffmpeg.wasm singleton (exported for diagnostics): the FFmpeg
 * class spawns its own dedicated Web Worker; the ~30 MB core wasm
 * blob-loads from unpkg on first need. Concurrent callers share one load;
 * a failed load (offline CDN, worker policy) clears for retry.
 */
export function ensureFfmpeg(): Promise<FFmpeg> {
  if (ffmpegPromise === null) {
    ffmpegPromise = (async () => {
      const ffmpeg = new FFmpeg();
      let timer: ReturnType<typeof setTimeout> | undefined;
      try {
        await Promise.race([
          ffmpeg.load({
            classWorkerURL: ffmpegWorkerUrl,
            // Blob-URLs so the worker's importScripts stays same-origin
            // (the documented 0.12 bundler pattern).
            coreURL: await toBlobURL(`${FFMPEG_CORE_BASE}/ffmpeg-core.js`, 'text/javascript'),
            wasmURL: await toBlobURL(`${FFMPEG_CORE_BASE}/ffmpeg-core.wasm`, 'application/wasm'),
          }),
          new Promise<never>((_, reject) => {
            timer = setTimeout(
              () => reject(new Error('ffmpeg.wasm load budget exceeded')),
              FFMPEG_LOAD_BUDGET_MS,
            );
          }),
        ]);
        return ffmpeg;
      } finally {
        if (timer !== undefined) clearTimeout(timer);
      }
    })();
    // A failed load (offline CDN, worker policy) must not poison retries.
    ffmpegPromise.catch(() => {
      ffmpegPromise = null;
    });
  }
  return ffmpegPromise;
}

// ---------------------------------------------------------------------------
// m3u8 parsing
// ---------------------------------------------------------------------------

/** Master playlist → the lowest-BANDWIDTH variant's absolute URL. */
function pickLowestBandwidthVariant(
  masterText: string,
  masterUrl: string,
): string | null {
  const lines = masterText.split(/\r?\n/);
  let best: { url: string; bandwidth: number } | null = null;
  for (let i = 0; i < lines.length; i += 1) {
    if (!lines[i].startsWith('#EXT-X-STREAM-INF')) continue;
    const match = /BANDWIDTH=(\d+)/.exec(lines[i]);
    const bandwidth = match !== null ? Number(match[1]) : Number.MAX_SAFE_INTEGER;
    // The variant URI is the next non-empty, non-comment line.
    for (let j = i + 1; j < lines.length; j += 1) {
      const line = lines[j].trim();
      if (line.length === 0 || line.startsWith('#')) continue;
      if (best === null || bandwidth < best.bandwidth) {
        best = { url: new URL(line, masterUrl).toString(), bandwidth };
      }
      break;
    }
  }
  return best !== null ? best.url : null;
}

interface MediaPlaylist {
  initUrl: string | null;
  segments: { url: string }[];
}

/** Media playlist → init segment URL (#EXT-X-MAP) + segment URLs. */
function parseMediaPlaylist(text: string, playlistUrl: string): MediaPlaylist {
  const lines = text.split(/\r?\n/);
  let initUrl: string | null = null;
  const segments: { url: string }[] = [];
  for (const raw of lines) {
    const line = raw.trim();
    if (line.length === 0) continue;
    if (line.startsWith('#')) {
      if (line.startsWith('#EXT-X-MAP')) {
        const uri = /URI="([^"]+)"/.exec(line);
        if (uri !== null) {
          initUrl = new URL(uri[1], playlistUrl).toString();
        }
      }
      continue; // byterange et al: YouTube VOD audio segments are whole files
    }
    segments.push({ url: new URL(line, playlistUrl).toString() });
  }
  return { initUrl, segments };
}

// ---------------------------------------------------------------------------
// Fetch helpers (relay-routed)
// ---------------------------------------------------------------------------

async function fetchText(
  url: string,
  relayBase: string,
  signal?: AbortSignal | null,
): Promise<string> {
  const res = await fetch(relayUrl(relayBase, url), {
    signal: signal ?? undefined,
  });
  if (!res.ok) throw new Error(`HLS fetch ${res.status} for ${url.slice(0, 64)}…`);
  return res.text();
}

async function fetchBytes(
  url: string,
  relayBase: string,
  signal?: AbortSignal | null,
): Promise<ArrayBuffer> {
  const res = await fetch(relayUrl(relayBase, url), {
    signal: signal ?? undefined,
  });
  if (!res.ok) throw new Error(`segment fetch ${res.status}`);
  return res.arrayBuffer();
}

// ---------------------------------------------------------------------------
// Byte helpers
// ---------------------------------------------------------------------------

/** fMP4 signature: a box type at the start — ftyp / styp / moov / moof. */
function isFragmentedMp4(bytes: Uint8Array): boolean {
  if (bytes.byteLength < 12) return false;
  const tag = String.fromCharCode(bytes[4], bytes[5], bytes[6], bytes[7]);
  return tag === 'ftyp' || tag === 'styp' || tag === 'moov' || tag === 'moof';
}

function concatBuffers(buffers: Uint8Array[]): Uint8Array {
  const total = buffers.reduce((n, b) => n + b.byteLength, 0);
  const out = new Uint8Array(total);
  let offset = 0;
  for (const b of buffers) {
    out.set(b, offset);
    offset += b.byteLength;
  }
  return out;
}
