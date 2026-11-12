/**
 * Streamify Web — wasm engine bridge (W0.4).
 *
 * This module is the ONLY place that talks to the wasm facade
 * (crates/streamify-wasm, built by wasm-pack --target web). The rest of the
 * app consumes typed envelopes.
 *
 * W0.1 landmines honored here:
 *  - JSON-string bridge: the facade returns Strings containing JSON (never
 *    serde_wasm_bindgen Value/Map); each call parses exactly once.
 *  - The relay prefix is chosen for the deployment topology at runtime:
 *      * VITE_RELAY_BASE_URL set  → production worker (GitHub Pages build).
 *      * localhost                → local wrangler dev on :8787.
 *      * anything else            → sandbox preview gateway, same-origin
 *                                   /r path routed by ?XTransformPort=8787.
 */
import init, {
  get_stream,
  ping,
  search_music,
  set_relay,
} from "../../crates/streamify-wasm/pkg/streamify_wasm.js";
import type { InitInput } from "../../crates/streamify-wasm/pkg/streamify_wasm.js";
import wasmUrl from "../../crates/streamify-wasm/pkg/streamify_wasm_bg.wasm?url";

export interface Track {
  video_id: string;
  title: string;
  artist: string;
  album: string;
  duration_sec: number;
  thumbnail_url: string;
}

export type SearchEnvelope =
  | { ok: true; tracks: Track[] }
  | { ok: false; error: string };

export interface StreamInfo {
  url: string;
  mimeType: string;
  bitrate: number;
  durationSec: number;
  itag: number;
  loudnessDb: number | null;
}

export interface ClientAttempt {
  client: string;
  status: string;
  reason: string;
  ciphered?: number;
  directAudio?: number;
}

export type StreamEnvelope =
  | {
      ok: true;
      client: string;
      status: string;
      ciphered: number;
      stream: StreamInfo;
      attempts?: ClientAttempt[];
    }
  | { ok: false; error: string; attempts?: ClientAttempt[] };

/** Request prefix for one relay-routed call to `target`. */
export function relayPrefix(): string {
  const env =
    (import.meta.env.VITE_RELAY_BASE_URL as string | undefined) ?? "";
  if (env) {
    return env.replace(/\/+$/, "") + "/r";
  }
  const host = location.hostname;
  if (host === "localhost" || host === "127.0.0.1") {
    return "http://localhost:8787/r";
  }
  // Sandbox preview gateway: same-origin path, routed by the port param.
  return "/r?XTransformPort=8787";
}

/** URL of `target` fetched through the relay (audio src, range probes…). */
export function relayWrap(target: string): string {
  const prefix = relayPrefix();
  const sep = prefix.includes("?") ? "&" : "?";
  return `${prefix}${sep}u=${encodeURIComponent(target)}`;
}

let booted = false;

/** Instantiate the wasm module, wire the relay, return the handshake pong. */
export async function bootEngine(): Promise<string> {
  if (!booted) {
    // wasm-pack 0.13's generated .d.ts lags its own JS: the runtime takes the
    // single-object form and warns on the positional form. Cast across the
    // lag so the browser console stays clean for the no-CORS-errors AC.
    await init({ module_or_path: wasmUrl } as unknown as InitInput);
    set_relay(relayPrefix());
    booted = true;
  }
  return ping();
}

export async function doSearch(query: string): Promise<SearchEnvelope> {
  return JSON.parse(await search_music(query)) as SearchEnvelope;
}

export async function doGetStream(videoId: string): Promise<StreamEnvelope> {
  return JSON.parse(await get_stream(videoId)) as StreamEnvelope;
}

export function formatDuration(sec: number): string {
  if (!Number.isFinite(sec) || sec <= 0) return "–:––";
  const m = Math.floor(sec / 60);
  const s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
}
