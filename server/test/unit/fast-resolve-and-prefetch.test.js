import test from 'node:test';
import assert from 'node:assert/strict';
import { sliceObject, extractPlayerSubtrees } from '../../src/slices/stream/workers/parser.worker.mjs';
import { ParserClient } from '../../src/slices/stream/parser-client.js';
import { getYoutubeiPool, prewarmUpstreamTransport } from '../../src/platform/upstream-pool.js';
import { PrefetchCoordinator } from '../../src/slices/stream/prefetch.js';

test('L4 Selective JSON Extraction: sliceObject extracts nested object without full parse', () => {
  const json = JSON.stringify({
    responseContext: { visitorData: 'test-visitor-123' },
    playabilityStatus: { status: 'OK' },
    streamingData: {
      adaptiveFormats: [{ itag: 251, url: 'https://example.com/audio.opus' }],
    },
    hugeCruft: Array.from({ length: 1000 }, (_, i) => ({ id: i, text: 'dummy storyboard data' })),
  });

  const sd = sliceObject(json, '"streamingData"');
  assert.ok(sd);
  const parsedSd = JSON.parse(sd);
  assert.equal(parsedSd.adaptiveFormats[0].itag, 251);

  const ps = sliceObject(json, '"playabilityStatus"');
  assert.ok(ps);
  assert.equal(JSON.parse(ps).status, 'OK');
});

test('L4 Selective JSON Extraction: extractPlayerSubtrees extracts required subtrees', () => {
  const json = JSON.stringify({
    responseContext: { visitorData: 'test-vd-456' },
    playabilityStatus: { status: 'OK' },
    streamingData: {
      adaptiveFormats: [{ itag: 251, url: 'https://example.com/audio.opus' }],
    },
    videoDetails: { videoId: 'abc1234', title: 'Test Song' },
  });

  const res = extractPlayerSubtrees(json);
  assert.equal(res.visitorData, 'test-vd-456');
  assert.equal(res.playabilityStatus?.status, 'OK');
  assert.equal(res.streamingData?.adaptiveFormats?.length, 1);
  assert.equal(res.videoDetails?.videoId, 'abc1234');
});

test('L4 ParserClient: dispatches extraction to worker or fallback seamlessly', async () => {
  const client = new ParserClient();
  const json = JSON.stringify({
    playabilityStatus: { status: 'OK' },
    streamingData: { adaptiveFormats: [{ itag: 251, url: 'https://cdn.example.com/stream' }] },
    visitorData: 'vd-direct',
  });

  const res = await client.extractPlayer(json);
  assert.equal(res.playabilityStatus?.status, 'OK');
  assert.equal(res.streamingData?.adaptiveFormats[0].itag, 251);
  client.close();
});

test('L3 Upstream Pool: getYoutubeiPool returns initialized undici Pool', async () => {
  const pool = getYoutubeiPool();
  assert.ok(pool);
  assert.equal(typeof pool.request, 'function');
  await prewarmUpstreamTransport();
});

test('L2 Deep Lookahead Prefetch: prefetchQueueWindow prefetches lookahead window with stage separation', async () => {
  const descriptorsResolved = [];
  const bytesWarmed = [];

  const mockResolver = {
    resolve: async (videoId) => {
      descriptorsResolved.push(videoId);
      return { videoId, itag: 251, targetUrl: 'https://example.com' };
    },
  };

  const mockCache = {
    hasRange: () => false,
    hasComplete: () => false,
    warmBlock: async (desc) => {
      bytesWarmed.push(desc.videoId);
      return { status: 'warmed', bytesWarmed: 256 * 1024 };
    },
  };

  const coordinator = new PrefetchCoordinator({
    resolver: mockResolver,
    cache: mockCache,
  });

  const queue = ['vid-1', 'vid-2', 'vid-3', 'vid-4'];
  const results = await coordinator.prefetchQueueWindow(queue, { lookahead: 3 });

  // Lookahead = 3: vid-1 gets stage 2 (bytes + descriptor), vid-2 & vid-3 get stage 1 (descriptor)
  assert.equal(results.length, 3);
  assert.deepEqual(bytesWarmed, ['vid-1'], 'Only top of lookahead should warm bytes');
  assert.deepEqual(descriptorsResolved, ['vid-1', 'vid-2', 'vid-3'], 'All 3 items in window resolved');
});
