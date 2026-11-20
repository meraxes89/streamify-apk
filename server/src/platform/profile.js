import os from 'node:os';

export function resolveProfile(env = process.env) {
  const isTermux = Boolean(env.TERMUX_VERSION || (env.PREFIX && env.PREFIX.includes('com.termux')));
  const totalMemBytes = os.totalmem();
  const totalMemMb = Math.floor(totalMemBytes / (1024 * 1024));
  const cpuCount = os.cpus()?.length || 1;

  const requested = (env.STREAMIFY_PROFILE || 'auto').toLowerCase();

  let name;
  if (['low', 'balanced', 'high'].includes(requested)) {
    name = requested;
  } else {
    // Auto profile selection
    if (isTermux && totalMemMb <= 3500) {
      name = 'low';
    } else if (totalMemMb <= 4500) {
      name = 'balanced';
    } else {
      name = 'high';
    }
  }

  const profiles = {
    low: {
      name: 'low',
      maxOldSpaceSizeMb: 192,
      maxStreams: 12,
      metadataLruMb: 8,
      diskCacheMb: 256,
    },
    balanced: {
      name: 'balanced',
      maxOldSpaceSizeMb: 384,
      maxStreams: 24,
      metadataLruMb: 24,
      diskCacheMb: 768,
    },
    high: {
      name: 'high',
      maxOldSpaceSizeMb: 768,
      maxStreams: 48,
      metadataLruMb: 64,
      diskCacheMb: 1536,
    },
  };

  const selected = profiles[name] || profiles.balanced;
  return {
    ...selected,
    isTermux,
    totalMemMb,
    cpuCount,
  };
}
