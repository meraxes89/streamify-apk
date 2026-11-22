/**
 * AmazonDmlsSolver - THE REAL Amazon Music stream-link solver
 *
 * Speaks the production DMLS wire protocol. Originally decoded from the
 * web-hornet Vinyl player bundle; 2026-11 re-decode of the live
 * vinylChunk-e54d4980a921fb73eda3fe610b852eb1.js dmls client module
 * (functions H/w/U/V/j/de/he/Te/Ae) locked in the exact shapes below and
 * added a fifth operation + the anonymous samples vector:
 *
 *   URL      {host}/{region}/api/dmls/                      (H())
 *   Target   com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal.<op>  (U)
 *   Headers  Content-Encoding: amz-1.0   <- Coral dispatcher key
 *            Content-Type: application/json
 *            X-Amz-Target: <target>                        (V())
 *            Authorization: Bearer <oAuthToken>            (plain Bearer, every
 *              token shape incl. Atna| — the AmznMusic base64 form is used by
 *              the vinyl log endpoint only, never DMLS)
 *            csrf-token / csrf-rnd / csrf-ts
 *            Cookie + credentials:include in cookie mode
 *   musicAgent  "Vinyl/2.0 {appId}/{appVersion} ({clientToken})"   (w())
 *              appId = GREENHORNET_WEB, clientToken = random hex groups
 *
 *   Operations (U):
 *     getDashManifestsV2            FLAC/Opus manifests (de) - two profiles:
 *          enableFlacOpus=true (default):
 *            musicDashVersionList: [SIREN_KATANA | SIREN_KATANA_NO_CLEAR_LEAD],
 *            contentProtectionList: [TRACK_PSSH],
 *            tryAsinSubstitution: entitlementList has HAWKFIRE|KATANA
 *          enableFlacOpus=false (tier 'lossy' / 'v2'):
 *            musicDashVersionList: ['V2'], bitrateTypeList: [HIGH?|MEDIUM|LOW],
 *            NO contentProtectionList, NO tryAsinSubstitution
 *     getStreamingURLsWithFirstChunkV2 (he) - direct URL solve:
 *            { contentIdList, customerId, customerInfo:{marketplaceId,
 *              territoryId, entitlementList}, protocol, format, drmType,
 *              deviceToken, appMetadata, clientMetadata:{clientId: 'WebCP',
 *              clientRequestId} }
 *     getStreamingURLsForSamples (Ae) - GUEST-CAPABLE, live-verified:
 *            { tracksInfoList:[{asin, contentFormat:'mp3', bitrate:64}],
 *              deviceInfo:{deviceType}, marketplaceId, territoryId,
 *              expiration: <unix seconds, now+3600>,
 *              clientInfo:{clientId:'WebCP'}, protocolList:['https'] }
 *            response: { urls: [[url, ...], ...] }  -> urls[0][0]
 *            Anonymous guests receive HTTP 200 + signed CloudFront MP3 URLs
 *            (verified live 2026-11: audio/mpeg bytes, ID3 header).
 *     getStreamingURLs (Te) - legacy COID path: identifierType 'COID',
 *            bitRate HIGH; guest verdict = AuthenticationException
 *            UNSUPPORTED_DEVICE_TYPE (device-type wall; documented, unused).
 *     getLicenseForPlaybackV2 (W) - SIREN DRM license (documented only; the
 *            engine never performs the DRM round-trip).
 *
 *   Manifest response envelope:
 *     { contentResponseList:[{ manifest:<inline MPD XML>, cdnType,
 *        substituteAsin, contentResponseStatusCode, contentResponseStatusMessage }] }
 *     status universe (MpegDashContentResponseStatusCode): SUCCESS,
 *       CONTENT_NOT_FOUND, CONTENT_NOT_ELIGIBLE, CONTENT_LOCKED,
 *       CUSTOMER_NOT_ELIGIBLE, CUSTOMER_NOT_VALID, CLIENT_NOT_ELIGIBLE,
 *       CLIENT_NOT_VALID, DEVICE_NOT_AUTHORIZED, DEVICE_NOT_ELIGIBLE, ...
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

/** Production web player registered client id (authStore.clientId === 'WebCP'). */
const DMLS_CLIENT_ID = 'WebCP';
/** Production app id (Application.GREENHORNET_WEB). */
const DMLS_APP_ID = 'GREENHORNET_WEB';
const DMLS_APP_VERSION = '1.196.81';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

/** Entitlements that allow the HIGH bitrate on the V2 lossy manifest path. */
const V2_HIGH_BITRATE_ENTITLEMENTS = new Set(['ROBIN', 'SONIC_RUSH', 'HAWKFIRE', 'KATANA']);
/** Entitlements that enable tryAsinSubstitution on the FLAC/Opus path. */
const SUBSTITUTION_ENTITLEMENTS = new Set(['HAWKFIRE', 'KATANA']);

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

/** Random 4-hex group, exactly the production w() token generator shape. */
function hexGroup() {
  return Math.floor(65536 * (1 + Math.random())).toString(16).substring(1);
}

/**
 * Production musicAgent builder (w()): "Vinyl/2.0 {appId}/{appVersion} ({token})".
 * The client token is generated once per solver instance and reused.
 */
function buildMusicAgent({ appId = DMLS_APP_ID, appVersion = DMLS_APP_VERSION, clientToken = null } = {}) {
  const token = clientToken ||
    [hexGroup(), hexGroup(), (appId || '').substring(0, 4), hexGroup(), hexGroup() + hexGroup()].join('-');
  return `Vinyl/2.0 ${appId || ''}/${appVersion || ''} (${token})`;
}

/** Production short-ish client request id (createShortUid shape). */
function shortUid() {
  return `${Date.now().toString(36)}-${hexGroup()}${hexGroup()}`;
}

/** tier -> manifest request profile ('flac-opus' | 'v2-lossy'). */
function dashProfileForTier(tier) {
  return (tier === 'lossy' || tier === 'v2' || tier === 'v2-lossy') ? 'v2-lossy' : 'flac-opus';
}

export class AmazonDmlsSolver {
  constructor({
    sessionManager = new AmazonSessionManager(),
    musicAgent = null,
    clientId = DMLS_CLIENT_ID,
    appId = DMLS_APP_ID,
    appVersion = DMLS_APP_VERSION
  } = {}) {
    this.sessionManager = sessionManager;
    this.clientId = clientId;
    this.appId = appId;
    this.appVersion = appVersion;
    // Per-instance production client token (w()) reused across calls.
    this.clientToken = [hexGroup(), hexGroup(), appId.substring(0, 4), hexGroup(), hexGroup() + hexGroup()].join('-');
    this.musicAgent = musicAgent || buildMusicAgent({ appId, appVersion, clientToken: this.clientToken });
  }

  /**
   * Production appMetadata block (carried by first-chunk/legacy calls).
   */
  _appMetadata() {
    return { https: true, appId: this.appId, appVersion: this.appVersion, userAgent: USER_AGENT };
  }

  /**
   * Production clientMetadata block: { clientId: 'WebCP', clientRequestId }.
   */
  _clientMetadata(requestId = null) {
    return { clientId: this.clientId, clientRequestId: requestId || shortUid() };
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
   * @param {string} [options.tier='ultra'] - 'ultra' | 'hd' | 'best' (FLAC/Opus
   *        SIREN_KATANA manifest path) | 'lossy' | 'v2' (V2 bitrateTypeList path,
   *        no content protection requested)
   * @param {boolean} [options.includeFirstChunk=false] - also solve
   *        getStreamingURLsWithFirstChunkV2 direct URL
   * @param {boolean} [options.noClearLead=false] - request the
   *        SIREN_KATANA_NO_CLEAR_LEAD variant (Firefox profile)
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
    noClearLead = false,
    forceWire = false
  } = {}) {
    if (!asin) throw new Error('ASIN is required to solve DMLS stream links');
    const normalizedAsin = String(asin).replace('amzn:track:', '');
    const terr = territory.toUpperCase();
    const profile = dashProfileForTier(tier);

    const cred = this._normalizeCredential(credential);

    const session = await this.sessionManager.getSession(terr, { credential: cred });

    const baseCard = {
      solver: 'AmazonDmlsSolver',
      service: DMLS_SERVICE,
      operation: 'getDashManifestsV2',
      asin: normalizedAsin,
      territory: terr,
      requestedTier: tier,
      dashProfile: profile,
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
          'AMAZON_MUSIC_COOKIES env / ~/.streamify/amazon-music.cookies / credential option. ' +
          'Anonymous REAL audio is still available via solveSampleUrl (30s MP3 previews).',
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
    // Authenticated: issue the REAL manifest request (production shape de()).
    // ------------------------------------------------------------------
    const entitled = new Set(session.entitlementList || []);
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
      musicDashVersionList: profile === 'v2-lossy'
        ? ['V2']
        : [noClearLead ? 'SIREN_KATANA_NO_CLEAR_LEAD' : 'SIREN_KATANA']
    };

    if (profile === 'v2-lossy') {
      // Production enableFlacOpus=false path: bitrate ladder, no protection list.
      requestBody.bitrateTypeList = [
        ...(entitled.size > 0 && [...V2_HIGH_BITRATE_ENTITLEMENTS].some(e => entitled.has(e)) ? ['HIGH'] : []),
        'MEDIUM',
        'LOW'
      ];
    } else {
      requestBody.contentProtectionList = ['TRACK_PSSH'];
      requestBody.tryAsinSubstitution = [...SUBSTITUTION_ENTITLEMENTS].some(e => entitled.has(e));
    }

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
            envelope: profile === 'v2-lossy'
              ? 'CENC (V2 manifest carried ContentProtection)'
              : 'SIREN_KATANA CENC (TRACK_PSSH)',
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
   * (production shape he(), 2026-11 re-decode). Returns the URL + DRM resource.
   *
   * Body: { contentIdList, customerId, customerInfo:{marketplaceId, territoryId,
   * entitlementList}, protocol, format, drmType, deviceToken, appMetadata,
   * clientMetadata:{clientId:'WebCP', clientRequestId} } — no
   * musicRequestIdentityContext, no bitRate (production truth).
   */
  async solveFirstChunkUrl({
    asin,
    territory = 'US',
    credential = null,
    session = null,
    protocol = 'DASH',
    format = 'ENCRYPTED_AAC',
    drmType = 'WIDEVINE'
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
        entitlementList: sess.entitlementList || []
      },
      protocol,
      format,
      drmType,
      deviceToken: {
        deviceTypeId: sess.deviceType,
        deviceId: sess.deviceId
      },
      appMetadata: this._appMetadata(),
      clientMetadata: this._clientMetadata()
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
        requested: { protocol, format, drmType }
      };
    } catch (err) {
      return { status: DmlsSolveStatus.UNAVAILABLE, url: null, reason: `Network failure: ${err.message}` };
    }
  }

  /**
   * Solves the REAL sample/preview MP3 URL for a track ASIN - the one DMLS
   * surface that works for ANONYMOUS GUEST sessions.
   *
   * Production shape Ae() (2026-11 re-decode + live verification):
   *   POST {host}/{region}/api/dmls/  X-Amz-Target ...getStreamingURLsForSamples
   *   { tracksInfoList: [{ asin, contentFormat: 'mp3', bitrate: 64 }],
   *     deviceInfo: { deviceType }, marketplaceId, territoryId,
   *     expiration: <unix seconds; production uses now + 3600>,
   *     clientInfo: { clientId: 'WebCP' }, protocolList: ['https'] }
   *   -> { urls: [[url, ...], ...] }  (urls[0][0] is the track's sample URL)
   *
   * The registered client id 'WebCP' + a real expiration timestamp are BOTH
   * required: deviceType-as-clientId answers 400 "unknown client" and
   * expiration=0 is not the production shape (both live-verified).
   *
   * @param {Object} options
   * @param {string} options.asin - track ASIN
   * @param {string} [options.territory='US']
   * @param {number} [options.expirationSec=3600] - signed URL lifetime
   * @returns {Promise<Object>} { status, url, urls, asin, territory, service,
   *                              operation, expiresAt, detail? }
   */
  async solveSampleUrl({ asin, territory = 'US', expirationSec = 3600 } = {}) {
    if (!asin) throw new Error('ASIN is required to solve sample URLs');
    const normalizedAsin = String(asin).replace('amzn:track:', '');
    const terr = territory.toUpperCase();

    const session = await this.sessionManager.getSession(terr);

    const expiresAt = Math.floor(Date.now() / 1000) + expirationSec;
    const requestBody = {
      tracksInfoList: [{ asin: normalizedAsin, contentFormat: 'mp3', bitrate: 64 }],
      deviceInfo: { deviceType: session.deviceType },
      marketplaceId: session.marketplaceId,
      territoryId: session.musicTerritory,
      expiration: expiresAt,
      clientInfo: { clientId: this.clientId },
      protocolList: ['https']
    };

    const url = `${session.host}/${session.region}/api/dmls/`;
    const headers = this.sessionManager.buildHeaders(session, dmlsTarget('getStreamingURLsForSamples'));

    try {
      const res = await fetch(url, { method: 'POST', headers, body: JSON.stringify(requestBody) });
      const rawText = await res.text();
      let data = {};
      try { data = JSON.parse(rawText); } catch { data = {}; }

      const errorType = coralErrorType(data);
      if (errorType) {
        return {
          status: DmlsSolveStatus.UNAVAILABLE,
          url: null,
          urls: [],
          asin: normalizedAsin,
          territory: terr,
          service: DMLS_SERVICE,
          operation: 'getStreamingURLsForSamples',
          expiresAt,
          reason: `DMLS samples verdict: ${errorType} - ${data.message || ''}`.trim(),
          detail: rawText.slice(0, 300)
        };
      }

      // Production envelope: { urls: [[url, ...], ...] } - one array per track.
      const urls = Array.isArray(data.urls)
        ? data.urls.map(u => (Array.isArray(u) ? u.filter(Boolean) : (u ? [u] : [])).flat())
        : [];

      if (!urls[0]?.length) {
        return {
          status: DmlsSolveStatus.CONTENT_NOT_FOUND,
          url: null,
          urls,
          asin: normalizedAsin,
          territory: terr,
          service: DMLS_SERVICE,
          operation: 'getStreamingURLsForSamples',
          expiresAt,
          reason: 'No sample URL for this ASIN (album-only ASIN or preview absent in catalog)',
        };
      }

      return {
        status: DmlsSolveStatus.LINKS_SOLVED,
        url: urls[0][0],
        urls,
        asin: normalizedAsin,
        territory: terr,
        service: DMLS_SERVICE,
        operation: 'getStreamingURLsForSamples',
        expiresAt,
        evidence: { requestId: data.requestId || null, httpStatus: res.status }
      };
    } catch (err) {
      return {
        status: DmlsSolveStatus.UNAVAILABLE,
        url: null,
        urls: [],
        asin: normalizedAsin,
        territory: terr,
        service: DMLS_SERVICE,
        operation: 'getStreamingURLsForSamples',
        expiresAt,
        reason: `Network failure: ${err.message}`
      };
    }
  }

  /**
   * Verifies that a solved CDN URL is actually reachable and returns honest
   * byte-level evidence (Range probe; never decrypts anything).
   *
   * @param {string} url - solved init/segment/first-chunk/sample URL
   * @param {Object} [options]
   * @param {number} [options.timeoutMs=10000]
   * @param {number} [options.probeBytes=1024]
   * @returns {Promise<Object>} { reachable, httpStatus, contentType,
   *                              contentRange, totalBytes, firstBytesHex }
   */
  async verifyLinkReachability(url, { timeoutMs = 10_000, probeBytes = 1024 } = {}) {
    if (!url || typeof url !== 'string') {
      return { reachable: false, httpStatus: null, reason: 'URL is required' };
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetch(url, {
        headers: { 'User-Agent': USER_AGENT, 'Range': `bytes=0-${probeBytes - 1}` },
        signal: controller.signal
      });
      const contentType = res.headers.get('content-type');
      const contentRange = res.headers.get('content-range');
      const contentLength = res.headers.get('content-length');
      let firstBytesHex = null;
      if (res.ok || res.status === 206) {
        const buf = Buffer.from(await res.arrayBuffer());
        firstBytesHex = buf.subarray(0, 16).toString('hex');
      }
      const totalBytes = contentRange
        ? Number(/\/(\d+)$/.exec(contentRange)?.[1] || NaN)
        : (contentLength ? Number(contentLength) : NaN);
      return {
        reachable: res.ok || res.status === 206,
        httpStatus: res.status,
        contentType,
        contentRange,
        totalBytes: Number.isFinite(totalBytes) ? totalBytes : null,
        firstBytesHex
      };
    } catch (err) {
      return { reachable: false, httpStatus: null, reason: err.message };
    } finally {
      clearTimeout(timer);
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

export { DMLS_CLIENT_ID, DMLS_APP_ID, buildMusicAgent };
