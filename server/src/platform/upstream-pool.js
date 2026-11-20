import http from 'node:http';
import https from 'node:https';
import { Agent, setGlobalDispatcher, getGlobalDispatcher } from 'undici';
import CacheableLookup from 'cacheable-lookup';

/**
 * L3 Warm Upstream Connection Pool & DNS Cache Pinning (comm10.1.md Frontier 3)
 * 
 * - Amortizes TCP 3-way handshake and TLS 1.3 key exchange across ranges/seeks/tracks
 * - Eliminates Node getaddrinfo libuv threadpool lookup bottleneck via CacheableLookup
 * - Reuses persistent HTTP/1.1 keep-alive and HTTP/2 sessions to googlevideo edges
 */

let globalCacheableLookup = null;
let upstreamAgent = null;
let isConfigured = false;

export function configureGlobalUpstreamPool({
  keepAliveTimeout = 60_000,
  keepAliveMaxTimeout = 600_000,
  connections = 8,
  pipelining = 1,
  allowH2 = true,
} = {}) {
  if (isConfigured && upstreamAgent) {
    return { agent: upstreamAgent, cacheableLookup: globalCacheableLookup };
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
    isConfigured = true;
  } catch (err) {
    console.warn('[upstream-pool] Failed to initialize global undici pool:', err.message);
  }

  return { agent: upstreamAgent, cacheableLookup: globalCacheableLookup };
}

export function getUpstreamPoolStatus() {
  return {
    isConfigured,
    keepAliveTimeoutMs: 60_000,
    keepAliveMaxTimeoutMs: 600_000,
    maxConnectionsPerOrigin: 8,
    h2Allowed: true,
    dnsCacheActive: Boolean(globalCacheableLookup),
  };
}

export async function closeUpstreamPool() {
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
