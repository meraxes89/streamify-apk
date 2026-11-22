/**
 * AmazonLyricsService
 * 
 * Synchronized lyrics engine for Amazon Music tracks using LRCLIB.
 * Returns both raw LRC strings and parsed millisecond timing cues.
 */

const USER_AGENT = 'Streamify/1.0 (https://github.com/meraxes89/streamify-apk)';

export class AmazonLyricsService {
  constructor({ cache = new Map() } = {}) {
    this.cache = cache;
  }

  /**
   * Fetches lyrics for a track.
   * 
   * @param {string} track - Track title
   * @param {string} artist - Artist name
   * @param {number} [durationSeconds] - Track duration in seconds
   * @returns {Promise<Object>}
   */
  async getLyrics(track, artist, durationSeconds = null) {
    if (!track || !artist) {
      throw new Error('Track and artist are required for lyrics lookup');
    }

    const cacheKey = `${track.toLowerCase()}:${artist.toLowerCase()}`;
    if (this.cache.has(cacheKey)) {
      return this.cache.get(cacheKey);
    }

    let url = `https://lrclib.net/api/get?track_name=${encodeURIComponent(track)}&artist_name=${encodeURIComponent(artist)}`;
    if (durationSeconds) {
      url += `&duration=${Math.round(durationSeconds)}`;
    }

    try {
      let res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
      let data = null;

      if (res.ok) {
        data = await res.json();
      } else if (res.status === 404) {
        // Fallback: search endpoint
        const searchUrl = `https://lrclib.net/api/search?q=${encodeURIComponent(track + ' ' + artist)}`;
        const sRes = await fetch(searchUrl, { headers: { 'User-Agent': USER_AGENT } });
        if (sRes.ok) {
          const list = await sRes.json();
          if (Array.isArray(list) && list.length > 0) {
            data = list[0];
          }
        }
      }

      if (!data) {
        return {
          track,
          artist,
          syncedLyrics: null,
          plainLyrics: null,
          cues: [],
          instrumental: false
        };
      }

      const cues = this._parseLrc(data.syncedLyrics);

      const result = {
        track: data.trackName || track,
        artist: data.artistName || artist,
        album: data.albumName || null,
        durationSeconds: data.duration || durationSeconds,
        syncedLyrics: data.syncedLyrics || null,
        plainLyrics: data.plainLyrics || null,
        instrumental: !!data.instrumental,
        cues
      };

      this.cache.set(cacheKey, result);
      return result;
    } catch (err) {
      throw new Error(`Lyrics fetch failed: ${err.message}`);
    }
  }

  _parseLrc(lrcText) {
    if (!lrcText || typeof lrcText !== 'string') return [];

    const lines = lrcText.split('\n');
    const cues = [];
    const regex = /\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)/;

    for (const line of lines) {
      const match = line.match(regex);
      if (match) {
        const min = parseInt(match[1], 10);
        const sec = parseInt(match[2], 10);
        const ms = match[3].length === 2 ? parseInt(match[3], 10) * 10 : parseInt(match[3], 10);
        const startMs = min * 60 * 1000 + sec * 1000 + ms;
        const text = match[4].trim();

        if (text) {
          cues.push({ startMs, text });
        }
      }
    }

    return cues.sort((a, b) => a.startMs - b.startMs);
  }
}
