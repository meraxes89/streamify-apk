/**
 * AmazonScraper (Fallback Tiers 2 & 3)
 * 
 * Extracts metadata directly from public Amazon Music web URLs when RPC endpoints
 * face bot-checks, WAF challenges, or schema rotation.
 * 
 * Supports:
 *  - Schema.org JSON-LD (MusicPlaylist, MusicAlbum, MusicRecording)
 *  - OpenGraph & Twitter meta tags
 *  - Hydration state extraction (__NEXT_DATA__, window.__INITIAL_STATE__)
 */

import { mapAmazonTrack, mapAmazonAlbum, mapAmazonPlaylist } from './mappers.mjs';

const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

export class AmazonScraper {
  constructor({ userAgent = DESKTOP_UA } = {}) {
    this.userAgent = userAgent;
  }

  async scrapeUrl(url) {
    if (!url || typeof url !== 'string') {
      throw new Error('Valid Amazon Music URL is required');
    }

    const res = await fetch(url, {
      headers: {
        'User-Agent': this.userAgent,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9'
      }
    });

    if (!res.ok) {
      throw new Error(`Scraper fetch failed with HTTP ${res.status} on ${url}`);
    }

    const html = await res.text();
    return this.parseHtml(html, url);
  }

  parseHtml(html, sourceUrl = '') {
    // 1. Try Schema.org JSON-LD
    const jsonLdData = this._extractJsonLd(html);
    if (jsonLdData) {
      return this._mapJsonLd(jsonLdData, sourceUrl);
    }

    // 2. Try Hydration state
    const hydrationData = this._extractHydrationState(html);
    if (hydrationData) {
      return hydrationData;
    }

    // 3. Fallback: OpenGraph Meta Tags
    return this._extractMetaTags(html, sourceUrl);
  }

  _extractJsonLd(html) {
    const regex = /<script\s+type=["']application\/ld\+json["']>([\s\S]*?)<\/script>/gi;
    let match;
    while ((match = regex.exec(html)) !== null) {
      try {
        const parsed = JSON.parse(match[1]);
        if (parsed['@type'] || parsed.itemListElement) {
          return parsed;
        }
      } catch {}
    }
    return null;
  }

  _mapJsonLd(data, sourceUrl) {
    const type = data['@type'];
    if (type === 'MusicRecording') {
      return mapAmazonTrack({
        id: this._extractAsin(sourceUrl),
        title: data.name,
        artistName: data.byArtist?.name || data.byArtist,
        albumName: data.inAlbum?.name,
        duration: data.duration,
        artOriginal: data.image
      });
    }

    if (type === 'MusicAlbum') {
      const tracks = (data.track?.itemListElement || data.tracks || []).map(t => {
        const item = t.item || t;
        return mapAmazonTrack({
          title: item.name,
          artistName: item.byArtist?.name || data.byArtist?.name,
          duration: item.duration,
          artOriginal: data.image
        });
      });

      return mapAmazonAlbum({
        asin: this._extractAsin(sourceUrl),
        title: data.name,
        artistName: data.byArtist?.name,
        artOriginal: data.image,
        releaseDate: data.datePublished,
        tracks
      });
    }

    if (type === 'MusicPlaylist') {
      const tracks = (data.track?.itemListElement || []).map(t => {
        const item = t.item || t;
        return mapAmazonTrack({
          title: item.name,
          artistName: item.byArtist?.name,
          duration: item.duration
        });
      });

      return mapAmazonPlaylist({
        asin: this._extractAsin(sourceUrl),
        title: data.name,
        description: data.description,
        artOriginal: data.image,
        tracks
      });
    }

    return null;
  }

  _extractHydrationState(html) {
    const match = html.match(/<script\s+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i)
      || html.match(/window\.__INITIAL_STATE__\s*=\s*(\{[\s\S]*?\});/);

    if (match) {
      try {
        const parsed = JSON.parse(match[1]);
        return parsed;
      } catch {}
    }
    return null;
  }

  _extractMetaTags(html, sourceUrl) {
    const getMeta = (prop) => {
      const m = html.match(new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]+content=["']([^"']+)["']`, 'i'));
      return m ? m[1] : null;
    };

    const title = getMeta('og:title') || getMeta('twitter:title') || '';
    const description = getMeta('og:description') || getMeta('twitter:description') || '';
    const image = getMeta('og:image') || getMeta('twitter:image') || '';
    const type = getMeta('og:type') || 'music.song';
    const asin = this._extractAsin(sourceUrl);

    if (sourceUrl.includes('/playlist') || type === 'music.playlist') {
      return mapAmazonPlaylist({
        asin,
        title,
        description,
        artOriginal: image,
        tracks: []
      });
    }

    if (sourceUrl.includes('/album') || type === 'music.album') {
      return mapAmazonAlbum({
        asin,
        title,
        artistName: getMeta('music:musician') || '',
        artOriginal: image,
        tracks: []
      });
    }

    return mapAmazonTrack({
      asin,
      title,
      artistName: getMeta('music:musician') || '',
      albumName: getMeta('music:album') || '',
      artOriginal: image
    });
  }

  _extractAsin(url) {
    const match = url.match(/\/(?:albums|playlists|tracks|artists)\/([A-Z0-9]{8,12})/i);
    return match ? match[1] : '';
  }
}
