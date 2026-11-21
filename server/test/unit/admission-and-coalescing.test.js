import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import { AsyncAdmission, SaturatedError, TimeoutError, AbortError, PRIORITY } from '../../src/slices/stream/admission.js';
import { StreamCoalescer } from '../../src/slices/stream/coalescer.js';
import { StreamGateway } from '../../src/slices/stream/gateway.js';

test('L3 Admission: basic acquire and release under capacity', async () => {
  const adm = new AsyncAdmission({ globalMax: 2, perIpMax: 2 });
  const rel1 = await adm.acquire('1.1.1.1', PRIORITY.PLAYBACK);
  assert.equal(adm.globalActive, 1);
  assert.equal(adm.perIp.get('1.1.1.1'), 1);

  const rel2 = await adm.acquire('1.1.1.1', PRIORITY.PLAYBACK);
  assert.equal(adm.globalActive, 2);

  rel1();
  assert.equal(adm.globalActive, 1);
  assert.equal(adm.perIp.get('1.1.1.1'), 1);

  rel2();
  assert.equal(adm.globalActive, 0);
  assert.equal(adm.perIp.has('1.1.1.1'), false, 'Map entry cleaned up to prevent memory leak');
});

test('L3 Admission: per-IP cap queues excess requests', async () => {
  const adm = new AsyncAdmission({ globalMax: 10, perIpMax: 1, queueTimeoutMs: 1000 });
  const rel1 = await adm.acquire('2.2.2.2', PRIORITY.PLAYBACK);
  assert.equal(adm.globalActive, 1);

  let queuedResolved = false;
  const p2 = adm.acquire('2.2.2.2', PRIORITY.PLAYBACK).then((rel) => {
    queuedResolved = true;
    return rel;
  });

  assert.equal(queuedResolved, false);
  assert.equal(adm.queue.length, 1);

  // Release first request -> wakes queued request
  rel1();
  const rel2 = await p2;
  assert.equal(queuedResolved, true);
  assert.equal(adm.globalActive, 1);
  rel2();
  assert.equal(adm.globalActive, 0);
});

test('L3 Admission: Priority queue ordering (Playback > Seek > Prefetch)', async () => {
  const adm = new AsyncAdmission({ globalMax: 1, perIpMax: 1, maxQueue: 10 });
  const rel1 = await adm.acquire('1.1.1.1', PRIORITY.PLAYBACK);

  const order = [];
  const pPrefetch = adm.acquire('2.2.2.2', PRIORITY.PREFETCH).then((rel) => {
    order.push('prefetch');
    rel();
  });
  const pPlayback = adm.acquire('3.3.3.3', PRIORITY.PLAYBACK).then((rel) => {
    order.push('playback');
    rel();
  });
  const pSeek = adm.acquire('4.4.4.4', PRIORITY.SEEK).then((rel) => {
    order.push('seek');
    rel();
  });

  rel1();
  await Promise.all([pPrefetch, pPlayback, pSeek]);

  // Highest priority (playback = 0) should be serviced first, then seek (1), then prefetch (3)
  assert.deepEqual(order, ['playback', 'seek', 'prefetch']);
});

test('L3 Admission: SaturatedError thrown when queue exceeds maxQueue', async () => {
  const adm = new AsyncAdmission({ globalMax: 1, perIpMax: 1, maxQueue: 1 });
  const rel1 = await adm.acquire('1.1.1.1', PRIORITY.PLAYBACK);

  // Queue item 1
  const pQueued = adm.acquire('2.2.2.2', PRIORITY.PLAYBACK);

  // Queue is full -> reject immediately with SaturatedError (503)
  await assert.rejects(async () => {
    await adm.acquire('3.3.3.3', PRIORITY.PLAYBACK);
  }, SaturatedError);

  rel1();
  const relQueued = await pQueued;
  relQueued();
});

test('L3 Admission: Client abort cancels queued request cleanly', async () => {
  const adm = new AsyncAdmission({ globalMax: 1, perIpMax: 1, maxQueue: 10 });
  const rel1 = await adm.acquire('1.1.1.1', PRIORITY.PLAYBACK);

  const ac = new AbortController();
  const pQueued = adm.acquire('2.2.2.2', PRIORITY.PLAYBACK, ac.signal);

  assert.equal(adm.queue.length, 1);
  ac.abort();

  await assert.rejects(async () => {
    await pQueued;
  }, AbortError);

  assert.equal(adm.queue.length, 0, 'Cancelled item removed from queue');
  rel1();
});

test('L1/L2/L3 Gateway: 25 simultaneous burst requests coalesce without 503 errors', async () => {
  // Mock cache that simulates block arrival
  let hasBlock = false;
  const mockCache = {
    hasRange: () => hasBlock,
    readDirect: async () => Buffer.from('mock-bytes'),
    createRangeStream: () => {
      return Readable.from([Buffer.from('stream-chunk')]);
    },
    getEntry: () => ({
      blockState: new Uint8Array(10),
      activeFetches: new Map(),
      startFetchWindow: () => {
        let done = false;
        return {
          writtenOffset: 0,
          done: false,
          abortController: new AbortController(),
          waitFor: async (target) => {
            // Simulate 20ms upstream network fetch
            await new Promise((r) => setTimeout(r, 20));
            hasBlock = true;
          },
        };
      },
    }),
  };

  const mockResolver = {
    resolve: async () => ({
      videoId: 'burst-test',
      itag: 251,
      targetUrl: 'https://rr1---sn-xxx.googlevideo.com/videoplayback?id=burst-test',
      mimeType: 'audio/webm; codecs="opus"',
      contentLength: 4000000,
    }),
  };

  const admission = new AsyncAdmission({ globalMax: 4, perIpMax: 2 });
  const coalescer = new StreamCoalescer({ cache: mockCache, admission });
  const gateway = new StreamGateway({
    resolver: mockResolver,
    cache: mockCache,
    admission,
    coalescer,
    pacer: null,
  });

  // Dispatch 25 simultaneous requests from the same client IP
  const responses = [];
  const reqs = Array.from({ length: 25 }, (_, i) => {
    const req = {
      params: { videoId: 'burst-test' },
      query: {},
      headers: { range: 'bytes=0-1000' },
      ip: '192.168.1.100',
      raw: {
        signal: new AbortController().signal,
        on: () => {},
        socket: { setNoDelay: () => {} },
      },
    };

    let statusCode = 200;
    const sentHeaders = {};
    const reply = {
      header: (k, v) => { sentHeaders[k] = v; return reply; },
      status: (c) => { statusCode = c; return reply; },
      send: (body) => {
        responses.push({ statusCode, body });
      },
    };

    return gateway.handleRequest(req, reply);
  });

  await Promise.all(reqs);

  assert.equal(responses.length, 25);
  const statusCodes = responses.map((r) => r.statusCode);
  assert.equal(statusCodes.every((s) => s === 206), true, `All 25 should be 206, got: ${JSON.stringify(statusCodes)}`);
  assert.equal(statusCodes.includes(503), false, 'Zero 503 errors under burst');
  assert.equal(coalescer.totalLeaders, 1, 'Only 1 upstream leader fetch should be initiated');
  assert.equal(coalescer.totalFollowers, 24, '24 followers should coalesce onto leader');
});
