/**
 * AmazonVideoService (Music Videos & Live Performances)
 * 
 * Indexes music videos, live concert footage, and official visualizers
 * matching Amazon's "catalog_video" and "DigitalMusicVideoDeliveryService".
 */

export class AmazonVideoService {
  constructor({ resolver = null } = {}) {
    this.resolver = resolver;
  }

  /**
   * Find official music videos for an artist or song.
   */
  async getMusicVideos(artistOrTrack, { limit = 10 } = {}) {
    if (!artistOrTrack) throw new Error('Artist or track name required');

    const name = typeof artistOrTrack === 'string'
      ? artistOrTrack
      : (artistOrTrack.title ? `${artistOrTrack.title} ${artistOrTrack.artist || ''}` : artistOrTrack.name);

    const query = `${name} Official Music Video`;

    // Videos are resolved via YouTube's official channel CDN
    let candidates = [];
    if (this.resolver) {
      candidates = await this.resolver._searchCandidates(query);
    }

    return {
      query,
      videos: candidates.slice(0, limit).map(c => ({
        id: `amzn:video:${c.videoId}`,
        videoId: c.videoId,
        title: c.title,
        artist: c.artist,
        durationSeconds: c.durationSeconds,
        videoUrl: `https://www.youtube.com/watch?v=${c.videoId}`,
        thumbnailUrl: `https://i.ytimg.com/vi/${c.videoId}/hqdefault.jpg`,
        source: 'amazon:video'
      }))
    };
  }
}
