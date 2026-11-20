const USER_AGENT = 'Streamify/2.6.0 (https://github.com/maxrave-dev/SimpMusic)';

export class LrclibClient {
  constructor({ timeoutMs = 6000 } = {}) {
    this.timeoutMs = timeoutMs;
  }

  async fetchLyrics({ track, artist, duration }) {
    const cleanTrack = (track || '').replace(/\s*\(.*?\)\s*/g, ' ').replace(/\s*\[.*?\]\s*/g, ' ').trim();
    const cleanArtist = (artist || '').split(/[,&]/)[0]?.trim() || (artist || '').trim();
    const durSec = parseInt(duration, 10) || 0;

    let getUrl = `https://lrclib.net/api/get?track_name=${encodeURIComponent(cleanTrack || track)}&artist_name=${encodeURIComponent(cleanArtist || artist)}`;
    if (durSec > 0) {
      getUrl += `&duration=${durSec}`;
    }

    try {
      const resp = await fetch(getUrl, {
        headers: { 'User-Agent': USER_AGENT },
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      if (resp.ok) {
        const data = await resp.json();
        if (data && (data.syncedLyrics || data.plainLyrics)) {
          return data;
        }
      }
    } catch (_) {}

    // Fallback: search query
    try {
      const searchUrl = `https://lrclib.net/api/search?q=${encodeURIComponent((cleanTrack || track) + ' ' + (cleanArtist || artist))}`;
      const searchResp = await fetch(searchUrl, {
        headers: { 'User-Agent': USER_AGENT },
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      if (searchResp.ok) {
        const searchData = await searchResp.json();
        if (Array.isArray(searchData) && searchData.length > 0) {
          return searchData.find((item) => item.syncedLyrics) || searchData[0];
        }
      }
    } catch (_) {}

    return null;
  }
}
