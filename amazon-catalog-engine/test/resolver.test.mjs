import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonStreamResolver } from '../src/resolver.mjs';

describe('AmazonStreamResolver', () => {
  test('ranks candidates prioritizing duration tolerance and official title', () => {
    const resolver = new AmazonStreamResolver();
    const original = {
      title: 'Bohemian Rhapsody',
      artist: 'Queen',
      durationSeconds: 354
    };

    const candidates = [
      { videoId: 'v1', title: 'Bohemian Rhapsody (Live at Wembley)', artist: 'Queen', durationSeconds: 380 },
      { videoId: 'v2', title: 'Bohemian Rhapsody (Karaoke Version)', artist: 'Karaoke Band', durationSeconds: 354 },
      { videoId: 'v3', title: 'Bohemian Rhapsody', artist: 'Queen - Topic', durationSeconds: 355 }, // Best match!
      { videoId: 'v4', title: 'Bohemian Rhapsody Cover', artist: 'Fan', durationSeconds: 350 }
    ];

    const ranked = resolver._rankCandidates(candidates, original);
    assert.equal(ranked.videoId, 'v3', 'Should select official track matching duration and title without penalties');
    assert.ok(ranked.score > 100);
  });

  test('caches resolved videoId by ASIN', async () => {
    const cache = new Map();
    cache.set('B0TEST', {
      asin: 'B0TEST',
      videoId: 'testVid123',
      streamUrl: '/api/stream?id=testVid123',
      manifestUrl: '/api/stream/testVid123/manifest.mpd'
    });

    const resolver = new AmazonStreamResolver({ cache });
    const resolved = await resolver.resolve({ asin: 'B0TEST', title: 'Test' });

    assert.equal(resolved.videoId, 'testVid123');
    assert.equal(resolved.streamUrl, '/api/stream?id=testVid123');
  });
});
