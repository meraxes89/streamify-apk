import { Worker } from 'node:worker_threads';

export class CipherClient {
  /**
   * @param {Object} [options]
   * @param {number} [options.idleTimeoutMs=20000] - Idle timeout before worker teardown (20s)
   * @param {number} [options.requestTimeoutMs=5000] - Request execution timeout (5s)
   * @param {string} [options.workerPath]
   */
  constructor({
    idleTimeoutMs = 20000,
    requestTimeoutMs = 5000,
    workerPath = null,
  } = {}) {
    this.idleTimeoutMs = idleTimeoutMs;
    this.requestTimeoutMs = requestTimeoutMs;
    this.workerPath = workerPath || new URL('./workers/cipher.worker.mjs', import.meta.url);

    this.worker = null;
    this.pending = new Map();
    this.reqId = 1;
    this.idleTimer = null;
    this.isClosed = false;
  }

  ensureWorker() {
    if (this.isClosed) {
      throw new Error('CipherClient is closed');
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
        promiseObj.reject(new Error(error || 'Worker execution failed'));
      }
    });

    this.worker.on('error', (err) => {
      this.teardownWorker(err);
    });

    this.worker.on('exit', (code) => {
      if (!this.isClosed && code !== 0) {
        this.teardownWorker(new Error(`Cipher worker exited with code ${code}`));
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
      const err = errorReason || new Error('Cipher worker terminated with pending requests');
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

  send(type, payload = {}) {
    return new Promise((resolve, reject) => {
      try {
        const worker = this.ensureWorker();
        const id = this.reqId++;

        const timer = setTimeout(() => {
          this.pending.delete(id);
          reject(new Error(`CipherClient timeout after ${this.requestTimeoutMs}ms for ${type}`));
        }, this.requestTimeoutMs);
        if (typeof timer.unref === 'function') timer.unref();

        this.pending.set(id, { resolve, reject, timer });
        worker.postMessage({ id, type, payload });
      } catch (err) {
        reject(err);
      }
    });
  }

  ping() {
    return this.send('PING');
  }

  applySigOps(sig, ops) {
    return this.send('APPLY_SIG_OPS', { sig, ops });
  }

  solveNsig(n, nsigCode) {
    return this.send('SOLVE_NSIG', { n, nsigCode });
  }

  parsePlayer(hash, playerJs) {
    return this.send('PARSE_PLAYER', { hash, playerJs });
  }

  decipherUrl(cipherString, ops, nsigCode) {
    return this.send('DECIPHER_URL', { cipherString, ops, nsigCode });
  }

  close() {
    this.isClosed = true;
    this.teardownWorker();
  }
}
