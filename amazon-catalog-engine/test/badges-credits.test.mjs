import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { detectAudioBadges, AudioTier } from '../src/badges.mjs';
import { extractTrackCredits } from '../src/credits.mjs';

describe('Badges and Credits System', () => {
  test('detectAudioBadges detects Ultra HD and Dolby Atmos', () => {
    const raw = {
      title: 'Blinding Lights',
      contentEncoding: ['uhdAvailable', 'atmosAvailable', 'hdAvailable']
    };

    const badges = detectAudioBadges(raw);
    assert.equal(badges.tier, AudioTier.ULTRA_HD);
    assert.equal(badges.isHiRes, true);
    assert.equal(badges.isSpatial, true);
    assert.equal(badges.isLossless, true);
    assert.ok(badges.badges.some(b => b.id === 'ultra_hd'));
    assert.ok(badges.badges.some(b => b.id === 'dolby_atmos'));
  });

  test('extractTrackCredits returns complete liner notes and master label copyright', () => {
    const raw = {
      asin: 'B0855G6H7G',
      title: 'Blinding Lights',
      artists: [{ name: 'The Weeknd', asin: 'B0ART' }],
      album: { name: 'After Hours' },
      copyright: '℗ 2020 The Weeknd XO, Inc.',
      originalReleaseDate: '2020-03-20',
      parentalControls: { hasExplicitLanguage: false },
      contentEncoding: ['uhdAvailable']
    };

    const credits = extractTrackCredits(raw);
    assert.equal(credits.asin, 'B0855G6H7G');
    assert.equal(credits.title, 'Blinding Lights');
    assert.equal(credits.primaryArtists[0].name, 'The Weeknd');
    assert.equal(credits.copyright, '℗ 2020 The Weeknd XO, Inc.');
    assert.equal(credits.releaseYear, 2020);
    assert.equal(credits.isExplicit, false);
    assert.equal(credits.audioSpecs.tier, AudioTier.ULTRA_HD);
  });
});
