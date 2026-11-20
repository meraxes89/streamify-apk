import test from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../../src/app.js';

let BASE_URL = process.env.TEST_BASE_URL || 'http://localhost:3000';
let ephemeralApp = null;

test.before(async () => {
  try {
    const res = await fetch(`${BASE_URL}/api/health`, { signal: AbortSignal.timeout(1000) });
    if (res.ok) return;
  } catch {}

  // Spawn ephemeral app instance for self-contained testing
  ephemeralApp = await createApp({ logger: false });
  const address = await ephemeralApp.listen({ port: 0, host: '127.0.0.1' });
  BASE_URL = address;
});

test.after(async () => {
  if (ephemeralApp) {
    await ephemeralApp.close();
  }
});

test('API Contract: Health & Capabilities', async (t) => {
  await t.test('GET /api/health returns ok status and runtime metrics', async () => {
    const res = await fetch(`${BASE_URL}/api/health`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /application\/json/);
    const data = await res.json();
    assert.equal(data.status, 'ok');
    assert.ok(typeof data.version === 'string');
    assert.ok(typeof data.uptime === 'number');
    assert.ok(data.memory && typeof data.memory.rss === 'number');
  });

  await t.test('GET /api/capabilities returns supported capability list', async () => {
    const res = await fetch(`${BASE_URL}/api/capabilities`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.status, 'ok');
    assert.ok(Array.isArray(data.capabilities));
  });
});

test('API Contract: Catalog (Home, Search, Suggestions, Browse)', async (t) => {
  await t.test('GET /api/yt/home returns sections with item contents', async () => {
    const res = await fetch(`${BASE_URL}/api/yt/home`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.ok(data && Array.isArray(data.items), 'data.items should be an array');
    assert.ok(data.items.length > 0, 'data.items should not be empty');
    
    const firstSection = data.items[0];
    assert.ok(firstSection.title, 'section should have a title');
    assert.ok(Array.isArray(firstSection.contents), 'section contents should be an array');
    assert.ok(firstSection.contents.length > 0, 'section contents should not be empty');

    const firstItem = firstSection.contents[0];
    assert.ok(firstItem.title, 'content item should have a title');
    assert.ok(firstItem.videoId || firstItem.browseId || firstItem.playlistId, 'content item should have an id');
    assert.ok(Array.isArray(firstItem.thumbnails), 'item should have thumbnails array');
  });

  await t.test('GET /api/yt/search returns songs with duration and thumbnails', async () => {
    const res = await fetch(`${BASE_URL}/api/yt/search?q=coldplay`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.ok(Array.isArray(data), 'search response should be an array of tracks');
    assert.ok(data.length > 0, 'search results should not be empty');

    const track = data[0];
    assert.ok(typeof track.videoId === 'string', 'track should have videoId string');
    assert.ok(typeof track.title === 'string', 'track should have title string');
    assert.ok(Array.isArray(track.artists), 'track should have artists array');
    assert.ok(typeof track.duration === 'string', 'track should have duration string');
    assert.ok(typeof track.durationSeconds === 'number', 'track should have durationSeconds number');
    assert.ok(track.durationSeconds > 0, 'durationSeconds should be parsed > 0 for standard song');
    assert.ok(Array.isArray(track.thumbnails), 'track should have thumbnails array');
  });

  await t.test('GET /api/yt/suggest returns search query string suggestions', async () => {
    const res = await fetch(`${BASE_URL}/api/yt/suggest?q=cold`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.ok(Array.isArray(data), 'suggestions should be an array');
    assert.ok(data.length > 0, 'suggestions should not be empty');
    assert.equal(typeof data[0], 'string');
  });

  await t.test('GET /api/yt/browse returns valid entity data', async () => {
    const res = await fetch(`${BASE_URL}/api/yt/browse?id=MPREb_PvMNqFUp1oW`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.ok(data && typeof data === 'object');
    assert.ok(data.title || data.name, 'browse entity should have title/name');
  });
});

test('API Contract: Queue & Radio (/api/yt/next)', async (t) => {
  await t.test('GET /api/yt/next with videoId builds queue with tracks', async () => {
    const res = await fetch(`${BASE_URL}/api/yt/next?videoId=H3Kzh6RrnMc`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.ok(data && Array.isArray(data.tracks), 'data.tracks should be an array');
    assert.ok(data.tracks.length > 0, 'data.tracks should contain tracks');

    const firstTrack = data.tracks[0];
    assert.ok(typeof firstTrack.videoId === 'string');
    assert.ok(typeof firstTrack.title === 'string');
    assert.ok(Array.isArray(firstTrack.artists));
    assert.ok(typeof firstTrack.durationSeconds === 'number');
    assert.ok(Array.isArray(firstTrack.thumbnails));
  });
});

test('API Contract: Lyrics (/api/yt/lyrics)', async (t) => {
  await t.test('GET /api/yt/lyrics returns lyrics data structure', async () => {
    const res = await fetch(`${BASE_URL}/api/yt/lyrics?track=Yellow&artist=Coldplay`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.error, false);
    assert.ok(Array.isArray(data.lines), 'lyrics lines should be an array');
    assert.ok(data.lines.length > 0, 'lyrics lines should not be empty');
    assert.ok(data.lines[0].words !== undefined);
  });
});

test('API Contract: Thumbnail Proxy (/api/yt/thumb)', async (t) => {
  await t.test('GET /api/yt/thumb proxies image bytes with image Content-Type', async () => {
    const targetUrl = encodeURIComponent('https://i.ytimg.com/vi/H3Kzh6RrnMc/hqdefault.jpg');
    const res = await fetch(`${BASE_URL}/api/yt/thumb?url=${targetUrl}`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') || '', /^image\//);
    const buf = await res.arrayBuffer();
    assert.ok(buf.byteLength > 100, 'image response should contain binary data');
  });
});

test('API Contract: Audio Stream & Range Requests (/api/stream)', async (t) => {
  const videoId = 'H3Kzh6RrnMc';

  await t.test('HEAD /api/stream returns 200 with Accept-Ranges and Content-Length', async () => {
    const res = await fetch(`${BASE_URL}/api/stream?id=${videoId}`, { method: 'HEAD' });
    assert.ok(res.status === 200 || res.status === 206);
    assert.equal(res.headers.get('accept-ranges'), 'bytes');
    const cl = res.headers.get('content-length');
    assert.ok(cl && parseInt(cl, 10) > 0);
  });

  await t.test('Range: bytes=0-1 (Safari probe) returns 206 with Content-Length: 2', async () => {
    const res = await fetch(`${BASE_URL}/api/stream?id=${videoId}`, {
      headers: { Range: 'bytes=0-1' }
    });
    assert.equal(res.status, 206, 'Should respond with 206 Partial Content');
    assert.equal(res.headers.get('content-length'), '2');
    const cr = res.headers.get('content-range') || '';
    assert.match(cr, /^bytes 0-1\/\d+$/);
    const buf = await res.arrayBuffer();
    assert.equal(buf.byteLength, 2);
  });

  await t.test('Range: bytes=0-1023 returns 206 with Content-Length: 1024', async () => {
    const res = await fetch(`${BASE_URL}/api/stream?id=${videoId}`, {
      headers: { Range: 'bytes=0-1023' }
    });
    assert.equal(res.status, 206);
    assert.equal(res.headers.get('content-length'), '1024');
    const cr = res.headers.get('content-range') || '';
    assert.match(cr, /^bytes 0-1023\/\d+$/);
    const buf = await res.arrayBuffer();
    assert.equal(buf.byteLength, 1024);
  });
});

test('API Contract: Multi-Room Synchronized Audio Relay (/api/room)', async (t) => {
  await t.test('GET /api/room/list returns active rooms status', async () => {
    const res = await fetch(`${BASE_URL}/api/room/list`);
    assert.equal(res.status, 200);
    const data = await res.json();
    assert.equal(data.status, 'ok');
    assert.ok(Array.isArray(data.rooms));
  });

  await t.test('GET /api/room/:roomId returns 404 for nonexistent room', async () => {
    const res = await fetch(`${BASE_URL}/api/room/nonexistent_test_room`);
    assert.equal(res.status, 404);
  });
});
