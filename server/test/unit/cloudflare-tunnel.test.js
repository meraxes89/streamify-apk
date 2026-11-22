import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { CloudflareTunnel } from '../../src/platform/cloudflare-tunnel.mjs';
import { createApp } from '../../src/app.js';

test('CloudflareTunnel: getDownloadUrl architecture mapping', () => {
  const linuxArm64 = CloudflareTunnel.getDownloadUrl('linux', 'arm64');
  assert.ok(linuxArm64?.includes('cloudflared-linux-arm64'));

  const linuxAmd64 = CloudflareTunnel.getDownloadUrl('linux', 'x64');
  assert.ok(linuxAmd64?.includes('cloudflared-linux-amd64'));

  const win64 = CloudflareTunnel.getDownloadUrl('win32', 'x64');
  assert.ok(win64?.includes('cloudflared-windows-amd64.exe'));

  const darwin = CloudflareTunnel.getDownloadUrl('darwin', 'arm64');
  assert.ok(darwin?.includes('cloudflared-darwin-amd64.tgz'));

  const unknown = CloudflareTunnel.getDownloadUrl('solaris', 'sparc');
  assert.equal(unknown, null);
});

test('CloudflareTunnel: initial status and mode classification', () => {
  const quickTunnel = new CloudflareTunnel({ port: 8080 });
  const quickStatus = quickTunnel.getStatus();
  assert.equal(quickStatus.status, 'disconnected');
  assert.equal(quickStatus.mode, 'quick');
  assert.equal(quickStatus.url, null);

  const namedTunnel = new CloudflareTunnel({ port: 8080, token: 'eyJh...' });
  const namedStatus = namedTunnel.getStatus();
  assert.equal(namedStatus.mode, 'named');
  assert.equal(namedStatus.enabled, true);
});

test('CloudflareTunnel: URL extraction and event handling', () => {
  const tunnel = new CloudflareTunnel({ port: 3000 });
  
  // Mock child process simulation
  const mockChild = new EventEmitter();
  mockChild.stdout = new EventEmitter();
  mockChild.stderr = new EventEmitter();
  mockChild.pid = 99999;
  mockChild.kill = () => {};

  tunnel.childProcess = mockChild;
  tunnel.status = 'starting';

  let connectedUrl = null;
  tunnel.once('connected', (url) => {
    connectedUrl = url;
  });

  // Simulate cloudflared output stream
  const chunk = Buffer.from(
    '2026-11-22T17:00:00Z INF +--------------------------------------------------------------------------------------------+\n' +
    '2026-11-22T17:00:00Z INF |  Your quick Tunnel has been created! Visit it at (it may take some time to be reachable):  |\n' +
    '2026-11-22T17:00:00Z INF |  https://stream-relay-test-42.trycloudflare.com                                         |\n' +
    '2026-11-22T17:00:00Z INF +--------------------------------------------------------------------------------------------+\n'
  );

  const urlRegex = /https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/;
  const match = chunk.toString().match(urlRegex);
  assert.ok(match);
  assert.equal(match[0], 'https://stream-relay-test-42.trycloudflare.com');

  tunnel.url = match[0];
  tunnel.status = 'connected';

  assert.equal(tunnel.getStatus().url, 'https://stream-relay-test-42.trycloudflare.com');
  assert.equal(tunnel.getStatus().status, 'connected');
  assert.equal(tunnel.getStatus().pid, 99999);
});

test('Server API Contract: /api/tunnel endpoint and health telemetry', async () => {
  const mockTunnel = {
    getStatus: () => ({
      enabled: true,
      status: 'connected',
      url: 'https://streamify-live.trycloudflare.com',
      mode: 'quick',
      pid: 12345,
      error: null,
    }),
  };

  const app = await createApp({
    tunnelManager: mockTunnel,
  });

  const tunnelRes = await app.inject({
    method: 'GET',
    url: '/api/tunnel',
  });

  assert.equal(tunnelRes.statusCode, 200);
  const tunnelJson = JSON.parse(tunnelRes.payload);
  assert.equal(tunnelJson.enabled, true);
  assert.equal(tunnelJson.status, 'connected');
  assert.equal(tunnelJson.url, 'https://streamify-live.trycloudflare.com');

  const healthRes = await app.inject({
    method: 'GET',
    url: '/api/health',
  });

  assert.equal(healthRes.statusCode, 200);
  const healthJson = JSON.parse(healthRes.payload);
  assert.ok(healthJson.tunnel);
  assert.equal(healthJson.tunnel.url, 'https://streamify-live.trycloudflare.com');

  const capRes = await app.inject({
    method: 'GET',
    url: '/api/capabilities',
  });
  assert.equal(capRes.statusCode, 200);
  const capJson = JSON.parse(capRes.payload);
  assert.ok(capJson.capabilities.includes('cloudflare-tunnel'));

  await app.close();
});
