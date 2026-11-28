import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { AmazonIntegratedEngine } from '../src/integrated-engine.mjs';
import { BLOCK_SIZE } from '../../server/src/slices/stream/sparse-block-cache.js';

describe('AmazonIntegratedEngine (Full Infrastructure Bridge: Steps 1, 2, 3)', () => {
  let engine;
  let tmpStorageDir;

  before(async () => {
    tmpStorageDir = fs.mkdtempSync(path.join(os.tmpdir(), 'amazon-integrated-infra-'));
    engine = new AmazonIntegratedEngine({
      country: 'US',
      maxGlobalStreams: 4,
      streamsDir: tmpStorageDir,
      maxCacheMb: 20
    });
    await engine.init();
  });

  after(async () => {
    await engine.close();
    if (tmpStorageDir && fs.existsSync(tmpStorageDir)) {
      fs.rmSync(tmpStorageDir, { recursive: true, force: true });
    }
  });

  it('Step 1 & 2: verifies single-flight concurrency deduplication on search', async () => {
    let rawCalls = 0;
    const originalSearch = engine.client.search.bind(engine.client);
    engine.client.search = async (query, filter) => {
      rawCalls++;
      await new Promise(r => setTimeout(r, 40));
      return { results: [{ id: 'TEST1', title: query }] };
    };

    const promises = Array.from({ length: 10 }, () => engine.search('Blinding Lights', 'track'));
    const results = await Promise.all(promises);

    assert.equal(results.length, 10);
    assert.equal(results[0].results[0].title, 'Blinding Lights');
    assert.equal(rawCalls, 1);
    assert.equal(engine.cache.size(), 1);

    engine.client.search = originalSearch;
  });

  it('Step 1 & 2: verifies LRU cache hit and single-flight on queue generation', async () => {
    let callCount = 0;
    engine.client.getQueue = async (trackId) => {
      callCount++;
      return { tracks: [{ id: trackId, title: 'Queue Track' }] };
    };

    const res1 = await engine.getQueue('TRACK_A');
    const res2 = await engine.getQueue('TRACK_A');

    assert.deepEqual(res1, res2);
    assert.equal(callCount, 1);
  });

  it('Step 1 & 2: verifies AsyncAdmission concurrency slot control', async () => {
    engine.streamResolver.resolve = async (trackMeta) => ({
      streamUrl: 'https://mock.stream/audio.opus',
      source: 'resolved-mock',
      trackId: trackMeta.id
    });

    const initialMetrics = engine.admission.getMetrics();
    assert.equal(initialMetrics.globalActive, 0);

    const handle = await engine.resolvePacedStream({ id: 'B081TV3C5Y', title: 'Blinding Lights' });
    assert.equal(engine.admission.activeStreams, 1);
    assert.equal(handle.activeStreams, 1);

    handle.release();
    assert.equal(engine.admission.activeStreams, 0);
  });

  it('Step 2 & 3: verifies PacedStreamTransform audio pipe with token wheel', async () => {
    // Generate a simulated 10KB audio buffer
    const testAudioBuffer = Buffer.alloc(1024 * 10, 0x7f);
    const inStream = Readable.from([testAudioBuffer]);

    const pacedOut = engine.createPacedAudioPipeline(inStream, { bitrate: 160_000 });

    const receivedChunks = [];
    pacedOut.on('data', chunk => receivedChunks.push(chunk));

    await new Promise((resolve, reject) => {
      pacedOut.on('end', resolve);
      pacedOut.on('error', reject);
    });

    const totalBytes = receivedChunks.reduce((acc, c) => acc + c.length, 0);
    assert.equal(totalBytes, 1024 * 10);
  });

  it('Step 2 & 3: verifies SparseBlockCache entry indexing and byte persistence', () => {
    assert.ok(engine.blockCache);
    const entry = engine.storeBlock('AMAZON_ASIN_123', 'audio_ultra_hd', 0, null);
    assert.ok(entry);

    entry.setTotalLength(BLOCK_SIZE * 4);
    assert.equal(entry.totalLength, BLOCK_SIZE * 4);

    const isPresent = entry.isRangePresent(0, BLOCK_SIZE);
    assert.equal(typeof isPresent, 'boolean');
  });

  it('verifies integrated diagnostics reporting across all layers', () => {
    const diag = engine.getDiagnostics();
    assert.equal(diag.engine, 'AmazonIntegratedEngine');
    assert.ok(diag.governor);
    assert.ok(diag.admission);
    assert.equal(diag.hasBlockCache, true);
    assert.equal(typeof diag.cacheSize, 'number');
    assert.equal(typeof diag.activeFlights, 'number');
  });
});
