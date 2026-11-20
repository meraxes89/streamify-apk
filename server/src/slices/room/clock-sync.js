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
   * Minimum-RTT sliding filter (comm9.md Section 1):
   * Filters out asymmetric Wi-Fi queueing spikes by selecting the lowest-RTT 20%
   * of the last 30 samples to compute the true physical clock offset.
   * @param {Array<{ offsetMs: number, rttMs: number }>} samples
   * @param {number} [windowSize=30]
   * @param {number} [topFraction=0.2]
   * @returns {{ filteredOffsetMs: number, minRttMs: number }}
   */
  static filterSamples(samples, windowSize = 30, topFraction = 0.2) {
    if (!samples || samples.length === 0) {
      return { filteredOffsetMs: 0, minRttMs: 0 };
    }

    const window = samples.slice(-windowSize);
    // Sort by RTT ascending
    const sorted = [...window].sort((a, b) => a.rttMs - b.rttMs);
    const count = Math.max(1, Math.floor(sorted.length * topFraction));
    const best = sorted.slice(0, count);

    const sumOffset = best.reduce((acc, s) => acc + s.offsetMs, 0);
    return {
      filteredOffsetMs: +(sumOffset / best.length).toFixed(4),
      minRttMs: +(best[0].rttMs).toFixed(4),
    };
  }
}
