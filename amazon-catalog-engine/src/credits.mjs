/**
 * AmazonTrackCredits
 * 
 * Extracts deep liner notes, master label copyright, explicit language ratings,
 * and personnel details from Amazon Music tracks.
 */

import { detectAudioBadges } from './badges.mjs';

export function extractTrackCredits(track) {
  if (!track) return null;

  const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : '');
  const title = track.title || '';
  const artists = Array.isArray(track.artists)
    ? track.artists.map(a => typeof a === 'string' ? { name: a } : a)
    : (track.artist ? [{ name: track.artist }] : []);

  const album = track.album
    ? (typeof track.album === 'string' ? { name: track.album } : track.album)
    : null;

  const isExplicit = track.parentalControls?.hasExplicitLanguage || track.isExplicit || false;
  const label = track.copyright || track.label || track.recordLabel || 'Master Recording';
  const releaseDate = track.originalReleaseDate || track.releaseDate || null;
  const language = track.languageOfPerformance || 'English';

  const audioSpecs = detectAudioBadges(track);

  return {
    asin,
    title,
    primaryArtists: artists,
    album,
    label,
    copyright: label.startsWith('℗') ? label : `℗ ${label}`,
    releaseDate,
    releaseYear: track.releaseYear || (releaseDate ? parseInt(releaseDate.slice(0, 4), 10) : null),
    isExplicit,
    language,
    audioSpecs
  };
}
