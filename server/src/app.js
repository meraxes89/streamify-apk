import Fastify from 'fastify';
import cors from '@fastify/cors';
import { Agent, setGlobalDispatcher } from 'undici';
import { monitorEventLoopDelay } from 'node:perf_hooks';
import { resolveProfile } from './platform/profile.js';
import { resolveDataDir } from './platform/data-dir.js';
import fastifyStatic from '@fastify/static';
import path from 'node:path';
import fs from 'node:fs';

// P0 Diagnostics: Event loop delay monitor with 10ms sampling resolution
const eventLoopLagMonitor = monitorEventLoopDelay({ resolution: 10 });
eventLoopLagMonitor.enable();

import dns from 'node:dns/promises';

// P1 Performance: In-memory TTL DNS cache to prevent getaddrinfo libuv threadpool contention
const dnsCache = new Map();
function cachedDnsLookup(hostname, options, callback) {
  const isAll = typeof options === 'object' && options?.all;
  const cached = dnsCache.get(hostname);
  if (cached && Date.now() < cached.expiresAt) {
    if (isAll) return callback(null, cached.records);
    return callback(null, cached.records[0].address, cached.records[0].family);
  }
  dns.lookup(hostname, { ...options, all: true })
    .then((records) => {
      dnsCache.set(hostname, {
        records,
        expiresAt: Date.now() + 120_000, // 2-minute TTL
      });
      if (isAll) {
        callback(null, records);
      } else {
        callback(null, records[0].address, records[0].family);
      }
    })
    .catch((err) => callback(err));
}

// P1 Performance: Undici Agent with non-blocking cached DNS, tuned Happy Eyeballs, and keep-alive pool
const undiciAgent = new Agent({
  connect: {
    lookup: cachedDnsLookup,
    autoSelectFamily: true,
    autoSelectFamilyAttemptTimeout: 50, // Cut IPv6 Happy Eyeballs fallback from 250ms default to 50ms
    keepAliveTimeout: 60_000,
  },
  pipelining: 1,
  maxRedirections: 3,
});
setGlobalDispatcher(undiciAgent);
import { CatalogService } from './slices/catalog/service.js';
import { catalogRoutes } from './slices/catalog/routes.js';
import { LyricsService } from './slices/lyrics/service.js';
import { lyricsRoutes } from './slices/lyrics/routes.js';
import { ThumbService } from './slices/thumb/service.js';
import { thumbRoutes } from './slices/thumb/routes.js';
import { StreamResolver } from './slices/stream/resolver.js';
import { SparseBlockCache } from './slices/stream/sparse-block-cache.js';
import { WriteThroughCache } from './slices/stream/write-through-cache.js';
import { StreamGateway } from './slices/stream/gateway.js';
import { streamRoutes } from './slices/stream/routes.js';
import { PrefetchCoordinator } from './slices/stream/prefetch.js';
import { StreamSemaphore } from './lib/semaphore.js';
import { authPlugin } from './slices/auth/routes.js';

export async function createApp({
  profile = resolveProfile(),
  dataDir = resolveDataDir(),
  catalogService = new CatalogService(),
  lyricsService = new LyricsService(),
  thumbService = new ThumbService(),
  streamResolver = new StreamResolver(),
  streamSemaphore = new StreamSemaphore({ maxGlobal: profile.maxStreams, maxPerIp: 4 }),
  writeThroughCache = new SparseBlockCache({ streamsDir: dataDir.streams, maxCacheMb: profile.diskCacheMb }),
  streamGateway = new StreamGateway({ resolver: streamResolver, cache: writeThroughCache, semaphore: streamSemaphore }),
  prefetchCoordinator = new PrefetchCoordinator({
    resolver: streamResolver,
    cache: writeThroughCache,
    liveSemaphore: streamSemaphore,
    eventLoopLagMonitor,
  }),
  logger = false,
} = {}) {
  const app = Fastify({
    logger,
    exposeHeadRoutes: false,
  });

  // CORS & Private Network Access
  await app.register(cors, {
    origin: '*',
    methods: ['GET', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'HEAD'],
    allowedHeaders: ['*'],
    exposedHeaders: ['Content-Range', 'Content-Length', 'Accept-Ranges'],
    preflightContinue: false,
    optionsSuccessStatus: 204,
  });

  // Add Private-Network-Access header for modern browser fetch
  app.addHook('onSend', async (request, reply) => {
    reply.header('Access-Control-Allow-Private-Network', 'true');
  });

  // Optional Room PIN Authentication
  await app.register(authPlugin);

  // Base Health & Capabilities
  app.get('/api/health', async () => {
    return {
      status: 'ok',
      version: '3.0.0',
      uptime: Math.floor(process.uptime()),
      profile: profile.name,
      memory: process.memoryUsage(),
      eventLoopLagMs: {
        min: eventLoopLagMonitor.min < 1e9 ? +(eventLoopLagMonitor.min / 1e6).toFixed(2) : 0,
        max: +(eventLoopLagMonitor.max / 1e6).toFixed(2),
        mean: Number.isFinite(eventLoopLagMonitor.mean) ? +(eventLoopLagMonitor.mean / 1e6).toFixed(2) : 0,
        p50: +(eventLoopLagMonitor.percentile(50) / 1e6).toFixed(2),
        p90: +(eventLoopLagMonitor.percentile(90) / 1e6).toFixed(2),
        p99: +(eventLoopLagMonitor.percentile(99) / 1e6).toFixed(2),
      },
      streams: {
        active: streamGateway.semaphore.globalCount,
        maxGlobal: streamGateway.semaphore.maxGlobal,
      },
      cache: {
        prefetched: prefetchCoordinator.prefetchedCount,
        resolved: streamResolver.cache.size,
      },
    };
  });

  app.get('/api/capabilities', async () => {
    return {
      name: 'streamify-relay',
      status: 'ok',
      version: '3.0.0',
      capabilities: [
        'modular-fastify',
        '8mib-range-stream',
        'mobile-vr-resolver',
        'two-stage-prefetch',
        'ytm-meta',
        'neg-cache',
        'slab-read',
      ],
    };
  });

  // Catalog vertical slice
  await app.register(catalogRoutes, { catalogService });

  // Lyrics vertical slice
  await app.register(lyricsRoutes, { lyricsService });

  // Thumb proxy vertical slice
  await app.register(thumbRoutes, { thumbService });

  // Stream gateway vertical slice
  await app.register(streamRoutes, { gateway: streamGateway, prefetch: prefetchCoordinator });

  // Static files for Compose Wasm UI & SPA fallback
  const publicDir = path.resolve('server/public');
  if (fs.existsSync(publicDir)) {
    await app.register(fastifyStatic, {
      root: publicDir,
      prefix: '/',
      preCompressed: true,
      setHeaders: (res, filePath) => {
        if (filePath.endsWith('index.html')) {
          res.header('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
          res.header('Pragma', 'no-cache');
          res.header('Expires', '0');
        } else if (filePath.endsWith('.wasm')) {
          res.header('Content-Type', 'application/wasm');
          res.header('Cache-Control', 'public, max-age=31536000, immutable');
        } else if (filePath.endsWith('.js')) {
          res.header('Content-Type', 'application/javascript');
          res.header('Cache-Control', 'public, max-age=31536000, immutable');
        }
      },
    });

    app.setNotFoundHandler(async (request, reply) => {
      if (request.url.startsWith('/api/')) {
        reply.status(404);
        return { error: 'Not Found' };
      }
      return reply.sendFile('index.html');
    });
  }

  return app;
}
