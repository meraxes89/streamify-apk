import { parseRange } from '../../lib/range.js';
import { StreamSemaphore } from '../../lib/semaphore.js';
import { StreamPacer } from './pacer.js';
import { StreamWatchdog } from './watchdog.js';

export class StreamGateway {
  constructor({
    resolver,
    cache,
    semaphore = new StreamSemaphore(),
    pacer = new StreamPacer(),
    watchdog = new StreamWatchdog({ healthManager: resolver?.healthManager }),
  } = {}) {
    this.resolver = resolver;
    this.cache = cache;
    this.semaphore = semaphore;
    this.pacer = pacer;
    this.watchdog = watchdog;

    // Phase 1+2: On watchdog throttle detection, evict cached URL to trigger ladder escalation
    if (this.watchdog && this.resolver) {
      this.watchdog.on('throttled', ({ videoId }) => {
        if (videoId) {
          this.resolver.evict?.(videoId);
        }
      });
    }
  }

  async handleRequest(req, reply) {
    const videoId = req.params.videoId || req.query.id || req.query.videoId;
    if (!videoId) {
      reply.status(400);
      return reply.send('videoId required');
    }

    // Acquire stream concurrency slot
    const clientIp = req.headers['x-forwarded-for'] || req.ip || '127.0.0.1';
    const permit = this.semaphore.acquire(String(clientIp));
    if (!permit.allowed) {
      reply.header('Retry-After', permit.retryAfter);
      reply.status(503);
      return reply.send('Maximum concurrent audio streams exceeded');
    }

    req.raw.on('close', () => {
      permit.release();
    });

    let desc;
    try {
      desc = await this.resolver.resolve(videoId);
    } catch (err) {
      reply.status(502);
      return reply.send(`Resolve error: ${err.message}`);
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

    // Handle HEAD request
    if (req.method === 'HEAD') {
      if (totalLength) {
        reply.header('Content-Length', totalLength);
      }
      return reply.status(200).send();
    }

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

    // Response headers for 206 Partial Content or 200
    if (actualLength !== undefined) {
      reply.header('Content-Length', actualLength);
    }
    if (totalLength && (range || actualEnd !== undefined)) {
      reply.status(206);
      reply.header('Content-Range', `bytes ${start}-${actualEnd}/${totalLength}`);
    } else {
      reply.status(200);
    }

    // P1 Slab Optimization: Direct read for small ranges <= 256 KiB if blocks are already PRESENT on disk/cache
    if (
      actualLength !== undefined &&
      actualLength <= 256 * 1024 &&
      this.cache.hasRange(desc.videoId, desc.itag, start, actualEnd)
    ) {
      const buf = await this.cache.readDirect(desc, start, actualLength);
      return reply.send(buf);
    }

    // P0 Sparse Block Engine: Single upstream fetch with zero-copy page cache read-while-write multiplexing
    const rawStream = this.cache.createRangeStream(
      desc,
      start,
      actualEnd,
      req.raw.signal || req.raw
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
      signal: req.raw.signal || req.raw,
    });
    return reply.send(pacedStream);
  }
}
