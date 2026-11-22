/**
 * AmazonIntegratedEngine
 *
 * Bridges amazon-catalog-engine with server/src low-level infrastructure primitives:
 * - LruCache & SingleFlight (server/src/lib)
 * - StreamPacer, PacingWheel & PacedStreamTransform (server/src/slices/stream/pacer.js)
 * - AsyncAdmission & PRIORITY (server/src/slices/stream/admission.js)
 * - TierGovernor (server/src/platform/tier/governor.js)
 * - SparseBlockCache & BLOCK_SIZE (server/src/slices/stream/sparse-block-cache.js)
 *
 * Strict Constraint: Reads server/src primitives without modifying server/src files.
 *
 * Lossless pipeline (comm21 elite architecture):
 *   resolveLossless -> probeNativeCapability (DMLS verdict) -> acquireLosslessFullSong
 *   (real bytes via HttpAudioSource / relay) -> FLAC introspection -> metadata-first
 *   remux -> frame-aligned 206 gateway -> two-phase paced delivery.
 */

import { LruCache } from '../../server/src/lib/cache.js';
import { SingleFlight } from '../../server/src/lib/single-flight.js';
import { StreamPacer, PacingWheel, PacedStreamTransform, BURST_TOKENS, MAX_STEADY_TOKENS } from '../../server/src/slices/stream/pacer.js';
import { AsyncAdmission, PRIORITY } from '../../server/src/slices/stream/admission.js';
import { TierGovernor } from '../../server/src/platform/tier/governor.js';
import { SparseBlockCache, BLOCK_SIZE } from '../../server/src/slices/stream/sparse-block-cache.js';

import { AmazonCatalogClient } from './client.mjs';
import { AmazonLyricsService } from './lyrics.mjs';
import { AmazonStreamResolver } from './resolver.mjs';
import { LosslessFullSongService } from './lossless-fullsong.mjs';
import { AmazonNativeDeliveryService } from './native-delivery.mjs';
import { HttpAudioSource } from './audio-source.mjs';
import { Readable } from 'stream';

export class AmazonIntegratedEngine {
  constructor({
    country = 'US',
    cacheMaxItems = 1000,
    maxGlobalStreams = 16,
    ttlMs = 300_000, // 5 min default TTL
    pal = null,
    streamsDir = null,
    maxCacheMb = 256,
    relayBaseUrl = process.env.STREAMIFY_RELAY_BASE || null,
    sessionManager = null,
    losslessFullSongService = null
  } = {}) {
    this.country = country.toUpperCase();

    // 1. Core Amazon Catalog Client & Resolver (real byte acquisition wired)
    this.audioSource = new HttpAudioSource({ baseUrl: relayBaseUrl });
    this.client = new AmazonCatalogClient({
      sessionManager: sessionManager || undefined,
      relayBaseUrl,
    });
    this.streamResolver = new AmazonStreamResolver({
      sessionManager: sessionManager || this.client.session,
      nativeDelivery: this.client.nativeDelivery,
    });
    this.nativeDelivery = this.client.nativeDelivery ||
      new AmazonNativeDeliveryService({ sessionManager: this.client.session });
    this.losslessFullSongService = losslessFullSongService ||
      new LosslessFullSongService({ audioSource: this.audioSource });

    // 2. High-performance Infrastructure Primitives from server/src
    this.cache = new LruCache({ maxItems: cacheMaxItems });
    this.singleFlight = new SingleFlight();
    this.lyricsService = new AmazonLyricsService({ cache: this.cache });
    this.admission = new AsyncAdmission({ maxGlobal: maxGlobalStreams });
    this.pacer = new StreamPacer();
    this.pacingWheel = new PacingWheel();
    this.governor = new TierGovernor({ pal });

    // 3. Sparse Block Cache (if storage dir provided)
    this.streamsDir = streamsDir;
    this.blockCache = streamsDir ? new SparseBlockCache({ streamsDir, maxCacheMb }) : null;

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
    this.pacingWheel.close();
    if (this.blockCache) {
      this.blockCache.close();
    }
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
      const data = await this.client.search(query, { territory: this.country, limit: 30 });
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
      const data = await this.client.getQueue(trackId, { territory: this.country });
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
   * Lossless full-song engineering: acquire the whole raw lossless audio
   * (FLAC metadata-first remux + frame-aligned geometry) for a full song via
   * the resolved top-tier lossless stream.
   */
  async acquireLosslessFullSong(trackMeta, { relayBaseUrl = null } = {}) {
    const descriptor = await this.streamResolver.resolveLossless(trackMeta);
    const artifact = await this.losslessFullSongService.acquireFullSong({
      streamUrl: descriptor.streamUrl,
      tier: descriptor.tier,
      codec: descriptor.codec,
      ...(relayBaseUrl ? { audioSource: async (o) => this.audioSource.load({ ...o, baseUrl: relayBaseUrl }) } : {}),
    });
    return artifact;
  }

  /**
   * Native DMLS capability card: the honest verdict on whether Amazon's own
   * delivery surface can serve this track (auth/entitlement/DRM walls included).
   */
  async probeNativeFlacCapability(trackMeta, { territory = this.country } = {}) {
    const track = typeof trackMeta === 'string'
      ? { asin: trackMeta.replace('amzn:track:', '') }
      : trackMeta;
    return this.nativeDelivery.probeCapability(track, { territory });
  }

  /**
   * Lossless full-song stream resolution, gated by the same admission +
   * pacing + cache infrastructure as regular playback (comm12-comm15).
   *
   * Resolves the track to the top-tier lossless rendition and returns the
   * paced stream handle so the caller can stream full songs without loss.
   */
  async resolveLosslessPacedStream(trackMeta, { priority = PRIORITY.NORMAL } = {}) {
    const releaseAdmission = await this.admission.acquire({ priority });
    this.governor.setActiveStreams(this.admission.activeStreams);

    try {
      const streamInfo = await this.streamResolver.resolveLossless(trackMeta);
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
   * Creates a stream pipeline that passes raw audio buffers through PacedStreamTransform
   * throttled at playback bitrate according to server/src pacer rules.
   */
  createPacedAudioPipeline(inputStream, { bitrate = 160_000, isVip = false } = {}) {
    const transform = new PacedStreamTransform({
      wheel: this.pacingWheel,
      playbackBitrate: bitrate,
      burstTokens: BURST_TOKENS,
      maxSteadyTokens: MAX_STEADY_TOKENS,
      isVip
    });

    return inputStream.pipe(transform);
  }

  /**
   * Caches an audio block into SparseBlockCache.
   *
   * Synchronous entry allocation ( SparseBlockCache.getEntry ). When a buffer
   * is supplied, an honest write-through is scheduled via writeBlock():
   * positional-pwrite at blockIndex * BLOCK_SIZE + present-block marking,
   * exactly the geometry the server relay uses.
   */
  storeBlock(trackId, profile, blockIndex = 0, buffer = null) {
    if (!this.blockCache) return null;

    const entry = this.blockCache.getEntry({
      videoId: String(trackId),
      itag: String(profile),
      ...(buffer && buffer.length ? { contentLength: Math.max((blockIndex + 1) * BLOCK_SIZE, buffer.length) } : {})
    });

    if (buffer && buffer.length > 0) {
      // Fire-and-forget warm-ahead write; failures leave the block EMPTY
      // so readers re-fetch (the cache self-heals).
      this.writeBlock(entry, blockIndex, buffer).catch(() => {});
    }

    return entry;
  }

  /**
   * Honest positional write-through into a SparseBlockCache entry.
   */
  async writeBlock(entry, blockIndex, buffer) {
    const fh = await entry.getHandle();
    const bytes = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
    const offset = blockIndex * BLOCK_SIZE;
    await fh.write(bytes, 0, bytes.length, offset);
    if (typeof entry.updatePresentBlocks === 'function') {
      entry.updatePresentBlocks(offset, offset + bytes.length);
    }
    entry.lastAccessed = Date.now();
    return entry;
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
      activeFlights: this.singleFlight.size(),
      hasBlockCache: !!this.blockCache,
      relayBaseUrl: this.audioSource.baseUrl || null,
      country: this.country
    };
  }
}
