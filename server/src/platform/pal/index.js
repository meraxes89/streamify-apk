/**
 * Streamify Platform Abstraction Layer (PAL) - Capability Registry & Adapter Dispatcher
 * 
 * Provides unified, OS-agnostic interfaces to the media core:
 * - pal.power: Sleep / wake-lock inhibition
 * - pal.cache: Kernel page-cache hygiene and readahead
 * - pal.memory: Memory pressure sampling and PSI telemetry
 * - pal.network: Network media classification (wired vs wifi vs cellular)
 * - pal.lifecycle: Graceful shutdown and process signal governance
 * 
 * Never throws exceptions into the core media pipe.
 */

import os from 'node:os';
import { ADVICE, PRESSURE_LEVEL, NET_MEDIA } from './types.js';
import {
  NoopPowerAdapter,
  NoopCacheAdapter,
  NoopMemoryAdapter,
  NoopNetworkAdapter,
  NoopLifecycleAdapter
} from './noop.js';
import {
  LinuxPowerAdapter,
  LinuxCacheAdapter,
  LinuxMemoryAdapter,
  LinuxNetworkAdapter,
  LinuxLifecycleAdapter
} from './adapters/linux.js';
import {
  DarwinPowerAdapter,
  DarwinCacheAdapter,
  DarwinMemoryAdapter,
  DarwinNetworkAdapter,
  DarwinLifecycleAdapter
} from './adapters/darwin.js';
import {
  Win32PowerAdapter,
  Win32CacheAdapter,
  Win32MemoryAdapter,
  Win32NetworkAdapter,
  Win32LifecycleAdapter
} from './adapters/win32.js';

export { ADVICE, PRESSURE_LEVEL, NET_MEDIA };

export class PlatformAbstractionLayer {
  /**
   * @param {object} [options]
   * @param {string} [options.platform=process.platform]
   * @param {object} [options.adapters]
   */
  constructor(options = {}) {
    const platform = options.platform || process.platform;
    const isTermux = Boolean(
      process.env.TERMUX_VERSION || (process.env.PREFIX && process.env.PREFIX.includes('com.termux'))
    );

    this.platform = platform;
    this.isTermux = isTermux;

    // Resolve adapters with safe fallbacks
    const isTargetLinuxOrAndroid = options.platform
      ? (options.platform === 'linux' || options.platform === 'android')
      : (platform === 'linux' || isTermux || platform === 'android');

    try {
      if (options.adapters) {
        this.power = options.adapters.power || new NoopPowerAdapter();
        this.cache = options.adapters.cache || new NoopCacheAdapter();
        this.memory = options.adapters.memory || new NoopMemoryAdapter();
        this.network = options.adapters.network || new NoopNetworkAdapter();
        this.lifecycle = options.adapters.lifecycle || new NoopLifecycleAdapter();
      } else if (isTargetLinuxOrAndroid) {
        this.power = new LinuxPowerAdapter();
        this.cache = new LinuxCacheAdapter();
        this.memory = new LinuxMemoryAdapter();
        this.network = new LinuxNetworkAdapter();
        this.lifecycle = new LinuxLifecycleAdapter();
      } else if (platform === 'darwin') {
        this.power = new DarwinPowerAdapter();
        this.cache = new DarwinCacheAdapter();
        this.memory = new DarwinMemoryAdapter();
        this.network = new DarwinNetworkAdapter();
        this.lifecycle = new DarwinLifecycleAdapter();
      } else if (platform === 'win32') {
        this.power = new Win32PowerAdapter();
        this.cache = new Win32CacheAdapter();
        this.memory = new Win32MemoryAdapter();
        this.network = new Win32NetworkAdapter();
        this.lifecycle = new Win32LifecycleAdapter();
      } else {
        this.power = new NoopPowerAdapter(`Platform ${platform} unsupported`);
        this.cache = new NoopCacheAdapter(`Platform ${platform} unsupported`);
        this.memory = new NoopMemoryAdapter(`Platform ${platform} unsupported`);
        this.network = new NoopNetworkAdapter(`Platform ${platform} unsupported`);
        this.lifecycle = new NoopLifecycleAdapter();
      }
    } catch {
      // Emergency no-op fallback
      this.power = new NoopPowerAdapter('Adapter initialization failed');
      this.cache = new NoopCacheAdapter('Adapter initialization failed');
      this.memory = new NoopMemoryAdapter('Adapter initialization failed');
      this.network = new NoopNetworkAdapter('Adapter initialization failed');
      this.lifecycle = new NoopLifecycleAdapter();
    }
  }

  /**
   * Diagnostic capability summary.
   */
  get capabilities() {
    return {
      platform: this.platform,
      isTermux: this.isTermux,
      power: this.power.cap,
      cache: this.cache.cap,
      memory: this.memory.cap,
      network: this.network.cap,
      lifecycle: this.lifecycle.cap
    };
  }
}

/**
 * Global default PAL instance
 */
export const pal = new PlatformAbstractionLayer();
