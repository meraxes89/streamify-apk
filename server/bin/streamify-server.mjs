#!/usr/bin/env node
import { createApp } from '../src/app.js';
import { resolveProfile } from '../src/platform/profile.js';
import { resolveDataDir } from '../src/platform/data-dir.js';
import { acquireWakeLock, releaseWakeLock } from '../src/platform/wake-lock.js';

const profile = resolveProfile();
const dataDir = resolveDataDir();
const host = process.env.STREAMIFY_HOST || '0.0.0.0';
const port = parseInt(process.env.STREAMIFY_PORT || process.env.PORT || '3000', 10);

if (profile.isTermux) {
  acquireWakeLock();
}

const app = await createApp({
  profile,
  dataDir,
  logger: process.env.STREAMIFY_LOG === '1',
});

try {
  const address = await app.listen({ host, port });
  console.log(`Streamify Modular Fastify Server listening on ${address} [Profile: ${profile.name}]`);
} catch (err) {
  console.error('[streamify-server] Failed to start:', err);
  releaseWakeLock();
  process.exit(1);
}

const shutdown = async (signal) => {
  console.log(`\nReceived ${signal}, shutting down gracefully...`);
  releaseWakeLock();
  await app.close();
  process.exit(0);
};

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
