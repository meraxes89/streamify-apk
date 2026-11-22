/**
 * Amazon Lossless Full-Song Engineering
 *
 * Produces the whole raw lossless audio file for a full Amazon song:
 *  1. Acquires the full audio bytes for the selected top-tier lossless rendition.
 *  2. For FLAC sources: metadata-first remux (audio-critical blocks before the heavy PICTURE).
 *  3. Builds the frame index + frame-aligned seek geometry via FlacIntrospector / FrameAlignedGateway.
 *  4. Returns the whole lossless artifact (bytes + introspection) for delivery.
 *
 * Delivery mechanics (sparse-block cache, pacing, admission, single-flight) stay in
 * AmazonIntegratedEngine; this module owns whole-song lossless acquisition.
 */

import { FlacIntrospector } from './flac-introspector.mjs';
import { FrameAlignedGateway } from './frame-gateway.mjs';
import { LOSSLESS_TIERS } from './lossless-stream.mjs';

export class LosslessFullSongService {
    constructor({
        introspector = FlacIntrospector,
        gateway = new FrameAlignedGateway(),
        audioSource = null,
    } = {}) {
        this.introspector = introspector;
        this.gateway = gateway;
        /** @type {Function} async ({ streamUrl, tier, codec }) -> { bytes, codec, contentType, bitrate, durationSec } */
        this.audioSource = audioSource;
    }

    /**
     * Acquire the whole raw lossless audio for a full song.
     *
     * @param {Object} options
     * @param {string} options.streamUrl - resolved audio stream URL for the full song
     * @param {string} [options.tier] - lossless tier (AudioTier.ULTRA_HD | AudioTier.HD)
     * @param {string} [options.codec] - acquired codec (default 'flac')
     * @returns {Promise<Object>} Whole-song lossless artifact
     */
    async acquireFullSong({ streamUrl, tier, codec = 'flac', audioSource: sourceOverride } = {}) {
        const source = sourceOverride || this.audioSource;
        if (!source) throw new Error('Lossless full-song audio source is required');

        const audio = await source({ streamUrl, tier, codec });
        const bytes = Buffer.isBuffer(audio.bytes) ? audio.bytes : Buffer.from(audio.bytes || []);
        const acquired = {
            codec: audio.codec || codec,
            bytes,
            contentType: audio.contentType || null,
            bitrate: audio.bitrate || null,
            durationSec: audio.durationSec || null,
            sourceUrl: streamUrl || null,
        };

        const losslessTier = tier || audio.tier || LOSSLESS_TIERS[1].tier;
        const tierSpec = LOSSLESS_TIERS.find((t) => t.tier === losslessTier) || LOSSLESS_TIERS[1];

        const artifact = {
            codec: acquired.codec,
            isLossless: true,
            tier: losslessTier,
            specLabel: tierSpec.label,
            bitrate: acquired.bitrate || tierSpec.bitrate,
            contentType: acquired.contentType,
            sourceUrl: streamUrl || null,
            bytes: acquired.bytes,
            flac: null,
            streamInfo: null,
            frameIndex: null,
            remuxed: false,
            gateway: null,
        };

        if (acquired.codec !== 'flac') {
            // Non-FLAC (e.g. Opus via relay) source: whole bytes are the lossless delivery payload
            // under the selected lossless tier; no FLAC metadata remux applies.
            return artifact;
        }

        const info = this.introspector.inspect(acquired.bytes);
        const remux = this.introspector.createMetadataFirstStream(acquired.bytes);
        const frameIndex = this.introspector.scanFrameSyncs(remux.remuxedBuffer, info.firstAudioFrameOffset, 4096);
        const gateway = new FrameAlignedGateway({
            frameIndex,
            sampleRate: info.streamInfo?.sampleRate || 48000,
            totalBytes: remux.remuxedBuffer.length,
            metadataBytes: info.firstAudioFrameOffset,
            bitrate: tierSpec.bitrate,
        });

        artifact.flac = remux.remuxedBuffer;
        artifact.streamInfo = info;
        artifact.frameIndex = frameIndex;
        artifact.remuxed = true;
        artifact.gateway = gateway;

        return artifact;
    }
}
