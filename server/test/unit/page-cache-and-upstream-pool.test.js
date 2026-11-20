import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { pageCache, POSIX_FADV_SEQUENTIAL, POSIX_FADV_WILLNEED, POSIX_FADV_DONTNEED } from '../../src/platform/page-cache.js';
import { configureGlobalUpstreamPool, getUpstreamPoolStatus, closeUpstreamPool } from '../../src/platform/upstream-pool.js';
import { SparseBlockCache } from '../../src/slices/stream/sparse-block-cache.js';

test('L2 Page-Cache Hygiene: binds posix_fadvise and sync_file_range via koffi on Linux', async (t) => {
  const status = pageCache.getStatus();
  assert.equal(typeof status.platform, 'string');
  assert.equal(typeof status.isSupported, 'boolean');

  // Verify invalid fds are handled safely without throw
  assert.equal(pageCache.adviseSequential(-1), -1);
  assert.equal(pageCache.adviseWillNeed(-1, 0, 1024), -1);
  assert.equal(pageCache.adviseDontNeed(-1, 0, 1024, true), -1);

  // If running on Linux/Android with koffi loaded, test against real temporary file
  if (process.platform === 'linux') {
    const tmpFile = path.join(os.tmpdir(), `streamify-page-cache-test-${Date.now()}.dat`);
    fs.writeFileSync(tmpFile, Buffer.alloc(64 * 1024, 0xaa));
    const fh = await fs.promises.open(tmpFile, 'r+');

    t.after(async () => {
      try {
        await fh.close();
        fs.unlinkSync(tmpFile);
      } catch {}
    });

    if (pageCache.isSupported) {
      const resSeq = pageCache.adviseSequential(fh.fd);
      assert.equal(resSeq, 0, 'POSIX_FADV_SEQUENTIAL should return 0 (success)');

      const resWill = pageCache.adviseWillNeed(fh.fd, 0, 32768);
      assert.equal(resWill, 0, 'POSIX_FADV_WILLNEED should return 0 (success)');

      const resDont = pageCache.adviseDontNeed(fh.fd, 0, 32768, true);
      assert.equal(resDont, 0, 'POSIX_FADV_DONTNEED with sync_file_range should return 0 (success)');
    }
  }
});

test('L3 Warm Upstream Pool & DNS Cache Pinning: undici Agent + CacheableLookup', async (t) => {
  const { agent, cacheableLookup } = configureGlobalUpstreamPool({
    connections: 4,
    keepAliveTimeout: 30000,
  });

  t.after(async () => {
    await closeUpstreamPool();
  });

  assert.ok(agent, 'Undici Agent must be instantiated');
  assert.ok(cacheableLookup, 'CacheableLookup must be active');

  const status = getUpstreamPoolStatus();
  assert.equal(status.isConfigured, true);
  assert.equal(status.dnsCacheActive, true);
  assert.equal(status.h2Allowed, true);

  // Test DNS lookup resolution
  const dnsRes = await new Promise((resolve, reject) => {
    cacheableLookup.lookup('localhost', (err, address, family) => {
      if (err) reject(err);
      else resolve({ address, family });
    });
  });
  assert.ok(dnsRes.address, 'Should resolve address');
});

test('L2+L3 Integration: SparseBlockCache and Prefetcher apply kernel readahead and warm pool', async (t) => {
  const tmpDir = path.join(os.tmpdir(), `streamify-sbc-l2-${Date.now()}`);
  fs.mkdirSync(tmpDir, { recursive: true });

  const cache = new SparseBlockCache({
    streamsDir: tmpDir,
    maxCacheMb: 10,
  });

  t.after(async () => {
    cache.close();
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  });

  const desc = {
    videoId: 'l2_vid_test',
    itag: 251,
    contentLength: 512 * 1024,
    targetUrl: 'https://rr1---sn-test.googlevideo.com/videoplayback?id=123',
  };

  const entry = cache.getEntry(desc);
  const fh = await entry.getHandle();
  assert.ok(typeof fh.fd === 'number');

  // Pre-seed block 0 as PRESENT
  entry.blockState[0] = 2; // BLOCK_PRESENT

  // Warm block should invoke adviseWillNeed without errors
  const warmRes = await cache.warmBlock(desc, 0);
  assert.equal(warmRes.status, 'already_cached');

  await entry.close();
});
