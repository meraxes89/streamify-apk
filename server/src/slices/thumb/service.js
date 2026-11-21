import { isAllowedThumbnailUrl } from './allowlist.js';

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36';
const MAX_BYTES = 2 * 1024 * 1024; // 2 MB cap

export class ThumbService {
  constructor({ timeoutMs = 6000 } = {}) {
    this.timeoutMs = timeoutMs;
  }

  async fetchThumbnail(rawUrl) {
    if (!rawUrl) {
      throw new Error('url parameter required');
    }

    if (!isAllowedThumbnailUrl(rawUrl)) {
      const err = new Error('Thumbnail domain not allowed');
      err.statusCode = 403;
      throw err;
    }

    const res = await fetch(rawUrl, {
      headers: { 'User-Agent': USER_AGENT },
      redirect: 'error', // Harden against SSRF redirects
      signal: AbortSignal.timeout(this.timeoutMs),
    });

    if (!res.ok) {
      const err = new Error(`Failed to fetch image upstream: ${res.status}`);
      err.statusCode = res.status;
      throw err;
    }

    const contentType = res.headers.get('content-type') || 'image/jpeg';
    if (!contentType.startsWith('image/')) {
      const err = new Error('Upstream response is not an image');
      err.statusCode = 415;
      throw err;
    }
    const buffer = await res.arrayBuffer();
    if (buffer.byteLength > MAX_BYTES) {
      const err = new Error('Image exceeds 2MB limit');
      err.statusCode = 413;
      throw err;
    }

    return {
      contentType,
      buffer: Buffer.from(buffer),
    };
  }
}
