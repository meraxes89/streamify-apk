import http from 'node:http';
import https from 'node:https';
import { Agent, Pool, setGlobalDispatcher, getGlobalDispatcher } from 'undici';
import CacheableLookup from 'cacheable-lookup';

/**
 * L3 Warm Upstream Connection Pool & DNS Cache Pinning (comm10.1.md Frontier 3 & comm13.md)
 * 
 * - Amortizes TCP 3-way handshake and TLS 1.3 key exchange across ranges/seeks/tracks
 * - Eliminates Node getaddrinfo libuv threadpool lookup bottleneck via CacheableLookup
 * - Pre-warmed explicit Pool for https://youtubei.googleapis.com
 * - Reuses persistent HTTP/1.1 keep-alive and HTTP/2 sessions to googlevideo edges
 */

let globalCacheableLookup = null;
let upstreamAgent = null;
let youtubeiPool = null;
let isConfigured = false;
let keepWarmTimer = null;

export function configureGlobalUpstreamPool({
  keepAliveTimeout = 60_000,
  keepAliveMaxTimeout = 600_000,
  connections = 8,
  pipelining = 1,
  allowH2 = true,
} = {}) {
  if (isConfigured && upstreamAgent) {
    return { agent: upstreamAgent, cacheableLookup: globalCacheableLookup, youtubeiPool };
  }

  try {
    globalCacheableLookup = new CacheableLookup({
      maxTtl: 300, // 5 min DNS cache
    });

    try {
      if (http.globalAgent) globalCacheableLookup.install(http.globalAgent);
      if (https.globalAgent) globalCacheableLookup.install(https.globalAgent);
    } catch {}

    upstreamAgent = new Agent({
      keepAliveTimeout,
      keepAliveMaxTimeout,
      connections,
      pipelining,
      allowH2,
      connect: {
        lookup: (hostname, opts, cb) => globalCacheableLookup.lookup(hostname, opts, cb),
      },
    });

    setGlobalDispatcher(upstreamAgent);

    // Dedicated pre-warmed pool for InnerTube API (comm13.md L3)
    youtubeiPool = new Pool('https://youtubei.googleapis.com', {
      connections: 4,
      pipelining: 1,
      keepAliveTimeout,
      keepAliveMaxTimeout,
      connect: {
        lookup: (hostname, opts, cb) => globalCacheableLookup.lookup(hostname, opts, cb),
      },
    });

    isConfigured = true;
  } catch (err) {
    console.warn('[upstream-pool] Failed to initialize global undici pool:', err.message);
  }

  return { agent: upstreamAgent, cacheableLookup: globalCacheableLookup, youtubeiPool };
}

export function getYoutubeiPool() {
  if (!youtubeiPool && !isConfigured) {
    configureGlobalUpstreamPool();
  }
  return youtubeiPool;
}

/**
 * Boot Pre-Warm (comm13.md L1)
 * Warms DNS lookup, TCP handshake, and TLS 1.3 session tickets to YouTube API and edge servers.
 */
export async function prewarmUpstreamTransport(sampleEdgeHost = null) {
  if (!isConfigured) {
    configureGlobalUpstreamPool();
  }

  try {
    // 1. Warm youtubei.googleapis.com
    if (globalCacheableLookup) {
      await new Promise((r) => globalCacheableLookup.lookup('youtubei.googleapis.com', {}, () => r()));
    }
    if (youtubeiPool) {
      // Lightweight HEAD/OPTIONS or visitor probe to warm TLS socket
      youtubeiPool.request({
        path: '/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8',
        method: 'HEAD',
        headers: { 'User-Agent': 'com.google.android.youtube/21.26.364' },
      }).then((res) => {
        res.body?.dump?.();
      }).catch(() => {});
    }

    // 2. Warm googlevideo edge if provided
    if (sampleEdgeHost && globalCacheableLookup) {
      await new Promise((r) => globalCacheableLookup.lookup(sampleEdgeHost, {}, () => r()));
    }

    // 3. Periodic keep-warm probe (every 45s) to prevent idle socket drop
    if (!keepWarmTimer) {
      keepWarmTimer = setInterval(() => {
        if (youtubeiPool) {
          youtubeiPool.request({
            path: '/youtubei/v1/visitor_id?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8',
            method: 'HEAD',
            headers: { 'User-Agent': 'com.google.android.youtube/21.26.364' },
          }).then((res) => res.body?.dump?.()).catch(() => {});
        }
      }, 45_000);
      keepWarmTimer.unref?.();
    }
  } catch {}
}

export function getUpstreamPoolStatus() {
  return {
    isConfigured,
    keepAliveTimeoutMs: 60_000,
    keepAliveMaxTimeoutMs: 600_000,
    maxConnectionsPerOrigin: 8,
    h2Allowed: true,
    dnsCacheActive: Boolean(globalCacheableLookup),
    youtubeiPoolReady: Boolean(youtubeiPool),
  };
}

export async function closeUpstreamPool() {
  if (keepWarmTimer) {
    clearInterval(keepWarmTimer);
    keepWarmTimer = null;
  }
  if (youtubeiPool) {
    try {
      await youtubeiPool.destroy();
    } catch {}
    youtubeiPool = null;
  }
  if (upstreamAgent) {
    try {
      await upstreamAgent.destroy();
    } catch {}
    upstreamAgent = null;
  }
  if (globalCacheableLookup) {
    try {
      if (http.globalAgent) globalCacheableLookup.uninstall(http.globalAgent);
      if (https.globalAgent) globalCacheableLookup.uninstall(https.globalAgent);
      globalCacheableLookup.clear();
    } catch {}
    globalCacheableLookup = null;
  }
  isConfigured = false;
}
