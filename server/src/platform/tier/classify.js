/**
 * Streamify Elastic Tiering - Stage 2: Classification
 * 
 * Classifies the system into Micro, Balanced, or Workstation tier.
 * Invariant: Android is capped at Balanced to avoid flash wear and lmkd thrashing.
 */

export const TIERS = Object.freeze({
  MICRO: 'Micro',
  BALANCED: 'Balanced',
  WORKSTATION: 'Workstation'
});

const GIB = 1024 * 1024 * 1024;

/**
 * Classify probed hardware into a sizing tier.
 * @param {import('./probe.js').probe} probeResult
 * @returns {{ tier: 'Micro' | 'Balanced' | 'Workstation', reason: string, probe: object }}
 */
export function classify(probeResult) {
  const { effectiveMem, availMem, parallelism, isAndroid } = probeResult;

  // Rule 1: Micro
  // effectiveMem <= 3GB || parallelism <= 2 || (Android && availMem <= 1.5GB)
  if (
    effectiveMem <= 3 * GIB ||
    parallelism <= 2 ||
    (isAndroid && availMem <= 1.5 * GIB)
  ) {
    let reason = 'effectiveMem <= 3GB';
    if (parallelism <= 2) reason = `parallelism (${parallelism}) <= 2`;
    else if (isAndroid && availMem <= 1.5 * GIB) reason = 'Android with availMem <= 1.5GB';

    return {
      tier: TIERS.MICRO,
      reason,
      probe: probeResult
    };
  }

  // Rule 2: Android cap at Balanced
  if (isAndroid) {
    return {
      tier: TIERS.BALANCED,
      reason: 'Android capped at Balanced tier to preserve flash wear and prevent lmkd thrashing',
      probe: probeResult
    };
  }

  // Rule 3: Balanced vs Workstation for desktop / server
  if (effectiveMem <= 8 * GIB) {
    return {
      tier: TIERS.BALANCED,
      reason: 'effectiveMem <= 8GB',
      probe: probeResult
    };
  }

  // Workstation: effectiveMem > 8GB && !isAndroid
  return {
    tier: TIERS.WORKSTATION,
    reason: `Workstation: effectiveMem > 8GB (${(effectiveMem / GIB).toFixed(1)} GB) and non-Android`,
    probe: probeResult
  };
}
