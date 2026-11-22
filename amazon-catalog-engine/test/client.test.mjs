import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonCatalogClient } from '../src/client.mjs';

describe('AmazonCatalogClient', () => {
  test('formats search results correctly with tracks, albums, and artists', () => {
    const client = new AmazonCatalogClient();
    const mockRaw = {
      results: [
        {
          label: 'Tracks',
          hits: [
            { asin: 'BT01', title: 'Song One', artistName: 'Artist A', duration: 200 }
          ]
        },
        {
          label: 'Albums',
          hits: [
            { asin: 'BA01', title: 'Album One', artistName: 'Artist A' }
          ]
        },
        {
          label: 'Artists',
          hits: [
            { asin: 'BAR01', name: 'Artist A' }
          ]
        }
      ]
    };

    const formatted = client._formatSearchResults(mockRaw, 'Test Query');
    assert.equal(formatted.query, 'Test Query');
    assert.equal(formatted.tracks.length, 1);
    assert.equal(formatted.tracks[0].title, 'Song One');
    assert.equal(formatted.albums.length, 1);
    assert.equal(formatted.albums[0].title, 'Album One');
    assert.equal(formatted.artists.length, 1);
    assert.equal(formatted.artists[0].name, 'Artist A');
  });

  test('probe returns healthy metadata structure', async () => {
    const client = new AmazonCatalogClient();
    client.session.getSession = async () => ({
      host: 'https://music.amazon.com',
      region: 'NA',
      sessionId: 'test-session',
      deviceId: 'test-device',
      deviceType: 'A16ZV8BU3SN1N3',
      csrf: { token: 'token' }
    });

    const probe = await client.probe('US');
    assert.equal(probe.status, 'ONLINE');
    assert.equal(probe.territory, 'US');
    assert.equal(probe.hasCsrf, true);
    assert.equal(probe.circuitState, 'CLOSED');
  });
});
