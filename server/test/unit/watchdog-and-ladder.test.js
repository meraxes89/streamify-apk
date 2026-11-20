import test from 'node:test';
import assert from 'node:assert/strict';
import { ClientHealthManager } from '../../src/slices/stream/health.js';
import { StreamWatchdog } from '../../src/slices/stream/watchdog.js';
import { StreamResolver } from '../../src/slices/stream/resolver.js';

test('ClientHealthManager: EWMA promotion, demotion, and ranking', () => {
  const health = new ClientHealthManager({ alpha: 0.2 });

  // 1. Initial state
  const statsA = health.getStats('ANDROID');
  assert.equal(statsA.score, 1.0);

  // 2. Promotion on success
  health.recordSuccess('ANDROID', 120);
  assert.equal(statsA.successes, 1);
  assert.equal(statsA.score, 1.0);

  // 3. Demotion on error
  health.recordError('IOS', 403);
  const statsI = health.getStats('IOS');
  assert.equal(statsI.errors, 1);
  assert.ok(statsI.score <= 0.4, '403 error must heavily demote score');

  // 4. Demotion on throttle
  health.recordThrottle('VISIONOS');
  const statsV = health.getStats('VISIONOS');
  assert.equal(statsV.throttles, 1);
  assert.ok(statsV.score <= 0.5, 'Throttle must heavily demote score');
  assert.equal(health.isQuarantined('VISIONOS'), true);

  // 5. Ranking: ANDROID (1.0) > IOS (0.4) > VISIONOS (quarantined)
  const clients = [{ name: 'VISIONOS' }, { name: 'IOS' }, { name: 'ANDROID' }];
  const ranked = health.getRankedClients(clients);
  assert.equal(ranked[0].name, 'ANDROID', 'Healthy client must be first');
  assert.equal(ranked[ranked.length - 1].name, 'VISIONOS', 'Quarantined client must be last');
});

test('StreamWatchdog: Normal throughput (160 kbps) does not trigger throttle alarm', async () => {
  const health = new ClientHealthManager();
  const watchdog = new StreamWatchdog({
    healthManager: health,
    throttleBps: 65_000,
    sampleIntervalMs: 50, // fast sample for test
  });

  let throttledFired = false;
  watchdog.on('throttled', () => {
    throttledFired = true;
  });

  const streamId = 'stream_normal';
  watchdog.watch(streamId, 'ANDROID', 'vid1');
  // Backdate startedAt so ramp-up check passes
  watchdog.activeStreams.get(streamId).startedAt = Date.now() - 2000;

  // Simulate 160 kbps audio transfer (~20 KB/sec)
  for (let i = 0; i < 5; i++) {
    watchdog.recordBytes(streamId, 20_000);
    watchdog.sample();
  }

  assert.equal(throttledFired, false, 'Normal stream must not trigger throttle alarm');
  assert.equal(health.getStats('ANDROID').throttles, 0);

  watchdog.unwatch(streamId);
  watchdog.close();
});

test('StreamWatchdog: Sustained ~50 kbps throttling triggers alarm and demotes client in health manager', async () => {
  const health = new ClientHealthManager();
  const watchdog = new StreamWatchdog({
    healthManager: health,
    throttleBps: 65_000,
    sampleIntervalMs: 50,
  });

  let throttledPayload = null;
  watchdog.on('throttled', (data) => {
    throttledPayload = data;
  });

  const streamId = 'stream_throttled';
  watchdog.watch(streamId, 'IOS', 'vid_choked');
  watchdog.activeStreams.get(streamId).startedAt = Date.now() - 2000;

  // Simulate sustained ~50 kbps transfer (only ~300 bytes per 50ms tick = ~48 kbps)
  for (let i = 0; i < 2; i++) {
    watchdog.recordBytes(streamId, 300);
    watchdog.sample();
  }

  assert.ok(throttledPayload, 'Watchdog must emit throttled event on sustained ~50 kbps');
  assert.equal(throttledPayload.clientName, 'IOS');
  assert.equal(throttledPayload.videoId, 'vid_choked');

  // Verify health manager was alerted and demoted IOS
  assert.equal(health.getStats('IOS').throttles, 1);
  assert.ok(health.getStats('IOS').score <= 0.5);

  watchdog.unwatch(streamId);
  watchdog.close();
});

test('StreamResolver: Loads ladder clients and orders by health manager', async () => {
  const health = new ClientHealthManager();
  // Demote ANDROID so IOS is ranked #1
  health.recordThrottle('ANDROID');

  const resolver = new StreamResolver({ healthManager: health });
  assert.ok(Array.isArray(resolver.clients));
  assert.ok(resolver.clients.length >= 3);

  const ranked = resolver.healthManager.getRankedClients(resolver.clients);
  assert.notEqual(ranked[0].name, 'ANDROID', 'Demoted ANDROID must not be ranked first');
  assert.equal(ranked[0].name, 'IOS', 'Healthy IOS must be promoted to #1');
  resolver.close();
});
