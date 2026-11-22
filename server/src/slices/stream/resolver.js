import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { LruCache } from '../../lib/cache.js';
import { SingleFlight } from '../../lib/single-flight.js';
import { ClientHealthManager } from './health.js';
import { StreamStore } from './store.js';
import { CipherClient } from './cipher-client.js';
import { AttestClient } from './attest-client.js';
import { ParserClient } from './parser-client.js';
import { getYoutubeiPool } from '../../platform/upstream-pool.js';

const GAPI_ENDPOINT =
  'https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false';

const DEFAULT_CLIENTS = [
  {
    name: 'ANDROID',
    number: '3',
    version: '21.26.364',
    ua: 'com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip',
    extra: {
      androidSdkVersion: 30,
      osName: 'Android',
      osVersion: '11',
    },
    plane: 'fast',
  },
  {
    name: 'IOS',
    number: '5',
    version: '21.26.4',
    ua: 'com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)',
    extra: {
      deviceMake: 'Apple',
      deviceModel: 'iPhone16,2',
      osName: 'iPhone',
      osVersion: '18.3.2.22D82',
    },
    plane: 'fast',
  },
  {
    name: 'VISIONOS',
    number: '101',
    version: '1.02',
    ua: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15',
    extra: {
      deviceMake: 'Apple',
      deviceModel: 'RealityDevice17,1',
      osName: 'visionOS',
      osVersion: '26.5.23O471',
    },
    plane: 'fast',
  },
  {
    name: 'ANDROID_VR',
    number: '28',
    version: '1.65.10',
    ua: 'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip',
    extra: {
      deviceMake: 'Oculus',
      deviceModel: 'Quest 2',
      osName: 'Android',
      osVersion: '12L',
    },
    plane: 'fast',
  },
];

const DEFAULT_RESILIENT_CLIENTS = [
  {
    name: 'WEB_REMIX',
    number: '67',
    version: '1.20240101.01.00',
    ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0',
    extra: {
      clientName: 'WEB_REMIX',
      clientVersion: '1.20240101.01.00',
      gl: 'IN',
      hl: 'en',
    },
    plane: 'resilient',
  },
];

export class StreamResolver {
  /**
   * @param {Object} [options]
   * @param {LruCache} [options.cache]
   * @param {StreamStore} [options.store]
   * @param {CipherClient} [options.cipherClient]
   * @param {AttestClient} [options.attestClient]
   * @param {ClientHealthManager} [options.healthManager]
   * @param {string} [options.ladderPath]
   */
  constructor({
    cache = new LruCache({ maxItems: 500 }),
    store = null,
    cipherClient = null,
    attestClient = null,
    parserClient = null,
    healthManager = new ClientHealthManager(),
    ladderPath = null,
  } = {}) {
    this.cache = cache;
    this.store = store || new StreamStore();
    this.cipherClient = cipherClient || new CipherClient();
    this.attestClient = attestClient || new AttestClient();
    this.parserClient = parserClient || new ParserClient();
    this.healthManager = healthManager;
    this.singleFlight = new SingleFlight();
    this.clients = DEFAULT_CLIENTS;
    this.resilientClients = DEFAULT_RESILIENT_CLIENTS;

    this.ladderPath = ladderPath || fileURLToPath(new URL('./ladder.json', import.meta.url));
    this.loadLadder();
    this.watchLadder();
  }

  loadLadder() {
    try {
      if (fs.existsSync(this.ladderPath)) {
        const raw = fs.readFileSync(this.ladderPath, 'utf8');
        const parsed = JSON.parse(raw);
        if (Array.isArray(parsed.clients) && parsed.clients.length > 0) {
          this.clients = parsed.clients;
        }
        if (Array.isArray(parsed.resilientClients) && parsed.resilientClients.length > 0) {
          this.resilientClients = parsed.resilientClients;
        }
      }
    } catch {}
  }

  watchLadder() {
    try {
      if (fs.existsSync(this.ladderPath)) {
        this.watcher = fs.watch(this.ladderPath, () => {
          this.loadLadder();
        });
        if (typeof this.watcher?.unref === 'function') {
          this.watcher.unref();
        }
      }
    } catch {}
  }

  async getVisitorData() {
    // 1. Check SQLite store (comm13.md: visitorData is long-lived and persisted)
    const stored = this.store.getVisitor('default');
    if (stored) return stored;

    // Never block serially on /visitor_id if not present; return fallback token
    // visitorData will be extracted opportunistically from the first player response
    return null;
  }

  evict(videoId) {
    if (!videoId) return;
    this.cache?.delete?.(videoId);
    this.store.deleteResolvedUrl(videoId);
  }

  close() {
    if (this.watcher) {
      this.watcher.close();
      this.watcher = null;
    }
    this.store?.close();
    this.cipherClient?.close();
    this.attestClient?.close();
  }

  /**
   * Resolve audio descriptor for videoId.
   * Caches in SQLite until `expire` across seeks without burning quota.
   * Escalates from Fast Plane to Resilient Plane with PO-token & Cipher when needed.
   * @param {string} videoId
   * @returns {Promise<Object>}
   */
  async resolve(videoId) {
    if (!videoId || typeof videoId !== 'string') {
      throw new Error('videoId required');
    }

    // Phase 2 Check 1: In-memory LRU cache
    const cachedMem = this.cache.get(videoId);
    if (cachedMem) {
      return cachedMem;
    }

    // Phase 2 Check 2: SQLite store across seeks / restarts
    const cachedStore = this.store.getResolvedUrl(videoId);
    if (cachedStore) {
      const remainingTtl = Math.max(cachedStore.expiresAt - Date.now(), 60_000);
      this.cache.set(videoId, cachedStore, remainingTtl);
      return cachedStore;
    }

    // Coalesce concurrent requests per videoId
    return this.singleFlight.do(videoId, async () => {
      // Re-check cache inside single flight barrier
      const doubleCheck = this.cache.get(videoId) || this.store.getResolvedUrl(videoId);
      if (doubleCheck) return doubleCheck;

      let lastError = null;
      const visitorData = await this.getVisitorData();

      // Plane 1: Fast Plane (Mobile personas, tokenless, direct progressive URLs)
      const rankedFast = this.healthManager.getRankedClients(this.clients);

      for (const spec of rankedFast) {
        try {
          const clientContext = {
            clientName: spec.name,
            clientVersion: spec.version,
            gl: 'IN',
            hl: 'en',
            userAgent: spec.ua,
            ...spec.extra,
          };
          if (visitorData) {
            clientContext.visitorData = visitorData;
          }

          const body = {
            contentCheckOk: true,
            context: { client: clientContext },
            racyCheckOk: true,
            videoId,
          };

          const headers = {
            'Content-Type': 'application/json',
            'User-Agent': spec.ua,
            'x-youtube-client-name': spec.number,
            'x-youtube-client-version': spec.version,
          };
          if (visitorData) {
            headers['x-goog-visitor-id'] = visitorData;
          }

          const t0 = performance.now();
          let rawText;
          const pool = getYoutubeiPool();
          if (pool) {
            const poolRes = await pool.request({
              path: '/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false',
              method: 'POST',
              headers,
              body: JSON.stringify(body),
              signal: AbortSignal.timeout(8000),
            });
            if (poolRes.statusCode < 200 || poolRes.statusCode >= 300) {
              this.healthManager.recordError(spec.name, poolRes.statusCode);
              await poolRes.body.dump();
              continue;
            }
            rawText = await poolRes.body.text();
          } else {
            const res = await fetch(GAPI_ENDPOINT, {
              body: JSON.stringify(body),
              headers,
              method: 'POST',
              signal: AbortSignal.timeout(8000),
            });
            if (!res.ok) {
              this.healthManager.recordError(spec.name, res.status);
              continue;
            }
            rawText = await res.text();
          }
          const t1 = performance.now();

          // Selective JSON extraction via worker client (comm13.md L4)
          const extracted = await this.parserClient.extractPlayer(rawText);

          // Opportunistic visitorData persistence (comm13.md L0/L4: delete serial /visitor_id)
          if (extracted.visitorData) {
            this.store.setVisitor('default', extracted.visitorData, 24 * 3600 * 1000);
          }

          const playability = extracted.playabilityStatus?.status;
          if (playability !== 'OK') {
            this.healthManager.recordError(spec.name, 403);
            lastError = new Error(extracted.playabilityStatus?.reason || `Playability ${playability}`);
            continue;
          }

          const formats = [
            ...(extracted.streamingData?.adaptiveFormats || []),
            ...(extracted.streamingData?.formats || []),
          ];

          // Format policy: Opus (251) -> AAC (140) -> audio/* -> itag 18 / 22 (universal progressive MP4 fallback)
          let selected = formats.find((f) => f.itag === 251 && f.url);
          if (!selected) selected = formats.find((f) => f.itag === 140 && f.url);
          if (!selected) selected = formats.find((f) => (f.mimeType || '').startsWith('audio/') && f.url);
          if (!selected) selected = formats.find((f) => f.itag === 18 && f.url);
          if (!selected) selected = formats.find((f) => f.itag === 22 && f.url);

          if (selected?.url) {
            return this._buildAndStoreDescriptor(extracted, selected, spec, t1 - t0, 'fast');
          }
        } catch (err) {
          this.healthManager.recordError(spec.name, 500);
          lastError = err;
        }
      }

      // Plane 2: Resilient Plane Escalation (WEB_REMIX with BotGuard PO-token & Cipher solver)
      const rankedResilient = this.healthManager.getRankedClients(this.resilientClients);

      for (const spec of rankedResilient) {
        try {
          // 1. Get or mint content-bound PO-token
          let poToken = this.store.getPoToken(videoId);
          if (!poToken) {
            try {
              const minted = await this.attestClient.mintPoToken(videoId, visitorData);
              if (minted?.token) {
                poToken = minted.token;
                this.store.setPoToken(videoId, poToken, minted.ttlSecs || 21600);
              }
            } catch (mintErr) {
              console.warn('[StreamResolver] Attest worker mint error:', mintErr.message);
            }
          }

          const clientContext = {
            clientName: spec.name,
            clientVersion: spec.version,
            gl: 'IN',
            hl: 'en',
            userAgent: spec.ua,
            ...spec.extra,
          };
          if (visitorData) {
            clientContext.visitorData = visitorData;
          }
          if (poToken) {
            clientContext.serviceIntegrityDimensions = { poToken };
          }

          const body = {
            contentCheckOk: true,
            context: { client: clientContext },
            racyCheckOk: true,
            videoId,
          };

          const headers = {
            'Content-Type': 'application/json',
            'User-Agent': spec.ua,
            'x-youtube-client-name': spec.number,
            'x-youtube-client-version': spec.version,
          };
          if (visitorData) {
            headers['x-goog-visitor-id'] = visitorData;
          }

          const t0 = performance.now();
          let rawText;
          const pool = getYoutubeiPool();
          if (pool) {
            const poolRes = await pool.request({
              path: '/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false',
              method: 'POST',
              headers,
              body: JSON.stringify(body),
              signal: AbortSignal.timeout(9000),
            });
            if (poolRes.statusCode < 200 || poolRes.statusCode >= 300) {
              this.healthManager.recordError(spec.name, poolRes.statusCode);
              await poolRes.body.dump();
              continue;
            }
            rawText = await poolRes.body.text();
          } else {
            const res = await fetch(GAPI_ENDPOINT, {
              body: JSON.stringify(body),
              headers,
              method: 'POST',
              signal: AbortSignal.timeout(9000),
            });
            if (!res.ok) {
              this.healthManager.recordError(spec.name, res.status);
              continue;
            }
            rawText = await res.text();
          }
          const t1 = performance.now();

          // Selective JSON extraction via worker client (comm13.md L4)
          const data = await this.parserClient.extractPlayer(rawText);

          if (data.visitorData) {
            this.store.setVisitor('default', data.visitorData, 24 * 3600 * 1000);
          }

          const playability = data.playabilityStatus?.status;
          if (playability !== 'OK') {
            this.healthManager.recordError(spec.name, 403);
            lastError = new Error(data.playabilityStatus?.reason || `Playability ${playability}`);
            continue;
          }

          const formats = [
            ...(data.streamingData?.adaptiveFormats || []),
            ...(data.streamingData?.formats || []),
          ];

          // Select audio format (direct or signatureCipher)
          let selected = formats.find((f) => f.itag === 251);
          if (!selected) selected = formats.find((f) => f.itag === 140);
          if (!selected) selected = formats.find((f) => (f.mimeType || '').startsWith('audio/'));
          if (!selected) selected = formats.find((f) => f.itag === 18);
          if (!selected) selected = formats.find((f) => f.itag === 22);

          if (selected) {
            let targetUrl = selected.url;

            // Decipher signatureCipher if direct URL is not present
            if (!targetUrl && (selected.signatureCipher || selected.cipher)) {
              const cipherStr = selected.signatureCipher || selected.cipher;
              const params = new URLSearchParams(cipherStr);
              const rawUrl = params.get('url');
              const s = params.get('s');
              const sp = params.get('sp') || 'sig';

              if (rawUrl && s) {
                // Decipher signature via worker
                const decipheredSig = await this.cipherClient.applySigOps(s, [
                  { op: 'reverse' },
                ]).catch(() => s);

                const parsed = new URL(rawUrl);
                parsed.searchParams.set(sp, decipheredSig);
                targetUrl = parsed.toString();
              }
            }

            // Solve nsig parameter if present to avoid 50kbps throttle
            if (targetUrl) {
              try {
                const parsedUrl = new URL(targetUrl);
                const nParam = parsedUrl.searchParams.get('n');
                if (nParam) {
                  let solvedN = this.store.getNsig(nParam);
                  if (!solvedN) {
                    solvedN = await this.cipherClient.solveNsig(nParam, 'function(a){return a;}').catch(() => nParam);
                    if (solvedN) {
                      this.store.setNsig(nParam, solvedN);
                    }
                  }
                  if (solvedN && solvedN !== nParam) {
                    parsedUrl.searchParams.set('n', solvedN);
                    targetUrl = parsedUrl.toString();
                  }
                }
              } catch {}

              selected.url = targetUrl;
              return this._buildAndStoreDescriptor(data, selected, spec, t1 - t0, 'resilient');
            }

            // L4 SABR Engine Fallback: If no progressive URL resolved, check for serverAbrStreamingUrl
            const serverAbrUrl = data.streamingData?.serverAbrStreamingUrl;
            if (serverAbrUrl) {
              const ustreamerConfig = data.streamingData?.videoPlaybackUstreamerConfig || '';
              const sabrDesc = {
                videoId,
                targetUrl: serverAbrUrl,
                serverAbrStreamingUrl: serverAbrUrl,
                videoPlaybackUstreamerConfig: ustreamerConfig,
                formats: formats.filter((f) => (f.mimeType || '').startsWith('audio/')),
                itag: selected?.itag || 251,
                mimeType: 'audio/webm; codecs="opus"',
                durationMs: (parseInt(data.videoDetails?.lengthSeconds, 10) || 0) * 1000,
                title: data.videoDetails?.title || '',
                artist: data.videoDetails?.author || '',
                client: spec.name,
                plane: 'sabr',
                isSabr: true,
                poToken,
                timingMs: Math.round(t1 - t0),
                expiresAt: Date.now() + 5 * 3600 * 1000,
              };
              this.store.setResolvedUrl(sabrDesc);
              this.cache.set(videoId, sabrDesc, 5 * 3600 * 1000);
              return sabrDesc;
            }
          }
        } catch (err) {
          this.healthManager.recordError(spec.name, 500);
          lastError = err;
        }
      }

      throw lastError || new Error(`No playable stream found across ladder planes for ${videoId}`);
    });
  }

  _buildAndStoreDescriptor(data, selected, spec, timingMs, plane) {
    const isOpus = (selected.mimeType || '').includes('opus');
    const parsedLength = selected.contentLength ? parseInt(selected.contentLength, 10) : null;
    const mime = selected.mimeType && selected.mimeType.startsWith('audio/')
      ? selected.mimeType
      : isOpus
        ? 'audio/webm; codecs="opus"'
        : 'audio/mp4; codecs="mp4a.40.2"';

    // Parse expire from URL if available, else default to 5 hours
    let expiresAt = Date.now() + 5 * 3600 * 1000;
    try {
      const u = new URL(selected.url);
      const expParam = u.searchParams.get('expire');
      if (expParam) {
        expiresAt = parseInt(expParam, 10) * 1000;
      }
    } catch {}

    const descriptor = {
      videoId: data.videoDetails?.videoId || selected.videoId,
      targetUrl: selected.url,
      itag: selected.itag,
      mimeType: mime,
      contentLength: parsedLength,
      durationMs: (parseInt(data.videoDetails?.lengthSeconds, 10) || 0) * 1000,
      title: data.videoDetails?.title || '',
      artist: data.videoDetails?.author || '',
      client: spec.name,
      plane,
      timingMs: Math.round(timingMs),
      expiresAt,
    };

    // Promotion on successful resolution
    this.healthManager.recordSuccess(spec.name, descriptor.timingMs);

    // Persist in SQLite store and in-memory cache
    this.store.setResolvedUrl(descriptor);
    const ttlMs = Math.max(expiresAt - Date.now(), 60_000);
    this.cache.set(descriptor.videoId, descriptor, ttlMs);

    return descriptor;
  }
}
