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
    it('guest ladder returns the REAL Amazon sample descriptor (never a silent bridge)', async () => {
      const resolver = new AmazonStreamResolver({ cache: new Map() });
      resolver._searchCandidates = async () => {
        throw new Error('the YouTube bridge must never run implicitly');
      };
      resolver.nativeDelivery = {
        probeCapability: async () => ({
          status: 'AUTH_REQUIRED',
          reason: 'Guest session carries no customerId; DMLS full-song delivery is auth-walled.',
          evidence: {},
          wall: { __type: 'com.amazon.digitalmusiclocator#UnauthorizedException' }
        }),
        solver: {
          solveSampleUrl: async () => ({
            status: 'LINKS_SOLVED',
            url: 'https://d28julafmv4ekl.cloudfront.net/64%2F30%2F200000354418958_S64.mp3',
            expiresAt: Math.floor(Date.now() / 1000) + 3600
          })
        }
      };

      const descriptor = await resolver.resolveLossless({
        asin: 'B0TEST',
        title: 'Bohemian Rhapsody',
        artist: 'Queen',
        durationSeconds: 354,
      });

      assert.equal(descriptor.source, 'amazon-sample-preview');
      assert.equal(descriptor.previewOnly, true);
      assert.equal(descriptor.ok, true);
      assert.equal(descriptor.isLossless, false);
      assert.equal(descriptor.codec, 'mp3');
      assert.ok(descriptor.streamUrl.includes('cloudfront.net'));
      assert.ok(descriptor.delivery.pipeline === 'amazon-sample-preview');
      assert.equal(descriptor.delivery.previewOnly, true);
      assert.equal(descriptor.native.status, 'AUTH_REQUIRED');
      assert.ok(descriptor.native.reason.includes('customerId'));
    });

    it('authenticated ladder returns the native DMLS link graph and caches per ASIN', async () => {
      const cache = new Map();
      const resolver = new AmazonStreamResolver({ cache });
      let probes = 0;
      resolver.nativeDelivery = {
        probeCapability: async () => {
          probes++;
          return {
            status: 'LINKS_SOLVED',
            reason: 'Real DMLS links solved',
            selected: {
              id: 'flac-24-96', tierLabel: 'UHD96', specLabel: '24-bit / 96 kHz FLAC (Ultra HD)',
              codecFamily: 'flac', audioSamplingRate: 96000, bitDepth: 24,
              links: {
                initUrl: 'https://d25tk16o50n0lf.cloudfront.net/track/init/flac-24-96/init.mp4',
                firstSegmentUrls: ['https://d25tk16o50n0lf.cloudfront.net/track/seg/flac-24-96/1.m4s']
              }
            },
            manifest: { inline: '<MPD>...</MPD>', representationCount: 3 },
            links: {
              initUrl: 'https://d25tk16o50n0lf.cloudfront.net/track/init/flac-24-96/init.mp4',
              firstSegmentUrls: ['https://d25tk16o50n0lf.cloudfront.net/track/seg/flac-24-96/1.m4s']
            },
            drm: { protected: false },
            evidence: {}
          };
        },
        solver: { solveSampleUrl: async () => ({ status: 'UNAVAILABLE', url: null }) }
      };

      const first = await resolver.resolveLossless({ asin: 'B0NATIVE', title: 'X', artist: 'Y' });
      const second = await resolver.resolveLossless({ asin: 'B0NATIVE', title: 'X', artist: 'Y' });

      assert.equal(probes, 1, 'second resolution is served from cache');
      assert.equal(first, second);
      assert.equal(first.source, 'amazon-dmls-native');
      assert.equal(first.isLossless, true);
      assert.equal(first.isHiRes, true);
      assert.equal(first.streamUrl, first.links.initUrl);
      assert.equal(first.delivery.pipeline, 'amazon-dmls-native');
      assert.equal(first.delivery.bitPerfect, true);
    });

    it('EXPLICIT bridge still resolves the legacy YouTube descriptor (opt-in only)', async () => {
      const resolver = new AmazonStreamResolver({ cache: new Map() });
      resolver._searchCandidates = async () => ([
        { videoId: 'vidLoss01', title: 'Bohemian Rhapsody', artist: 'Queen - Topic', durationSeconds: 355 },
      ]);

      const descriptor = await resolver.resolveLosslessViaBridge({
        asin: 'B0TEST',
        title: 'Bohemian Rhapsody',
        artist: 'Queen',
        durationSeconds: 354,
      });

      assert.equal(descriptor.source, 'amazon-lossless-fullsong');
      assert.equal(descriptor.explicitBridge, true);
      assert.equal(descriptor.isLossless, true);
      assert.equal(descriptor.ok, true);
      assert.equal(descriptor.codec, 'flac');
      assert.equal(descriptor.videoId, 'vidLoss01');
      assert.ok(descriptor.streamUrl.startsWith('/api/stream?id='));
      assert.equal(descriptor.delivery.pipeline, 'relay-bridge');
      assert.equal(descriptor.delivery.bitPerfect, false);
    });

    it('requires track metadata for lossless resolution', async () => {
      const resolver = new AmazonStreamResolver({ cache: new Map() });

      await assert.rejects(() => resolver.resolveLossless(null), /lossless resolution/i);
    });
  });

  describe('AmazonCatalogClient.resolveLosslessAudio', () => {
    it('delegates to the stream resolver and flags the lossless response', async () => {
      const client = new AmazonCatalogClient();
      client.search = async () => ({
        tracks: [{ asin: 'B0TEST', title: 'Song', artists: [{ name: 'Artist' }] }]
      });
      client.resolver.resolveLossless = async () => ({
        videoId: 'vidLoss03',
        isLossless: true,
        tier: AudioTier.HD,
        source: 'amazon-dmls-native',
      });

      const resolved = await client.resolveLosslessAudio({ title: 'Song', artist: 'Artist' }, { tier: AudioTier.HD });

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
