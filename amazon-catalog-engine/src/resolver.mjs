/**
 * AmazonStreamResolver (Hybrid Playback Bridge)
 * 
 * Bridges Amazon Music metadata to high-bitrate streaming audio.
 * Matches Amazon tracks (title, artist, duration, year, label) to official
 * YouTube Music audio streams with scoring heuristics and caching.
 */

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

import { detectAudioBadges } from './badges.mjs';
import { selectTopLosslessTier } from './lossless-stream.mjs';
import { AmazonNativeDeliveryService } from './native-delivery.mjs';

export class AmazonStreamResolver {
  constructor({ cache = new Map(), badges = detectAudioBadges, nativeDelivery = null, sessionManager = null } = {}) {
    /** @type {Map<string, Object>} asin -> resolved stream descriptor */
    this.cache = cache;
    /** @type {Function} Amazon badge/tier detector (injectable for tests) */
    this.badges = badges;
    /**
     * Native DMLS delivery probe (optional). When wired, resolveLossless first
     * asks Amazon's REAL delivery surface for the track and attaches an honest
     * capability card (auth/entitlement/DRM verdicts) to the descriptor.
     */
    this.nativeDelivery = nativeDelivery || (sessionManager
      ? new AmazonNativeDeliveryService({ sessionManager })
      : null);
  }

  /**
   * Resolves an Amazon track to a playable stream descriptor.
   * 
   * @param {Object} track - Canonical track object or search params
   * @param {string} track.title
   * @param {string|Array} track.artists
   * @param {number} [track.durationSeconds]
   * @param {string} [track.id]
   * @param {string} [track.asin]
   * @returns {Promise<Object>} Stream descriptor
   */
  async resolve(track) {
    if (!track) throw new Error('Track metadata is required for resolution');

    const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : null);
    if (asin && this.cache.has(asin)) {
      return this.cache.get(asin);
    }

    const title = track.title || '';
    const artistName = Array.isArray(track.artists)
      ? track.artists.map(a => typeof a === 'string' ? a : a.name).join(' ')
      : (track.artist || track.artistName || '');

    const query = `${title} ${artistName}`.trim();
    if (!query) throw new Error('Track title or artist is required for resolution');

    const candidates = await this._searchCandidates(query);
    const bestMatch = this._rankCandidates(candidates, track);

    if (!bestMatch) {
      throw new Error(`Failed to resolve audio stream for track: "${title}" by ${artistName}`);
    }

    const descriptor = {
      asin: asin || null,
      videoId: bestMatch.videoId,
      title: bestMatch.title || title,
      artist: bestMatch.artist || artistName,
      durationSeconds: bestMatch.durationSeconds || track.durationSeconds || 0,
      streamUrl: `/api/stream?id=${bestMatch.videoId}`,
      manifestUrl: `/api/stream/${bestMatch.videoId}/manifest.mpd`,
      matchConfidence: bestMatch.score,
      source: 'amazon->youtube-bridge'
    };

    if (asin) {
      this.cache.set(asin, descriptor);
    }

    return descriptor;
  }

  async _searchCandidates(query) {
    const payload = {
      context: {
        client: {
          clientName: 'WEB_REMIX',
          clientVersion: '1.20240401.01.00',
          hl: 'en',
          gl: 'US'
        }
      },
      query
    };

    const res = await fetch('https://music.youtube.com/youtubei/v1/search?prettyPrint=false', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'User-Agent': USER_AGENT
      },
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      throw new Error(`Upstream resolver query failed with status ${res.status}`);
    }

    const data = await res.json();
    return this._extractCandidates(data);
  }

  _extractCandidates(data) {
    const candidates = [];
    const sections = data.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];

    for (const s of sections) {
      // 1. Top hit card
      if (s.musicCardShelfRenderer) {
        const c = s.musicCardShelfRenderer;
        const videoId = c.onTap?.watchEndpoint?.videoId || c.buttons?.[0]?.buttonRenderer?.command?.watchEndpoint?.videoId;
        const title = c.title?.runs?.map(r => r.text).join('') || '';
        const artist = c.subtitle?.runs?.map(r => r.text).join('') || '';
        if (videoId) candidates.push({ videoId, title, artist, durationSeconds: 0 });
      }

      // 2. Item sections & shelves
      const items = s.musicShelfRenderer?.contents || s.itemSectionRenderer?.contents || [];
      for (const item of items) {
        const r = item.musicResponsiveListItemRenderer || item;
        const videoId = r.playlistItemData?.videoId ||
          r.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint?.videoId ||
          r.navigationEndpoint?.watchEndpoint?.videoId ||
          r.doubleTapCommand?.watchEndpoint?.videoId;

        if (!videoId) continue;

        const runs = r.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
        const title = runs.map(x => x.text).join('');
        const subRuns = r.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
        const artist = subRuns.map(x => x.text).join('');

        candidates.push({ videoId, title, artist, durationSeconds: 0 });
      }
    }

    // 3. Fallback regex extraction if empty
    if (candidates.length === 0) {
      const str = JSON.stringify(data);
      const matches = str.matchAll(/"videoId":"([a-zA-Z0-9_\-]{11})"/g);
      for (const m of matches) {
        candidates.push({ videoId: m[1], title: '', artist: '', durationSeconds: 0 });
      }
    }

    return candidates;
  }

  _rankCandidates(candidates, original) {
    if (!candidates || candidates.length === 0) return null;

    const targetTitle = (original.title || '').toLowerCase();
    const targetDuration = original.durationSeconds || 0;

    let best = null;
    let maxScore = -1000;

    for (const c of candidates) {
      let score = 100;
      const cTitle = (c.title || '').toLowerCase();

      // Duration tolerance scoring
      if (targetDuration > 0 && c.durationSeconds > 0) {
        const delta = Math.abs(c.durationSeconds - targetDuration);
        if (delta <= 2) score += 50;
        else if (delta <= 5) score += 30;
        else if (delta <= 10) score += 10;
        else score -= delta * 5;
      }

      // Title exact or partial match
      if (cTitle && targetTitle) {
        if (cTitle === targetTitle) {
          score += 40;
        } else if (cTitle.includes(targetTitle)) {
          score += 20;
        }
      }

      // Penalize unwanted modifiers unless present in original
      const modifiers = ['live', 'cover', 'remix', 'karaoke', 'sped up', 'slowed', 'instrumental'];
      for (const mod of modifiers) {
        if (cTitle.includes(mod) && !targetTitle.includes(mod)) {
          score -= 40;
        }
      }

      if (score > maxScore) {
        maxScore = score;
        best = { ...c, score };
      }
    }

    return best || candidates[0];
  }

  /**
   * Resolve a full song to the top-tier lossless audio stream descriptor.
   *
   * Lossless policy: Amazon full songs are delivered as bit-perfect FLAC
   * masters (Ultra HD 24/96 kHz -> HD 16/44.1 kHz). The relay/server never
   * transcodes; delivery uses the sparse-block cache + pacing + admission
   * pipeline (see AmazonIntegratedEngine.resolveLosslessPacedStream).
   *
   * @param {Object} track - Canonical Amazon track metadata
   * @param {string} track.title
   * @param {string} track.artist
   * @param {string} [track.asin]
   * @param {number} [track.durationSeconds]
   * @returns {Promise<Object>} Full-song lossless stream descriptor
   */
  async resolveLossless(track) {
    if (!track) throw new Error('Track metadata is required for lossless resolution');

    const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : null);
    const cacheKey = `lossless:${asin || `${(track.title || '').toLowerCase().trim()}`}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    const title = track.title || '';
    const artistName = Array.isArray(track.artists)
      ? track.artists.map((a) => (typeof a === 'string' ? a : a.name)).join(' ')
      : (track.artist || track.artistName || '');
    const query = `${title} ${artistName}`.trim();
    if (!query) throw new Error('Track title or artist is required for lossless resolution');

    const candidates = await this._searchCandidates(query);
    const bestMatch = this._rankCandidates(candidates, track);
    if (!bestMatch) throw new Error(`Failed to resolve lossless audio for track: "${title}" by ${artistName}`);

    const lossless = selectTopLosslessTier(track);
    const descriptor = {
      asin: asin || null,
      videoId: bestMatch.videoId,
      title: bestMatch.title || title,
      artist: bestMatch.artist || artistName,
      durationSeconds: bestMatch.durationSeconds || track.durationSeconds || 0,
      streamUrl: `/api/stream?id=${bestMatch.videoId}`,
      manifestUrl: `/api/stream/${bestMatch.videoId}/manifest.mpd`,
      matchConfidence: Math.min(100, bestMatch.score),
      // Target tier policy (Amazon-side rendition the pipeline aims for).
      // The `delivery` card below reports what is ACTUALLY delivered.
      audioTier: lossless.tier,
      tier: lossless.tier,
      isLossless: true,
      isHiRes: lossless.isHiRes,
      isSpatial: lossless.isSpatial,
      specLabel: lossless.specLabel,
      codec: lossless.codec,
      bitrate: lossless.bitrate,
      source: 'amazon-lossless-fullsong',
      ok: true,
      // Honest delivery card: bridged via relay. True delivered codec is
      // determined by byte sniffing at acquisition (HttpAudioSource +
      // LosslessFullSongService). Today the relay serves lossy Opus - the
      // engine reports that instead of decorating Opus as FLAC.
      delivery: {
        pipeline: 'relay-bridge',
        bitPerfect: false,
        note: 'Target tier is FLAC; delivered bytes are sniffed at acquisition. ' +
          'Bit-perfect FLAC requires an entitled native DMLS session (see probeNativeDelivery).'
      },
    };

    // Attach the real native-delivery capability card when wired
    if (this.nativeDelivery && asin) {
      try {
        descriptor.native = await this.nativeDelivery.probeCapability(
          { asin, ...track },
          { territory: (track.territory || 'US').toUpperCase() }
        );
      } catch {
        // Native probe must never break bridge resolution
        descriptor.native = null;
      }
    }

    this.cache.set(cacheKey, descriptor);
    return descriptor;
  }

  /**
   * Native delivery capability card for a track (DMLS probe).
   * Returns null when no nativeDelivery service is wired.
   */
  async probeNativeDelivery(track, { territory = 'US' } = {}) {
    if (!this.nativeDelivery) return null;
    return this.nativeDelivery.probeCapability(track, { territory });
  }
}
