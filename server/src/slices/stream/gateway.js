import fs from 'node:fs';
import { Readable } from 'node:stream';
import { parseRange } from '../../lib/range.js';

import { StreamSemaphore } from '../../lib/semaphore.js';

const CHUNK_SIZE = 8 * 1024 * 1024; // 8 MiB bounded chunking

export class StreamGateway {
  constructor({ resolver, cache, semaphore = new StreamSemaphore() } = {}) {
    this.resolver = resolver;
    this.cache = cache;
    this.semaphore = semaphore;
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
        const head = await fetch(targetUrl, { method: 'HEAD' });
        const cl = head.headers.get('content-length');
        if (cl) totalLength = parseInt(cl, 10);
      } catch {}
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
    const length = range?.length ?? (end !== undefined ? end - start + 1 : undefined);

    // 1. Check complete disk cache file
    const completeFilePath = this.cache.hasComplete(desc.videoId, desc.itag);
    if (completeFilePath && totalLength) {
      const actualEnd = end !== undefined ? end : totalLength - 1;
      const actualLength = actualEnd - start + 1;

      reply
        .status(206)
        .header('Content-Range', `bytes ${start}-${actualEnd}/${totalLength}`)
        .header('Content-Length', actualLength);

      const stream = fs.createReadStream(completeFilePath, { start, end: actualEnd });
      return reply.send(stream);
    }

    // 2. Otherwise serve via bounded upstream range relay (and start disk writer)
    this.cache.startWriter(desc).catch(() => {});

    const chunkEnd =
      end !== undefined ? Math.min(start + CHUNK_SIZE - 1, end) : start + CHUNK_SIZE - 1;

    const upstreamHeaders = {
      Accept: '*/*',
      Range: `bytes=${start}-${chunkEnd}`,
      'User-Agent':
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    };

    const upstreamRes = await fetch(targetUrl, { headers: upstreamHeaders });
    const statusCode = upstreamRes.status === 206 || upstreamRes.status === 200 ? 206 : upstreamRes.status;

    if (upstreamRes.headers.get('content-range')) {
      reply.header('Content-Range', upstreamRes.headers.get('content-range'));
    } else if (totalLength) {
      reply.header('Content-Range', `bytes ${start}-${chunkEnd}/${totalLength}`);
    }

    if (upstreamRes.headers.get('content-length')) {
      reply.header('Content-Length', upstreamRes.headers.get('content-length'));
    } else if (length !== undefined) {
      reply.header('Content-Length', Math.min(length, chunkEnd - start + 1));
    }

    reply.status(statusCode);

    if (upstreamRes.body) {
      const nodeReadable = Readable.fromWeb(upstreamRes.body);
      return reply.send(nodeReadable);
    }
    return reply.send();
  }
}
