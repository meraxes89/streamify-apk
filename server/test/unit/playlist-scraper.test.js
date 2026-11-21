import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../../src/app.js';
import { PlaylistScraperService } from '../../src/slices/catalog/playlist-scraper.js';

describe('Playlist Scraper Unit & Integration Tests', () => {
  it('parses spotify playlist metadata and track list correctly', async () => {
    const scraper = new PlaylistScraperService();
    // Using popular stable public playlist
    const res = await scraper.scrape('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M');
    assert.equal(res.source, 'SPOTIFY');
    assert.ok(res.name.length > 0, 'Should have playlist name');
    assert.ok(res.tracks.length > 0, 'Should have scraped tracks');
    assert.ok(res.tracks[0].title.length > 0, 'Track should have title');
    assert.ok(res.tracks[0].artist.length > 0, 'Track should have artist');
  });

  it('parses apple music playlist metadata and track list correctly', async () => {
    const scraper = new PlaylistScraperService();
    const res = await scraper.scrape('https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb');
    assert.equal(res.source, 'APPLE_MUSIC');
    assert.ok(res.name.length > 0, 'Should have playlist name');
    assert.ok(res.tracks.length > 0, 'Should have scraped tracks');
    assert.ok(res.tracks[0].title.length > 0, 'Track should have title');
    assert.ok(res.tracks[0].artist.length > 0, 'Track should have artist');
  });

  it('handles /api/playlist/scrape GET and POST endpoints on Fastify', async () => {
    const app = await createApp({ profile: 'test' });
    try {
      const getRes = await app.inject({
        method: 'GET',
        url: '/api/playlist/scrape?url=' + encodeURIComponent('https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'),
      });
      assert.equal(getRes.statusCode, 200);
      const getJson = JSON.parse(getRes.payload);
      assert.equal(getJson.source, 'SPOTIFY');
      assert.ok(getJson.tracks.length > 0);

      const postRes = await app.inject({
        method: 'POST',
        url: '/api/playlist/scrape',
        payload: {
          url: 'https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb',
        },
      });
      assert.equal(postRes.statusCode, 200);
      const postJson = JSON.parse(postRes.payload);
      assert.equal(postJson.source, 'APPLE_MUSIC');
      assert.ok(postJson.tracks.length > 0);

      const errRes = await app.inject({
        method: 'GET',
        url: '/api/playlist/scrape',
      });
      assert.equal(errRes.statusCode, 400);
    } finally {
      await app.close();
    }
  });
});
