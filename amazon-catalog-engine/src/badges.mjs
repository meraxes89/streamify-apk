/**
 * AmazonAudioBadges
 * 
 * Inspects Amazon track/album metadata and contentEncodings to detect
 * audiophile audio tiers (Ultra HD 24/192, HD 16/44.1, Dolby Atmos, 360RA).
 */

export const AudioTier = Object.freeze({
  ULTRA_HD: 'ULTRA_HD',
  HD: 'HD',
  STANDARD: 'STANDARD',
  DOLBY_ATMOS: 'DOLBY_ATMOS',
  SONY_360RA: 'SONY_360RA'
});

export function detectAudioBadges(trackOrMetadata) {
  if (!trackOrMetadata) {
    return {
      tier: AudioTier.STANDARD,
      badges: [],
      isLossless: false,
      isHiRes: false,
      isSpatial: false,
      specLabel: '16-bit / 44.1 kHz Standard'
    };
  }

  const encodings = Array.isArray(trackOrMetadata.contentEncoding)
    ? trackOrMetadata.contentEncoding
    : (trackOrMetadata.encodings || []);

  const hasAtmos = encodings.includes('atmosAvailable') || trackOrMetadata.isAtmos || false;
  const has360Ra = encodings.includes('ra360Available') || false;
  const hasUltraHd = encodings.includes('uhdAvailable') || trackOrMetadata.isUltraHd || false;
  const hasHd = encodings.includes('hdAvailable') || trackOrMetadata.isHd || true; // Most Amazon catalog is at least HD

  const badges = [];

  if (hasAtmos) {
    badges.push({
      id: 'dolby_atmos',
      label: 'Dolby Atmos',
      type: 'spatial',
      icon: 'atmos'
    });
  }

  if (has360Ra) {
    badges.push({
      id: '360_ra',
      label: '360 Reality Audio',
      type: 'spatial',
      icon: 'spatial_360'
    });
  }

  let specLabel = '16-bit / 44.1 kHz HD';
  let tier = AudioTier.HD;
  let isHiRes = false;

  if (hasUltraHd) {
    tier = AudioTier.ULTRA_HD;
    isHiRes = true;
    specLabel = '24-bit / 96 kHz Ultra HD';
    badges.push({
      id: 'ultra_hd',
      label: 'Ultra HD',
      type: 'hi-res',
      bitDepth: 24,
      sampleRateKhz: 96
    });
  } else if (hasHd) {
    badges.push({
      id: 'hd',
      label: 'HD',
      type: 'lossless',
      bitDepth: 16,
      sampleRateKhz: 44.1
    });
  }

  return {
    tier,
    badges,
    isLossless: hasHd || hasUltraHd,
    isHiRes,
    isSpatial: hasAtmos || has360Ra,
    specLabel
  };
}
