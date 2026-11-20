import { spawnSync } from 'node:child_process';

let isLocked = false;

export function acquireWakeLock() {
  const isTermux = Boolean(process.env.TERMUX_VERSION || (process.env.PREFIX && process.env.PREFIX.includes('com.termux')));
  if (!isTermux || isLocked) return false;

  try {
    const res = spawnSync('termux-wake-lock', [], { stdio: 'ignore' });
    if (res.status === 0) {
      isLocked = true;
      return true;
    }
  } catch {
    // termux-api not installed or not in Termux; ignore gracefully
  }
  return false;
}

export function releaseWakeLock() {
  if (!isLocked) return false;
  try {
    spawnSync('termux-wake-unlock', [], { stdio: 'ignore' });
    isLocked = false;
    return true;
  } catch {
    // ignore gracefully
  }
  return false;
}
