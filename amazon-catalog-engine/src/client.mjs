/**
 * AmazonCatalogClient
 * 
 * High-level autonomous client for querying Amazon Music catalog.
 * Coordinates:
 *  - AmazonSessionManager (guest session handshakes)
 *  - CircuitBreaker & RatePacer (governance & anti-ban protection)
 *  - RPC Transport (AWS JSON-1.1 action calls)
 *  - AmazonScraper (fail-safe HTML / Microdata fallback)
 *  - Canonical Mappers (normalizing data for Streamify)
 */

import { AmazonSessionManager } from './session.mjs';
import { CircuitBreaker, RatePacer } from './governance.mjs';
import { AmazonScraper } from './scraper.mjs';
import {
  mapAmazonTrack,
  mapAmazonAlbum,
  mapAmazonPlaylist,
  mapAmazonArtist
} from './mappers.mjs';

export class AmazonCatalogClient {
  constructor({
    sessionManager = new AmazonSessionManager(),
    circuitBreaker = new CircuitBreaker(),
    ratePacer = new RatePacer(),
    scraper = new AmazonScraper()
  } = {}) {
    this.session = sessionManager;
    this.breaker = circuitBreaker;
    this.pacer = ratePacer;
    this.scraper = scraper;
  }

  /**
   * Search Amazon Music catalog for tracks, albums, artists, and playlists.
   */
  async search(query, { territory = 'US', limit = 10 } = {}) {
    if (!query || typeof query !== 'string') {
      throw new Error('Search query is required');
    }

    const terr = territory.toUpperCase();

    return this.breaker.execute(terr, async () => {
      await this.pacer.pace(terr);
      const session = await this.session.getSession(terr);

      const endpoint = `${session.host}/${session.region}/api/textsearch/search/v1_1/`;
      const target = this.session.protocol.targets.search;

      const payload = {
        customerIdentity: {
          deviceId: session.deviceId,
          deviceType: session.deviceType
        },
        features: {
          spellCorrection: { allowCorrection: true },
          upsell: { allowUpsellForCatalogContent: true }
        },
        locale: 'en_US',
        musicTerritory: session.musicTerritory,
        query,
        resultSpecs: [
          {
            documentSpecs: [
              {
                type: 'catalog_track',
                fields: ['asin', 'title', 'artistAsin', 'artistName', 'artOriginal', 'originalReleaseDate']
              }
            ],
            label: 'Tracks',
            maxResults: limit
          },
          {
            documentSpecs: [
              {
                type: 'catalog_artist',
                fields: ['asin', 'name', 'artOriginal']
              }
            ],
            label: 'Artists',
            maxResults: Math.min(5, limit)
          },
          {
            documentSpecs: [
              {
                type: 'catalog_album',
                fields: ['asin', 'artOriginal', 'title', 'artistAsin', 'artistName', 'originalReleaseDate']
              }
            ],
            label: 'Albums',
            maxResults: Math.min(5, limit)
          }
        ]
      };

      const headers = this.session.buildHeaders(session, target);

      try {
        const res = await fetch(endpoint, {
          method: 'POST',
          headers,
          body: JSON.stringify(payload)
        });

        if (res.status === 401 || res.status === 403) {
          this.session.invalidateSession(terr);
        }

        if (res.status === 429) {
          throw new Error('HTTP 429 Too Many Requests from Amazon');
        }

        const text = await res.text();
        let data;
        try {
          data = JSON.parse(text);
        } catch {
          data = {};
        }

        return this._formatSearchResults(data, query);
      } catch (err) {
        // Fallback: If query looks like an Amazon URL, scrape directly
        if (query.startsWith('http://') || query.startsWith('https://')) {
          return this.scraper.scrapeUrl(query);
        }
        throw err;
      }
    });
  }

  /**
   * Search suggestions / auto-complete.
   */
  async instantSearch(query, { territory = 'US', limit = 5 } = {}) {
    const terr = territory.toUpperCase();

    return this.breaker.execute(terr, async () => {
      await this.pacer.pace(terr);
      const session = await this.session.getSession(terr);

      const endpoint = `${session.host}/${session.region}/api/textsearch/instantsearch/v1_1/`;
      const target = this.session.protocol.targets.instantSearch;

      const payload = {
        customerIdentity: {
          deviceId: session.deviceId,
          deviceType: session.deviceType
        },
        locale: 'en_US',
        musicTerritory: session.musicTerritory,
        query,
        maxResults: limit
      };

      const headers = this.session.buildHeaders(session, target);

      const res = await fetch(endpoint, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload)
      });

      if (!res.ok) {
        throw new Error(`Instant search failed with HTTP ${res.status}`);
      }

      return res.json();
    });
  }

  /**
   * Scrape or fetch playlist details.
   */
  async getPlaylist(urlOrAsin, { territory = 'US' } = {}) {
    const url = urlOrAsin.startsWith('http')
      ? urlOrAsin
      : `${this.session.protocol.hosts[territory] || this.session.protocol.hosts.US}/playlists/${urlOrAsin}`;

    return this.scraper.scrapeUrl(url);
  }

  /**
   * Scrape or fetch album details.
   */
  async getAlbum(urlOrAsin, { territory = 'US' } = {}) {
    const url = urlOrAsin.startsWith('http')
      ? urlOrAsin
      : `${this.session.protocol.hosts[territory] || this.session.protocol.hosts.US}/albums/${urlOrAsin}`;

    return this.scraper.scrapeUrl(url);
  }

  /**
   * Formats raw search results into canonical grouped categories.
   */
  _formatSearchResults(data, query) {
    const results = {
      query,
      tracks: [],
      albums: [],
      artists: [],
      playlists: [],
      raw: data
    };

    if (Array.isArray(data.results)) {
      for (const section of data.results) {
        const label = section.label || '';
        const items = section.hits || section.items || [];

        if (label === 'Tracks') {
          results.tracks = items.map(mapAmazonTrack).filter(Boolean);
        } else if (label === 'Albums') {
          results.albums = items.map(mapAmazonAlbum).filter(Boolean);
        } else if (label === 'Artists') {
          results.artists = items.map(mapAmazonArtist).filter(Boolean);
        } else if (label === 'Playlists') {
          results.playlists = items.map(mapAmazonPlaylist).filter(Boolean);
        }
      }
    }

    return results;
  }

  /**
   * Diagnostic probe method.
   */
  async probe(territory = 'US') {
    const session = await this.session.getSession(territory);
    const state = this.breaker.getState(territory);

    return {
      territory,
      status: 'ONLINE',
      host: session.host,
      region: session.region,
      sessionId: session.sessionId,
      deviceId: session.deviceId,
      deviceType: session.deviceType,
      hasCsrf: !!session.csrf?.token,
      circuitState: state.circuit,
      cooldownMs: state.cooldownMs
    };
  }
}
