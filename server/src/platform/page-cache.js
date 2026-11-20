/**
 * L2 Page-Cache Hygiene via FFI (comm10.1.md Frontier 2)
 * 
 * Binds libc posix_fadvise and sync_file_range using pure-JS FFI (koffi).
 * - POSIX_FADV_SEQUENTIAL (2): 2x kernel readahead window
 * - POSIX_FADV_WILLNEED (3): Asynchronous kernel readahead of upcoming blocks (hides flash latency)
 * - POSIX_FADV_DONTNEED (4): Drops clean pages from RAM to reduce refaulting/thrashing under lmkd
 * - sync_file_range (SYNC_FILE_RANGE_WRITE=2): Flushes dirty write pages before DONTNEED
 */

export const POSIX_FADV_NORMAL = 0;
export const POSIX_FADV_RANDOM = 1;
export const POSIX_FADV_SEQUENTIAL = 2;
export const POSIX_FADV_WILLNEED = 3;
export const POSIX_FADV_DONTNEED = 4;
export const POSIX_FADV_NOREUSE = 5;

export const SYNC_FILE_RANGE_WAIT_BEFORE = 1;
export const SYNC_FILE_RANGE_WRITE = 2;
export const SYNC_FILE_RANGE_WAIT_AFTER = 4;

class PageCacheController {
  constructor() {
    this.isSupported = false;
    this.fadviseFn = null;
    this.syncRangeFn = null;
    this.init();
  }

  init() {
    if (process.platform !== 'linux') {
      return;
    }

    try {
      // Dynamic import / require of koffi
      // In ESM, use createRequire or dynamic import
      import('koffi')
        .then((koffiModule) => {
          const koffi = koffiModule.default || koffiModule;
          const libc = koffi.load('libc.so');
          this.fadviseFn = libc.func('int posix_fadvise(int fd, int64 off, int64 len, int advice)');
          this.syncRangeFn = libc.func('int sync_file_range(int fd, int64 off, int64 len, uint flags)');
          this.isSupported = true;
        })
        .catch(() => {
          // Gracefully fallback if koffi native prebuild is unavailable
          this.isSupported = false;
        });
    } catch {
      this.isSupported = false;
    }
  }

  /**
   * Set sequential readahead on file descriptor.
   * @param {number} fd
   * @param {number} [offset=0]
   * @param {number} [length=0]
   * @returns {number} 0 on success, -1 on failure/unsupported
   */
  adviseSequential(fd, offset = 0, length = 0) {
    if (!this.isSupported || !this.fadviseFn || typeof fd !== 'number' || fd < 0) {
      return -1;
    }
    try {
      return this.fadviseFn(fd, BigInt(offset), BigInt(length), POSIX_FADV_SEQUENTIAL);
    } catch {
      return -1;
    }
  }

  /**
   * Asynchronously pre-fault upcoming block range into page cache.
   * @param {number} fd
   * @param {number} offset
   * @param {number} length
   * @returns {number}
   */
  adviseWillNeed(fd, offset, length) {
    if (!this.isSupported || !this.fadviseFn || typeof fd !== 'number' || fd < 0) {
      return -1;
    }
    try {
      return this.fadviseFn(fd, BigInt(offset), BigInt(length), POSIX_FADV_WILLNEED);
    } catch {
      return -1;
    }
  }

  /**
   * Evict clean pages from kernel page cache.
   * If wasWritten is true, flushes dirty pages first via sync_file_range.
   * @param {number} fd
   * @param {number} offset
   * @param {number} length
   * @param {boolean} [wasWritten=false]
   * @returns {number}
   */
  adviseDontNeed(fd, offset, length, wasWritten = false) {
    if (!this.isSupported || !this.fadviseFn || typeof fd !== 'number' || fd < 0) {
      return -1;
    }
    try {
      if (wasWritten && this.syncRangeFn) {
        this.syncRangeFn(fd, BigInt(offset), BigInt(length), SYNC_FILE_RANGE_WRITE);
      }
      return this.fadviseFn(fd, BigInt(offset), BigInt(length), POSIX_FADV_DONTNEED);
    } catch {
      return -1;
    }
  }

  getStatus() {
    return {
      isSupported: this.isSupported,
      platform: process.platform,
    };
  }
}

export const pageCache = new PageCacheController();
