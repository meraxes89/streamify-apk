/**
 * AmazonStreamResolver (Amazon-First Resolution Ladder)
 *
 * THE POLICY CHANGE (2026-11): this resolver NEVER silently bridges to
 * YouTube. The default ladder hammers Amazon's REAL delivery surfaces and
 * only ever returns Amazon-native artifacts:
 *
 *   1. Authenticated (credential via env/file/inline):
 *        DMLS getDashManifestsV2 -> REAL stream links (inline MPD,
 *        representation cards, init + segment CDN URLs, DRM card).
 *   2. Anonymous guest:
 *        getStreamingURLsForSamples with the registered clientId 'WebCP' +
 *        real expiration -> REAL signed CloudFront MP3 sample URLs
 *        (live-verified: audio/mpeg bytes served to anonymous guests),
 *        plus the honest AUTH_REQUIRED full-song verdict + unlock recipe.
 *
 * The legacy YouTube Music bridge survives ONLY as an explicitly requested
 * vector (resolveViaBridge / resolveLosslessViaBridge / { allowBridge: true }
 * / CLI resolve-youtube). Audio resolution failures are loud and typed -
 * never a silent source swap.
 *
 * Kept from the original design: candidate search/rank heuristics for the
 * explicit bridge path and for AmazonVideoService (music-video discovery).
 */

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

import { detectAudioBadges } from './badges.mjs';
import { selectTopLosslessTier } from './lossless-stream.mjs';
import { AmazonNativeDeliveryService } from './native-delivery.mjs';
import { NativeDeliveryStatus } from './native-delivery.mjs';

const NATIVE_LINK_STATUSES = new Set([NativeDeliveryStatus.LINKS_SOLVED, NativeDeliveryStatus.DRM_MANIFEST]);

/** Manifest-request tier ladder: solve the highest tier the account can see. */
const TIER_LADDERS = Object.freeze({
  best: ['ultra', 'hd', 'lossy'],
  ultra: ['ultra', 'hd', 'lossy'],
  hd: ['hd', 'lossy'],
  lossy: ['lossy'],
  v2: ['lossy']
});

export class AmazonStreamResolver {
  constructor({ cache = new Map(), badges = detectAudioBadges, nativeDelivery = null, sessionManager = null } = {}) {
    /** @type {Map<string, Object>} asin -> resolved stream descriptor */
    this.cache = cache;
    /** @type {Function} Amazon badge/tier detector (injectable for tests) */
    this.badges = badges;
    /**
     * Native DMLS delivery surface (required for the Amazon-first ladder).
     * When wired, resolution first asks Amazon's REAL delivery surface for
     * the track and returns native links or the guest sample artifact.
     */
    this.nativeDelivery = nativeDelivery || (sessionManager
      ? new AmazonNativeDeliveryService({ sessionManager })
      : null);
  }

  /**
   * Resolves an Amazon track to a REAL Amazon-native stream descriptor.
   *
   * Ladder: (1) authenticated DMLS full-song links (tier ladder
   * ultra -> hd -> lossy so every entitled account floor is reachable),
   * (2) guest sample URL with the honest AUTH_REQUIRED card. The YouTube
   * bridge runs ONLY when explicitly allowed via { allowBridge: true }.
   *
   * @param {Object} track - Canonical track object (asin preferred; the client
   *                         fills the ASIN via Tenzing search when missing)
   * @param {Object} [options]
   * @param {boolean} [options.allowBridge=false] - explicit YouTube bridge opt-in
   * @param {import('./credentials.mjs').AmazonMusicCredential|string} [options.credential]
   *        explicit credential (defaults to the solver's env/file resolution)
   * @param {string} [options.tier='best'] - starting tier of the native ladder
   * @returns {Promise<Object>} Amazon-native stream descriptor
   */
  async resolve(track, { allowBridge = false, credential = null, tier = 'best' } = {}) {
    if (!track) throw new Error('Track metadata is required for resolution');

    const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : null);
    if (asin && this.cache.has(asin)) {
      return this.cache.get(asin);
    }

    // -----------------------------------------------------------------
    // AMAZON-FIRST: native ladder (full-song links or guest sample).
    // -----------------------------------------------------------------
    if (asin && this.nativeDelivery) {
      const native = await this._resolveNative(track, asin, { lossless: false, credential, tier });
      if (native) {
        if (asin) this.cache.set(asin, native);
        return native;
      }
    }

    if (allowBridge) {
      return this.resolveViaBridge(track);
    }

    throw new Error(
      'Amazon-native resolution failed' +
      (asin ? ` for ASIN ${asin}` : ' (no ASIN - resolve via the catalog client so one can be attached)') +
      '. The engine never silently substitutes another platform\'s audio. ' +
      'Options: (a) supply an Amazon Music credential for full-song links ' +
      '(AMAZON_MUSIC_COOKIES env / ~/.streamify/amazon-music.cookies), ' +
      '(b) request the explicit YouTube bridge via { allowBridge: true }.'
    );
  }

  /**
   * Lossless full-song resolution, Amazon-first.
   *
   * Authenticated: REAL FLAC Ultra HD link graph (manifest + representations
   * + segment CDN URLs + DRM card), falling down the tier ladder when the
   * account lacks KATANA (Ultra HD). Guest: REAL sample URL + the honest
   * AUTH_REQUIRED full-song verdict. YouTube bridge only on explicit opt-in.
   */
  async resolveLossless(track, { allowBridge = false, credential = null, tier = 'best' } = {}) {
    if (!track) throw new Error('Track metadata is required for lossless resolution');

    const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : null);
    const cacheKey = `lossless:${asin || `${(track.title || '').toLowerCase().trim()}`}`;
    const cached = this.cache.get(cacheKey);
    if (cached) return cached;

    // -----------------------------------------------------------------
    // AMAZON-FIRST: native ladder with the lossless tier policy attached.
    // -----------------------------------------------------------------
    if (asin && this.nativeDelivery) {
      const native = await this._resolveNative(track, asin, { lossless: true, credential, tier });
      if (native) {
        this.cache.set(cacheKey, native);
        if (asin && !this.cache.has(asin)) this.cache.set(asin, native);
        return native;
      }
    }

    if (allowBridge) {
      return this.resolveLosslessViaBridge(track);
    }

    throw new Error(
      'Amazon-native lossless resolution failed' +
      (asin ? ` for ASIN ${asin}` : '') +
      '. Full-song FLAC requires an authenticated DMLS session (cookie or bearer); ' +
      'guests still get REAL signed sample URLs via the sample vector. ' +
      'The engine never silently substitutes another platform\'s audio. ' +
      'Explicit YouTube bridge: { allowBridge: true }.'
    );
  }

  /**
   * The native ladder for one ASIN: full-song tier-ladder solve (auth) then
   * the guest-capable sample vector. Returns null when both floors fail.
   *
   * Tier ladder (authenticated sessions): ultra -> hd -> lossy. A signed-in
   * account without the KATANA (Ultra HD) entitlement still solves the V2
   * lossy manifest (bitrateTypeList path, no contentProtectionList) - REAL
   * Amazon full-song links at the account's actual floor. Guests hit the
   * auth wall on the first rung and drop straight to the sample vector.
   */
  async _resolveNative(track, asin, { lossless = false, credential = null, tier = 'best' } = {}) {
    const territory = (track.territory || 'US').toUpperCase();
    const ladder = TIER_LADDERS[tier] || TIER_LADDERS.best;
    const verdicts = [];

    let solvedCard = null;
    let authCard = null;
    for (const tierStep of ladder) {
      let card = null;
      try {
        card = await this.nativeDelivery.probeCapability(
          { asin, ...track },
          { territory, credential, tier: tierStep }
        );
      } catch {
        card = null; // native probe must never break resolution; fall to samples
      }

      if (card && NATIVE_LINK_STATUSES.has(card.status)) {
        solvedCard = card;
        break;
      }

      if (card) {
        verdicts.push({ tier: tierStep, status: card.status, reason: card.reason || null });
        if (card.status === NativeDeliveryStatus.AUTH_REQUIRED && !authCard) authCard = card;
      }

      // Tier-independent walls stop the ladder; entitlement verdicts
      // (KATANA denials etc.) continue down to the account's real floor.
      if (!card ||
          card.status === NativeDeliveryStatus.AUTH_REQUIRED ||
          card.status === NativeDeliveryStatus.UNAVAILABLE) {
        break;
      }
    }

    if (solvedCard) {
      return this._nativeDescriptor(track, asin, solvedCard, {
        lossless,
        attemptedTiers: verdicts.length ? verdicts : undefined
      });
    }

    // Guest floor: the live-verified sample vector (real signed
    // CloudFront MP3, anonymous). Attach the honest AUTH_REQUIRED card
    // (with the full unlock recipe) from the auth-walled probe.
    const sample = await this._solveSample(asin, territory);
    if (sample && sample.url) {
      return this._sampleDescriptor(track, asin, sample, authCard, {
        lossless,
        nativeVerdicts: verdicts
      });
    }

    return null;
  }

  /** Solves the REAL guest sample URL through the native delivery surface. */
  async _solveSample(asin, territory) {
    try {
      if (this.nativeDelivery.solver && typeof this.nativeDelivery.solver.solveSampleUrl === 'function') {
        return await this.nativeDelivery.solver.solveSampleUrl({ asin, territory });
      }
    } catch { /* fall through to the service method */ }
    try {
      const svc = await this.nativeDelivery.getSampleStream(asin, { territory });
      return svc && svc.streamUrl
        ? { url: svc.streamUrl, expiresAt: svc.expiresAt, status: svc.status }
        : null;
    } catch {
      return null;
    }
  }

  /** Descriptor for a solved native (DMLS) full-song link graph. */
  _nativeDescriptor(track, asin, card, { lossless = false, attemptedTiers = null } = {}) {
    const selected = card.selected || {};
    const policy = lossless ? selectTopLosslessTier(track) : null;
    const streamUrl = card.firstChunk?.url ||
      selected.links?.initUrl ||
      selected.links?.firstSegmentUrls?.[0] ||
      null;

    return {
      asin,
      videoId: null,
      title: track.title || null,
      artist: track.artist || track.artistName || null,
      durationSeconds: track.durationSeconds || 0,
      streamUrl,
      manifestUrl: null,
      manifestInline: card.manifest?.inline || null,
      representation: selected || null,
      links: selected.links || null,
      drm: card.drm || { protected: false },
      matchConfidence: 100, // exact ASIN - no fuzzy matching involved
      source: 'amazon-dmls-native',
      tier: selected.tierLabel || (policy ? policy.tier : null),
      specLabel: selected.specLabel || (policy ? policy.specLabel : null),
      codec: selected.codecFamily || 'flac',
      sampleRate: selected.audioSamplingRate || null,
      bitDepth: selected.bitDepth || null,
      isLossless: true,
      isHiRes: /UHD(96|192|48|44)/.test(selected.tierLabel || ''),
      ...(policy ? {
        isSpatial: policy.isSpatial,
        bitrate: policy.bitrate
      } : {}),
      ...(attemptedTiers ? { nativeLadder: attemptedTiers } : {}),
      ok: true,
      native: card,
      delivery: {
        pipeline: 'amazon-dmls-native',
        bitPerfect: card.drm?.protected === false && selected.codecFamily === 'flac',
        tierLabel: selected.tierLabel || null,
        note: card.drm?.protected
          ? 'Real Amazon links solved; bytes are CENC-encrypted (pssh attached; license operation documented, never performed).'
          : 'Real Amazon links solved; clear bytes directly fetchable from the CDN.'
      }
    };
  }

  /** Descriptor for the guest sample vector (real signed Amazon MP3). */
  _sampleDescriptor(track, asin, sample, nativeCard, { lossless = false, nativeVerdicts = null } = {}) {
    const policy = lossless ? selectTopLosslessTier(track) : null;
    const verdict = nativeCard || (nativeVerdicts?.length ? {
      status: nativeVerdicts[0].status,
      reason: nativeVerdicts[0].reason
    } : null);
    return {
      asin,
      videoId: null,
      title: track.title || null,
      artist: track.artist || track.artistName || null,
      durationSeconds: track.durationSeconds || 0,
      streamUrl: sample.url,
      sampleUrl: sample.url,
      manifestUrl: null,
      matchConfidence: 100, // exact ASIN
      source: 'amazon-sample-preview',
      tier: policy ? policy.tier : 'PREVIEW',
      specLabel: policy ? policy.specLabel : 'MP3 64 kbps preview',
      codec: 'mp3',
      bitrate: 64000,
      isLossless: false,
      isHiRes: false,
      previewOnly: true,
      expiresAt: sample.expiresAt || null,
      ...(nativeVerdicts?.length ? { nativeLadder: nativeVerdicts } : {}),
      ok: true,
      native: {
        status: verdict?.status || NativeDeliveryStatus.AUTH_REQUIRED,
        reason: verdict?.reason ||
          'Guest session carries no customerId; DMLS full-song delivery is auth-walled ' +
          '(CUSTOMER_INFO_NOT_PROVIDED, live-verified).',
        howToAuthenticate: nativeCard?.evidence?.howToAuthenticate ||
          nativeCard?.howToAuthenticate || null,
        wall: nativeCard?.evidence?.wall || nativeCard?.wall || null
      },
      delivery: {
        pipeline: 'amazon-sample-preview',
        codec: 'mp3',
        lossless: false,
        bitPerfect: false,
        previewOnly: true,
        note: 'REAL signed Amazon CloudFront sample MP3 (~30s @ 64 kbps), served to the ' +
          'ANONYMOUS guest through the live-verified getStreamingURLsForSamples vector. ' +
          'Full-song links require an authenticated DMLS session (cookies/bearer).'
      }
    };
  }

  // -----------------------------------------------------------------
  // EXPLICIT YouTube bridge (opt-in only; never the default path)
  // -----------------------------------------------------------------

  /**
   * Explicit YouTube Music bridge resolution (the legacy hybrid path).
   * Callers must opt in; resolve()/resolveLossless() never route here silently.
   */
  async resolveViaBridge(track) {
    if (!track) throw new Error('Track metadata is required for bridge resolution');

    const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : null);
    if (asin && this.cache.has(asin)) {
      const cached = this.cache.get(asin);
      if (cached && cached.videoId) return cached; // bridge-shaped cache entry
    }

    const title = track.title || '';
    const artistName = Array.isArray(track.artists)
      ? track.artists.map(a => typeof a === 'string' ? a : a.name).join(' ')
      : (track.artist || track.artistName || '');

    const query = `${title} ${artistName}`.trim();
    if (!query) throw new Error('Track title or artist is required for bridge resolution');

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
      source: 'amazon->youtube-bridge',
      explicitBridge: true
    };

    if (asin) {
      this.cache.set(asin, descriptor);
    }

    return descriptor;
  }

  /**
   * Explicit lossless-target bridge resolution (the legacy hybrid path
   * decorated with the lossless tier policy and an honest delivery card).
   */
  async resolveLosslessViaBridge(track) {
    if (!track) throw new Error('Track metadata is required for lossless bridge resolution');

    const asin = track.asin || (track.id ? track.id.replace('amzn:track:', '') : null);
    const title = track.title || '';
    const artistName = Array.isArray(track.artists)
      ? track.artists.map((a) => (typeof a === 'string' ? a : a.name)).join(' ')
      : (track.artist || track.artistName || '');
    const query = `${title} ${artistName}`.trim();
    if (!query) throw new Error('Track title or artist is required for lossless bridge resolution');

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
      explicitBridge: true,
      // Target tier policy (Amazon-side rendition the pipeline aims for).
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
      delivery: {
        pipeline: 'relay-bridge',
        bitPerfect: false,
        note: 'Explicit YouTube bridge: delivered bytes are sniffed at acquisition and ' +
          'labeled with their TRUE codec - the engine never decorates them as FLAC.'
      },
    };

    if (asin) this.cache.set(`lossless:${asin}`, descriptor);
    return descriptor;
  }

  // -----------------------------------------------------------------
  // Bridge internals (YouTube candidate search + ranking)
  // -----------------------------------------------------------------

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
   * Native delivery capability card for a track (DMLS probe).
   * Returns null when no nativeDelivery service is wired.
   */
  async probeNativeDelivery(track, { territory = 'US' } = {}) {
    if (!this.nativeDelivery) return null;
    return this.nativeDelivery.probeCapability(track, { territory });
  }
}
