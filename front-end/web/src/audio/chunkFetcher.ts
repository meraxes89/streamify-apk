/**
 * audio/chunkFetcher.ts — byte-range chunk fetcher over the relay
 * (W1.3).
 *
 * The spine never downloads a whole media URL: it walks the file in
 * 256 KiB range chunks so the demuxer starts emitting packets after
 * the first chunk and seek is just "next chunk at a new offset"
 * (epoch flush, W1.4/W1.5).
 *
 * REDIRECT PROTOCOL — the deliberate 302 dance
 * ─────────────────────────────────────────────
 * googlevideo `videoplayback` answers with 302 + `Location` to a
 * per-edge URL on a fresh probe (and when `ratebypass`/`rn` params go
 * stale). The RELAY performs its upstream fetch with
 * `redirect: 'manual'` and passes any 3xx STRAIGHT THROUGH with
 * `Location` in `Access-Control-Expose-Headers` (W1.0 relay update).
 * Why not let the relay follow server-side? Two reasons:
 *
 *   1. Browsers cannot read a 302's Location themselves
 *      (`redirect:'manual'` in the page yields an opaque-redirect,
 *      status 0, headers empty) — so the relay must surface it, and
 *      once it does, the CLIENT owns the hop decision.
 *   2. Amortization: the hop-resolved edge URL is stable for minutes.
 *      Caching it here means chunk #1 pays one redirect round-trip
 *      and chunks #2..N hit the edge directly instead of paying the
 *      hop through the relay EVERY chunk (server-side follow would
 *      silently do that).
 *
 * Hops are BOUNDED (default 5) — a redirect loop must die, not pin
 * the CPU. Each hop re-checks the relay allowlist contract (the relay
 * re-validates every target; the fetcher trusts but logs).
 *
 * STATUS FIDELITY: 206 stays 206. A 200 in response to a Range
 * request (upstream ignored the header — seen on some edges) is
 * handled by treating the body as byte 0.. and slicing: correctness
 * over luck.
 *
 * UA NORMALIZATION: requests carrying `X-YouTube-Client-Name` (APP
 * clients — the resolver's ANDROID_VR ladder rung) get the matrix UA
 * applied by the RELAY (P0 rule); the fetcher just forwards the
 * header. Direct (relay-less) URLs keep whatever UA the environment
 * has — only used by tooling, never the page (CORS).
 */

/** Tunables; the defaults are the P1 task-book values. */
export interface ChunkFetcherOptions {
  /**
   * Relay base (`https://…workers.dev`). When null the URL is fetched
   * DIRECTLY — allowed in Node/tooling, always wrong in the page
   * (googlevideo sends no ACAO) — the code doesn't police this, the
   * browser will.
   */
  relayBase: string | null;
  /** Optional innertube client number for the relay's UA matrix. */
  clientName?: string | null;
  /** Chunk size in bytes (default 256 × 1024). */
  chunkSize?: number;
  /** Max redirect hops per request (default 5). */
  maxRedirects?: number;
  /** Retries per chunk on network error / 5xx / 429 (default 3). */
  retries?: number;
  /** Abort everything (probe + chunks in flight). */
  signal?: AbortSignal | null;
  /** Injectable fetch (tests / Node adapters). Defaults to global. */
  fetchImpl?: typeof fetch;
  /**
   * URL-rotation hook: called when the stream URL answers 403/410
   * (googlevideo expires/limits URLs). Must return a FRESH direct
   * URL for the same stream (a re-resolve, not a redirect follow).
   * Up to 2 rotations per chunk; absent → 403 is a hard failure.
   */
  onStaleUrl?: () => Promise<string>;
}

/** Result of the 1-byte length probe. */
export interface ProbeResult {
  /** Total byte length from `Content-Range: bytes 0-0/LEN` (0 = "*"). */
  contentLength: number;
  /** True when the resolved URL answers ranges with 206. */
  acceptsRanges: boolean;
  /** The hop-resolved URL (cache this for chunk fetches). */
  resolvedUrl: string;
}

/** One chunk fetch outcome. */
export interface Chunk {
  data: Uint8Array;
  /** Absolute file offset of data[0]. */
  offset: number;
}

const DEFAULT_CHUNK_SIZE = 256 * 1024;
const MAX_REDIRECTS_DEFAULT = 5;
const RETRIES_DEFAULT = 3;

/** Redirect statuses the hop loop understands. */
function isRedirect(status: number): boolean {
  return (
    status === 301 || status === 302 || status === 303 || status === 307 || status === 308
  );
}

/** Backoff between retries: 250ms × 2^attempt, capped at 2s. */
function backoffMs(attempt: number): number {
  return Math.min(250 * 2 ** attempt, 2000);
}

export class ChunkFetcher {
  private readonly opts: Required<
    Pick<ChunkFetcherOptions, 'chunkSize' | 'maxRedirects' | 'retries'>
  > &
    ChunkFetcherOptions;
  private readonly doFetch: typeof fetch;
  /** Redirect-resolved URL (set by probe / first chunk; kept sticky). */
  private resolvedUrl: string | null = null;

  constructor(opts: ChunkFetcherOptions) {
    this.opts = {
      chunkSize: opts.chunkSize ?? DEFAULT_CHUNK_SIZE,
      maxRedirects: opts.maxRedirects ?? MAX_REDIRECTS_DEFAULT,
      retries: opts.retries ?? RETRIES_DEFAULT,
      ...opts,
    };
    this.doFetch = opts.fetchImpl ?? fetch.bind(globalThis);
  }

  /** Wrap `url` through the relay (or pass through when relay-less). */
  private wrap(url: string): string {
    const { relayBase } = this.opts;
    if (relayBase === null || relayBase === '') return url;
    const sep = relayBase.endsWith('/') ? '' : '/';
    // Vendored rustypipe relay contract: GET {base}/r?u={urlencoded}.
    return `${relayBase}${sep}r?u=${encodeURIComponent(url)}`;
  }

  /** Request headers shared by probe and chunk fetches. */
  private baseHeaders(range: string | null): Record<string, string> {
    const h: Record<string, string> = { accept: '*/*' };
    if (range !== null) h.range = range;
    if (this.opts.clientName != null && this.opts.clientName !== '') {
      h['x-youtube-client-name'] = this.opts.clientName;
    }
    return h;
  }

  private aborted(): boolean {
    return this.opts.signal?.aborted ?? false;
  }

  /**
   * Fetch `url` following relay-surfaced redirects up to the hop
   * budget. Non-2xx/3xx statuses are RETURNED (caller decides retry),
   * network throws propagate after the internal hop loop gives up.
   */
  private async fetchWithHops(
    url: string,
    headers: Record<string, string>,
  ): Promise<Response> {
    let target = url;
    for (let hop = 0; hop <= this.opts.maxRedirects; hop++) {
      if (this.aborted()) throw new DOMException('aborted', 'AbortError');
      // redirect:'follow' ON THE RELAY URL is correct: the relay itself
      // never 3xx's; upstream 3xx arrives as a normal readable response
      // (the relay surfaced it — see file header). A direct URL that
      // 3xx's is outside the browser contract (tooling only) and the
      // browser's own follow would kick in harmlessly.
      const res = await this.doFetch(this.wrap(target), { headers });
      if (isRedirect(res.status)) {
        const location = res.headers.get('location');
        // Drain so the connection can be reused, then hop.
        try {
          await res.body?.cancel();
        } catch {
          /* already gone */
        }
        if (location === null) {
          throw new Error(`relay surfaced ${res.status} without Location`);
        }
        target = new URL(location, target).toString();
        continue;
      }
      // Remember the hop-resolved target for later chunks (amortize).
      if (this.resolvedUrl !== target) this.resolvedUrl = target;
      return res;
    }
    throw new Error(`redirect budget exhausted (${this.opts.maxRedirects} hops)`);
  }

  /**
   * 1-byte probe: learn the total length and whether the resolved URL
   * honors ranges, plus cache the edge URL for chunk fetches.
   */
  async probe(url: string): Promise<ProbeResult> {
    const headers = this.baseHeaders('bytes=0-0');
    const res = await this.fetchWithRetries(url, headers);
    if (!res.ok) {
      throw new Error(`probe failed: ${res.status}`);
    }
    const range = res.headers.get('content-range'); // bytes 0-0/LEN
    let contentLength = 0;
    let acceptsRanges = res.status === 206;
    if (range !== null) {
      acceptsRanges = true;
      const m = /bytes\s+\d+-\d+\/(\d+|\*)/i.exec(range);
      if (m !== null && m[1] !== '*') contentLength = Number(m[1]);
    } else {
      // No Content-Range on a 0-0 probe: upstream ignored Range. The
      // body we just asked to be 1 byte may be the whole file — the
      // plain length header (if sane) is our best total estimate.
      const len = res.headers.get('content-length');
      if (len !== null && /^\d+$/.test(len)) contentLength = Number(len);
    }
    try {
      await res.body?.cancel();
    } catch {
      /* probe body is expendable */
    }
    return {
      contentLength,
      acceptsRanges,
      resolvedUrl: this.resolvedUrl ?? url,
    };
  }

  /** One bounded retry wrapper (network errors + 5xx/429 + URL rotation). */
  private async fetchWithRetries(
    url: string,
    headers: Record<string, string>,
  ): Promise<Response> {
    let lastError: unknown = null;
    let rotations = 0;
    for (let attempt = 0; attempt <= this.opts.retries; attempt++) {
      if (this.aborted()) throw new DOMException('aborted', 'AbortError');
      try {
        const res = await this.fetchWithHops(
          this.resolvedUrl ?? url,
          headers,
        );
        if (res.status >= 500 || res.status === 429) {
          await res.body?.cancel().catch(() => undefined);
          throw new Error(`upstream ${res.status}`);
        }
        if (
          (res.status === 403 || res.status === 410) &&
          rotations < 2 &&
          this.opts.onStaleUrl !== undefined
        ) {
          // Stale/limited URL: rotate via a fresh resolve and retry
          // with a clean budget (does not consume a retry attempt).
          await res.body?.cancel().catch(() => undefined);
          rotations++;
          this.resetResolution();
          const fresh = await this.opts.onStaleUrl();
          this.resolvedUrl = fresh;
          attempt--; // rotation is not a retry
          continue;
        }
        return res;
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') throw err;
        lastError = err;
        if (attempt < this.opts.retries) {
          await new Promise((r) => setTimeout(r, backoffMs(attempt)));
        }
      }
    }
    throw lastError instanceof Error ? lastError : new Error(String(lastError));
  }

  /**
   * Fetch `length` bytes at absolute `offset`. Returns fewer bytes only
   * at end-of-file. Throws on hard failure after retries.
   */
  async fetchChunk(offset: number, length = this.opts.chunkSize): Promise<Chunk> {
    if (this.aborted()) throw new DOMException('aborted', 'AbortError');
    const end = offset + length - 1;
    const headers = this.baseHeaders(`bytes=${offset}-${end}`);
    const res = await this.fetchWithRetries(this.resolvedUrl ?? '', headers);
    if (!res.ok && res.status !== 206) {
      await res.body?.cancel().catch(() => undefined);
      throw new Error(`chunk at ${offset} failed: ${res.status}`);
    }
    const buf = new Uint8Array(await res.arrayBuffer());
    if (res.status === 206) {
      // Verify the range math — a corrupted 206 must fail loud, not
      // shift the demuxer's cursor (silent garbage in, garbage out).
      const range = res.headers.get('content-range');
      if (range !== null) {
        const m = /bytes\s+(\d+)-(\d+)\/(\d+|\*)/i.exec(range);
        if (m !== null && Number(m[1]) !== offset) {
          throw new Error(
            `range mismatch: asked ${offset}-${end}, got ${m[1]}-${m[2]}`,
          );
        }
      }
      return { data: buf, offset };
    }
    // 200 on a ranged ask: upstream ignored Range — body is byte 0…
    if (offset === 0) {
      return { data: buf.subarray(0, length), offset: 0 };
    }
    const skip = offset;
    if (buf.length <= skip) {
      return { data: new Uint8Array(0), offset };
    }
    return { data: buf.subarray(skip, skip + length), offset };
  }

  /** Forget the hop-resolved URL (called when a stream goes stale). */
  resetResolution(): void {
    this.resolvedUrl = null;
  }
}
