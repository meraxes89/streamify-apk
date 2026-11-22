/**
 * AmazonNativeDeliveryService (DMLS / AMALS Capability Card)
 *
 * Talks the REAL Amazon Music delivery surface extracted from the production
 * web player (d5fx445wy2wpk.cloudfront.net web-hornet 1.196.81 + the live
 * vinylChunk-e54d4980a921fb73eda3fe610b852eb1.js dmls module):
 *
 *   URL:      {host}/{region}/api/dmls/
 *   Services: com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal
 *              - getDashManifestsV2          (Ultra HD FLAC delivery path)
 *              - getStreamingURLsWithFirstChunkV2
 *              - getStreamingURLsForSamples  (previews; GUEST-CAPABLE with the
 *                                             registered clientId 'WebCP' +
 *                                             real expiration timestamp)
 *              - getLicenseForPlaybackV2     (SIREN DRM license)
 *              - getStreamingURLs             (legacy COID path)
 *
 * Empirically verified with anonymous guest sessions (2026-11):
 *   - getDashManifestsV2  => 400 UnauthorizedException
 *     "CUSTOMER_INFO_NOT_PROVIDED (AuthenticationException)"
 *     Guest /config.json returns customerId === "" => the call is auth-walled.
 *   - getStreamingURLsForSamples + clientInfo.clientId='WebCP' + expiration
 *     => HTTP 200 with SIGNED CloudFront MP3 sample URLs (audio/mpeg bytes
 *     verified end-to-end). The deviceType-as-clientId shape answers
 *     400 BadRequestException "unknown client" — the registered WebCP id
 *     and a real expiration are both REQUIRED.
 *   - Tier entitlement map (web player, decoded from the shipped bundle):
 *       AMF         -> ["NIGHTWING"]               (free ad tier)
 *       PRIME       -> ["ROBIN","SONIC_RUSH"]
 *       UNLIMITED   -> ["HAWKFIRE"]
 *       UNLIMITED_HD-> ["HAWKFIRE","KATANA"]        (Ultra HD entitlement)
 *
 * This service now routes the full solve through AmazonDmlsSolver (the real
 * link solver) when a credential is available, keeps issuing typed honest
 * verdicts for guests instead of pretending to fetch DRM-wrapped bytes, and
 * serves the guest REAL sample URLs through the live-verified vector.
 */

import { AmazonDmlsSolver, DmlsSolveStatus } from './dmls-solver.mjs';

const DMLS_TARGET = (op) =>
  `com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal.${op}`;

export const NativeDeliveryStatus = Object.freeze({
  AVAILABLE: 'AVAILABLE',                  // bit-perfect lossless bytes obtainable
  LINKS_SOLVED: 'LINKS_SOLVED',            // real stream links solved (manifest + segments)
  DRM_MANIFEST: 'DRM_MANIFEST',            // manifest/links obtainable but CENC-encrypted
  AUTH_REQUIRED: 'AUTH_REQUIRED',          // customerId/session auth wall (guest sessions)
  ENTITLEMENT_REQUIRED: 'ENTITLEMENT_REQUIRED', // signed-in but lacks UNLIMITED_HD (KATANA)
  UNAVAILABLE: 'UNAVAILABLE',              // network / territory / catalog absence
  PREVIEW_ONLY: 'PREVIEW_ONLY'             // only lossy sample stream is obtainable
});

const SOLVE_TO_NATIVE = new Map([
  [DmlsSolveStatus.LINKS_SOLVED, NativeDeliveryStatus.LINKS_SOLVED],
  [DmlsSolveStatus.DRM_PROTECTED_LINKS, NativeDeliveryStatus.DRM_MANIFEST],
  [DmlsSolveStatus.AUTH_REQUIRED, NativeDeliveryStatus.AUTH_REQUIRED],
  [DmlsSolveStatus.ENTITLEMENT_REQUIRED, NativeDeliveryStatus.ENTITLEMENT_REQUIRED],
  [DmlsSolveStatus.CONTENT_NOT_FOUND, NativeDeliveryStatus.UNAVAILABLE],
  [DmlsSolveStatus.CONTENT_NOT_ELIGIBLE, NativeDeliveryStatus.ENTITLEMENT_REQUIRED],
  [DmlsSolveStatus.CONTENT_LOCKED, NativeDeliveryStatus.UNAVAILABLE],
  [DmlsSolveStatus.UNAVAILABLE, NativeDeliveryStatus.UNAVAILABLE]
]);

export class AmazonNativeDeliveryService {
  constructor({ sessionManager = null, solver = null } = {}) {
    this.sessionManager = sessionManager;
    this.solver = solver || new AmazonDmlsSolver({ sessionManager });
  }

  /**
   * Probes (or solves, with a credential) the real DMLS surface for a track
   * and returns a typed verdict card with full evidence. When `credential` is
   * supplied and authenticated, the card carries the SOLVED links: inline
   * MPD, representation cards, init/segment CDN URLs, DRM pssh + license op.
   *
   * @param {Object} track - {asin | id, title}
   * @param {Object} [options]
   * @param {string} [options.territory='US']
   * @param {Object} [options.sessionManager] - override session manager
   * @param {import('./credentials.mjs').AmazonMusicCredential} [options.credential]
   * @param {boolean} [options.forceWire=false]
   * @returns {Promise<Object>} capability card
   */
  async probeCapability(track = {}, { territory = 'US', sessionManager = null, credential = null, forceWire = false, tier = 'ultra' } = {}) {
    const asin = track.asin || (typeof track.id === 'string' ? track.id.replace('amzn:track:', '') : null);
    if (!asin) {
      return this._verdict(NativeDeliveryStatus.UNAVAILABLE, {
        reason: 'ASIN is required to address DMLS contentIdList',
        detail: null
      });
    }

    const solved = await this.solver.solveStreamLinks({
      asin,
      territory,
      credential,
      tier,
      forceWire
    });

    const nativeStatus = SOLVE_TO_NATIVE.get(solved.status) || NativeDeliveryStatus.UNAVAILABLE;

    const card = this._verdict(nativeStatus, {
      reason: solved.reason ||
        (solved.status === DmlsSolveStatus.LINKS_SOLVED || solved.status === DmlsSolveStatus.DRM_PROTECTED_LINKS
          ? 'Real DMLS links solved (manifest + representation + segment URLs)'
          : solved.reason || 'DMLS verdict'),
      evidence: {
        ...solved.evidence,
        territory: solved.territory,
        asin,
        customerIdPresent: !!solved.session?.authenticated,
        session: solved.session,
        marketplaceId: null,
        howToAuthenticate: solved.howToAuthenticate || null
      },
      detail: solved.detail || null,
      manifest: solved.manifest || null
    });

    // Solved links ride on the card (this IS the actual solving output)
    card.links = solved.links || null;
    card.selected = solved.selected || null;
    card.drm = solved.drm || null;
    card.firstChunk = solved.firstChunk || null;
    card.solveStatus = solved.status;
    card.requestedTier = solved.requestedTier;
    if (solved.wall) card.wall = solved.wall;

    // AVAILABLE means bit-perfect clear FLAC bytes fetchable end-to-end
    card.available =
      nativeStatus === NativeDeliveryStatus.LINKS_SOLVED &&
      solved.drm?.protected === false;

    return card;
  }

  /**
   * Requests the sample/preview stream (getStreamingURLsForSamples) through
   * the production guest-capable vector: registered client id 'WebCP' + a
   * real expiration timestamp (now + 3600). Live-verified for anonymous
   * guests: HTTP 200 + signed CloudFront MP3 URLs (audio/mpeg bytes).
   *
   * @returns {Promise<Object>} { status, streamUrl, lossy, detail, expiresAt }
   */
  async getSampleStream(asin, { territory = 'US', sessionManager = null, clientId = null, expirationSec = 3600 } = {}) {
    const sm = sessionManager || this.solver.sessionManager;
    if (!sm) throw new Error('sessionManager is required for sample streaming');
    const session = await sm.getSession(territory.toUpperCase());

    const headers = sm.buildHeaders(session, DMLS_TARGET('getStreamingURLsForSamples'));
    const expiresAt = Math.floor(Date.now() / 1000) + expirationSec;
    const body = {
      tracksInfoList: [{ asin, contentFormat: 'mp3', bitrate: 64 }],
      deviceInfo: { deviceType: session.deviceType },
      marketplaceId: session.marketplaceId,
      territoryId: session.musicTerritory,
      // Registered web-player client + real expiration: BOTH required.
      // deviceType-as-clientId answers 400 "unknown client" (live-verified).
      expiration: expiresAt,
      clientInfo: { clientId: clientId || this.solver.clientId || 'WebCP' },
      protocolList: ['https']
    };

    try {
      const res = await fetch(`${session.host}/${session.region}/api/dmls/`, {
        method: 'POST',
        headers,
        body: JSON.stringify(body)
      });
      const text = await res.text();
      let data = {};
      try { data = JSON.parse(text); } catch { /* non-JSON */ }

      // Production envelope: { urls: [[url, ...], ...] } -> urls[0][0]
      const url = Array.isArray(data.urls) && Array.isArray(data.urls[0])
        ? data.urls[0][0] || null
        : null;
      const serverVerdict = data?.message || data?.__type || text.slice(0, 200);
      return {
        status: url ? NativeDeliveryStatus.PREVIEW_ONLY : NativeDeliveryStatus.UNAVAILABLE,
        streamUrl: url,
        lossy: true,
        expiresAt,
        detail: url
          ? 'real signed CloudFront MP3 sample (preview; guest-capable, live-verified)'
          : `getStreamingURLsForSamples returned no URL: ${serverVerdict}`
      };
    } catch (err) {
      return { status: NativeDeliveryStatus.UNAVAILABLE, streamUrl: null, detail: err.message };
    }
  }

  _extractSampleUrl(data) {
    // Production envelope first: { urls: [[url, ...], ...] } -> urls[0][0].
    if (Array.isArray(data?.urls) && Array.isArray(data.urls[0]) && data.urls[0][0]) {
      return data.urls[0][0];
    }
    // Defensive multi-shape extraction; DMLS response envelopes rotate across versions.
    const candidates = [];
    const visit = (node, depth = 0) => {
      if (!node || depth > 6) return;
      if (typeof node === 'string') {
        if (/^https?:\/\/.+\.(mp3|m4a|mp4)/i.test(node)) candidates.push(node);
        return;
      }
      if (typeof node !== 'object') return;
      for (const k of ['streamUrl', 'url', 'streamURL', 'resourceUrl']) {
        if (typeof node[k] === 'string' && /^https?:\/\//.test(node[k])) candidates.push(node[k]);
      }
      for (const v of Object.values(node)) visit(v, depth + 1);
    };
    visit(data);
    return candidates[0] || null;
  }

  _verdict(status, { reason, evidence = {}, detail = null, manifest = null }) {
    return {
      service: 'com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal',
      status,                                   // NativeDeliveryStatus
      available: status === NativeDeliveryStatus.AVAILABLE,
      reason,
      evidence,
      detail,
      manifest,
      probedAt: new Date().toISOString()
    };
  }
}
