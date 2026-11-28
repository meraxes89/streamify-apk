/**
 * Amazon Music Credential Layer
 *
 * DMLS full-song/manifest delivery (getDashManifestsV2 FLAC/Opus path) is
 * entitlement-walled: anonymous guest sessions carry customerId === "" and the
 * service answers with the live-verified wall
 *
 *   UnauthorizedException:
 *   "Request authentication failure : CUSTOMER_INFO_NOT_PROVIDED (AuthenticationException)"
 *
 * The production web player (Vinyl/DMLS client decoded from
 * d5fx445wy2wpk.cloudfront.net web-hornet 1.196.81) authorizes DMLS through
 * exactly two vectors, both supported here:
 *
 *   1. Cookie credentials  (authCookie mode - the player's DEFAULT):
 *        POST /config.json?clientApplication=hornet&cookieauth=true sends the
 *        browser cookie jar; the response carries the authenticated customerId.
 *   2. OAuth bearer token   (oAuthToken mode):
 *        Authorization: Bearer <token> on every Coral DMLS call.
 *
 * This module never guesses entitlements: an authenticated session is one whose
 * config.json bootstrap returns a non-empty customerId. Everything else is a
 * guest and is labeled as such by the solver.
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const DEFAULT_COOKIE_FILE_CANDIDATES = [
  path.join(os.homedir(), '.streamify', 'amazon-music.cookies'),
  path.join(os.homedir(), '.streamify', 'amazon-music-cookies.txt')
];

const COOKIE_NAME_ALLOWLIST_HINT = [
  // Auth-signaling cookies carried by a signed-in music.amazon.com browser jar.
  'at-main', 'x-main', 'sess-main', 'ubid-main', 'session-id', 'session-token',
  'am-token', 'session-id-time', 'i18n-prefs', 'lc-main', 'av-profile',
  'music-cloudplayer-device', 'csm-hit'
];

/**
 * Normalizes arbitrary cookie text into a single `name=value; ...` header.
 * Accepted shapes:
 *   - raw Cookie header   "at-main=...; x-main=..."
 *   - newline pairs       "at-main=...\nx-main=..."
 *   - Netscape cookie-jar ".music.amazon.com\tTRUE\t/\tTRUE\t0\tat-main\t..."
 *   - curl -b style files with either of the above (comments skipped)
 *
 * @param {string} text
 * @returns {string|null} cookie header or null when nothing usable parsed
 */
export function parseCookieText(text) {
  if (!text || typeof text !== 'string') return null;

  /** @type {Map<string,string>} */
  const jar = new Map();
  const addPair = (name, value) => {
    name = name.trim();
    value = value.trim();
    if (!name || !value || value === '""') return;
    jar.set(name, value);
  };

  for (const rawLine of text.split(/\r?\n/)) {
    let line = rawLine.trim();
    if (!line) continue;

    // Netscape cookie-jar rows (optionally under an HttpOnly marker) parse
    // through the tab layout; plain comments without '=' are skipped.
    const isJarRow = line.includes('\t') && (line.match(/\t/g) || []).length >= 6;

    if (line.startsWith('#HttpOnly_')) {
      const tabbed = line.slice('#HttpOnly_'.length).split('\t');
      if (tabbed.length >= 7) { addPair(tabbed[tabbed.length - 2], tabbed[tabbed.length - 1]); continue; }
      line = line.slice('#HttpOnly_'.length);
    } else if (line.startsWith('#') && !isJarRow && !line.includes('=')) {
      continue; // plain comment
    }

    if (line.includes('\t')) {
      const tabbed = line.split('\t');
      if (tabbed.length >= 7) {
        addPair(tabbed[tabbed.length - 2], tabbed[tabbed.length - 1]);
        continue;
      }
      // "name<TAB>value" pairs
      if (tabbed.length === 2) { addPair(tabbed[0], tabbed[1]); continue; }
    }

    // Semicolon/newline separated name=value list on one or more lines
    for (const pair of line.split(';')) {
      const eq = pair.indexOf('=');
      if (eq <= 0) continue;
      addPair(pair.slice(0, eq), pair.slice(eq + 1));
    }
  }

  if (jar.size === 0) return null;
  return [...jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
}

/**
 * Heuristic: does this cookie jar carry Amazon sign-in material?
 * Guest jars (session-id + am-token only) never authenticate DMLS.
 */
export function jarLooksAuthenticated(cookieHeader) {
  if (!cookieHeader) return false;
  return /(at-main|x-main|sess-main|session-token)=/.test(cookieHeader);
}

/**
 * Immutable credential value object.
 */
export class AmazonMusicCredential {
  /**
   * @param {Object} options
   * @param {string} [options.cookieHeader]  - full `name=value; ...` header
   * @param {string} [options.oAuthToken]   - DMLS bearer token
   * @param {string} [options.source]       - provenance label ('env','file','inline',...)
   * @param {string} [options.label]        - human description
   */
  constructor({ cookieHeader = null, oAuthToken = null, source = 'inline', label = null } = {}) {
    const cookie = typeof cookieHeader === 'string'
      ? (cookieHeader.includes('=') ? cookieHeader : null)
      : null;
    this.cookieHeader = cookie;
    this.oAuthToken = typeof oAuthToken === 'string' && oAuthToken.trim() ? oAuthToken.trim() : null;
    this.source = source;
    this.label = label || (cookie ? 'cookie credential' : (this.oAuthToken ? 'bearer credential' : null));
  }

  get authenticated() {
    return Boolean(this.cookieHeader || this.oAuthToken);
  }

  get mode() {
    if (this.cookieHeader && this.oAuthToken) return 'cookie+bearer';
    if (this.cookieHeader) return 'cookie';
    if (this.oAuthToken) return 'bearer';
    return 'anonymous';
  }

  toJSON() {
    return {
      authenticated: this.authenticated,
      mode: this.mode,
      source: this.source,
      label: this.label,
      cookieNames: this.cookieHeader
        ? this.cookieHeader.split(';').map(c => c.split('=')[0].trim()).filter(Boolean)
        : [],
      hasBearer: !!this.oAuthToken
    };
  }
}

/**
 * Resolves a credential from (in priority order):
 *   1. inline cookie/token arguments
 *   2. AMAZON_MUSIC_COOKIES / AMAZON_MUSIC_OAUTH_TOKEN env
 *   3. AMAZON_MUSIC_COOKIES_FILE env, or the default ~/.streamify/ cookie files
 *
 * Returns an anonymous credential (authenticated === false) when nothing is
 * configured - never null - so callers always have a typed object.
 *
 * @param {Object} [options]
 * @param {string} [options.cookieHeader]
 * @param {string} [options.oAuthToken]
 * @param {string} [options.cookiesFile] - explicit file path
 * @param {Object} [options.env] - environment map (default process.env)
 * @returns {AmazonMusicCredential}
 */
export function resolveAmazonMusicCredential({
  cookieHeader = null,
  oAuthToken = null,
  cookiesFile = null,
  env = process.env
} = {}) {
  if (cookieHeader || oAuthToken) {
    return new AmazonMusicCredential({ cookieHeader, oAuthToken, source: 'inline' });
  }

  const envCookies = env.AMAZON_MUSIC_COOKIES;
  const envToken = env.AMAZON_MUSIC_OAUTH_TOKEN;
  if (envCookies || envToken) {
    return new AmazonMusicCredential({ cookieHeader: envCookies, oAuthToken: envToken, source: 'env' });
  }

  const candidates = [];
  if (env.AMAZON_MUSIC_COOKIES_FILE) candidates.push(env.AMAZON_MUSIC_COOKIES_FILE);
  if (cookiesFile) candidates.push(cookiesFile);
  candidates.push(...DEFAULT_COOKIE_FILE_CANDIDATES);

  for (const file of candidates) {
    try {
      if (!fs.existsSync(file)) continue;
      const parsed = parseCookieText(fs.readFileSync(file, 'utf8'));
      if (parsed) {
        return new AmazonMusicCredential({ cookieHeader: parsed, source: `file:${file}` });
      }
    } catch {
      // Unreadable file: keep scanning candidates
    }
  }

  return new AmazonMusicCredential({});
}

export { COOKIE_NAME_ALLOWLIST_HINT };
