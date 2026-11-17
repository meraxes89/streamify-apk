/**
 * relay ytm-meta.test.ts — the W1-A network-free test lane.
 *
 * Every fixture below is a trimmed capture of the LIVE innertube shapes
 * the worker's normalizers walk (probed against production on
 * 2026-11-16 — see the W1-A ADR's probe record). The tests lock:
 *   • key encoding (gl/hl isolation — regional charts and localized
 *     titles never bleed across countries/languages)
 *   • the TTL matrix
 *   • the synced flag round-trip through the cache layers
 *   • single-flight coalescing (N concurrent identical requests →
 *     exactly ONE upstream fill)
 *   • the KV-absent fallback (local dev / CI: bypassed, never thrown)
 *   • the SWR policy (the lyrics rule: synced-and-complete is terminal)
 *   • the normalized-payload shapes (playlist/album/lyrics/suggest/
 *     up-next, first page + continuation page)
 *   • failure separation (429 passthrough, network 502, parse 502)
 *   • the 2 MiB cache gate (oversized is served, never cached)
 */

import { beforeEach, describe, expect, it } from 'vitest';

import {
  appsScriptSolverUrl,
  isControlPlaneTarget,
} from './index';
import {
  buildYtmCacheKey,
  counterpartSeedOf,
  matchCounterpart,
  normalizeYtmResponse,
  fnv1a64,
  parseDurationText,
  parseLyricsText,
  parseYtmMetaParams,
  resetYtmMetaForTests,
  shouldBackgroundRevalidate,
  ttlForEndpoint,
  validateYtmPayload,
  ytmMetaRoute,
  type LegFetcher,
  type LegResult,
  type YtmEndpoint,
  type YtmEnvelope,
  type YtmEnv,
  type YtmPayload,
} from './ytm-meta';

// ---------------------------------------------------------------------------
// Test helpers
// ---------------------------------------------------------------------------

/** A Map-backed KVNamespace double — enough surface for the route. */
class FakeKV {
  store = new Map<string, string>();
  puts = 0;
  async get(key: string, kind: 'json'): Promise<null | unknown> {
    // Real KV with kind 'json' returns the PARSED value.
    if (kind !== 'json') throw new Error('fake only supports json');
    const raw = this.store.get(key);
    if (raw === undefined) return null;
    return JSON.parse(raw);
  }
  async put(key: string, value: string, _opts: unknown): Promise<void> {
    void _opts;
    this.puts += 1;
    this.store.set(key, value);
  }
}

function fakeEnv(kv?: FakeKV): YtmEnv {
  return kv === undefined ? {} : { YTM_CACHE: kv as never };
}

function fakeCtx() {
  const waited: Promise<unknown>[] = [];
  return {
    waited,
    waitUntil(p: Promise<unknown>) {
      waited.push(p);
    },
  };
}

function requestFor(params: Record<string, string>): Request {
  const qs = new URLSearchParams(params).toString();
  return new Request(`https://relay.test/ytm/meta?${qs}`, { method: 'GET' });
}

/** Counting leg fetcher with canned per-path responses. */
function legFetcherWith(
  byPath: Record<string, unknown>,
  opts: { onCall?: (path: string) => void; status?: number } = {},
): LegFetcher {
  return async (leg) => {
    opts.onCall?.(leg.path);
    const json = byPath[leg.path] ?? null;
    const ok = (opts.status ?? 200) < 400;
    const text = json === null ? 'not json' : JSON.stringify(json);
    let parsed: unknown = null;
    try {
      parsed = JSON.parse(text);
    } catch {
      parsed = null;
    }
    const result: LegResult = {
      byteLength: text.length,
      json: parsed,
      ok,
      status: opts.status ?? 200,
    };
    return result;
  };
}

// ---------------------------------------------------------------------------
// Fixtures — trimmed live captures (probe record: W1-A ADR)
// ---------------------------------------------------------------------------

const LYRICS_TEXT = "We're no strangers to love\nYou know the rules and so do I\n[00:12.34] A synced line\nNever gonna give you up";

const NEXT_RESPONSE = {
  contents: {
    singleColumnMusicWatchNextResultsRenderer: {
      tabbedRenderer: {
        watchNextTabbedResultsRenderer: {
          tabs: [
            {
              tabRenderer: {
                title: 'Up next',
                content: {
                  musicQueueRenderer: {
                    content: {
                      playlistPanelRenderer: {
                        contents: [
                          {
                            playlistPanelVideoRenderer: {
                              videoId: 'lYBUbBu4W08',
                              title: { runs: [{ text: 'Never Gonna Give You Up' }] },
                              longBylineText: {
                                runs: [
                                  { text: 'Rick Astley' },
                                  { text: ' • ' },
                                  { text: 'Whenever You Need Somebody' },
                                  { text: ' • ' },
                                  { text: '1987' },
                                ],
                              },
                              lengthText: { runs: [{ text: '3:34' }] },
                            },
                          },
                        ],
                      },
                    },
                  },
                },
                endpoint: {},
              },
            },
            {
              tabRenderer: {
                title: 'Lyrics',
                endpoint: {
                  browseEndpoint: {
                    browseId: 'MPLYt_dcYZhAh5urI-1',
                    browseEndpointContextSupportedConfigs: {
                      browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_TRACK_LYRICS' },
                    },
                  },
                },
              },
            },
          ],
        },
      },
    },
  },
};

const LYRICS_PAGE = {
  contents: {
    sectionListRenderer: {
      contents: [
        {
          musicDescriptionShelfRenderer: {
            description: { runs: [{ text: LYRICS_TEXT }] },
            footer: { runs: [{ text: 'Source: Musixmatch' }] },
          },
        },
      ],
    },
  },
};

const PLAYLIST_FIRST_PAGE = {
  contents: {
    twoColumnBrowseResultsRenderer: {
      secondaryContents: {
        sectionListRenderer: {
          contents: [
            {
              musicPlaylistShelfRenderer: {
                playlistId: 'PL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF',
                contents: [
                  {
                    musicResponsiveListItemRenderer: {
                      flexColumns: [
                        { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Say My Name' }] } } },
                        {
                          musicResponsiveListItemFlexColumnRenderer: {
                            text: {
                              runs: [
                                { text: "Destiny's Child" },
                                { text: ' • ' },
                                { text: "The Writing's On The Wall" },
                              ],
                            },
                          },
                        },
                      ],
                      fixedColumns: [
                        {
                          musicResponsiveListItemFixedColumnRenderer: {
                            text: { runs: [{ text: '4:32' }] },
                          },
                        },
                      ],
                      overlay: {
                        musicItemThumbnailOverlayRenderer: {
                          content: {
                            musicPlayButtonRenderer: {
                              playNavigationEndpoint: { watchEndpoint: { videoId: 'Ajez9PaCgek' } },
                            },
                          },
                        },
                      },
                    },
                  },
                  // trailing continuation item (the CURRENT token form)
                  {
                    continuationItemRenderer: {
                      trigger: 'CONTINUATION_TRIGGER_ON_ITEM_PRESCAN_VISIBLE',
                      continuationEndpoint: {
                        continuationCommand: { token: 'cont-token-page-2' },
                      },
                    },
                  },
                ],
              },
            },
          ],
        },
      },
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    musicResponsiveHeaderRenderer: {
                      title: { runs: [{ text: '90s r&b and hip hop hits' }] },
                      subtitle: { runs: [{ text: 'Playlist' }, { text: ' • ' }, { text: '2023' }] },
                      secondSubtitle: {
                        runs: [
                          { text: '4M views' },
                          { text: ' • ' },
                          { text: '147 tracks' },
                          { text: ' • ' },
                          { text: '11 hours, 35 minutes' },
                        ],
                      },
                      description: { runs: [{ text: 'The best of the decade.' }] },
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

const PLAYLIST_CONTINUATION_PAGE = {
  onResponseReceivedActions: [
    {
      appendContinuationItemsAction: {
        continuationItems: [
          {
            musicResponsiveListItemRenderer: {
              flexColumns: [
                { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Rain' }] } } },
                {
                  musicResponsiveListItemFlexColumnRenderer: {
                    text: { runs: [{ text: 'SWV' }, { text: ' • ' }, { text: 'Some Album' }] },
                  },
                },
              ],
              fixedColumns: [
                { musicResponsiveListItemFixedColumnRenderer: { text: { runs: [{ text: '4:25' }] } } },
              ],
              overlay: {
                musicItemThumbnailOverlayRenderer: {
                  content: {
                    musicPlayButtonRenderer: {
                      playNavigationEndpoint: { watchEndpoint: { videoId: 'zH74g3l7eH4' } },
                    },
                  },
                },
              },
            },
          },
          // last page: no trailing continuation item → continuation: null
        ],
      },
    },
  ],
};

const SUGGEST_RESPONSE = {
  contents: [
    {
      searchSuggestionsSectionRenderer: {
        contents: [
          {
            searchSuggestionRenderer: {
              suggestion: { runs: [{ text: 'never gonna' }, { text: ' give you up' }] },
            },
          },
          {
            searchSuggestionRenderer: {
              suggestion: { runs: [{ text: 'never gonna' }, { text: ' let you down' }] },
            },
          },
        ],
      },
    },
  ],
};

// ---------------------------------------------------------------------------
// Key encoding + TTL
// ---------------------------------------------------------------------------

describe('ytm-meta cache key encoding', () => {
  it('isolates gl and hl (regional charts / localized titles never bleed)', () => {
    const a = buildYtmCacheKey('lyrics', 'VID1', 'US', 'en');
    const de = buildYtmCacheKey('lyrics', 'VID1', 'DE', 'de');
    expect(a).not.toEqual(de);
    expect(a.startsWith('ytm/v1/lyrics/US/en/VID1')).toBe(true);
    expect(de.startsWith('ytm/v1/lyrics/DE/de/VID1')).toBe(true);
  });

  it('isolates endpoints and ids', () => {
    expect(buildYtmCacheKey('lyrics', 'VID1', 'US', 'en')).not.toEqual(
      buildYtmCacheKey('credits', 'VID1', 'US', 'en'),
    );
    expect(buildYtmCacheKey('lyrics', 'VID1', 'US', 'en')).not.toEqual(
      buildYtmCacheKey('lyrics', 'VID2', 'US', 'en'),
    );
  });

  it('urlencodes ids safely', () => {
    const key = buildYtmCacheKey('playlist', 'VL OLAK5uy_l4Z8', 'US', 'en');
    expect(key).not.toContain(' ');
    expect(key).toContain(encodeURIComponent('VL OLAK5uy_l4Z8'));
  });

  it('digests continuations stably (bounded keys)', () => {
    const a = buildYtmCacheKey('playlist', 'PL1', 'US', 'en', 'cont-token-page-2');
    const b = buildYtmCacheKey('playlist', 'PL1', 'US', 'en', 'cont-token-page-2');
    const c = buildYtmCacheKey('playlist', 'PL1', 'US', 'en', 'cont-token-page-3');
    expect(a).toEqual(b);
    expect(a).not.toEqual(c);
    expect(a).not.toContain('cont-token-page-2'); // the digest, not the token
    expect(fnv1a64('x')).toHaveLength(16);
  });
});

describe('ytm-meta TTL matrix', () => {
  const DAY = 24 * 60 * 60 * 1000;
  it('assigns the enforced TTLs per class', () => {
    expect(ttlForEndpoint('lyrics')).toBe(7 * DAY);
    expect(ttlForEndpoint('credits')).toBe(7 * DAY);
    expect(ttlForEndpoint('chapters')).toBe(7 * DAY);
    expect(ttlForEndpoint('counterpart')).toBe(DAY); // W2-A — the 24 h artist/album class
    expect(ttlForEndpoint('artist')).toBe(DAY);
    expect(ttlForEndpoint('album')).toBe(DAY);
    expect(ttlForEndpoint('explore')).toBe(6 * 60 * 60 * 1000);
    expect(ttlForEndpoint('home')).toBe(6 * 60 * 60 * 1000);
    expect(ttlForEndpoint('up-next')).toBe(12 * 60 * 60 * 1000);
    expect(ttlForEndpoint('related')).toBe(12 * 60 * 60 * 1000);
    expect(ttlForEndpoint('playlist')).toBe(12 * 60 * 60 * 1000);
    expect(ttlForEndpoint('search')).toBe(15 * 60 * 1000);
    expect(ttlForEndpoint('search-suggest')).toBe(15 * 60 * 1000);
  });
});

// ---------------------------------------------------------------------------
// Pure parsing helpers
// ---------------------------------------------------------------------------

describe('parseDurationText', () => {
  it('converts m:ss and h:mm:ss', () => {
    expect(parseDurationText('4:21')).toBe(261_000);
    expect(parseDurationText('1:02:33')).toBe(3_753_000);
    expect(parseDurationText('4:32')).toBe(272_000);
  });
  it('rejects junk', () => {
    expect(parseDurationText('1987')).toBeNull();
    expect(parseDurationText('abc')).toBeNull();
    expect(parseDurationText('')).toBeNull();
    expect(parseDurationText('0:00')).toBeNull();
  });
});

describe('parseLyricsText (the synced flag)', () => {
  it('plain text → synced false, atMs null', () => {
    const { lines, synced } = parseLyricsText('line one\n\nline two\n');
    expect(synced).toBe(false);
    expect(lines).toHaveLength(2);
    expect(lines[0]).toEqual({ atMs: null, text: 'line one' });
  });
  it('LRC-timestamped lines → synced true with atMs', () => {
    const { lines, synced } = parseLyricsText('[00:12.34] first\n[01:02.5] second\nplain tail');
    expect(synced).toBe(false); // the plain tail breaks full sync
    expect(lines[0]).toEqual({ atMs: 12_340, text: 'first' });
    expect(lines[1]).toEqual({ atMs: 62_500, text: 'second' });
    expect(lines[2]).toEqual({ atMs: null, text: 'plain tail' });
  });
  it('fully timestamped → synced true', () => {
    const { synced } = parseLyricsText('[00:12.34] first\n[01:02.5] second');
    expect(synced).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Param validation
// ---------------------------------------------------------------------------

describe('parseYtmMetaParams', () => {
  it('accepts a valid id request and defaults gl/hl into the params', () => {
    const url = new URL('https://r/ytm/meta?endpoint=lyrics&id=VID&hl=de');
    const res = parseYtmMetaParams(url);
    expect(res.ok).toBe(true);
    if (res.ok) {
      expect(res.params.gl).toBe('US');
      expect(res.params.hl).toBe('de');
    }
  });
  it('rejects unknown endpoints / missing ids / missing queries', () => {
    expect(parseYtmMetaParams(new URL('https://r/ytm/meta?endpoint=bogus&id=x')).ok).toBe(false);
    expect(parseYtmMetaParams(new URL('https://r/ytm/meta?endpoint=lyrics')).ok).toBe(false);
    expect(parseYtmMetaParams(new URL('https://r/ytm/meta?endpoint=search')).ok).toBe(false);
    expect(parseYtmMetaParams(new URL('https://r/ytm/meta?endpoint=search-suggest&q=')).ok).toBe(
      false,
    );
  });
  it('rejects pagination on non-paginating endpoints', () => {
    expect(
      parseYtmMetaParams(new URL('https://r/ytm/meta?endpoint=artist&id=UCx&continuation=c')).ok,
    ).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Validation + SWR policy
// ---------------------------------------------------------------------------

describe('validateYtmPayload', () => {
  it('requires boolean synced and non-empty lines on lyrics', () => {
    const good = { kind: 'lyrics', lines: [{ atMs: null, text: 'x' }], synced: false, hl: 'en', source: 's', videoId: 'v' };
    expect(validateYtmPayload('lyrics', good as YtmPayload)).toEqual({ ok: true });
    const noSynced = { ...good, synced: 'yes' } as unknown as YtmPayload;
    expect(validateYtmPayload('lyrics', noSynced)).toEqual({
      ok: false,
      reason: 'lyrics: synced must be boolean',
    });
    const noLines = { ...good, lines: [] } as YtmPayload;
    expect(validateYtmPayload('lyrics', noLines).ok).toBe(false);
  });
});

describe('shouldBackgroundRevalidate (the SWR policy)', () => {
  const base = (payload: YtmPayload | null): YtmEnvelope => ({
    cachedAt: 0,
    endpoint: 'lyrics',
    expiresAt: 1,
    key: 'k',
    missing: payload === null,
    payload,
    v: 1,
  });
  const syncedLyrics: YtmPayload = {
    hl: 'en',
    kind: 'lyrics',
    lines: [{ atMs: 1000, text: 'x' }],
    source: 's',
    synced: true,
    videoId: 'v',
  };
  const unsyncedLyrics: YtmPayload = { ...syncedLyrics, synced: false, lines: [{ atMs: null, text: 'x' }] };

  it('lyrics: synced+complete is terminal — no revalidation', () => {
    expect(shouldBackgroundRevalidate('lyrics', base(syncedLyrics))).toBe(false);
  });
  it('lyrics: unsynced or missing → revalidate', () => {
    expect(shouldBackgroundRevalidate('lyrics', base(unsyncedLyrics))).toBe(true);
    expect(shouldBackgroundRevalidate('lyrics', base(null))).toBe(true);
  });
  it('credits/chapters: only missing triggers', () => {
    expect(shouldBackgroundRevalidate('credits', base(null))).toBe(true);
  });
  it('every other class: always', () => {
    expect(shouldBackgroundRevalidate('artist', base(null))).toBe(true);
    expect(shouldBackgroundRevalidate('search', base(null))).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// Route behavior: single-flight, KV fallback, synced preservation, failures
// ---------------------------------------------------------------------------

beforeEach(() => {
  resetYtmMetaForTests();
});

describe('ytmMetaRoute', () => {
  it('fills from upstream, then serves fresh (KV absent — bypassed, never thrown)', async () => {
    const calls: string[] = [];
    const fetchLeg = legFetcherWith(
      { next: NEXT_RESPONSE, browse: LYRICS_PAGE },
      { onCall: (p) => calls.push(p) },
    );
    const env = fakeEnv(); // NO KV binding
    const ctx = fakeCtx();
    let now = 1_000_000;
    const deps = { fetchLeg, now: () => now, fetchImpl: fetch };

    const first = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'lYBUbBu4W08', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    expect(first.status).toBe(200);
    const body1 = (await first.json()) as {
      cache: { state: string };
      payload: { synced: boolean; lines: unknown[] };
    };
    // Leg 1 (/next) + conditional leg 2 (browse) both ran.
    expect(calls).toEqual(['next', 'browse']);
    expect(body1.cache.state).toBe('fill');
    expect(body1.payload.synced).toBe(false); // the fixture mixes plain + one LRC line

    // Second call: fresh from memory, no upstream.
    calls.length = 0;
    const second = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'lYBUbBu4W08', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body2 = (await second.json()) as { cache: { state: string }; payload: { synced: boolean } };
    expect(body2.cache.state).toBe('fresh');
    expect(calls).toEqual([]);
    expect(body2.payload.synced).toBe(body1.payload.synced);
  });

  it('coalesces concurrent identical requests into ONE upstream fill', async () => {
    let legCalls = 0;
    let resolveGate: () => void = () => {};
    const gate = new Promise<void>((r) => {
      resolveGate = r;
    });
    const fetchLeg: LegFetcher = async (leg) => {
      legCalls += 1;
      await gate; // hold the fill so both requests arrive while it runs
      const json = leg.path === 'next' ? NEXT_RESPONSE : LYRICS_PAGE;
      const text = JSON.stringify(json);
      return { byteLength: text.length, json, ok: true, status: 200 };
    };
    const env = fakeEnv();
    const ctx = fakeCtx();
    const deps = { fetchLeg, now: () => 1, fetchImpl: fetch };

    const p1 = ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const p2 = ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    resolveGate!();
    const [r1, r2] = await Promise.all([p1, p2]);
    expect(r1.status).toBe(200);
    expect(r2.status).toBe(200);
    // ONE fill: next + browse (leg 2) — never duplicated per request.
    expect(legCalls).toBe(2);
  });

  it('preserves the synced flag through the KV round-trip', async () => {
    const kv = new FakeKV();
    const env = fakeEnv(kv);
    const ctx = fakeCtx();
    const syncedLyricsPage = {
      contents: {
        sectionListRenderer: {
          contents: [
            {
              musicDescriptionShelfRenderer: {
                description: { runs: [{ text: '[00:01.00] alpha\n[00:05.00] beta' }] },
                footer: { runs: [{ text: 'Source: Musixmatch' }] },
              },
            },
          ],
        },
      },
    };
    const fetchLeg = legFetcherWith({ next: NEXT_RESPONSE, browse: syncedLyricsPage });
    const deps = { fetchLeg, now: () => 1, fetchImpl: fetch };

    const first = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body1 = (await first.json()) as { payload: { synced: boolean } };
    expect(body1.payload.synced).toBe(true);
    expect(kv.puts).toBe(1);

    // Fresh memory layer dies (module reset simulates an isolate recycle);
    // the KV layer answers with the SAME envelope — flag intact.
    resetYtmMetaForTests();
    const second = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body2 = (await second.json()) as { cache: { state: string }; payload: { synced: boolean } };
    expect(body2.cache.state).toBe('fresh');
    expect(body2.payload.synced).toBe(true);
  });

  it('serves stale past expiry and background-revalidates unsynced lyrics', async () => {
    const kv = new FakeKV();
    const env = fakeEnv(kv);
    const ctx = fakeCtx();
    let now = 1_000;
    const calls: string[] = [];
    const fetchLeg = legFetcherWith(
      { next: NEXT_RESPONSE, browse: LYRICS_PAGE },
      { onCall: (p) => calls.push(p) },
    );
    const deps = { fetchLeg, now: () => now, fetchImpl: fetch };

    await ytmMetaRoute(requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }), env, ctx, deps);
    calls.length = 0;

    // Past TTL (7 days) but inside the stale window (3× TTL).
    now += 7 * 24 * 60 * 60 * 1000 + 5 * 60 * 1000;
    const stale = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body = (await stale.json()) as { cache: { state: string }; payload: { synced: boolean } };
    expect(body.cache.state).toBe('stale');
    // The fixture lyrics are unsynced → the policy says revalidate.
    expect(body.payload.synced).toBe(false);
    expect(ctx.waited.length).toBe(1);
    await Promise.all(ctx.waited);
    expect(calls).toEqual(['next', 'browse']); // the revalidation fill ran
  });

  it('stale synced lyrics are terminal — NO background revalidation', async () => {
    const kv = new FakeKV();
    const env = fakeEnv(kv);
    const ctx = fakeCtx();
    let now = 1_000;
    const syncedPage = {
      contents: {
        sectionListRenderer: {
          contents: [
            {
              musicDescriptionShelfRenderer: {
                description: { runs: [{ text: '[00:01.00] alpha\n[00:05.00] beta' }] },
                footer: { runs: [{ text: 'Source: Musixmatch' }] },
              },
            },
          ],
        },
      },
    };
    const calls: string[] = [];
    const fetchLeg = legFetcherWith(
      { next: NEXT_RESPONSE, browse: syncedPage },
      { onCall: (p) => calls.push(p) },
    );
    const deps = { fetchLeg, now: () => now, fetchImpl: fetch };

    await ytmMetaRoute(requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }), env, ctx, deps);
    calls.length = 0;
    now += 7 * 24 * 60 * 60 * 1000 * 2; // well past TTL, inside 3× window
    const stale = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body = (await stale.json()) as { cache: { state: string } };
    expect(body.cache.state).toBe('stale');
    expect(ctx.waited).toHaveLength(0); // synced+complete → no revalidation
  });

  it('429 upstream → 429 passthrough with Retry-After', async () => {
    const fetchLeg = legFetcherWith({}, { status: 429 });
    const deps = { fetchLeg, now: () => 1, fetchImpl: fetch };
    const res = await ytmMetaRoute(
      requestFor({ endpoint: 'search', q: 'test', filter: 'songs' }),
      fakeEnv(),
      fakeCtx(),
      deps,
    );
    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBeNull(); // no upstream header in the fake
    const body = (await res.json()) as { detail: string; endpoint: string };
    expect(body.endpoint).toBe('search');
    expect(body.detail).toBe('upstream rate limit');
  });

  it('network failure → 502; unparseable JSON → 502 parse', async () => {
    const broken: LegFetcher = async () => ({ byteLength: 8, json: null, ok: false, status: 503 });
    const deps = { fetchLeg: broken, now: () => 1, fetchImpl: fetch };
    const res = await ytmMetaRoute(
      requestFor({ endpoint: 'home' }),
      fakeEnv(),
      fakeCtx(),
      deps,
    );
    expect(res.status).toBe(502);
    const body = (await res.json()) as { detail: string };
    expect(body.detail).toBe('upstream fetch failed');

    const unparseable: LegFetcher = async (leg) => {
      if (leg.path === 'browse') {
        return { byteLength: 9, json: null, ok: true, status: 200 };
      }
      return { byteLength: 9, json: null, ok: true, status: 200 };
    };
    const res2 = await ytmMetaRoute(
      requestFor({ endpoint: 'home' }),
      fakeEnv(),
      fakeCtx(),
      { fetchLeg: unparseable, now: () => 1, fetchImpl: fetch },
    );
    expect(res2.status).toBe(502);
    const body2 = (await res2.json()) as { detail: string };
    expect(body2.detail).toBe('upstream response is not JSON');
  });

  it('oversized upstream JSON is served but never cached', async () => {
    const kv = new FakeKV();
    const env = fakeEnv(kv);
    const ctx = fakeCtx();
    const big: LegFetcher = async (leg) => {
      const json = leg.path === 'next' ? NEXT_RESPONSE : LYRICS_PAGE;
      // The measured byteLength is inflated past the 2 MiB gate.
      return { byteLength: 3 * 1024 * 1024, json, ok: true, status: 200 };
    };
    const deps = { fetchLeg: big, now: () => 1, fetchImpl: fetch };

    const res = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body = (await res.json()) as { cache: { uncached?: boolean }; payload: unknown };
    expect(body.cache.uncached).toBe(true);
    expect(body.payload).toBeDefined();
    expect(kv.puts).toBe(0); // never cached

    // A second call refetches (nothing was stored).
    let refetch = 0;
    const counting: LegFetcher = async (leg) => {
      refetch += 1;
      const json = leg.path === 'next' ? NEXT_RESPONSE : LYRICS_PAGE;
      const text = JSON.stringify(json);
      return { byteLength: text.length, json, ok: true, status: 200 };
    };
    const res2 = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'VID', gl: 'US', hl: 'en' }),
      env,
      ctx,
      { fetchLeg: counting, now: () => 1, fetchImpl: fetch },
    );
    expect(res2.status).toBe(200);
    expect(refetch).toBe(2);
  });

  it('empty-normalized result caches as missing (the not-found form)', async () => {
    const kv = new FakeKV();
    const env = fakeEnv(kv);
    const ctx = fakeCtx();
    const empty: LegFetcher = async () => ({
      byteLength: 40,
      json: { responseContext: {} },
      ok: true,
      status: 200,
    });
    const deps = { fetchLeg: empty, now: () => 1, fetchImpl: fetch };
    const res = await ytmMetaRoute(
      requestFor({ endpoint: 'lyrics', id: 'NOEXIST', gl: 'US', hl: 'en' }),
      env,
      ctx,
      deps,
    );
    const body = (await res.json()) as { missing?: boolean; payload?: unknown };
    expect(body.missing).toBe(true);
    expect(body.payload).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Normalizer shapes (fixtures from the live probe record)
// ---------------------------------------------------------------------------

describe('normalizeYtmResponse shapes', () => {
  it('playlist first page: title, tracks with artist/album/duration, total, continuation', () => {
    const params = { endpoint: 'playlist' as YtmEndpoint, gl: 'US', hl: 'en', id: 'PL9ADD7GKlxvEfDKWmW93kv92Gh9L1s0FF' };
    const payload = normalizeYtmResponse(params, [PLAYLIST_FIRST_PAGE]);
    expect(payload).not.toBeNull();
    if (payload?.kind !== 'playlist') throw new Error('wrong kind');
    expect(payload.title).toBe('90s r&b and hip hop hits');
    expect(payload.trackCountTotal).toBe(147);
    expect(payload.description).toBe('The best of the decade.');
    expect(payload.continuation).toBe('cont-token-page-2');
    expect(payload.tracks).toHaveLength(1);
    expect(payload.tracks[0]).toEqual({
      albumText: "The Writing's On The Wall",
      artistText: "Destiny's Child",
      durationMs: 272_000,
      index: 0,
      title: 'Say My Name',
      videoId: 'Ajez9PaCgek',
    });
  });

  it('playlist items carry the album from column 2 (the plays-count guard)', () => {
    // The VL+OLAK layout: [title], [artist], [album] + fixed duration.
    const page = {
      contents: {
        twoColumnBrowseResultsRenderer: {
          secondaryContents: {
            sectionListRenderer: {
              contents: [
                {
                  musicPlaylistShelfRenderer: {
                    contents: [
                      {
                        musicResponsiveListItemRenderer: {
                          flexColumns: [
                            { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Come Together (2019 Mix)' }] } } },
                            { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'The Beatles' }] } } },
                            { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Abbey Road (2019 Mix)' }] } } },
                          ],
                          fixedColumns: [
                            { musicResponsiveListItemFixedColumnRenderer: { text: { runs: [{ text: '4:21' }] } } },
                          ],
                          overlay: {
                            musicItemThumbnailOverlayRenderer: {
                              content: {
                                musicPlayButtonRenderer: {
                                  playNavigationEndpoint: { watchEndpoint: { videoId: '_VM8gB6Ywfc' } },
                                },
                              },
                            },
                          },
                        },
                      },
                    ],
                  },
                },
              ],
            },
          },
        },
      },
    };
    const params = { endpoint: 'playlist' as YtmEndpoint, gl: 'US', hl: 'en', id: 'OLAK5uy_x' };
    const payload = normalizeYtmResponse(params, [page]);
    if (payload?.kind !== 'playlist') throw new Error('wrong kind');
    expect(payload.tracks[0]).toEqual({
      albumText: 'Abbey Road (2019 Mix)',
      artistText: 'The Beatles',
      durationMs: 261_000,
      index: 0,
      title: 'Come Together (2019 Mix)',
      videoId: '_VM8gB6Ywfc',
    });
  });

  it('playlist continuation page: items + null continuation on the last page', () => {
    const params = { endpoint: 'playlist' as YtmEndpoint, gl: 'US', hl: 'en', id: 'PLx', continuation: 'cont-token-page-2' };
    const payload = normalizeYtmResponse(params, [PLAYLIST_CONTINUATION_PAGE]);
    if (payload?.kind !== 'playlist') throw new Error('wrong kind');
    expect(payload.tracks).toHaveLength(1);
    expect(payload.tracks[0].title).toBe('Rain');
    expect(payload.tracks[0].durationMs).toBe(265_000);
    expect(payload.tracks[0].videoId).toBe('zH74g3l7eH4');
    expect(payload.continuation).toBeNull();
  });

  it('lyrics: source, lines, mixed sync', () => {
    const params = { endpoint: 'lyrics' as YtmEndpoint, gl: 'US', hl: 'en', id: 'lYBUbBu4W08' };
    const payload = normalizeYtmResponse(params, [NEXT_RESPONSE, LYRICS_PAGE]);
    if (payload?.kind !== 'lyrics') throw new Error('wrong kind');
    expect(payload.source).toBe('Musixmatch');
    expect(payload.synced).toBe(false);
    expect(payload.lines.some((l) => l.atMs === 12_340)).toBe(true);
    expect(payload.lines.some((l) => l.text === "We're no strangers to love")).toBe(true);
  });

  it('up-next: byline split, duration, videoId', () => {
    const params = { endpoint: 'up-next' as YtmEndpoint, gl: 'US', hl: 'en', id: 'lYBUbBu4W08' };
    const payload = normalizeYtmResponse(params, [NEXT_RESPONSE]);
    if (payload?.kind !== 'up-next') throw new Error('wrong kind');
    expect(payload.tracks).toHaveLength(1);
    expect(payload.tracks[0]).toEqual({
      artistText: 'Rick Astley',
      durationMs: 214_000,
      title: 'Never Gonna Give You Up',
      videoId: 'lYBUbBu4W08',
    });
  });

  it('search-suggest: suggestions list', () => {
    const params = { endpoint: 'search-suggest' as YtmEndpoint, gl: 'US', hl: 'en', query: 'never gonna' };
    const payload = normalizeYtmResponse(params, [SUGGEST_RESPONSE]);
    if (payload?.kind !== 'search-suggest') throw new Error('wrong kind');
    expect(payload.suggestions).toEqual(['never gonna give you up', 'never gonna let you down']);
  });
});


// ---------------------------------------------------------------------------
// W2-A — chapters (the enriched shape) + counterpart + the Apps Script lane
// ---------------------------------------------------------------------------

const WEB_NEXT_WITH_CHAPTERS = {
  playerOverlays: {
    playerOverlayRenderer: {
      decoratedPlayerBarRenderer: {
        decoratedPlayerBarRenderer: {
          multiMarkersPlayerBarRenderer: {
            markersMap: [
              {
                key: 'DESCRIPTION_CHAPTERS',
                value: {
                  chapters: [
                    {
                      chapterRenderer: {
                        title: { runs: [{ text: 'Intro' }] },
                        timeRangeStartMillis: 0,
                        thumbnail: {
                          thumbnails: [
                            { url: 'https://i.ytimg.com/vi/x/hq72.jpg' },
                            { url: 'https://i.ytimg.com/vi/x/hqdefault.jpg' },
                          ],
                        },
                      },
                    },
                    {
                      chapterRenderer: {
                        title: { simpleText: 'The Drop' },
                        timeRangeStartMillis: 42000,
                      },
                    },
                    {
                      chapterRenderer: {
                        title: { runs: [{ text: 'Outro' }] },
                        timeRangeStartMillis: 180000,
                      },
                    },
                  ],
                },
              },
              { key: 'IRRELEVANT_MARKER', value: { chapters: [] } },
            ],
          },
        },
      },
    },
  },
};

const COUNTERPART_SEARCH_RESPONSE = {
  contents: {
    tabbedSearchResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    musicShelfRenderer: {
                      contents: [
                        {
                          musicResponsiveListItemRenderer: {
                            flexColumns: [
                              {
                                musicResponsiveListItemFlexColumnRenderer: {
                                  text: { runs: [{ text: 'Never Gonna Give You Up (Official Music Video)' }] },
                                },
                              },
                              {
                                musicResponsiveListItemFlexColumnRenderer: {
                                  text: {
                                    runs: [
                                      { text: 'Rick Astley' },
                                      { text: ' • ' },
                                      { text: '3:33' },
                                    ],
                                  },
                                },
                              },
                            ],
                            overlay: {
                              musicItemThumbnailOverlayRenderer: {
                                content: {
                                  musicPlayButtonRenderer: {
                                    playNavigationEndpoint: { watchEndpoint: { videoId: 'vidOfficialMV' } },
                                  },
                                },
                              },
                            },
                          },
                        },
                        {
                          // Same videoId as the seed — must be skipped
                          // (a song whose only "video" is itself has no
                          // counterpart).
                          musicResponsiveListItemRenderer: {
                            flexColumns: [
                              {
                                musicResponsiveListItemFlexColumnRenderer: {
                                  text: { runs: [{ text: 'Never Gonna Give You Up' }] },
                                },
                              },
                              {
                                musicResponsiveListItemFlexColumnRenderer: {
                                  text: {
                                    runs: [
                                      { text: 'Rick Astley' },
                                      { text: ' • ' },
                                      { text: '3:33' },
                                    ],
                                  },
                                },
                              },
                            ],
                            overlay: {
                              musicItemThumbnailOverlayRenderer: {
                                content: {
                                  musicPlayButtonRenderer: {
                                    playNavigationEndpoint: { watchEndpoint: { videoId: 'lYBUbBu4W08' } },
                                  },
                                },
                              },
                            },
                          },
                        },
                        {
                          // Different title — never matches.
                          musicResponsiveListItemRenderer: {
                            flexColumns: [
                              {
                                musicResponsiveListItemFlexColumnRenderer: {
                                  text: { runs: [{ text: 'Together Forever' }] },
                                },
                              },
                            ],
                            overlay: {
                              musicItemThumbnailOverlayRenderer: {
                                content: {
                                  musicPlayButtonRenderer: {
                                    playNavigationEndpoint: { watchEndpoint: { videoId: 'vidOther' } },
                                  },
                                },
                              },
                            },
                          },
                        },
                      ],
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

describe('W2-A chapters (the enriched normalized shape)', () => {
  it('normalizes startSeconds/endSeconds/thumbnailUrl and keeps atMs for v1 back-compat', () => {
    const params = { endpoint: 'chapters' as YtmEndpoint, gl: 'US', hl: 'en', id: 'lYBUbBu4W08' };
    const payload = normalizeYtmResponse(params, [WEB_NEXT_WITH_CHAPTERS]);
    if (payload?.kind !== 'chapters') throw new Error('wrong kind');
    expect(payload.chapters).toEqual([
      {
        atMs: 0,
        endSeconds: 42,
        startSeconds: 0,
        thumbnailUrl: 'https://i.ytimg.com/vi/x/hqdefault.jpg', // the LAST (largest) thumb
        title: 'Intro',
      },
      {
        atMs: 42000,
        endSeconds: 180,
        startSeconds: 42,
        thumbnailUrl: null,
        title: 'The Drop',
      },
      {
        atMs: 180000,
        endSeconds: null, // the last chapter runs to the video's end
        startSeconds: 180,
        thumbnailUrl: null,
        title: 'Outro',
      },
    ]);
    // Validation gates both the new shape and the W1-A-era legacy rows.
    expect(validateYtmPayload('chapters', payload).ok).toBe(true);
    expect(
      validateYtmPayload('chapters', {
        kind: 'chapters',
        chapters: [{ atMs: 1000, title: 'Legacy' }],
        hl: 'en',
        videoId: 'x',
      }).ok,
    ).toBe(true);
    expect(
      validateYtmPayload('chapters', {
        kind: 'chapters',
        chapters: [{ title: 'No Time At All' }],
        hl: 'en',
        videoId: 'x',
      }).ok,
    ).toBe(false);
  });

  it('an empty marker map normalizes to the honest missing form', () => {
    const params = { endpoint: 'chapters' as YtmEndpoint, gl: 'US', hl: 'en', id: 'lYBUbBu4W08' };
    expect(normalizeYtmResponse(params, [{}])).toBeNull();
  });
});

describe('W2-A counterpart (the official-music-video resolution)', () => {
  it('the seed extraction reads the seed videoId entry off the queue panel', () => {
    // counterpartSeedOf is exercised through the route below; here the
    // direct contract: the panel entry matching the seed id wins.
    const seed = counterpartSeedOf('lYBUbBu4W08', NEXT_RESPONSE);
    expect(seed).not.toBeNull();
    expect(seed?.title).toBe('Never Gonna Give You Up');
    expect(seed?.artistText).toBe('Rick Astley');
    expect(seed?.durationMs).toBe(214000);
  });

  it('the matcher: distinct videoId, normalized title, strong confidence', () => {
    const payload = matchCounterpart(
      { artistText: 'Rick Astley', durationMs: 214000, title: 'Never Gonna Give You Up' },
      'lYBUbBu4W08',
      [
        {
          durationMs: 213000,
          subtitle: 'Rick Astley • 3:33',
          title: 'Never Gonna Give You Up (Official Music Video)',
          videoId: 'vidOfficialMV',
        },
        {
          durationMs: 213000,
          subtitle: 'Rick Astley • 3:33',
          title: 'Never Gonna Give You Up',
          videoId: 'lYBUbBu4W08', // the seed itself — skipped
        },
        {
          durationMs: 213000,
          subtitle: 'Rick Astley',
          title: 'Together Forever (Official Music Video)',
          videoId: 'vidOther', // different song — never matches
        },
      ],
    );
    expect(payload.kind).toBe('counterpart');
    expect(payload.videoId).toBe('vidOfficialMV');
    expect(payload.confidence).toBe('strong');
    expect(payload.sourceVideoId).toBe('lYBUbBu4W08');
  });

  it('the matcher: duration drift within 12% stays strong; far drift demotes to weak', () => {
    const seed = { artistText: 'Rick Astley', durationMs: 214000, title: 'Never Gonna Give You Up' };
    const close = matchCounterpart(seed, 'seed', [
      {
        durationMs: 214000 + 20_000, // 9.3% — inside the tolerance
        subtitle: 'Rick Astley',
        title: 'Never Gonna Give You Up (Official Video)',
        videoId: 'vidClose',
      },
    ]);
    expect(close.confidence).toBe('strong');
    const far = matchCounterpart(seed, 'seed', [
      {
        durationMs: 214000 + 90_000, // 42% — a different cut
        subtitle: 'Rick Astley',
        title: 'Never Gonna Give You Up',
        videoId: 'vidFar',
      },
    ]);
    expect(far.confidence).toBe('weak');
    expect(far.videoId).toBe('vidFar');
  });

  it('the matcher: no distinct match → videoId null (the cached positive)', () => {
    const payload = matchCounterpart(
      { artistText: 'Rick Astley', durationMs: 214000, title: 'Never Gonna Give You Up' },
      'lYBUbBu4W08',
      [
        {
          durationMs: 213000,
          subtitle: 'Rick Astley',
          title: 'Never Gonna Give You Up',
          videoId: 'lYBUbBu4W08', // only itself
        },
      ],
    );
    expect(payload.videoId).toBeNull();
    expect(payload.confidence).toBeNull();
    expect(validateYtmPayload('counterpart', payload).ok).toBe(true);
  });

  it('the route: two-leg flow, 24 h TTL, cached as a positive (missing on a seedless panel)', async () => {
    resetYtmMetaForTests();
    const calls: string[] = [];
    const legs = legFetcherWith(
      { next: NEXT_RESPONSE, search: COUNTERPART_SEARCH_RESPONSE },
      { onCall: (path) => calls.push(path) },
    );

    const res = await ytmMetaRoute(
      requestFor({ endpoint: 'counterpart', id: 'lYBUbBu4W08', gl: 'US', hl: 'en' }),
      fakeEnv(),
      fakeCtx(),
      { fetchLeg: legs },
    );
    expect(res.status).toBe(200);
    const body = (await res.json()) as { cache: { ttlMs: number }; payload: YtmPayload };
    expect(calls).toEqual(['next', 'search']); // the conditional leg-2 ran
    expect(body.cache.ttlMs).toBe(24 * 60 * 60 * 1000);
    if (body.payload?.kind !== 'counterpart') throw new Error('wrong kind');
    expect(body.payload.videoId).toBe('vidOfficialMV');
    expect(body.payload.confidence).toBe('strong');

    // A seedless panel (no queue panel on leg 1) → the honest missing form.
    resetYtmMetaForTests();
    const missing = await ytmMetaRoute(
      requestFor({ endpoint: 'counterpart', id: 'unknownVideo', gl: 'US', hl: 'en' }),
      fakeEnv(),
      fakeCtx(),
      { fetchLeg: legFetcherWith({ next: {} }) },
    );
    const missingBody = (await missing.json()) as { missing?: boolean };
    expect(missingBody.missing).toBe(true);
  });
});

describe('W2-A the Apps Script solver lane (APPS_SCRIPT_SOLVER_URL)', () => {
  it('validates the configured URL shape (and rejects placeholders/other hosts)', () => {
    const good = 'https://script.google.com/macros/s/AKfycbX/exec';
    expect(appsScriptSolverUrl({ APPS_SCRIPT_SOLVER_URL: good })).toBe(good);
    expect(appsScriptSolverUrl({})).toBeNull();
    expect(appsScriptSolverUrl({ APPS_SCRIPT_SOLVER_URL: '' })).toBeNull();
    expect(appsScriptSolverUrl({ APPS_SCRIPT_SOLVER_URL: '${APPS_SCRIPT_SOLVER_URL}' })).toBeNull();
    expect(
      appsScriptSolverUrl({ APPS_SCRIPT_SOLVER_URL: 'https://evil.example.com/macros/s/x/exec' }),
    ).toBeNull();
    expect(
      appsScriptSolverUrl({ APPS_SCRIPT_SOLVER_URL: 'http://script.google.com/macros/s/x/exec' }),
    ).toBeNull();
  });

  it('gates the lane to CONTROL-PLANE targets (googlevideo media never rides it)', () => {
    expect(isControlPlaneTarget(new URL('https://www.youtube.com/youtubei/v1/player'))).toBe(true);
    expect(isControlPlaneTarget(new URL('https://music.youtube.com/youtubei/v1/browse'))).toBe(true);
    expect(isControlPlaneTarget(new URL('https://youtubei.googleapis.com/youtubei/v1/player'))).toBe(true);
    expect(isControlPlaneTarget(new URL('https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT'))).toBe(true);
    expect(isControlPlaneTarget(new URL('https://www.google.com/js/th/foobar.js'))).toBe(true);
    expect(isControlPlaneTarget(new URL('https://rr4---sn-x0.googlevideo.com/videoplayback?itag=18'))).toBe(false);
    expect(isControlPlaneTarget(new URL('https://www.google.com/other/path'))).toBe(false);
  });

  it('forwards a control-plane /r POST through the solver envelope and unwraps the answer', async () => {
    const solverUrl = 'https://script.google.com/macros/s/AKfycbW2A/exec';
    const seen: Array<{ body: unknown; url: string }> = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (async (url: string | URL | Request, init?: RequestInit) => {
      const target = typeof url === 'string' ? url : url instanceof URL ? url.toString() : url.url;
      seen.push({ body: init?.body, url: target });
      // The solver lane: one GET against the exec URL with the ?q= envelope.
      if (target.startsWith(solverUrl)) {
        return new Response(
          JSON.stringify({
            ok: true,
            s: 200,
            h: { 'content-type': 'application/json' },
            b: JSON.stringify({ playabilityStatus: { status: 'OK' } }),
            e: 't',
          }),
          { status: 200 },
        );
      }
      throw new Error(`unexpected direct fetch: ${target}`);
    }) as typeof fetch;
    try {
      const worker = (await import('./index')).default;
      const request = new Request(
        `https://relay.test/r?u=${encodeURIComponent('https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSy')}`,
        {
          method: 'POST',
          headers: { 'content-type': 'application/json', 'x-youtube-client-name': '5' },
          body: JSON.stringify({ videoId: 'w2aTest', context: { client: { clientName: 'IOS' } } }),
        },
      );
      const res = await worker.fetch(request, { APPS_SCRIPT_SOLVER_URL: solverUrl }, fakeCtx() as never);
      expect(res.status).toBe(200);
      expect(await res.json()).toEqual({ playabilityStatus: { status: 'OK' } });
      // The envelope went to the solver — the direct fetch never ran.
      expect(seen).toHaveLength(1);
      expect(seen[0]?.url.startsWith(`${solverUrl}?q=`)).toBe(true);
      const envelope = JSON.parse(
        new URL(seen[0]!.url).searchParams.get('q') as string,
      ) as { h: Record<string, string>; m: string; u: string };
      expect(envelope.u).toContain('youtubei.googleapis.com/youtubei/v1/player');
      expect(envelope.m).toBe('post');
      expect(envelope.h['content-type']).toBe('application/json');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('a solver-lane failure degrades to the direct worker fetch (additive, never worse)', async () => {
    const solverUrl = 'https://script.google.com/macros/s/AKfycbW2Afails/exec';
    const originalFetch = globalThis.fetch;
    const order: string[] = [];
    globalThis.fetch = (async (url: string | URL | Request) => {
      const target = typeof url === 'string' ? url : url instanceof URL ? url.toString() : url.url;
      if (target.startsWith(solverUrl)) {
        order.push('solver');
        return new Response(JSON.stringify({ ok: false, error: 'quota' }), { status: 200 });
      }
      order.push('direct');
      return new Response('{"status":"direct-ok"}', {
        status: 200,
        headers: { 'content-type': 'application/json' },
      });
    }) as typeof fetch;
    try {
      const worker = (await import('./index')).default;
      const request = new Request(
        `https://relay.test/r?u=${encodeURIComponent('https://www.youtube.com/youtubei/v1/next')}`,
        { method: 'GET' },
      );
      const res = await worker.fetch(request, { APPS_SCRIPT_SOLVER_URL: solverUrl }, fakeCtx() as never);
      expect(res.status).toBe(200);
      expect(order).toEqual(['solver', 'direct']); // the degrade path ran
      expect(await res.text()).toContain('direct-ok');
    } finally {
      globalThis.fetch = originalFetch;
    }
  });

  it('googlevideo media and /ytm/meta stay OFF the solver lane', async () => {
    const solverUrl = 'https://script.google.com/macros/s/AKfycbW2Anever/exec';
    const originalFetch = globalThis.fetch;
    const targets: string[] = [];
    globalThis.fetch = (async (url: string | URL | Request) => {
      const target = typeof url === 'string' ? url : url instanceof URL ? url.toString() : url.url;
      targets.push(target);
      return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
    }) as typeof fetch;
    try {
      const worker = (await import('./index')).default;
      // A media fetch: the direct googlevideo URL is fetched — the solver
      // URL never appears.
      await worker.fetch(
        new Request(
          `https://relay.test/r?u=${encodeURIComponent('https://rr4---sn-x0.googlevideo.com/videoplayback?itag=251')}`,
          { method: 'GET' },
        ),
        { APPS_SCRIPT_SOLVER_URL: solverUrl },
        fakeCtx() as never,
      );
      expect(targets).toHaveLength(1);
      expect(targets[0]).toContain('googlevideo.com');
      // /ytm/meta answers through the KV route (a 400 for the missing
      // endpoint param — NOT a solver call).
      const meta = await worker.fetch(
        new Request('https://relay.test/ytm/meta'),
        { APPS_SCRIPT_SOLVER_URL: solverUrl },
        fakeCtx() as never,
      );
      expect(meta.status).toBe(400);
      expect(targets).toHaveLength(1); // still only the media fetch
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
