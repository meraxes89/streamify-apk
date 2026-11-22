/**
 * Amazon Lossless Full-Song Engineering
 *
 * Produces the whole raw lossless audio file for a full Amazon song:
 *  1. Acquires the full audio bytes for the selected top-tier lossless rendition
 *     (real byte downloader: HttpAudioSource or injected source).
 *  2. For FLAC sources: metadata-first remux (audio-critical blocks before the
 *     heavy PICTURE), REAL frame index via UTF-8-coded header sample numbers,
 *     frame-aligned seek geometry via FlacIntrospector / FrameAlignedGateway.
 *  3. Sniffs the delivered bytes and reports the TRUE codec verdict: FLAC is
 *     bit-perfect lossless; anything else is labeled exactly what it is
 *     (delivery.bitPerfect === false, delivery.codec = actual codec).
 *  4. Returns the whole lossless artifact (bytes + introspection) for delivery.
 *
 * Delivery mechanics (sparse-block cache, pacing, admission, single-flight) stay in
 * AmazonIntegratedEngine; this module owns whole-song lossless acquisition.
 */

import { FlacIntrospector } from './flac-introspector.mjs';
import { FrameAlignedGateway } from './frame-gateway.mjs';
import { LOSSLESS_TIERS } from './lossless-stream.mjs';
import { sniffAudioCodec } from './audio-source.mjs';

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

        // Honest delivered-bytes codec verdict (magic-byte sniffed, never guessed)
        const sniffed = sniffAudioCodec(bytes);
        const deliveredIsFlac = sniffed.codec === 'flac';

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
            // Honest delivery card: what the bytes ACTUALLY are
            delivery: {
                codec: sniffed.codec,
                container: sniffed.container,
                lossless: sniffed.lossless,
                bitPerfect: sniffed.bitPerfect,
                detail: sniffed.detail,
                requestedTier: losslessTier,
                requestedCodec: codec
            },
        };

        if (!deliveredIsFlac) {
            // Non-FLAC (e.g. Opus via relay) source: whole bytes are the full-song
            // delivery payload under the selected lossless tier request. The
            // delivery card reports the true lossy codec - the engine never
            // decorates Opus/AAC as bit-perfect FLAC.
            return artifact;
        }

        const info = this.introspector.inspect(acquired.bytes);
        const remux = this.introspector.createMetadataFirstStream(acquired.bytes);

        // Offset-skew fix: re-introspect the REMUXED stream so frame byte
        // offsets are anchored to the remuxed geometry (the original buffer's
        // firstAudioFrameOffset inflates every offset by the PICTURE size).
        const remuxInfo = this.introspector.inspect(remux.remuxedBuffer);
        const audioPayload = remux.remuxedBuffer.subarray(remuxInfo.firstAudioFrameOffset);
        const scan = this.introspector.scanFramesDetailed(
            audioPayload,
            remuxInfo.firstAudioFrameOffset,
            8192,
            remuxInfo.streamInfo
        );
        // Seek-grade geometry: only CRC-8 validated frames with real sample numbers
        const frameIndex = scan.frames;

        // True compressed bitrate from real geometry when available
        const actualBitrate = remuxInfo.estimatedBitrate ||
            (remuxInfo.streamInfo?.durationSec > 0
                ? Math.round((remux.remuxedBuffer.length - remuxInfo.firstAudioFrameOffset) * 8 /
                    remuxInfo.streamInfo.durationSec)
                : tierSpec.bitrate);

        const gateway = new FrameAlignedGateway({
            frameIndex,
            sampleRate: remuxInfo.streamInfo?.sampleRate || 48000,
            totalBytes: remux.remuxedBuffer.length,
            metadataBytes: remuxInfo.firstAudioFrameOffset,
            bitrate: actualBitrate,
        });

        artifact.flac = remux.remuxedBuffer;
        artifact.streamInfo = {
            ...remuxInfo,
            remuxBytesSaved: remux.bytesSavedFromFront,
        };
        artifact.frameIndex = frameIndex;
        artifact.remuxed = true;
        artifact.gateway = gateway;
        artifact.bitrate = actualBitrate;
        artifact.delivery = {
            ...artifact.delivery,
            codec: 'flac',
            container: 'flac',
            lossless: true,
            bitPerfect: true,
            detail: 'FLAC bitstream, metadata-first remuxed, CRC-8-validated frame index',
            frameCount: scan.validatedFrameCount,
            syncMarkerCount: frameIndex.length,
            frameTileIntegrity: scan.integrity,
            estimatedBitrate: actualBitrate
        };

        return artifact;
    }
}
