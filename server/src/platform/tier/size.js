/**
 * Streamify Elastic Tiering - Stage 3: Sizing Formulas & Bounds
 * 
 * Computes boot ceilings and elastic dynamic bounds for:
 * - Buffer slots (64 KiB slots)
 * - Worker thread count & heap limits
 * - Worker idle keep-warm timeouts
 * - Storage disk cache quotas
 * - Kernel readahead windows
 * - V8 semi-space and old-space bounds
 * - Media network pacing policies
 */

import { TIERS } from './classify.js';

const MIB = 1024 * 1024;
const GIB = 1024 * 1024 * 1024;
export const SLOT_SIZE_BYTES = 64 * 1024; // 64 KiB

/**
 * Clamp a number between min and max.
 */
function clamp(val, min, max) {
  return Math.min(Math.max(val, min), max);
}

/**
 * Size resources based on tier and probed hardware.
 * @param {{ tier: string, probe: object }} classification
 * @param {object} [options]
 * @param {number} [options.freeDiskBytes]
 * @returns {object} Resource sizing specification
 */
export function size(classification, options = {}) {
  const { tier, probe } = classification;
  const parallelism = probe.parallelism || 1;
  const isAndroid = Boolean(probe.isAndroid);
  const effectiveMemMb = Math.floor(probe.effectiveMem / MIB);

  let maxBufferSlots = 64;
  let minBufferSlots = 16;
  let maxWorkers = 4;
  let workerHeapCapMb = 128;
  let idleTeardownSeconds = 30;
  let tierDiskCapMb = 1024;
  let readaheadBytes = 512 * 1024;
  let semiSpaceMb = 16;
  let oldSpaceMb = 512;
  let pacingPolicy = 'standard';

  switch (tier) {
    case TIERS.MICRO:
      maxBufferSlots = 32; // 2 MiB
      minBufferSlots = 16; // 1 MiB
      maxWorkers = clamp(parallelism - 1, 1, 2);
      workerHeapCapMb = 64;
      idleTeardownSeconds = 10;
      tierDiskCapMb = 256;
      readaheadBytes = 128 * 1024; // 128 KiB
      semiSpaceMb = 8;
      oldSpaceMb = Math.min(256, Math.max(128, Math.floor(effectiveMemMb * 0.25)));
      pacingPolicy = 'aggressive';
      break;

    case TIERS.BALANCED:
      maxBufferSlots = 64; // 4 MiB
      minBufferSlots = 16; // 1 MiB
      maxWorkers = clamp(parallelism - 1, 1, 4);
      workerHeapCapMb = 128;
      idleTeardownSeconds = 30;
      tierDiskCapMb = 1024;
      readaheadBytes = 512 * 1024; // 512 KiB
      semiSpaceMb = 16;
      oldSpaceMb = Math.min(512, Math.max(256, Math.floor(effectiveMemMb * 0.25)));
      pacingPolicy = 'standard';
      break;

    case TIERS.WORKSTATION:
      maxBufferSlots = 256; // 16 MiB
      minBufferSlots = 32;  // 2 MiB
      maxWorkers = clamp(parallelism - 1, 1, 8);
      workerHeapCapMb = 256;
      idleTeardownSeconds = 300;
      tierDiskCapMb = 4096;
      readaheadBytes = 2 * 1024 * 1024; // 2 MiB
      semiSpaceMb = 32;
      oldSpaceMb = Math.min(2048, Math.max(1024, Math.floor(effectiveMemMb * 0.25)));
      pacingPolicy = 'off';
      break;
  }

  // Calculate actual disk cache budget
  let effectiveDiskCacheBytes = tierDiskCapMb * MIB;
  if (options.freeDiskBytes && options.freeDiskBytes > 0) {
    const proportionalFree = options.freeDiskBytes * 0.10;
    const androidFactor = isAndroid ? 0.5 : 1.0;
    effectiveDiskCacheBytes = Math.floor(
      Math.min(tierDiskCapMb * MIB, proportionalFree) * androidFactor
    );
  }

  /**
   * Elastic buffer slot calculator given active streams.
   * Formula: clamp(16, activeStreams * 4 + 8, tierMax)
   * @param {number} activeStreams
   * @returns {number} slots
   */
  const computeBufferSlots = (activeStreams = 1) => {
    return clamp(activeStreams * 4 + 8, minBufferSlots, maxBufferSlots);
  };

  return {
    tier,
    probe,
    bufferPool: {
      slotSizeBytes: SLOT_SIZE_BYTES,
      minSlots: minBufferSlots,
      maxSlots: maxBufferSlots,
      minBytes: minBufferSlots * SLOT_SIZE_BYTES,
      maxBytes: maxBufferSlots * SLOT_SIZE_BYTES,
      computeSlots: computeBufferSlots
    },
    workers: {
      maxWorkers,
      minWorkers: 1, // Always keep 1 warm
      heapCapMb: workerHeapCapMb,
      idleTeardownSeconds
    },
    storage: {
      tierCapMb: tierDiskCapMb,
      effectiveDiskCacheBytes,
      readaheadBytes,
      diskClass: probe.diskClass
    },
    v8: {
      semiSpaceMb,
      oldSpaceMb,
      nodeOptions: `--max-semi-space-size=${semiSpaceMb} --max-old-space-size=${oldSpaceMb}`
    },
    network: {
      pacingPolicy
    }
  };
}
