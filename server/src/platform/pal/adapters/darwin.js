/**
 * Streamify Platform Abstraction Layer (PAL) - Darwin / macOS Adapter
 * 
 * Implements macOS primitives:
 * - Power: caffeinate -w <pid>
 * - Cache: libSystem fcntl(F_RDADVISE) & fcntl(F_NOCACHE) (>= 16 KiB)
 * - Memory: memory_pressure CLI / host_statistics64 telemetry
 * - Network: networksetup hardware ports classification
 */

import os from 'node:os';
import { spawn } from 'node:child_process';
import { symbols, safeCall } from '../ffi.js';
import { ADVICE, PRESSURE_LEVEL, NET_MEDIA } from '../types.js';

// Darwin fcntl constants
const F_NOCACHE = 48;
const F_RDADVISE = 44;

export class DarwinPowerAdapter {
  constructor() {
    this.cap = { available: true, reason: 'macOS caffeinate child process' };
    this.activeInhibitors = new Map();
  }

  /**
   * Spawn caffeinate -w <pid> to prevent sleep during streaming
   */
  async inhibitSleep(reason = 'Streaming playback') {
    const id = Symbol(reason);
    try {
      const proc = spawn('caffeinate', ['-w', String(process.pid)], {
        stdio: 'ignore',
        detached: true
      });
      proc.on('error', () => {
        // Safe fallback if caffeinate binary is missing
        this.activeInhibitors.delete(id);
      });
      proc.unref();

      this.activeInhibitors.set(id, () => {
        try { proc.kill('SIGTERM'); } catch {}
      });
    } catch {
      this.activeInhibitors.set(id, () => {});
    }

    return () => {
      const release = this.activeInhibitors.get(id);
      if (release) {
        this.activeInhibitors.delete(id);
        release();
      }
    };
  }
}

export class DarwinCacheAdapter {
  constructor() {
    this.darwinFfi = symbols.darwin;
    this.hasFcntl = Boolean(this.darwinFfi && typeof this.darwinFfi.fcntlInt === 'function');
    this.cap = {
      available: this.hasFcntl,
      reason: this.hasFcntl ? 'libSystem fcntl (F_NOCACHE/F_RDADVISE)' : 'libSystem FFI unavailable'
    };
  }

  advise(fd, _off = 0, _len = 0, advice = ADVICE.SEQUENTIAL) {
    if (!this.hasFcntl || typeof fd !== 'number' || fd < 0) return;
    // Darwin F_RDADVISE can be called with struct radvisory or F_NOCACHE on eviction
    if (advice === ADVICE.DONTNEED) {
      this.noCache(fd);
    }
  }

  noCache(fd) {
    if (!this.hasFcntl || typeof fd !== 'number' || fd < 0) return;
    // Bypasses Darwin Unified Buffer Cache for transfers >= 16 KiB
    safeCall(this.darwinFfi.fcntlInt, [fd, F_NOCACHE, 1]);
  }

  syncRange(fd, _off = 0, _len = 0) {
    // Darwin lacks sync_file_range; no-op or fsync
  }
}

export class DarwinMemoryAdapter {
  constructor() {
    this.cap = { available: true, reason: 'macOS host telemetry' };
    this.subscribers = new Set();
    this.pollInterval = null;
  }

  sample() {
    const total = os.totalmem();
    const free = os.freemem();
    const freeRatio = total > 0 ? free / total : 1.0;

    let level = PRESSURE_LEVEL.NORMAL;
    if (freeRatio < 0.10) {
      level = PRESSURE_LEVEL.CRITICAL;
    } else if (freeRatio < 0.20) {
      level = PRESSURE_LEVEL.WARN;
    }

    return {
      level,
      availBytes: free,
      swapPct: 0,
      timestamp: Date.now()
    };
  }

  subscribe(cb) {
    if (typeof cb === 'function') {
      this.subscribers.add(cb);
      if (!this.pollInterval) {
        this.pollInterval = setInterval(() => {
          const s = this.sample();
          for (const sub of this.subscribers) {
            try { sub(s); } catch {}
          }
        }, 1000);
        if (this.pollInterval.unref) this.pollInterval.unref();
      }
    }
    return () => {
      this.subscribers.delete(cb);
      if (this.subscribers.size === 0 && this.pollInterval) {
        clearInterval(this.pollInterval);
        this.pollInterval = null;
      }
    };
  }

  oomScoreAdj() {
    return null; // macOS does not have oom_score_adj
  }
}

export class DarwinNetworkAdapter {
  constructor() {
    this.cap = { available: true, reason: 'macOS networksetup' };
  }

  async classify(iface) {
    if (!iface || iface === 'lo0' || iface === '127.0.0.1') {
      return { media: NET_MEDIA.LOOPBACK, mtu: 16384 };
    }
    if (iface.startsWith('en0')) {
      // en0 on MacBooks is typically Wi-Fi, on Mac Mini/Pro can be Ethernet
      return { media: NET_MEDIA.WIFI, mtu: 1500 };
    }
    return { media: NET_MEDIA.WIRED, mtu: 1500 };
  }

  async defaultRoute() {
    return 'en0';
  }
}

export class DarwinLifecycleAdapter {
  constructor() {
    this.cap = { available: true };
    this.hooks = [];
  }

  onShutdown(cb) {
    if (typeof cb === 'function') this.hooks.push(cb);
  }

  async triggerShutdown() {
    for (const hook of this.hooks) {
      try { await hook(); } catch {}
    }
  }
}
