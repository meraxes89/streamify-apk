/**
 * Streamify Elastic Tiering - Entrypoint
 * 
 * Sizing & resource governance:
 * 1. probe()    - reads hardware, cgroups, and parallelism
 * 2. classify() - assigns Micro, Balanced, or Workstation
 * 3. size()     - computes dynamic bounds & V8 settings
 */

import { probe } from './probe.js';
import { classify, TIERS } from './classify.js';
import { size, SLOT_SIZE_BYTES } from './size.js';
import { TierGovernor } from './governor.js';

export { probe, classify, size, TierGovernor, TIERS, SLOT_SIZE_BYTES };

/**
 * Convenience helper to probe, classify, and size in a single call.
 * @param {object} [options]
 * @returns {object} Tier configuration
 */
export function getTierConfig(options = {}) {
  const p = probe(options.probeOverrides);
  const c = classify(p);
  return size(c, options.sizeOptions);
}

export default {
  probe,
  classify,
  size,
  TierGovernor,
  getTierConfig,
  TIERS,
  SLOT_SIZE_BYTES
};
