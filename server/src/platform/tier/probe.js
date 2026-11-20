/**
 * Streamify Elastic Tiering - Stage 1: Hardware & Constraint Probe
 * 
 * Inspects effective memory (accounting for Linux/Android cgroups),
 * core parallelism, and platform storage class.
 */

import os from 'node:os';
import fs from 'node:fs';

/**
 * Read cgroup v1/v2 memory limit if present.
 * @returns {{ maxBytes: number | null, currentBytes: number | null }}
 */
function readCgroupMemory() {
  try {
    // cgroup v2
    const v2Max = '/sys/fs/cgroup/memory.max';
    const v2Cur = '/sys/fs/cgroup/memory.current';
    if (fs.existsSync(v2Max)) {
      const maxRaw = fs.readFileSync(v2Max, 'utf8').trim();
      const curRaw = fs.existsSync(v2Cur) ? fs.readFileSync(v2Cur, 'utf8').trim() : null;
      if (maxRaw !== 'max') {
        const maxBytes = parseInt(maxRaw, 10);
        const currentBytes = curRaw ? parseInt(curRaw, 10) : 0;
        if (Number.isFinite(maxBytes) && maxBytes > 0) {
          return { maxBytes, currentBytes };
        }
      }
    }

    // cgroup v1
    const v1Max = '/sys/fs/cgroup/memory/memory.limit_in_bytes';
    const v1Cur = '/sys/fs/cgroup/memory/memory.usage_in_bytes';
    if (fs.existsSync(v1Max)) {
      const maxRaw = fs.readFileSync(v1Max, 'utf8').trim();
      const maxBytes = parseInt(maxRaw, 10);
      // Large 64-bit int (e.g. 0x7FFFFFFFFFFFF000) indicates no limit
      if (Number.isFinite(maxBytes) && maxBytes < 0x7FFFFFFFF0000000) {
        const curRaw = fs.existsSync(v1Cur) ? fs.readFileSync(v1Cur, 'utf8').trim() : null;
        const currentBytes = curRaw ? parseInt(curRaw, 10) : 0;
        return { maxBytes, currentBytes };
      }
    }
  } catch {}

  return { maxBytes: null, currentBytes: null };
}

/**
 * Probe disk storage class (nvme | ssd | hdd | flash).
 * @param {boolean} isAndroid
 * @returns {'nvme' | 'ssd' | 'hdd' | 'flash'}
 */
function probeDiskClass(isAndroid) {
  if (isAndroid) {
    return 'flash';
  }

  if (process.platform === 'linux') {
    try {
      const sysBlock = '/sys/block';
      if (fs.existsSync(sysBlock)) {
        const devices = fs.readdirSync(sysBlock);
        for (const dev of devices) {
          if (dev.startsWith('nvme')) return 'nvme';
          const rotPath = `${sysBlock}/${dev}/queue/rotational`;
          if (fs.existsSync(rotPath)) {
            const rot = fs.readFileSync(rotPath, 'utf8').trim();
            if (rot === '0') return 'ssd';
            if (rot === '1') return 'hdd';
          }
        }
      }
    } catch {}
  }

  // Default desktop storage assumption
  return 'ssd';
}

/**
 * Execute hardware and cgroup probe.
 * @param {object} [overrides]
 * @returns {object} Hardware and budget profile
 */
export function probe(overrides = {}) {
  const isAndroid = overrides.isAndroid ?? Boolean(
    process.platform === 'android' ||
    process.env.TERMUX_VERSION ||
    (process.env.PREFIX && process.env.PREFIX.includes('com.termux'))
  );

  const totalMem = overrides.totalMem ?? os.totalmem();
  const freeMem = overrides.freeMem ?? os.freemem();

  const cgroup = overrides.cgroup ?? readCgroupMemory();
  const effectiveMem = cgroup.maxBytes && cgroup.maxBytes < totalMem
    ? cgroup.maxBytes
    : totalMem;

  const availMem = cgroup.maxBytes && cgroup.currentBytes != null
    ? Math.max(0, cgroup.maxBytes - cgroup.currentBytes)
    : freeMem;

  const parallelism = overrides.parallelism ?? (
    typeof os.availableParallelism === 'function'
      ? os.availableParallelism()
      : os.cpus().length
  );

  const diskClass = overrides.diskClass ?? probeDiskClass(isAndroid);

  return {
    effectiveMem,
    availMem,
    totalMem,
    freeMem,
    cgroupLimit: cgroup.maxBytes,
    parallelism: Math.max(1, parallelism),
    isAndroid,
    diskClass,
    platform: overrides.platform ?? process.platform
  };
}
