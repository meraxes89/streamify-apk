/**
 * Amazon Lossless Full-Song Stream Resolution
 *
 * Owns the Amazon-side lossless tier selection for full songs and builds the
 * full-song lossless stream descriptor consumed by playback.
 *
 * Lossless policy (docs/nano/comm21.md):
 *  - The relay/server never transcodes: delivered bytes stay bit-perfect.
 *  - Amazon full songs are delivered losslessly via FLAC masters.
 *  - Top tier is selected: Ultra HD (24-bit / 96 kHz) -> HD (16-bit / 44.1 kHz).
 *
 * Delivery mechanics (sparse-block cache, pacing, admission, single-flight)
 * live in the server/src primitives and are reused by AmazonIntegratedEngine;
 * this module decides/declares the lossless target for a full song.
 */

import { AudioTier, detectAudioBadges } from './badges.mjs';

export const LOSSLESS_CODEC_FLAC = 'flac';

/**
 * Lossless renditions Amazon serves, highest tier first.
 * bitrate is expressed in bits/sec.
 */
export const LOSSLESS_TIERS = Object.freeze([
    {
        tier: AudioTier.ULTRA_HD,
        codec: LOSSLESS_CODEC_FLAC,
        bitDepth: 24,
        sampleRateKhz: 96,
        bitrate: 18_900_000,
        label: '24-bit / 96 kHz Ultra HD',
        lossless: true,
    },
    {
        tier: AudioTier.HD,
        codec: LOSSLESS_CODEC_FLAC,
        bitDepth: 16,
        sampleRateKhz: 44.1,
        bitrate: 11_200_000,
        label: '16-bit / 44.1 kHz HD',
        lossless: true,
    },
]);

/** Lossless floor used when the catalog metadata carries no lossless tier. */
const DEFAULT_LOSSLESS_TIER = LOSSLESS_TIERS[1];

/**
 * Selects the top-tier lossless rendition for a full song.
 *
 * Priority: Ultra HD (24/96 kHz) -> HD (16/44.1 kHz) -> HD (lossless floor).
 * The returned object merges the Amazon badge telemetry with the selected
 * lossless audio format so callers can serve the best available tier.
 *
 * @param {Object} trackOrMetadata - Amazon track metadata (title/artists/ASIN/contentEncoding...)
 * @returns {Object} Lossless tier selection descriptor
 */
export function selectTopLosslessTier(trackOrMetadata = {}) {
    const badges = detectAudioBadges(trackOrMetadata);
    const tierSpec = LOSSLESS_TIERS.find((t) => t.tier === badges.tier) || DEFAULT_LOSSLESS_TIER;

    return {
        ...badges,
        tier: tierSpec.tier,
        isLossless: true,
        isHiRes: tierSpec.tier === AudioTier.ULTRA_HD,
        specLabel: tierSpec.label,
        codec: tierSpec.codec,
        bitrate: tierSpec.bitrate,
    };
}
