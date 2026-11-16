/// <reference types="vite/client" />
/**
 * lyrics/sanitize.ts — universal metadata sanitizers (1:1 port of the
 * private helpers in app/…/data/network/LyricsResolver.kt, Plans 23/24).
 *
 * Every lyrics subsystem derives its identity strings and cache keys from
 * THESE two functions, so the sanitized shape is identical everywhere:
 * resolver search queries, the identity gate, the localStorage lyrics
 * cache, and the per-track user-offset store all key off
 *   `${sanitizeCacheKeyTitle(t)}|${sanitizeCacheKeyArtist(a)}`.
 *
 * The Kotlin semantics are preserved exactly (order of operations matters):
 *   cleanTitle — compound delimiters first ("Title – Artist" keeps the
 *     LEFT side), then parenthetical/bracket noise stripping, and a blank
 *     result falls back to the raw trimmed input.
 *   cleanArtist — distribution noise ("- Topic", "VEVO", "Official"),
 *     feat-clauses, then the FIRST comma-separated artist; blank falls
 *     back to raw trimmed input.
 */

/** The desktop-UA string the Kotlin resolver sends on lyric provider calls. */
export const USER_AGENT_DESKTOP =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

/** Compound-title delimiters — "Title – Artist" style strings keep the title. */
const TITLE_DELIMITERS = [' – ', ' — ', ' - ', ' // ', ' | '] as const;

/**
 * Parenthetical / bracket noise: "(Official Video)", "[Remastered]",
 * "(feat. …)", "(Lyric Video)", "(HD)"… The Kotlin regex requires the
 * keyword to open the parenthesis; anything else (e.g. "(2010)") stays.
 */
const TITLE_NOISE_REGEX =
  /\((?:official|audio|video|remastered|remaster|radio edit|edit|deluxe|version|feat\.|feat|ft\.|with|bonus|live|acoustic|anniversary|lyrics|lyric video|hd|4k|mv|topic|vevo).*?\)|\[.*?\]/i;

/** Artist distribution/feature noise: "- Topic", "VEVO", "Official", "feat. …". */
const ARTIST_NOISE_REGEX = /(\s*-\s*Topic|\s*VEVO|\s*Official)/i;
const ARTIST_FEAT_REGEX = /(feat\.|ft\.).*/i;

export function cleanTitle(rawTitle: string): string {
  if (rawTitle.trim() === '') return '';
  let title = rawTitle;

  // Split compound titles like "Title – Artist" or "Artist - Title".
  for (const delim of TITLE_DELIMITERS) {
    if (title.includes(delim)) {
      const parts = title.split(delim);
      if (parts.length === 2) {
        title = parts[0].trim();
      }
    }
  }

  // Strip parenthetical and bracket noise.
  title = title.replace(TITLE_NOISE_REGEX, '').trim();
  return title.trim() === '' ? rawTitle.trim() : title;
}

export function cleanArtist(rawArtist: string): string {
  if (rawArtist.trim() === '') return '';
  let artist = rawArtist
    .replace(ARTIST_NOISE_REGEX, '')
    .replace(ARTIST_FEAT_REGEX, '')
    .split(',')[0]
    .trim();
  return artist === '' ? rawArtist.trim() : artist;
}

/** Public sanitizer so every subsystem derives identical cache keys. */
export function sanitizeCacheKeyTitle(rawTitle: string): string {
  return cleanTitle(rawTitle).toLowerCase().trim();
}

/** Public sanitizer so every subsystem derives identical cache keys. */
export function sanitizeCacheKeyArtist(rawArtist: string): string {
  return cleanArtist(rawArtist).toLowerCase().trim();
}

/** The stable per-track key: sanitized "title|artist" (LyricOffsetStore.keyOf). */
export function trackKeyOf(rawTitle: string, rawArtist: string): string {
  return `${sanitizeCacheKeyTitle(rawTitle)}|${sanitizeCacheKeyArtist(rawArtist)}`;
}

/** Strict videoId format validation — rejects garbage like numeric DB ids ("42"). */
export function isValidVideoId(videoId: string | null | undefined): boolean {
  return (
    videoId != null &&
    videoId !== '' &&
    /^[a-zA-Z0-9_-]{11}$/.test(videoId)
  );
}
