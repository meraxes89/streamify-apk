/**
 * Real Audio Byte Acquisition Layer
 *
 * Elite delivery contract (docs/nano/comm21.md):
 *   The pipeline never guesses what it delivers. Every byte stream is sniffed,
 *   introspected, and reported with its true codec, bit-perfect status, and
 *   actual bitrate. FLAC => lossless/bit-perfect. Everything else is labeled
 *   exactly as what it is, never decorated with aspirational tier names.
 *
 * Components:
 *  1. sniffAudioCodec(buffer)  - magic-byte codec/lossless/bit-perfect verdict
 *  2. HttpAudioSource          - full-song byte downloader (absolute URLs or
 *                                relay-relative /api/stream URLs) with retries,
 *                                timeouts, and size guards.
 */

const DEFAULT_MAX_BYTES = 512 * 1024 * 1024; // 512 MiB full-song ceiling
const DEFAULT_TIMEOUT_MS = 120_000;
const DEFAULT_RETRIES = 2;

/**
 * Codec verdict from leading magic bytes.
 * Honest by construction: lossless=true ONLY for true lossless codecs.
 */
export function sniffAudioCodec(buffer) {
  if (!buffer || buffer.length < 12) {
    return { codec: 'unknown', container: null, lossless: false, bitPerfect: false, detail: 'buffer too small' };
  }

  // Native endian magic checks (FLAC is big-endian structured, WAV is RIFF little-endian)
  const ascii4 = (o, n = 4) => buffer.toString('ascii', o, o + n);

  if (ascii4(0) === 'fLaC') {
    return { codec: 'flac', container: 'flac', lossless: true, bitPerfect: true, detail: 'FLAC bitstream (fLaC marker)' };
  }

  if (ascii4(0) === 'OggS') {
    // Opus vs Vorbis vs FLAC-in-Ogg
    const probe = buffer.toString('latin1', 0, Math.min(buffer.length, 512));
    if (probe.includes('OpusHead')) return { codec: 'opus', container: 'ogg', lossless: false, bitPerfect: false, detail: 'Ogg Opus (lossy)' };
    if (probe.includes('vorbis')) return { codec: 'vorbis', container: 'ogg', lossless: false, bitPerfect: false, detail: 'Ogg Vorbis (lossy)' };
    if (probe.includes('FLAC')) return { codec: 'flac', container: 'ogg', lossless: true, bitPerfect: true, detail: 'Ogg FLAC' };
    return { codec: 'ogg', container: 'ogg', lossless: false, bitPerfect: false, detail: 'Ogg container' };
  }

  if (ascii4(0) === 'RIFF' && ascii4(8) === 'WAVE') {
    return { codec: 'pcm', container: 'wav', lossless: true, bitPerfect: false, detail: 'RIFF WAVE PCM (lossless, raw)' };
  }

  if (ascii4(4, 4) === 'ftyp') {
    const brand = buffer.toString('ascii', 8, Math.min(buffer.length, 16));
    if (brand.startsWith('alac')) return { codec: 'alac', container: 'm4a', lossless: true, bitPerfect: true, detail: 'ALAC in M4A' };
    return { codec: 'aac', container: 'm4a', lossless: false, bitPerfect: false, detail: `M4A (${brand})` };
  }

  // MP3: ID3v2 tag or bare MPEG frame sync 0xFFEx/0xFFFx
  if (ascii4(0, 3) === 'ID3') {
    return { codec: 'mp3', container: 'mp3', lossless: false, bitPerfect: false, detail: 'MP3 (ID3v2)' };
  }
  if (buffer[0] === 0xff && (buffer[1] & 0xe0) === 0xe0) {
    return { codec: 'mp3', container: 'mp3', lossless: false, bitPerfect: false, detail: 'MP3 (frame sync)' };
  }

  if (ascii4(0) === 'wvpk') {
    return { codec: 'wv', container: 'wv', lossless: true, bitPerfect: true, detail: 'WavPack' };
  }
  if (ascii4(0) === 'MAC ') {
    return { codec: 'ape', container: 'ape', lossless: true, bitPerfect: true, detail: "Monkey's Audio" };
  }

  return { codec: 'unknown', container: null, lossless: false, bitPerfect: false, detail: 'no known magic' };
}

/**
 * Full-song byte downloader.
 *
 * Resolves relative relay URLs (/api/stream?id=...) against a relay base URL,
 * streams the whole body into memory with retry on transient failures.
 */
export class HttpAudioSource {
  constructor({
    baseUrl = null,
    userAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
    maxBytes = DEFAULT_MAX_BYTES,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    retries = DEFAULT_RETRIES
  } = {}) {
    this.baseUrl = baseUrl;
    this.userAgent = userAgent;
    this.maxBytes = maxBytes;
    this.timeoutMs = timeoutMs;
    this.retries = retries;
  }

  /**
   * @param {Object} options
   * @param {string} options.streamUrl - absolute URL or relay-relative /api/stream?id=...
   * @param {string} [options.baseUrl] - relay origin for relative URLs (or constructor baseUrl)
   * @returns {Promise<{bytes: Buffer, contentType: string|null, contentLength: number,
   *                    sourceUrl: string, codec: Object}>}
   */
  async load({ streamUrl, baseUrl } = {}) {
    if (!streamUrl || typeof streamUrl !== 'string') {
      throw new Error('streamUrl is required for full-song byte acquisition');
    }

    const base = baseUrl || this.baseUrl;
    let url = streamUrl;
    if (url.startsWith('/')) {
      if (!base) {
        throw new Error(
          `Relative stream URL "${streamUrl}" requires a relay baseUrl ` +
          `(pass relayBaseUrl to AmazonIntegratedEngine or baseUrl to this source)`
        );
      }
      url = base.replace(/\/+$/, '') + url;
    }

    let lastErr = null;
    for (let attempt = 0; attempt <= this.retries; attempt++) {
      try {
        return await this._fetchOnce(url);
      } catch (err) {
        lastErr = err;
        if (err.fatal) throw err;
        // transient (network / 5xx): retry with linear backoff
        await new Promise(r => setTimeout(r, 400 * (attempt + 1)));
      }
    }
    throw new Error(`Full-song acquisition failed after ${this.retries + 1} attempts: ${lastErr?.message || lastErr}`);
  }

  async _fetchOnce(url) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let res;
    try {
      res = await fetch(url, {
        headers: {
          'User-Agent': this.userAgent,
          'Accept': '*/*',
          'Range': 'bytes=0-'
        },
        signal: controller.signal,
        redirect: 'follow'
      });
    } finally {
      clearTimeout(timer);
    }

    if (!res.ok) {
      const err = new Error(`Audio source responded HTTP ${res.status} for ${url}`);
      err.fatal = res.status >= 400 && res.status < 500 && res.status !== 429;
      throw err;
    }

    const contentType = res.headers.get('content-type');
    const chunks = [];
    let total = 0;
    const reader = res.body.getReader();
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value || value.length === 0) continue;
      total += value.length;
      if (total > this.maxBytes) {
        try { reader.cancel(); } catch {}
        const err = new Error(`Full-song size ${total} exceeds maxBytes ${this.maxBytes}`);
        err.fatal = true;
        throw err;
      }
      chunks.push(Buffer.from(value));
    }

    const bytes = Buffer.concat(chunks);
    return {
      bytes,
      contentType,
      contentLength: total,
      sourceUrl: url,
      codec: sniffAudioCodec(bytes)
    };
  }
}
