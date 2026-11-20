const INNERTUBE_CLIENT_VERSION = '1.20240401.01.00';
const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36';

export class YtmClient {
  constructor({ timeoutMs = 8000 } = {}) {
    this.timeoutMs = timeoutMs;
    this.latestSearchTelemetry = null;
  }

  async #post(endpoint, payload, externalSignal = null) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

    if (externalSignal) {
      externalSignal.addEventListener('abort', () => controller.abort(externalSignal.reason), {
        once: true,
      });
    }

    const t0 = performance.now();
    let ttfbMs = 0;

    try {
      // P2: Pass ?prettyPrint=false to eliminate ~30% indentation bloat
      const res = await fetch(`https://music.youtube.com/youtubei/v1/${endpoint}?prettyPrint=false`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': USER_AGENT,
          'Accept-Encoding': 'gzip, deflate, br',
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });

      ttfbMs = Math.round(performance.now() - t0);

      if (!res.ok) {
        throw new Error(`YTM upstream ${endpoint} responded with status ${res.status}`);
      }

      const text = await res.text();
      const t1 = performance.now();
      const parseStart = performance.now();
      const data = JSON.parse(text);
      const parseEnd = performance.now();

      // P2 Telemetry instrumentation
      if (endpoint === 'search') {
        this.latestSearchTelemetry = {
          ttfbMs,
          downloadMs: Math.round(t1 - t0 - ttfbMs),
          wireBytes: text.length,
          contentEncoding: res.headers.get('content-encoding') || 'identity',
          jsonParseMs: +(parseEnd - parseStart).toFixed(2),
          timestamp: Date.now(),
        };
      }

      return data;
    } finally {
      clearTimeout(timeout);
    }
  }

  async getHome({ gl = 'IN', signal = null } = {}) {
    return this.#post(
      'browse',
      {
        context: {
          client: {
            clientName: 'WEB_REMIX',
            clientVersion: INNERTUBE_CLIENT_VERSION,
            hl: 'en',
            gl,
          },
        },
        browseId: 'FEmusic_home',
      },
      signal
    );
  }

  async searchSongs({ query, gl = 'IN', signal = null }) {
    return this.#post(
      'search',
      {
        context: {
          client: {
            clientName: 'WEB_REMIX',
            clientVersion: INNERTUBE_CLIENT_VERSION,
            hl: 'en',
            gl,
          },
        },
        query,
        params: 'EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D',
      },
      signal
    );
  }

  async getNext({ videoId, playlistId, continuation, gl = 'IN', signal = null }) {
    const payload = {
      context: {
        client: {
          clientName: 'WEB_REMIX',
          clientVersion: INNERTUBE_CLIENT_VERSION,
          hl: 'en',
          gl,
        },
      },
      isAudioOnly: true,
    };
    if (videoId) payload.videoId = videoId;
    if (playlistId) payload.playlistId = playlistId;
    if (continuation) payload.continuation = continuation;

    return this.#post('next', payload, signal);
  }

  async browse({ browseId, gl = 'IN', signal = null }) {
    return this.#post(
      'browse',
      {
        context: {
          client: {
            clientName: 'WEB_REMIX',
            clientVersion: INNERTUBE_CLIENT_VERSION,
            hl: 'en',
            gl,
          },
        },
        browseId,
      },
      signal
    );
  }

  async getSuggestions({ query, signal = null }) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

    if (signal) {
      signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true });
    }

    try {
      const res = await fetch(
        `https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&hl=en&gl=US&q=${encodeURIComponent(query)}&ds=yt`,
        {
          headers: {
            'User-Agent': USER_AGENT,
            'Accept-Encoding': 'gzip, deflate, br',
          },
          signal: controller.signal,
        }
      );
      if (!res.ok) return [];
      const text = await res.text();
      const match = text.match(/^[^(]*\((.*)\);?$/);
      if (!match) return [];
      const parsed = JSON.parse(match[1]);
      return (parsed[1] || []).map((item) => item[0]);
    } catch {
      return [];
    } finally {
      clearTimeout(timeout);
    }
  }
}
