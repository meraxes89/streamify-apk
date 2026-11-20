/**
 * EWMA Client Health & Persona Ladder Manager (comm9.1.md Phase 1)
 *
 * Tracks health scores per client persona using Exponentially Weighted Moving Averages (EWMA).
 * Automatically promotes healthy clients and demotes/circuit-breaks broken or throttled clients.
 */

export class ClientHealthManager {
  /**
   * @param {Object} [options]
   * @param {number} [options.alpha=0.2] - EWMA smoothing factor
   * @param {number} [options.circuitBreakerThreshold=0.2] - Minimum score before demotion
   * @param {number} [options.throttleCooldownMs=60000] - Cooldown duration after throttle
   */
  constructor({
    alpha = 0.2,
    circuitBreakerThreshold = 0.2,
    throttleCooldownMs = 60_000,
  } = {}) {
    this.alpha = alpha;
    this.threshold = circuitBreakerThreshold;
    this.throttleCooldownMs = throttleCooldownMs;

    /** @type {Map<string, { score: number, successes: number, errors: number, throttles: number, lastThrottledAt: number, avgLatencyMs: number }>} */
    this.scores = new Map();
  }

  getStats(clientName) {
    let stat = this.scores.get(clientName);
    if (!stat) {
      stat = {
        score: 1.0,
        successes: 0,
        errors: 0,
        throttles: 0,
        lastThrottledAt: 0,
        avgLatencyMs: 0,
      };
      this.scores.set(clientName, stat);
    }
    return stat;
  }

  recordSuccess(clientName, latencyMs = 0) {
    const s = this.getStats(clientName);
    s.successes++;
    // EWMA promotion toward 1.0
    s.score = +(s.score * (1 - this.alpha) + 1.0 * this.alpha).toFixed(4);
    if (latencyMs > 0) {
      s.avgLatencyMs = +(s.avgLatencyMs * 0.8 + latencyMs * 0.2).toFixed(2);
    }
  }

  recordError(clientName, statusCode = 0) {
    const s = this.getStats(clientName);
    s.errors++;
    if (statusCode === 403 || statusCode === 429) {
      // Severe demotion on 403 Forbidden or 429 Too Many Requests
      s.score = Math.max(0.05, +(s.score * 0.4).toFixed(4));
    } else {
      // Moderate demotion on network/resolve error
      s.score = Math.max(0.1, +(s.score * (1 - this.alpha)).toFixed(4));
    }
  }

  recordThrottle(clientName) {
    const s = this.getStats(clientName);
    s.throttles++;
    s.lastThrottledAt = Date.now();
    // Heavy demotion on attestation throttle (~50 kbps)
    s.score = Math.max(0.05, +(s.score - 0.5).toFixed(4));
  }

  isQuarantined(clientName) {
    const s = this.scores.get(clientName);
    if (!s) return false;
    if (Date.now() - s.lastThrottledAt < this.throttleCooldownMs) {
      return true;
    }
    return s.score < this.threshold;
  }

  /**
   * Sorts client personas by health score descending (healthy clients first)
   * @param {Array<Object>} clients
   * @returns {Array<Object>}
   */
  getRankedClients(clients) {
    if (!Array.isArray(clients) || clients.length <= 1) return clients;

    // Check if all clients are degraded/quarantined (fail-open probe)
    const allDegraded = clients.every((c) => this.isQuarantined(c.name));

    return [...clients].sort((a, b) => {
      const sa = this.getStats(a.name);
      const sb = this.getStats(b.name);

      if (!allDegraded) {
        const qa = this.isQuarantined(a.name);
        const qb = this.isQuarantined(b.name);
        if (qa && !qb) return 1;
        if (!qa && qb) return -1;
      }

      // Sort by score descending
      if (sb.score !== sa.score) {
        return sb.score - sa.score;
      }
      // If equal, prefer lower latency
      return (sa.avgLatencyMs || 0) - (sb.avgLatencyMs || 0);
    });
  }

  getTelemetry() {
    const telemetry = {};
    for (const [name, stat] of this.scores) {
      telemetry[name] = {
        score: stat.score,
        successes: stat.successes,
        errors: stat.errors,
        throttles: stat.throttles,
        quarantined: this.isQuarantined(name),
        avgLatencyMs: stat.avgLatencyMs,
      };
    }
    return telemetry;
  }
}
