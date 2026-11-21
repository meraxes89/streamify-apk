import test from 'node:test';
import assert from 'node:assert/strict';
import { ProcessSurvival } from '../../src/platform/survival.js';
import { SlotBufferPool } from '../../src/lib/buffer-pool.js';
import { RoomBroadcaster } from '../../src/slices/room/broadcaster.js';

test('L0 Survival Shell: ProcessSurvival inspects oom_score_adj, PSI, and wake locks', () => {
  const survival = new ProcessSurvival();
  const status = survival.init();

  assert.equal(typeof status.isTermux, 'boolean');
  assert.equal(typeof status.wakeLockHeld, 'boolean');
  assert.ok('oomScoreAdj' in status);
  assert.ok('phantomProcNotice' in status);

  // Read PSI memory
  const psi = survival.readPsiMemory();
  // PSI may be null on non-Linux or kernel < 4.20, or object with 'some'/'full'
  if (psi) {
    assert.ok(typeof psi === 'object');
  }

  // Tune oom_score_adj handled safely without exception
  const oomRes = survival.tuneOomScoreAdj(-1000);
  assert.ok('score' in oomRes);
  assert.equal(typeof oomRes.adjusted, 'boolean');

  // Lock acquisition/release safety
  survival.acquireLock();
  survival.releaseLock();
  assert.equal(survival.wakeLockHeld, false);
});

test('L1 Allocation Governance: SlotBufferPool LIFO allocation, refcounting, and recycling', () => {
  const pool = new SlotBufferPool({ slotSize: 1024, numSlots: 4 });
  assert.equal(pool.available, 4);

  // 1. Acquire slots
  const s0 = pool.acquire();
  const s1 = pool.acquire();
  assert.equal(s0, 3, 'LIFO returns top of stack');
  assert.equal(s1, 2);
  assert.equal(pool.available, 2);

  // 2. Views are fixed subarrays of single arena
  const v0 = pool.getView(s0);
  const v1 = pool.getView(s1);
  assert.equal(v0.length, 1024);
  assert.equal(v1.length, 1024);
  assert.notEqual(v0.buffer, undefined);
  assert.equal(v0.buffer, v1.buffer, 'All views share single contiguous arena');

  // 3. Multi-refcount retain and release
  pool.retain(s0); // rc = 2
  pool.release(s0); // rc = 1, still not free
  assert.equal(pool.available, 2);
  pool.release(s0); // rc = 0, now free!
  assert.equal(pool.available, 3);

  // Re-acquire returns recycled s0
  const recycled = pool.acquire();
  assert.equal(recycled, s0);

  // 4. Exhaust pool to test fallback
  const s2 = pool.acquire();
  const s3 = pool.acquire();
  const sExhausted = pool.acquire();
  assert.equal(sExhausted, -1, 'Exhausted pool returns -1');
  assert.equal(pool.getStatus().fallbackCount, 1);

  // Release all
  pool.release(s1);
  pool.release(recycled);
  pool.release(s2);
  pool.release(s3);
  assert.equal(pool.available, 4);
});

test('L1 Allocation Governance: Socket send with refcounted lifecycle completion', async () => {
  const pool = new SlotBufferPool({ slotSize: 512, numSlots: 2 });
  const slot = pool.acquire();
  assert.equal(slot, 1);
  assert.equal(pool.available, 1);

  // Write test data into view
  const view = pool.getView(slot);
  view.write('streamify-l1-test', 0, 'utf8');

  // Mock socket with delayed callback
  let writtenData = null;
  const mockSocket = {
    write(buf, cb) {
      writtenData = buf;
      // Simulate async kernel write completion
      setImmediate(() => cb?.(null));
    },
  };

  await new Promise((resolve) => {
    pool.send(mockSocket, slot, 17, (err) => {
      assert.ifError(err);
      resolve();
    });
    // Immediately after send(), slot is still retained by socket in-flight
    // Initial acquire (1) + send retain (1) = rc 2.
    // But release initial acquire count:
    pool.release(slot);
  });

  // After socket callback finishes, slot is completely recycled!
  assert.equal(writtenData.toString('utf8'), 'streamify-l1-test');
  assert.equal(pool.available, 2, 'Slot must be fully recycled back to pool');
});

test('L1 Allocation Governance: RoomBroadcaster uses SlotBufferPool with zero buffer duplication', () => {
  const pool = new SlotBufferPool({ slotSize: 4096, numSlots: 4 });
  const broadcaster = new RoomBroadcaster({
    roomId: 'l1_room_test',
    cache: null,
    bufferPool: pool,
  });

  const writes = [];
  const mockSocket = {
    writableLength: 0,
    destroyed: false,
    write(buf, cb) {
      writes.push(buf);
      setImmediate(() => cb?.(null));
    },
    on(event, fn) {},
    end() {},
  };

  broadcaster.addMember(mockSocket);

  // Mock 5 Opus frames
  const frames = [
    { frame: Buffer.from([0x01, 0x02, 0x03]) },
    { frame: Buffer.from([0x04, 0x05, 0x06]) },
    { frame: Buffer.from([0x07, 0x08, 0x09]) },
    { frame: Buffer.from([0x0a, 0x0b, 0x0c]) },
    { frame: Buffer.from([0x0d, 0x0e, 0x0f]) },
  ];

  const initialAcquired = pool.getStatus().acquiredCount;
  broadcaster.broadcastFrameBatch(frames, 123456789n);

  assert.ok(writes.length >= 2, 'Must write welcome frame and audio batch frame');
  assert.equal(pool.getStatus().acquiredCount, initialAcquired + 1, 'Broadcaster acquired from buffer pool');

  broadcaster.close();
});
