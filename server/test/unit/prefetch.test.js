import test from 'node:test';
import assert from 'node:assert/strict';
import { PrefetchCoordinator } from '../../src/slices/stream/prefetch.js';

class MockResolver {
  constructor() {
    this.resolves = 0;
  }
  async resolve(videoId) {
    this.resolves++;
    return {
      videoId,
      targetUrl: `https://googlevideo.com/videoplayback?id=${videoId}`,
      itag: 251,
      mimeType: 'audio/webm; codecs="opus"',
      contentLength: 4000000,
    };
  }
}

class MockCache {
  constructor() {
    this.completed = new Set();
    this.writers = new Map();
  }
  hasComplete(videoId, itag) {
    return this.completed.has(`${videoId}_${itag}`) ? `/tmp/${videoId}_${itag}.bin` : null;
  }
  getActiveWriter(videoId, itag) {
    return this.writers.get(`${videoId}_${itag}`);
  }
  async startWriter(desc) {
    const writer = {
      bytesAvailable: 300000,
      isComplete: false,
    };
    this.writers.set(`${desc.videoId}_${desc.itag}`, writer);
    return writer;
  }
}

test('PrefetchCoordinator: Stage 1 (Descriptor resolve)', async () => {
  const resolver = new MockResolver();
  const cache = new MockCache();
  const coordinator = new PrefetchCoordinator({ resolver, cache });

  const result = await coordinator.prefetchDescriptor('test-video-1');
  assert.equal(result.status, 'resolved');
  assert.equal(result.videoId, 'test-video-1');
  assert.equal(result.descriptor.itag, 251);
  assert.equal(resolver.resolves, 1);
});

test('PrefetchCoordinator: Stage 2 (Warm bytes)', async () => {
  const resolver = new MockResolver();
  const cache = new MockCache();
  const coordinator = new PrefetchCoordinator({ resolver, cache });

  const result = await coordinator.prefetchBytes('test-video-2', { maxWarmBytes: 256 * 1024 });
  assert.equal(result.status, 'warmed');
  assert.equal(result.videoId, 'test-video-2');
  assert.ok(result.bytes >= 256 * 1024);
});

test('PrefetchCoordinator: Preemption on live stream pressure (>50%)', async () => {
  const resolver = new MockResolver();
  const cache = new MockCache();
  const mockLiveSemaphore = {
    globalCount: 20,
    maxGlobal: 24, // 20/24 > 50%
  };

  const coordinator = new PrefetchCoordinator({
    resolver,
    cache,
    liveSemaphore: mockLiveSemaphore,
  });

  const s1 = await coordinator.prefetchDescriptor('test-video-3');
  assert.equal(s1.status, 'preempted');
  assert.equal(s1.reason, 'system_pressure');

  const s2 = await coordinator.prefetchBytes('test-video-3');
  assert.equal(s2.status, 'preempted');
});

test('PrefetchCoordinator: Cancel active prefetch on skip', () => {
  const resolver = new MockResolver();
  const cache = new MockCache();
  const coordinator = new PrefetchCoordinator({ resolver, cache });

  const controller = new AbortController();
  coordinator.activeJobs.set('skip-target', { stage: 2, controller, startedAt: Date.now() });

  assert.equal(coordinator.activeJobs.has('skip-target'), true);
  coordinator.cancel('skip-target');
  assert.equal(coordinator.activeJobs.has('skip-target'), false);
  assert.equal(controller.signal.aborted, true);
});
