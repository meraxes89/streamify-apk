/**
 * Single-flight request coalescer.
 * Ensures duplicate concurrent calls for the same key share a single execution.
 */
export class SingleFlight {
  constructor() {
    /** @type {Map<string, Promise<any>>} */
    this.inFlight = new Map();
  }

  /**
   * Execute an async function, coalescing duplicate concurrent calls.
   * @template T
   * @param {string} key
   * @param {() => Promise<T>} fn
   * @returns {Promise<T>}
   */
  async do(key, fn) {
    const existing = this.inFlight.get(key);
    if (existing) {
      return existing;
    }

    const promise = (async () => {
      try {
        return await fn();
      } finally {
        this.inFlight.delete(key);
      }
    })();

    this.inFlight.set(key, promise);
    return promise;
  }

  size() {
    return this.inFlight.size;
  }
}
