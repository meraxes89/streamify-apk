import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { AmazonPodcastService } from '../src/podcasts.mjs';
import { AmazonStationService, CURATED_GENRE_STATIONS } from '../src/stations.mjs';

describe('Podcasts & Stations System', () => {
  test('AmazonStationService returns curated genre stations', () => {
    const stationService = new AmazonStationService();
    const stations = stationService.getGenreStations();

    assert.ok(stations.length >= 5);
    assert.equal(stations[0].stationKey, 'amzn:station:focus');
    assert.ok(stations[0].thumbnails.length >= 1);
  });

  test('AmazonStationService createStation builds algorithmic queue', async () => {
    const stationService = new AmazonStationService();
    stationService.setClient({
      search: async (query) => ({
        tracks: [
          { id: 'amzn:track:1', title: 'Track 1' },
          { id: 'amzn:track:2', title: 'Track 2' }
        ]
      })
    });

    const station = await stationService.createStation('Queen');
    assert.equal(station.title, 'Queen Radio');
    assert.equal(station.tracks.length, 2);
    assert.equal(station.source, 'amazon:station');
  });

  test('AmazonPodcastService parses episode HTML and extracts direct audio stream', () => {
    const podcastService = new AmazonPodcastService();
    const mockHtml = `
      <html>
        <head>
          <meta property="og:title" content="Episode 100: Neuroscience of Focus" />
          <meta property="og:description" content="Deep dive into dopamine and focus" />
          <meta property="og:image" content="https://m.media-amazon.com/images/podcast.jpg" />
          <meta property="og:audio" content="https://audio.podcasts.amazon.com/episode100.mp3" />
        </head>
      </html>
    `;

    const episode = podcastService._parsePodcastEpisodeHtml(mockHtml, 'https://music.amazon.com/podcasts/episodes/B0TEST123');
    assert.equal(episode.title, 'Episode 100: Neuroscience of Focus');
    assert.equal(episode.streamUrl, 'https://audio.podcasts.amazon.com/episode100.mp3');
    assert.equal(episode.isDirectAudioStream, true);
    assert.equal(episode.source, 'amazon:podcast');
  });
});
