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
   * @param {number} [options.numSlots=64] - initial slot count
   * @param {number} [options.maxSlots=64] - maximum physical arena capacity
   */
  constructor({ slotSize = 64 * 1024, numSlots = 64, maxSlots } = {}) {
    this.slotSize = slotSize;
    this.maxSlots = Math.max(numSlots, maxSlots || numSlots);
    this.activeSlots = numSlots;

    // Single contiguous off-heap backing store allocated to max capacity
    this.arena = Buffer.allocUnsafe(this.maxSlots * this.slotSize);

    // Fixed reusable subarray views (zero wrapper allocation during runtime)
    this.views = Array.from({ length: this.maxSlots }, (_, i) =>
      this.arena.subarray(i * this.slotSize, (i + 1) * this.slotSize)
    );

    // LIFO freelist of slot indices initially populated up to activeSlots
    this.free = Array.from({ length: this.activeSlots }, (_, i) => i);

    // Reference counts per slot
    this.rc = new Int32Array(this.maxSlots);

    this.metrics = {
      acquired: 0,
      released: 0,
      fallbackAllocations: 0,
    };
  }

  /**
   * Dynamically adjust active slot capacity (Elastic Tiering governance).
   * @param {number} targetSlots
   */
  setActiveSlots(targetSlots) {
    const clamped = Math.min(Math.max(1, targetSlots), this.maxSlots);
    if (clamped === this.activeSlots) return;

    if (clamped > this.activeSlots) {
      // Expanding: push newly unlocked slots onto free list if not currently held
      for (let i = this.activeSlots; i < clamped; i++) {
        if (this.rc[i] === 0 && !this.free.includes(i)) {
          this.free.push(i);
        }
      }
    } else {
      // Shrinking: filter out slots that are now beyond active range
      this.free = this.free.filter((idx) => idx < clamped);
    }

    this.activeSlots = clamped;
  }

  get numSlots() {
    return this.activeSlots;
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
    if (idx >= 0 && idx < this.maxSlots) {
      this.rc[idx]++;
    }
  }

  /**
   * Release a slot index (decrement reference count).
   * Slot returns to LIFO freelist when rc drops to 0 (if within activeSlots).
   * @param {number} idx
   */
  release(idx) {
    if (idx >= 0 && idx < this.maxSlots) {
      if (--this.rc[idx] === 0) {
        if (idx < this.activeSlots) {
          this.free.push(idx);
        }
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
    if (idx >= 0 && idx < this.maxSlots) {
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
    if (idx < 0 || idx >= this.maxSlots) {
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
      numSlots: this.activeSlots,
      maxSlots: this.maxSlots,
      availableSlots: this.free.length,
      totalResidencyBytes: this.maxSlots * this.slotSize,
      activeResidencyBytes: this.activeSlots * this.slotSize,
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
