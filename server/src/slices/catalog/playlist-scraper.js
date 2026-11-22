import { LruCache } from '../../lib/cache.js';
import { SingleFlight } from '../../lib/single-flight.js';

const USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

function parseDurationIso(d) {
  if (!d || typeof d !== 'string') return 0;
  const match = d.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
  if (!match) return 0;
  const hr = parseInt(match[1] || '0', 10);
  const min = parseInt(match[2] || '0', 10);
  const sec = parseInt(match[3] || '0', 10);
  return hr * 3600 + min * 60 + sec;
}

export class PlaylistScraperService {
  constructor({ cache = new LruCache({ maxItems: 200 }) } = {}) {
    this.cache = cache;
    this.singleFlight = new SingleFlight();
  }

  async scrape(url, { signal = null } = {}) {
    if (!url || typeof url !== 'string') {
      throw new Error('URL is required');
    }

    const trimmed = url.trim();
    const cacheKey = `playlist_scrape:${trimmed}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    return this.singleFlight.do(cacheKey, async () => {
      let result;
      if (trimmed.includes('spotify.com') || trimmed.startsWith('spotify:')) {
        result = await this.scrapeSpotify(trimmed, { signal });
      } else if (trimmed.includes('music.apple.com')) {
        result = await this.scrapeAppleMusic(trimmed, { signal });
      } else {
        throw new Error('Unsupported playlist URL. Please provide a Spotify or Apple Music link.');
      }

      this.cache.set(cacheKey, result, 1_800_000); // 30 min TTL
      return result;
    });
  }

  async scrapeSpotify(url, { signal = null } = {}) {
    let itemType = 'playlist';
    if (url.includes('album/') || url.startsWith('spotify:album:')) {
      itemType = 'album';
    } else if (url.includes('track/') || url.startsWith('spotify:track:')) {
      itemType = 'track';
    }

    let playlistId = '';
    if (url.includes('playlist/')) {
      playlistId = url.split('playlist/')[1].split('?')[0].split('/')[0];
    } else if (url.includes('album/')) {
      playlistId = url.split('album/')[1].split('?')[0].split('/')[0];
    } else if (url.includes('track/')) {
      playlistId = url.split('track/')[1].split('?')[0].split('/')[0];
    } else if (url.startsWith('spotify:playlist:')) {
      playlistId = url.split('spotify:playlist:')[1];
    } else if (url.startsWith('spotify:album:')) {
      playlistId = url.split('spotify:album:')[1];
    } else if (url.startsWith('spotify:track:')) {
      playlistId = url.split('spotify:track:')[1];
    }

    if (!playlistId) throw new Error('Invalid Spotify URL');

    const embedUrl = `https://open.spotify.com/embed/${itemType}/${playlistId}`;
    const res = await fetch(embedUrl, {
      headers: {
        'User-Agent': USER_AGENT,
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      },
      signal,
    });

    if (!res.ok) {
      throw new Error(`Spotify embed fetch failed: HTTP ${res.status}`);
    }

    const html = await res.text();
    const match = html.match(/<script id="__NEXT_DATA__"[^>]*>(.*?)<\/script>/s);
    if (!match) {
      throw new Error('Could not parse Spotify embed metadata');
    }

    const json = JSON.parse(match[1]);
    const entity = json?.props?.pageProps?.state?.data?.entity;
    if (!entity) {
      throw new Error('No entity found in Spotify metadata');
    }

    const playlistName = entity.name || entity.title || 'Imported Spotify Playlist';
    const rawTrackList = entity.trackList || [];
    const tracks = [];

    for (const item of rawTrackList) {
      const title = (item.title || item.name || '').trim();
      const subtitle = (item.subtitle || item.artist || '').replace(/\u00A0/g, ' ').trim();
      const artist = subtitle || 'Unknown Artist';
      const durationSec = Math.floor((item.duration || 0) / 1000);

      if (title) {
        tracks.push({
          title,
          artist,
          durationSec,
        });
      }
    }

    return {
      name: playlistName,
      source: 'SPOTIFY',
      trackCount: tracks.length,
      tracks,
    };
  }

  async scrapeAppleMusic(url, { signal = null } = {}) {
    const res = await fetch(url, {
      headers: {
        'User-Agent': USER_AGENT,
        Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      },
      signal,
    });

    if (!res.ok) {
      throw new Error(`Apple Music fetch failed: HTTP ${res.status}`);
    }

    const html = await res.text();
    let playlistName = 'Imported Apple Music Playlist';
    const tracks = [];

    // Method 1: Check application/json hydration data for complete titles and artist names
    for (const m of html.matchAll(/<script[^>]*type=["']application\/json["'][^>]*>(.*?)<\/script>/gs)) {
      try {
        const json = JSON.parse(m[1].trim());
        const sections = json?.data?.[0]?.data?.sections;
        if (Array.isArray(sections)) {
          for (const s of sections) {
            if (s.itemKind === 'containerDetailHeaderLockup' && s.items?.[0]?.title) {
              playlistName = s.items[0].title;
            }
            if (s.itemKind === 'trackLockup' && Array.isArray(s.items)) {
              for (const it of s.items) {
                const title = (it.title || '').trim();
                const artist = (it.artistName || 'Unknown Artist').trim();
                const durationSec = it.duration ? Math.floor(it.duration / 1000) : 0;
                if (title) {
                  tracks.push({
                    title,
                    artist,
                    durationSec,
                  });
                }
              }
            }
          }
        }
        if (tracks.length > 0) break;
      } catch {}
    }

    // Method 2: Schema.org MusicPlaylist / MusicAlbum fallback
    if (tracks.length === 0) {
      for (const m of html.matchAll(/<script[^>]*type=["']application\/ld\+json["'][^>]*>(.*?)<\/script>/gs)) {
        try {
          const json = JSON.parse(m[1].trim());
          const type = json['@type'] || '';
          if (type.includes('MusicPlaylist') || type.includes('MusicAlbum')) {
            if (json.name) playlistName = json.name;
            const trackArray = json.track;
            if (Array.isArray(trackArray)) {
              for (const t of trackArray) {
                const title = (t.name || '').trim();
                let artist = 'Unknown Artist';
                if (t.byArtist) {
                  artist = typeof t.byArtist === 'string' ? t.byArtist : (t.byArtist.name || 'Unknown Artist');
                }
                const durationSec = parseDurationIso(t.duration);
                if (title) {
                  tracks.push({
                    title,
                    artist: artist.trim(),
                    durationSec,
                  });
                }
              }
            }
            if (tracks.length > 0) break;
          }
        } catch {}
      }
    }

    if (tracks.length === 0) {
      throw new Error('Failed to extract tracks from Apple Music link. Ensure the playlist is public.');
    }

    return {
      name: playlistName,
      source: 'APPLE_MUSIC',
      trackCount: tracks.length,
      tracks,
    };
  }
}
