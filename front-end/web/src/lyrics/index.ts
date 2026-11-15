/// <reference types="vite/client" />
/**
 * lyrics/index.ts — the public surface of the web lyrics engine (v1 port
 * of the Android LyricsResolver / LyricsData / LyricPlaybackController
 * stack). The player hook consumes exactly this barrel; nothing outside
 * src/lyrics/ imports a module directly.
 */

export {
  cleanTitle,
  cleanArtist,
  sanitizeCacheKeyTitle,
  sanitizeCacheKeyArtist,
  trackKeyOf,
  isValidVideoId,
  USER_AGENT_DESKTOP,
} from './sanitize';

export {
  calculateSimilarity,
  computeLevenshtein,
  candidateTitleMatches,
  candidateArtistMatches,
  candidateDurationMatches,
  isSameRecording,
  TITLE_SIMILARITY_THRESHOLD,
} from './identityGate';

export {
  parseLrc,
  findActiveLineIndex,
  findActiveSyllableIndex,
  shiftTimestamps,
  formatLrc,
  synthesizeSyncedLyricsFromPlain,
  EMPTY_LYRICS,
  MIN_LINE_DURATION_MS,
  FINAL_LINE_DURATION_MS,
  LAST_SYLLABLE_TAIL_MS,
  SYNTH_DEFAULT_DURATION_MS,
} from './lrc';
export type { Syllable, LyricsLine, LyricsData } from './lrc';

export {
  fetchSyncedLyrics,
  clearLyricsCache,
} from './resolver';
export type { LyricsRequest, LyricsResult, LyricsSource } from './resolver';

export { keyOf, getOffset, setOffset } from './offsetStore';

export { LyricPlaybackClock } from './playbackClock';
