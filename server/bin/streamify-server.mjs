#!/usr/bin/env node
import os from 'node:os';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// P1 Performance: Scale libuv threadpool to physical cores (min 8, max 12)
// Must be set before any asynchronous libuv operations occur.
if (!process.env.UV_THREADPOOL_SIZE) {
  const cpuCount = os.cpus()?.length || 4;
  process.env.UV_THREADPOOL_SIZE = String(Math.min(12, Math.max(8, cpuCount)));
}

import { createApp } from '../src/app.js';
import { resolveProfile } from '../src/platform/profile.js';
import { resolveDataDir } from '../src/platform/data-dir.js';
import { acquireWakeLock, releaseWakeLock } from '../src/platform/wake-lock.js';
import { CloudflareTunnel } from '../src/platform/cloudflare-tunnel.mjs';

const profile = resolveProfile();
const dataDir = resolveDataDir();
const host = process.env.STREAMIFY_HOST || '0.0.0.0';
const port = parseInt(process.env.STREAMIFY_PORT || process.env.PORT || '3000', 10);

const tunnelEnabled = Boolean(
  process.env.STREAMIFY_TUNNEL === '1' ||
  process.env.CLOUDFLARE_TUNNEL === '1' ||
  process.env.CLOUDFLARE_TUNNEL_TOKEN
);

let tunnel = null;
if (tunnelEnabled) {
  tunnel = new CloudflareTunnel({
    port,
    host: '127.0.0.1',
    token: process.env.CLOUDFLARE_TUNNEL_TOKEN,
    dataDir: dataDir.root,
  });
}

if (profile.isTermux) {
  acquireWakeLock();
}

const app = await createApp({
  profile,
  dataDir,
  tunnelManager: tunnel,
  logger: process.env.STREAMIFY_LOG === '1',
});

try {
  const address = await app.listen({ host, port });
  console.log(`Streamify Modular Fastify Server listening on ${address} [Profile: ${profile.name}]`);

  if (tunnel) {
    console.log('[streamify-server] Starting Cloudflare Tunnel...');
    tunnel.start().then((url) => {
      if (url) {
        console.log(`\n============================================================`);
        console.log(`Cloudflare Relay Active!`);
        console.log(`Global URL: ${url}`);
        console.log(`Connect in Streamify app: Settings -> Custom Relay Host`);
        console.log(`============================================================\n`);
      }
    }).catch((err) => {
      console.warn(`[streamify-server] Cloudflare Tunnel notice: ${err.message}`);
    });
  }

  // P0 Elite Delivery: Spawn non-blocking background bundle updater
  if (process.env.STREAMIFY_DISABLE_AUTO_UPDATE !== '1') {
    import('../scripts/update-bundle.mjs')
      .then(({ runBundleUpdate }) => {
        let delay = 3000;
        try {
          const binDir = path.dirname(fileURLToPath(import.meta.url));
          const publicLink = path.resolve(binDir, '../public');
          if (!fs.existsSync(publicLink) || !fs.lstatSync(publicLink).isSymbolicLink()) {
            delay = 0;
          } else {
            const target = fs.readlinkSync(publicLink);
            if (target.includes('placeholder')) delay = 0;
          }
        } catch (_) {
          delay = 0;
        }

        setTimeout(() => {
          runBundleUpdate().catch((err) => {
            console.warn(`[bundle-updater] Background check notice: ${err.message}`);
          });
        }, delay).unref();
      })
      .catch(() => {});
  }
} catch (err) {
  console.error('[streamify-server] Failed to start:', err);
  releaseWakeLock();
  process.exit(1);
}

// Suppress unhandled stream abort errors from abrupt client disconnects
process.on('uncaughtException', (err) => {
  if (err?.name === 'AbortError' || err?.code === 'ABORT_ERR' || err?.message?.includes('aborted')) {
    return;
  }
  console.error('[streamify-server] Uncaught Exception:', err);
  process.exit(1);
});

const shutdown = async (signal) => {
  console.log(`\nReceived ${signal}, shutting down gracefully...`);
  releaseWakeLock();
  if (tunnel) {
    await tunnel.stop();
  }
  await app.close();
  process.exit(0);
};

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
