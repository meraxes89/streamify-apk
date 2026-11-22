/**
 * AmazonPodcastService
 * 
 * Manages Podcast Show and Episode discovery and direct audio streaming.
 * Podcasts on Amazon Music are delivered over open RSS audio enclosures (MP3/M4A),
 * allowing direct, unencrypted, bit-perfect playback without DRM restrictions.
 */

import { formatAmazonArtwork, parseDuration } from './mappers.mjs';

const DESKTOP_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

export class AmazonPodcastService {
  constructor({ userAgent = DESKTOP_UA } = {}) {
    this.userAgent = userAgent;
    this.cache = new Map();
  }

  /**
   * Search for podcasts on Amazon Music.
   */
  async searchPodcasts(query, { territory = 'US', limit = 10 } = {}) {
    if (!query) throw new Error('Podcast search query is required');

    // Scrape public search or search index
    const url = `https://music.amazon.com/search/${encodeURIComponent(query)}?filter=PODCASTS`;
    const res = await fetch(url, {
      headers: {
        'User-Agent': this.userAgent,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
      }
    });

    if (!res.ok) {
      throw new Error(`Podcast search failed with HTTP ${res.status}`);
    }

    const html = await res.text();
    return this._extractPodcastSearchResults(html, query, limit);
  }

  /**
   * Get Podcast Show details and episode list.
   */
  async getPodcastShow(showIdOrUrl) {
    const url = showIdOrUrl.startsWith('http')
      ? showIdOrUrl
      : `https://music.amazon.com/podcasts/${showIdOrUrl}`;

    if (this.cache.has(url)) {
      return this.cache.get(url);
    }

    const res = await fetch(url, {
      headers: {
        'User-Agent': this.userAgent,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
      }
    });

    if (!res.ok) {
      throw new Error(`Failed to fetch podcast show: HTTP ${res.status}`);
    }

    const html = await res.text();
    const show = this._parsePodcastShowHtml(html, url);
    this.cache.set(url, show);
    return show;
  }

  /**
   * Get Podcast Episode details including direct stream URL.
   */
  async getPodcastEpisode(episodeIdOrUrl) {
    const url = episodeIdOrUrl.startsWith('http')
      ? episodeIdOrUrl
      : `https://music.amazon.com/podcasts/episodes/${episodeIdOrUrl}`;

    const res = await fetch(url, {
      headers: {
        'User-Agent': this.userAgent,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
      }
    });

    if (!res.ok) {
      throw new Error(`Failed to fetch podcast episode: HTTP ${res.status}`);
    }

    const html = await res.text();
    return this._parsePodcastEpisodeHtml(html, url);
  }

  _parsePodcastShowHtml(html, sourceUrl) {
    const getMeta = (prop) => {
      const m = html.match(new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]+content=["']([^"']+)["']`, 'i'));
      return m ? m[1] : null;
    };

    const title = getMeta('og:title') || 'Podcast Show';
    const description = getMeta('og:description') || '';
    const image = getMeta('og:image') || '';

    // Extract JSON-LD if present
    const jsonLdMatch = html.match(/<script\s+type=["']application\/ld\+json["']>([\s\S]*?)<\/script>/i);
    let provider = 'Amazon Music';
    let episodes = [];

    if (jsonLdMatch) {
      try {
        const data = JSON.parse(jsonLdMatch[1]);
        if (data.provider || data.author) provider = data.provider?.name || data.author?.name || provider;
      } catch {}
    }

    return {
      id: `amzn:podcast:${this._extractId(sourceUrl)}`,
      title,
      description,
      provider,
      thumbnails: formatAmazonArtwork(image),
      episodes,
      source: 'amazon:podcast'
    };
  }

  _parsePodcastEpisodeHtml(html, sourceUrl) {
    const getMeta = (prop) => {
      const m = html.match(new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]+content=["']([^"']+)["']`, 'i'));
      return m ? m[1] : null;
    };

    const title = getMeta('og:title') || 'Podcast Episode';
    const description = getMeta('og:description') || '';
    const image = getMeta('og:image') || '';
    const audioUrl = getMeta('og:audio') || getMeta('twitter:player:stream') || null;

    return {
      id: `amzn:episode:${this._extractId(sourceUrl)}`,
      title,
      description,
      thumbnails: formatAmazonArtwork(image),
      streamUrl: audioUrl,
      isDirectAudioStream: !!audioUrl,
      source: 'amazon:podcast'
    };
  }

  _extractPodcastSearchResults(html, query, limit) {
    const shows = [];
    const showMatches = [...html.matchAll(/\/podcasts\/([a-zA-Z0-9]{8,12})\/([^"'>]+)/g)];

    const seen = new Set();
    for (const m of showMatches) {
      const asin = m[1];
      if (seen.has(asin)) continue;
      seen.add(asin);

      const rawSlug = m[2];
      const title = decodeURIComponent(rawSlug).replace(/-/g, ' ');

      shows.push({
        id: `amzn:podcast:${asin}`,
        asin,
        title,
        url: `https://music.amazon.com/podcasts/${asin}/${rawSlug}`,
        source: 'amazon:podcast'
      });

      if (shows.length >= limit) break;
    }

    return {
      query,
      shows,
      source: 'amazon:podcast'
    };
  }

  _extractId(url) {
    const match = url.match(/\/(?:podcasts|episodes)\/([A-Z0-9]{8,12})/i);
    return match ? match[1] : '';
  }
}
