import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { LosslessFullSongService } from '../src/lossless-fullsong.mjs';
import { AmazonIntegratedEngine } from '../src/integrated-engine.mjs';
import { FrameAlignedGateway } from '../src/frame-gateway.mjs';
import { AudioTier } from '../src/badges.mjs';

/** Minimal FLAC file header: fLaC + STREAMINFO block (34 bytes). */
function syntheticFlacBuffer() {
    const buffer = Buffer.alloc(42, 0);
    buffer.write('fLaC', 0, 'ascii');
    buffer[4] = 0x00; // STREAMINFO, not last block
    buffer[5] = 0x00;
    buffer[6] = 0x00;
    buffer[7] = 0x22; // 34-byte STREAMINFO payload
    return buffer;
}

describe('LosslessFullSongService (whole raw lossless audio)', () => {
    it('acquires the whole FLAC file with metadata-first remux and frame geometry', async () => {
        const flacBytes = syntheticFlacBuffer();
        const service = new LosslessFullSongService({
            audioSource: async () => ({ bytes: flacBytes, codec: 'flac', contentType: 'audio/flac' }),
        });

        const artifact = await service.acquireFullSong({
            streamUrl: '/api/stream?id=J7p4bzqLvCw',
            tier: AudioTier.HD,
            codec: 'flac',
        });

        assert.equal(artifact.isLossless, true);
        assert.equal(artifact.tier, AudioTier.HD);
        assert.equal(artifact.codec, 'flac');
        assert.equal(artifact.remuxed, true);
        assert.equal(artifact.flac.length, flacBytes.length);
        assert.ok(artifact.streamInfo);
        assert.ok(Array.isArray(artifact.frameIndex));
        assert.ok(artifact.gateway instanceof FrameAlignedGateway);
        assert.equal(artifact.gateway.totalBytes, flacBytes.length);
    });

    it('preserves whole bytes for non-FLAC lossless sources', async () => {
        const opusBytes = Buffer.from('1a45dfa3-opus-whole-song', 'utf8');
        const service = new LosslessFullSongService({
            audioSource: async () => ({ bytes: opusBytes, codec: 'opus' }),
        });

        const artifact = await service.acquireFullSong({
            streamUrl: '/api/stream?id=J7p4bzqLvCw',
            tier: AudioTier.ULTRA_HD,
            codec: 'opus',
        });

        assert.equal(artifact.isLossless, true);
        assert.equal(artifact.tier, AudioTier.ULTRA_HD);
        assert.equal(artifact.remuxed, false);
        assert.equal(artifact.flac, null);
        assert.equal(artifact.bytes, opusBytes);
    });

    it('requires an audio source to acquire the full song', async () => {
        const service = new LosslessFullSongService();

        await assert.rejects(
            () => service.acquireFullSong({ streamUrl: 'x', tier: AudioTier.HD, codec: 'flac' }),
            /audio source is required/i
        );
    });

    it('is reachable from AmazonIntegratedEngine with the resolved lossless descriptor', async () => {
        const engine = new AmazonIntegratedEngine();
        engine.streamResolver.resolveLossless = async () => ({
            streamUrl: '/api/stream?id=J7p4bzqLvCw',
            tier: AudioTier.HD,
            codec: 'flac',
        });
        engine.losslessFullSongService.acquireFullSong = async (options) => ({
            ...options,
            wholeSong: true,
            codec: 'flac',
        });

        const artifact = await engine.acquireLosslessFullSong({ title: 'Blinding Lights' });

        assert.equal(artifact.wholeSong, true);
        assert.equal(artifact.tier, AudioTier.HD);
        assert.equal(artifact.codec, 'flac');
    });
});
