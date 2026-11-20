import { YtmClient } from './ytm-client.js';
import {
  mapHomeSections,
  mapSearchResults,
  mapNextQueue,
  mapBrowseResult,
} from './mappers.js';
import { LruCache } from '../../lib/cache.js';
import { SingleFlight } from '../../lib/single-flight.js';

export class CatalogService {
  constructor({ ytmClient = new YtmClient(), cache = new LruCache({ maxItems: 1000 }) } = {}) {
    this.client = ytmClient;
    this.cache = cache;
    this.singleFlight = new SingleFlight();
  }

  async getHome({ gl = 'IN', baseUrl = '' } = {}) {
    const cacheKey = `home:${gl}:${baseUrl}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.getHome({ gl });
      const mapped = mapHomeSections(raw, baseUrl);
      this.cache.set(cacheKey, mapped, 180_000); // 3 min TTL
      return mapped;
    });
  }

  async searchSongs({ query, gl = 'IN', baseUrl = '' }) {
    if (!query || !query.trim()) return [];
    const cacheKey = `search:${query.trim().toLowerCase()}:${gl}:${baseUrl}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.searchSongs({ query, gl });
      const mapped = mapSearchResults(raw, baseUrl);
      this.cache.set(cacheKey, mapped, 600_000); // 10 min TTL
      return mapped;
    });
  }

  async getNext({ videoId, playlistId, continuation, gl = 'IN', baseUrl = '' }) {
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
      });
      const mapped = mapNextQueue(raw, continuation, baseUrl);
      const ttl = continuation ? 600_000 : 300_000; // 10 min vs 5 min
      this.cache.set(cacheKey, mapped, ttl);
      return mapped;
    });
  }

  async browse({ browseId, gl = 'IN', baseUrl = '' }) {
    if (!browseId) throw new Error('browseId required');
    const cacheKey = `browse:${browseId}:${gl}:${baseUrl}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const raw = await this.client.browse({ browseId, gl });
      const mapped = mapBrowseResult(raw, browseId, baseUrl);
      this.cache.set(cacheKey, mapped, 900_000); // 15 min TTL
      return mapped;
    });
  }

  async getSuggestions({ query }) {
    if (!query || !query.trim()) return [];
    const cacheKey = `suggest:${query.trim().toLowerCase()}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      const list = await this.client.getSuggestions({ query });
      this.cache.set(cacheKey, list, 86_400_000); // 24h TTL
      return list;
    });
  }
}
