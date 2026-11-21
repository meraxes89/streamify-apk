export class SaturatedError extends Error {
  constructor(message = 'Upstream admission queue saturated') {
    super(message);
    this.name = 'SaturatedError';
    this.statusCode = 503;
  }
}

export class TimeoutError extends Error {
  constructor(message = 'Upstream admission timed out waiting in queue') {
    super(message);
    this.name = 'TimeoutError';
    this.statusCode = 504;
  }
}

export class AbortError extends Error {
  constructor(message = 'Upstream admission aborted by client') {
    super(message);
    this.name = 'AbortError';
    this.statusCode = 499;
  }
}

/**
 * Priority values: lower integer = higher priority
 * playback (0) > seek (1) > segment (2) > prefetch (3)
 */
export const PRIORITY = {
  PLAYBACK: 0,
  SEEK: 1,
  SEGMENT: 2,
  PREFETCH: 3,
};

/**
 * L3 Async Admission Controller (comm12.md)
 * 
 * - Gates upstream *work* (network fetches to googlevideo/youtube), NOT client connections.
 * - Global upstream concurrency cap + per-IP sub-cap.
 * - Priority-ordered wait queue with age-based anti-starvation.
 * - Bounded queue with early 503 shedding only when queue is saturated.
 * - Cancellable on client abort / disconnect (frees queue slots and upstream work).
 * - Automatic Map cleanup on zero active count to eliminate memory leaks.
 */
export class AsyncAdmission {
  /**
   * @param {Object} [options]
   * @param {number} [options.globalMax=6] - Max concurrent active upstream network fetches
   * @param {number} [options.perIpMax=4] - Max concurrent upstream fetches per IP
   * @param {number} [options.maxQueue=64] - Max waiting queue depth before 503
   * @param {number} [options.queueTimeoutMs=8000] - Max wait time before timeout
   * @param {number} [options.retryAfter=2] - Recommended Retry-After in seconds
   */
  constructor({
    globalMax = 6,
    perIpMax = 4,
    maxQueue = 64,
    queueTimeoutMs = 8000,
    retryAfter = 2,
  } = {}) {
    this.globalMax = globalMax;
    this.perIpMax = perIpMax;
    this.maxQueue = maxQueue;
    this.queueTimeoutMs = queueTimeoutMs;
    this.retryAfter = retryAfter;

    this.globalActive = 0;
    /** @type {Map<string, number>} */
    this.perIp = new Map();
    /** @type {Array<Object>} */
    this.queue = [];

    // Telemetry metrics
    this.totalAdmitted = 0;
    this.totalQueued = 0;
    this.totalTimedOut = 0;
    this.totalAborted = 0;
    this.totalSaturated = 0;
  }

  // Compatibility getter with legacy StreamSemaphore
  get globalCount() {
    return this.globalActive;
  }

  get activeStreams() {
    return this.globalActive;
  }

  _eligible(ip) {
    return (this.perIp.get(ip) || 0) < this.perIpMax;
  }

  _take(ip) {
    this.globalActive++;
    this.totalAdmitted++;
    this.perIp.set(ip, (this.perIp.get(ip) || 0) + 1);
  }

  /**
   * Acquires an upstream work slot.
   * 
   * @param {string} ip
   * @param {number} [priority=0] - 0: playback, 1: seek, 2: segment, 3: prefetch
   * @param {AbortSignal} [signal]
   * @returns {Promise<() => void>} release callback
   */
  async acquire(ip = '127.0.0.1', priority = PRIORITY.PLAYBACK, signal = null) {
    if (signal?.aborted) {
      throw new AbortError();
    }

    // Fast-path: Slot available under global cap & per-IP sub-cap
    if (this.globalActive < this.globalMax && this._eligible(ip)) {
      this._take(ip);
      let released = false;
      return () => {
        if (!released) {
          released = true;
          this._release(ip);
        }
      };
    }

    // Saturated check: only now reject with SaturatedError (503)
    if (this.queue.length >= this.maxQueue) {
      this.totalSaturated++;
      throw new SaturatedError();
    }

    this.totalQueued++;

    return new Promise((resolve, reject) => {
      let released = false;
      const safeRelease = () => {
        if (!released) {
          released = true;
          this._release(ip);
        }
      };

      const item = {
        ip,
        priority: typeof priority === 'number' ? priority : PRIORITY.PLAYBACK,
        resolve: () => resolve(safeRelease),
        reject,
        t: Date.now(),
        timer: null,
        abortHandler: null,
      };

      item.timer = setTimeout(() => {
        this.totalTimedOut++;
        this._drop(item, new TimeoutError());
      }, this.queueTimeoutMs);
      item.timer.unref?.();

      if (signal) {
        item.abortHandler = () => {
          this.totalAborted++;
          this._drop(item, new AbortError());
        };
        signal.addEventListener('abort', item.abortHandler, { once: true });
      }

      this._insert(item);
    });
  }

  /**
   * Priority queue insertion with age-based boost to prevent starvation.
   * Effective Score = priority * 1000 - ageMs
   */
  _insert(item) {
    const now = Date.now();
    const itemScore = item.priority * 1000 - (now - item.t);

    let idx = this.queue.length;
    for (let i = 0; i < this.queue.length; i++) {
      const qScore = this.queue[i].priority * 1000 - (now - this.queue[i].t);
      if (itemScore < qScore) {
        idx = i;
        break;
      }
    }
    this.queue.splice(idx, 0, item);
  }

  _release(ip) {
    const n = (this.perIp.get(ip) || 1) - 1;
    if (n <= 0) {
      this.perIp.delete(ip); // Map cleanup: eliminate memory leak
    } else {
      this.perIp.set(ip, n);
    }
    this.globalActive = Math.max(0, this.globalActive - 1);

    const next = this._nextEligible();
    if (next) {
      clearTimeout(next.timer);
      this._take(next.ip);
      next.resolve();
    }
  }

  _nextEligible() {
    for (let i = 0; i < this.queue.length; i++) {
      const candidate = this.queue[i];
      if (this._eligible(candidate.ip)) {
        this.queue.splice(i, 1);
        return candidate;
      }
    }
    return null;
  }

  _drop(item, err) {
    clearTimeout(item.timer);
    const idx = this.queue.indexOf(item);
    if (idx !== -1) {
      this.queue.splice(idx, 1);
    }
    item.reject(err);
  }

  getMetrics() {
    return {
      globalActive: this.globalActive,
      globalMax: this.globalMax,
      queueDepth: this.queue.length,
      maxQueue: this.maxQueue,
      uniqueIps: this.perIp.size,
      totalAdmitted: this.totalAdmitted,
      totalQueued: this.totalQueued,
      totalTimedOut: this.totalTimedOut,
      totalAborted: this.totalAborted,
      totalSaturated: this.totalSaturated,
    };
  }
}
