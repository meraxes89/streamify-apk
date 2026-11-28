/**
 * AmazonStationService (Algorithmic Endless Mix & Radio)
 * 
 * Provides continuous radio playback and mood/genre stations
 * matching Amazon Music's "catalog_station" subsystem.
 */

import { formatAmazonArtwork } from './mappers.mjs';

export const CURATED_GENRE_STATIONS = [
  {
    stationKey: 'amzn:station:focus',
    title: 'Focus & Deep Work',
    subtitle: 'Instrumental and ambient sounds for concentration',
    category: 'Activity',
    artworkUrl: 'https://m.media-amazon.com/images/I/71G1Pj3P5kL._SX500_.jpg'
  },
  {
    stationKey: 'amzn:station:workout',
    title: 'High Energy Workout',
    subtitle: 'Upbeat EDM, hip-hop and pop to power your training',
    category: 'Activity',
    artworkUrl: 'https://m.media-amazon.com/images/I/81xGg6zB4DL._SX500_.jpg'
  },
  {
    stationKey: 'amzn:station:chill',
    title: 'Chill Vibes & Lo-Fi',
    subtitle: 'Relaxing beats and mellow indie tracks',
    category: 'Mood',
    artworkUrl: 'https://m.media-amazon.com/images/I/71wLpWdG1uL._SX500_.jpg'
  },
  {
    stationKey: 'amzn:station:classic_rock',
    title: 'Classic Rock Legends',
    subtitle: '70s and 80s definitive anthems',
    category: 'Genre',
    artworkUrl: 'https://m.media-amazon.com/images/I/81xGg6zB4DL._SX500_.jpg'
  },
  {
    stationKey: 'amzn:station:jazz_lounge',
    title: 'Late Night Jazz Lounge',
    subtitle: 'Acoustic jazz, bebop and noir saxophone',
    category: 'Genre',
    artworkUrl: 'https://m.media-amazon.com/images/I/71G1Pj3P5kL._SX500_.jpg'
  }
];

export class AmazonStationService {
  constructor({ catalogClient = null } = {}) {
    this.client = catalogClient;
  }

  setClient(client) {
    this.client = client;
  }

  /**
   * Returns curated mood and genre stations.
   */
  getGenreStations() {
    return CURATED_GENRE_STATIONS.map(s => ({
      ...s,
      thumbnails: formatAmazonArtwork(s.artworkUrl)
    }));
  }

  /**
   * Creates an endless algorithmic radio station from a seed song or artist.
   */
  async createStation(seed, { limit = 20 } = {}) {
    if (!seed) throw new Error('Seed track or artist is required to build station');

    const seedTitle = typeof seed === 'string' ? seed : (seed.title || seed.name || '');
    const seedArtist = typeof seed === 'object' && seed.artist ? seed.artist : '';

    const query = seedArtist ? `${seedArtist} Radio` : `${seedTitle} Mix`;

    let tracks = [];
    if (this.client) {
      const res = await this.client.search(query, { limit });
      tracks = res.tracks || [];
    }

    return {
      stationId: `amzn:station:${encodeURIComponent(seedTitle.toLowerCase())}`,
      seed: {
        title: seedTitle,
        artist: seedArtist
      },
      title: `${seedTitle} Radio`,
      description: `Continuous algorithmic radio inspired by ${seedTitle}`,
      tracks,
      source: 'amazon:station'
    };
  }
}
