import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonLyricsService } from '../src/lyrics.mjs';

describe('AmazonLyricsService', () => {
  test('parses standard LRC strings into sorted millisecond timing cues', () => {
    const service = new AmazonLyricsService();
    const lrc = `
[00:00.15] Is this the real life?
[00:07.13] Caught in a landslide
[00:14.77] Open your eyes
`;
    const cues = service._parseLrc(lrc);
    assert.equal(cues.length, 3);
    assert.equal(cues[0].startMs, 150);
    assert.equal(cues[0].text, 'Is this the real life?');
    assert.equal(cues[1].startMs, 7130);
    assert.equal(cues[1].text, 'Caught in a landslide');
    assert.equal(cues[2].startMs, 14770);
  });

  test('returns cached lyrics on subsequent lookups', async () => {
    const cache = new Map();
    cache.set('song:artist', {
      track: 'Song',
      artist: 'Artist',
      syncedLyrics: '[00:01.00] Line',
      cues: [{ startMs: 1000, text: 'Line' }]
    });

    const service = new AmazonLyricsService({ cache });
    const result = await service.getLyrics('Song', 'Artist');
    assert.equal(result.track, 'Song');
    assert.equal(result.cues.length, 1);
  });
});
