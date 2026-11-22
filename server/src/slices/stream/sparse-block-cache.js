import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pageCache } from '../../platform/page-cache.js';

export const BLOCK_SIZE = 256 * 1024; // 256 KiB
export const BLOCK_SHIFT = 18;

export const BLOCK_EMPTY = 0;
export const BLOCK_FETCHING = 1;
export const BLOCK_PRESENT = 2;

/**
 * ActiveFetch tracks one coalesced upstream range window (comm8.md Specification)
 */
export class ActiveFetch {
  constructor(startOffset, endOffset) {
    this.startOffset = startOffset;   // aligned to 256 KiB
    this.endOffset = endOffset;
    this.writtenOffset = startOffset; // monotonic byte watermark already pwritten
    this.done = false;
    this.error = null;
    this.waiters = [];                // [{ targetOffset, resolve, reject }]
    this.abortController = new AbortController();
    this.activeReaders = 0;
  }

  // Called by the upstream writer after each fh.write() completes
  advance(newWrittenOffset) {
    this.writtenOffset = newWrittenOffset;
    let i = 0;
    while (i < this.waiters.length) {
      if (this.writtenOffset >= this.waiters[i].targetOffset) {
        this.waiters[i].resolve();
        this.waiters.splice(i, 1);
      } else {
        i++;
      }
    }
  }

  finish() {
    this.done = true;
    for (const w of this.waiters) {
      w.resolve();
    }
    this.waiters = [];
  }

  fail(err) {
    this.error = err;
    this.done = true;
    for (const w of this.waiters) {
      w.reject(err);
    }
    this.waiters = [];
  }

  waitFor(targetOffset, signal) {
    if (this.writtenOffset >= targetOffset || this.done) return Promise.resolve();
    if (this.error) return Promise.reject(this.error);
    return new Promise((resolve, reject) => {
      const waiter = { targetOffset, resolve, reject };
      this.waiters.push(waiter);
      signal?.addEventListener(
        'abort',
        () => {
          const idx = this.waiters.indexOf(waiter);
          if (idx !== -1) this.waiters.splice(idx, 1);
          reject(signal.reason || new Error('Fetch aborted'));
        },
        { once: true }
      );
    });
  }

  abort(reason) {
    this.abortController.abort(reason);
    this.fail(new Error(reason || 'Fetch aborted'));
  }
}

/**
 * TrackEntry manages block state and file handles for a single (videoId, itag) stream
 */
export class TrackEntry {
  constructor({ videoId, itag, totalLength = 0, streamsDir }) {
    this.videoId = videoId;
    this.itag = itag;
    this.totalLength = totalLength;
    this.streamsDir = streamsDir;
    this.key = `${videoId}_${itag}`;
    this.datPath = path.join(streamsDir, `${this.key}.dat`);
    this.idxPath = path.join(streamsDir, `${this.key}.idx`);

    this.numBlocks = totalLength > 0 ? Math.ceil(totalLength / BLOCK_SIZE) : 0;
    this.blockState = new Uint8Array(this.numBlocks); // 0=EMPTY, 1=FETCHING, 2=PRESENT

    this.fileHandlePromise = null;
    this.activeFetches = new Map(); // alignedStartBlock -> ActiveFetch
    this.activeReaders = 0;
    this.lastAccessed = Date.now();
    this.saveIndexTimeout = null;

    this.loadIndexSync();
  }

  setTotalLength(len) {
    if (this.totalLength === len && this.numBlocks > 0) return;
    this.totalLength = len;
    const newNumBlocks = Math.ceil(len / BLOCK_SIZE);
    if (newNumBlocks !== this.numBlocks) {
      const oldState = this.blockState;
      this.numBlocks = newNumBlocks;
      this.blockState = new Uint8Array(newNumBlocks);
      this.blockState.set(oldState.subarray(0, newNumBlocks));
    }
    this.saveIndexDebounced();
  }

  loadIndexSync() {
    try {
      if (fs.existsSync(this.idxPath)) {
        const raw = fs.readFileSync(this.idxPath, 'utf8');
        const data = JSON.parse(raw);
        if (data && data.totalLength) {
          this.totalLength = data.totalLength;
          this.numBlocks = Math.ceil(this.totalLength / BLOCK_SIZE);
          this.blockState = new Uint8Array(this.numBlocks);
          if (Array.isArray(data.blocks)) {
            for (let i = 0; i < Math.min(this.numBlocks, data.blocks.length); i++) {
              this.blockState[i] = data.blocks[i];
            }
          }
        }
      }
    } catch {
      // Ignored: index will be regenerated
    }
  }

  saveIndexSync() {
    try {
      const data = {
        videoId: this.videoId,
        itag: this.itag,
        totalLength: this.totalLength,
        numBlocks: this.numBlocks,
        blocks: Array.from(this.blockState),
        updatedAt: Date.now(),
      };
      fs.writeFileSync(this.idxPath, JSON.stringify(data), 'utf8');
    } catch {}
  }

  saveIndexDebounced() {
    if (this.saveIndexTimeout) return;
    this.saveIndexTimeout = setTimeout(() => {
      this.saveIndexTimeout = null;
      this.saveIndexSync();
    }, 100);
  }

  async getHandle() {
    this.lastAccessed = Date.now();
    if (!this.fileHandlePromise) {
      this.fileHandlePromise = fs.promises
        .open(this.datPath, fs.constants.O_RDWR | fs.constants.O_CREAT, 0o666)
        .then((fh) => {
          if (typeof fh?.fd === 'number') {
            pageCache.adviseSequential(fh.fd);
          }
          return fh;
        });
    }
    return this.fileHandlePromise;
  }

  isRangePresent(start, end) {
    if (this.numBlocks === 0) return false;
    const startBlock = Math.floor(start / BLOCK_SIZE);
    const endBlock = Math.floor(end / BLOCK_SIZE);
    if (endBlock >= this.numBlocks) return false;
    for (let b = startBlock; b <= endBlock; b++) {
      if (this.blockState[b] !== BLOCK_PRESENT) return false;
    }
    return true;
  }

  isComplete() {
    if (this.numBlocks === 0) return false;
    for (let b = 0; b < this.numBlocks; b++) {
      if (this.blockState[b] !== BLOCK_PRESENT) return false;
    }
    return true;
  }

  presentBlocksCount() {
    let count = 0;
    for (let b = 0; b < this.numBlocks; b++) {
      if (this.blockState[b] === BLOCK_PRESENT) count++;
    }
    return count;
  }

  updatePresentBlocks(startOffset, currentCursor) {
    const startBlock = Math.floor(startOffset / BLOCK_SIZE);
    const endBlock = Math.min(this.numBlocks - 1, Math.floor(currentCursor / BLOCK_SIZE));

    for (let b = startBlock; b <= endBlock; b++) {
      const blockByteEnd = Math.min((b + 1) * BLOCK_SIZE, this.totalLength);
      if (currentCursor >= blockByteEnd) {
        this.blockState[b] = BLOCK_PRESENT;
      }
    }
  }

  resetFetchingBlocks(startBlock, numBlocks) {
    const end = Math.min(this.numBlocks, startBlock + numBlocks);
    for (let b = startBlock; b < end; b++) {
      if (this.blockState[b] === BLOCK_FETCHING) {
        this.blockState[b] = BLOCK_EMPTY;
      }
    }
  }

  findActiveFetchFor(pos) {
    for (const fetch of this.activeFetches.values()) {
      if (pos >= fetch.startOffset && pos <= fetch.endOffset) {
        return fetch;
      }
      // Near-seek optimization: <= 512 KiB ahead of current watermark
      if (pos > fetch.writtenOffset && pos - fetch.writtenOffset <= 512 * 1024 && !fetch.done) {
        return fetch;
      }
    }
    return null;
  }

  startFetchWindow(desc, startBlock, numBlocksToFetch = 4) {
    const startOffset = startBlock * BLOCK_SIZE;
    const endOffset = this.totalLength
      ? Math.min((startBlock + numBlocksToFetch) * BLOCK_SIZE - 1, this.totalLength - 1)
      : (startBlock + numBlocksToFetch) * BLOCK_SIZE - 1;

    // Check if fetch already exists
    if (this.activeFetches.has(startBlock)) {
      return this.activeFetches.get(startBlock);
    }

    // Mark blocks as FETCHING
    for (let b = startBlock; b < Math.min(this.numBlocks || startBlock + numBlocksToFetch, startBlock + numBlocksToFetch); b++) {
      if (this.blockState[b] === BLOCK_EMPTY) {
        this.blockState[b] = BLOCK_FETCHING;
      }
    }

    const activeFetch = new ActiveFetch(startOffset, endOffset);
    this.activeFetches.set(startBlock, activeFetch);

    (async () => {
      let cursor = startOffset;
      try {
        const fh = await this.getHandle();
        const headers = {
          Accept: '*/*',
          Range: `bytes=${startOffset}-${endOffset}`,
          'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
        };

        const res = await fetch(desc.targetUrl, {
          headers,
          signal: activeFetch.abortController.signal,
        });

        if (!res.ok && res.status !== 206) {
          throw new Error(`Upstream returned ${res.status}`);
        }

        if (!this.totalLength) {
          const cr = res.headers.get('content-range');
          if (cr) {
            const m = cr.match(/\/(\d+)$/);
            if (m) this.setTotalLength(parseInt(m[1], 10));
          } else {
            const cl = res.headers.get('content-length');
            if (cl) this.setTotalLength(parseInt(cl, 10));
          }
        }

        const reader = res.body.getReader();
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;
          if (!value || value.length === 0) continue;

          // Positional pwrite into sparse file (zero file cursor mutation)
          await fh.write(value, 0, value.length, cursor);
          cursor += value.length;
          activeFetch.advance(cursor);

          if (this.totalLength) {
            this.updatePresentBlocks(startOffset, cursor);
          }
        }

        if (this.totalLength) {
          this.updatePresentBlocks(startOffset, cursor);
        }
        activeFetch.finish();
      } catch (err) {
        if (activeFetch.abortController.signal.aborted) {
          if (this.totalLength) {
            this.updatePresentBlocks(startOffset, cursor);
          }
          activeFetch.finish();
        } else {
          activeFetch.fail(err);
          this.resetFetchingBlocks(startBlock, numBlocksToFetch);
        }
      } finally {
        this.activeFetches.delete(startBlock);
        this.saveIndexDebounced();
      }
    })();

    return activeFetch;
  }

  async close() {
    if (this.saveIndexTimeout) {
      clearTimeout(this.saveIndexTimeout);
      this.saveIndexTimeout = null;
    }
    this.saveIndexSync();
    for (const f of this.activeFetches.values()) {
      f.abort('Track closing');
    }
    this.activeFetches.clear();
    if (this.fileHandlePromise) {
      try {
        const fh = await this.fileHandlePromise;
        this.fileHandlePromise = null;
        if (typeof fh?.fd === 'number') {
          pageCache.adviseDontNeed(fh.fd, 0, this.totalLength || 0, true);
        }
        await fh.close();
      } catch {}
    }
  }
}

/**
 * BlockRangeStream: Readable stream reading from SparseBlockCache (pread dirty page cache / flash)
 */
export class BlockRangeStream extends Readable {
  constructor({ entry, desc, start, end, highWaterMark = 256 * 1024, signal, cache }) {
    super({ highWaterMark });
    this.entry = entry;
    this.desc = desc;
    this.currentPos = start;
    this.endPos = end;
    this.signal = signal;
    this.cache = cache;
    this.isReading = false;

    this.entry.activeReaders++;

    if (signal) {
      signal.addEventListener(
        'abort',
        () => {
          this.destroy();
        },
        { once: true }
      );
    }
  }

  _destroy(err, callback) {
    this.entry.activeReaders = Math.max(0, this.entry.activeReaders - 1);
    this.cache.onReaderDetached(this.entry);
    const isAbort = err && (err.name === 'AbortError' || err.code === 'ABORT_ERR' || this.signal?.aborted);
    callback(isAbort ? null : err);
  }

  async _read(size) {
    if (this.isReading) return;
    this.isReading = true;

    try {
      while (this.currentPos <= this.endPos) {
        const blockIdx = Math.floor(this.currentPos / BLOCK_SIZE);
        const blockEnd = Math.min((blockIdx + 1) * BLOCK_SIZE - 1, this.endPos);
        const maxBytesToRead = Math.min(size || 65536, this.endPos - this.currentPos + 1);

        const state = this.entry.blockState[blockIdx];

        if (state === BLOCK_PRESENT) {
          // Block is already on disk. Direct pread from OS cache
          const fh = await this.entry.getHandle();
          const buf = Buffer.allocUnsafe(maxBytesToRead);
          const { bytesRead } = await fh.read(buf, 0, maxBytesToRead, this.currentPos);
          if (bytesRead === 0) break;

          this.currentPos += bytesRead;
          const pushed = this.push(buf.subarray(0, bytesRead));
          if (!pushed) {
            this.isReading = false;
            return;
          }
          continue;
        }

        // Block is not yet PRESENT. Find or start ActiveFetch
        let activeFetch = this.entry.findActiveFetchFor(this.currentPos);
        if (!activeFetch) {
          activeFetch = this.entry.startFetchWindow(this.desc, blockIdx, 4);
        }

        // Wait for byte barrier (monotonic written watermark)
        const targetByte = Math.min(this.currentPos + 16384, blockEnd + 1);
        await activeFetch.waitFor(targetByte, this.signal);

        const available = Math.min(maxBytesToRead, activeFetch.writtenOffset - this.currentPos);
        if (available <= 0) {
          if (activeFetch.done) break;
          continue;
        }

        // Read bytes directly from dirty page cache in RAM
        const fh = await this.entry.getHandle();
        const buf = Buffer.allocUnsafe(available);
        const { bytesRead } = await fh.read(buf, 0, available, this.currentPos);
        if (bytesRead === 0) break;

        this.currentPos += bytesRead;
        const pushed = this.push(buf.subarray(0, bytesRead));
        if (!pushed) {
          this.isReading = false;
          return;
        }
      }

      if (this.currentPos > this.endPos) {
        this.push(null);
      }
    } catch (err) {
      this.destroy(err);
    } finally {
      this.isReading = false;
    }
  }
}

/**
 * SparseBlockCache: Central manager for block-indexed streams (comm8.md P0)
 */
export class SparseBlockCache {
  constructor({ streamsDir, maxCacheMb = 512 } = {}) {
    this.streamsDir = streamsDir;
    this.maxCacheBytes = maxCacheMb * 1024 * 1024;
    /** @type {Map<string, TrackEntry>} */
    this.entries = new Map();

    if (!fs.existsSync(streamsDir)) {
      fs.mkdirSync(streamsDir, { recursive: true });
    }

    this.scanExistingEntries();
  }

  scanExistingEntries() {
    try {
      const files = fs.readdirSync(this.streamsDir);
      for (const file of files) {
        if (file.endsWith('.idx')) {
          const key = file.slice(0, -4);
          const lastUnder = key.lastIndexOf('_');
          if (lastUnder !== -1) {
            const videoId = key.slice(0, lastUnder);
            const itagStr = key.slice(lastUnder + 1);
            if (videoId && itagStr) {
              const entry = new TrackEntry({
                videoId,
                itag: parseInt(itagStr, 10),
                streamsDir: this.streamsDir,
              });
              this.entries.set(key, entry);
            }
          }
        }
      }
    } catch {}
  }

  getEntry(desc) {
    const key = `${desc.videoId}_${desc.itag}`;
    let entry = this.entries.get(key);
    if (!entry) {
      entry = new TrackEntry({
        videoId: desc.videoId,
        itag: desc.itag,
        totalLength: desc.contentLength || 0,
        streamsDir: this.streamsDir,
      });
      this.entries.set(key, entry);
    } else if (desc.contentLength && !entry.totalLength) {
      entry.setTotalLength(desc.contentLength);
    }
    entry.lastAccessed = Date.now();
    return entry;
  }

  hasComplete(videoId, itag) {
    const key = `${videoId}_${itag}`;
    const entry = this.entries.get(key);
    if (entry && entry.isComplete()) {
      return entry.datPath;
    }
    // Also check if legacy .bin exists
    const legacyPath = path.join(this.streamsDir, `${key}.bin`);
    try {
      const st = fs.statSync(legacyPath);
      if (st.size > 0) return legacyPath;
    } catch {}
    return null;
  }

  hasRange(videoId, itag, start, end) {
    const key = `${videoId}_${itag}`;
    const entry = this.entries.get(key);
    if (!entry) return false;
    return entry.isRangePresent(start, end);
  }

  getActiveWriter(videoId, itag) {
    const key = `${videoId}_${itag}`;
    const entry = this.entries.get(key);
    if (entry && entry.activeFetches.size > 0) {
      let maxWritten = 0;
      for (const f of entry.activeFetches.values()) {
        if (f.writtenOffset > maxWritten) maxWritten = f.writtenOffset;
      }
      return {
        bytesAvailable: maxWritten,
        isComplete: entry.isComplete(),
      };
    }
    return null;
  }

  async readDirect(desc, start, length) {
    const entry = this.getEntry(desc);
    const fh = await entry.getHandle();
    const buf = Buffer.allocUnsafe(length);
    const { bytesRead } = await fh.read(buf, 0, length, start);
    return buf.subarray(0, bytesRead);
  }

  createRangeStream(desc, start, end, signal) {
    const entry = this.getEntry(desc);
    this.evictOverBudget();
    return new BlockRangeStream({
      entry,
      desc,
      start,
      end,
      signal,
      cache: this,
    });
  }

  /**
   * Pre-warms target block (e.g. Block 0 for Stage 2 prefetch)
   */
  async warmBlock(desc, blockIdx = 0, signal) {
    const entry = this.getEntry(desc);
    if (entry.blockState[blockIdx] === BLOCK_PRESENT) {
      // L2 Page-Cache: pre-fault block into RAM asynchronously
      entry.getHandle().then((fh) => {
        if (typeof fh?.fd === 'number') {
          pageCache.adviseWillNeed(fh.fd, blockIdx * BLOCK_SIZE, BLOCK_SIZE);
        }
      }).catch(() => {});
      return { status: 'already_cached', bytesWarmed: BLOCK_SIZE };
    }

    let activeFetch = entry.activeFetches.get(blockIdx);
    if (!activeFetch) {
      activeFetch = entry.startFetchWindow(desc, blockIdx, 1); // 1 block only = 256 KiB
    }

    const targetOffset = (blockIdx + 1) * BLOCK_SIZE;
    await activeFetch.waitFor(targetOffset, signal);

    return {
      status: 'warmed',
      bytesWarmed: Math.min(BLOCK_SIZE, activeFetch.writtenOffset - blockIdx * BLOCK_SIZE),
    };
  }

  onReaderDetached(entry) {
    if (entry.activeReaders === 0) {
      // 1.5 second grace window before halting non-essential fetches
      setTimeout(() => {
        if (entry.activeReaders === 0) {
          for (const fetch of entry.activeFetches.values()) {
            if (fetch.activeReaders === 0) {
              fetch.abort('No active readers');
            }
          }
        }
      }, 1500);
    }
  }

  /**
   * Value-Based Segmented LRU Eviction with Water-Level Hysteresis (comm14.md Option 1):
   * - High-water trigger: 85% of maxCacheBytes
   * - Evict down to low-water target: 70% of maxCacheBytes (prevents thrashing)
   * - Active playhead/readers pinned (never evicted)
   * - Tier 1: Cold Partial tracks (<80% complete, no active readers)
   * - Tier 2: Cold Complete tracks (LRU order)
   * - Warm partial tracks >80% are preserved for opportunistic completion
   */
  evictOverBudget() {
    let totalCachedBytes = 0;
    for (const entry of this.entries.values()) {
      totalCachedBytes += entry.presentBlocksCount() * BLOCK_SIZE;
    }

    const highWater = this.maxCacheBytes * 0.85;
    const lowWater = this.maxCacheBytes * 0.70;

    if (totalCachedBytes <= highWater) return;

    // Segment candidates into Tier 1 (Partial cold) and Tier 2 (Complete cold)
    const tier1PartialCold = [];
    const tier2CompleteCold = [];

    for (const entry of this.entries.values()) {
      // PINNED: Active readers or ongoing fetches are NEVER evicted
      if (entry.activeReaders > 0 || entry.activeFetches.size > 0) continue;

      const totalBlocks = entry.numBlocks || 1;
      const presentBlocks = entry.presentBlocksCount();
      const completionRatio = presentBlocks / totalBlocks;

      if (entry.isComplete() || completionRatio >= 0.8) {
        tier2CompleteCold.push(entry);
      } else {
        tier1PartialCold.push(entry);
      }
    }

    // Sort both tiers by lastAccessed ascending (LRU)
    tier1PartialCold.sort((a, b) => a.lastAccessed - b.lastAccessed);
    tier2CompleteCold.sort((a, b) => a.lastAccessed - b.lastAccessed);

    const candidates = [...tier1PartialCold, ...tier2CompleteCold];

    for (const entry of candidates) {
      const freedBytes = entry.presentBlocksCount() * BLOCK_SIZE;
      entry.close().catch(() => {});
      try {
        if (fs.existsSync(entry.datPath)) fs.unlinkSync(entry.datPath);
        if (fs.existsSync(entry.idxPath)) fs.unlinkSync(entry.idxPath);
      } catch {}

      this.entries.delete(entry.key);
      totalCachedBytes -= freedBytes;

      // Stop once we've reached low-water mark (hysteresis)
      if (totalCachedBytes <= lowWater) break;
    }
  }

  // Backward compatibility alias for WriteThroughCache.startWriter
  async startWriter(desc) {
    const entry = this.getEntry(desc);
    let fetch = entry.findActiveFetchFor(0);
    if (!fetch) {
      fetch = entry.startFetchWindow(desc, 0, 4);
    }
    return {
      get bytesAvailable() {
        return fetch.writtenOffset;
      },
      get isComplete() {
        return entry.isComplete();
      },
    };
  }

  async close() {
    for (const entry of this.entries.values()) {
      await entry.close();
    }
    this.entries.clear();
  }
}
