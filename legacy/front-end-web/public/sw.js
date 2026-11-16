/**
 * sw.js — the Streamify service worker (W2.3 PWA shell + W2.0 media bridge).
 *
 * TWO JOBS, dependency-free (house style — no workbox):
 *
 * 1. THE MEDIA BRIDGE (W2.0 — the egress fallback lane)
 *    Same-origin `/swgv/<token>` URLs serve the <audio> element through a
 *    Service-Worker → relay → googlevideo chain with BOUNDED ranges:
 *
 *      element ── Range: bytes=X- (open) ──▶ SW
 *      SW ── Range: bytes=X-(X+512K-1) ──▶ RELAY /r?u=<googlevideo> (CORS-open)
 *      relay ── 206 + Content-Range ──▶ SW
 *      SW ── synthetic same-origin 206 ──▶ element
 *
 *    WHY THIS EXISTS (measured 2026-11-13, the W2.0 egress suite):
 *      • googlevideo REFUSES open-ended ranges (`bytes=X-`) — exactly what
 *        every media element sends — with 403 (datacenter IPs at least;
 *        the bisect: ≤1MB spans 206, ≥1.5MB/open 403). The BARE element
 *        therefore fails on datacenter egress; residential behavior is the
 *        runtime probe's job.
 *      • googlevideo's ACAO is origin-restricted to YouTube — readable
 *        fetch() from a foreign page is impossible, so the WASM spine
 *        cannot ride the user's IP either.
 *      • THE RELAY IS CORS-OPEN (ACAO:*) — the SW can READ relay responses
 *        (real status + Content-Range/Content-Type) and serve them to the
 *        element as perfectly normal same-origin 206s. Bounded spans are
 *        what googlevideo serves every IP class.
 *    The DIRECT lane (bare element → googlevideo, user's own IP, zero
 *    relay bytes) remains the default wherever it works — the bridge is
 *    the automatic fallback (element error → bridge reload) so playback
 *    is bulletproof on every network.
 *
 * 2. THE APP SHELL (W2.3) — precache + offline navigation + SWR assets.
 *    Registered as `sw.js` (full behavior) in production; in dev the app
 *    registers `sw.js?media-only` — the SAME worker, shell features off
 *    (Vite HMR must never see cached modules).
 *
 * Cross-origin media/relay/Supabase requests are NEVER touched (403-on-
 * stale googlevideo URLs is a feature — rotation depends on it).
 */

const VERSION = 'p2-w23-v2';
const SHELL_CACHE = `streamify-shell-${VERSION}`;
const ASSET_CACHE = `streamify-assets-${VERSION}`;
const MEDIA_PATH = '/streamify-apk/swgv/';
const MEDIA_CHUNK = 512 * 1024;

// `?media-only` → the dev registration: bridge on, shell off.
const MEDIA_ONLY = new URL(self.location.href).searchParams.has('media-only');

const SHELL_URLS = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon192.png',
  './icon512.png',
];

// ---------------------------------------------------------------------------
// The media bridge
// ---------------------------------------------------------------------------

/** token → upstream googlevideo URL (registered/swapped via postMessage). */
const mediaSessions = new Map();

self.addEventListener('message', (event) => {
  const data = event.data;
  if (data === null || typeof data !== 'object') return;
  switch (data.type) {
    case 'sw-ping':
      event.source?.postMessage({ type: 'sw-pong', version: VERSION, mediaOnly: MEDIA_ONLY });
      return;
    case 'media-register':
      if (typeof data.token === 'string' && typeof data.url === 'string') {
        mediaSessions.set(data.token, data.url);
        event.source?.postMessage({ type: 'media-registered', token: data.token });
      }
      return;
    case 'media-swap':
      if (typeof data.token === 'string' && typeof data.url === 'string') {
        mediaSessions.set(data.token, data.url);
        event.source?.postMessage({ type: 'media-swapped', token: data.token });
      }
      return;
    case 'media-unregister':
      if (typeof data.token === 'string') mediaSessions.delete(data.token);
      return;
    default:
      return;
  }
});

async function bridgeMedia(req, token) {
  const upstream = mediaSessions.get(token);
  if (upstream === undefined) {
    return new Response('unknown media token', { status: 404 });
  }
  // The element's Range is open-ended (`bytes=X-`); bound it. The relay
  // forwards the Range verbatim and googlevideo serves bounded spans to
  // every IP class (the measured rule the whole bridge rests on).
  let start = 0;
  const rangeHeader = req.headers.get('range');
  if (rangeHeader !== null) {
    const m = /^bytes=(\d+)-(\d*)$/.exec(rangeHeader.trim());
    if (m !== null) start = Number(m[1]);
  }
  const bounded = `bytes=${start}-${start + MEDIA_CHUNK - 1}`;
  try {
    const relayBase = (typeof dataRelayBase === 'string' && dataRelayBase !== '')
      ? dataRelayBase
      : 'https://streamify-relay.zephyr4289.workers.dev';
    const res = await fetch(`${relayBase}/r?u=${encodeURIComponent(upstream)}`, {
      headers: { Range: bounded },
      credentials: 'omit',
      cache: 'no-store',
    });
    if (res.status !== 206 && res.status !== 200) {
      return new Response(`upstream ${res.status}`, { status: 502 });
    }
    const headers = new Headers();
    for (const h of ['content-type', 'content-range', 'content-length', 'accept-ranges']) {
      const v = res.headers.get(h);
      if (v !== null) headers.set(h, v);
    }
    return new Response(res.body, { status: res.status, headers });
  } catch {
    return new Response('bridge fetch failed', { status: 502 });
  }
}

// The page tells the SW which relay to use (kept out of the URL/queries so
// ?relay= overrides survive); stored on the SW global via a message.
let dataRelayBase = '';
self.addEventListener('message', (event) => {
  const data = event.data;
  if (data !== null && typeof data === 'object' && data.type === 'relay-base' && typeof data.url === 'string') {
    dataRelayBase = data.url;
  }
});

// ---------------------------------------------------------------------------
// Fetch routing
// ---------------------------------------------------------------------------

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);

  // Same-origin only. Cross-origin (media direct, relay, Supabase) =
  // pass-through — NEVER cache googlevideo (URL expiry is load-bearing).
  if (url.origin !== self.location.origin) return;

  // ---- the media bridge (always on, dev + prod) --------------------------
  if (url.pathname.startsWith(MEDIA_PATH)) {
    const token = url.pathname.slice(MEDIA_PATH.length).split('/')[0] ?? '';
    event.respondWith(bridgeMedia(req, token));
    return;
  }

  if (MEDIA_ONLY) return; // dev registration: nothing else

  // ---- the app shell (production) -----------------------------------------
  if (req.mode === 'navigate') {
    event.respondWith(
      (async () => {
        try {
          return await fetch(req);
        } catch {
          const cache = await caches.open(SHELL_CACHE);
          return (
            (await cache.match('./index.html')) ?? (await cache.match('./')) ?? Response.error()
          );
        }
      })(),
    );
    return;
  }

  if (url.pathname.startsWith('/assets/') || url.pathname.includes('/streamify-apk/assets/')) {
    event.respondWith(
      (async () => {
        const cache = await caches.open(ASSET_CACHE);
        const cached = await cache.match(req);
        const network = fetch(req)
          .then((res) => {
            if (res.ok) void cache.put(req, res.clone());
            return res;
          })
          .catch(() => undefined);
        return cached ?? network ?? Response.error();
      })(),
    );
    return;
  }

  event.respondWith(
    (async () => {
      try {
        return await fetch(req);
      } catch {
        const cache = await caches.open(SHELL_CACHE);
        return (await cache.match(req)) ?? Response.error();
      }
    })(),
  );
});

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

self.addEventListener('install', (event) => {
  if (MEDIA_ONLY) {
    void self.skipWaiting();
    return;
  }
  event.waitUntil(
    (async () => {
      const cache = await caches.open(SHELL_CACHE);
      await Promise.allSettled(
        SHELL_URLS.map((u) => cache.add(new Request(u, { cache: 'reload' }))),
      );
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((n) => n.startsWith('streamify-') && n !== SHELL_CACHE && n !== ASSET_CACHE)
          .map((n) => caches.delete(n)),
      );
      await self.clients.claim();
    })(),
  );
});
