/**
 * L1 Allocation Governance: Fixed Reusable Buffer Pool (comm10.1.md)
 * 
 * Reuses the same subarray views forever to eliminate V8 heap wrapper churn.
 * Refcounted via socket write callback; returns slot to LIFO freelist upon completion.
 */
export class SlotBufferPool {
  /**
   * @param {Object} [options]
   * @param {number} [options.slotSize=65536] - 64 KiB slot size
   * @param {number} [options.numSlots=64] - 64 slots = 4 MiB fixed residency
   */
  constructor({ slotSize = 64 * 1024, numSlots = 64 } = {}) {
    this.slotSize = slotSize;
    this.numSlots = numSlots;

    // Single contiguous off-heap backing store
    this.arena = Buffer.allocUnsafe(this.numSlots * this.slotSize);

    // Fixed reusable subarray views (zero wrapper allocation during runtime)
    this.views = Array.from({ length: this.numSlots }, (_, i) =>
      this.arena.subarray(i * this.slotSize, (i + 1) * this.slotSize)
    );

    // LIFO freelist of slot indices (cache-hot, single-thread event loop)
    this.free = Array.from({ length: this.numSlots }, (_, i) => i);

    // Reference counts per slot
    this.rc = new Int32Array(this.numSlots);

    this.metrics = {
      acquired: 0,
      released: 0,
      fallbackAllocations: 0,
    };
  }

  /**
   * Acquire a slot index from the LIFO freelist.
   * Returns -1 if pool is exhausted.
   * @returns {number}
   */
  acquire() {
    const idx = this.free.pop();
    if (idx === undefined) {
      this.metrics.fallbackAllocations++;
      return -1;
    }
    this.rc[idx] = 1;
    this.metrics.acquired++;
    return idx;
  }

  /**
   * Retain a slot index (increment reference count).
   * @param {number} idx
   */
  retain(idx) {
    if (idx >= 0 && idx < this.numSlots) {
      this.rc[idx]++;
    }
  }

  /**
   * Release a slot index (decrement reference count).
   * Slot returns to LIFO freelist when rc drops to 0.
   * @param {number} idx
   */
  release(idx) {
    if (idx >= 0 && idx < this.numSlots) {
      if (--this.rc[idx] === 0) {
        this.free.push(idx);
        this.metrics.released++;
      }
    }
  }

  /**
   * Get pre-allocated Buffer view for slot.
   * @param {number} idx
   * @returns {Buffer}
   */
  getView(idx) {
    if (idx >= 0 && idx < this.numSlots) {
      return this.views[idx];
    }
    return null;
  }

  /**
   * Send slot payload directly to socket, recycling slot when socket write completes.
   * @param {import('node:net').Socket} socket
   * @param {number} idx
   * @param {number} length
   * @param {Function} [cb]
   */
  send(socket, idx, length = this.slotSize, cb) {
    if (idx < 0 || idx >= this.numSlots) {
      if (cb) cb(new Error('Invalid slot index'));
      return;
    }
    this.retain(idx);
    const slice = this.views[idx].subarray(0, length);
    socket.write(slice, (err) => {
      this.release(idx);
      if (cb) cb(err);
    });
  }

  /**
   * Available slots in pool.
   */
  get available() {
    return this.free.length;
  }

  /**
   * Metrics and residency status.
   */
  getStatus() {
    return {
      slotSize: this.slotSize,
      numSlots: this.numSlots,
      availableSlots: this.free.length,
      totalResidencyBytes: this.numSlots * this.slotSize,
      acquiredCount: this.metrics.acquired,
      releasedCount: this.metrics.released,
      fallbackCount: this.metrics.fallbackAllocations,
    };
  }
}

// Global default singleton pool for stream data plane
let defaultPool = null;

export function getDefaultBufferPool() {
  if (!defaultPool) {
    defaultPool = new SlotBufferPool();
  }
  return defaultPool;
}
