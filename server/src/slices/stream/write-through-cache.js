import { SparseBlockCache } from './sparse-block-cache.js';

/**
 * Backward compatibility wrapper forwarding WriteThroughCache to SparseBlockCache (P0)
 */
export class WriteThroughCache extends SparseBlockCache {}
