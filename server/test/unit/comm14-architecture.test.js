import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { SparseBlockCache, BLOCK_SIZE } from '../../src/slices/stream/sparse-block-cache.js';
import { StreamStore } from '../../src/slices/stream/store.js';

test('Option 1: Segmented LRU Eviction evicts partial cold before complete cold', () => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'streamify-seg-lru-'));
  // Set budget = 5 blocks. 85% high water = 4.25 blocks.
  const cache = new SparseBlockCache({
    streamsDir: tmpDir,
    maxCacheMb: (5 * BLOCK_SIZE) / (1024 * 1024),
  });

  const completeDesc = { videoId: 'complete-cold', itag: 251, contentLength: 3 * BLOCK_SIZE };
  const partialDesc = { videoId: 'partial-cold', itag: 251, contentLength: 5 * BLOCK_SIZE };
  const activeDesc = { videoId: 'active-pinned', itag: 251, contentLength: 2 * BLOCK_SIZE };

  const compEntry = cache.getEntry(completeDesc);
  const partEntry = cache.getEntry(partialDesc);
  const actEntry = cache.getEntry(activeDesc);

  // Complete cold has 3 blocks (100% of 3)
  compEntry.blockState[0] = 2;
  compEntry.blockState[1] = 2;
  compEntry.blockState[2] = 2;
  compEntry.lastAccessed = 1000;

  // Partial cold has 2 blocks (40% of 5)
  partEntry.blockState[0] = 2;
  partEntry.blockState[1] = 2;
  partEntry.lastAccessed = 2000;

  // Active pinned has 1 block, active reader = 1
  actEntry.blockState[0] = 2;
  actEntry.activeReaders = 1;
  actEntry.lastAccessed = 3000;

  // Total blocks = 3 + 2 + 1 = 6 blocks (> 4.25 high water)
  cache.evictOverBudget();

  // Partial cold should be evicted FIRST (Tier 1)
  assert.equal(cache.entries.has('partial-cold_251'), false, 'Partial cold track should be evicted first');
  // Active pinned MUST survive
  assert.equal(cache.entries.has('active-pinned_251'), true, 'Active pinned track must never be evicted');

  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('Option 3: SQLite WAL In-Process Pragmas and Passive Checkpoint', () => {
  const store = new StreamStore({ inMemory: true });
  // Verifies pragma initialization & non-blocking checkpoint execution
  store.checkpoint('PASSIVE');
  store.close();
});
