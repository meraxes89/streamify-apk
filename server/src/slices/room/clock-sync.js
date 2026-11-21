/**
 * High-Precision Monotonic Clock Sync (comm9.md Frontier 2 Specification)
 *
 * Implements 4-timestamp monotonic ping-pong using process.hrtime.bigint()
 * and sliding-window minimum-RTT jitter filtering.
 */

export class ClockSync {
  /**
   * Responds to client sync ping with monotonic server timestamps
   * @param {Object} pingMsg - { type: 'sync_ping', clientTimeMs: number }
   * @returns {Object} - { type: 'sync_pong', clientTimeMs: number, serverRecvMs: number, serverSendMs: number }
   */
  static handlePing(pingMsg) {
    const t2Ns = process.hrtime.bigint();
    const t3Ns = process.hrtime.bigint();

    return {
      type: 'sync_pong',
      clientTimeMs: pingMsg.clientTimeMs,
      serverRecvMs: Number(t2Ns) / 1e6,
      serverSendMs: Number(t3Ns) / 1e6,
    };
  }

  /**
   * Client-side reference math for calculating offset and RTT from 4 timestamps
   * @param {number} t1 - Client send time
   * @param {number} t2 - Server receive time
   * @param {number} t3 - Server send time
   * @param {number} t4 - Client receive time
   * @returns {{ offsetMs: number, rttMs: number }}
   */
  static calculateOffset(t1, t2, t3, t4) {
    const offsetMs = ((t2 - t1) + (t3 - t4)) / 2;
    const rttMs = (t4 - t1) - (t3 - t2);
    return { offsetMs, rttMs: Math.max(0, rttMs) };
  }

  /**
   * Minimum-RTT sliding filter & Skew Linear Regression (comm15.md Option 1):
   * Filters out asymmetric Wi-Fi queueing spikes by selecting the lowest-RTT 20%
   * of samples, then computes both authoritative offset and continuous clock drift (skew in ppm).
   * 
   * @param {Array<{ offsetMs: number, rttMs: number, t?: number }>} samples
   * @param {number} [windowSize=30]
   * @param {number} [topFraction=0.2]
   * @returns {{ filteredOffsetMs: number, minRttMs: number, skewPpm: number }}
   */
  static filterSamples(samples, windowSize = 30, topFraction = 0.2) {
    if (!samples || samples.length === 0) {
      return { filteredOffsetMs: 0, minRttMs: 0, skewPpm: 0 };
    }

    const window = samples.slice(-windowSize);
    // Sort by RTT ascending (minimum-RTT selects symmetric packets)
    const sorted = [...window].sort((a, b) => a.rttMs - b.rttMs);
    const count = Math.max(1, Math.floor(sorted.length * topFraction));
    const best = sorted.slice(0, count);

    const sumOffset = best.reduce((acc, s) => acc + s.offsetMs, 0);
    const filteredOffsetMs = +(sumOffset / best.length).toFixed(4);
    const minRttMs = +(best[0].rttMs).toFixed(4);

    // Linear regression on offset vs time across window to calculate clock skew (slope)
    let skewPpm = 0;
    if (window.length >= 4) {
      const n = window.length;
      let sumT = 0, sumY = 0, sumTY = 0, sumTT = 0;
      const baseT = window[0].t || 0;

      for (let i = 0; i < n; i++) {
        const tSec = ((window[i].t || i * 1000) - baseT) / 1000;
        const yMs = window[i].offsetMs;
        sumT += tSec;
        sumY += yMs;
        sumTY += tSec * yMs;
        sumTT += tSec * tSec;
      }

      const denominator = (n * sumTT - sumT * sumT);
      if (Math.abs(denominator) > 1e-6) {
        const slopeMsPerSec = (n * sumTY - sumT * sumY) / denominator;
        // 1 ms/sec drift = 1,000 ppm
        skewPpm = +(slopeMsPerSec * 1000).toFixed(2);
      }
    }

    return {
      filteredOffsetMs,
      minRttMs,
      skewPpm,
    };
  }

  /**
   * Client-side PI clock servo simulator / reference math (comm15.md Option 1):
   * Computes rate correction in ppm clamped to ±100 ppm with deadband and slew limiting.
   * 
   * @param {Object} state - { integral: number, prevPpm: number }
   * @param {number} errMs - (localDACpos in T_s) - (T_s - horizon)
   * @param {Object} [params]
   * @param {number} [params.kp=0.5]
   * @param {number} [params.ki=0.01]
   * @param {number} [params.deadbandMs=1.5]
   * @param {number} [params.maxSlewPpmPerSec=10]
   * @returns {{ ppm: number, readRate: number }}
   */
  static computeServoRate(state, errMs, { kp = 0.5, ki = 0.01, deadbandMs = 1.5, maxSlewPpm = 10 } = {}) {
    if (Math.abs(errMs) <= deadbandMs) {
      errMs = 0;
    }

    state.integral = (state.integral || 0) + errMs;
    // Anti-windup
    state.integral = Math.max(-500, Math.min(500, state.integral));

    let rawPpm = kp * errMs + ki * state.integral;
    // Clamp to ±100 ppm (~0.17 cents, pitch-imperceptible)
    rawPpm = Math.max(-100, Math.min(100, rawPpm));

    // Slew rate limiter to prevent zipper noise
    const prevPpm = state.prevPpm || 0;
    const maxDelta = maxSlewPpm;
    let clampedPpm = rawPpm;
    if (clampedPpm - prevPpm > maxDelta) clampedPpm = prevPpm + maxDelta;
    else if (prevPpm - clampedPpm > maxDelta) clampedPpm = prevPpm - maxDelta;

    state.prevPpm = clampedPpm;
    const readRate = 1 + clampedPpm * 1e-6;

    return {
      ppm: +clampedPpm.toFixed(2),
      readRate: +readRate.toFixed(8),
    };
  }
}
