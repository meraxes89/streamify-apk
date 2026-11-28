/**
 * AmazonDmlsSolver / dash-mpd / credentials test suite.
 *
 * Verifies the REAL stream-link solving pipeline offline:
 *   - parseDashManifest on the Amazon MPD dialect (amz-music supplemental
 *     properties, SIREN_KATANA ContentProtection pssh, SegmentTemplate)
 *   - resolveSegmentUrls template substitution (the actual CDN links)
 *   - quality ladder (UHD96 24-bit FLAC selection)
 *   - solver envelope mapping (SUCCESS manifest / Coral auth wall /
 *     entitlement verdicts / guest short-circuit)
 *   - credential resolution (env/file/inline cookie parsing)
 *   - authenticated session bootstrap (cookie merge + entitlement ladder)
 */

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

import {
  parseDashManifest,
  resolveSegmentUrls,
  selectRepresentationForTier,
  qualityTierLabel,
  parseXml,
  parseDuration
} from '../src/dash-mpd.mjs';
import { AmazonDmlsSolver, DmlsSolveStatus } from '../src/dmls-solver.mjs';
import { AmazonSessionManager } from '../src/session.mjs';
import {
  parseCookieText,
  jarLooksAuthenticated,
  resolveAmazonMusicCredential,
  AmazonMusicCredential
} from '../src/credentials.mjs';

/* ------------------------------------------------------------------ */
/* Fixture: Amazon-dialect MPD (SIREN_KATANA Ultra HD FLAC track)     */
/* ------------------------------------------------------------------ */

const FIXTURE_MPD = `<?xml version="1.0" encoding="UTF-8"?>
<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013"
     profiles="urn:mpeg:dash:profile:isoff-live:2011" type="static"
     mediaPresentationDuration="PT3M20.24S" minBufferTime="PT2S">
  <BaseURL>https://d25tk16o50n0lf.cloudfront.net/track/B084WKYYC3/</BaseURL>
  <Period id="0" duration="PT3M20.24S">
    <AdaptationSet id="0" contentType="audio" mimeType="audio/mp4" segmentAlignment="true" startWithSAP="1">
      <SupplementalProperty schemeIdUri="amz-music:trackType" value="UHD"/>
      <SegmentTemplate timescale="48000" duration="192000" startNumber="1" presentationTimeOffset="0"
                       initialization="init/$RepresentationID$/init.mp4"
                       media="seg/$RepresentationID$/$Number$.m4s"/>
      <Representation id="flac-24-96" codecs="flac" mimeType="audio/mp4" audioSamplingRate="96000" bandwidth="4608000">
        <SupplementalProperty schemeIdUri="amz-music:bitDepth" value="24"/>
        <ContentProtection schemeIdUri="urn:mpeg:cenc:2013" value="Widevine" cenc:default_KID="be576e5a-1cd8-4c71-b0f2-2d4e64d0d000">
          <cenc:pssh>AAAAOHBzc2gAAAAA7e+LqXnWSs6jgC9nam7YAAAAA7e+LqXnWSg==</cenc:pssh>
        </ContentProtection>
      </Representation>
      <Representation id="flac-24-48" codecs="flac" mimeType="audio/mp4" audioSamplingRate="48000" bandwidth="2304000">
        <SupplementalProperty schemeIdUri="amz-music:bitDepth" value="24"/>
        <ContentProtection schemeIdUri="urn:mpeg:cenc:2013" value="Widevine" cenc:default_KID="be576e5a-1cd8-4c71-b0f2-2d4e64d0d001">
          <cenc:pssh>AAAAOHBzc2gAAAAA7e+LqXnWSs6jgC9nam7YAAAAA7e+LqXnWSg==</cenc:pssh>
        </ContentProtection>
      </Representation>
      <Representation id="opus-96" codecs="opus" mimeType="audio/mp4" audioSamplingRate="48000" bandwidth="96000">
        <SupplementalProperty schemeIdUri="amz-music:bitDepth" value="16"/>
      </Representation>
    </AdaptationSet>
  </Period>
</MPD>`;

const MANIFEST_ENVELOPE = JSON.stringify({
  contentResponseList: [{
    manifest: FIXTURE_MPD,
    cdnType: 'CLOUDFRONT',
    substituteAsin: null,
    contentResponseStatusCode: 'SUCCESS',
    contentResponseStatusMessage: null
  }]
});

/* ------------------------------------------------------------------ */
/* XML + MPD parsing                                                   */
/* ------------------------------------------------------------------ */

describe('dash-mpd XML parser', () => {
  test('parses elements, attributes, namespaces, and CDATA', () => {
    const doc = parseXml('<?xml version="1.0"?><root a="1" ns:b="2"><child>te&lt;xt</child><c><![CDATA[raw<>&]]></c><!-- comment --></root>');
    assert.equal(doc.children[0].name, 'root');
    const root = doc.children[0];
    assert.equal(root.attrs.a, '1');
    assert.equal(root.attrs.b, '2', 'namespaced attribute resolves to local name');
    assert.equal(root.children[0].text, 'te<xt');
    assert.equal(root.children[1].text, 'raw<>&', 'CDATA preserved');
  });

  test('parseDuration handles ISO8601 and plain seconds', () => {
    assert.equal(parseDuration('PT3M20.24S'), 200.24);
    assert.equal(parseDuration('200.24'), 200.24);
    assert.equal(parseDuration('PT1H2M3S'), 3723);
  });
});

describe('dash-mpd quality ladder (production enum)', () => {
  test('trackType + samplingRate -> tier labels', () => {
    assert.equal(qualityTierLabel('UHD', 96000), 'UHD96');
    assert.equal(qualityTierLabel('UHD', 192000), 'UHD192');
    assert.equal(qualityTierLabel('UHD', 48000), 'UHD48');
    assert.equal(qualityTierLabel('UHD', 44100), 'UHD44');
    assert.equal(qualityTierLabel('HD', 44100), 'HD44');
    assert.equal(qualityTierLabel('SD', null, 300000), 'High');
    assert.equal(qualityTierLabel(null, null, null), 'Unknown');
  });
});

describe('parseDashManifest (Amazon dialect)', () => {
  const manifest = parseDashManifest(FIXTURE_MPD);

  test('extracts MPD metadata and BaseURL', () => {
    assert.equal(manifest.mpd.type, 'static');
    assert.equal(manifest.mpd.durationSec, 200.24);
    assert.equal(manifest.baseUrls[0], 'https://d25tk16o50n0lf.cloudfront.net/track/B084WKYYC3/');
  });

  test('extracts all representations with quality cards', () => {
    assert.equal(manifest.representations.length, 3);
    const flac96 = manifest.representations.find(r => r.id === 'flac-24-96');
    assert.equal(flac96.codecFamily, 'flac');
    assert.equal(flac96.audioSamplingRate, 96000);
    assert.equal(flac96.bitDepth, 24);
    assert.equal(flac96.trackType, 'UHD');
    assert.equal(flac96.tierLabel, 'UHD96');
    assert.equal(flac96.lossless, true);
    assert.equal(flac96.drmProtected, true);
    assert.ok(flac96.specLabel.includes('24-bit 96 kHz FLAC'), flac96.specLabel);
    assert.ok(flac96.contentProtections[0].psshList.length === 1);
    assert.equal(flac96.contentProtections[0].default_KID, 'be576e5a-1cd8-4c71-b0f2-2d4e64d0d000');
  });

  test('clear representations are not flagged DRM-protected', () => {
    const opus = manifest.representations.find(r => r.id === 'opus-96');
    assert.equal(opus.codecFamily, 'opus');
    assert.equal(opus.drmProtected, false);
    assert.equal(opus.lossless, false);
  });

  test('segment URL resolution substitutes the template against BaseURL', () => {
    const flac96 = manifest.representations.find(r => r.id === 'flac-24-96');
    const links = resolveSegmentUrls(manifest, flac96);
    assert.equal(links.initUrl,
      'https://d25tk16o50n0lf.cloudfront.net/track/B084WKYYC3/init/flac-24-96/init.mp4');
    assert.equal(links.firstSegmentUrls[0],
      'https://d25tk16o50n0lf.cloudfront.net/track/B084WKYYC3/seg/flac-24-96/1.m4s');
    assert.equal(links.segmentCount, 51, 'ceil(200.24s / 4s segments)');
    assert.equal(links.segmentDurationSec, 4);
  });

  test('tier selection prefers 24-bit UHD FLAC for ultra', () => {
    const sel = selectRepresentationForTier(manifest.representations, 'ultra');
    assert.equal(sel.id, 'flac-24-96');
    const hd = selectRepresentationForTier(manifest.representations, 'lossy');
    assert.equal(hd.id, 'opus-96');
  });
});

/* ------------------------------------------------------------------ */
/* Credentials                                                          */
/* ------------------------------------------------------------------ */

describe('credentials layer', () => {
  test('parses raw cookie headers', () => {
    const jar = parseCookieText('at-main=abc; x-main=def; ubid-main=xyz');
    assert.equal(jar, 'at-main=abc; x-main=def; ubid-main=xyz');
  });

  test('parses newline pairs and Netscape jars', () => {
    const nl = parseCookieText('at-main=abc\nx-main=def');
    assert.ok(nl.includes('at-main=abc') && nl.includes('x-main=def'));
    const netscape = parseCookieText('# HttpOnly_.music.amazon.com\tTRUE\t/\tTRUE\t0\tat-main\ttok');
    assert.equal(netscape, 'at-main=tok');
  });

  test('authentication heuristics', () => {
    assert.equal(jarLooksAuthenticated('at-main=abc; session-id=1'), true);
    assert.equal(jarLooksAuthenticated('session-id=1; am-token=x'), false);
    assert.equal(jarLooksAuthenticated(null), false);
  });

  test('resolves anonymous credential by default (isolated env)', () => {
    const cred = resolveAmazonMusicCredential({ env: {} });
    assert.equal(cred.authenticated, false);
    assert.equal(cred.mode, 'anonymous');
  });

  test('resolves cookies and bearer tokens from env', () => {
    const cred = resolveAmazonMusicCredential({
      env: { AMAZON_MUSIC_COOKIES: 'at-main=abc; x-main=def' }
    });
    assert.equal(cred.authenticated, true);
    assert.equal(cred.mode, 'cookie');
    const token = resolveAmazonMusicCredential({
      env: { AMAZON_MUSIC_OAUTH_TOKEN: 'Atza|bearer' }
    });
    assert.equal(token.mode, 'bearer');
    assert.equal(token.oAuthToken, 'Atza|bearer');
  });

  test('resolves cookies from file (cookie header and jar formats)', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'amzcred-'));
    const fileA = path.join(dir, 'a.cookies');
    fs.writeFileSync(fileA, 'at-main=abc; x-main=def');
    const credA = resolveAmazonMusicCredential({ cookiesFile: fileA, env: {} });
    assert.equal(credA.authenticated, true);
    assert.equal(credA.source, `file:${fileA}`);

    const fileB = path.join(dir, 'b.cookies');
    fs.writeFileSync(fileB, '# Netscape EPSF\r\n.music.amazon.com\tTRUE\t/\tTRUE\t0\tat-main\ttok\r\n.music.amazon.com\tTRUE\t/\tTRUE\t0\tx-main\txyz');
    const credB = resolveAmazonMusicCredential({ cookiesFile: fileB, env: {} });
    assert.ok(credB.cookieHeader.includes('at-main=tok'));
    assert.ok(credB.cookieHeader.includes('x-main=xyz'));
  });

  test('inline credential object', () => {
    const cred = new AmazonMusicCredential({ cookieHeader: 'at-main=abc' });
    assert.equal(cred.authenticated, true);
    assert.equal(cred.mode, 'cookie');
    assert.deepEqual(cred.toJSON().cookieNames, ['at-main']);
  });
});

/* ------------------------------------------------------------------ */
/* Solver                                                               */
/* ------------------------------------------------------------------ */

/** Offline session manager stub: serves a fully authenticated session. */
function stubSessionManager({ authenticated = true, entitlementList = null } = {}) {
  const cache = {};
  const buildSession = (territory) => (authenticated
    ? {
        territory, host: 'https://music.amazon.com', region: 'NA',
        sessionId: '111-1111111-1111111', deviceId: '12345', deviceType: 'A16ZV8BU3SN1N3',
        customerId: 'A1B2C3D4E5F6G7', authenticated: true,
        musicTier: 'UNLIMITED', tierInferred: false,
        entitlementList: entitlementList || ['KATANA', 'HAWKFIRE', 'SONIC_RUSH', 'ROBIN', 'NIGHTWING'],
        marketplaceId: 'ATVPDKIKX0DER', musicTerritory: 'US',
        csrf: { token: 'csrf' }, oAuthToken: null,
        cookies: 'at-main=abc; session-id=1', createdAt: Date.now()
      }
    : {
        territory, host: 'https://music.amazon.com', region: 'NA',
        sessionId: '111-1111111-1111111', deviceId: '12345', deviceType: 'A16ZV8BU3SN1N3',
        customerId: '', authenticated: false,
        entitlementList: [], marketplaceId: 'ATVPDKIKX0DER', musicTerritory: 'US',
        csrf: { token: 'csrf' }, cookies: 'session-id=1', createdAt: Date.now()
      });
  return {
    protocol: { userAgent: 'TestUA', commonHeaders: { 'Content-Type': 'application/json', 'Content-Encoding': 'amz-1.0', Accept: 'application/json' } },
    async getSession(territory, { credential } = {}) {
      const key = authenticated ? 'auth' : 'guest';
      if (!cache[key]) cache[key] = buildSession(territory);
      return cache[key];
    },
    buildHeaders(session, target) {
      return { 'Content-Encoding': 'amz-1.0', 'Content-Type': 'application/json', 'X-Amz-Target': target || '', Cookie: session.cookies };
    },
    invalidateSession() {}
  };
}

describe('AmazonDmlsSolver', () => {
  const CRED = new AmazonMusicCredential({ cookieHeader: 'at-main=abc; x-main=def' });

  test('guest short-circuit returns AUTH_REQUIRED with the live-verified wall', async () => {
    const solver = new AmazonDmlsSolver({ sessionManager: stubSessionManager({ authenticated: false }) });
    const solved = await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED });
    // stub says guest even though a credential object was passed (no customerId)
    assert.equal(solved.status, DmlsSolveStatus.AUTH_REQUIRED);
    assert.equal(solved.wall.__type, 'com.amazon.digitalmusiclocator#UnauthorizedException');
    assert.ok(solved.howToAuthenticate.recipe.length >= 3);
    assert.equal(solved.links, null);
    assert.equal(solved.manifest, null);
  });

  test('solves links from a SUCCESS manifest envelope (DRM card included)', async () => {
    const sm = stubSessionManager({ authenticated: true });
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      calls.push({ url, headers: opts.headers, body: JSON.parse(opts.body) });
      return { ok: true, status: 200, text: async () => MANIFEST_ENVELOPE };
    };
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const solved = await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED, tier: 'ultra' });

      assert.equal(solved.status, DmlsSolveStatus.DRM_PROTECTED_LINKS);
      assert.equal(calls.length, 1);
      assert.equal(calls[0].url, 'https://music.amazon.com/NA/api/dmls/');
      assert.equal(calls[0].headers['X-Amz-Target'],
        'com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal.getDashManifestsV2');
      assert.equal(calls[0].headers['Content-Encoding'], 'amz-1.0');

      // Production request shape
      const body = calls[0].body;
      assert.equal(body.customerId, 'A1B2C3D4E5F6G7');
      assert.deepEqual(body.contentIdList, [{ identifier: 'B084WKYYC3', identifierType: 'ASIN' }]);
      assert.deepEqual(body.musicDashVersionList, ['SIREN_KATANA']);
      assert.deepEqual(body.contentProtectionList, ['TRACK_PSSH']);
      assert.equal(body.tryAsinSubstitution, true);
      assert.equal(body.deviceToken.deviceTypeId, 'A16ZV8BU3SN1N3');
      assert.ok(body.appInfo.musicAgent.startsWith('Vinyl/2.0'));

      // Solved links
      assert.equal(solved.manifest.representationCount, 3);
      assert.equal(solved.selected.id, 'flac-24-96');
      assert.equal(solved.selected.tierLabel, 'UHD96');
      assert.equal(solved.links.initUrl,
        'https://d25tk16o50n0lf.cloudfront.net/track/B084WKYYC3/init/flac-24-96/init.mp4');
      assert.equal(solved.links.firstSegmentUrls[0],
        'https://d25tk16o50n0lf.cloudfront.net/track/B084WKYYC3/seg/flac-24-96/1.m4s');

      // DRM card
      assert.equal(solved.drm.protected, true);
      assert.equal(solved.drm.envelope, 'SIREN_KATANA CENC (TRACK_PSSH)');
      assert.ok(solved.drm.psshList.length === 1);
      assert.ok(solved.drm.licenseOperation.endsWith('getLicenseForPlaybackV2'));
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('clear FLAC representation yields LINKS_SOLVED (no DRM card)', async () => {
    const clearMpd = FIXTURE_MPD.replace(
      /<ContentProtection[\s\S]*?<\/ContentProtection>\s*/g, '');
    const envelope = JSON.stringify({
      contentResponseList: [{ manifest: clearMpd, cdnType: 'CLOUDFRONT', contentResponseStatusCode: 'SUCCESS' }]
    });
    const sm = stubSessionManager({ authenticated: true });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({ ok: true, status: 200, text: async () => envelope });
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const solved = await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED });
      assert.equal(solved.status, DmlsSolveStatus.LINKS_SOLVED);
      assert.equal(solved.drm.protected, false);
      assert.equal(solved.selected.id, 'flac-24-96');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('maps Coral auth walls and entitlement verdicts verbatim', async () => {
    const sm = stubSessionManager({ authenticated: true });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: false, status: 400,
      text: async () => JSON.stringify({
        __type: 'com.amazon.digitalmusiclocator#UnauthorizedException',
        message: 'Request authentication failure : CUSTOMER_INFO_NOT_PROVIDED (AuthenticationException)',
        requestId: 'req-1'
      })
    });
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const solved = await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED, forceWire: true });
      assert.equal(solved.status, DmlsSolveStatus.AUTH_REQUIRED);
      assert.ok(solved.reason.includes('UnauthorizedException'));
    } finally {
      globalThis.fetch = originalFetch;
    }

    globalThis.fetch = async () => ({
      ok: true, status: 200,
      text: async () => JSON.stringify({
        contentResponseList: [{
          contentResponseStatusCode: 'CUSTOMER_NOT_ELIGIBLE',
          contentResponseStatusMessage: 'Not entitled for KATANA'
        }]
      })
    });
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const solved = await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED });
      assert.equal(solved.status, DmlsSolveStatus.ENTITLEMENT_REQUIRED);
      assert.ok(solved.reason.includes('CUSTOMER_NOT_ELIGIBLE'));
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('first-chunk URL solve honors the production request shape', async () => {
    const sm = stubSessionManager({ authenticated: true });
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      calls.push({ url, body: JSON.parse(opts.body) });
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({
          contentResponseList: [{
            urlList: ['https://d25tk16o50n0lf.cloudfront.net/firstchunk/B084WKYYC3.mp4'],
            cdnTypeList: ['CLOUDFRONT'],
            statusCode: 'SUCCESS'
          }],
          drmPlaybackResource: { fairplay: { licenseAcquisitionURL: 'https://lic.example/fps' } }
        })
      };
    };
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const fc = await solver.solveFirstChunkUrl({ asin: 'B084WKYYC3', credential: CRED, session: await sm.getSession('US') });
      assert.equal(fc.status, DmlsSolveStatus.LINKS_SOLVED);
      assert.equal(fc.url, 'https://d25tk16o50n0lf.cloudfront.net/firstchunk/B084WKYYC3.mp4');
      assert.equal(fc.cdnType, 'CLOUDFRONT');
      assert.equal(fc.drmPlaybackResource.fairplay.licenseAcquisitionURL, 'https://lic.example/fps');
      // Production body shape (he(), 2026-11 vinylChunk re-decode)
      assert.deepEqual(calls[0].body.contentIdList, [{ identifier: 'B084WKYYC3', identifierType: 'ASIN' }]);
      assert.deepEqual(calls[0].body.customerInfo.entitlementList, ['KATANA', 'HAWKFIRE', 'SONIC_RUSH', 'ROBIN', 'NIGHTWING']);
      assert.equal(calls[0].body.protocol, 'DASH');
      assert.equal(calls[0].body.clientMetadata.clientId, 'WebCP', "registered web-player client id (authStore.clientId === 'WebCP')");
      assert.ok(calls[0].body.clientMetadata.clientRequestId, 'clientRequestId short uid present');
      assert.equal(calls[0].body.appMetadata.https, true);
      assert.equal(calls[0].body.appMetadata.appId, 'GREENHORNET_WEB');
      assert.equal(calls[0].body.appMetadata.appVersion, '1.196.81');
      assert.equal(calls[0].body.musicRequestIdentityContext, undefined, 'production he() carries no musicRequestIdentityContext');
      assert.equal(calls[0].body.bitRate, undefined, 'production he() carries no bitRate field');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('tier lossy requests the V2 bitrateTypeList manifest path (no content protection)', async () => {
    const sm = stubSessionManager({ authenticated: true });
    const calls = [];
    const v2Mpd = FIXTURE_MPD.replace(
      /<ContentProtection[\s\S]*?<\/ContentProtection>\s*/g, '');
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      calls.push({ url, headers: opts.headers, body: JSON.parse(opts.body) });
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({
          contentResponseList: [{
            manifest: v2Mpd, cdnType: 'CLOUDFRONT', contentResponseStatusCode: 'SUCCESS'
          }]
        })
      };
    };
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const solved = await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED, tier: 'lossy' });

      assert.equal(solved.status, DmlsSolveStatus.LINKS_SOLVED);
      assert.equal(solved.dashProfile, 'v2-lossy');

      const body = calls[0].body;
      assert.deepEqual(body.musicDashVersionList, ['V2'], 'production enableFlacOpus=false path');
      assert.deepEqual(body.bitrateTypeList, ['HIGH', 'MEDIUM', 'LOW'], 'entitled account gets the HIGH rung');
      assert.equal(body.contentProtectionList, undefined, 'V2 lossy path requests no content protection');
      assert.equal(body.tryAsinSubstitution, undefined);
      assert.ok(body.appInfo.musicAgent.startsWith('Vinyl/2.0 GREENHORNET_WEB/1.196.81 ('));
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('FLAC path drops tryAsinSubstitution when the account lacks HAWKFIRE/KATANA', async () => {
    // PRIME floor: ROBIN/NIGHTWING only - no HAWKFIRE/KATANA substitution rights.
    const sm = stubSessionManager({ authenticated: true, entitlementList: ['ROBIN', 'NIGHTWING'] });
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => {
      calls.push({ body: JSON.parse(opts.body) });
      return { ok: true, status: 200, text: async () => MANIFEST_ENVELOPE };
    };
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      await solver.solveStreamLinks({ asin: 'B084WKYYC3', credential: CRED });
      assert.equal(calls[0].body.tryAsinSubstitution, false, 'substitution is entitlement-gated (production de())');
      assert.deepEqual(calls[0].body.contentProtectionList, ['TRACK_PSSH']);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('solveSampleUrl: guest production shape with WebCP client + real expiration', async () => {
    const sm = stubSessionManager({ authenticated: false });
    const calls = [];
    const originalFetch = globalThis.fetch;
    const before = Math.floor(Date.now() / 1000);
    globalThis.fetch = async (url, opts) => {
      calls.push({ url, headers: opts.headers, body: JSON.parse(opts.body) });
      return {
        ok: true, status: 200,
        text: async () => JSON.stringify({
          requestId: 'req-s1',
          urls: [['https://d28julafmv4ekl.cloudfront.net/64%2F30%2F200000354418958_S64.mp3?Signature=abc']]
        })
      };
    };
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const sample = await solver.solveSampleUrl({ asin: 'B086Q41M9C' });

      assert.equal(sample.status, DmlsSolveStatus.LINKS_SOLVED);
      assert.equal(sample.url, 'https://d28julafmv4ekl.cloudfront.net/64%2F30%2F200000354418958_S64.mp3?Signature=abc');
      assert.equal(sample.operation, 'getStreamingURLsForSamples');

      const body = calls[0].body;
      assert.equal(body.clientInfo.clientId, 'WebCP', 'registered client id (unknown-client wall)');
      assert.ok(body.expiration >= before + 3595 && body.expiration <= before + 3605, 'expiration = unix now + 3600');
      assert.deepEqual(body.tracksInfoList, [{ asin: 'B086Q41M9C', contentFormat: 'mp3', bitrate: 64 }]);
      assert.deepEqual(body.protocolList, ['https']);
      assert.equal(calls[0].headers['X-Amz-Target'].endsWith('getStreamingURLsForSamples'), true);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('solveSampleUrl: missing sample maps to CONTENT_NOT_FOUND, not a throw', async () => {
    const sm = stubSessionManager({ authenticated: false });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: true, status: 200,
      text: async () => JSON.stringify({ requestId: 'req-s2', urls: [[]] })
    });
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const sample = await solver.solveSampleUrl({ asin: 'B0ALBUMONLY' });
      assert.equal(sample.status, DmlsSolveStatus.CONTENT_NOT_FOUND);
      assert.equal(sample.url, null);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('verifyLinkReachability returns honest Range-probe evidence', async () => {
    const sm = stubSessionManager({ authenticated: false });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, opts) => ({
      ok: true, status: 206,
      headers: {
        get: (name) => name === 'content-type' ? 'audio/mpeg'
          : name === 'content-range' ? 'bytes 0-1023/493511'
          : name === 'content-length' ? '1024' : null
      },
      arrayBuffer: async () => new ArrayBuffer(16)
    });
    try {
      const solver = new AmazonDmlsSolver({ sessionManager: sm });
      const evidence = await solver.verifyLinkReachability('https://d28julafmv4ekl.cloudfront.net/64/30/x_S64.mp3');
      assert.equal(evidence.reachable, true);
      assert.equal(evidence.httpStatus, 206);
      assert.equal(evidence.contentType, 'audio/mpeg');
      assert.equal(evidence.totalBytes, 493511);
      assert.equal(evidence.firstBytesHex.length, 32);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});

/* ------------------------------------------------------------------ */
/* Session manager: authenticated bootstrap + entitlement ladder        */
/* ------------------------------------------------------------------ */

describe('session manager (authenticated bootstrap)', () => {
  test('cookie bootstrap merges jars, harvests customerId, asserts the Unlimited ladder', async () => {
    const protocol = {
      hosts: { US: 'https://music.amazon.com' },
      regions: { US: 'NA' },
      configPath: '/config.json',
      authenticatedConfigPath: '/config.json?clientApplication=hornet&cookieauth=true',
      userAgent: 'UA',
      commonHeaders: { 'Content-Type': 'application/json', 'Content-Encoding': 'amz-1.0' }
    };
    const manager = new AmazonSessionManager({ protocol });
    const originalFetch = globalThis.fetch;
    const seen = [];
    globalThis.fetch = async (url, opts) => {
      seen.push({ url, cookie: opts.headers.Cookie });
      return {
        ok: true,
        headers: { getSetCookie: () => ['session-id=guest-sess; Path=/', 'am-token=devtok; Path=/'] },
        json: async () => ({
          sessionId: 'guest-sess', deviceId: '12345', deviceType: 'A16ZV8BU3SN1N3',
          customerId: 'A1B2C3D4E5F6G7', marketplaceId: 'ATVPDKIKX0DER', musicTerritory: 'US',
          benefits: ['UNLIMITED_HD'], csrf: { token: 'csrf' }
        })
      };
    };
    try {
      const cred = new AmazonMusicCredential({ cookieHeader: 'at-main=mine; x-main=mine2' });
      const session = await manager.getSession('US', { credential: cred });

      assert.equal(seen[0].url, 'https://music.amazon.com/config.json?clientApplication=hornet&cookieauth=true');
      assert.equal(seen[0].cookie, 'at-main=mine; x-main=mine2');
      assert.equal(session.authenticated, true);
      assert.equal(session.customerId, 'A1B2C3D4E5F6G7');
      assert.equal(session.musicTier, 'UNLIMITED');
      assert.equal(session.tierInferred, true);
      // Caller cookies win on collisions; device cookies included
      assert.ok(session.cookies.includes('at-main=mine'));
      assert.ok(session.cookies.includes('session-id=guest-sess'));
      assert.deepEqual(session.entitlementList, ['KATANA', 'HAWKFIRE', 'SONIC_RUSH', 'ROBIN', 'NIGHTWING']);

      // buildHeaders carries the Coral dispatcher key + cookie jar
      const headers = manager.buildHeaders(session, 'com.amazon.digitalmusiclocator.DigitalMusicLocatorServiceExternal.getDashManifestsV2');
      assert.equal(headers['Content-Encoding'], 'amz-1.0');
      assert.equal(headers['X-Amz-Target'].endsWith('getDashManifestsV2'), true);
      assert.ok(headers.Cookie.includes('at-main=mine'));
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  test('guest bootstrap stays anonymous with empty entitlements', async () => {
    const protocol = {
      hosts: { US: 'https://music.amazon.com' }, regions: { US: 'NA' },
      configPath: '/config.json', userAgent: 'UA',
      commonHeaders: { 'Content-Type': 'application/json', 'Content-Encoding': 'amz-1.0' }
    };
    const manager = new AmazonSessionManager({ protocol });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => ({
      ok: true,
      headers: { getSetCookie: () => ['session-id=g; Path=/'] },
      json: async () => ({ sessionId: 'g', deviceId: '1', deviceType: 'A16ZV8BU3SN1N3', customerId: '', marketplaceId: 'ATVPDKIKX0DER' })
    });
    try {
      const session = await manager.getSession('US');
      assert.equal(session.authenticated, false);
      assert.deepEqual(session.entitlementList, []);
      assert.equal(session.musicTier, null);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
