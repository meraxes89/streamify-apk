import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { SparseBlockCache, BLOCK_SIZE, BLOCK_PRESENT } from '../../src/slices/stream/sparse-block-cache.js';

function createTempDir() {
  const dir = path.join(os.tmpdir(), `sparse_int_${Date.now()}_${Math.random().toString(36).slice(2)}`);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

test('P0 Verification: Coalesced single upstream fetch on multiple concurrent readers', async () => {
  const tmpDir = createTempDir();
  const cache = new SparseBlockCache({ streamsDir: tmpDir });

  let upstreamFetchCount = 0;
  const mockTargetUrl = 'https://mock.googlevideo.com/playback';

  // Intercept global fetch for mockTargetUrl
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (typeof url === 'string' && url.includes('mock.googlevideo.com')) {
      upstreamFetchCount++;
      // Return a 512 KiB stream (2 blocks) yielding in 32 KiB chunks
      const totalBytes = 2 * BLOCK_SIZE;
      let sentBytes = 0;
      const stream = new ReadableStream({
        async pull(controller) {
          if (sentBytes >= totalBytes) {
            controller.close();
            return;
          }
          await new Promise((r) => setTimeout(r, 10)); // simulate network delay
          const chunkSize = Math.min(32 * 1024, totalBytes - sentBytes);
          const chunk = new Uint8Array(chunkSize);
          chunk.fill(0xAA);
          sentBytes += chunkSize;
          controller.enqueue(chunk);
        },
      });

      return new Response(stream, {
        status: 206,
        headers: {
          'Content-Type': 'audio/webm',
          'Content-Range': `bytes 0-${totalBytes - 1}/${totalBytes}`,
          'Content-Length': String(totalBytes),
        },
      });
    }
    return originalFetch(url, opts);
  };

  try {
    const desc = {
      videoId: 'coalesce_vid',
      itag: 251,
      contentLength: 2 * BLOCK_SIZE,
      targetUrl: mockTargetUrl,
    };

    // Reader 1 starts reading bytes 0 to 256 KiB
    const stream1 = cache.createRangeStream(desc, 0, BLOCK_SIZE - 1);
    // Reader 2 simultaneously starts reading bytes 0 to 256 KiB
    const stream2 = cache.createRangeStream(desc, 0, BLOCK_SIZE - 1);

    const collectData = (s) =>
      new Promise((resolve, reject) => {
        const chunks = [];
        s.on('data', (c) => chunks.push(c));
        s.on('end', () => resolve(Buffer.concat(chunks)));
        s.on('error', reject);
      });

    const [data1, data2] = await Promise.all([collectData(stream1), collectData(stream2)]);

    assert.equal(data1.length, BLOCK_SIZE);
    assert.equal(data2.length, BLOCK_SIZE);
    assert.equal(data1[0], 0xAA);
    assert.equal(data2[0], 0xAA);

    // CRITICAL WIN: Only ONE upstream fetch was made for both concurrent readers!
    assert.equal(upstreamFetchCount, 1, 'Concurrent cold readers must share a SINGLE upstream fetch');

    // Block 0 should now be marked PRESENT on disk
    assert.equal(cache.hasRange(desc.videoId, desc.itag, 0, BLOCK_SIZE - 1), true);
  } finally {
    globalThis.fetch = originalFetch;
    await cache.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  }
});
