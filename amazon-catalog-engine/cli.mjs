#!/usr/bin/env node
/**
 * Standalone CLI for Amazon Catalog Engine (Full Expanded Suite)
 * 
 * Usage:
 *   node cli.mjs probe                 Run live discovery probe (API 12)
 *   node cli.mjs session [territory]   Print current session metadata
 *   node cli.mjs home [territory]      Fetch Home Showcase & Editorial (API 1)
 *   node cli.mjs search <query>        Search catalog (API 2)
 *   node cli.mjs suggest <query>       Instant suggestions (API 3)
 *   node cli.mjs browse <id>           Browse album/playlist/artist/podcast (API 4)
 *   node cli.mjs next <asin>           Fetch autoplay queue (API 5)
 *   node cli.mjs thumb <url> [size]    Scale artwork thumbnail (API 6)
 *   node cli.mjs scrape <url>          Scrape external playlist link (API 7)
 *   node cli.mjs lyrics <song> <band>  Fetch synced lyrics via LRCLIB (API 8)
 *   node cli.mjs resolve <title> [art] Resolve audio stream URL (API 9)
 *   node cli.mjs manifest <title>      Generate DASH MPD manifest (API 10)
 *   node cli.mjs prefetch <title>      Prefetch descriptor (API 11)
 *   node cli.mjs podcast <query>       Search and stream podcasts
 *   node cli.mjs station <seed>        Generate algorithmic radio station
 *   node cli.mjs badges <title>        Inspect audiophile quality badges
 *   node cli.mjs credits <title>       View track personnel & liner notes
 *   node cli.mjs videos <artist>       Find official music videos
 */

import fs from 'node:fs';
import { AmazonCatalogClient } from './src/client.mjs';

const args = process.argv.slice(2);
const command = args[0] || 'help';

const client = new AmazonCatalogClient();

async function main() {
  switch (command) {
    case 'probe': {
      await import('./probe.mjs');
      break;
    }

    case 'session': {
      const terr = (args[1] || 'US').toUpperCase();
      console.log(`Bootstrapping anonymous guest session for [${terr}]...`);
      const session = await client.session.getSession(terr);
      console.log(JSON.stringify({
        territory: session.territory,
        host: session.host,
        region: session.region,
        sessionId: session.sessionId,
        deviceId: session.deviceId,
        deviceType: session.deviceType,
        marketplaceId: session.marketplaceId,
        hasCsrf: !!session.csrf?.token,
        cookieCount: session.cookies ? session.cookies.split(';').length : 0,
        createdAt: new Date(session.createdAt).toISOString()
      }, null, 2));
      break;
    }

    case 'home': {
      const terr = (args[1] || 'US').toUpperCase();
      console.log(`Fetching Home Showcase for [${terr}]...`);
      const home = await client.getHome({ territory: terr });
      console.log(JSON.stringify(home, null, 2));
      break;
    }

    case 'search': {
      const query = args.slice(1).filter(a => !a.startsWith('--')).join(' ');
      if (!query) {
        console.error('Error: Please provide a search query.');
        process.exit(1);
      }
      console.log(`Searching for "${query}" on Amazon Music...`);
      const results = await client.search(query, { territory: 'US', limit: 5 });
      console.log(JSON.stringify(results, null, 2));
      break;
    }

    case 'suggest': {
      const query = args.slice(1).join(' ');
      if (!query) {
        console.error('Error: Please provide a suggest query.');
        process.exit(1);
      }
      const suggestions = await client.instantSearch(query);
      console.log(JSON.stringify(suggestions, null, 2));
      break;
    }

    case 'browse': {
      const id = args[1];
      if (!id) {
        console.error('Error: Please provide a browseId or ASIN.');
        process.exit(1);
      }
      const result = await client.getBrowse(id);
      console.log(JSON.stringify(result, null, 2));
      break;
    }

    case 'next': {
      const asin = args[1] || 'B01M11SBC8';
      console.log(`Resolving autoplay next queue for ASIN: ${asin}...`);
      const nextQueue = await client.getNext(asin);
      console.log(JSON.stringify(nextQueue, null, 2));
      break;
    }

    case 'thumb': {
      const url = args[1];
      const size = parseInt(args[2] || '500', 10);
      if (!url) {
        console.error('Error: Please provide an image URL.');
        process.exit(1);
      }
      console.log('Scaled Thumbnail URL:', client.getThumb(url, size));
      break;
    }

    case 'scrape': {
      const url = args[1];
      if (!url) {
        console.error('Error: Please provide a playlist URL.');
        process.exit(1);
      }
      console.log(`Scraping playlist: ${url}...`);
      const res = await client.scrapePlaylist(url);
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case 'lyrics': {
      const track = args[1];
      const artist = args[2] || '';
      if (!track) {
        console.error('Error: Please provide track name and artist.');
        process.exit(1);
      }
      console.log(`Fetching synced lyrics for "${track}" by "${artist}"...`);
      const lyrics = await client.getLyrics(track, artist);
      console.log(JSON.stringify(lyrics, null, 2));
      break;
    }

    case 'resolve': {
      const trackName = args[1];
      const artistName = args[2] || '';
      if (!trackName) {
        console.error('Error: Please provide track title.');
        process.exit(1);
      }
      console.log(`Resolving hybrid audio stream for "${trackName}" by "${artistName}"...`);
      const resolved = await client.resolveAudio({ title: trackName, artist: artistName });
      console.log(JSON.stringify(resolved, null, 2));
      break;
    }

    case 'lossless': {
      const trackName = args[1];
      const artistName = args[2] || '';
      if (!trackName) {
        console.error('Error: Please provide track title.');
        process.exit(1);
      }
      console.log(`Resolving lossless top-tier audio for "${trackName}" by "${artistName}"...`);
      const resolved = await client.resolveLosslessAudio({ title: trackName, artist: artistName });
      console.log(JSON.stringify(resolved, null, 2));
      break;
    }

    case 'lossless-full': {
      // Whole-song lossless acquisition: supply the Amazon FLAC stream URL for the
      // full track via STREAMIFY_FLAC_SOURCE_URL, e.g. an Amazon Music Ultra HD/HD
      // master link for the resolved track. Writes the entire raw FLAC to disk.
      const trackName = args[1];
      const artistName = args[2] || '';
      const sourceUrl = process.env.STREAMIFY_FLAC_SOURCE_URL;
      if (!trackName || !sourceUrl) {
        console.error('Error: provide track title and set STREAMIFY_FLAC_SOURCE_URL=<amazon flac url>.');
        process.exit(1);
      }
      const { LosslessFullSongService } = await import('./src/lossless-fullsong.mjs');
      const service = new LosslessFullSongService({
        audioSource: async ({ streamUrl }) => {
          const res = await fetch(streamUrl);
          if (!res.ok) throw new Error(`Full-song FLAC fetch failed: HTTP ${res.status}`);
          const bytes = Buffer.from(await res.arrayBuffer());
          const declared = res.headers.get('content-type') || '';
          return {
            bytes,
            codec: declared.includes('flac') ? 'flac' : 'opus',
            contentType: declared,
          };
        },
      });
      const descriptor = await client.resolveLosslessAudio({ title: trackName, artist: artistName });
      const artifact = await service.acquireFullSong({
        streamUrl: sourceUrl,
        tier: descriptor.tier,
        codec: 'flac',
      });
      const outputFile = args[3] || `${trackName.toLowerCase().replace(/[^a-z0-9]+/g, '_')}_lossless.flac`;
      fs.writeFileSync(outputFile, artifact.flac || artifact.bytes);
      console.log(JSON.stringify({
        outputFile,
        tier: artifact.tier,
        specLabel: artifact.specLabel,
        codec: artifact.codec,
        isLossless: artifact.isLossless,
        totalBytes: (artifact.flac || artifact.bytes).length,
        remuxed: artifact.remuxed,
        sampleRate: artifact.streamInfo?.streamInfo?.sampleRate || null,
        bitDepth: artifact.streamInfo?.streamInfo?.bitsPerSample || null,
      }, null, 2));
      break;
    }

    case 'manifest': {
      const trackName = args[1];
      const manifest = await client.getDashManifest({ title: trackName, artist: args[2] || '' });
      console.log(JSON.stringify(manifest, null, 2));
      break;
    }

    case 'prefetch': {
      const trackName = args[1];
      const prefetched = await client.prefetchDescriptor({ title: trackName, artist: args[2] || '' });
      console.log(JSON.stringify(prefetched, null, 2));
      break;
    }

    case 'podcast': {
      const query = args.slice(1).join(' ') || 'Huberman';
      console.log(`Searching podcasts for: "${query}"...`);
      const podcasts = await client.searchPodcasts(query);
      console.log(JSON.stringify(podcasts, null, 2));
      break;
    }

    case 'station': {
      const seed = args.slice(1).join(' ') || 'The Weeknd';
      console.log(`Generating radio station for seed: "${seed}"...`);
      const station = await client.createStation(seed);
      console.log(JSON.stringify(station, null, 2));
      break;
    }

    case 'badges': {
      const title = args[1] || 'Blinding Lights';
      console.log(`Inspecting audio quality badges for "${title}"...`);
      const badges = client.getAudioBadges({ title, isUltraHd: true, isAtmos: true });
      console.log(JSON.stringify(badges, null, 2));
      break;
    }

    case 'credits': {
      const title = args[1] || 'Blinding Lights';
      console.log(`Extracting track credits for "${title}"...`);
      const credits = client.getTrackCredits({
        title,
        artist: 'The Weeknd',
        album: 'After Hours',
        copyright: '℗ 2020 The Weeknd XO, Inc., manufactured and marketed by Republic Records, a division of UMG Recordings, Inc.',
        isUltraHd: true,
        isAtmos: true,
        isExplicit: false
      });
      console.log(JSON.stringify(credits, null, 2));
      break;
    }

    case 'videos': {
      const artist = args.slice(1).join(' ') || 'The Weeknd';
      console.log(`Finding official music videos for "${artist}"...`);
      const videos = await client.getMusicVideos(artist);
      console.log(JSON.stringify(videos, null, 2));
      break;
    }

    case 'help':
    default: {
      console.log('Streamify Amazon Catalog Engine CLI (Complete Suite)');
      console.log('');
      console.log('Available Commands:');
      console.log('  node cli.mjs home [territory]      Editorial Home & Showcase');
      console.log('  node cli.mjs search <query>        Catalog Search');
      console.log('  node cli.mjs suggest <query>       Instant Suggestions');
      console.log('  node cli.mjs browse <id>           Browse Album/Artist/Playlist/Podcast');
      console.log('  node cli.mjs next <asin>           Next Autoplay Queue');
      console.log('  node cli.mjs thumb <url> [size]    Thumbnail URL Formatter');
      console.log('  node cli.mjs scrape <url>          Scrape External Playlist');
      console.log('  node cli.mjs lyrics <song> <band>  Synchronized Lyrics');
      console.log('  node cli.mjs resolve <song> <band> Hybrid Stream Resolver');
      console.log('  node cli.mjs lossless <song> <band> Lossless Top-Tier Full-Song Stream');
      console.log('  node cli.mjs lossless-full <song> <band> [out.flac]  Whole raw FLAC file (STREAMIFY_FLAC_SOURCE_URL)');
      console.log('  node cli.mjs manifest <song>       DASH MPD Manifest');
      console.log('  node cli.mjs prefetch <song>       Stream Prefetch Descriptor');
      console.log('  node cli.mjs probe                 Live Discovery & Health');
      console.log('  node cli.mjs podcast <query>       Podcast Discovery & Streaming');
      console.log('  node cli.mjs station <seed>        Algorithmic Radio Station Generator');
      console.log('  node cli.mjs badges <title>        Audiophile Quality Badges');
      console.log('  node cli.mjs credits <title>       Personnel & Liner Notes');
      console.log('  node cli.mjs videos <artist>       Official Music Videos');
      break;
    }
  }
}

main().catch(err => {
  console.error('CLI Execution Error:', err.message);
  process.exit(1);
});
