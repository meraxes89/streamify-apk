export class StreamSemaphore {
  /**
   * @param {Object} options
   * @param {number} [options.maxGlobal=24] - Max concurrent streams across all clients
   * @param {number} [options.maxPerIp=4] - Max concurrent streams per client IP
   */
  constructor({ maxGlobal = 24, maxPerIp = 4, retryAfter = 2 } = {}) {
    this.maxGlobal = maxGlobal;
    this.maxPerIp = maxPerIp;
    this.retryAfter = retryAfter;
    this.globalCount = 0;
    /** @type {Map<string, number>} */
    this.ipCounts = new Map();
  }

  acquire(clientIp = 'unknown') {
    const ipCount = this.ipCounts.get(clientIp) || 0;

    if (this.globalCount >= this.maxGlobal || ipCount >= this.maxPerIp) {
      return {
        allowed: false,
        retryAfter: this.retryAfter,
      };
    }

    this.globalCount++;
    this.ipCounts.set(clientIp, ipCount + 1);

    let released = false;
    const release = () => {
      if (released) return;
      released = true;
      this.globalCount = Math.max(0, this.globalCount - 1);
      const cur = this.ipCounts.get(clientIp) || 1;
      if (cur <= 1) {
        this.ipCounts.delete(clientIp);
      } else {
        this.ipCounts.set(clientIp, cur - 1);
      }
    };

    return {
      allowed: true,
      release,
    };
  }

  getMetrics() {
    return {
      activeStreams: this.globalCount,
      uniqueIps: this.ipCounts.size,
    };
  }
}
