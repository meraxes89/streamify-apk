#!/usr/bin/env node
/**
 * Streamify Universal Platform Bootstrap (comm11.md Pillar C)
 * 
 * Single cross-platform entrypoint running across Android (Termux), desktop Linux,
 * macOS, and Windows. Automatically probes system hardware and cgroup memory limits,
 * sizes V8 heap flags (--max-semi-space-size and --max-old-space-size), and self-re-executes
 * before booting the media core.
 */

import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { getTierConfig } from '../platform/tier/index.js';

const cfg = getTierConfig();

if (!process.env.__STREAMIFY_BOOTED) {
  const v8Flags = `--max-semi-space-size=${cfg.v8.semiSpaceMb} --max-old-space-size=${cfg.v8.oldSpaceMb}`;
  const existingOptions = process.env.NODE_OPTIONS || '';
  const uvThreadPool = process.env.UV_THREADPOOL_SIZE || String(Math.min(12, Math.max(8, cfg.probe.parallelism)));

  const env = {
    ...process.env,
    __STREAMIFY_BOOTED: '1',
    STREAMIFY_TIER: cfg.tier,
    UV_THREADPOOL_SIZE: uvThreadPool,
    NODE_OPTIONS: `${existingOptions} ${v8Flags}`.trim()
  };

  const child = spawn(process.execPath, [fileURLToPath(import.meta.url), ...process.argv.slice(2)], {
    env,
    stdio: 'inherit'
  });

  child.on('exit', (code, signal) => {
    process.exit(code ?? (signal ? 1 : 0));
  });

  const forwardSignal = (sig) => {
    try {
      child.kill(sig);
    } catch {}
  };

  process.on('SIGINT', () => forwardSignal('SIGINT'));
  process.on('SIGTERM', () => forwardSignal('SIGTERM'));
  if (process.platform === 'win32') {
    process.on('SIGBREAK', () => forwardSignal('SIGBREAK'));
  }
} else {
  // Re-executed with optimal V8 flags; launch core server binary
  await import('../../bin/streamify-server.mjs');
}
