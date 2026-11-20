import test from 'node:test';
import assert from 'node:assert/strict';
import { StreamSemaphore } from '../../src/lib/semaphore.js';

test('Lib: StreamSemaphore limits', () => {
  const sem = new StreamSemaphore({ maxGlobal: 2, maxPerIp: 1 });

  const p1 = sem.acquire('1.1.1.1');
  assert.equal(p1.allowed, true);

  // Same IP blocked by maxPerIp
  const p1Duplicate = sem.acquire('1.1.1.1');
  assert.equal(p1Duplicate.allowed, false);
  assert.equal(p1Duplicate.retryAfter, 5);

  // Different IP allowed
  const p2 = sem.acquire('2.2.2.2');
  assert.equal(p2.allowed, true);

  // Third IP blocked by maxGlobal
  const p3 = sem.acquire('3.3.3.3');
  assert.equal(p3.allowed, false);

  // Release slot
  p1.release();

  // Now third IP can acquire
  const p3Retry = sem.acquire('3.3.3.3');
  assert.equal(p3Retry.allowed, true);

  p2.release();
  p3Retry.release();

  assert.equal(sem.getMetrics().activeStreams, 0);
});
