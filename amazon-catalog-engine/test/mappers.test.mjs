import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  mapAmazonTrack,
  mapAmazonAlbum,
  mapAmazonPlaylist,
  mapAmazonArtist,
  parseDuration,
  parseYearFromDate
} from '../src/mappers.mjs';

describe('Canonical Mappers', () => {
  test('parseDuration parses seconds, milliseconds, mm:ss, and ISO strings', () => {
    assert.equal(parseDuration(180), 180);
    assert.equal(parseDuration(180000), 180);
    assert.equal(parseDuration('3:45'), 225);
    assert.equal(parseDuration('1:02:15'), 3735);
    assert.equal(parseDuration('PT3M45S'), 225);
  });

  test('parseYearFromDate extracts 4-digit release year', () => {
    assert.equal(parseYearFromDate('2024-05-12'), 2024);
    assert.equal(parseYearFromDate('December 1975'), 1975);
    assert.equal(parseYearFromDate(null), null);
  });

  test('mapAmazonTrack correctly maps track with ASIN, artists, duration, and year', () => {
    const raw = {
      asin: 'B0TEST123',
      title: 'Bohemian Rhapsody',
      artistName: 'Queen',
      artistAsin: 'B0ARTIST1',
      albumName: 'A Night at the Opera',
      albumAsin: 'B0ALBUM1',
      duration: 354,
      originalReleaseDate: '1975-10-31',
      copyright: '℗ 1975 Queen Productions Ltd.',
      artOriginal: 'https://m.media-amazon.com/images/sample._SX300_.jpg'
    };

    const track = mapAmazonTrack(raw);
    assert.equal(track.id, 'amzn:track:B0TEST123');
    assert.equal(track.videoId, 'amzn:B0TEST123');
    assert.equal(track.title, 'Bohemian Rhapsody');
    assert.equal(track.artists[0].name, 'Queen');
    assert.equal(track.artists[0].id, 'amzn:artist:B0ARTIST1');
    assert.equal(track.album.name, 'A Night at the Opera');
    assert.equal(track.durationSeconds, 354);
    assert.equal(track.releaseYear, 1975);
    assert.equal(track.label, '℗ 1975 Queen Productions Ltd.');
    assert.equal(track.source, 'amazon');
    assert.ok(track.thumbnails.length >= 1);
  });

  test('mapAmazonAlbum maps album and embedded track list', () => {
    const raw = {
      asin: 'B0ALBUM1',
      title: 'A Night at the Opera',
      artistName: 'Queen',
      originalReleaseDate: '1975-10-31',
      tracks: [
        { asin: 'B0T1', title: 'Death on Two Legs', duration: 223 },
        { asin: 'B0T2', title: 'Lazing on a Sunday Afternoon', duration: 68 }
      ]
    };

    const album = mapAmazonAlbum(raw);
    assert.equal(album.id, 'amzn:album:B0ALBUM1');
    assert.equal(album.title, 'A Night at the Opera');
    assert.equal(album.artist.name, 'Queen');
    assert.equal(album.tracks.length, 2);
    assert.equal(album.tracks[0].id, 'amzn:track:B0T1');
  });
});
