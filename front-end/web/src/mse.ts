/**
 * Streamify Web — MSE bridge player (W0.4).
 *
 * Why this exists (measured in-sandbox, see PR evidence):
 * googlevideo serves BOUNDED byte-range requests (206) to every IP class we
 * tested, but rejects open-ended/unranged GETs from datacenter IPs (403).
 * A plain <audio> element always issues open-ended ranges internally
 * ("bytes=0-…"), so it cannot survive on those IPs. MediaSource lets us feed
 * the element ONLY what we fetched through the relay with bounded ranges —
 * which also happens to be where the P1 engine queue lands anyway.
 *
 * No SharedArrayBuffer, no workers, single-threaded: fetch loop → append.
 */

import { relayWrap } from "./wasm";

const CHUNK_BYTES = 262_144; // 256 KiB bounded ranges (all proven 206)
const MAX_RETRIES_PER_CHUNK = 5;
// Measured in-sandbox: googlevideo (datacenter IP) throttles rapid bursts
// on one URL — 400 ms spacing still tripped at ~768 KiB, while ≥1 s spacing
// survived 6+ sequential requests. 256 KiB of opus ≈ 14 s of audio, so 1.1 s
// pacing prefetches ~13× realtime and keeps the CDN calm.
const INTER_CHUNK_DELAY_MS = 1100;
const RETRY_BASE_DELAY_MS = 1000;

export interface MseProgress {
  bytes: number;
  chunks: number;
  ended: boolean;
}

/**
 * Stream `target` (a direct googlevideo URL) through the relay into a
 * MediaSource attached to `audio`, fetching bounded ranges sequentially.
 * Resolves when the first chunk has been appended (playback can start);
 * `onProgress` keeps firing as more chunks land until end of stream.
 */
export async function playViaMse(
  audio: HTMLAudioElement,
  target: string,
  mimeType: string,
  onProgress?: (p: MseProgress) => void,
): Promise<void> {
  if (typeof MediaSource === "undefined") {
    throw new Error("MediaSource unsupported — cannot range-stream in this browser");
  }
  if (!MediaSource.isTypeSupported(mimeType)) {
    throw new Error(`MediaSource cannot handle mime "${mimeType}" in this browser`);
  }

  const mediaSource = new MediaSource();
  const objectUrl = URL.createObjectURL(mediaSource);
  const previousUrl = audio.currentSrc;
  if (previousUrl.startsWith("blob:")) {
    URL.revokeObjectURL(previousUrl);
  }
  audio.src = objectUrl;

  const opened = new Promise<void>((resolve, reject) => {
    mediaSource.addEventListener("sourceopen", () => {
      resolve();
    }, { once: true });
    mediaSource.addEventListener("error", () => {
      reject(new Error("MediaSource sourceopen error"));
    }, { once: true });
  });
  await opened;

  const sb = mediaSource.addSourceBuffer(mimeType);
  const updateEnd = () =>
    new Promise<void>((resolve) => {
      sb.addEventListener("updateend", () => {
        resolve();
      }, { once: true });
    });

  let offset = 0;
  let chunks = 0;
  let finished = false;

  // Start the fetch+append pump; the caller awaits play() below while the
  // pump continues in the background — bounded-range chunks only.
  const pump = (async () => {
    while (!finished) {
      const end = offset + CHUNK_BYTES - 1;
      let buf: ArrayBuffer | null = null;
      let lastStatus = 0;
      for (let attempt = 0; attempt < MAX_RETRIES_PER_CHUNK; attempt++) {
        try {
          const resp = await fetch(relayWrap(target), {
            headers: { Range: `bytes=${offset}-${end}` },
          });
          lastStatus = resp.status;
          if (resp.status === 206 || resp.status === 200) {
            buf = await resp.arrayBuffer();
            break;
          }
          // 416 = past end of file → treat as finished.
          if (resp.status === 416) {
            finished = true;
            break;
          }
        } catch {
          // network hiccup — retry
        }
        await new Promise((r) => {
          setTimeout(r, RETRY_BASE_DELAY_MS + attempt * 1000);
        });
      }
      if (finished) {
        break;
      }
      if (!buf || buf.byteLength === 0) {
        throw new Error(
          `range fetch failed at offset ${offset} after retries (last status ${lastStatus})`,
        );
      }
      // Never append while an update is in flight; wait for updateend after.
      if (sb.updating) {
        await updateEnd();
      }
      sb.appendBuffer(buf);
      await updateEnd();
      offset += buf.byteLength;
      chunks++;
      onProgress?.({ bytes: offset, chunks, ended: false });
      if (buf.byteLength < CHUNK_BYTES) {
        finished = true;
        break;
      }
      // Pacing (see constant comment): keep CDN request bursts low.
      await new Promise((r) => {
        setTimeout(r, INTER_CHUNK_DELAY_MS);
      });
    }
    if (mediaSource.readyState === "open") {
      try {
        mediaSource.endOfStream();
      } catch {
        // ended mid-append races are benign
      }
    }
    onProgress?.({ bytes: offset, chunks, ended: true });
  })();

  pump.catch((e: unknown) => {
    if (mediaSource.readyState === "open") {
      try {
        mediaSource.endOfStream();
      } catch {
        // ignore
      }
    }
    // Surface pump degradation as a warning — buffered playback continues;
    // P1's engine-side queue owns re-resolve/backoff strategy.
    console.warn("mse pump stalled (buffered playback continues):", e);
  });

  await audio.play();
}

/**
 * Preferred playback path: MSE with bounded ranges; falls back to a direct
 * relay src (plain element streaming) when the browser cannot handle the
 * mime — residential IPs where open-ended ranges pass will still work.
 */
export async function playWithFallback(
  audio: HTMLAudioElement,
  target: string,
  mimeType: string,
  onProgress?: (p: MseProgress) => void,
): Promise<"mse" | "element"> {
  try {
    await playViaMse(audio, target, mimeType, onProgress);
    return "mse";
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (
      msg.includes("MediaSource cannot handle") ||
      msg.includes("MediaSource unsupported")
    ) {
      // Deliberate fallback: element-native streaming (works where the CDN
      // allows open-ended ranges, e.g. residential IPs).
      audio.crossOrigin = "anonymous";
      audio.src = relayWrap(target);
      await audio.play();
      return "element";
    }
    throw e;
  }
}
