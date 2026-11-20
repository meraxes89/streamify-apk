import { Worker } from 'node:worker_threads';

export class AttestClient {
  /**
   * @param {Object} [options]
   * @param {number} [options.idleTimeoutMs=20000] - 20s idle teardown
   * @param {number} [options.requestTimeoutMs=5000] - 5s request timeout
   * @param {string} [options.workerPath]
   */
  constructor({
    idleTimeoutMs = 20000,
    requestTimeoutMs = 5000,
    workerPath = null,
  } = {}) {
    this.idleTimeoutMs = idleTimeoutMs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.workerPath = workerPath || new URL('./workers/attest.worker.mjs', import.meta.url);

    this.worker = null;
    this.pending = new Map();
    this.reqId = 1;
    this.idleTimer = null;
    this.isClosed = false;

    // Concurrency 1 queue for heavy VM minting
    this.queue = [];
    this.activeTask = false;
  }

  ensureWorker() {
    if (this.isClosed) {
      throw new Error('AttestClient is closed');
    }
    if (this.worker) {
      this.resetIdleTimer();
      return this.worker;
    }

    this.worker = new Worker(this.workerPath, {
      resourceLimits: { maxOldGenerationSizeMb: 128 },
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
        promiseObj.reject(new Error(error || 'Attest worker execution failed'));
      }
    });

    this.worker.on('error', (err) => {
      this.teardownWorker(err);
    });

    this.worker.on('exit', (code) => {
      if (!this.isClosed && code !== 0) {
        this.teardownWorker(new Error(`Attest worker exited with code ${code}`));
      }
    });

    this.resetIdleTimer();
    return this.worker;
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
      const err = errorReason || new Error('Attest worker terminated with pending requests');
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

  enqueue(type, payload = {}) {
    return new Promise((resolve, reject) => {
      this.queue.push({ type, payload, resolve, reject });
      this.processQueue();
    });
  }

  async processQueue() {
    if (this.activeTask || this.queue.length === 0 || this.isClosed) {
      return;
    }

    this.activeTask = true;
    const { type, payload, resolve, reject } = this.queue.shift();

    try {
      const worker = this.ensureWorker();
      const id = this.reqId++;

      await new Promise((res, rej) => {
        const timer = setTimeout(() => {
          this.pending.delete(id);
          rej(new Error(`AttestClient timeout after ${this.requestTimeoutMs}ms for ${type}`));
        }, this.requestTimeoutMs);
        if (typeof timer.unref === 'function') timer.unref();

        this.pending.set(id, {
          resolve: (val) => {
            res(val);
            resolve(val);
          },
          reject: (err) => {
            rej(err);
            reject(err);
          },
          timer,
        });

        worker.postMessage({ id, type, payload });
      });
    } catch (err) {
      // rejection handled via pending.set above
    } finally {
      this.activeTask = false;
      this.processQueue();
    }
  }

  ping() {
    return this.enqueue('PING');
  }

  /**
   * Mint content-bound PO token for given videoId and visitorData.
   * @param {string} videoId
   * @param {string} [visitorData]
   * @returns {Promise<{token: string, ttlSecs: number}>}
   */
  mintPoToken(videoId, visitorData = '') {
    return this.enqueue('MINT_PO_TOKEN', { videoId, visitorData });
  }

  close() {
    this.isClosed = true;
    this.queue = [];
    this.teardownWorker();
  }
}
