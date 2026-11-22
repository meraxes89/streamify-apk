#!/usr/bin/env node
/**
 * Standalone CLI for Amazon Catalog Engine (12-API Suite)
 * 
 * Usage:
 *   node cli.mjs probe                 Run live discovery probe (API 12)
 *   node cli.mjs session [territory]   Print current session metadata
 *   node cli.mjs home [territory]      Fetch Home Showcase & Editorial (API 1)
 *   node cli.mjs search <query>        Search catalog (API 2)
 *   node cli.mjs suggest <query>       Instant suggestions (API 3)
 *   node cli.mjs browse <id>           Browse album/playlist/artist (API 4)
 *   node cli.mjs next <asin>           Fetch autoplay queue (API 5)
 *   node cli.mjs thumb <url> [size]    Scale artwork thumbnail (API 6)
 *   node cli.mjs scrape <url>          Scrape external playlist link (API 7)
 *   node cli.mjs lyrics <song> <band>  Fetch synced lyrics via LRCLIB (API 8)
 *   node cli.mjs resolve <title> [art] Resolve audio stream URL (API 9)
 *   node cli.mjs manifest <title>      Generate DASH MPD manifest (API 10)
 *   node cli.mjs prefetch <title>      Prefetch descriptor (API 11)
 */

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

    case 'help':
    default: {
      console.log('Streamify Amazon Catalog Engine CLI (12-API Suite)');
      console.log('');
      console.log('Available Commands:');
      console.log('  node cli.mjs home [territory]      (API 1)  Editorial Home & Showcase');
      console.log('  node cli.mjs search <query>        (API 2)  Catalog Search');
      console.log('  node cli.mjs suggest <query>       (API 3)  Instant Suggestions');
      console.log('  node cli.mjs browse <id>           (API 4)  Browse Album/Artist/Playlist');
      console.log('  node cli.mjs next <asin>           (API 5)  Next Autoplay Queue');
      console.log('  node cli.mjs thumb <url> [size]    (API 6)  Thumbnail URL Formatter');
      console.log('  node cli.mjs scrape <url>          (API 7)  Scrape External Playlist');
      console.log('  node cli.mjs lyrics <song> <band>  (API 8)  Synchronized Lyrics');
      console.log('  node cli.mjs resolve <song> <band> (API 9)  Hybrid Stream Resolver');
      console.log('  node cli.mjs manifest <song>       (API 10) DASH MPD Manifest');
      console.log('  node cli.mjs prefetch <song>       (API 11) Stream Prefetch Descriptor');
      console.log('  node cli.mjs probe                 (API 12) Live Discovery & Health');
      break;
    }
  }
}

main().catch(err => {
  console.error('CLI Execution Error:', err.message);
  process.exit(1);
});
