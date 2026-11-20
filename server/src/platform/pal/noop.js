/**
 * Streamify Platform Abstraction Layer (PAL) - Default No-Op Adapters
 * 
 * Used when a platform lacks native support, FFI is unavailable, or a capability
 * is explicitly unsupported. Every method executes cleanly and returns safe defaults.
 */

import os from 'node:os';
import { PRESSURE_LEVEL, NET_MEDIA } from './types.js';

export class NoopPowerAdapter {
  constructor(reason = 'Power control unsupported on this platform') {
    this.cap = { available: false, reason };
  }

  /**
   * Inhibit sleep / acquire wake lock
   * @param {string} _reason
   * @returns {Promise<() => void>} Release function
   */
  async inhibitSleep(_reason) {
    return () => {};
  }
}

export class NoopCacheAdapter {
  constructor(reason = 'Kernel page cache control unsupported on this platform') {
    this.cap = { available: false, reason };
  }

  /**
   * Advise OS on file access pattern
   * @param {number} _fd
   * @param {bigint|number} _off
   * @param {bigint|number} _len
   * @param {'sequential'|'willneed'|'dontneed'} _advice
   */
  advise(_fd, _off, _len, _advice) {
    // No-op
  }

  /**
   * Bypass file cache
   * @param {number} _fd
   */
  noCache(_fd) {
    // No-op
  }

  /**
   * Flush file range to disk
   * @param {number} _fd
   * @param {bigint|number} _off
   * @param {bigint|number} _len
   */
  syncRange(_fd, _off, _len) {
    // No-op
  }
}

export class NoopMemoryAdapter {
  constructor(reason = 'Memory pressure telemetry unsupported on this platform') {
    this.cap = { available: false, reason };
    this.subscribers = new Set();
  }

  /**
   * Sample current memory pressure
   * @returns {import('./types.js').PressureSample}
   */
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

  /**
   * Subscribe to memory pressure events
   * @param {(sample: import('./types.js').PressureSample) => void} cb
   * @returns {() => void} Unsubscribe function
   */
  subscribe(cb) {
    if (typeof cb === 'function') {
      this.subscribers.add(cb);
    }
    return () => {
      this.subscribers.delete(cb);
    };
  }

  /**
   * Read OOM score adjustment
   * @returns {number|null}
   */
  oomScoreAdj() {
    return null;
  }
}

export class NoopNetworkAdapter {
  constructor(reason = 'Network media detection unsupported on this platform') {
    this.cap = { available: false, reason };
  }

  /**
   * Classify interface media type
   * @param {string} iface
   * @returns {Promise<import('./types.js').NetworkClassification>}
   */
  async classify(iface) {
    if (iface === 'lo' || iface === 'loopback' || iface === '127.0.0.1') {
      return { media: NET_MEDIA.LOOPBACK, mtu: 65536 };
    }
    return { media: NET_MEDIA.UNKNOWN, mtu: 1500 };
  }

  /**
   * Detect default routing interface
   * @returns {Promise<string>}
   */
  async defaultRoute() {
    return 'lo';
  }
}

export class NoopLifecycleAdapter {
  constructor() {
    this.cap = { available: true };
    this.shutdownHooks = [];
  }

  /**
   * Register graceful shutdown hook
   * @param {() => Promise<void>|void} cb
   */
  onShutdown(cb) {
    if (typeof cb === 'function') {
      this.shutdownHooks.push(cb);
    }
  }

  /**
   * Install as OS service
   * @param {object} _opts
   * @returns {Promise<void>}
   */
  async installService(_opts) {
    throw new Error('Service installation not supported in no-op adapter');
  }

  /**
   * Execute all registered shutdown hooks
   * @returns {Promise<void>}
   */
  async triggerShutdown() {
    for (const hook of this.shutdownHooks) {
      try {
        await hook();
      } catch {
        // Continue draining remaining hooks
      }
    }
  }
}
