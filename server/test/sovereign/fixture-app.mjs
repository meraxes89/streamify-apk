/**
 * Sovereign HAL Phase 3 (Step 2) — the REAL-TRACK Fastify /api/stream
 * fixture app.
 *
 * The E2E parity harness: a Fastify 5 app whose /api/stream/:videoId
 * routes ride the PRODUCTION StreamGateway stack (the same gateway,
 * semaphore, watchdog and sparse-block-cache classes `createApp` wires)
 * against a stubbed StreamResolver whose "googlevideo upstream" is a
 * local Node HTTP server serving the checked-in codec fixtures with
 * full HTTP 206 byte-range support.
 *
 * Two tracks, exactly as the production resolver would resolve them:
 *   * itag 251 — Opus 48 kHz: the WebM container (audio/webm;
 *     codecs="opus") — the #225 EBML/Ogg-Opus lane. A /raw sibling
 *     serves the raw #225 framing (OpusHead + UInt16BE length-prefixed
 *     packets) the Rust producer decodes directly.
 *   * itag 140 — AAC-LC 44.1 kHz: the M4A container (audio/mp4;
 *     codecs="mp4a.40.2").
 *
 * The fixture resolver implements the StreamResolver surface the
 * gateway consumes (resolve/evict/healthManager) — the same duck-typed
 * seam server/test suites already use.
 */
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import { EbmlOpusExtractor } from '../../src/slices/stream/ebml-opus-extractor.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const FIXTURE_DIR = path.resolve(__dirname, '../../../fixtures/audio');

export const TRACKS = {
  sov_opus_251: {
    videoId: 'sov_opus_251',
    file: 'opus_dual_tone_48k.webm',
    itag: 251,
    mimeType: 'audio/webm; codecs="opus"',
  },
  sov_aac_140: {
    videoId: 'sov_aac_140',
    file: 'aac_dual_tone_44k1.m4a',
    itag: 140,
    mimeType: 'audio/mp4; codecs="mp4a.40.2"',
  },
};

/** The raw #225 framing of the Opus fixture (built from the WebM). */
export function rawOpusFraming() {
  const webm = fs.readFileSync(path.join(FIXTURE_DIR, TRACKS.sov_opus_251.file));
  const { opusHead, frames } = EbmlOpusExtractor.extractFromBuffer(webm);
  if (!opusHead || frames.length === 0) throw new Error('fixture webm has no opus frames');
  const parts = [Buffer.from(opusHead)];
  for (const f of frames) {
    const len = Buffer.alloc(2);
    len.writeUInt16BE(f.frame.length);
    parts.push(len, Buffer.from(f.frame));
  }
  return Buffer.concat(parts);
}

/**
 * The stubbed googlevideo upstream: a bare Node HTTP server with REAL
 * Range support (206 + Content-Range), byte-identical to the header
 * grammar the production gateway's sparse-block cache parses.
 */
export async function startUpstream() {
  const files = new Map(); // urlPath -> { bytes, mime }
  for (const [key, t] of Object.entries(TRACKS)) {
    const bytes = fs.readFileSync(path.join(FIXTURE_DIR, t.file));
    files.set(`/videoplayback/${key}`, { bytes, mime: t.mimeType });
  }
  // The raw #225 lane: /videoplayback/sov_opus_251/raw
  files.set('/videoplayback/sov_opus_251/raw', {
    bytes: rawOpusFraming(),
    mime: 'application/octet-stream',
  });

  const server = http.createServer((req, res) => {
    const entry = files.get(new URL(req.url, 'http://x').pathname);
    if (!entry) {
      res.writeHead(404).end('nf');
      return;
    }
    const total = entry.bytes.length;
    const rangeHeader = req.headers.range;
    let start = 0;
    let end = total - 1;
    let status = 200;
    if (rangeHeader) {
      const m = /bytes=(\d*)-(\d*)/.exec(rangeHeader);
      if (m) {
        if (m[1] === '') {
          // suffix range: last N bytes
          start = Math.max(0, total - parseInt(m[2] || '1', 10));
        } else {
          start = parseInt(m[1], 10);
        }
        if (m[2] !== '' && m[2] !== undefined) end = Math.min(parseInt(m[2], 10), total - 1);
        if (start > end || start >= total) {
          res.writeHead(416, { 'Content-Range': `bytes */${total}` }).end();
          return;
        }
        status = 206;
      }
    }
    const chunk = entry.bytes.subarray(start, end + 1);
    res.writeHead(status, {
      'Content-Type': entry.mime,
      'Content-Length': chunk.length,
      'Accept-Ranges': 'bytes',
      ...(status === 206 ? { 'Content-Range': `bytes ${start}-${end}/${total}` } : {}),
    });
    res.end(req.method === 'HEAD' ? undefined : chunk);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const urlFor = (key, raw = false) =>
    `http://127.0.0.1:${port}/videoplayback/${key}${raw ? '/raw' : ''}`;
  return { server, port, urlFor, files };
}

/**
 * A StreamResolver stand-in over the fixture upstream. Implements the
 * gateway's consumed surface: resolve(videoId) -> descriptor (the same
 * shape _buildAndStoreDescriptor produces) with cache/evict.
 *
 * The targetUrl uses a *.googlevideo.com hostname so the gateway's
 * upstream-domain verification passes; the fetch rewrite installed by
 * createSovereignFixtureApp routes that host to the local fixture
 * server (production server code stays untouched).
 */
export class FixtureResolver {
  constructor(upstream) {
    this.upstream = upstream;
    this.cache = new Map();
    /** When true, itag 251 resolves to the raw #225 framing lane. */
    this.rawMode = false;
    /** Scripted next-resolve failure (the degradation drills). */
    this.failNext = false;
    this.evict = (videoId) => this.cache.delete(videoId);
    // The watchdog wiring reads healthManager (defensive optional use).
    this.healthManager = { recordError() {}, recordSuccess() {}, getTelemetry: () => ({}) };
  }

  async resolve(videoId) {
    if (this.failNext) {
      this.failNext = false;
      throw new Error('fixture resolver: scripted resolve failure');
    }
    const cached = this.cache.get(videoId);
    if (cached) return cached;
    const track = Object.values(TRACKS).find((t) => t.videoId === videoId);
    if (!track) throw new Error(`fixture resolver: unknown videoId ${videoId}`);
    const raw = videoId === TRACKS.sov_opus_251.videoId && this.rawMode;
    const bytes = raw
      ? this.upstream.files.get('/videoplayback/sov_opus_251/raw').bytes
      : this.upstream.files.get(`/videoplayback/${videoId}`).bytes;
    const descriptor = {
      videoId,
      targetUrl: `http://fixture-upstream.googlevideo.com/videoplayback/${videoId}${raw ? '/raw' : ''}`,
      itag: track.itag,
      mimeType: track.mimeType,
      contentLength: bytes.length,
      durationMs: 3000,
      title: `Sovereign fixture ${track.itag}`,
      artist: 'Phase 3 E2E',
      client: 'FIXTURE',
      plane: 'fixture',
      timingMs: 0,
      expiresAt: Date.now() + 3600_000,
    };
    this.cache.set(videoId, descriptor);
    return descriptor;
  }
}

/**
 * Route *.googlevideo.com URLs in THIS PROCESS's global fetch to the
 * local fixture upstream (the gateway + sparse-block cache call the
 * bare `fetch`). Non-googlevideo URLs pass through untouched.
 */
export function installFetchRewrite(upstream) {
  const realFetch = globalThis.fetch.bind(globalThis);
  globalThis.fetch = (input, init) => {
    const url = typeof input === 'string' ? input : input?.url || String(input);
    if (typeof url === 'string' && url.includes('.googlevideo.com')) {
      const rewritten = url.replace(
        /^https?:\/\/[^/]+/,
        `http://127.0.0.1:${upstream.port}`
      );
      return realFetch(rewritten, init);
    }
    return realFetch(input, init);
  };
  return () => {
    globalThis.fetch = realFetch;
  };
}

/**
 * Build the E2E Fastify app: the REAL gateway stack (the same classes
 * src/app.js wires: StreamGateway + StreamSemaphore + StreamWatchdog +
 * SparseBlockCache-backed cache) over the fixture resolver, exposing
 * exactly the production /api/stream routes from slices/stream/routes.js.
 */
export async function createSovereignFixtureApp({
  streamsDir = fs.mkdtempSync(path.join('/tmp', 'sov-e2e-')),
  rawOpus = false,
  pacer = null,
} = {}) {
  const upstream = await startUpstream();
  const restoreFetch = installFetchRewrite(upstream);
  const resolver = new FixtureResolver(upstream);
  resolver.rawMode = rawOpus;

  const { StreamGateway } = await import('../../src/slices/stream/gateway.js');
  const { streamRoutes } = await import('../../src/slices/stream/routes.js');
  const { StreamSemaphore } = await import('../../src/lib/semaphore.js');
  const { SparseBlockCache } = await import('../../src/slices/stream/sparse-block-cache.js');
  const Fastify = (await import('fastify')).default;

  const cache = new SparseBlockCache({ streamsDir, maxCacheMb: 256 });
  const gateway = new StreamGateway({
    resolver,
    cache,
    semaphore: new StreamSemaphore({ maxGlobal: 8, maxPerIp: 4 }),
    ...(pacer !== null ? { pacer } : {}),
  });

  const app = Fastify({ logger: false });
  await app.register(streamRoutes, { gateway, prefetch: null });

  app.get('/api/sovereign/fixture-info', async () => ({
    tracks: Object.fromEntries(
      Object.entries(TRACKS).map(([k, t]) => [
        k,
        {
          videoId: t.videoId,
          itag: t.itag,
          mimeType: t.mimeType,
          sha256: createHash('sha256')
            .update(fs.readFileSync(path.join(FIXTURE_DIR, t.file)))
            .digest('hex'),
        },
      ])
    ),
    upstream: { port: upstream.port },
  }));

  await app.listen({ port: 0, host: '127.0.0.1' });
  const close = async () => {
    await app.close();
    await new Promise((r) => upstream.server.close(r));
    await cache.close?.();
    restoreFetch();
    try { fs.rmSync(streamsDir, { recursive: true, force: true }); } catch {}
  };
  return {
    app,
    gateway,
    upstream,
    resolver,
    close,
    baseUrl: `http://127.0.0.1:${app.server.address().port}`,
  };
}
