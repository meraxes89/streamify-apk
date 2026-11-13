/**
 * lib/mediaBridge.ts — the client half of the W2.0 SW media bridge.
 *
 * Owns the Service-Worker session registry: same-origin `/swgv/<token>`
 * URLs that the SW serves through the relay with bounded ranges (see
 * public/sw.js for the full measured rationale). The element's src stays
 * STABLE across URL rotations — the SW swaps the upstream internally, so
 * a stale-URL recovery never reloads the element.
 *
 * Registration: production pages already carry the full SW (index.html);
 * dev pages register the SAME worker with `?media-only` (bridge on, app
 * shell off — Vite HMR must never see cached modules).
 */

export interface MediaBridgeHandle {
  /** The same-origin element URL for a (registered) upstream. */
  urlFor(token: string): string;
  /** Register/refresh the upstream for a token (rotation-safe). */
  register(token: string, upstreamUrl: string): Promise<void>;
  swap(token: string, upstreamUrl: string): Promise<void>;
  /** Whether the bridge is live (SW controlling + session-capable). */
  readonly live: boolean;
}

const MEDIA_PATH = '/streamify-apk/swgv/';
const REGISTRATION_TIMEOUT_MS = 4000;

let handlePromise: Promise<MediaBridgeHandle | null> | null = null;

class SwMediaBridge implements MediaBridgeHandle {
  constructor(private readonly relayBase: string) {}

  get live(): boolean {
    return navigator.serviceWorker.controller !== null;
  }

  urlFor(token: string): string {
    return `${MEDIA_PATH}${token}`;
  }

  async register(token: string, upstreamUrl: string): Promise<void> {
    await this.post({ type: 'relay-base', url: this.relayBase });
    await this.post({ type: 'media-register', token, url: upstreamUrl });
  }

  async swap(token: string, upstreamUrl: string): Promise<void> {
    await this.post({ type: 'media-swap', token, url: upstreamUrl });
  }

  private async post(message: unknown): Promise<void> {
    const controller = navigator.serviceWorker.controller;
    if (controller === null) return;
    controller.postMessage(message);
  }
}

async function waitController(timeoutMs: number): Promise<boolean> {
  if (navigator.serviceWorker.controller !== null) return true;
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (navigator.serviceWorker.controller !== null) return true;
    await new Promise((r) => setTimeout(r, 200));
  }
  return navigator.serviceWorker.controller !== null;
}

/**
 * Acquire the bridge (best-effort, memoized): if a Streamify SW already
 * controls the page use it; otherwise register the media-only variant.
 * Resolves null when service workers are unavailable (file:// contexts,
 * hardened browsers) — the player then runs direct-only.
 */
export function ensureMediaBridge(relayBase: string): Promise<MediaBridgeHandle | null> {
  if (handlePromise === null) {
    handlePromise = (async () => {
      if (!('serviceWorker' in navigator)) return null;
      try {
        if (navigator.serviceWorker.controller === null) {
          // Dev pages carry no SW; production pages register the full one
          // from index.html. The ?media-only variant exists so a dev page
          // can still exercise the bridge without the app-shell caching.
          await navigator.serviceWorker.register('./sw.js?media-only', {
            scope: './',
          });
          await navigator.serviceWorker.ready;
          await waitController(REGISTRATION_TIMEOUT_MS);
        }
        if (navigator.serviceWorker.controller === null) return null;
        // Ping (verify it is OUR worker — a foreign SW would 404 /swgv/).
        const pong = await new Promise<boolean>((resolve) => {
          const timer = setTimeout(() => resolve(false), 1500);
          navigator.serviceWorker.controller!.postMessage({ type: 'sw-ping' });
          navigator.serviceWorker.addEventListener('message', function h(e) {
            if (e.data !== null && typeof e.data === 'object' && e.data.type === 'sw-pong') {
              navigator.serviceWorker.removeEventListener('message', h);
              clearTimeout(timer);
              resolve(true);
            }
          });
        });
        return pong ? new SwMediaBridge(relayBase) : null;
      } catch {
        return null;
      }
    })();
    handlePromise.catch(() => {
      handlePromise = null;
    });
  }
  return handlePromise;
}
