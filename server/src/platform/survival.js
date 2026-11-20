import fs from 'node:fs';
import { acquireWakeLock, releaseWakeLock } from './wake-lock.js';

/**
 * L0 Survival Shell: Android & Termux Process Survival (comm10.1.md)
 * 
 * - Monitors /proc/self/oom_score_adj (attempts -1000 if root)
 * - Inspects /proc/pressure/memory (PSI memory stalls)
 * - Holds termux-wake-lock to prevent CPU sleep during playback
 * - Provides advisory checks for Android 12+ Phantom Process Killer
 */

export class ProcessSurvival {
  constructor() {
    this.isTermux = Boolean(
      process.env.TERMUX_VERSION || (process.env.PREFIX && process.env.PREFIX.includes('com.termux'))
    );
    this.wakeLockHeld = false;
  }

  /**
   * Initialize survival mechanisms on server start.
   */
  init() {
    if (this.isTermux) {
      this.wakeLockHeld = acquireWakeLock();
      this.tuneOomScoreAdj(-1000);
    }
    return this.getStatus();
  }

  /**
   * Inspect and optionally tune /proc/self/oom_score_adj.
   * On rooted devices, lowering to -1000 shields from lmkd.
   * On non-rooted devices, writing is rejected with EACCES; handled gracefully.
   * 
   * @param {number} [targetScore=-1000]
   * @returns {{ score: number|null, adjusted: boolean }}
   */
  tuneOomScoreAdj(targetScore = -1000) {
    const oomPath = '/proc/self/oom_score_adj';
    try {
      if (!fs.existsSync(oomPath)) {
        return { score: null, adjusted: false };
      }

      const currentRaw = fs.readFileSync(oomPath, 'utf8').trim();
      const currentScore = parseInt(currentRaw, 10);

      // Attempt to set targetScore if different and permitted
      if (currentScore !== targetScore) {
        try {
          fs.writeFileSync(oomPath, String(targetScore));
          const updatedRaw = fs.readFileSync(oomPath, 'utf8').trim();
          return { score: parseInt(updatedRaw, 10), adjusted: true };
        } catch {
          // Non-root: cannot write negative oom_score_adj
          return { score: currentScore, adjusted: false };
        }
      }

      return { score: currentScore, adjusted: true };
    } catch {
      return { score: null, adjusted: false };
    }
  }

  /**
   * Read Linux PSI (Pressure Stall Information) memory stalls from /proc/pressure/memory.
   * Available on Linux / Android 4.20+ kernels.
   * 
   * @returns {Object|null}
   */
  readPsiMemory() {
    const psiPath = '/proc/pressure/memory';
    try {
      if (!fs.existsSync(psiPath)) return null;

      const raw = fs.readFileSync(psiPath, 'utf8');
      const lines = raw.trim().split('\n');
      const result = {};

      for (const line of lines) {
        // e.g. "some avg10=0.00 avg60=0.00 avg300=0.00 total=12345"
        const parts = line.split(' ');
        const type = parts[0]; // 'some' or 'full'
        result[type] = {};
        for (let i = 1; i < parts.length; i++) {
          const [k, v] = parts[i].split('=');
          if (k && v !== undefined) {
            result[type][k] = parseFloat(v);
          }
        }
      }
      return result;
    } catch {
      return null;
    }
  }

  /**
   * Acquire CPU wake lock.
   */
  acquireLock() {
    this.wakeLockHeld = acquireWakeLock();
    return this.wakeLockHeld;
  }

  /**
   * Release CPU wake lock.
   */
  releaseLock() {
    releaseWakeLock();
    this.wakeLockHeld = false;
  }

  /**
   * Get full L0 survival status report.
   */
  getStatus() {
    const oom = this.tuneOomScoreAdj(); // read current
    const psi = this.readPsiMemory();

    return {
      isTermux: this.isTermux,
      wakeLockHeld: this.wakeLockHeld,
      oomScoreAdj: oom.score,
      psiMemory: psi,
      phantomProcNotice: this.isTermux
        ? 'Ensure Android 12+ phantom process killer is disabled via ADB: settings put global settings_enable_monitor_phantom_procs false'
        : 'Not running on Android Termux',
    };
  }
}
