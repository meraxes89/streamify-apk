import test from 'node:test';
import assert from 'node:assert/strict';
import { ClockSync } from '../../src/slices/room/clock-sync.js';
import { ClientHealthManager } from '../../src/slices/stream/health.js';
import { ZeroCopySendfileEngine } from '../../src/slices/stream/sendfile-engine.js';

test('Option 1: ClockSync calculates filtered offset and skew linear regression', () => {
  // Simulate 10 samples with a steady 50 ppm clock drift (50 us / sec)
  const samples = [];
  for (let i = 0; i < 10; i++) {
    samples.push({
      offsetMs: 10 + i * 0.05, // 0.05 ms per sample drift = 50 ppm
      rttMs: 15 + (i % 2 === 0 ? 0 : 10), // Asymmetric Wi-Fi jitter
      t: 1000000 + i * 1000,
    });
  }

  const res = ClockSync.filterSamples(samples, 10, 0.3);
  assert.ok(res.filteredOffsetMs > 0);
  assert.equal(res.minRttMs, 15, 'Minimum RTT should select lowest latency packet');
  assert.ok(Math.abs(res.skewPpm - 50) < 5, `Skew ppm should be ~50 ppm, got ${res.skewPpm}`);
});

test('Option 1: ClockSync PI clock servo produces smooth rate adjustment', () => {
  const state = { integral: 0, prevPpm: 0 };

  // 10ms buffer error (playout lag)
  const step1 = ClockSync.computeServoRate(state, 10, { kp: 0.5, ki: 0.01, maxSlewPpm: 5 });
  assert.equal(step1.ppm, 5, 'Slew limiter should cap change to maxSlewPpm');
  assert.ok(step1.readRate > 1.0, 'Read rate should speed up to catch up');

  // Small error inside deadband
  const step2 = ClockSync.computeServoRate(state, 1.0, { deadbandMs: 1.5, maxSlewPpm: 5 });
  assert.ok(Math.abs(step2.ppm) <= 100);
});

test('Option 2: HealthManager coordinated circuit-breaker and global quarantine', () => {
  const health = new ClientHealthManager();
  assert.equal(health.isQuarantined('ANDROID'), false);

  // Consecutive 403s trigger coordinated circuit breaker
  health.recordError('ANDROID', 403);
  health.recordError('ANDROID', 403);
  assert.equal(health.isQuarantined('ANDROID'), true);
  assert.ok(health.globalChallengeCooldownUntil > Date.now());

  // Ranked clients puts quarantined client at the end
  const clients = [{ name: 'ANDROID' }, { name: 'IOS' }];
  const ranked = health.getRankedClients(clients);
  assert.equal(ranked[0].name, 'IOS');
});

test('Option 3: ZeroCopySendfileEngine initializes safely', () => {
  const engine = new ZeroCopySendfileEngine();
  // Safe instantiation on any OS
  assert.equal(typeof engine.canOffload, 'function');
  assert.equal(engine.canOffload(null, null), false);
});
