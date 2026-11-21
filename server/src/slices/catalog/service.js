import { YtmClient } from './ytm-client.js';
import {
  mapHomeSections,
  mapSearchResults,
  mapNextQueue,
  mapBrowseResult,
} from './mappers.js';
import { LruCache } from '../../lib/cache.js';
import { SingleFlight } from '../../lib/single-flight.js';
import { PlaylistScraperService } from './playlist-scraper.js';

export class CatalogService {
  constructor({ ytmClient = new YtmClient(), cache = new LruCache({ maxItems: 1000 }), playlistScraper = new PlaylistScraperService() } = {}) {
    this.client = ytmClient;
    this.cache = cache;
    this.playlistScraper = playlistScraper;
    this.singleFlight = new SingleFlight();
    /** @type {Map<string, AbortController>} */
    this.suggestWarmControllers = new Map();
    this.activeSuggestWarms = 0;
  }

  /**
   * P2 SWR: Stale-While-Revalidate Home feed.
   * Serves cached pre-serialized buffer under 5ms for up to 30 minutes.
   * Revalidates in the background whenever age exceeds 3 minutes.
   */
  async getHome({ gl = 'IN', baseUrl = '' } = {}) {
    const cacheKey = `home:${gl}:${baseUrl}`;
    const cached = this.cache.get(cacheKey);

    if (cached) {
      const age = Date.now() - (cached.createdAt || 0);
      // Background revalidation if stale (older than 3 min but under 30 min)
      if (age > 180_000 && age < 1_800_000) {
        this.singleFlight
          .do(`swr:${cacheKey}`, async () => {
            try {
              const raw = await this.client.getHome({ gl });
              const mapped = mapHomeSections(raw, baseUrl);
              const rawBuf = Buffer.from(JSON.stringify(mapped), 'utf8');
              this.cache.set(cacheKey, { data: mapped, rawBuf, createdAt: Date.now() }, 1_800_000);
            } catch {}
          })
          .catch(() => {});
      }
      return cached;
    }

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.getHome({ gl });
      const mapped = mapHomeSections(raw, baseUrl);
      const rawBuf = Buffer.from(JSON.stringify(mapped), 'utf8');
      const entry = { data: mapped, rawBuf, createdAt: Date.now() };
      this.cache.set(cacheKey, entry, 1_800_000); // 30 min SWR TTL
      return entry;
    });
  }

  /**
   * P2: Pre-serialized UTF-8 buffer caching for instant warm search responses
   */
  async searchSongs({ query, gl = 'IN', baseUrl = '', signal = null }) {
    if (!query || !query.trim()) return { data: [], rawBuf: Buffer.from('[]', 'utf8') };
    const cleanQuery = query.trim().toLowerCase();
    const cacheKey = `search:${cleanQuery}:${gl}:${baseUrl}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.searchSongs({ query: cleanQuery, gl, signal });
      const mapped = mapSearchResults(raw, baseUrl);
      const rawBuf = Buffer.from(JSON.stringify(mapped), 'utf8');
      const entry = { data: mapped, rawBuf, createdAt: Date.now() };
      this.cache.set(cacheKey, entry, 600_000); // 10 min TTL
      return entry;
    });
  }

  async getNext({ videoId, playlistId, continuation, gl = 'IN', baseUrl = '', signal = null }) {
    let resolvedPlaylistId = playlistId;
    if (!resolvedPlaylistId && videoId) {
      resolvedPlaylistId = `RDAMVM${videoId}`;
    }

    const cacheKey = continuation
      ? `next:cont:${continuation}:${baseUrl}`
      : `next:root:${videoId || ''}:${resolvedPlaylistId || ''}:${gl}:${baseUrl}`;

    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.getNext({
        videoId,
        playlistId: resolvedPlaylistId,
        continuation,
        gl,
        signal,
      });
      const mapped = mapNextQueue(raw, continuation, baseUrl);
      const rawBuf = Buffer.from(JSON.stringify(mapped), 'utf8');
      const ttl = continuation ? 600_000 : 300_000;
      const entry = { data: mapped, rawBuf, createdAt: Date.now() };
      this.cache.set(cacheKey, entry, ttl);
      return entry;
    });
  }

  async browse({ browseId, gl = 'IN', baseUrl = '', signal = null }) {
    if (!browseId) throw new Error('browseId required');
    const cacheKey = `browse:${browseId}:${gl}:${baseUrl}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.browse({ browseId, gl, signal });
      const mapped = mapBrowseResult(raw, browseId, baseUrl);
      const rawBuf = Buffer.from(JSON.stringify(mapped), 'utf8');
      const entry = { data: mapped, rawBuf, createdAt: Date.now() };
      this.cache.set(cacheKey, entry, 900_000); // 15 min TTL
      return entry;
    });
  }

  async getSuggestions({ query, signal = null }) {
    if (!query || !query.trim()) return [];
    const cacheKey = `suggest:${query.trim().toLowerCase()}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const list = await this.client.getSuggestions({ query, signal });
      this.cache.set(cacheKey, list, 86_400_000); // 24h TTL
      return list;
    });
  }

  /**
   * P2: Debounced Top-1 Suggestion Pre-Warm with Per-Client Cancellation
   * Fires a low-priority, single-flight background fetch for Top-1 suggestion only.
   */
  prewarmTopSuggestion(topQuery, clientIp = '127.0.0.1', gl = 'IN') {
    if (!topQuery || typeof topQuery !== 'string') return;

    // 1. Abort previous pending pre-warm for this client
    const prev = this.suggestWarmControllers.get(clientIp);
    if (prev) {
      prev.abort();
    }

    // 2. Strict concurrency cap of 2 to prevent upstream 429 rate-limiting
    if (this.activeSuggestWarms >= 2) return;

    const controller = new AbortController();
    this.suggestWarmControllers.set(clientIp, controller);
    this.activeSuggestWarms++;

    this.searchSongs({ query: topQuery, gl, signal: controller.signal })
      .catch(() => {})
      .finally(() => {
        this.activeSuggestWarms = Math.max(0, this.activeSuggestWarms - 1);
        if (this.suggestWarmControllers.get(clientIp) === controller) {
          this.suggestWarmControllers.delete(clientIp);
        }
      });
  }

  /**
   * Scrapes external playlists (Spotify, Apple Music) and caches results.
   */
  async scrapePlaylist(url, { signal = null } = {}) {
    return this.playlistScraper.scrape(url, { signal });
  }
}
