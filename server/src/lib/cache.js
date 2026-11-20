/**
 * Lightweight bounded LRU cache with TTL support.
 */
export class LruCache {
  /**
   * @param {Object} options
   * @param {number} [options.maxItems=500] - Maximum item count
   * @param {number} [options.defaultTtlMs=300000] - Default TTL in ms (5 min)
   */
  constructor({ maxItems = 500, defaultTtlMs = 300000 } = {}) {
    this.maxItems = maxItems;
    this.defaultTtlMs = defaultTtlMs;
    /** @type {Map<string, { value: any, expiresAt: number }>} */
    this.map = new Map();
  }

  get(key) {
    const entry = this.map.get(key);
    if (!entry) return undefined;

    if (Date.now() > entry.expiresAt) {
      this.map.delete(key);
      return undefined;
    }

    // Refresh LRU position
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  set(key, value, ttlMs = this.defaultTtlMs) {
    if (this.map.has(key)) {
      this.map.delete(key);
    } else if (this.map.size >= this.maxItems) {
      // Evict oldest (first key in iteration)
      const oldestKey = this.map.keys().next().value;
      if (oldestKey !== undefined) {
        this.map.delete(oldestKey);
      }
    }

    this.map.set(key, {
      value,
      expiresAt: Date.now() + ttlMs,
    });
    return this;
  }

  has(key) {
    return this.get(key) !== undefined;
  }

  delete(key) {
    return this.map.delete(key);
  }

  clear() {
    this.map.clear();
  }

  size() {
    return this.map.size;
  }
}
