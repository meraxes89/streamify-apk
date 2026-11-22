import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonSessionManager } from '../src/session.mjs';

describe('AmazonSessionManager', () => {
  test('single-flight deduplication coalesces parallel requests', async () => {
    let bootstrapCalls = 0;
    const mockProtocol = {
      hosts: { US: 'https://music.amazon.com' },
      regions: { US: 'NA' },
      configPath: '/config.json',
      userAgent: 'MockUA/1.0',
      commonHeaders: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
      targets: {}
    };

    const manager = new AmazonSessionManager({ protocol: mockProtocol });

    // Mock internal _bootstrap
    manager._bootstrap = async (territory) => {
      bootstrapCalls++;
      await new Promise(r => setTimeout(r, 20));
      return {
        territory,
        sessionId: 'test-session-123',
        deviceId: 'dev-456',
        deviceType: 'A16ZV8BU3SN1N3',
        csrf: { token: 'mock-csrf' },
        createdAt: Date.now()
      };
    };

    // Dispatch 10 parallel session requests
    const promises = Array.from({ length: 10 }, () => manager.getSession('US'));
    const results = await Promise.all(promises);

    assert.equal(bootstrapCalls, 1, 'Only 1 bootstrap call should be made for concurrent requests');
    assert.equal(results.length, 10);
    assert.equal(results[0].sessionId, 'test-session-123');
  });

  test('invalidateSession clears cached token', async () => {
    const manager = new AmazonSessionManager();
    manager.sessions.set('US', { sessionId: 'cached-session', createdAt: Date.now() });

    assert.ok(manager.sessions.has('US'));
    manager.invalidateSession('US');
    assert.ok(!manager.sessions.has('US'));
  });

  test('buildHeaders constructs required Amazon headers', () => {
    const manager = new AmazonSessionManager();
    const mockSession = {
      host: 'https://music.amazon.com',
      deviceType: 'A16ZV8BU3SN1N3',
      deviceId: 'dev-123',
      sessionId: 'sess-456',
      cookies: 'session-id=sess-456',
      csrf: { token: 'csrf-abc', rnd: '123', ts: '456' }
    };

    const headers = manager.buildHeaders(mockSession, 'MockTargetAction');
    assert.equal(headers['x-amzn-device-family'], 'WebPlayer');
    assert.equal(headers['x-amzn-device-type'], 'A16ZV8BU3SN1N3');
    assert.equal(headers['x-amzn-session-id'], 'sess-456');
    assert.equal(headers['X-Amz-Target'], 'MockTargetAction');
    assert.equal(headers['csrf'], 'csrf-abc');
    assert.equal(headers['Cookie'], 'session-id=sess-456');
  });
});
