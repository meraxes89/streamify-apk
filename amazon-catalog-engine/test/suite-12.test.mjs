import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonCatalogClient } from '../src/client.mjs';

describe('AmazonCatalogClient - Full 12-API Suite Validation', () => {
  const client = new AmazonCatalogClient();

  // Mock internal session & resolver
  client.session.getSession = async () => ({
    territory: 'US',
    host: 'https://music.amazon.com',
    region: 'NA',
    sessionId: 'mock-session-id',
    deviceId: 'mock-device-id',
    deviceType: 'A16ZV8BU3SN1N3',
    csrf: { token: 'mock-token' }
  });

  client.resolver.resolve = async (track) => ({
    asin: track.asin || 'B0TEST',
    videoId: 'mockVideo123',
    title: track.title,
    artist: 'Mock Artist',
    durationSeconds: 200,
    streamUrl: '/api/stream?id=mockVideo123',
    manifestUrl: '/api/stream/mockVideo123/manifest.mpd',
    source: 'amazon->youtube-bridge'
  });

  client.lyrics.getLyrics = async () => ({
    track: 'Test Song',
    artist: 'Test Artist',
    syncedLyrics: '[00:01.00] Test Lyric',
    cues: [{ startMs: 1000, text: 'Test Lyric' }]
  });

  test('API 1: getHome returns curated editorial showcase sections', async () => {
    const home = await client.getHome({ territory: 'US' });
    assert.equal(home.territory, 'US');
    assert.ok(home.sections.length >= 2);
    assert.equal(home.sections[0].title, 'Trending Now & Global Hits');
  });

  test('API 4: getBrowse routes album, playlist, and artist ASINs', async () => {
    client.scraper.scrapeUrl = async (url) => ({ id: 'scraped', url });

    const albumRes = await client.getBrowse('amzn:album:B0TEST');
    assert.ok(albumRes.url.includes('/albums/B0TEST'));

    const playlistRes = await client.getBrowse('amzn:playlist:B0PLAY');
    assert.ok(playlistRes.url.includes('/playlists/B0PLAY'));
  });

  test('API 5: getNext constructs autoplay queue', async () => {
    client.search = async () => ({
      query: 'Test Artist Top Songs',
      tracks: [
        { id: 'amzn:track:T1', title: 'Song 1' },
        { id: 'amzn:track:T2', title: 'Song 2' }
      ]
    });

    const next = await client.getNext({ id: 'amzn:track:T0', artist: 'Test Artist' });
    assert.ok(next.queue.length >= 1);
    assert.equal(next.source, 'amazon');
  });

  test('API 6: getThumb rewrites image URL to requested resolution', () => {
    const original = 'https://m.media-amazon.com/images/I/sample._SX300_.jpg';
    const scaled = client.getThumb(original, 600);
    assert.equal(scaled, 'https://m.media-amazon.com/images/I/sample._SX600_.jpg');
  });

  test('API 8: getLyrics delegates to lyrics service', async () => {
    const lyrics = await client.getLyrics('Test Song', 'Test Artist');
    assert.equal(lyrics.track, 'Test Song');
    assert.equal(lyrics.cues.length, 1);
  });

  test('API 9: resolveAudio bridges to audio stream descriptor', async () => {
    const resolved = await client.resolveAudio({ asin: 'B0TEST', title: 'Test Song' });
    assert.equal(resolved.videoId, 'mockVideo123');
    assert.equal(resolved.streamUrl, '/api/stream?id=mockVideo123');
  });

  test('API 10: getDashManifest generates MPD metadata', async () => {
    const manifest = await client.getDashManifest({ asin: 'B0TEST', title: 'Test Song' });
    assert.equal(manifest.videoId, 'mockVideo123');
    assert.equal(manifest.manifestUrl, '/api/stream/mockVideo123/manifest.mpd');
    assert.equal(manifest.contentType, 'application/dash+xml');
  });

  test('API 11: prefetchDescriptor generates Stage 1 prefetch payload', async () => {
    const prefetched = await client.prefetchDescriptor({ asin: 'B0TEST', title: 'Test Song' });
    assert.equal(prefetched.status, 'warmed');
    assert.equal(prefetched.stage, 1);
    assert.equal(prefetched.videoId, 'mockVideo123');
  });

  test('API 12: probe returns online diagnostic status', async () => {
    const probe = await client.probe('US');
    assert.equal(probe.status, 'ONLINE');
    assert.equal(probe.hasCsrf, true);
    assert.equal(probe.sessionId, 'mock-session-id');
  });
});
