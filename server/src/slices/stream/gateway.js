import { parseRange } from '../../lib/range.js';
import { AsyncAdmission, SaturatedError, TimeoutError, AbortError, PRIORITY } from './admission.js';
import { asAbortSignal } from './coalescer.js';
import { StreamCoalescer } from './coalescer.js';
import { StreamPacer } from './pacer.js';
import { StreamWatchdog } from './watchdog.js';
import { SabrEngine } from './sabr-engine.js';

export class StreamGateway {
  /**
   * @param {Object} options
   * @param {Object} options.resolver
   * @param {Object} options.cache
   * @param {Object} [options.admission] - L3 Admission Gate (defaults to AsyncAdmission)
   * @param {Object} [options.semaphore] - Backward-compatible alias for admission/semaphore
   * @param {Object} [options.coalescer] - L2 Request Coalescer
   * @param {Object} [options.pacer]
   * @param {Object} [options.watchdog]
   * @param {Object} [options.sabrEngine]
   */
  constructor({
    resolver,
    cache,
    admission,
    semaphore,
    coalescer,
    pacer = new StreamPacer(),
    watchdog = new StreamWatchdog({ healthManager: resolver?.healthManager }),
    sabrEngine = new SabrEngine(),
  } = {}) {
    this.resolver = resolver;
    this.cache = cache;

    // Use AsyncAdmission (or wrap / adapt legacy StreamSemaphore if provided)
    if (admission) {
      this.admission = admission;
    } else if (semaphore && typeof semaphore.acquire === 'function' && semaphore.globalMax !== undefined) {
      this.admission = semaphore;
    } else {
      this.admission = new AsyncAdmission({ globalMax: semaphore?.maxGlobal || 24, perIpMax: semaphore?.maxPerIp || 4 });
    }

    // Retain this.semaphore accessor for telemetry & legacy contract tests
    this.semaphore = this.admission;

    // L2 Request Coalescer
    this.coalescer = coalescer || (cache ? new StreamCoalescer({ cache: this.cache, admission: this.admission }) : null);
    this.pacer = pacer;
    this.watchdog = watchdog;
    this.sabrEngine = sabrEngine;

    // Phase 1+2: On watchdog throttle detection, evict cached URL to trigger ladder escalation
    if (this.watchdog && this.resolver) {
      this.watchdog.on('throttled', ({ videoId }) => {
        if (videoId) {
          this.resolver.evict?.(videoId);
        }
      });
    }
  }

  /**
   * Classifies request intent for priority admission
   * 0: playback, 1: seek, 2: segment, 3: prefetch
   */
  _classifyIntent(req, range) {
    if (req.headers && (req.headers['x-purpose'] === 'prefetch' || req.query?.prefetch === '1')) {
      return PRIORITY.PREFETCH;
    }
    const url = req.url || req.raw?.url || '';
    if (url.endsWith('.m4s') || url.includes('/segment/')) {
      return PRIORITY.SEGMENT;
    }
    if (range && range.start > 0) {
      return PRIORITY.SEEK;
    }
    return PRIORITY.PLAYBACK;
  }

  async handleRequest(req, reply) {
    const videoId = req.params.videoId || req.query.id || req.query.videoId;
    if (!videoId) {
      reply.status(400);
      return reply.send('videoId required');
    }

    const clientIp = String(req.headers['x-forwarded-for'] || req.ip || '127.0.0.1');

    // 1. Resolve descriptor (coalesced via SingleFlight inside resolver)
    let desc;
    try {
      desc = await this.resolver.resolve(videoId);
    } catch (err) {
      reply.status(502);
      return reply.send(`Resolve error: ${err.message}`);
    }

    // L4 SABR Stream handling
    if (desc.isSabr && this.sabrEngine) {
      reply
        .header('Content-Type', desc.mimeType)
        .header('Access-Control-Allow-Origin', '*')
        .header('Transfer-Encoding', 'chunked');

      const sabrRes = this.sabrEngine.createStream({
        videoId: desc.videoId,
        serverAbrStreamingUrl: desc.serverAbrStreamingUrl || desc.targetUrl,
        videoPlaybackUstreamerConfig: desc.videoPlaybackUstreamerConfig || '',
        formats: desc.formats || [],
        poToken: desc.poToken,
        onMintPoToken: () => this.resolver?.attestClient?.mintPoToken?.(desc.videoId),
      });

      return reply.send(sabrRes.stream);
    }

    const targetUrl = desc.targetUrl;
    try {
      const parsedUrl = new URL(targetUrl);
      if (!parsedUrl.hostname.endsWith('.googlevideo.com')) {
        reply.status(403);
        return reply.send('Forbidden upstream domain');
      }
    } catch {
      reply.status(400);
      return reply.send('Invalid stream target');
    }

    let totalLength = desc.contentLength;
    if (!totalLength || totalLength <= 0) {
      try {
        const head = await fetch(targetUrl, { method: 'HEAD', signal: AbortSignal.timeout(3000) });
        const cl = head.headers.get('content-length');
        if (cl) totalLength = parseInt(cl, 10);
      } catch {}
    }

    // P1: Disable Nagle's algorithm on streaming sockets to eliminate delayed-ACK stalls
    if (req.raw?.socket && typeof req.raw.socket.setNoDelay === 'function') {
      req.raw.socket.setNoDelay(true);
    }

    // Common response headers
    reply
      .header('Accept-Ranges', 'bytes')
      .header('Content-Type', desc.mimeType)
      .header('Access-Control-Allow-Origin', '*')
      .header('Access-Control-Expose-Headers', 'Content-Range, Content-Length, Accept-Ranges');

    // Handle HEAD request (ZERO upstream work, instant reply)
    if (req.method === 'HEAD') {
      if (totalLength) {
        reply.header('Content-Length', totalLength);
      }
      return reply.status(200).send();
    }

    // Node-version hardening: IncomingMessage is not an EventTarget
    // before Node 23 — normalize once, before every consumer below
    // (the cache-hit path, the coalescer, admission, the range stream).
    const clientSignal = asAbortSignal(req.raw.signal) || asAbortSignal(req.raw);

    const rangeHeader = req.headers.range;
    let range = null;

    if (totalLength && rangeHeader) {
      range = parseRange(rangeHeader, totalLength);
      if (range && !range.satisfiable) {
        reply.header('Content-Range', `bytes */${totalLength}`);
        return reply.status(416).send('Range Not Satisfiable');
      }
    }

    const start = range?.start ?? 0;
    const end = range?.end ?? (totalLength ? totalLength - 1 : undefined);
    const actualEnd = end !== undefined ? end : totalLength ? totalLength - 1 : undefined;
    const actualLength = actualEnd !== undefined ? actualEnd - start + 1 : undefined;

    // Response status and headers
    if (actualLength !== undefined) {
      reply.header('Content-Length', actualLength);
    }
    if (totalLength && (range || actualEnd !== undefined)) {
      reply.status(206);
      reply.header('Content-Range', `bytes ${start}-${actualEnd}/${totalLength}`);
    } else {
      reply.status(200);
    }

    // =========================================================================
    // L1: CACHE HIT? (comm12.md)
    // Check if the requested range is already PRESENT in the cache.
    // Cache hits consume ZERO admission permits and trigger ZERO upstream fetches.
    // =========================================================================
    const isCacheHit = this.cache && this.cache.hasRange(desc.videoId, desc.itag || 251, start, actualEnd);

    if (isCacheHit) {
      // Slab Optimization: Direct synchronous read for small ranges <= 256 KiB
      if (actualLength !== undefined && actualLength <= 256 * 1024) {
        const buf = await this.cache.readDirect(desc, start, actualLength);
        return reply.send(buf);
      }

      // Independent cache read stream (client has its OWN fs stream & backpressure)
      const cachedStream = this.cache.createRangeStream(
        desc,
        start,
        actualEnd,
        clientSignal
      );
      return reply.send(cachedStream);
    }

    // =========================================================================
    // L2 COALESCER & L3 ADMISSION GATE (comm12.md)
    // For cold misses, coordinate through the coalescer.
    // Followers attach to the leader's block readiness and consume NO admission permit.
    // Only the unique cold leader acquires an admission slot in L3.
    // =========================================================================
    const startBlock = Math.floor(start / (256 * 1024));
    const intent = this._classifyIntent(req, range);

    if (this.coalescer) {
      try {
        const coalEntry = this.coalescer.join(desc, startBlock, {
          priority: intent,
          clientIp,
          signal: clientSignal,
        });

        // Wait until Block 0 (or target block) lands in cache
        await coalEntry.ready;
      } catch (err) {
        if (err instanceof SaturatedError) {
          reply.header('Retry-After', this.admission.retryAfter || 2);
          reply.status(503);
          return reply.send('Upstream admission queue saturated');
        }
        if (err instanceof TimeoutError) {
          reply.status(504);
          return reply.send('Gateway timeout waiting for upstream admission');
        }
        if (err instanceof AbortError) {
          return; // Client disconnected
        }
        // Fallback / upstream error
        reply.status(502);
        return reply.send(`Upstream streaming error: ${err.message}`);
      }
    } else {
      // Direct admission fallback if no coalescer configured
      let releaseAdmission = null;
      try {
        releaseAdmission = await this.admission.acquire(clientIp, intent, clientSignal);
        req.raw.on('close', () => {
          if (releaseAdmission) releaseAdmission();
        });
      } catch (err) {
        if (err instanceof SaturatedError) {
          reply.header('Retry-After', 2);
          reply.status(503);
          return reply.send('Maximum concurrent audio streams exceeded');
        }
        throw err;
      }
    }

    // =========================================================================
    // L4: SERVE RANGE FROM CACHE (comm12.md)
    // Every client (leader or follower) reads independently from the block cache.
    // Each client gets its own backpressure and its own fs read stream.
    // =========================================================================
    const rawStream = this.cache.createRangeStream(
      desc,
      start,
      actualEnd,
      clientSignal
    );

    // P1 Attestation Watchdog: Monitor real-time streaming bytes to detect ~50 kbps throttling
    const streamId = `${desc.videoId}_${Date.now()}_${Math.random().toString(36).slice(2)}`;
    if (this.watchdog) {
      this.watchdog.watch(streamId, desc.client, desc.videoId);
      rawStream.on('data', (c) => this.watchdog.recordBytes(streamId, c.length));
      req.raw.on('close', () => this.watchdog.unwatch(streamId));
    }

    // P1 Wi-Fi Bufferbloat Pacing: Bypass probes <= 256 KiB; pace large/open streams with 2-phase token bucket
    const isProbeOrSmallRange = actualLength !== undefined && actualLength <= 256 * 1024;
    if (isProbeOrSmallRange || !this.pacer) {
      return reply.send(rawStream);
    }

    const pacedStream = this.pacer.pace(rawStream, {
      bitrateBps: desc.bitrateBps || 160_000,
      signal: clientSignal,
    });
    return reply.send(pacedStream);
  }
}
