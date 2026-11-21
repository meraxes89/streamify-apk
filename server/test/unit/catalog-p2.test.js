import test from 'node:test';
import assert from 'node:assert/strict';
import { CatalogService } from '../../src/slices/catalog/service.js';

class MockYtmClient {
  constructor() {
    this.homeCalls = 0;
    this.searchCalls = 0;
  }

  async getHome({ gl = 'IN' } = {}) {
    this.homeCalls++;
    return {
      contents: {
        singleColumnBrowseResultsRenderer: {
          tabs: [
            {
              tabRenderer: {
                content: {
                  sectionListRenderer: {
                    contents: [
                      {
                        musicCarouselShelfRenderer: {
                          header: {
                            musicCarouselShelfBasicHeaderRenderer: {
                              title: { runs: [{ text: 'Quick picks' }] },
                            },
                          },
                          contents: [
                            {
                              musicResponsiveListItemRenderer: {
                                flexColumns: [
                                  {
                                    musicResponsiveListItemFlexColumnRenderer: {
                                      text: { runs: [{ text: 'Mock Song 1' }] },
                                    },
                                  },
                                ],
                                navigationEndpoint: {
                                  watchEndpoint: { videoId: 'mock123' },
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
  }

  async searchSongs({ query, gl = 'IN', signal = null } = {}) {
    this.searchCalls++;
    if (signal?.aborted) {
      throw new Error('Search aborted');
    }
    return {
      contents: {
        tabbedSearchResultsRenderer: {
          tabs: [
            {
              tabRenderer: {
                content: {
                  sectionListRenderer: {
                    contents: [
                      {
                        musicCardShelfRenderer: null,
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
  }

  async getSuggestions({ query }) {
    return [`${query} official`, `${query} lyrics`];
  }
}

test('P2 SWR: getHome serves pre-serialized buffer instantly and supports SWR background revalidation', async () => {
  const mockClient = new MockYtmClient();
  const service = new CatalogService({ ytmClient: mockClient });

  // Initial fetch (cold)
  const cold = await service.getHome({ gl: 'US' });
  assert.equal(mockClient.homeCalls, 1);
  assert.ok(cold.rawBuf instanceof Buffer);
  assert.ok(cold.data.items.length > 0);

  // Subsequent fetch (warm): served from cache without calling client again
  const warm = await service.getHome({ gl: 'US' });
  assert.equal(mockClient.homeCalls, 1, 'Warm fetch must not hit upstream');
  assert.equal(warm.rawBuf, cold.rawBuf);

  // Simulate stale age > 3 min: should return cached and trigger background revalidation
  warm.createdAt = Date.now() - 200_000;
  const stale = await service.getHome({ gl: 'US' });
  assert.ok(stale.rawBuf);

  // Allow background SWR single-flight tick to run
  await new Promise((r) => setTimeout(r, 50));
  assert.equal(mockClient.homeCalls, 2, 'SWR must revalidate stale cache in background');
});

test('P2 Catalog: searchSongs caches pre-serialized UTF-8 Buffer', async () => {
  const mockClient = new MockYtmClient();
  const service = new CatalogService({ ytmClient: mockClient });

  const result1 = await service.searchSongs({ query: 'coldplay', gl: 'IN' });
  assert.equal(mockClient.searchCalls, 1);
  assert.ok(result1.rawBuf instanceof Buffer);

  const result2 = await service.searchSongs({ query: 'coldplay', gl: 'IN' });
  assert.equal(mockClient.searchCalls, 1, 'Warm search must hit cache');
  assert.equal(result1.rawBuf, result2.rawBuf);
});

test('P2 Suggestion Pre-Warm: Debounced Top-1 pre-warm cancels previous typing and caps concurrency', async () => {
  const mockClient = new MockYtmClient();
  const service = new CatalogService({ ytmClient: mockClient });

  // Client starts typing: 'col' -> prewarm 'col official'
  service.prewarmTopSuggestion('col official', '192.168.1.100', 'IN');
  assert.equal(service.suggestWarmControllers.has('192.168.1.100'), true);
  const firstController = service.suggestWarmControllers.get('192.168.1.100');

  // Client continues typing 20ms later: 'cold' -> prewarm 'coldplay'
  // Should abort firstController!
  service.prewarmTopSuggestion('coldplay', '192.168.1.100', 'IN');
  assert.equal(firstController.signal.aborted, true, 'Superseded typing must abort previous warm');

  await new Promise((r) => setTimeout(r, 50));
  assert.ok(mockClient.searchCalls >= 1);
});
