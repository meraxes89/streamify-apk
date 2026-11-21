import test from 'node:test';
import assert from 'node:assert/strict';
import { SabrEngine } from '../../src/slices/stream/sabr-engine.js';
import { StreamResolver } from '../../src/slices/stream/resolver.js';
import { LruCache } from '../../src/lib/cache.js';
import { StreamStore } from '../../src/slices/stream/store.js';

test('L4 SABR Engine: isSabrRequired detection and DASH MPD generator', () => {
  const sabr = new SabrEngine();

  // 1. Detection
  assert.equal(sabr.isSabrRequired({}), false);
  assert.equal(
    sabr.isSabrRequired({
      streamingData: { serverAbrStreamingUrl: 'https://rr1.googlevideo.com/sabr' },
    }),
    true
  );

  // Progressive formats present -> false
  assert.equal(
    sabr.isSabrRequired({
      streamingData: {
        adaptiveFormats: [
          { itag: 251, mimeType: 'audio/webm; codecs="opus"', url: 'https://rr1.googlevideo.com/videoplayback?id=1' },
        ],
      },
    }),
    false
  );

  // 2. DASH MPD Generation
  const formats = [
    { itag: 251, mimeType: 'audio/webm; codecs="opus"', bitrate: 160000, audioSampleRate: '48000' },
    { itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 128000, audioSampleRate: '44100' },
  ];

  const mpd = sabr.generateDashMpd({
    videoId: 'sabr_test_123',
    durationSec: 215,
    formats,
    baseUrl: 'http://127.0.0.1:3000',
  });

  assert.ok(mpd.startsWith('<?xml'), 'Must start with XML declaration');
  assert.ok(mpd.includes('<MPD'), 'Must contain MPD root');
  assert.ok(mpd.includes('mediaPresentationDuration="PT215S"'));
  assert.ok(mpd.includes('Representation id="251"'));
  assert.ok(mpd.includes('Representation id="140"'));
  assert.ok(mpd.includes('http://127.0.0.1:3000/api/stream/sabr_test_123?itag=251'));
});

test('L4 SABR Engine: Session tracking and status metrics', () => {
  const sabr = new SabrEngine();
  const status = sabr.getStatus();

  assert.equal(status.activeStreams, 0);
  assert.equal(status.totalSabrStreams, 0);
  assert.equal(status.totalSegmentsRemuxed, 0);

  sabr.close();
});

test('L4 SABR Escalation: StreamResolver handles SABR-only streaming data fallback', async (t) => {
  const store = new StreamStore({ inMemory: true });
  const cache = new LruCache({ maxItems: 100 });
  const resolver = new StreamResolver({ store, cache });

  t.after(() => {
    resolver.close();
    store.close();
  });

  // Verify SABR descriptor properties when stored
  const sabrDesc = {
    videoId: 'sabr_fallback_vid',
    targetUrl: 'https://rr1---sn-test.googlevideo.com/sabr',
    serverAbrStreamingUrl: 'https://rr1---sn-test.googlevideo.com/sabr',
    isSabr: true,
    plane: 'sabr',
    itag: 251,
    mimeType: 'audio/webm; codecs="opus"',
    durationMs: 180000,
    expiresAt: Date.now() + 3600 * 1000,
    client: 'WEB_REMIX',
  };

  store.setResolvedUrl(sabrDesc);
  const resolved = await resolver.resolve('sabr_fallback_vid');

  assert.ok(resolved);
  assert.equal(resolved.isSabr, true);
  assert.equal(resolved.plane, 'sabr');
  assert.equal(resolved.serverAbrStreamingUrl, sabrDesc.serverAbrStreamingUrl);
});
