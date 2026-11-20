/**
 * Streamify Platform Abstraction Layer (PAL) - Linux & Android/Termux Adapter
 * 
 * Implements OS primitives via FFI (libc posix_fadvise, sync_file_range)
 * and Linux kernel interfaces (/proc/pressure/memory, /proc/self/oom_score_adj, /sys/class/net).
 * 
 * Never throws exceptions into the caller.
 */

import fs from 'node:fs';
import os from 'node:os';
import { spawn } from 'node:child_process';
import { symbols, safeCall, safeCallAsync } from '../ffi.js';
import { ADVICE, PRESSURE_LEVEL, NET_MEDIA } from '../types.js';

// Advice mapping to Linux posix_fadvise constants
const LINUX_ADVICE = {
  [ADVICE.SEQUENTIAL]: 2, // POSIX_FADV_SEQUENTIAL
  [ADVICE.WILLNEED]: 3,   // POSIX_FADV_WILLNEED
  [ADVICE.DONTNEED]: 4    // POSIX_FADV_DONTNEED
};

export class LinuxPowerAdapter {
  constructor() {
    this.isTermux = Boolean(
      process.env.TERMUX_VERSION || (process.env.PREFIX && process.env.PREFIX.includes('com.termux'))
    );
    this.cap = {
      available: true,
      reason: this.isTermux ? 'Android Termux wake-lock' : 'Linux power inhibitor'
    };
    this.activeInhibitors = new Map();
  }

  /**
   * Inhibit sleep during active streaming playback.
   * @param {string} reason
   * @returns {Promise<() => void>}
   */
  async inhibitSleep(reason = 'Streaming playback') {
    const id = Symbol(reason);

    if (this.isTermux) {
      try {
        const proc = spawn('termux-wake-lock', [], { stdio: 'ignore', detached: true });
        proc.unref();
        this.activeInhibitors.set(id, () => {
          try {
            const releaseProc = spawn('termux-wake-unlock', [], { stdio: 'ignore', detached: true });
            releaseProc.unref();
          } catch {
            // Ignore unlock errors on shutdown
          }
        });
      } catch {
        // Fallback no-op
        this.activeInhibitors.set(id, () => {});
      }
    } else {
      // Desktop Linux: Attempt systemd-inhibit if available
      try {
        const proc = spawn('systemd-inhibit', [
          '--what=idle:sleep',
          '--who=Streamify',
          `--why=${reason}`,
          '--mode=block',
          'sleep', 'infinity'
        ], { stdio: 'ignore', detached: true });
        proc.unref();

        this.activeInhibitors.set(id, () => {
          try {
            proc.kill('SIGTERM');
          } catch {
            // Process might have terminated
          }
        });
      } catch {
        this.activeInhibitors.set(id, () => {});
      }
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

export class LinuxCacheAdapter {
  constructor() {
    this.linuxFfi = symbols.linux;
    this.hasFadvise = Boolean(this.linuxFfi && typeof this.linuxFfi.posix_fadvise === 'function');
    this.hasSyncRange = Boolean(this.linuxFfi && typeof this.linuxFfi.sync_file_range === 'function');

    this.cap = {
      available: this.hasFadvise,
      reason: this.hasFadvise ? 'libc posix_fadvise via koffi' : 'libc FFI unavailable; safe no-op'
    };
  }

  /**
   * Advise kernel on file access patterns.
   * @param {number} fd
   * @param {bigint|number} off
   * @param {bigint|number} len
   * @param {'sequential'|'willneed'|'dontneed'} advice
   */
  advise(fd, off = 0, len = 0, advice = ADVICE.SEQUENTIAL) {
    if (!this.hasFadvise || typeof fd !== 'number' || fd < 0) return;
    const adviceCode = LINUX_ADVICE[advice] ?? 0;
    safeCall(this.linuxFfi.posix_fadvise, [fd, BigInt(off), BigInt(len), adviceCode]);
  }

  /**
   * macOS-specific bypass (no-op on Linux).
   */
  noCache(_fd) {
    // No-op on Linux
  }

  /**
   * Initiate asynchronous or synchronous writeback of dirty pages without waiting for completion.
   * SYNC_FILE_RANGE_WRITE = 2
   */
  syncRange(fd, off = 0, len = 0) {
    if (!this.hasSyncRange || typeof fd !== 'number' || fd < 0) {
      // Fallback: synchronous fdatasync if range sync unsupported
      try {
        fs.fdatasyncSync(fd);
      } catch {}
      return;
    }

    const SYNC_FILE_RANGE_WRITE = 2;
    if (this.linuxFfi.sync_file_range_async) {
      safeCallAsync(this.linuxFfi.sync_file_range_async, [fd, BigInt(off), BigInt(len), SYNC_FILE_RANGE_WRITE]);
    } else {
      safeCall(this.linuxFfi.sync_file_range, [fd, BigInt(off), BigInt(len), SYNC_FILE_RANGE_WRITE]);
    }
  }
}

export class LinuxMemoryAdapter {
  constructor() {
    this.psiPath = '/proc/pressure/memory';
    this.oomScorePath = '/proc/self/oom_score_adj';
    this.hasPsi = fs.existsSync(this.psiPath);
    this.subscribers = new Set();
    this.pollInterval = null;

    this.cap = {
      available: true,
      reason: this.hasPsi ? 'Linux PSI + /proc telemetry' : '/proc telemetry (PSI unavailable)'
    };
  }

  /**
   * Read PSI memory pressure metrics.
   * @returns {{ someAvg10: number, fullAvg10: number } | null}
   */
  readPsi() {
    if (!this.hasPsi) return null;
    try {
      const content = fs.readFileSync(this.psiPath, 'utf8');
      const someMatch = content.match(/some\s+avg10=([\d.]+)/);
      const fullMatch = content.match(/full\s+avg10=([\d.]+)/);
      return {
        someAvg10: someMatch ? parseFloat(someMatch[1]) : 0.0,
        fullAvg10: fullMatch ? parseFloat(fullMatch[1]) : 0.0
      };
    } catch {
      return null;
    }
  }

  /**
   * Sample current memory pressure.
   * @returns {import('../types.js').PressureSample}
   */
  sample() {
    const psi = this.readPsi();
    const total = os.totalmem();
    const free = os.freemem();
    const freeRatio = total > 0 ? free / total : 1.0;

    let level = PRESSURE_LEVEL.NORMAL;
    const psiSome = psi ? psi.someAvg10 : 0.0;

    if (psiSome >= 25.0 || freeRatio < 0.10) {
      level = PRESSURE_LEVEL.CRITICAL;
    } else if (psiSome >= 10.0 || freeRatio < 0.20) {
      level = PRESSURE_LEVEL.WARN;
    }

    return {
      level,
      availBytes: free,
      swapPct: 0,
      psiSomeAvg10: psiSome,
      timestamp: Date.now()
    };
  }

  /**
   * Read OOM score adjustment (READ-ONLY).
   * @returns {number|null}
   */
  oomScoreAdj() {
    try {
      if (fs.existsSync(this.oomScorePath)) {
        const val = parseInt(fs.readFileSync(this.oomScorePath, 'utf8').trim(), 10);
        return Number.isFinite(val) ? val : null;
      }
    } catch {}
    return null;
  }

  /**
   * Subscribe to memory pressure samples (polled at 1 Hz).
   * @param {(sample: import('../types.js').PressureSample) => void} cb
   * @returns {() => void}
   */
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
}

export class LinuxNetworkAdapter {
  constructor() {
    this.cap = { available: true, reason: 'Linux sysfs net classifier' };
  }

  /**
   * Classify interface media type using Linux sysfs and naming conventions.
   * @param {string} iface
   * @returns {Promise<import('../types.js').NetworkClassification>}
   */
  async classify(iface) {
    if (!iface || iface === 'lo' || iface === '127.0.0.1') {
      return { media: NET_MEDIA.LOOPBACK, mtu: 65536 };
    }

    const sysNet = `/sys/class/net/${iface}`;
    let isWireless = false;
    let speedMbps = undefined;
    let mtu = 1500;

    try {
      if (fs.existsSync(`${sysNet}/wireless`)) {
        isWireless = true;
      }
      if (fs.existsSync(`${sysNet}/mtu`)) {
        mtu = parseInt(fs.readFileSync(`${sysNet}/mtu`, 'utf8').trim(), 10) || 1500;
      }
      if (fs.existsSync(`${sysNet}/speed`)) {
        const s = parseInt(fs.readFileSync(`${sysNet}/speed`, 'utf8').trim(), 10);
        if (Number.isFinite(s) && s > 0) speedMbps = s;
      }
    } catch {}

    if (isWireless || iface.startsWith('wlan') || iface.startsWith('wl')) {
      return { media: NET_MEDIA.WIFI, mtu, speedMbps };
    }

    if (iface.startsWith('rmnet') || iface.startsWith('wwan') || iface.startsWith('ccmni')) {
      return { media: NET_MEDIA.CELLULAR, mtu, speedMbps };
    }

    if (iface.startsWith('eth') || iface.startsWith('en')) {
      return { media: NET_MEDIA.WIRED, mtu, speedMbps: speedMbps || 1000 };
    }

    return { media: NET_MEDIA.UNKNOWN, mtu, speedMbps };
  }

  /**
   * Determine default routing interface from /proc/net/route
   * @returns {Promise<string>}
   */
  async defaultRoute() {
    try {
      if (fs.existsSync('/proc/net/route')) {
        const lines = fs.readFileSync('/proc/net/route', 'utf8').split('\n');
        for (let i = 1; i < lines.length; i++) {
          const parts = lines[i].trim().split(/\s+/);
          if (parts.length >= 2 && parts[1] === '00000000') {
            return parts[0];
          }
        }
      }
    } catch {}
    return 'wlan0';
  }
}

export class LinuxLifecycleAdapter {
  constructor() {
    this.cap = { available: true };
    this.hooks = [];
    this.isShuttingDown = false;
  }

  onShutdown(cb) {
    if (typeof cb === 'function') {
      this.hooks.push(cb);
    }
  }

  async triggerShutdown() {
    if (this.isShuttingDown) return;
    this.isShuttingDown = true;
    for (const hook of this.hooks) {
      try {
        await hook();
      } catch {}
    }
  }
}
