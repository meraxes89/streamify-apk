/**
 * AmazonIntegratedEngine
 * 
 * Adapts and marries amazon-catalog-engine with server/src low-level infrastructure primitives:
 * - LruCache & SingleFlight (server/src/lib)
 * - StreamPacer & PacedStreamTransform (server/src/slices/stream/pacer.js)
 * - AsyncAdmission & PRIORITY (server/src/slices/stream/admission.js)
 * - TierGovernor (server/src/platform/tier/governor.js)
 * - SparseBlockCache (server/src/slices/stream/sparse-block-cache.js)
 * 
 * Strict Constraint: Reads server/src primitives without modifying server/src files.
 */

import { LruCache } from '../../server/src/lib/cache.js';
import { SingleFlight } from '../../server/src/lib/single-flight.js';
import { StreamPacer } from '../../server/src/slices/stream/pacer.js';
import { AsyncAdmission, PRIORITY } from '../../server/src/slices/stream/admission.js';
import { TierGovernor } from '../../server/src/platform/tier/governor.js';

import { AmazonCatalogClient } from './client.mjs';
import { AmazonLyricsService } from './lyrics.mjs';
import { AmazonStreamResolver } from './resolver.mjs';

export class AmazonIntegratedEngine {
  constructor({
    country = 'US',
    cacheMaxItems = 1000,
    maxGlobalStreams = 16,
    ttlMs = 300_000, // 5 min default TTL
    pal = null
  } = {}) {
    // 1. Core Amazon Catalog Client
    this.client = new AmazonCatalogClient({ country });
    this.streamResolver = new AmazonStreamResolver({ country });

    // 2. High-performance Infrastructure Primitives from server/src
    this.cache = new LruCache({ maxItems: cacheMaxItems });
    this.singleFlight = new SingleFlight();
    this.lyricsService = new AmazonLyricsService({ cache: this.cache });
    this.admission = new AsyncAdmission({ maxGlobal: maxGlobalStreams });
    this.pacer = new StreamPacer();
    this.governor = new TierGovernor({ pal });

    this.ttlMs = ttlMs;
  }

  /**
   * Initializes catalog and starts the tier governor.
   */
  async init() {
    this.governor.start();
    return this;
  }

  /**
   * Shuts down governors, pacers, and clears caches.
   */
  async close() {
    this.governor.stop();
    this.pacer.close();
    this.cache.clear();
  }

  /**
   * Cached & Single-Flight protected Track Search.
   */
  async search(query, filter = 'track') {
    const key = `search:${filter}:${query.toLowerCase().trim()}`;
    const cached = this.cache.get(key);
    if (cached) return cached;

    return this.singleFlight.do(key, async () => {
      const data = await this.client.search(query, filter);
      this.cache.set(key, data, this.ttlMs);
      return data;
    });
  }

  /**
   * Cached & Single-Flight protected Queue Lookup.
   */
  async getQueue(trackId) {
    const key = `queue:${trackId}`;
    const cached = this.cache.get(key);
    if (cached) return cached;

    return this.singleFlight.do(key, async () => {
      const data = await this.client.getQueue(trackId);
      this.cache.set(key, data, this.ttlMs);
      return data;
    });
  }

  /**
   * Cached & Single-Flight protected Lyrics with millisecond sync cues.
   */
  async getLyrics(track, artist, durationSeconds = null) {
    const key = `lyrics:${track.toLowerCase()}:${artist.toLowerCase()}`;
    const cached = this.cache.get(key);
    if (cached) return cached;

    return this.singleFlight.do(key, async () => {
      const data = await this.lyricsService.getLyrics(track, artist, durationSeconds);
      this.cache.set(key, data, 7 * 86_400_000); // 7-day TTL for lyrics
      return data;
    });
  }

  /**
   * Admission-gated, paced stream resolution.
   * Acquires concurrency admission slot before resolving stream.
   */
  async resolvePacedStream(trackMeta, { priority = PRIORITY.NORMAL } = {}) {
    const releaseAdmission = await this.admission.acquire({ priority });
    this.governor.setActiveStreams(this.admission.activeStreams);

    try {
      const streamInfo = await this.streamResolver.resolve(trackMeta);
      const shouldPace = this.pacer.shouldPace();

      return {
        ...streamInfo,
        shouldPace,
        activeStreams: this.admission.activeStreams,
        governorTier: this.governor.getStatus().tier,
        release: () => {
          releaseAdmission();
          this.governor.setActiveStreams(this.admission.activeStreams);
        }
      };
    } catch (err) {
      releaseAdmission();
      this.governor.setActiveStreams(this.admission.activeStreams);
      throw err;
    }
  }

  /**
   * Status metrics combining Amazon catalog health with server infrastructure metrics.
   */
  getDiagnostics() {
    return {
      engine: 'AmazonIntegratedEngine',
      governor: this.governor.getStatus(),
      admission: this.admission.getMetrics(),
      cacheSize: this.cache.size(),
      activeFlights: this.singleFlight.size()
    };
  }
}
