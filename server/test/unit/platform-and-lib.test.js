import test from 'node:test';
import assert from 'node:assert/strict';
import { resolveProfile } from '../../src/platform/profile.js';
import { resolveDataDir } from '../../src/platform/data-dir.js';
import { parseRange } from '../../src/lib/range.js';
import { LruCache } from '../../src/lib/cache.js';
import { SingleFlight } from '../../src/lib/single-flight.js';

test('Platform: resolveProfile', () => {
  const profileAuto = resolveProfile({});
  assert.ok(['low', 'balanced', 'high'].includes(profileAuto.name));
  assert.ok(profileAuto.maxOldSpaceSizeMb > 0);
  assert.ok(profileAuto.maxStreams > 0);

  const profileLow = resolveProfile({ STREAMIFY_PROFILE: 'low' });
  assert.equal(profileLow.name, 'low');
  assert.equal(profileLow.maxStreams, 12);

  const profileHigh = resolveProfile({ STREAMIFY_PROFILE: 'high' });
  assert.equal(profileHigh.name, 'high');
  assert.equal(profileHigh.maxStreams, 48);
});

test('Platform: resolveDataDir', () => {
  const dataDir = resolveDataDir();
  assert.ok(typeof dataDir.root === 'string' && dataDir.root.length > 0);
  assert.ok(typeof dataDir.streams === 'string');
  assert.ok(typeof dataDir.thumbs === 'string');
  assert.ok(typeof dataDir.metadata === 'string');
});

test('Lib: parseRange', () => {
  const total = 5000;

  // No header
  assert.equal(parseRange(undefined, total), null);

  // Exact range: bytes=0-1 (Safari probe)
  const probe = parseRange('bytes=0-1', total);
  assert.deepEqual(probe, {
    satisfiable: true,
    start: 0,
    end: 1,
    length: 2,
    contentRange: 'bytes 0-1/5000',
  });

  // Range within bounds
  const standard = parseRange('bytes=100-199', total);
  assert.deepEqual(standard, {
    satisfiable: true,
    start: 100,
    end: 199,
    length: 100,
    contentRange: 'bytes 100-199/5000',
  });

  // Open-ended range
  const openEnded = parseRange('bytes=4000-', total);
  assert.deepEqual(openEnded, {
    satisfiable: true,
    start: 4000,
    end: 4999,
    length: 1000,
    contentRange: 'bytes 4000-4999/5000',
  });

  // Suffix range
  const suffix = parseRange('bytes=-500', total);
  assert.deepEqual(suffix, {
    satisfiable: true,
    start: 4500,
    end: 4999,
    length: 500,
    contentRange: 'bytes 4500-4999/5000',
  });

  // Unsatisfiable ranges
  assert.equal(parseRange('bytes=6000-7000', total)?.satisfiable, false);
  assert.equal(parseRange('bytes=500-200', total)?.satisfiable, false);
  assert.equal(parseRange('invalid-range', total)?.satisfiable, false);
});

test('Lib: LruCache', async () => {
  const cache = new LruCache({ maxItems: 2, defaultTtlMs: 50 });

  cache.set('a', 1);
  cache.set('b', 2);
  assert.equal(cache.get('a'), 1);
  assert.equal(cache.get('b'), 2);

  // Evicts oldest (a was accessed, b was accessed)
  cache.set('c', 3);
  assert.equal(cache.has('a'), false); // 'a' was oldest
  assert.equal(cache.get('b'), 2);
  assert.equal(cache.get('c'), 3);

  // TTL expiration
  await new Promise((r) => setTimeout(r, 60));
  assert.equal(cache.get('b'), undefined);
});

test('Lib: SingleFlight', async () => {
  const sf = new SingleFlight();
  let executionCount = 0;

  const task = async () => {
    executionCount++;
    await new Promise((r) => setTimeout(r, 30));
    return 'result';
  };

  const [res1, res2, res3] = await Promise.all([
    sf.do('key1', task),
    sf.do('key1', task),
    sf.do('key1', task),
  ]);

  assert.equal(res1, 'result');
  assert.equal(res2, 'result');
  assert.equal(res3, 'result');
  assert.equal(executionCount, 1, 'task should only execute once across parallel calls');
  assert.equal(sf.size(), 0, 'single flight should be empty after resolution');
});
