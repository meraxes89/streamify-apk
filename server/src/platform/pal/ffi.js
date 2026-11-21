/**
 * Streamify Platform Abstraction Layer (PAL) - Safe FFI Loader & Symbol Tables
 * 
 * Thin, fail-safe wrapper around koffi for pure-JS C-ABI calls across OSes.
 * Guarantees zero unhandled exceptions:
 * - Falls back to null on unsupported architectures (e.g. armeabi-v7a softfp)
 * - Safe call wrappers swallow native errors and return error codes instead of crashing
 */

import os from 'node:os';

let koffi = null;
let koffiLoadError = null;

// Architecture verification: Android armeabi-v7a is softfp; koffi arm32 prebuilt is hard-float.
const isAndroid = process.platform === 'android' || Boolean(process.env.TERMUX_VERSION);
const isArmV7a = process.arch === 'arm';

if (isAndroid && isArmV7a) {
  koffiLoadError = new Error('Android 32-bit armeabi-v7a softfp detected; FFI disabled to prevent SIGILL/SIGBUS');
} else {
  try {
    const imported = await import('koffi');
    koffi = imported.default || imported;
  } catch (err) {
    koffiLoadError = err;
  }
}

/**
 * Attempt to load the first available shared library from a list of candidate names.
 * @param {string[]} names - Array of library names or paths (e.g. ['libc.so.6', 'libc.so'])
 * @returns {any|null} The koffi library handle, or null if all candidates fail
 */
export function loadLib(names) {
  if (!koffi) return null;
  for (const n of names) {
    try {
      return koffi.load(n);
    } catch {
      // Try next candidate
    }
  }
  return null;
}

/**
 * Safely execute an FFI function, catching any exceptions and returning null or fallback.
 * @param {Function} fn - The FFI function to call
 * @param {any[]} args - Arguments to pass
 * @param {any} [fallback=null] - Value to return if call fails
 * @returns {any} Result of fn(...args) or fallback
 */
export function safeCall(fn, args = [], fallback = null) {
  if (typeof fn !== 'function') return fallback;
  try {
    return fn(...args);
  } catch (err) {
    return fallback;
  }
}

/**
 * Safely execute an async FFI function.
 * @param {Function} asyncFn - The async FFI function to call
 * @param {any[]} args - Arguments to pass
 * @param {any} [fallback=null] - Value to return if call fails
 * @returns {Promise<any>}
 */
export async function safeCallAsync(asyncFn, args = [], fallback = null) {
  if (typeof asyncFn !== 'function') return fallback;
  try {
    return await asyncFn(...args);
  } catch {
    return fallback;
  }
}

// -----------------------------------------------------------------------------
// Pre-bound symbol tables per platform (lazy evaluation)
// -----------------------------------------------------------------------------

export const symbols = {
  get koffiAvailable() {
    return koffi !== null;
  },
  get koffiError() {
    return koffiLoadError ? koffiLoadError.message : null;
  },

  // Linux / Android (libc)
  linux: (() => {
    if (!koffi) return null;
    const libc = loadLib(['libc.so.6', 'libc.so']);
    if (!libc) return null;

    try {
      const posix_fadvise = libc.func('int posix_fadvise(int fd, int64 off, int64 len, int advice)');
      let sync_file_range = null;
      let sync_file_range_async = null;
      try {
        sync_file_range = libc.func('int sync_file_range(int fd, int64 off, int64 len, uint flags)');
        // Koffi async wrapper for writeback operations to prevent event loop stalls
        if (typeof sync_file_range.async === 'function') {
          sync_file_range_async = sync_file_range.async;
        }
      } catch {
        // sync_file_range is Linux-specific (may not exist on some musl or bionic targets)
      }

      return {
        libc,
        posix_fadvise,
        sync_file_range,
        sync_file_range_async
      };
    } catch {
      return null;
    }
  })(),

  // macOS (libSystem.B.dylib)
  darwin: (() => {
    if (!koffi || process.platform !== 'darwin') return null;
    const sys = loadLib(['/usr/lib/libSystem.B.dylib', 'libSystem.B.dylib']);
    if (!sys) return null;

    try {
      // fcntl is variadic in C; bind separate prototypes for pointer vs int argument
      const fcntlPtr = sys.func('int fcntl(int fd, int cmd, void *arg)');
      const fcntlInt = sys.func('int fcntl(int fd, int cmd, int arg)');
      return {
        sys,
        fcntlPtr,
        fcntlInt
      };
    } catch {
      return null;
    }
  })(),

  // Windows (kernel32.dll)
  win32: (() => {
    if (!koffi || process.platform !== 'win32') return null;
    const k32 = loadLib(['kernel32.dll']);
    if (!k32) return null;

    try {
      const SetThreadExecutionState = k32.func('uint32 SetThreadExecutionState(uint32 flags)');
      // MEMORYSTATUSEX struct representation if needed
      return {
        k32,
        SetThreadExecutionState
      };
    } catch {
      return null;
    }
  })()
};
