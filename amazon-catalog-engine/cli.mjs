#!/usr/bin/env node
/**
 * Standalone CLI for Amazon Catalog Engine
 * 
 * Usage:
 *   node cli.mjs probe                 Run live discovery probe
 *   node cli.mjs session [territory]   Print current session metadata
 *   node cli.mjs search <query>        Search catalog
 *   node cli.mjs album <urlOrAsin>     Inspect album
 *   node cli.mjs playlist <urlOrAsin>  Inspect playlist
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

    case 'search': {
      const query = args.slice(1).filter(a => !a.startsWith('--')).join(' ');
      if (!query) {
        console.error('Error: Please provide a search query.');
        console.error('Example: node cli.mjs search "The Beatles"');
        process.exit(1);
      }

      console.log(`Searching for "${query}" on Amazon Music...`);
      const results = await client.search(query, { territory: 'US', limit: 5 });
      console.log(JSON.stringify({
        query: results.query,
        tracksCount: results.tracks.length,
        tracks: results.tracks,
        albumsCount: results.albums.length,
        albums: results.albums,
        artistsCount: results.artists.length,
        artists: results.artists
      }, null, 2));
      break;
    }

    case 'playlist':
    case 'album': {
      const target = args[1];
      if (!target) {
        console.error(`Error: Please provide an ASIN or URL for ${command}.`);
        process.exit(1);
      }
      console.log(`Fetching ${command}: ${target}...`);
      const fn = command === 'album' ? client.getAlbum.bind(client) : client.getPlaylist.bind(client);
      const res = await fn(target);
      console.log(JSON.stringify(res, null, 2));
      break;
    }

    case 'help':
    default: {
      console.log('Streamify Amazon Catalog Engine CLI');
      console.log('');
      console.log('Commands:');
      console.log('  node cli.mjs probe                 Run live diagnostic probe');
      console.log('  node cli.mjs session [territory]   Inspect session token');
      console.log('  node cli.mjs search <query>        Search tracks, albums, artists');
      console.log('  node cli.mjs album <asin|url>      Inspect album details');
      console.log('  node cli.mjs playlist <asin|url>   Inspect playlist details');
      break;
    }
  }
}

main().catch(err => {
  console.error('CLI Execution Error:', err.message);
  process.exit(1);
});
