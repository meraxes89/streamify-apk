import { Worker } from 'node:worker_threads';
import { extractPlayerSubtrees } from './workers/parser.worker.mjs';

/**
 * Worker-isolated selective parser client with graceful in-thread fallback.
 */
export class ParserClient {
  /**
   * @param {Object} [options]
   * @param {number} [options.idleTimeoutMs=30000]
   * @param {number} [options.requestTimeoutMs=3000]
   * @param {string} [options.workerPath]
   */
  constructor({
    idleTimeoutMs = 30000,
    requestTimeoutMs = 3000,
    workerPath = null,
  } = {}) {
    this.idleTimeoutMs = idleTimeoutMs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.workerPath = workerPath || new URL('./workers/parser.worker.mjs', import.meta.url);

    this.worker = null;
    this.pending = new Map();
    this.reqId = 1;
    this.idleTimer = null;
    this.isClosed = false;
  }

  ensureWorker() {
    if (this.isClosed) {
      throw new Error('ParserClient is closed');
    }
    if (this.worker) {
      this.resetIdleTimer();
      return this.worker;
    }

    try {
      this.worker = new Worker(this.workerPath, {
        resourceLimits: { maxOldGenerationSizeMb: 64 },
      });

      this.worker.on('message', (msg) => {
        const { id, ok, data, error } = msg || {};
        const promiseObj = this.pending.get(id);
        if (!promiseObj) return;

        this.pending.delete(id);
        clearTimeout(promiseObj.timer);

        if (ok) {
          promiseObj.resolve(data);
        } else {
          promiseObj.reject(new Error(error || 'Parser worker execution failed'));
        }
      });

      this.worker.on('error', (err) => {
        this.teardownWorker(err);
      });

      this.worker.on('exit', (code) => {
        if (!this.isClosed && code !== 0) {
          this.teardownWorker(new Error(`Parser worker exited with code ${code}`));
        }
      });

      this.resetIdleTimer();
      return this.worker;
    } catch {
      // Fallback to inline parsing if worker spawning fails
      return null;
    }
  }

  resetIdleTimer() {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer);
    }
    this.idleTimer = setTimeout(() => {
      this.teardownWorker();
    }, this.idleTimeoutMs);
    if (typeof this.idleTimer.unref === 'function') {
      this.idleTimer.unref();
    }
  }

  teardownWorker(errorReason = null) {
    if (this.idleTimer) {
      clearTimeout(this.idleTimer);
      this.idleTimer = null;
    }

    if (this.pending.size > 0) {
      const err = errorReason || new Error('Parser worker terminated with pending requests');
      for (const [, p] of this.pending) {
        clearTimeout(p.timer);
        p.reject(err);
      }
      this.pending.clear();
    }

    if (this.worker) {
      try {
        this.worker.terminate();
      } catch {}
      this.worker = null;
    }
  }

  /**
   * Selectively parse player JSON off the main thread.
   * @param {string} rawText
   * @returns {Promise<Object>}
   */
  async extractPlayer(rawText) {
    if (!rawText || typeof rawText !== 'string') {
      throw new Error('rawText is required');
    }

    // Try worker dispatch
    const worker = this.ensureWorker();
    if (!worker) {
      // Direct in-thread fallback
      return extractPlayerSubtrees(rawText);
    }

    return new Promise((resolve, reject) => {
      const id = this.reqId++;
      const timer = setTimeout(() => {
        this.pending.delete(id);
        // Fall back to direct in-thread on timeout
        try {
          resolve(extractPlayerSubtrees(rawText));
        } catch (e) {
          reject(e);
        }
      }, this.requestTimeoutMs);
      if (typeof timer.unref === 'function') timer.unref();

      this.pending.set(id, { resolve, reject, timer });
      worker.postMessage({ id, type: 'EXTRACT_PLAYER', payload: { rawText } });
    });
  }

  close() {
    this.isClosed = true;
    this.teardownWorker();
  }
}
