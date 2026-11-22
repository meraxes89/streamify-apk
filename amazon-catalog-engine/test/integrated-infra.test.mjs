import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonIntegratedEngine } from '../src/integrated-engine.mjs';

describe('AmazonIntegratedEngine (src Infrastructure Integration)', () => {
  let engine;

  before(async () => {
    engine = new AmazonIntegratedEngine({ country: 'US', maxGlobalStreams: 4 });
    await engine.init();
  });

  after(async () => {
    await engine.close();
  });

  it('verifies single-flight concurrency deduplication on search', async () => {
    let rawCalls = 0;
    // Monkey-patch client.search to count raw invocations
    const originalSearch = engine.client.search.bind(engine.client);
    engine.client.search = async (query, filter) => {
      rawCalls++;
      // small delay to allow parallel promises to queue up
      await new Promise(r => setTimeout(r, 50));
      return { results: [{ id: 'TEST1', title: query }] };
    };

    // Fire 10 parallel search requests
    const promises = Array.from({ length: 10 }, () => engine.search('Blinding Lights', 'track'));
    const results = await Promise.all(promises);

    assert.equal(results.length, 10);
    assert.equal(results[0].results[0].title, 'Blinding Lights');
    // Critical assertion: Only 1 upstream search invocation despite 10 simultaneous calls!
    assert.equal(rawCalls, 1);
    assert.equal(engine.cache.size(), 1);

    // Restore
    engine.client.search = originalSearch;
  });

  it('verifies LRU cache hit after initial population', async () => {
    let callCount = 0;
    engine.client.getQueue = async (trackId) => {
      callCount++;
      return { tracks: [{ id: trackId, title: 'Queue Song' }] };
    };

    const res1 = await engine.getQueue('TRACK_A');
    const res2 = await engine.getQueue('TRACK_A');

    assert.deepEqual(res1, res2);
    assert.equal(callCount, 1); // 2nd hit served entirely from LruCache
  });

  it('verifies AsyncAdmission slot acquisition and release during stream resolution', async () => {
    // Mock stream resolver
    engine.streamResolver.resolve = async (trackMeta) => {
      return {
        streamUrl: 'https://mock.stream/audio.opus',
        source: 'resolved-mock',
        trackId: trackMeta.id
      };
    };

    const initialMetrics = engine.admission.getMetrics();
    assert.equal(initialMetrics.globalActive, 0);

    // Acquire stream
    const handle = await engine.resolvePacedStream({ id: 'B081TV3C5Y', title: 'Blinding Lights' });
    assert.equal(engine.admission.activeStreams, 1);
    assert.equal(handle.activeStreams, 1);
    assert.equal(typeof handle.release, 'function');

    // Release stream
    handle.release();
    assert.equal(engine.admission.activeStreams, 0);
  });

  it('verifies integrated diagnostics reporting', () => {
    const diag = engine.getDiagnostics();
    assert.equal(diag.engine, 'AmazonIntegratedEngine');
    assert.ok(diag.governor);
    assert.ok(diag.admission);
    assert.equal(typeof diag.cacheSize, 'number');
    assert.equal(typeof diag.activeFlights, 'number');
  });
});
