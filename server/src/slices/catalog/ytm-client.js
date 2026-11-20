const INNERTUBE_CLIENT_VERSION = '1.20240401.01.00';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36';

export class YtmClient {
  constructor({ timeoutMs = 8000 } = {}) {
    this.timeoutMs = timeoutMs;
  }

  async #post(endpoint, payload) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const res = await fetch(`https://music.youtube.com/youtubei/v1/${endpoint}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'User-Agent': USER_AGENT,
        },
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
      if (!res.ok) {
        throw new Error(`YTM upstream ${endpoint} responded with status ${res.status}`);
      }
      return await res.json();
    } finally {
      clearTimeout(timeout);
    }
  }

  async getHome({ gl = 'IN' } = {}) {
    return this.#post('browse', {
      context: {
        client: {
          clientName: 'WEB_REMIX',
          clientVersion: INNERTUBE_CLIENT_VERSION,
          hl: 'en',
          gl,
        },
      },
      browseId: 'FEmusic_home',
    });
  }

  async searchSongs({ query, gl = 'IN' }) {
    return this.#post('search', {
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
    });
  }

  async getNext({ videoId, playlistId, continuation, gl = 'IN' }) {
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

    return this.#post('next', payload);
  }

  async browse({ browseId, gl = 'IN' }) {
    return this.#post('browse', {
      context: {
        client: {
          clientName: 'WEB_REMIX',
          clientVersion: INNERTUBE_CLIENT_VERSION,
          hl: 'en',
          gl,
        },
      },
      browseId,
    });
  }

  async getSuggestions({ query }) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      const res = await fetch(
        `https://suggestqueries-clients6.youtube.com/complete/search?client=youtube&hl=en&gl=US&q=${encodeURIComponent(query)}&ds=yt`,
        { signal: controller.signal }
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
