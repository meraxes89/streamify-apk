/**
 * AmazonSessionManager
 * 
 * Manages anonymous guest sessions per marketplace.
 * Features:
 *  - Handshake against https://music.amazon.{tld}/config.json
 *  - Single-flight bootstrap deduplication (N parallel requests -> 1 network call)
 *  - Extracts session-id, deviceId, deviceType, CSRF tokens, and cookies
 *  - Proactive 12-hour TTL cache with reactive 401/403 invalidation
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function loadProtocol() {
  const protocolPath = path.resolve(__dirname, '../protocol.json');
  return JSON.parse(fs.readFileSync(protocolPath, 'utf8'));
}

export class AmazonSessionManager {
  constructor({ protocol = null } = {}) {
    this.protocol = protocol || loadProtocol();
    /** @type {Map<string, Object>} territory -> session object */
    this.sessions = new Map();
    /** @type {Map<string, Promise>} territory -> inflight bootstrap promise */
    this.bootstraps = new Map();
    this.sessionTtlMs = 12 * 60 * 60 * 1000; // 12 hours
  }

  /**
   * Returns a valid session for the given territory (e.g. 'US', 'IN', 'GB', 'DE', 'JP').
   * If cached session is expired or missing, bootstraps a fresh session.
   */
  async getSession(territory = 'US') {
    const terr = territory.toUpperCase();
    const cached = this.sessions.get(terr);

    if (cached && Date.now() - cached.createdAt < this.sessionTtlMs) {
      return cached;
    }

    if (this.bootstraps.has(terr)) {
      return this.bootstraps.get(terr);
    }

    const promise = this._bootstrap(terr)
      .finally(() => {
        this.bootstraps.delete(terr);
      });

    this.bootstraps.set(terr, promise);
    return promise;
  }

  /**
   * Invalidate a session (typically called upon HTTP 401 or 403 response).
   */
  invalidateSession(territory = 'US') {
    const terr = territory.toUpperCase();
    this.sessions.delete(terr);
  }

  async _bootstrap(territory) {
    const host = this.protocol.hosts[territory] || this.protocol.hosts.US;
    const url = `${host}${this.protocol.configPath}`;
    const userAgent = this.protocol.userAgent;

    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'User-Agent': userAgent,
        'Content-Type': 'application/json',
        'Origin': host,
        'Referer': `${host}/`,
        'Accept': 'application/json, text/plain, */*'
      }
    });

    if (!res.ok) {
      throw new Error(`Amazon session bootstrap failed with HTTP ${res.status} on ${url}`);
    }

    const setCookies = res.headers.getSetCookie
      ? res.headers.getSetCookie()
      : [res.headers.get('set-cookie')].filter(Boolean);

    const cookieHeader = setCookies.map(c => c.split(';')[0].trim()).join('; ');
    const data = await res.json();

    const session = {
      territory,
      host,
      region: this.protocol.regions[territory] || 'NA',
      sessionId: data.sessionId || '111-1111111-1111111',
      deviceId: data.deviceId || '',
      deviceType: data.deviceType || 'A16ZV8BU3SN1N3',
      marketplaceId: data.marketplaceId || '',
      musicTerritory: data.musicTerritory || territory,
      csrf: data.csrf || null,
      cookies: cookieHeader,
      createdAt: Date.now(),
      rawConfig: data
    };

    this.sessions.set(territory, session);
    return session;
  }

  /**
   * Helper to construct complete request headers for a given session.
   */
  buildHeaders(session, targetAction = null, extraHeaders = {}) {
    const headers = {
      'User-Agent': this.protocol.userAgent,
      'Content-Type': this.protocol.commonHeaders['Content-Type'],
      'Accept': this.protocol.commonHeaders.Accept,
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
