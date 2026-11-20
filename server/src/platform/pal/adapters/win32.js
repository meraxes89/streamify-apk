/**
 * Streamify Platform Abstraction Layer (PAL) - Windows Adapter
 * 
 * Implements Windows primitives:
 * - Power: kernel32 SetThreadExecutionState(ES_SYSTEM_REQUIRED | ES_CONTINUOUS)
 * - Cache: Explicit no-op per comm11.md (OS standby list manages page eviction)
 * - Memory: GlobalMemoryStatusEx / node:os telemetry
 * - Network: Interface naming / IP helper classification
 */

import os from 'node:os';
import { symbols, safeCall } from '../ffi.js';
import { PRESSURE_LEVEL, NET_MEDIA } from '../types.js';

const ES_SYSTEM_REQUIRED = 0x00000001;
const ES_CONTINUOUS = 0x80000000;

export class Win32PowerAdapter {
  constructor() {
    this.winFfi = symbols.win32;
    this.hasSetExec = Boolean(this.winFfi && typeof this.winFfi.SetThreadExecutionState === 'function');
    this.cap = {
      available: this.hasSetExec,
      reason: this.hasSetExec ? 'kernel32 SetThreadExecutionState' : 'Win32 FFI unavailable'
    };
    this.activeCount = 0;
  }

  async inhibitSleep(_reason = 'Streaming playback') {
    if (this.hasSetExec) {
      this.activeCount++;
      if (this.activeCount === 1) {
        safeCall(this.winFfi.SetThreadExecutionState, [ES_CONTINUOUS | ES_SYSTEM_REQUIRED]);
      }
    }

    return () => {
      if (this.hasSetExec && this.activeCount > 0) {
        this.activeCount--;
        if (this.activeCount === 0) {
          // Reset to continuous without system required
          safeCall(this.winFfi.SetThreadExecutionState, [ES_CONTINUOUS]);
        }
      }
    };
  }
}

export class Win32CacheAdapter {
  constructor() {
    // Windows cache hygiene is an intentional no-op per comm11.md
    this.cap = {
      available: false,
      reason: 'Windows standby list managed by OS; explicit cache hygiene is no-op'
    };
  }

  advise(_fd, _off = 0, _len = 0, _advice) {}
  noCache(_fd) {}
  syncRange(_fd, _off = 0, _len = 0) {}
}

export class Win32MemoryAdapter {
  constructor() {
    this.cap = { available: true, reason: 'Windows memory telemetry' };
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
    return null;
  }
}

export class Win32NetworkAdapter {
  constructor() {
    this.cap = { available: true, reason: 'Windows network classification' };
  }

  async classify(iface) {
    if (!iface || iface.includes('Loopback') || iface === '127.0.0.1') {
      return { media: NET_MEDIA.LOOPBACK, mtu: 65536 };
    }
    const lower = iface.toLowerCase();
    if (lower.includes('wi-fi') || lower.includes('wireless') || lower.includes('wlan')) {
      return { media: NET_MEDIA.WIFI, mtu: 1500 };
    }
    if (lower.includes('cellular') || lower.includes('mobile')) {
      return { media: NET_MEDIA.CELLULAR, mtu: 1500 };
    }
    return { media: NET_MEDIA.WIRED, mtu: 1500, speedMbps: 1000 };
  }

  async defaultRoute() {
    return 'Ethernet';
  }
}

export class Win32LifecycleAdapter {
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
