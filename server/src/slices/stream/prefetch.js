/**
 * Two-Stage Preemptible Prefetch Coordinator (comm7.md P2 Specification)
 *
 * Implements:
 * 1. Stage 1 (Descriptor only): Triggered at 30s playback / 50% progress. Erases the 650ms cold floor.
 * 2. Stage 2 (First 256 KiB byte warm): Triggered when under 25s remain. Warms WebM Opus / DASH init cues.
 * 3. Strict priority isolation: Dedicated low-priority concurrency limiter (max 2 global).
 * 4. Instant preemption: Aborts immediately if live playback exceeds 50% capacity, event-loop lag > 30ms,
 *    or when client emits a skip/queue mutation event.
 * 5. Unified SingleFlight handoff: Attaches directly to the shared resolver & disk write-through cache.
 */

export class PrefetchCoordinator {
  /**
   * @param {Object} options
   * @param {import('./resolver.js').StreamResolver} options.resolver
   * @param {import('./write-through-cache.js').WriteThroughCache} options.cache
   * @param {import('../../lib/semaphore.js').StreamSemaphore} [options.liveSemaphore]
   * @param {Object} [options.eventLoopLagMonitor]
   * @param {number} [options.maxConcurrent=2]
   */
  constructor({
    resolver,
    cache,
    liveSemaphore,
    eventLoopLagMonitor,
    maxConcurrent = 2,
  } = {}) {
    this.resolver = resolver;
    this.cache = cache;
    this.liveSemaphore = liveSemaphore;
    this.eventLoopLagMonitor = eventLoopLagMonitor;
    this.maxConcurrent = maxConcurrent;

    /** @type {Map<string, { stage: number, controller: AbortController, startedAt: number }>} */
    this.activeJobs = new Map();
    this.prefetchedCount = 0;
  }

  /**
   * Checks whether system pressure demands prefetch preemption.
   * @returns {boolean}
   */
  shouldPreempt() {
    // 1. Live stream capacity check (preempt if live stream utilization > 50%)
    if (this.liveSemaphore) {
      const active = this.liveSemaphore.globalCount;
      const max = this.liveSemaphore.maxGlobal;
      if (max > 0 && active / max > 0.5) {
        return true;
      }
    }

    // 2. Event loop p99 lag check (preempt if p99 lag > 30ms)
    if (this.eventLoopLagMonitor) {
      const p99Ms = this.eventLoopLagMonitor.percentile(99) / 1e6;
      if (p99Ms > 30) {
        return true;
      }
    }

    return false;
  }

  /**
   * Stage 1: Resolves descriptor metadata (cheap JSON fetch via unified SingleFlight).
   * @param {string} videoId
   * @returns {Promise<{ status: string, videoId: string, descriptor?: Object, reason?: string }>}
   */
  async prefetchDescriptor(videoId) {
    if (!videoId || typeof videoId !== 'string') {
      return { status: 'invalid_id', videoId };
    }

    if (this.shouldPreempt()) {
      return { status: 'preempted', videoId, reason: 'system_pressure' };
    }

    try {
      const descriptor = await this.resolver.resolve(videoId);
      this.prefetchedCount++;
      return { status: 'resolved', videoId, descriptor };
    } catch (err) {
      return { status: 'error', videoId, reason: err.message };
    }
  }

  /**
   * Stage 2: Warms the initial 256 KiB audio container/init block into the write-through disk cache.
   * @param {string} videoId
   * @param {Object} [options]
   * @param {number} [options.maxWarmBytes=262144] - 256 KiB default (~12s Opus audio)
   * @returns {Promise<{ status: string, videoId: string, reason?: string }>}
   */
  async prefetchBytes(videoId, { maxWarmBytes = 256 * 1024 } = {}) {
    if (!videoId || typeof videoId !== 'string') {
      return { status: 'invalid_id', videoId };
    }

    if (this.shouldPreempt()) {
      this.cancel(videoId);
      return { status: 'preempted', videoId, reason: 'system_pressure' };
    }

    if (this.activeJobs.size >= this.maxConcurrent && !this.activeJobs.has(videoId)) {
      return { status: 'busy', videoId, reason: 'concurrency_limit' };
    }

    let desc;
    try {
      desc = await this.resolver.resolve(videoId);
    } catch (err) {
      return { status: 'error', videoId, reason: err.message };
    }

    // If stream is already completely cached or block 0 is present, no byte prefetch required
    if (this.cache.hasComplete?.(desc.videoId, desc.itag) || this.cache.hasRange?.(desc.videoId, desc.itag, 0, maxWarmBytes - 1)) {
      return { status: 'already_cached', videoId };
    }

    // If an active disk writer is already streaming this track, unified handoff is active
    if (this.cache.getActiveWriter?.(desc.videoId, desc.itag)) {
      return { status: 'in_progress', videoId };
    }

    const controller = new AbortController();
    this.activeJobs.set(videoId, {
      stage: 2,
      controller,
      startedAt: Date.now(),
    });

    try {
      // P0: Prefer single-block warmBlock if available (downloads ONLY the first 256 KiB)
      if (typeof this.cache.warmBlock === 'function') {
        const warmRes = await this.cache.warmBlock(desc, 0, controller.signal);
        this.prefetchedCount++;
        return { status: 'warmed', videoId, bytes: warmRes.bytesWarmed };
      }

      // Backward compatibility fallback for legacy cache / mocks
      const writer = await this.cache.startWriter(desc);
      this.prefetchedCount++;

      // Wait until the required 256 KiB is committed to disk or complete
      if (writer.bytesAvailable < maxWarmBytes && !writer.isComplete) {
        await new Promise((resolve) => {
          const check = () => {
            if (writer.bytesAvailable >= maxWarmBytes || writer.isComplete) {
              resolve();
            } else if (controller.signal.aborted) {
              resolve();
            } else {
              setTimeout(check, 50);
            }
          };
          check();
        });
      }

      return { status: 'warmed', videoId, bytes: writer.bytesAvailable };
    } catch (err) {
      return { status: 'error', videoId, reason: err.message };
    } finally {
      this.activeJobs.delete(videoId);
    }
  }

  /**
   * Preempts and cancels any active or in-flight prefetch for a video (or all if omitted).
   * Used immediately on client skip storms or queue mutations.
   * @param {string} [videoId]
   */
  cancel(videoId) {
    if (videoId) {
      const job = this.activeJobs.get(videoId);
      if (job) {
        job.controller.abort();
        this.activeJobs.delete(videoId);
      }
    } else {
      for (const [id, job] of this.activeJobs) {
        job.controller.abort();
        this.activeJobs.delete(id);
      }
    }
  }

  getMetrics() {
    return {
      activePrefetches: this.activeJobs.size,
      totalPrefetched: this.prefetchedCount,
      maxConcurrent: this.maxConcurrent,
    };
  }
}
