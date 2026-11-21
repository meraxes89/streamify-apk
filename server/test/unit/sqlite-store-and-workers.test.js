import test from 'node:test';
import assert from 'node:assert/strict';
import { StreamStore } from '../../src/slices/stream/store.js';
import { CipherClient } from '../../src/slices/stream/cipher-client.js';
import { AttestClient } from '../../src/slices/stream/attest-client.js';
import { StreamResolver } from '../../src/slices/stream/resolver.js';
import { LruCache } from '../../src/lib/cache.js';

test('Phase 2 StreamStore: node:sqlite URL caching, seek reuse, and LRU pruning', async (t) => {
  const store = new StreamStore({ inMemory: true });
  t.after(() => store.close());

  // 1. Set and get resolved URL
  const testDesc = {
    videoId: 'track_123',
    itag: 251,
    targetUrl: 'https://rr1---sn.googlevideo.com/videoplayback?id=123&expire=1800000000',
    expiresAt: Date.now() + 3600 * 1000, // 1 hr in future
    client: 'ANDROID',
    mimeType: 'audio/webm; codecs="opus"',
    durationMs: 180000,
    contentLength: 4500000,
    title: 'Test Song',
    artist: 'Test Artist',
  };

  store.setResolvedUrl(testDesc);

  const retrieved = store.getResolvedUrl('track_123');
  assert.ok(retrieved, 'Should retrieve cached descriptor from SQLite');
  assert.equal(retrieved.videoId, 'track_123');
  assert.equal(retrieved.targetUrl, testDesc.targetUrl);
  assert.equal(retrieved.client, 'ANDROID');
  assert.equal(retrieved.cached, true);

  // 2. Expired URL handling
  const expiredDesc = {
    videoId: 'expired_track',
    itag: 251,
    targetUrl: 'https://rr1---sn.googlevideo.com/videoplayback?id=expired',
    expiresAt: Date.now() - 1000, // already expired
    client: 'IOS',
  };
  store.setResolvedUrl(expiredDesc);
  const expiredRes = store.getResolvedUrl('expired_track');
  assert.equal(expiredRes, null, 'Expired URL should return null and be purged');

  // 3. Safety margin check (within 60s of expiring)
  const soonExpiringDesc = {
    videoId: 'soon_track',
    itag: 251,
    targetUrl: 'https://rr1---sn.googlevideo.com/videoplayback?id=soon',
    expiresAt: Date.now() + 30_000, // expires in 30s
    client: 'IOS',
  };
  store.setResolvedUrl(soonExpiringDesc);
  const soonRes = store.getResolvedUrl('soon_track', 60_000);
  assert.equal(soonRes, null, 'URLs expiring within safety margin should return null');

  // 4. Deletion on eviction
  store.deleteResolvedUrl('track_123');
  assert.equal(store.getResolvedUrl('track_123'), null, 'Deleted URL should return null');

  // 5. Visitor tokens
  store.setVisitor('default', 'vis_data_abc', 10000);
  assert.equal(store.getVisitor('default'), 'vis_data_abc');

  // 6. nsig LRU caching
  store.setNsig('raw_n_1', 'solved_n_1', 2);
  store.setNsig('raw_n_2', 'solved_n_2', 2);
  assert.equal(store.getNsig('raw_n_1'), 'solved_n_1');
  assert.equal(store.getNsig('raw_n_2'), 'solved_n_2');

  // Exceed max entries
  store.setNsig('raw_n_3', 'solved_n_3', 2);
  // Oldest should have been pruned
  const count = store.stmts.countNsig.get().count;
  assert.ok(count <= 2, `Count should be <= 2 after eviction, got ${count}`);

  // 7. PO tokens
  store.setPoToken('track_pot_1', 'po_tok_xyz', 3600);
  assert.equal(store.getPoToken('track_pot_1'), 'po_tok_xyz');
});

test('Phase 3 CipherClient: Isolated V8 worker applies signature ops & VM nsig solving', async (t) => {
  const cipher = new CipherClient({ idleTimeoutMs: 1000 });
  t.after(() => cipher.close());

  // Ping worker
  const pong = await cipher.ping();
  assert.equal(pong, 'PONG');

  // Signature operations: reverse, swap, splice
  // Input: "ABCDEF"
  // 1. reverse -> "FEDCBA"
  // 2. swap(2) -> swap index 0 and 2: chars[0]='D', chars[2]='F' -> "DEFDBA"
  // 3. splice(1) -> remove first char: "EFDBA"
  const ops = [
    { op: 'reverse' },
    { op: 'swap', arg: 2 },
    { op: 'splice', arg: 1 },
  ];
  const sigResult = await cipher.applySigOps('ABCDEF', ops);
  assert.equal(sigResult, 'EFCBA');

  // VM nsig solving
  // Execute small JS transformation in isolated VM
  const nsigCode = 'function(n) { return n.split("").reverse().join("") + "_solved"; }';
  const solved = await cipher.solveNsig('abc123xyz', nsigCode);
  assert.equal(solved, 'zyx321cba_solved');

  // Decipher URL
  const cipherStr = 's=123456&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback%3Fid%3Dtest%26n%3DsampleN';
  const decipheredUrl = await cipher.decipherUrl(
    cipherStr,
    [{ op: 'reverse' }],
    'function(n) { return n + "_ok"; }'
  );
  assert.ok(decipheredUrl.includes('sig=654321'), 'Should contain deciphered signature');
  assert.ok(decipheredUrl.includes('n=sampleN_ok'), 'Should contain solved nsig parameter');
});

test('Phase 3 AttestClient: Isolated V8 worker mints content-bound PO tokens', async (t) => {
  const attest = new AttestClient({ idleTimeoutMs: 1000 });
  t.after(() => attest.close());

  const pong = await attest.ping();
  assert.equal(pong, 'PONG');

  const res1 = await attest.mintPoToken('track_abc', 'visitor_123');
  assert.ok(res1.token, 'Should mint token');
  assert.ok(res1.ttlSecs > 0, 'Should have positive ttl');

  // Verify decoded content binding
  const decodedStr = Buffer.from(res1.token, 'base64url').toString('utf8');
  assert.ok(decodedStr.includes('"v":"track_abc"'), 'Payload must be content-bound to videoId');
  assert.ok(decodedStr.includes('"vd":"visitor_123"'), 'Payload must be bound to visitorData');

  // Test concurrency 1 queue
  const p1 = attest.mintPoToken('vid_1');
  const p2 = attest.mintPoToken('vid_2');
  const [m1, m2] = await Promise.all([p1, p2]);
  assert.ok(m1.token);
  assert.ok(m2.token);
});

test('Phase 2+3 StreamResolver Integration: SQLite seek caching & Resilient Plane escalation', async (t) => {
  const store = new StreamStore({ inMemory: true });
  const cipher = new CipherClient({ idleTimeoutMs: 1000 });
  const attest = new AttestClient({ idleTimeoutMs: 1000 });
  const cache = new LruCache({ maxItems: 100 });

  const resolver = new StreamResolver({
    cache,
    store,
    cipherClient: cipher,
    attestClient: attest,
  });

  t.after(() => {
    resolver.close();
    cipher.close();
    attest.close();
    store.close();
  });

  // Pre-seed an active resolved URL into SQLite (simulating a prior resolve or seek)
  store.setResolvedUrl({
    videoId: 'seek_test_vid',
    itag: 251,
    targetUrl: 'https://rr2---sn.googlevideo.com/videoplayback?id=seek_test&expire=1999999999',
    expiresAt: Date.now() + 3600 * 1000,
    client: 'VISIONOS',
    mimeType: 'audio/webm; codecs="opus"',
    durationMs: 240000,
    contentLength: 6000000,
    title: 'Seekable Track',
    artist: 'Artist',
  });

  // 1. Resolve should hit SQLite directly without making any upstream network requests!
  const desc = await resolver.resolve('seek_test_vid');
  assert.ok(desc, 'Descriptor should resolve');
  assert.equal(desc.videoId, 'seek_test_vid');
  assert.equal(desc.cached, true, 'Must indicate cached=true from SQLite');
  assert.equal(desc.client, 'VISIONOS');

  // 2. Subsequent resolve from in-memory cache
  const descMem = await resolver.resolve('seek_test_vid');
  assert.equal(descMem.videoId, 'seek_test_vid');

  // 3. Eviction invalidates both memory cache and SQLite store
  resolver.evict('seek_test_vid');
  assert.equal(cache.get('seek_test_vid'), undefined);
  assert.equal(store.getResolvedUrl('seek_test_vid'), null);
});
