import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  ActiveFetch,
  SparseBlockCache,
  BLOCK_SIZE,
  BLOCK_EMPTY,
  BLOCK_FETCHING,
  BLOCK_PRESENT,
} from '../../src/slices/stream/sparse-block-cache.js';

function createTempDir() {
  const dir = path.join(os.tmpdir(), `sparse_test_${Date.now()}_${Math.random().toString(36).slice(2)}`);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

test('ActiveFetch: Byte-offset barrier advances and wakes waiters', async () => {
  const fetch = new ActiveFetch(0, 1024 * 1024);
  assert.equal(fetch.writtenOffset, 0);
  assert.equal(fetch.done, false);

  let waiter1Resolved = false;
  let waiter2Resolved = false;

  fetch.waitFor(100).then(() => {
    waiter1Resolved = true;
  });
  fetch.waitFor(500).then(() => {
    waiter2Resolved = true;
  });

  // Advance to 200: waiter 1 should resolve, waiter 2 should not
  fetch.advance(200);
  await new Promise((r) => setImmediate(r));
  assert.equal(waiter1Resolved, true);
  assert.equal(waiter2Resolved, false);

  // Advance to 600: waiter 2 resolves
  fetch.advance(600);
  await new Promise((r) => setImmediate(r));
  assert.equal(waiter2Resolved, true);

  fetch.finish();
  assert.equal(fetch.done, true);
});

test('ActiveFetch: Abort signal cleans up waiter', async () => {
  const fetch = new ActiveFetch(0, 1024 * 1024);
  const controller = new AbortController();

  const promise = fetch.waitFor(1000, controller.signal);
  controller.abort(new Error('User skip'));

  await assert.rejects(promise, /User skip/);
  assert.equal(fetch.waiters.length, 0);
});

test('SparseBlockCache: Block state and occupancy bitmask tracking', async () => {
  const tmpDir = createTempDir();
  const cache = new SparseBlockCache({ streamsDir: tmpDir });

  const desc = {
    videoId: 'test_vid_1',
    itag: 251,
    contentLength: 4 * BLOCK_SIZE, // 1 MiB = 4 blocks
    targetUrl: 'https://example.com/audio.webm',
  };

  const entry = cache.getEntry(desc);
  assert.equal(entry.numBlocks, 4);
  assert.equal(entry.blockState[0], BLOCK_EMPTY);
  assert.equal(entry.blockState[1], BLOCK_EMPTY);

  // Simulate writing 1 full block (256 KiB)
  const fh = await entry.getHandle();
  const blockBuf = Buffer.alloc(BLOCK_SIZE, 0x42);
  await fh.write(blockBuf, 0, BLOCK_SIZE, 0);
  entry.updatePresentBlocks(0, BLOCK_SIZE);

  assert.equal(entry.blockState[0], BLOCK_PRESENT);
  assert.equal(entry.blockState[1], BLOCK_EMPTY);
  assert.equal(cache.hasRange(desc.videoId, desc.itag, 0, BLOCK_SIZE - 1), true);
  assert.equal(cache.hasRange(desc.videoId, desc.itag, 0, BLOCK_SIZE + 10), false);
  assert.equal(cache.hasComplete(desc.videoId, desc.itag), null);

  // Direct read
  const readBuf = await cache.readDirect(desc, 0, 100);
  assert.equal(readBuf.length, 100);
  assert.equal(readBuf[0], 0x42);

  await cache.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('SparseBlockCache: Partial blocks survive skips without file unlinking', async () => {
  const tmpDir = createTempDir();
  const cache = new SparseBlockCache({ streamsDir: tmpDir });

  const desc = {
    videoId: 'skip_survive_vid',
    itag: 251,
    contentLength: 8 * BLOCK_SIZE,
    targetUrl: 'https://example.com/audio.webm',
  };

  const entry = cache.getEntry(desc);
  const fh = await entry.getHandle();

  // Write block 0 and block 1 (512 KiB)
  const data = Buffer.alloc(2 * BLOCK_SIZE, 0x77);
  await fh.write(data, 0, 2 * BLOCK_SIZE, 0);
  entry.updatePresentBlocks(0, 2 * BLOCK_SIZE);
  entry.saveIndexDebounced();

  await new Promise((r) => setTimeout(r, 250));

  assert.equal(entry.blockState[0], BLOCK_PRESENT);
  assert.equal(entry.blockState[1], BLOCK_PRESENT);
  assert.equal(entry.blockState[2], BLOCK_EMPTY);

  // Simulate skip / reader detachment
  cache.onReaderDetached(entry);

  // Close and recreate cache from disk: blocks 0 and 1 must persist
  await cache.close();

  const cache2 = new SparseBlockCache({ streamsDir: tmpDir });
  const reloadedEntry = cache2.getEntry(desc);

  assert.equal(reloadedEntry.blockState[0], BLOCK_PRESENT, 'Block 0 must survive restart/skip');
  assert.equal(reloadedEntry.blockState[1], BLOCK_PRESENT, 'Block 1 must survive restart/skip');
  assert.equal(reloadedEntry.blockState[2], BLOCK_EMPTY);

  await cache2.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('SparseBlockCache: Near-seek vs Far-seek detection', async () => {
  const tmpDir = createTempDir();
  const cache = new SparseBlockCache({ streamsDir: tmpDir });

  const desc = {
    videoId: 'seek_test_vid',
    itag: 251,
    contentLength: 16 * BLOCK_SIZE, // 4 MiB
    targetUrl: 'https://example.com/audio.webm',
  };

  const entry = cache.getEntry(desc);
  const fakeFetch = new ActiveFetch(0, 1024 * 1024);
  fakeFetch.advance(300 * 1024); // written up to 300 KiB
  entry.activeFetches.set(0, fakeFetch);

  // Near seek: 400 KiB is 100 KiB ahead of watermark (<= 512 KiB) -> shares fakeFetch
  const near = entry.findActiveFetchFor(400 * 1024);
  assert.equal(near, fakeFetch);

  // Far seek: 2 MiB is far ahead -> returns null, forcing new aligned ActiveFetch
  const far = entry.findActiveFetchFor(2 * 1024 * 1024);
  assert.equal(far, null);

  await cache.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

test('BlockRangeStream: AbortController abort cleanly closes stream without unhandled error', async () => {
  const tmpDir = createTempDir();
  const cache = new SparseBlockCache({ streamsDir: tmpDir });

  const desc = {
    videoId: 'abort_test_vid',
    itag: 251,
    contentLength: 4 * BLOCK_SIZE,
    targetUrl: 'https://example.com/audio.webm',
  };

  const controller = new AbortController();
  const stream = cache.createRangeStream(desc, 0, 4 * BLOCK_SIZE - 1, controller.signal);

  let closed = false;
  let errorEmitted = null;

  stream.on('close', () => {
    closed = true;
  });
  stream.on('error', (err) => {
    errorEmitted = err;
  });

  // Trigger abort immediately
  controller.abort(new DOMException('This operation was aborted', 'AbortError'));

  await new Promise((r) => setImmediate(r));

  assert.equal(closed, true, 'Stream must be closed on abort');
  assert.equal(errorEmitted, null, 'AbortError must not be emitted as an unhandled stream error');

  await cache.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });
});
