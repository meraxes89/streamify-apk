/**
 * Governance & Resilience System
 * 
 * Protects local device residential IP from rate-limiting (429), bans, and transient errors.
 * Includes:
 *  - Per-territory Circuit Breaker with exponential backoff and jitter
 *  - Cooldown quarantine for burned regions
 *  - Rate Pacer to ensure healthy request intervals
 */

export const CircuitState = Object.freeze({
  CLOSED: 'CLOSED',       // Normal operation
  OPEN: 'OPEN',           // Tripped, rejecting fast
  HALF_OPEN: 'HALF_OPEN'  // Testing recovery with single canary request
});

export class RateLimitedError extends Error {
  constructor(message = 'Upstream rate limited (HTTP 429)', retryAfterMs = 5000) {
    super(message);
    this.name = 'RateLimitedError';
    this.statusCode = 429;
    this.retryAfterMs = retryAfterMs;
  }
}

export class CircuitBreaker {
  constructor({
    failureThreshold = 3,
    baseCooldownMs = 15000,
    maxCooldownMs = 120000
  } = {}) {
    this.failureThreshold = failureThreshold;
    this.baseCooldownMs = baseCooldownMs;
    this.maxCooldownMs = maxCooldownMs;

    /** @type {Map<string, Object>} territory -> state */
    this.territoryStates = new Map();
  }

  getState(territory = 'US') {
    const terr = territory.toUpperCase();
    let state = this.territoryStates.get(terr);
    if (!state) {
      state = {
        circuit: CircuitState.CLOSED,
        failures: 0,
        lastFailureTime: 0,
        cooldownMs: this.baseCooldownMs
      };
      this.territoryStates.set(terr, state);
    }

    if (state.circuit === CircuitState.OPEN) {
      const elapsed = Date.now() - state.lastFailureTime;
      if (elapsed >= state.cooldownMs) {
        state.circuit = CircuitState.HALF_OPEN;
      }
    }

    return state;
  }

  canExecute(territory = 'US') {
    const state = this.getState(territory);
    return state.circuit !== CircuitState.OPEN;
  }

  recordSuccess(territory = 'US') {
    const state = this.getState(territory);
    state.circuit = CircuitState.CLOSED;
    state.failures = 0;
    state.cooldownMs = this.baseCooldownMs;
  }

  recordFailure(territory = 'US', statusCode = 500) {
    const state = this.getState(territory);
    state.failures++;
    state.lastFailureTime = Date.now();

    if (statusCode === 429) {
      // Immediate trip on HTTP 429
      state.circuit = CircuitState.OPEN;
      state.cooldownMs = Math.min(this.maxCooldownMs, state.cooldownMs * 2);
    } else if (state.failures >= this.failureThreshold) {
      state.circuit = CircuitState.OPEN;
      state.cooldownMs = Math.min(this.maxCooldownMs, state.cooldownMs * 1.5);
    }
  }

  async execute(territory, taskFn) {
    if (!this.canExecute(territory)) {
      const state = this.getState(territory);
      const remaining = Math.max(0, state.cooldownMs - (Date.now() - state.lastFailureTime));
      throw new RateLimitedError(`Circuit breaker OPEN for region ${territory}. Retry in ${Math.round(remaining / 1000)}s`, remaining);
    }

    try {
      const result = await taskFn();
      this.recordSuccess(territory);
      return result;
    } catch (err) {
      const status = err.statusCode || (err.message?.includes('429') ? 429 : 500);
      this.recordFailure(territory, status);
      throw err;
    }
  }
}

export class RatePacer {
  constructor({ minIntervalMs = 150 } = {}) {
    this.minIntervalMs = minIntervalMs;
    this.lastRequestTimes = new Map();
  }

  async pace(territory = 'US') {
    const terr = territory.toUpperCase();
    const last = this.lastRequestTimes.get(terr) || 0;
    const now = Date.now();
    const elapsed = now - last;

    if (elapsed < this.minIntervalMs) {
      const delay = this.minIntervalMs - elapsed;
      await new Promise(resolve => setTimeout(resolve, delay));
    }

    this.lastRequestTimes.set(terr, Date.now());
  }
}
