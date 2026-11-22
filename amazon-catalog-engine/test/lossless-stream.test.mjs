import { describe, it, before, after } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { AmazonStreamResolver } from '../src/resolver.mjs';
import { AmazonCatalogClient } from '../src/client.mjs';
import { AmazonIntegratedEngine } from '../src/integrated-engine.mjs';
import { selectTopLosslessTier, LOSSLESS_TIERS } from '../src/lossless-stream.mjs';
import { AudioTier } from '../src/badges.mjs';

describe('Amazon Lossless Full-Song Stream Resolution', () => {
  describe('selectTopLosslessTier', () => {
    it('selects Ultra HD (24/96 kHz) as the top lossless tier when available', () => {
      const selection = selectTopLosslessTier({ title: 'Blinding Lights', contentEncoding: ['uhdAvailable'] });

      assert.equal(selection.tier, AudioTier.ULTRA_HD);
      assert.equal(selection.isLossless, true);
      assert.equal(selection.isHiRes, true);
      assert.equal(selection.codec, 'flac');
      assert.equal(selection.specLabel, '24-bit / 96 kHz Ultra HD');
      assert.ok(LOSSLESS_TIERS[0].lossless);
    });

    it('selects HD (16/44.1 kHz) as the lossless tier when Ultra HD is absent', () => {
      const selection = selectTopLosslessTier({ title: 'Shape of You', contentEncoding: ['hdAvailable'] });

      assert.equal(selection.tier, AudioTier.HD);
      assert.equal(selection.isLossless, true);
      assert.equal(selection.isHiRes, false);
      assert.equal(selection.codec, 'flac');
      assert.equal(selection.specLabel, '16-bit / 44.1 kHz HD');
    });

    it('falls back to the lossless HD tier when metadata carries no lossless flag', () => {
      const selection = selectTopLosslessTier({ title: 'Song', artist: 'Artist' });

      assert.equal(selection.tier, AudioTier.HD);
      assert.equal(selection.isLossless, true);
      assert.equal(selection.codec, 'flac');
    });
  });

  describe('AmazonStreamResolver.resolveLossless', () => {
    it('resolves a full song to the top-tier lossless stream descriptor', async () => {
      const resolver = new AmazonStreamResolver({ cache: new Map() });
      resolver._searchCandidates = async () => ([
        { videoId: 'vidLoss01', title: 'Bohemian Rhapsody', artist: 'Queen - Topic', durationSeconds: 355 },
      ]);

      const descriptor = await resolver.resolveLossless({
        asin: 'B0TEST',
        title: 'Bohemian Rhapsody',
        artist: 'Queen',
        durationSeconds: 354,
      });

      assert.equal(descriptor.source, 'amazon-lossless-fullsong');
      assert.equal(descriptor.isLossless, true);
      assert.equal(descriptor.ok, true);
      assert.equal(descriptor.tier, AudioTier.HD);
      assert.equal(descriptor.codec, 'flac');
      assert.equal(descriptor.videoId, 'vidLoss01');
      assert.ok(descriptor.streamUrl.startsWith('/api/stream?id='));
      assert.ok(descriptor.manifestUrl.startsWith('/api/stream/'));
    });

    it('caches lossless resolution per ASIN', async () => {
      const cache = new Map();
      const resolver = new AmazonStreamResolver({ cache });
      let searchCalls = 0;
      resolver._searchCandidates = async () => {
        searchCalls++;
        return [{ videoId: 'vidLoss02', title: 'X', artist: 'Y', durationSeconds: 10 }];
      };

      const first = await resolver.resolveLossless({ asin: 'BLZCASHD', title: 'X', artist: 'Y' });
      const second = await resolver.resolveLossless({ asin: 'BLZCASHD', title: 'X', artist: 'Y' });

      assert.equal(searchCalls, 1);
      assert.equal(first, second);
    });

    it('requires track metadata for lossless resolution', async () => {
      const resolver = new AmazonStreamResolver({ cache: new Map() });

      await assert.rejects(() => resolver.resolveLossless(null), /lossless resolution/i);
    });
  });

  describe('AmazonCatalogClient.resolveLosslessAudio', () => {
    it('delegates to the stream resolver and flags the lossless response', async () => {
      const client = new AmazonCatalogClient();
      client.resolver.resolveLossless = async () => ({
        videoId: 'vidLoss03',
        isLossless: true,
        tier: AudioTier.HD,
        source: 'amazon-lossless-fullsong',
      });

      const resolved = await client.resolveLosslessAudio({ title: 'Song', artist: 'Artist' });

      assert.equal(resolved.videoId, 'vidLoss03');
      assert.equal(resolved.lossless, true);
      assert.equal(resolved.requestedTier, AudioTier.HD);
    });
  });

  describe('AmazonIntegratedEngine.resolveLosslessPacedStream', () => {
    let engine;
    let tmpStorageDir;

    before(async () => {
      tmpStorageDir = fs.mkdtempSync(path.join(os.tmpdir(), 'amazon-lossless-'));
      engine = new AmazonIntegratedEngine({
        country: 'US',
        streamsDir: tmpStorageDir,
        maxCacheMb: 20,
      });
      await engine.init();
    });

    after(async () => {
      await engine.close();
      if (tmpStorageDir && fs.existsSync(tmpStorageDir)) {
        fs.rmSync(tmpStorageDir, { recursive: true, force: true });
      }
    });

    it('runs lossless resolution through admission + pacing with a releasable handle', async () => {
      engine.streamResolver.resolveLossless = async (trackMeta) => ({
        streamUrl: 'https://mock.stream/lossless.flac',
        source: 'amazon-lossless-fullsong',
        lossless: true,
        trackId: trackMeta.id,
      });

      const handle = await engine.resolveLosslessPacedStream({ id: 'B081TV3C5Y', title: 'Blinding Lights' });

      assert.equal(handle.source, 'amazon-lossless-fullsong');
      assert.equal(handle.lossless, true);
      assert.equal(engine.admission.activeStreams, 1);

      handle.release();
      assert.equal(engine.admission.activeStreams, 0);
    });
  });
});
