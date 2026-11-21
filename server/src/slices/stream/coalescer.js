import { PRIORITY } from './admission.js';

/**
 * L2 Upstream Coalescer (comm12.md)
 * 
 * - Ensures that multiple concurrent client requests for the same content window
 *   coalesce onto a single upstream leader fetch.
 * - Followers attach to the leader's Block 0 readiness promise without consuming
 *   an admission permit.
 * - Refcounted: If all listeners disconnect before the block is cached, the upstream
 *   fetch is aborted, preserving mobile bandwidth and quota.
 * - Followers and future requests read independently from cache.
 */
export class StreamCoalescer {
  /**
   * @param {Object} options
   * @param {import('./sparse-block-cache.js').SparseBlockCache} options.cache
   * @param {import('./admission.js').AsyncAdmission} options.admission
   */
  constructor({ cache, admission }) {
    this.cache = cache;
    this.admission = admission;
    /** @type {Map<string, Object>} */
    this.inflight = new Map();

    // Telemetry
    this.totalLeaders = 0;
    this.totalFollowers = 0;
    this.totalAborts = 0;
  }

  /**
   * Joins an inflight window or leads a new upstream fetch.
   * 
   * @param {Object} desc - Stream descriptor with targetUrl, videoId, itag
   * @param {number} startBlock - Aligned start block index
   * @param {Object} [options]
   * @param {number} [options.priority]
   * @param {string} [options.clientIp]
   * @param {AbortSignal} [options.signal]
   * @returns {Object} { ready: Promise, blockReady: (idx) => Promise, abort: () => void, isLeader: boolean }
   */
  join(desc, startBlock = 0, { priority = PRIORITY.PLAYBACK, clientIp = '127.0.0.1', signal = null } = {}) {
    const key = `${desc.videoId}_${desc.itag || 251}_${startBlock}`;
    let entry = this.inflight.get(key);

    if (!entry) {
      this.totalLeaders++;
      entry = this._lead(key, desc, startBlock, priority, clientIp);
      this.inflight.set(key, entry);
      entry.done.finally(() => {
        this.inflight.delete(key);
      });
      entry.isLeader = true;
    } else {
      this.totalFollowers++;
      entry.isLeader = false;
    }

    entry.refcount++;

    if (signal) {
      const onAbort = () => {
        entry.refcount--;
        if (entry.refcount <= 0 && !entry.cached) {
          this.totalAborts++;
          entry.abort();
        }
      };
      signal.addEventListener('abort', onAbort, { once: true });
      entry.done.finally(() => {
        signal.removeEventListener('abort', onAbort);
      });
    }

    return entry;
  }

  _lead(key, desc, startBlock, priority, clientIp) {
    const ac = new AbortController();
    let readyResolve;
    let readyReject;
    const readyPromise = new Promise((resolve, reject) => {
      readyResolve = resolve;
      readyReject = reject;
    });

    const entry = {
      key,
      refcount: 0,
      cached: false,
      abort: () => ac.abort(),
      ready: readyPromise,
      done: null,
      isLeader: true,
      error: null,
    };

    entry.done = (async () => {
      let releaseAdmission = null;
      try {
        // L3: Only the unique cold leader acquires an admission slot
        releaseAdmission = await this.admission.acquire(clientIp, priority, ac.signal);

        // Check if track entry already exists or blocks are already present
        const trackEntry = this.cache.getEntry(desc);
        if (trackEntry.blockState[startBlock] === 2) { // BLOCK_PRESENT
          entry.cached = true;
          readyResolve();
          return;
        }

        // Start upstream fetch window via cache
        let activeFetch = trackEntry.activeFetches.get(startBlock);
        if (!activeFetch) {
          activeFetch = trackEntry.startFetchWindow(desc, startBlock, 4);
        }

        // Hook abort propagation
        ac.signal.addEventListener('abort', () => {
          activeFetch.abortController.abort('All coalesced followers disconnected');
        }, { once: true });

        // Wait for first block watermark to be written to cache
        const firstBlockTarget = (startBlock + 1) * (256 * 1024);
        
        // As soon as block 0 lands (or first target byte), resolve leader and wake followers
        activeFetch.waitFor(firstBlockTarget, ac.signal)
          .then(() => {
            entry.cached = true;
            readyResolve();
          })
          .catch((err) => {
            if (activeFetch.writtenOffset > startBlock * (256 * 1024)) {
              // Partial data arrived, can start
              readyResolve();
            } else {
              readyReject(err);
            }
          });

        // Await the fetch window completion
        await new Promise((res, rej) => {
          if (activeFetch.done) return res();
          const checkDone = () => {
            if (activeFetch.done) {
              if (activeFetch.error) rej(activeFetch.error);
              else res();
            } else {
              const timer = setTimeout(checkDone, 25);
              timer.unref?.();
            }
          };
          checkDone();
        });
      } catch (err) {
        entry.error = err;
        readyReject(err);
      } finally {
        if (releaseAdmission) {
          releaseAdmission();
        }
      }
    })();

    return entry;
  }

  getMetrics() {
    return {
      inflightCount: this.inflight.size,
      totalLeaders: this.totalLeaders,
      totalFollowers: this.totalFollowers,
      totalAborts: this.totalAborts,
      coalesceRatio: this.totalLeaders > 0 ? +(this.totalFollowers / this.totalLeaders).toFixed(2) : 0,
    };
  }
}
