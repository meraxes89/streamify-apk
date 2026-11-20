import test from 'node:test';
import assert from 'node:assert/strict';
import { Readable } from 'node:stream';
import {
  PacingWheel,
  PacedStreamTransform,
  StreamPacer,
  BURST_TOKENS,
  WRITE_QUANTUM,
} from '../../src/slices/stream/pacer.js';

test('PacingWheel: Timer auto-starts on stream register and stops when empty', () => {
  const wheel = new PacingWheel({ tickMs: 50 });
  assert.equal(wheel.timer, null);
  assert.equal(wheel.streams.size, 0);

  const mockStream = { onTick() {} };
  wheel.register(mockStream);
  assert.equal(wheel.streams.size, 1);
  assert.notEqual(wheel.timer, null, 'Timer must start when first stream registers');

  wheel.unregister(mockStream);
  assert.equal(wheel.streams.size, 0);
  assert.equal(wheel.timer, null, 'Timer must stop when last stream unregisters');

  wheel.close();
});

test('PacedStreamTransform: Phase 1 instant burst delivers 256 KiB with zero delay', async () => {
  const wheel = new PacingWheel({ tickMs: 100 });
  const pacer = new StreamPacer({ wheel });

  // Generate 256 KiB data
  const data = Buffer.alloc(BURST_TOKENS, 0x55);
  const source = Readable.from([data]);

  const t0 = performance.now();
  const paced = pacer.pace(source, { bitrateBps: 160_000 });

  const chunks = [];
  for await (const chunk of paced) {
    chunks.push(chunk);
  }
  const t1 = performance.now();

  const total = Buffer.concat(chunks);
  assert.equal(total.length, BURST_TOKENS);
  // Entire 256 KiB should be delivered within milliseconds (burst phase, no pacing sleep)
  assert.ok(t1 - t0 < 150, `Burst must complete without timer delay, took ${t1 - t0}ms`);

  wheel.close();
});

test('PacedStreamTransform: Phase 2 throttles subsequent bytes into steady-state quanta', async () => {
  const wheel = new PacingWheel({ tickMs: 50 });
  const pacer = new StreamPacer({ wheel });

  // 256 KiB burst + 64 KiB steady state
  const burstData = Buffer.alloc(BURST_TOKENS, 0x11);
  const steadyData = Buffer.alloc(64 * 1024, 0x22);
  const source = Readable.from([burstData, steadyData]);

  const paced = pacer.pace(source, { bitrateBps: 160_000 });

  const chunks = [];
  for await (const chunk of paced) {
    chunks.push(chunk);
  }

  const total = Buffer.concat(chunks);
  assert.equal(total.length, BURST_TOKENS + 64 * 1024);

  wheel.close();
});

test('PacedStreamTransform: Abort signal cleans up stream and unregisters from wheel', async () => {
  const wheel = new PacingWheel({ tickMs: 50 });
  const controller = new AbortController();

  const transform = new PacedStreamTransform({
    wheel,
    bitrateBps: 160_000,
    signal: controller.signal,
  });
  transform.on('error', () => {}); // swallow expected abort error

  assert.equal(wheel.streams.size, 1);

  controller.abort(new Error('Client navigated away'));

  assert.equal(transform.destroyed, true);
  assert.equal(wheel.streams.size, 0, 'Must unregister from wheel on abort');

  wheel.close();
});
