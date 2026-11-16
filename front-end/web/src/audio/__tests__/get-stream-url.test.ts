/**
 * P2-A — getStreamUrl: the single playback entry point.
 *
 * Runs the REAL resolve.ts ladder through the same __p2a router (network
 * free) and locks the wrapper's behavior: the element ladder ranking
 * (W1.2-derived: audio-only opus/webm → m4a → muxed video itag), the
 * per-track cache (seeks/retries never hit the pipeline), expiry from the
 * wire, the range probe, and the resolve instrumentation (one record per
 * resolve, protocol + format).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import fixtures from './p2a.fixtures.json';

import {
  clearStreamUrlCache,
  getStreamUrl,
  invalidateStreamUrl,
  peekStreamUrl,
  probeStreamUrl,
  recentResolveLog,
  type ResolveLogRecord,
  type StreamUrlResolution,
} from '../get-stream-url';
import { resetVideoLedger } from '../resolve';

type Any = any; // eslint-disable-line @typescript-eslint/no-explicit-any

// ---------------------------------------------------------------------------
// The __p2a test router (same shape as resolver-lock.test.ts)
// ---------------------------------------------------------------------------

interface RelayRouter {
  visionos?: Any;
  webSafari?: Any;
}

interface P2aState {
  playerPoToken(videoId: string): Promise<{ poToken: string; kind: 'live' } | null>;
  ytBasicInfo(videoId: string): Promise<Any>;
  directPlayerPost(spec: { rung: Any; clientNumber: string }, videoId: string): Promise<{ response: Response; lane: 'direct' }>;
  relay: RelayRouter;
  calls: string[];
}

const FAKE_PLAYER = {
  decipher: async (url: string) => `https://player-dec.test/${encodeURIComponent(url)}`,
  po_token: '',
};

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

vi.mock('../../lib/ytSession', () => ({
  getYt: async () => ({
    session: {
      player: FAKE_PLAYER,
      context: { client: { clientVersion: '2.20260708.00.00', visitorData: 'test-visitor' } },
    },
    getBasicInfo: async (videoId: string, opts: Any) =>
      (globalThis as Any).__p2a.ytBasicInfo(videoId, opts),
  }),
  // The real contract: directPlayerPost(endpoint, payload, headers, signal)
  // → { response: Response, lane }. Derive the rung from the client identity
  // + PO-token presence; the videoId rides the payload.
  directPlayerPost: async (_endpoint: Any, payload: Any, _headers: Any, signal: Any) => {
    const clientName = String(payload?.context?.client?.clientName ?? '');
    const hasPot = payload?.serviceIntegrityDimensions?.poToken !== undefined;
    const rung = clientName === 'VISIONOS' ? 'visionos-direct' : hasPot ? 'ios-pot-direct' : 'ios-direct';
    return (globalThis as Any).__p2a.directPlayerPost(
      { rung, clientNumber: '' },
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
  gvsPoToken: async () => 'TEST_GVS_POT',
  resetPotSession: () => undefined,
}));

vi.mock('../webcodecsDecoder', () => ({
  webCodecsAvailable: () => false,
  webCodecsSupports: async () => false,
  WebCodecsAudioDecoder: class {},
}));

beforeEach(() => {
  (globalThis as Any).__p2a = {
    playerPoToken: async () => ({ poToken: 'TEST_PO', kind: 'live' }),
    ytBasicInfo: async (videoId: string) => {
      if (videoId === 'FIX_YT') return decorateYtInfo(fixtures.ytWebPot);
      if (videoId === 'FIX_YSABR') return decorateYtInfo(fixtures.ytSabr);
      return { playability_status: { status: 'LOGIN_REQUIRED', reason: 'lic' } };
    },
    directPlayerPost: async (spec: Any, videoId: string) => {
      let resp: Any = DEFAULT_LIC;
      if (videoId === 'FIX_STD') resp = fixtures.stdVisionos;
      else if (videoId === 'FIX_HLS') resp = fixtures.hlsVisionos;
      else if (videoId === 'FIX_YT' || videoId === 'FIX_YSABR') resp = DEFAULT_LIC;
      (globalThis as Any).__p2a.calls.push(`direct ${spec.rung} ${videoId}`);
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
  } satisfies P2aState;
  vi.stubGlobal('fetch', async (url: Any, init?: Any) => {
    const u = String(url);
    const p2a = (globalThis as Any).__p2a as P2aState;
    p2a.calls.push(`fetch ${u}`);
    if (u.startsWith('https://relay.test/r?')) {
      const clientName = (init?.headers?.['x-youtube-client-name'] ?? '') as string;
      const resp =
        clientName === '1' ? (p2a.relay.webSafari ?? DEFAULT_LIC) : (p2a.relay.visionos ?? DEFAULT_LIC);
      return new Response(JSON.stringify(resp), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }
    throw new Error(`unrouted fetch: ${u}`);
  });
  for (const v of ['FIX_STD', 'FIX_HLS', 'FIX_YT', 'FIX_YSABR']) resetVideoLedger(v);
  clearStreamUrlCache();
});

afterEach(() => {
  vi.unstubAllGlobals();
  delete (globalThis as Any).__p2a;
});

// ---------------------------------------------------------------------------
// Probe fakes (the range probe is injectable — no global fetch involved)
// ---------------------------------------------------------------------------

function probe206(): typeof fetch {
  return (async () =>
    new Response(new Uint8Array([0]), {
      status: 206,
      headers: { 'content-range': 'bytes 0-0/3456789', 'content-length': '1' },
    })) as unknown as typeof fetch;
}

function probe200NoRange(): typeof fetch {
  return (async () =>
    new Response(new Uint8Array([0]), {
      status: 200,
      headers: { 'content-length': '3456789' },
    })) as unknown as typeof fetch;
}

function probe403(): typeof fetch {
  return (async () => new Response('gone', { status: 403 })) as unknown as typeof fetch;
}

const PIN = '&pot=TEST_GVS_POT';

describe('getStreamUrl — the single playback entry point', () => {
  it('resolves once and returns everything: ranked ladder, expiry, range, duration', async () => {
    const logs: ResolveLogRecord[] = [];
    const resolution = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
      probeImpl: probe206(),
      onLog: (r) => logs.push(r),
    });
    const expected: StreamUrlResolution = {
      trackId: 'FIX_STD',
      url: `https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=251${PIN}`,
      protocol: 'progressive',
      ladder: [
        {
          rank: 0,
          itag: 251,
          url: `https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=251${PIN}`,
          mimeType: 'audio/webm; codecs="opus"',
          audioOnly: true,
          codec: 'opus',
          bitrate: 130000,
          contentLength: 3456789,
        },
        {
          rank: 1,
          itag: 140,
          url: `https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=140${PIN}`,
          mimeType: 'audio/mp4; codecs="mp4a.40.2"',
          audioOnly: true,
          codec: 'aac',
          bitrate: 128000,
          contentLength: 3400000,
        },
        {
          rank: 2,
          itag: 18,
          url: `https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=18${PIN}`,
          mimeType: 'video/mp4; codecs="avc1.42E01E, mp4a.40.2"',
          audioOnly: false,
          codec: 'avc1+aac',
          bitrate: 300000,
          contentLength: 8000000,
        },
      ],
      codecs: 'opus',
      resolvedAt: expect.any(Number) as unknown as number,
      expiresAt: expect.any(Number) as unknown as number,
      expirySource: 'wire',
      durationMs: 213000,
      rangeSupport: true,
      contentLength: 3456789,
      title: 'Neon Cascade',
      artist: 'Fixture Artist',
      rung: 'visionos-direct',
      isSabr: false,
    };
    expect(resolution).toEqual(expected);
    // The wire expiry (21540 s) drove expiresAt.
    expect(resolution.expiresAt - resolution.resolvedAt).toBe(21540 * 1000);
    // One instrumentation record per resolve, protocol + format included.
    expect(logs).toHaveLength(1);
    expect(logs[0]?.protocol).toBe('progressive');
    expect(logs[0]?.rung).toBe('visionos-direct');
    expect(logs[0]?.itag).toBe(251);
    expect(logs[0]?.codec).toBe('opus');
    expect(logs[0]?.ladderDepth).toBe(3);
    expect(logs[0]?.expiresInSeconds).toBe(21540);
    expect(logs[0]?.sabr).toBe(false);
  });

  it('the ladder walks the W1.2 order even when the pipeline picked a lower rung', async () => {
    // The pipeline (element posture) picked itag 140; the element ladder
    // re-ranks 251 first — the engine walks DOWN from it on decode error.
    const resolution = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
      probeImpl: probe206(),
    });
    expect(resolution.ladder.map((r) => r.itag)).toEqual([251, 140, 18]);
    expect(resolution.ladder.map((r) => r.audioOnly)).toEqual([true, true, false]);
  });

  it('caches per track: the second call never touches the pipeline', async () => {
    const before = ((globalThis as Any).__p2a as P2aState).calls.length;
    const first = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
      probeImpl: probe206(),
    });
    const second = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
    });
    const after = ((globalThis as Any).__p2a as P2aState).calls.length;
    expect(after).toBe(before + 1); // exactly one visionos player call total
    expect(second).toBe(first); // the identical object — zero re-resolve
    expect(peekStreamUrl('FIX_STD')).toBe(first);
  });

  it('concurrent callers share one in-flight resolve', async () => {
    const [a, b] = await Promise.all([
      getStreamUrl('FIX_STD', { relayBase: 'https://relay.test', probeImpl: probe206() }),
      getStreamUrl('FIX_STD', { relayBase: 'https://relay.test', probeImpl: probe206() }),
    ]);
    expect(a).toBe(b);
    // One instrumentation record for the shared in-flight resolve.
    expect(recentResolveLog().filter((r) => r.trackId === 'FIX_STD')).toHaveLength(1);
  });

  it('expiry is the one cache-invalidating event: a stale entry re-resolves', async () => {
    vi.useFakeTimers();
    try {
      const first = await getStreamUrl('FIX_STD', {
        relayBase: 'https://relay.test',
        probeImpl: probe206(),
      });
      const recordsAfterFirst = recentResolveLog().length;
      // Still fresh.
      await getStreamUrl('FIX_STD', { relayBase: 'https://relay.test' });
      expect(recentResolveLog().length).toBe(recordsAfterFirst);
      // Past the wire expiry (21540 s).
      vi.advanceTimersByTime(21540 * 1000 + 1000);
      const second = await getStreamUrl('FIX_STD', {
        relayBase: 'https://relay.test',
        probeImpl: probe206(),
      });
      expect(recentResolveLog().length).toBe(recordsAfterFirst + 1);
      expect(second).not.toBe(first);
      expect(second.url).toBe(first.url); // same fixture, same ladder
    } finally {
      vi.useRealTimers();
    }
  });

  it('invalidateStreamUrl drops the entry; the next call re-resolves', async () => {
    await getStreamUrl('FIX_STD', { relayBase: 'https://relay.test', probeImpl: probe206() });
    const recordsAfterFirst = recentResolveLog().length;
    expect(invalidateStreamUrl('FIX_STD', 'reported 403')).toBe(true);
    expect(peekStreamUrl('FIX_STD')).toBeNull();
    await getStreamUrl('FIX_STD', { relayBase: 'https://relay.test', probeImpl: probe206() });
    expect(recentResolveLog().length).toBe(recordsAfterFirst + 1);
  });

  it('rangeSupport selects the seek strategy: 206 → true, 200-no-range → false, probe failure → null', async () => {
    const ranged = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
      probeImpl: probe206(),
    });
    expect(ranged.rangeSupport).toBe(true);
    const flat = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
      probeImpl: probe200NoRange(),
      forceRefresh: true,
    });
    expect(flat.rangeSupport).toBe(false);
    const dead = await getStreamUrl('FIX_STD', {
      relayBase: 'https://relay.test',
      probeImpl: probe403(),
      forceRefresh: true,
    });
    expect(dead.rangeSupport).toBe(null);
  });

  it('probeStreamUrl classifies URL health (the walk/stale classifier)', async () => {
    const healthy = await probeStreamUrl(
      'https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1750000000&itag=251',
      probe206(),
    );
    expect(healthy.ok).toBe(true);
    if (healthy.ok) {
      expect(healthy.acceptsRanges).toBe(true);
      expect(healthy.contentLength).toBe(3456789);
    }
    const stale = await probeStreamUrl(
      'https://rr3---sn-fix.googlevideo.com/videoplayback?expire=1&itag=251',
      probe403(),
    );
    expect(stale.ok).toBe(false);
    if (!stale.ok) {
      expect(stale.status).toBe(403);
    }
  });

  it('HLS wins carry no ladder and no probe (manifest-driven seek)', async () => {
    const resolution = await getStreamUrl('FIX_HLS', {
      relayBase: 'https://relay.test',
    });
    expect(resolution.protocol).toBe('hls');
    expect(resolution.ladder).toEqual([]);
    expect(resolution.rangeSupport).toBeNull();
    expect(resolution.codecs).toBe('aac');
    expect(resolution.url).toBe('https://manifest.googlevideo.com/api/manifest/hls_variant/fixture');
    expect(resolution.durationMs).toBe(213000);
  });

  it('SABR wins surface the honest unplayable state and count as SABR hits', async () => {
    const logs: ResolveLogRecord[] = [];
    const resolution = await getStreamUrl('FIX_YSABR', {
      relayBase: 'https://relay.test',
      onLog: (r) => logs.push(r),
    });
    expect(resolution.protocol).toBe('sabr');
    expect(resolution.isSabr).toBe(true);
    expect(resolution.ladder).toEqual([]);
    expect(resolution.rangeSupport).toBeNull();
    expect(resolution.url).toBe(
      `https://player-dec.test/${encodeURIComponent('https://sabr.test/videoplayback?sabr=1&cipher=1')}`,
    );
    expect(resolution.durationMs).toBe(213000);
    expect(logs[0]?.sabr).toBe(true);
    expect(logs[0]?.protocol).toBe('sabr');
    expect(recentResolveLog().at(-1)?.sabr).toBe(true);
  });

  it('the WEB+POT rung feeds the ladder through the deciphering player (no network)', async () => {
    const resolution = await getStreamUrl('FIX_YT', {
      relayBase: 'https://relay.test',
      probeImpl: probe206(),
    });
    expect(resolution.rung).toBe('web-pot-direct');
    expect(resolution.protocol).toBe('progressive');
    expect(resolution.ladder.map((r) => r.itag)).toEqual([251, 140, 18]);
    // The yt-rung URLs were deciphered locally (fake player), then pinned.
    expect(resolution.ladder[0]?.url).toBe(
      `https://yt-dec.googlevideo.com/videoplayback?itag=251&c=WEB${PIN}`,
    );
    expect(resolution.ladder[2]?.url).toBe(
      `https://yt-dec.googlevideo.com/videoplayback?itag=18&c=WEB${PIN}`,
    );
    expect(resolution.codecs).toBe('opus');
  });
});
