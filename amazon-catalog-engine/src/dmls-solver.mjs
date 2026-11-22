/**
 * AmazonDmlsSolver - THE REAL Amazon Music stream-link solver
 *
 * Speaks the production DMLS wire protocol (decoded end-to-end from the
 * web-hornet Vinyl player, d5fx445wy2wpk.cloudfront.net 1.196.81-hf.1):
 *
 *   URL      {host}/{region}/api/dmls/
 *   Target   com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal.<op>
 *   Headers  Content-Encoding: amz-1.0 (Coral dispatcher key)
 *            Content-Type: application/json
 *            Authorization: Bearer <oAuthToken>  (bearer mode)
 *            Cookie: <sign-in jar>                (cookie mode - player default)
 *            csrf-token / csrf-rnd / csrf-ts
 *
 *   FLAC/Opus manifest request (production shape, vinyl de()):
 *     { appInfo:{musicAgent:"Vinyl/2.0 ..."}, customerId, customerInfo:
 *       {marketplaceId, territoryId}, contentIdList:[{identifier, identifierType:"ASIN"}],
 *       deviceToken, musicDashVersionList:["SIREN_KATANA"],
 *       contentProtectionList:["TRACK_PSSH"], tryAsinSubstitution:true }
 *
 *   Manifest response envelope:
 *     { contentResponseList:[{ manifest:<inline MPD XML>, cdnType,
 *        substituteAsin, contentResponseStatusCode, contentResponseStatusMessage }] }
 *     status universe (MpegDashContentResponseStatusCode): SUCCESS,
 *       CONTENT_NOT_FOUND, CONTENT_NOT_ELIGIBLE, CONTENT_LOCKED,
 *       CUSTOMER_NOT_ELIGIBLE, CUSTOMER_NOT_VALID, CLIENT_NOT_ELIGIBLE,
 *       CLIENT_NOT_VALID, DEVICE_NOT_AUTHORIZED, DEVICE_NOT_ELIGIBLE, ...
 *
 *   First-chunk URL request (production shape, chunk_796 e.getStreamingURLsWithFirstChunkV2):
 *     { contentIdList, customerId, customerInfo:{marketplaceId, territoryId,
 *       entitlementList}, musicRequestIdentityContext:{musicIdentities:
 *       {musicAccountCid}}, protocol, format, drmType, deviceToken,
 *       appMetadata, clientMetadata, bitRate }
 *     response: { contentResponseList:[{ urlList:[...], cdnTypeList,
 *       statusCode, statusMessage }], drmPlaybackResource, clientCertificate }
 *
 *   MPD dialect: see dash-mpd.mjs (amz-music:bitDepth / amz-music:trackType,
 *   quality ladder UHD96/UHD192..., codec family flac/opus/aac, CENC pssh).
 *
 * Entitlement truth (live-verified 2026-11): anonymous guests receive
 *   UnauthorizedException "CUSTOMER_INFO_NOT_PROVIDED (AuthenticationException)"
 * The solver is credential-driven: with the operator's own Amazon Music cookie
 * jar (cookie mode) it solves the REAL links - inline MPD, representation
 * cards, init + media segment CDN URLs, first-chunk URL, DRM card with pssh
 * and the getLicenseForPlaybackV2 operation - for FLAC Ultra HD (KATANA)
 * and every other ladder tier the account can see.
 */

import { AmazonSessionManager } from './session.mjs';
import { resolveAmazonMusicCredential, AmazonMusicCredential } from './credentials.mjs';
import {
  parseDashManifest,
  resolveSegmentUrls,
  selectRepresentationForTier
} from './dash-mpd.mjs';

export const DmlsSolveStatus = Object.freeze({
  LINKS_SOLVED: 'LINKS_SOLVED',                       // manifest + links (clear bytes fetchable)
  DRM_PROTECTED_LINKS: 'DRM_PROTECTED_LINKS',         // manifest + links solved; CENC (pssh) wrapped
  AUTH_REQUIRED: 'AUTH_REQUIRED',                    // no authenticated customer (guest)
  ENTITLEMENT_REQUIRED: 'ENTITLEMENT_REQUIRED',       // signed in, tier denied (no KATANA/UHD)
  CONTENT_NOT_FOUND: 'CONTENT_NOT_FOUND',
  CONTENT_NOT_ELIGIBLE: 'CONTENT_NOT_ELIGIBLE',
  CONTENT_LOCKED: 'CONTENT_LOCKED',
  UNAVAILABLE: 'UNAVAILABLE'
});

const DMLS_SERVICE = 'com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal';
const dmlsTarget = (op) => `${DMLS_SERVICE}.${op}`;

const MUSIC_AGENT = 'Vinyl/2.0 WebPlayer/1.196.81';

const GUEST_WALL = Object.freeze({
  __type: 'com.amazon.digitalmusiclocator#UnauthorizedException',
  message: 'Request authentication failure : CUSTOMER_INFO_NOT_PROVIDED (AuthenticationException)',
  liveVerified: true
});

function coralErrorType(body) {
  if (!body || typeof body !== 'object') return null;
  return body.__type || body.Output?.__type || null;
}

function mapManifestStatus(statusCode) {
  switch (statusCode) {
    case 'SUCCESS': return DmlsSolveStatus.LINKS_SOLVED;
    case 'CONTENT_NOT_FOUND': return DmlsSolveStatus.CONTENT_NOT_FOUND;
    case 'CONTENT_NOT_ELIGIBLE':
    case 'CUSTOMER_NOT_ELIGIBLE':
    case 'CLIENT_NOT_ELIGIBLE':
    case 'DEVICE_NOT_ELIGIBLE':
    case 'CUSTOMER_NOT_VALID':
    case 'CLIENT_NOT_VALID': return DmlsSolveStatus.ENTITLEMENT_REQUIRED;
    case 'CONTENT_LOCKED': return DmlsSolveStatus.CONTENT_LOCKED;
    default: return DmlsSolveStatus.UNAVAILABLE;
  }
}

export class AmazonDmlsSolver {
  constructor({
    sessionManager = new AmazonSessionManager(),
    musicAgent = MUSIC_AGENT
  } = {}) {
    this.sessionManager = sessionManager;
    this.musicAgent = musicAgent;
  }

  /**
   * Solves the ACTUAL Amazon stream links for a track ASIN.
   *
   * @param {Object} options
   * @param {string} options.asin - track ASIN (or amzn:track:ASIN)
   * @param {string} [options.territory='US']
   * @param {import('./credentials.mjs').AmazonMusicCredential|string} [options.credential]
   *        AmazonMusicCredential, a raw cookie header string, or null for guest.
   *        Falls back to resolveAmazonMusicCredential() (env/file) when omitted.
   * @param {string} [options.tier='ultra'] - 'ultra' | 'hd' | 'lossy' | 'best'
   * @param {boolean} [options.includeFirstChunk=false] - also solve
   *        getStreamingURLsWithFirstChunkV2 direct URL
   * @param {boolean} [options.forceWire=false] - issue the DMLS call even for
   *        guests (captures the live wall as first-class evidence)
   * @returns {Promise<Object>} solved link card (see README)
   */
  async solveStreamLinks({
    asin,
    territory = 'US',
    credential = null,
    tier = 'ultra',
    includeFirstChunk = false,
    forceWire = false
  } = {}) {
    if (!asin) throw new Error('ASIN is required to solve DMLS stream links');
    const normalizedAsin = String(asin).replace('amzn:track:', '');
    const terr = territory.toUpperCase();

    const cred = this._normalizeCredential(credential);

    const session = await this.sessionManager.getSession(terr, { credential: cred });

    const baseCard = {
      solver: 'AmazonDmlsSolver',
      service: DMLS_SERVICE,
      operation: 'getDashManifestsV2',
      asin: normalizedAsin,
      territory: terr,
      requestedTier: tier,
      session: {
        authenticated: !!session.authenticated,
        customerId: session.customerId || null,
        musicTier: session.musicTier || null,
        tierInferred: session.tierInferred ?? false,
        entitlementList: session.entitlementList || [],
        credentialSource: session.credentialSource || null
      },
      evidence: {}
    };

    // Guest fast-path: the wall is live-verified; don't burn a guaranteed 400
    // unless the caller explicitly wants the wire evidence.
    if (!session.authenticated && !forceWire) {
      return {
        ...baseCard,
        status: DmlsSolveStatus.AUTH_REQUIRED,
        links: null,
        manifest: null,
        reason:
          'Guest session carries no customerId; DMLS getDashManifestsV2 answers ' +
          '"CUSTOMER_INFO_NOT_PROVIDED (AuthenticationException)" (live-verified). ' +
          'Solve real links by supplying an Amazon Music credential (cookies or bearer): ' +
          'AMAZON_MUSIC_COOKIES env / ~/.streamify/amazon-music.cookies / credential option.',
        wall: GUEST_WALL,
        howToAuthenticate: {
          mode: 'cookie (production default)',
          recipe: [
            'Sign in to music.amazon.com in a normal browser',
            'Copy the request Cookie header (or export the cookie jar for music.amazon.com)',
            'Export AMAZON_MUSIC_COOKIES="at-main=...; x-main=...; ..." ' +
            '(or write it to ~/.streamify/amazon-music.cookies)',
            'Re-run: solveStreamLinks({ asin, credential }) or CLI: flac-links <asin>'
          ]
        }
      };
    }

    // ------------------------------------------------------------------
    // Authenticated: issue the REAL FLAC/Opus manifest request.
    // ------------------------------------------------------------------
    const requestBody = {
      appInfo: { musicAgent: this.musicAgent },
      customerId: session.customerId,
      customerInfo: {
        marketplaceId: session.marketplaceId,
        territoryId: terr
      },
      contentIdList: [{ identifier: normalizedAsin, identifierType: 'ASIN' }],
      deviceToken: {
        deviceTypeId: session.deviceType,
        deviceId: session.deviceId
      },
      musicDashVersionList: ['SIREN_KATANA'],
      contentProtectionList: ['TRACK_PSSH'],
      tryAsinSubstitution: true
    };

    const url = `${session.host}/${session.region}/api/dmls/`;
    const headers = this.sessionManager.buildHeaders(session, dmlsTarget('getDashManifestsV2'));

    let res;
    let body = {};
    let rawText = '';
    try {
      res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(requestBody) });
      rawText = await res.text();
      try { body = JSON.parse(rawText); } catch { body = {}; }
    } catch (err) {
      return {
        ...baseCard,
        status: DmlsSolveStatus.UNAVAILABLE,
        links: null,
        manifest: null,
        reason: `DMLS network failure: ${err.message}`,
        evidence: { url }
      };
    }

    const errorType = coralErrorType(body);

    // Coral-level auth wall (e.g. expired cookies)
    if ((errorType || '').includes('Unauthorized') || (errorType || '').includes('Authentication') ||
        res.status === 401 || res.status === 403) {
      return {
        ...baseCard,
        status: DmlsSolveStatus.AUTH_REQUIRED,
        links: null,
        manifest: null,
        reason: `DMLS rejected the credential: ${errorType || `HTTP ${res.status}`}`,
        detail: body.message || rawText.slice(0, 400),
        evidence: { url, requestId: body.requestId || null, httpStatus: res.status }
      };
    }

    const entry = Array.isArray(body.contentResponseList) ? body.contentResponseList[0] : null;

    if (!entry) {
      return {
        ...baseCard,
        status: DmlsSolveStatus.UNAVAILABLE,
        links: null,
        manifest: null,
        reason: `DMLS returned no content response (${errorType || `HTTP ${res.status}`})`,
        detail: body.message || rawText.slice(0, 400),
        evidence: { url, requestId: body.requestId || null }
      };
    }

    const entryStatus = entry.contentResponseStatusCode || entry.statusCode || null;

    if (entryStatus && entryStatus !== 'SUCCESS') {
      const status = mapManifestStatus(entryStatus);
      return {
        ...baseCard,
        status,
        links: null,
        manifest: null,
        reason: `DMLS content verdict: ${entryStatus}${entry.contentResponseStatusMessage || entry.statusMessage ? ` - ${entry.contentResponseStatusMessage || entry.statusMessage}` : ''}`,
        evidence: {
          url,
          requestId: body.requestId || null,
          contentResponseStatusCode: entryStatus,
          substituteAsin: entry.substituteAsin ?? null
        }
      };
    }

    if (!entry.manifest || typeof entry.manifest !== 'string') {
      return {
        ...baseCard,
        status: DmlsSolveStatus.UNAVAILABLE,
        links: null,
        manifest: null,
        reason: 'DMLS SUCCESS without an inline manifest string (unexpected envelope)',
        evidence: { url, entryKeys: Object.keys(entry) }
      };
    }

    // ------------------------------------------------------------------
    // Manifest solved: parse the inline MPD and build the link graph.
    // ------------------------------------------------------------------
    let manifest;
    try {
      manifest = parseDashManifest(entry.manifest);
    } catch (err) {
      return {
        ...baseCard,
        status: DmlsSolveStatus.UNAVAILABLE,
        links: null,
        manifest: null,
        reason: `MPD parse failure: ${err.message}`,
        evidence: { url, manifestHead: entry.manifest.slice(0, 200) }
      };
    }

    const representations = manifest.representations.map(rep => {
      const links = resolveSegmentUrls(manifest, rep, { origin: session.host });
      return {
        id: rep.id,
        tierLabel: rep.tierLabel,
        specLabel: rep.specLabel,
        codec: rep.codecs,
        codecFamily: rep.codecFamily,
        lossless: rep.lossless,
        mimeType: rep.mimeType,
        audioSamplingRate: rep.audioSamplingRate,
        bitDepth: rep.bitDepth,
        trackType: rep.trackType,
        bandwidth: rep.bandwidth,
        drmProtected: rep.drmProtected,
        contentProtection: rep.contentProtections.length ? rep.contentProtections : null,
        links
      };
    });

    const selected = selectRepresentationForTier(representations, tier) || representations[0] || null;

    const card = {
      ...baseCard,
      status: selected && selected.drmProtected
        ? DmlsSolveStatus.DRM_PROTECTED_LINKS
        : DmlsSolveStatus.LINKS_SOLVED,
      manifest: {
        inline: entry.manifest,
        mpd: manifest.mpd,
        cdnType: entry.cdnType ?? null,
        substituteAsin: entry.substituteAsin ?? null,
        representationCount: representations.length,
        representations
      },
      selected,
      links: selected ? selected.links : null,
      drm: selected && selected.drmProtected
        ? {
            protected: true,
            envelope: 'SIREN_KATANA CENC (TRACK_PSSH)',
            keySystems: [...new Set((selected.contentProtection || []).flatMap(cp => [
              ...(cp.schemeIdUri || '').includes('widevine') || /widevine/i.test(cp.value || '') ? ['Widevine'] : [],
              ...(cp.schemeIdUri || '').includes('playready') || /playready/i.test(cp.value || '') ? ['PlayReady'] : [],
              ...(cp.schemeIdUri || '').includes('cenc') ? ['CENC'] : []
            ]))],
            psshList: (selected.contentProtection || []).flatMap(cp => cp.psshList).filter(Boolean),
            licenseOperation: `${DMLS_SERVICE}.getLicenseForPlaybackV2`,
            note: 'Links are solved (init/segment CDN URLs + pssh). Bytes are CENC-encrypted; ' +
              'decryption requires a DRM client license round-trip the engine does not perform.'
          }
        : { protected: false },
      evidence: {
        ...baseCard.evidence,
        url,
        requestId: body.requestId || null,
        httpStatus: res.status,
        contentResponseStatusCode: entryStatus || 'SUCCESS'
      }
    };

    if (includeFirstChunk) {
      card.firstChunk = await this.solveFirstChunkUrl({
        asin: normalizedAsin,
        territory: terr,
        credential: cred,
        session
      });
    }

    return card;
  }

  /**
   * Solves the direct streaming URL via getStreamingURLsWithFirstChunkV2
   * (production shape decoded from chunk_796). Returns the URL + DRM resource.
   */
  async solveFirstChunkUrl({
    asin,
    territory = 'US',
    credential = null,
    session = null,
    protocol = 'DASH',
    format = 'ENCRYPTED_AAC',
    drmType = 'WIDEVINE',
    bitRate = 'HIGH'
  } = {}) {
    const normalizedAsin = String(asin).replace('amzn:track:', '');
    const terr = territory.toUpperCase();
    const cred = this._normalizeCredential(credential);
    const sess = session || await this.sessionManager.getSession(terr, { credential: cred });

    if (!sess.authenticated) {
      return {
        status: DmlsSolveStatus.AUTH_REQUIRED,
        url: null,
        reason: 'getStreamingURLsWithFirstChunkV2 requires an authenticated customer ' +
          '(same CUSTOMER_INFO_NOT_PROVIDED wall as the manifest path).'
      };
    }

    const body = {
      contentIdList: [{ identifier: normalizedAsin, identifierType: 'ASIN' }],
      customerId: sess.customerId,
      customerInfo: {
        marketplaceId: sess.marketplaceId,
        territoryId: terr,
        entitlementList: sess.entitlementList
      },
      musicRequestIdentityContext: {
        musicIdentities: { musicAccountCid: sess.customerId }
      },
      protocol,
      format,
      drmType,
      bitRate,
      deviceToken: {
        deviceTypeId: sess.deviceType,
        deviceId: sess.deviceId
      },
      appMetadata: { https: 'true' },
      clientMetadata: {
        clientId: sess.deviceType,
        clientRequestId: `amzn-engine-${Date.now()}`
      }
    };

    const url = `${sess.host}/${sess.region}/api/dmls/`;
    const headers = this.sessionManager.buildHeaders(sess, dmlsTarget('getStreamingURLsWithFirstChunkV2'));

    try {
      const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(body) });
      const rawText = await res.text();
      let data = {};
      try { data = JSON.parse(rawText); } catch { data = {}; }

      const errorType = coralErrorType(data);
      if (errorType) {
        return {
          status: (errorType.includes('Unauthorized') || errorType.includes('Authentication'))
            ? DmlsSolveStatus.AUTH_REQUIRED
            : DmlsSolveStatus.UNAVAILABLE,
          url: null,
          reason: `DMLS first-chunk verdict: ${errorType} - ${data.message || ''}`.trim(),
          evidence: { requestId: data.requestId || null }
        };
      }

      const entry = Array.isArray(data.contentResponseList) ? data.contentResponseList[0] : null;
      const streamUrl = entry?.urlList?.[0] ?? null;
      const statusCode = entry?.statusCode ?? null;

      if (!streamUrl) {
        return {
          status: DmlsSolveStatus.UNAVAILABLE,
          url: null,
          reason: `No urlList in response (${statusCode || `HTTP ${res.status}`})`,
          detail: (entry?.statusMessage) || rawText.slice(0, 300)
        };
      }

      return {
        status: DmlsSolveStatus.LINKS_SOLVED,
        url: streamUrl,
        cdnType: entry.cdnTypeList?.[0] ?? null,
        substituteAsin: entry.substituteAsin ?? null,
        drmPlaybackResource: data.drmPlaybackResource ?? null,
        clientCertificate: data.clientCertificate ?? null,
        requested: { protocol, format, drmType, bitRate }
      };
    } catch (err) {
      return { status: DmlsSolveStatus.UNAVAILABLE, url: null, reason: `Network failure: ${err.message}` };
    }
  }

  /**
   * Live wire probe: forces the real getDashManifestsV2 call regardless of
   * authentication state and returns the verbatim server verdict.
   */
  async probeWire({ asin, territory = 'US', credential = null } = {}) {
    return this.solveStreamLinks({ asin, territory, credential, forceWire: true });
  }

  _normalizeCredential(credential) {
    if (!credential) {
      return resolveAmazonMusicCredential();
    }
    if (credential instanceof AmazonMusicCredential) return credential;
    if (typeof credential === 'string') {
      return new AmazonMusicCredential({ cookieHeader: credential, source: 'inline-string' });
    }
    return resolveAmazonMusicCredential(credential);
  }
}
