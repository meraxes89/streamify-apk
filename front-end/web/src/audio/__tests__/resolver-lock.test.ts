/**
 * P2-A — the link-solver lock.
 *
 * Guardrail: "the link solver must not break." These tests run the REAL
 * resolve.ts ladder end-to-end (rung sequencing, retry discipline, verdict
 * aggregation, ledger escalation, stream mapping) against recorded wire
 * shapes (p2a.fixtures.json) with every network call routed through the
 * __p2a test router. Zero network. Any behavior drift in resolve.ts breaks
 * a fixture here — CI goes red — the solver is locked.
 *
 * The fixtures are append-only: never edit an existing expectation, only
 * add new ones.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fixtures from './p2a.fixtures.json';

import {
  failedRungsFor,
  isBotGateError,
  resetVideoLedger,
  resolveAudioStream,
  type LadderDigest,
  type LadderRung,
  type ResolvedStream,
} from '../resolve';

// ---------------------------------------------------------------------------
// The __p2a test router (behavior injected through globalThis so the
// hoisted vi.mock factories can read it)
// ---------------------------------------------------------------------------

type Any = any; // eslint-disable-line @typescript-eslint/no-explicit-any

interface RelayRouter {
  visionos?: Any;
  webSafari?: Any;
}

interface P2aState {
  webCodecs: boolean;
  playerPoToken(videoId: string): Promise<{ poToken: string; kind: 'live' } | null>;
  ytBasicInfo(videoId: string): Promise<Any>;
  directPlayerPost(
    spec: { rung: LadderRung; clientNumber: string },
    videoId: string,
  ): Promise<{ response: Response; lane: 'direct' }>;
  relay: RelayRouter;
  calls: string[];
}

const FAKE_PLAYER: {
  decipher: (url: string) => Promise<string>;
  po_token: string;
} = {
  decipher: async (url) => `https://player-dec.test/${encodeURIComponent(url)}`,
  po_token: '',
};

/** Decorate a youtubei.js-shaped fixture: both wire spellings + decipher. */
function decorateYtInfo(info: Any): Any {
  const decorate = (f: Any): Any => ({
    ...f,
    mimeType: f.mime_type,
    contentLength: f.content_length,
    decipher: async () => `https://yt-dec.googlevideo.com/videoplayback?itag=${f.itag}&c=WEB`,
  });
  return {
    ...info,
    streaming_data: info.streaming_data
      ? {
          ...info.streaming_data,
          adaptive_formats: (info.streaming_data.adaptive_formats ?? []).map(decorate),
          formats: (info.streaming_data.formats ?? []).map(decorate),
        }
      : undefined,
  };
}

const DEFAULT_LIC = fixtures.licLoginRequired as Any;

function defaultDirectResp(rung: LadderRung, videoId: string): Any {
  switch (videoId) {
    case 'FIX_STD':
      return fixtures.stdVisionos;
    case 'FIX_HLS':
      return rung === 'visionos-direct' ? fixtures.hlsVisionos : DEFAULT_LIC;
    case 'FIX_IOSPOT':
      // Rung 1 must die licensed so rung 3 gets its turn (the derived rung
      // is 'ios-pot-direct' when the call carries a token).
      return rung === 'ios-direct' || rung === 'ios-pot-direct'
        ? fixtures.iosPotOk
        : DEFAULT_LIC;
    case 'FIX_RELAY':
      return DEFAULT_LIC; // rungs 1-4 die; rung 5 wins via the relay router
    case 'FIX_SAFARI':
      return DEFAULT_LIC; // rungs 1-4 die; rung 6 wins via the relay router
    case 'FIX_YT':
    case 'FIX_YSABR':
      return DEFAULT_LIC; // licensed: the WEB+POT rung owns these
    case 'FIX_LIC':
      return fixtures.licLoginRequired;
    case 'FIX_SABR':
      return fixtures.sabrOnly;
    default:
      return DEFAULT_LIC;
  }
}

vi.mock('../../lib/ytSession', () => ({
  getYt: async () => ({
    session: {
      player: FAKE_PLAYER,
      context: {
        client: { clientVersion: '2.20260708.00.00', visitorData: 'test-visitor' },
      },
    },
    getBasicInfo: async (videoId: string, opts: Any) =>
      (globalThis as Any).__p2a.ytBasicInfo(videoId, opts),
  }),
  // The real contract: directPlayerPost(endpoint, payload, headers, signal)
  // → { response: Response, lane }. Derive the rung from the client identity
  // + PO-token presence; the videoId rides the payload.
  directPlayerPost: async (_endpoint: Any, payload: Any, headers: Any, signal: Any) => {
    const clientName = String(payload?.context?.client?.clientName ?? '');
    const clientNumber = String(
      headers?.['x-youtube-client-name'] ?? payload?.context?.client?.clientName ?? '',
    );
    const hasPot = payload?.serviceIntegrityDimensions?.poToken !== undefined;
    const rung: LadderRung =
      clientName === 'VISIONOS' ? 'visionos-direct' : hasPot ? 'ios-pot-direct' : 'ios-direct';
    return (globalThis as Any).__p2a.directPlayerPost(
      { rung, clientNumber },
      String(payload?.videoId ?? ''),
      signal,
    );
  },
  visitorForFamily: async () => 'test-visitor',
  visitorProvenance: () => 'test/web',
  appVisitorProvenance: () => 'test/app',
  rotateVisitorFamily: () => undefined,
  directLaneHealthy: () => true,
  egressLaneFor: () => 'direct',
}));

vi.mock('../../lib/gsfLane', () => ({ gsfConfigured: () => false }));

vi.mock('../botguard/potSession', () => ({
  playerPoToken: async (...a: Any[]) => (globalThis as Any).__p2a.playerPoToken(a[0]),
  // Deterministic pinning for pinGvsPot / get-stream-url's uniform pin pass.
  gvsPoToken: async () => 'TEST_GVS_POT',
  resetPotSession: () => undefined,
}));

vi.mock('../webcodecsDecoder', () => ({
  webCodecsAvailable: () => (globalThis as Any).__p2a?.webCodecs ?? false,
  webCodecsSupports: async () => false,
  WebCodecsAudioDecoder: class {},
}));

function installRouter(overrides: Partial<P2aState> = {}): P2aState {
  const p2a: P2aState = {
    webCodecs: false,
    playerPoToken: async () => ({ poToken: 'TEST_PO', kind: 'live' }),
    ytBasicInfo: async (videoId) => {
      if (videoId === 'FIX_YT') return decorateYtInfo(fixtures.ytWebPot);
      if (videoId === 'FIX_YSABR') return decorateYtInfo(fixtures.ytSabr);
      return { playability_status: { status: 'LOGIN_REQUIRED', reason: 'lic' } };
    },
    directPlayerPost: async (spec, videoId) => {
      const resp =
        videoId === 'FIX_LIC' || videoId === 'FIX_SABR'
          ? defaultDirectResp('ios-direct', videoId)
          : defaultDirectResp(spec.rung, videoId);
      // The real directPlayerPost contract: { response: Response, lane }.
      return {
        response: new Response(JSON.stringify(resp), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
        lane: 'direct' as const,
      };
    },
    relay: {},
    calls: [],
    ...overrides,
  };
  (globalThis as Any).__p2a = p2a;
  return p2a;
}

/** The global fetch stub: the relay surface (rungs 5/6) + nothing else. */
function stubFetch(): void {
  vi.stubGlobal('fetch', async (url: Any, init?: Any) => {
    const u = String(url);
    const p2a = (globalThis as Any).__p2a as P2aState;
    p2a.calls.push(`fetch ${u}`);
    if (u.startsWith('https://relay.test/r?')) {
      const clientName = (init?.headers?.['x-youtube-client-name'] ?? '') as string;
      const resp =
        clientName === '1'
          ? (p2a.relay.webSafari ?? DEFAULT_LIC)
          : (p2a.relay.visionos ?? DEFAULT_LIC);
      return new Response(JSON.stringify(resp), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }
    throw new Error(`unrouted fetch: ${u}`);
  });
}

const LEDGER_VIDEOS = [
  'FIX_STD',
  'FIX_HLS',
  'FIX_LIC',
  'FIX_SABR',
  'FIX_YT',
  'FIX_YSABR',
  'FIX_IOSPOT',
  'FIX_RELAY',
  'FIX_SAFARI',
];

beforeEach(() => {
  installRouter();
  stubFetch();
  for (const v of LEDGER_VIDEOS) resetVideoLedger(v);
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (globalThis as Any).__p2a;
});

// ---------------------------------------------------------------------------
// The lock
// ---------------------------------------------------------------------------

describe('resolveAudioStream — the link-solver lock', () => {
  it('rung 1 (visionos-direct) maps the wire to the expected progressive stream (element posture)', async () => {
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream('FIX_STD', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });
    const expected: ResolvedStream = {
      videoId: 'FIX_STD',
      itag: 140,
      codec: 'aac',
      url: 'https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=140',
      mimeType: 'audio/mp4; codecs="mp4a.40.2"',
      bitrate: 128000,
      contentLength: 3400000,
      durationSec: 213,
      title: 'Neon Cascade',
      artist: 'Fixture Artist',
      rung: 'visionos-direct',
      potKind: 'none',
      isHls: false,
      isSabr: false,
    };
    expect(stream).toEqual(expected);
    // The digest carries the winning rung's raw materials.
    expect(digest?.kind).toBe('progressive');
    if (digest?.kind === 'progressive') {
      expect(digest.rung).toBe('visionos-direct');
      expect(digest.resp).toEqual(fixtures.stdVisionos);
      expect(digest.stream).toEqual(expected);
    }
  });

  it('rung 1 (visionos-direct) spine posture picks itag 251 when WebCodecs is available (W1.2 ladder)', async () => {
    installRouter({ webCodecs: true });
    const stream = await resolveAudioStream('FIX_STD', {
      relayBase: 'https://relay.test',
      elementCompat: false,
    });
    expect(stream.itag).toBe(251);
    expect(stream.codec).toBe('opus');
    expect(stream.url).toBe(
      'https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=251',
    );
  });

  it('rung 1 maps an HLS win (elementCompat, no progressive formats)', async () => {
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream('FIX_HLS', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });
    expect(stream).toEqual({
      videoId: 'FIX_HLS',
      itag: 0,
      codec: 'aac',
      url: 'https://manifest.googlevideo.com/api/manifest/hls_variant/fixture',
      mimeType: 'application/vnd.apple.mpegurl',
      bitrate: 0,
      contentLength: null,
      durationSec: 213,
      title: 'HLS Lane',
      artist: 'Fixture Artist',
      rung: 'visionos-direct',
      potKind: 'none',
      isHls: true,
      isSabr: false,
    } satisfies ResolvedStream);
    expect(digest?.kind).toBe('hls');
  });

  it('a licensed bot-gate refusal kills every rung: the terminal verdict carries bot-gate=1 and the ledger records it', async () => {
    const promise = resolveAudioStream('FIX_LIC', {
      relayBase: 'https://relay.test',
      elementCompat: true,
    });
    await expect(promise).rejects.toThrow(/no rung resolved/);
    await expect(promise).rejects.toThrow(/LOGIN_REQUIRED/);
    try {
      await promise;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      expect(isBotGateError(message)).toBe(true);
    }
    const ledger = new Set(failedRungsFor('FIX_LIC'));
    expect(ledger.has('visionos-direct')).toBe(true);
    expect(ledger.has('ios-direct')).toBe(true);
    expect(ledger.has('visionos-relay')).toBe(true);
    expect(ledger.has('web-safari-hls')).toBe(true);
    expect(ledger.has('ios-pot-direct')).toBe(true); // token present: rung 3 ran
  });

  it('a SABR-only answer on an app-client envelope is refused, escalated and never digested', async () => {
    const digests: LadderDigest[] = [];
    const promise = resolveAudioStream('FIX_SABR', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digests.push(d);
      },
    });
    await expect(promise).rejects.toThrow(/SABR-only on an app-client envelope/);
    expect(digests).toHaveLength(0); // no rung won: no digest, ever
    const ledger = new Set(failedRungsFor('FIX_SABR'));
    expect(ledger.has('visionos-direct')).toBe(true);
    expect(ledger.has('ios-direct')).toBe(true);
  });

  it('the WEB+POT rung (kira recipe) maps a deciphered yt format with the digest carrying info+player', async () => {
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream('FIX_YT', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });
    expect(stream).toEqual({
      videoId: 'FIX_YT',
      itag: 140,
      codec: 'aac',
      url: 'https://yt-dec.googlevideo.com/videoplayback?itag=140&c=WEB',
      mimeType: 'audio/mp4; codecs="mp4a.40.2"',
      bitrate: 128000,
      contentLength: 3400000,
      durationSec: 213,
      title: 'Kira Recipe',
      artist: 'Fixture Artist',
      rung: 'web-pot-direct',
      potKind: 'live',
      isHls: false,
      isSabr: false,
    } satisfies ResolvedStream);
    expect(digest?.kind).toBe('progressive');
    if (digest?.kind === 'progressive') {
      expect(digest.info?.basic_info?.title).toBe('Kira Recipe');
      expect(digest.player).toBeDefined();
    }
  });

  it('the WEB+POT rung accepts a SABR-only yt response (elementCompat) and digests it as sabr', async () => {
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream('FIX_YSABR', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });
    expect(stream.isSabr).toBe(true);
    expect(stream.mimeType).toBe('application/vnd.yt.sabr');
    expect(stream.rung).toBe('web-pot-direct');
    // The deciphered sabr URL rode the fake player.
    expect(stream.url).toBe(
      `https://player-dec.test/${encodeURIComponent('https://sabr.test/videoplayback?sabr=1&cipher=1')}`,
    );
    expect(stream.sabr?.ustreamerConfig).toBe('TEST_USTREAMER_B64');
    expect(stream.sabr?.poToken).toBe('TEST_PO');
    expect(stream.sabr?.durationMs).toBe(213000);
    expect(stream.sabr?.formats).toHaveLength(1);
    expect(digest?.kind).toBe('sabr');
    if (digest?.kind === 'sabr') {
      // Only video-only descriptors were in the response.
      expect(digest.sabrAudioItags).toEqual([]);
    }
  });

  it('rung 3 (ios-pot-direct) wins progressive and pins the GVS pot on the returned URL', async () => {
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream('FIX_IOSPOT', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });
    expect(stream).toEqual({
      videoId: 'FIX_IOSPOT',
      itag: 140,
      codec: 'aac',
      url: 'https://rr5---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=140&pot=TEST_GVS_POT',
      mimeType: 'audio/mp4; codecs="mp4a.40.2"',
      bitrate: 128000,
      contentLength: 3500000,
      durationSec: 221,
      title: 'IOS POT Lane',
      artist: 'Fixture Artist',
      rung: 'ios-pot-direct',
      potKind: 'live',
      isHls: false,
      isSabr: false,
    } satisfies ResolvedStream);
    // NOTE (design): the digest fires pre-pin (inside evaluateRawResponse);
    // getStreamUrl re-pins every ladder rung uniformly — documented in
    // decisions/p2/P2A-resolver-getstream-url.md.
    expect(digest?.kind).toBe('progressive');
    if (digest?.kind === 'progressive') {
      expect(digest.stream.url).toBe(
        'https://rr5---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=140',
      );
    }
  });

  it('rung 5 (visionos-relay) wins progressive through the relay surface, unpinned', async () => {
    installRouter({ relay: { visionos: fixtures.relayVisionosOk } });
    const stream = await resolveAudioStream('FIX_RELAY', {
      relayBase: 'https://relay.test',
      elementCompat: true,
    });
    expect(stream).toEqual({
      videoId: 'FIX_RELAY',
      itag: 140,
      codec: 'aac',
      url: 'https://rr7---sn-relay.googlevideo.com/videoplayback?expire=1750000000&itag=140',
      mimeType: 'audio/mp4; codecs="mp4a.40.2"',
      bitrate: 128000,
      contentLength: 3250000,
      durationSec: 204,
      title: 'Relay Lane',
      artist: 'Fixture Artist',
      rung: 'visionos-relay',
      potKind: 'none',
      isHls: false,
      isSabr: false,
    } satisfies ResolvedStream);
  });

  it('rung 6 (web-safari-hls) maps an HLS win with the live pot kind', async () => {
    installRouter({ relay: { webSafari: fixtures.safariHls } });
    let digest: LadderDigest | undefined;
    const stream = await resolveAudioStream('FIX_SAFARI', {
      relayBase: 'https://relay.test',
      elementCompat: true,
      onLadderDigest: (d) => {
        digest = d;
      },
    });
    expect(stream).toEqual({
      videoId: 'FIX_SAFARI',
      itag: 0,
      codec: 'aac',
      url: 'https://manifest.googlevideo.com/api/manifest/hls_variant/safari-fixture',
      mimeType: 'application/vnd.apple.mpegurl',
      bitrate: 0,
      contentLength: null,
      durationSec: 199,
      title: 'Safari HLS Lane',
      artist: 'Fixture Artist',
      rung: 'web-safari-hls',
      potKind: 'live',
      isHls: true,
      isSabr: false,
    } satisfies ResolvedStream);
    expect(digest?.kind).toBe('hls');
  });

  it('existing consumers stay intact: the legacy exports are untouched', async () => {
    // The six legacy importers (spine, wasmEngine, trackPlayer, playerStore,
    // g3bVerify, sabrStreamPlayer) import by symbol — resolved here from the
    // same module graph the lock runs against.
    const mod = await import('../resolve');
    expect(typeof mod.resolveAudioStream).toBe('function');
    expect(typeof mod.markRungFailed).toBe('function');
    expect(typeof mod.failedRungsFor).toBe('function');
    expect(typeof mod.resetVideoLedger).toBe('function');
    expect(typeof mod.isBotGateError).toBe('function');
    // Type-only exports (LadderRung, ResolvedStream, SabrPayload,
    // ResolveContext) are compile-time only — the legacy importers use
    // `import type`; no runtime member was added or removed.
  });
});
