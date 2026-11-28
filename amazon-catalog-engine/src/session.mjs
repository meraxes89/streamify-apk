/**
 * AmazonSessionManager
 *
 * Manages Amazon Music sessions per marketplace.
 * Features:
 *  - Handshake against https://music.amazon.{tld}/config.json
 *  - Single-flight bootstrap deduplication (N parallel requests -> 1 network call)
 *  - Extracts session-id, deviceId, deviceType, CSRF tokens, and cookies
 *  - Proactive 12-hour TTL cache with reactive 401/403 invalidation
 *  - Authenticated (credential) sessions via the production cookie-auth
 *    vector: POST /config.json?clientApplication=hornet&cookieauth=true with
 *    the caller's Amazon Music cookie jar; non-empty customerId == signed in.
 *  - Coral RPC header contract: EVERY api/* call must carry
 *    "Content-Encoding: amz-1.0" - without it the Coral front-end answers
 *    UnknownOperationException (live-verified; this single header is what
 *    kept Tenzing search and DMLS dead from anonymous clients for years).
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function loadProtocol() {
  const protocolPath = path.resolve(__dirname, '../protocol.json');
  return JSON.parse(fs.readFileSync(protocolPath, 'utf8'));
}

/**
 * Production entitlement ladder decoded from the web-player bundle:
 *   UserTier.Unlimited -> [UNLIMITED_HD(KATANA), UNLIMITED(HAWKFIRE),
 *                          AUGMENTED_PRIME(SONIC_RUSH), PRIME(ROBIN), FREE(NIGHTWING)]
 *   UserTier.Prime     -> [AUGMENTED_PRIME, PRIME, FREE]
 *   UserTier.Free      -> [FREE]
 */
const TIER_ENTITLEMENT_LIST = {
  UNLIMITED: ['KATANA', 'HAWKFIRE', 'SONIC_RUSH', 'ROBIN', 'NIGHTWING'],
  PRIME: ['SONIC_RUSH', 'ROBIN', 'NIGHTWING'],
  FREE: ['NIGHTWING']
};

/** Best-effort tier inference from an authenticated config.json payload. */
function inferMusicTier(configData) {
  if (!configData || typeof configData !== 'object') return { tier: null, inferred: false };
  const raw = JSON.stringify([
    configData.benefits, configData.tier, configData.subscription,
    configData.customerTier, configData.musicSubscription
  ]);
  const s = raw.toLowerCase();
  if (s.includes('unlimited_hd') || s.includes('unlimitedhd') || s.includes('katana')) {
    return { tier: 'UNLIMITED', inferred: true };   // Unlimited incl. HD (KATANA)
  }
  if (s.includes('unlimited')) return { tier: 'UNLIMITED', inferred: true };
  if (s.includes('prime') || s.includes('sonic_rush')) return { tier: 'PRIME', inferred: true };
  if (s.includes('nightwing') || s.includes('"free"')) return { tier: 'FREE', inferred: true };
  return { tier: null, inferred: false };
}

export class AmazonSessionManager {
  constructor({ protocol = null } = {}) {
    this.protocol = protocol || loadProtocol();
    /** @type {Map<string, Object>} cacheKey -> session object */
    this.sessions = new Map();
    /** @type {Map<string, Promise>} cacheKey -> inflight bootstrap promise */
    this.bootstraps = new Map();
    this.sessionTtlMs = 12 * 60 * 60 * 1000; // 12 hours
  }

  /**
   * Returns a valid session for the given territory (e.g. 'US', 'IN', 'GB', 'DE', 'JP').
   * If cached session is expired or missing, bootstraps a fresh session.
   *
   * @param {string} territory
   * @param {Object} [options]
   * @param {import('./credentials.mjs').AmazonMusicCredential} [options.credential]
   *        When provided (and authenticated), bootstraps an authenticated session
   *        through the production cookie-auth vector.
   */
  async getSession(territory = 'US', { credential = null } = {}) {
    const terr = territory.toUpperCase();
    const authed = !!(credential && credential.authenticated);
    const cacheKey = authed ? `${terr}:auth` : `${terr}:guest`;

    const cached = this.sessions.get(cacheKey);
    if (cached && Date.now() - cached.createdAt < this.sessionTtlMs) {
      return cached;
    }

    if (this.bootstraps.has(cacheKey)) {
      return this.bootstraps.get(cacheKey);
    }

    const promise = this._bootstrap(terr, { credential, cacheKey })
      .then(session => {
        // Cache on resolution (works with real and injected bootstrap layers)
        this.sessions.set(cacheKey, session);
        return session;
      })
      .finally(() => {
        this.bootstraps.delete(cacheKey);
      });

    this.bootstraps.set(cacheKey, promise);
    return promise;
  }

  /**
   * Invalidate a session (typically called upon HTTP 401 or 403 response).
   * Clears every cached variant for the territory (guest, auth, legacy key).
   */
  invalidateSession(territory = 'US', { credential = null } = {}) {
    const terr = territory.toUpperCase();
    this.sessions.delete(terr);
    this.sessions.delete(`${terr}:guest`);
    this.sessions.delete(`${terr}:auth`);
  }

  async _bootstrap(territory, { credential = null, cacheKey } = {}) {
    const host = this.protocol.hosts[territory] || this.protocol.hosts.US;
    const authed = !!(credential && credential.authenticated);
    // The current production player (web-hornet) bootstraps through the
    // cookie-auth config route; the anonymous engine keeps the skyfire route.
    const url = authed
      ? `${host}${this.protocol.authenticatedConfigPath || '/config.json?clientApplication=hornet&cookieauth=true'}`
      : `${host}${this.protocol.configPath}`;
    const userAgent = this.protocol.userAgent;

    const headers = {
      'User-Agent': userAgent,
      'Content-Type': 'application/json',
      'Origin': host,
      'Referer': `${host}/`,
      'Accept': 'application/json, text/plain, */*'
    };
    if (authed && credential.cookieHeader) {
      headers.Cookie = credential.cookieHeader;
    }

    const res = await fetch(url, { method: 'POST', headers });

    if (!res.ok) {
      throw new Error(`Amazon session bootstrap failed with HTTP ${res.status} on ${url}`);
    }

    const setCookies = res.headers.getSetCookie
      ? res.headers.getSetCookie()
      : [res.headers.get('set-cookie')].filter(Boolean);

    // Merge cookie jars: response (device/session) first, caller's sign-in
    // cookies last so they win on name collisions (at-main/x-main etc.).
    const jar = new Map();
    for (const c of setCookies.map(c => c.split(';')[0].trim())) {
      const eq = c.indexOf('=');
      if (eq > 0) jar.set(c.slice(0, eq), c.slice(eq + 1));
    }
    if (authed && credential.cookieHeader) {
      for (const c of credential.cookieHeader.split(';').map(c => c.trim()).filter(Boolean)) {
        const eq = c.indexOf('=');
        if (eq > 0) jar.set(c.slice(0, eq), c.slice(eq + 1));
      }
    }
    const cookieHeader = [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');

    const data = await res.json();

    const { tier, inferred } = inferMusicTier(data);
    const customerId = data.customerId || '';
    const authenticated = Boolean(customerId);

    // Client-asserted entitlements (server enforces the real account state).
    // Unknown tier on an authenticated session: assert the full Unlimited
    // ladder and let DMLS decide - the response carries the true verdict.
    const entitlementList = authenticated
      ? TIER_ENTITLEMENT_LIST[tier || 'UNLIMITED'] || TIER_ENTITLEMENT_LIST.UNLIMITED
      : [];

    const session = {
      territory,
      host,
      region: this.protocol.regions[territory] || 'NA',
      sessionId: data.sessionId || '111-1111111-1111111',
      deviceId: data.deviceId || '',
      deviceType: data.deviceType || 'A16ZV8BU3SN1N3',
      // Guest sessions return an empty customerId; authenticated Music
      // Unlimited sessions carry a real one. Empty => native delivery is auth-walled.
      customerId,
      authenticated,
      musicTier: authenticated ? (tier || 'UNLIMITED') : null,
      tierInferred: inferred,
      entitlementList,
      ipAddress: data.ipAddress || '',
      marketplaceId: data.marketplaceId || '',
      musicTerritory: data.musicTerritory || territory,
      csrf: data.csrf || null,
      oAuthToken: (authed && credential.oAuthToken) ? credential.oAuthToken : null,
      credentialSource: authed ? (credential.source || 'inline') : null,
      cookies: cookieHeader,
      createdAt: Date.now(),
      rawConfig: data
    };

    // Session caching happens in getSession (mock-friendly); the bootstrap
    // only returns the fresh session object.
    return session;
  }

  /**
   * Helper to construct complete request headers for a given session.
   * Applies the full production Coral contract:
   *   Content-Encoding: amz-1.0   <- REQUIRED (Coral dispatcher key)
   *   Content-Type: application/json
   *   X-Amz-Target (when provided)
   *   Authorization: Bearer <oAuthToken> (authenticated bearer sessions)
   *   CSRF triple + Cookie jar
   */
  buildHeaders(session, targetAction = null, extraHeaders = {}) {
    const common = this.protocol.commonHeaders || {};
    const headers = {
      'User-Agent': this.protocol.userAgent,
      'Content-Encoding': common['Content-Encoding'] || 'amz-1.0',
      'Content-Type': common['Content-Type'] || 'application/json; charset=UTF-8',
      'Accept': common.Accept || 'application/json',
      'x-amzn-device-family': 'WebPlayer',
      'x-amzn-device-type': session.deviceType,
      'x-amzn-device-id': session.deviceId,
      'x-amzn-session-id': session.sessionId,
      'Origin': session.host,
      'Referer': `${session.host}/`,
      ...extraHeaders
    };

    if (session.cookies) {
      headers.Cookie = session.cookies;
    }

    if (session.oAuthToken) {
      headers.Authorization = `Bearer ${session.oAuthToken}`;
    }

    if (session.csrf?.token) {
      headers.csrf = session.csrf.token;
      headers['csrf-token'] = session.csrf.token;
      if (session.csrf.rnd) headers['csrf-rnd'] = session.csrf.rnd;
      if (session.csrf.ts) headers['csrf-ts'] = session.csrf.ts;
    }

    if (targetAction) {
      headers['X-Amz-Target'] = targetAction;
    }

    return headers;
  }
}

export { TIER_ENTITLEMENT_LIST };
