/**
 * AmazonCatalogClient (The Complete Expanded Suite)
 * 
 * High-level autonomous client for querying Amazon Music catalog and
 * delivering complete parity with Streamify's multiplatform APIs,
 * including Podcasts, Radio Stations, Audiophile Badges, Liner Notes, and Music Videos.
 */

import { AmazonSessionManager } from './session.mjs';
import { CircuitBreaker, RatePacer } from './governance.mjs';
import { AmazonScraper } from './scraper.mjs';
import { AmazonLyricsService } from './lyrics.mjs';
import { AmazonStreamResolver } from './resolver.mjs';
import { AmazonPodcastService } from './podcasts.mjs';
import { AmazonStationService } from './stations.mjs';
import { AmazonVideoService } from './videos.mjs';
import { detectAudioBadges } from './badges.mjs';
import { extractTrackCredits } from './credits.mjs';
import { AmazonNativeDeliveryService } from './native-delivery.mjs';
import { AmazonDmlsSolver, DmlsSolveStatus } from './dmls-solver.mjs';
import { resolveAmazonMusicCredential } from './credentials.mjs';
import { LosslessFullSongService } from './lossless-fullsong.mjs';
import { HttpAudioSource } from './audio-source.mjs';
import {
  mapAmazonTrack,
  mapAmazonAlbum,
  mapAmazonPlaylist,
  mapAmazonArtist,
  formatAmazonArtwork
} from './mappers.mjs';

export class AmazonCatalogClient {
  constructor({
    sessionManager = new AmazonSessionManager(),
    circuitBreaker = new CircuitBreaker(),
    ratePacer = new RatePacer(),
    scraper = new AmazonScraper(),
    lyricsService = new AmazonLyricsService(),
    streamResolver = new AmazonStreamResolver(),
    podcastService = new AmazonPodcastService(),
    stationService = new AmazonStationService(),
    videoService = null,
    nativeDelivery = null,
    losslessFullSongService = null,
    relayBaseUrl = null,
    credential = null,
    dmlsSolver = null
  } = {}) {
    /**
     * Amazon Music credential (cookies/bearer). Defaults to env/file
     * resolution (AMAZON_MUSIC_COOKIES / ~/.streamify/amazon-music.cookies).
     * When authenticated, the DMLS solver returns REAL FLAC Ultra HD links.
     */
    this.credential = credential;
    this.session = sessionManager;
    this.breaker = circuitBreaker;
    this.pacer = ratePacer;
    this.scraper = scraper;
    this.lyrics = lyricsService;
    this.resolver = streamResolver;
    this.podcasts = podcastService;
    this.stations = stationService;
    this.stations.setClient(this);
    this.videos = videoService || new AmazonVideoService({ resolver: this.resolver });

    // Native DMLS delivery: capability probe + the REAL stream-link solver
    this.dmlsSolver = dmlsSolver || new AmazonDmlsSolver({ sessionManager: this.session });
    this.nativeDelivery = nativeDelivery ||
      new AmazonNativeDeliveryService({ sessionManager: this.session, solver: this.dmlsSolver });
    if (!this.resolver.nativeDelivery) {
      // Wire the native surface into the resolver so the Amazon-first
      // ladder (full-song links / guest sample URL) is always armed.
      this.resolver.nativeDelivery = this.nativeDelivery;
    }
    this.audioSource = new HttpAudioSource({ baseUrl: relayBaseUrl });
    this.losslessFullSong = losslessFullSongService ||
      new LosslessFullSongService({ audioSource: this.audioSource });
  }

  // -------------------------------------------------------------
  // API 1: Home Showcase & Editorial Feeds (/api/yt/home parity)
  // -------------------------------------------------------------
  async getHome({ territory = 'US' } = {}) {
    const terr = territory.toUpperCase();
    await this.session.getSession(terr);

    const sections = [
      {
        title: 'Trending Now & Global Hits',
        sectionId: 'amzn:home:trending',
        contents: [
          mapAmazonPlaylist({
            asin: 'B01M11SBC8',
            title: "Today's Hits",
            description: "The hottest tracks right now on Amazon Music.",
            author: 'Amazon Music',
            artOriginal: 'https://m.media-amazon.com/images/I/71G1Pj3P5kL._SX500_.jpg'
          }),
          mapAmazonPlaylist({
            asin: 'B01LTHMQB6',
            title: 'All Hits',
            description: 'Biggest pop, rock and urban anthems.',
            author: 'Amazon Music',
            artOriginal: 'https://m.media-amazon.com/images/I/71wLpWdG1uL._SX500_.jpg'
          })
        ]
      },
      {
        title: 'Top Charts & Viral',
        sectionId: 'amzn:home:charts',
        contents: [
          mapAmazonPlaylist({
            asin: 'B07D9G9S41',
            title: 'Top 50 Most Played',
            description: 'The definitive top songs played this week.',
            author: 'Amazon Music',
            artOriginal: 'https://m.media-amazon.com/images/I/81xGg6zB4DL._SX500_.jpg'
          })
        ]
      },
      {
        title: 'Curated Stations & Moods',
        sectionId: 'amzn:home:stations',
        contents: this.stations.getGenreStations().slice(0, 3)
      }
    ];

    return {
      territory: terr,
      sections,
      source: 'amazon'
    };
  }

  // -------------------------------------------------------------
  // API 2: Search Catalog (/api/yt/search parity)
  // -------------------------------------------------------------
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
        if (query.startsWith('http://') || query.startsWith('https://')) {
          return this.scraper.scrapeUrl(query);
        }
        throw err;
      }
    });
  }

  // -------------------------------------------------------------
  // API 3: Instant Suggest / Auto-Complete (/api/yt/suggest parity)
  // -------------------------------------------------------------
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

  // -------------------------------------------------------------
  // API 4: Universal Browse (/api/yt/browse parity)
  // -------------------------------------------------------------
  async getBrowse(browseId, { territory = 'US' } = {}) {
    if (!browseId) throw new Error('browseId is required');

    if (browseId.startsWith('amzn:album:') || browseId.includes('/album/')) {
      const asin = browseId.replace('amzn:album:', '');
      return this.getAlbum(asin, { territory });
    }

    if (browseId.startsWith('amzn:playlist:') || browseId.includes('/playlist/')) {
      const asin = browseId.replace('amzn:playlist:', '');
      return this.getPlaylist(asin, { territory });
    }

    if (browseId.startsWith('amzn:artist:') || browseId.includes('/artist/')) {
      const asin = browseId.replace('amzn:artist:', '');
      return this.getArtist(asin, { territory });
    }

    if (browseId.startsWith('amzn:podcast:') || browseId.includes('/podcasts/')) {
      return this.getPodcastShow(browseId);
    }

    return this.getAlbum(browseId, { territory });
  }

  // -------------------------------------------------------------
  // API 5: Next / Autoplay Queue (/api/yt/next parity)
  // -------------------------------------------------------------
  async getNext(trackOrAsin, { territory = 'US' } = {}) {
    const terr = territory.toUpperCase();

    let track = typeof trackOrAsin === 'string'
      ? { asin: trackOrAsin.replace('amzn:track:', ''), title: '', artist: '' }
      : trackOrAsin;

    const artist = Array.isArray(track.artists) ? track.artists[0]?.name : (track.artist || '');
    const query = artist ? `${artist} Top Songs` : track.title;

    const relatedSearch = await this.search(query || 'Top Hits', { territory: terr, limit: 15 });

    return {
      currentTrackId: track.id || `amzn:track:${track.asin}`,
      queue: relatedSearch.tracks.filter(t => t.id !== track.id),
      continuationToken: null,
      source: 'amazon'
    };
  }

  /**
   * Queue alias used by AmazonIntegratedEngine.getQueue (cached + single-flight
   * wrapped there). Delegates to the autoplay queue generator.
   */
  async getQueue(trackOrAsin, { territory = 'US' } = {}) {
    return this.getNext(trackOrAsin, { territory });
  }

  // -------------------------------------------------------------
  // API 6: Thumbnail Proxy & URL Resizer (/api/yt/thumb parity)
  // -------------------------------------------------------------
  getThumb(url, size = 500) {
    if (!url || typeof url !== 'string') return '';
    if (url.includes('._S')) {
      return url.replace(/\._S[XY]\d+_\./, `._SX${size}_.`);
    }
    return url;
  }

  // -------------------------------------------------------------
  // API 7: External Playlist Scrape (/api/playlist/scrape parity)
  // -------------------------------------------------------------
  async scrapePlaylist(url) {
    return this.scraper.scrapeUrl(url);
  }

  // -------------------------------------------------------------
  // API 8: Synchronized Lyrics (/api/yt/lyrics parity)
  // -------------------------------------------------------------
  async getLyrics(track, artist, durationSeconds = null) {
    return this.lyrics.getLyrics(track, artist, durationSeconds);
  }

  /**
   * Acquires the Amazon track ASIN for a track object via live Tenzing
   * catalog search when missing - the Amazon-first prerequisite. Returns
   * the track unchanged when it already carries an ASIN or search fails.
   */
  async _acquireAsin(track, { territory = 'US' } = {}) {
    if (track.asin) return track;
    const title = track.title || '';
    const artistName = Array.isArray(track.artists)
      ? track.artists.map(a => (typeof a === 'string' ? a : a.name)).join(' ')
      : (track.artist || track.artistName || '');
    const query = `${title} ${artistName}`.trim();
    if (!query) return track;

    try {
      const results = await this.search(query, { territory, limit: 10 });
      const tracks = results?.tracks || [];
      if (tracks.length) {
        const norm = (s) => String(s || '').toLowerCase().trim();
        const best = tracks.find(t => norm(t.title) === norm(title)) || tracks[0];
        return {
          ...track,
          asin: best.asin || best.id?.replace('amzn:track:', '') || null,
          title: track.title || best.title || null,
          artist: track.artist || track.artistName || (best.artists || []).map(a => a.name).join(', ') || null,
          durationSeconds: track.durationSeconds || best.durationSeconds || 0,
          artOriginal: best.artOriginal || track.artOriginal || null
        };
      }
    } catch {
      // Catalog search failure: the caller's track stays as-is; the resolver
      // will report the loud typed failure instead of bridging silently.
    }
    return track;
  }

  // -------------------------------------------------------------
  // API 9: Amazon-Native Audio Stream Resolver (/api/stream?id=... parity)
  // -------------------------------------------------------------
  // AMAZON-FIRST POLICY (2026-11): resolution returns Amazon-native
  // artifacts ONLY - authenticated DMLS full-song link graph, or the REAL
  // guest sample URL (signed CloudFront MP3) with the honest AUTH_REQUIRED
  // full-song verdict. The legacy YouTube bridge runs EXCLUSIVELY on the
  // explicit allowYoutubeBridge opt-in - never as a silent fallback.
  async resolveAudio(trackOrAsin, { allowYoutubeBridge = false, territory = 'US' } = {}) {
    let track = typeof trackOrAsin === 'string'
      ? {
          asin: trackOrAsin.replace('amzn:track:', ''),
          title: /^(amzn:|[A-Z0-9]{10})$/.test(trackOrAsin) ? '' : trackOrAsin,
          territory
        }
      : { ...trackOrAsin, territory: trackOrAsin.territory || territory };

    track = await this._acquireAsin(track, { territory });
    return this.resolver.resolve(track, { allowBridge: allowYoutubeBridge });
  }

  // -------------------------------------------------------------
  // API 9-legacy: EXPLICIT YouTube bridge (opt-in only)
  // -------------------------------------------------------------
  async resolveAudioViaYoutubeBridge(trackOrAsin, { territory = 'US' } = {}) {
    const track = typeof trackOrAsin === 'string'
      ? { asin: trackOrAsin.replace('amzn:track:', ''), title: trackOrAsin, territory }
      : { ...trackOrAsin, territory: trackOrAsin.territory || territory };
    return this.resolver.resolveViaBridge(track);
  }

  // -------------------------------------------------------------
  // API 9b: Lossless Full-Song Stream Resolver (top-tier FLAC)
  // -------------------------------------------------------------
  // Amazon-first: authenticated sessions solve the REAL link graph down the
  // tier ladder (Ultra HD FLAC -> HD FLAC -> V2 lossy manifest); guests get
  // the REAL sample URL + the honest AUTH_REQUIRED full-song verdict.
  // YouTube bridge only via allowYoutubeBridge.
  async resolveLosslessAudio(trackOrAsin, { territory = 'US', credential = null, tier = 'best', allowYoutubeBridge = false } = {}) {
    let track = typeof trackOrAsin === 'string'
      ? { asin: trackOrAsin.replace('amzn:track:', ''), territory }
      : { ...trackOrAsin, territory: trackOrAsin.territory || territory };

    track = await this._acquireAsin(track, { territory });
    const cred = credential || this.credential || null;

    const resolved = await this.resolver.resolveLossless(track, {
      allowBridge: allowYoutubeBridge,
      credential: cred,
      tier
    });

    return {
      ...resolved,
      requestedTier: tier,
      lossless: resolved.isLossless !== false,
      // Honest delivery card passthrough (actual delivered codec verdict lives here)
      delivery: resolved.delivery || null,
      native: resolved.native || null,
    };
  }

  // -------------------------------------------------------------
  // Native FLAC capability card (DMLS surface, typed honest verdict)
  // -------------------------------------------------------------
  async probeNativeFlacCapability(trackOrAsin, { territory = 'US', credential = null, forceWire = false } = {}) {
    const track = typeof trackOrAsin === 'string'
      ? { asin: trackOrAsin.replace('amzn:track:', '') }
      : trackOrAsin;
    return this.nativeDelivery.probeCapability(track, { territory, credential: credential || this.credential, forceWire });
  }

  // -------------------------------------------------------------
  // THE REAL Amazon stream-link solver (DMLS getDashManifestsV2)
  // -------------------------------------------------------------
  // Solves the actual Amazon Music stream links for a track: inline DASH MPD,
  // representation cards (FLAC UHD96/24-bit etc.), init + media segment CDN
  // URLs, optional first-chunk URL, DRM pssh + license operation.
  //
  // tier: 'ultra'|'hd'|'best' -> FLAC/Opus SIREN_KATANA manifest path;
  //       'lossy'|'v2'        -> V2 bitrateTypeList path (no content
  //                              protection requested - the account's floor).
  //
  // Guests get the live-verified AUTH_REQUIRED verdict with the exact
  // unlock recipe; authenticated credentials get real links.
  async solveStreamLinks(trackOrAsin, { territory = 'US', tier = 'ultra', credential = null, includeFirstChunk = false, forceWire = false, noClearLead = false } = {}) {
    const asin = typeof trackOrAsin === 'string'
      ? trackOrAsin.replace('amzn:track:', '')
      : (trackOrAsin.asin || trackOrAsin.id || '');
    const cred = credential || this.credential || resolveAmazonMusicCredential();
    return this.dmlsSolver.solveStreamLinks({
      asin, territory, tier,
      credential: cred,
      includeFirstChunk,
      noClearLead,
      forceWire
    });
  }

  // -------------------------------------------------------------
  // REAL guest sample URLs (getStreamingURLsForSamples vector)
  // -------------------------------------------------------------
  // The one DMLS surface that serves anonymous guests: registered client
  // 'WebCP' + real expiration -> signed CloudFront MP3 sample URLs
  // (live-verified: audio/mpeg bytes end-to-end).
  async getSampleStreamUrl(trackOrAsin, { territory = 'US', expirationSec = 3600 } = {}) {
    let track = typeof trackOrAsin === 'string'
      ? { asin: trackOrAsin.replace('amzn:track:', '') }
      : trackOrAsin;
    track = await this._acquireAsin(track, { territory });
    const asin = track.asin || (track.id ? String(track.id).replace('amzn:track:', '') : null);
    if (!asin) throw new Error('ASIN required for sample URL resolution (pass an ASIN or a searchable track)');
    return this.dmlsSolver.solveSampleUrl({ asin, territory, expirationSec });
  }

  // -------------------------------------------------------------
  // Whole raw lossless full-song acquisition (real bytes, sniffed codec)
  // -------------------------------------------------------------
  async acquireLosslessFullSong(trackOrAsin, { territory = 'US', relayBaseUrl = null } = {}) {
    const track = typeof trackOrAsin === 'string'
      ? { asin: trackOrAsin.replace('amzn:track:', ''), title: trackOrAsin }
      : trackOrAsin;

    const descriptor = await this.resolver.resolveLossless({ ...track, territory });
    const artifact = await this.losslessFullSong.acquireFullSong({
      streamUrl: descriptor.streamUrl,
      tier: descriptor.tier,
      codec: descriptor.codec,
      ...(relayBaseUrl ? { audioSource: async (o) => this.audioSource.load({ ...o, baseUrl: relayBaseUrl }) } : {}),
    });
    return { descriptor, artifact };
  }

  // -------------------------------------------------------------
  // API 10: Dynamic DASH MPD Manifest (/api/stream/:id/manifest.mpd)
  // -------------------------------------------------------------
  async getDashManifest(trackOrAsin) {
    const resolved = await this.resolveAudio(trackOrAsin);
    return {
      videoId: resolved.videoId,
      manifestUrl: resolved.manifestUrl,
      durationSeconds: resolved.durationSeconds,
      contentType: 'application/dash+xml'
    };
  }

  // -------------------------------------------------------------
  // API 11: Two-Stage Prefetch Descriptor (/api/stream/prefetch)
  // -------------------------------------------------------------
  async prefetchDescriptor(trackOrAsin) {
    const resolved = await this.resolveAudio(trackOrAsin);
    return {
      status: 'warmed',
      stage: 1,
      asin: resolved.asin,
      videoId: resolved.videoId,
      streamUrl: resolved.streamUrl
    };
  }

  // -------------------------------------------------------------
  // API 12: Diagnostic Probe & Health Telemetry (/api/health parity)
  // -------------------------------------------------------------
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
      cooldownMs: state.cooldownMs,
      timestamp: Date.now()
    };
  }

  // -------------------------------------------------------------
  // NEW FEATURE: Podcasts & Spoken Word (Unencrypted MP3 Stream)
  // -------------------------------------------------------------
  async searchPodcasts(query, options = {}) {
    return this.podcasts.searchPodcasts(query, options);
  }

  async getPodcastShow(showId) {
    return this.podcasts.getPodcastShow(showId);
  }

  async getPodcastEpisode(episodeId) {
    return this.podcasts.getPodcastEpisode(episodeId);
  }

  // -------------------------------------------------------------
  // NEW FEATURE: Algorithmic Stations & Endless Radio
  // -------------------------------------------------------------
  async createStation(seed, options = {}) {
    return this.stations.createStation(seed, options);
  }

  getGenreStations() {
    return this.stations.getGenreStations();
  }

  // -------------------------------------------------------------
  // NEW FEATURE: Audiophile Badges & Spatial Audio Detection
  // -------------------------------------------------------------
  getAudioBadges(trackOrMetadata) {
    return detectAudioBadges(trackOrMetadata);
  }

  // -------------------------------------------------------------
  // NEW FEATURE: Song Credits & Liner Notes
  // -------------------------------------------------------------
  getTrackCredits(trackOrMetadata) {
    return extractTrackCredits(trackOrMetadata);
  }

  // -------------------------------------------------------------
  // NEW FEATURE: Official Music Videos
  // -------------------------------------------------------------
  async getMusicVideos(artistOrTrack, options = {}) {
    return this.videos.getMusicVideos(artistOrTrack, options);
  }

  // -------------------------------------------------------------
  // Entity Helpers
  // -------------------------------------------------------------
  async getPlaylist(urlOrAsin, { territory = 'US' } = {}) {
    const url = urlOrAsin.startsWith('http')
      ? urlOrAsin
      : `${this.session.protocol.hosts[territory] || this.session.protocol.hosts.US}/playlists/${urlOrAsin}`;
    return this.scraper.scrapeUrl(url);
  }

  async getAlbum(urlOrAsin, { territory = 'US' } = {}) {
    const url = urlOrAsin.startsWith('http')
      ? urlOrAsin
      : `${this.session.protocol.hosts[territory] || this.session.protocol.hosts.US}/albums/${urlOrAsin}`;
    return this.scraper.scrapeUrl(url);
  }

  async getArtist(urlOrAsin, { territory = 'US' } = {}) {
    const url = urlOrAsin.startsWith('http')
      ? urlOrAsin
      : `${this.session.protocol.hosts[territory] || this.session.protocol.hosts.US}/artists/${urlOrAsin}`;
    return this.scraper.scrapeUrl(url);
  }

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

        // Live Tenzing hits wrap documents: { blockRef, document: {...} }.
        // Unwrap defensively for both live and fixture shapes.
        const docs = items.map(h => (h && h.document && typeof h.document === 'object') ? h.document : h);

        if (label === 'Tracks') {
          results.tracks = docs.map(mapAmazonTrack).filter(Boolean);
        } else if (label === 'Albums') {
          results.albums = docs.map(mapAmazonAlbum).filter(Boolean);
        } else if (label === 'Artists') {
          results.artists = docs.map(mapAmazonArtist).filter(Boolean);
        } else if (label === 'Playlists') {
          results.playlists = docs.map(mapAmazonPlaylist).filter(Boolean);
        }
      }
    }

    return results;
  }
}
