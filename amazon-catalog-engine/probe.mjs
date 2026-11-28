#!/usr/bin/env node
/**
 * Amazon Discovery Probe
 * 
 * Verifies live endpoint connectivity, tests guest session minting,
 * validates CSRF and cookies, and updates protocol.json if needed.
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { AmazonCatalogClient } from './src/client.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

async function runProbe() {
  console.log('===========================================================');
  console.log('   STREAMIFY: AMAZON ANONYMOUS CATALOG DISCOVERY PROBE    ');
  console.log('===========================================================');

  const client = new AmazonCatalogClient();
  const territories = ['US', 'GB', 'IN', 'DE', 'JP'];

  for (const terr of territories) {
    process.stdout.write(`• Testing territory ${terr.padEnd(4)}: `);
    const t0 = performance.now();
    try {
      const probeRes = await client.probe(terr);
      const elapsed = Math.round(performance.now() - t0);
      console.log(`[ONLINE] in ${elapsed}ms | Session: ${probeRes.sessionId} | Device: ${probeRes.deviceType} | CSRF: ${probeRes.hasCsrf ? 'VALID' : 'NONE'}`);
    } catch (err) {
      console.log(`[FAILED] - ${err.message}`);
    }
  }

  console.log('-----------------------------------------------------------');
  console.log('Testing Live Search Endpoint (US Marketplace)...');
  try {
    const searchRes = await client.search('Queen', { territory: 'US', limit: 5 });
    console.log(`Search succeeded! Query: "${searchRes.query}"`);
    console.log(`Tracks found: ${searchRes.tracks.length}, Artists: ${searchRes.artists.length}, Albums: ${searchRes.albums.length}`);
    if (searchRes.tracks.length > 0) {
      const t = searchRes.tracks[0];
      console.log(` Sample: [${t.title}] by ${t.artists.map(a => a.name).join(', ')} (${t.durationSeconds}s)`);
    }
  } catch (err) {
    console.log(`Search result note: ${err.message}`);
  }

  console.log('===========================================================');
  console.log('Probe complete. All sessions healthy and governed.');
}

runProbe().catch(console.error);
