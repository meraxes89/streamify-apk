import { LrclibClient } from './lrclib-client.js';
import { LruCache } from '../../lib/cache.js';
import { SingleFlight } from '../../lib/single-flight.js';

export class LyricsService {
  constructor({ client = new LrclibClient(), cache = new LruCache({ maxItems: 500 }) } = {}) {
    this.client = client;
    this.cache = cache;
    this.singleFlight = new SingleFlight();
  }

  async getLyrics({ track, artist, duration }) {
    if (!track && !artist) {
      throw new Error('track or artist query param required');
    }

    const cacheKey = `lyrics:${(track || '').trim().toLowerCase()}:${(artist || '').trim().toLowerCase()}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const data = await this.client.fetchLyrics({ track, artist, duration });
      const mapped = this.#mapLyrics(data);
      this.cache.set(cacheKey, mapped, 7 * 86_400_000); // 7 days TTL
      return mapped;
    });
  }

  #mapLyrics(data) {
    if (!data || (!data.syncedLyrics && !data.plainLyrics)) {
      return {
        error: true,
        syncType: null,
        lines: null,
      };
    }

    if (data.syncedLyrics) {
      const timeRegex = /\[(\d{2}):(\d{2})(?:\.(\d{2,3}))?\](.*)/;
      const parsed = [];
      const rawLines = data.syncedLyrics.split('\n');
      for (const line of rawLines) {
        const m = timeRegex.exec(line);
        if (m) {
          const min = parseInt(m[1], 10);
          const sec = parseInt(m[2], 10);
          const frac = m[3] || '0';
          const ms = frac.length === 2 ? parseInt(frac, 10) * 10 : parseInt(frac, 10);
          const totalMs = (min * 60 + sec) * 1000 + ms;
          const words = m[4].trim();
          parsed.push({ timeMs: totalMs, words });
        }
      }

      const lines = [];
      for (let i = 0; i < parsed.length; i++) {
        const current = parsed[i];
        const next = parsed[i + 1];
        const endMs = next ? next.timeMs : current.timeMs + 4000;
        lines.push({
          startTimeMs: String(current.timeMs),
          endTimeMs: String(endMs),
          syllables: null,
          words: current.words,
        });
      }

      return {
        error: false,
        syncType: 'LINE_SYNCED',
        lines,
      };
    }

    const plainLines = (data.plainLyrics || '')
      .split('\n')
      .map((line) => ({
        startTimeMs: '0',
        endTimeMs: '0',
        syllables: null,
        words: line.trim(),
      }))
      .filter((l) => l.words.length > 0);

    return {
      error: false,
      syncType: 'UNSYNCED',
      lines: plainLines,
    };
  }
}
