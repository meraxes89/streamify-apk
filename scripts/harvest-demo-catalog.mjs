import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';

const PORT = 3001;
const BASE_URL = `http://127.0.0.1:${PORT}`;

async function wait(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function fetchJson(endpoint) {
  try {
    const res = await fetch(`${BASE_URL}${endpoint}`);
    if (!res.ok) {
      console.warn(`[warn] ${endpoint} returned ${res.status}`);
      return null;
    }
    return await res.json();
  } catch (err) {
    console.error(`[error] fetch failed for ${endpoint}:`, err.message);
    return null;
  }
}

async function main() {
  console.log('Starting Streamify Relay Server on port', PORT, '...');
  const serverProc = spawn('node', ['server/bin/streamify-server.mjs'], {
    env: { ...process.env, PORT: String(PORT) },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  serverProc.stdout.on('data', (d) => process.stdout.write(`[server] ${d}`));
  serverProc.stderr.on('data', (d) => process.stderr.write(`[server:err] ${d}`));

  // Wait for server ready
  let ready = false;
  for (let i = 0; i < 30; i++) {
    await wait(1000);
    try {
      const res = await fetch(`${BASE_URL}/api/yt/home`);
      if (res.ok) {
        ready = true;
        break;
      }
    } catch (_) {}
  }

  if (!ready) {
    serverProc.kill('SIGKILL');
    console.error('Server failed to start in time.');
    process.exit(1);
  }

  console.log('Server ready! Harvester running...');

  try {
    // 1. Home
    console.log('Fetching Home...');
    const home = await fetchJson('/api/yt/home');

    // Extract item IDs from Home
    const videoIds = new Set();
    const playlistIds = new Set();
    const browseIds = new Set();

    if (home?.items) {
      for (const shelf of home.items) {
        for (const c of shelf.contents || []) {
          if (c.videoId) videoIds.add(c.videoId);
          if (c.playlistId) playlistIds.add(c.playlistId);
          if (c.browseId) browseIds.add(c.browseId);
          for (const a of c.artists || []) {
            if (a.id) browseIds.add(a.id);
          }
        }
      }
    }

    // 2. Search suggestions & queries
    console.log('Fetching Search Suggestions...');
    const searchQueries = ['The Weeknd', 'Lo-Fi Chill', 'Taylor Swift', 'Daft Punk', 'Workout'];
    const searchResults = {};
    const searchSuggestions = {};

    for (const q of searchQueries) {
      console.log(`Searching: ${q}...`);
      const res = await fetchJson(`/api/yt/search?q=${encodeURIComponent(q)}`);
      if (res) searchResults[q.toLowerCase()] = res;
      const sug = await fetchJson(`/api/yt/suggest?q=${encodeURIComponent(q)}`);
      if (sug) searchSuggestions[q.toLowerCase()] = sug;
      await wait(300);
    }

    // 3. Featured Playlists & Albums & Artists (Browse)
    console.log('Fetching Browse details...');
    const browseData = {};
    const sampleBrowseIds = Array.from(browseIds).slice(0, 10);
    const samplePlaylistIds = Array.from(playlistIds).slice(0, 5);

    for (const bId of sampleBrowseIds) {
      console.log(`Browse: ${bId}...`);
      const bRes = await fetchJson(`/api/yt/browse?id=${encodeURIComponent(bId)}`);
      if (bRes) browseData[bId] = bRes;
      await wait(300);
    }

    for (const pId of samplePlaylistIds) {
      console.log(`Playlist: ${pId}...`);
      const pRes = await fetchJson(`/api/yt/browse?id=${encodeURIComponent(pId)}`);
      if (pRes) browseData[pId] = pRes;
      await wait(300);
    }

    // 4. Queues & Lyrics for featured tracks
    console.log('Fetching Next & Lyrics for featured tracks...');
    const playerData = {};
    const sampleVideoIds = Array.from(videoIds).slice(0, 8);

    for (const vId of sampleVideoIds) {
      console.log(`Player data: ${vId}...`);
      const next = await fetchJson(`/api/yt/next?videoId=${encodeURIComponent(vId)}`);
      const lyrics = await fetchJson(`/api/yt/lyrics?videoId=${encodeURIComponent(vId)}`);
      playerData[vId] = { next, lyrics };
      await wait(300);
    }

    // 5. Build Seed Library & Synthetic Analytics
    console.log('Building Seed Library & Analytics...');
    const seedSongs = [];
    let idx = 0;
    if (home?.items) {
      for (const shelf of home.items) {
        for (const c of shelf.contents || []) {
          if (c.videoId && seedSongs.length < 25) {
            seedSongs.push({
              videoId: c.videoId,
              title: c.title || 'Unknown Title',
              artistName: c.artists?.[0]?.name || 'Streamify Artist',
              albumName: c.album?.name || 'Popular Hits',
              duration: c.duration || '3:30',
              durationSeconds: c.durationSeconds || 210,
              thumbnails: c.thumbnails?.[0]?.url || '',
              liked: idx % 3 === 0,
              totalPlayTime: (30 - idx) * 350,
            });
            idx++;
          }
        }
      }
    }

    const seedPlaylists = [
      {
        id: 'demo_favs',
        title: 'Streamify Top Favorites',
        author: 'Streamify Curated',
        trackCount: seedSongs.slice(0, 8).length,
        tracks: seedSongs.slice(0, 8),
      },
      {
        id: 'demo_chill',
        title: 'Late Night Chill & Lo-Fi',
        author: 'Streamify Curated',
        trackCount: seedSongs.slice(8, 16).length,
        tracks: seedSongs.slice(8, 16),
      },
      {
        id: 'demo_focus',
        title: 'Deep Focus & Ambient Beats',
        author: 'Streamify Curated',
        trackCount: seedSongs.slice(16, 24).length,
        tracks: seedSongs.slice(16, 24),
      },
    ];

    // Analytics: 30 days listening time, artists, albums, playback counts
    const artistPlayTimes = {};
    const albumPlayCounts = {};
    for (const s of seedSongs) {
      artistPlayTimes[s.artistName] = (artistPlayTimes[s.artistName] || 0) + s.totalPlayTime;
      albumPlayCounts[s.albumName] = (albumPlayCounts[s.albumName] || 0) + Math.floor(s.totalPlayTime / 180);
    }

    const topArtists = Object.entries(artistPlayTimes)
      .map(([name, time]) => ({ name, listeningTime: time }))
      .sort((a, b) => b.listeningTime - a.listeningTime)
      .slice(0, 5);

    const topAlbums = Object.entries(albumPlayCounts)
      .map(([name, playCount]) => ({ name, playCount }))
      .sort((a, b) => b.playCount - a.playCount)
      .slice(0, 5);

    const analytics = {
      totalListeningTimeSeconds: seedSongs.reduce((acc, s) => acc + s.totalPlayTime, 0),
      totalPlaybackCount: seedSongs.length * 12,
      topArtists,
      topAlbums,
      topSongs: seedSongs.slice(0, 10).map((s) => ({
        videoId: s.videoId,
        title: s.title,
        artistName: s.artistName,
        totalPlayTime: s.totalPlayTime,
        playCount: Math.floor(s.totalPlayTime / 150),
      })),
    };

    const finalBundle = {
      version: '1.0.0',
      generatedAt: new Date().toISOString(),
      home,
      search: {
        suggestions: searchSuggestions,
        results: searchResults,
      },
      browse: browseData,
      player: playerData,
      seedLibrary: {
        songs: seedSongs,
        playlists: seedPlaylists,
      },
      analytics,
    };

    const outPath = path.resolve('demo-catalog.json');
    await fs.writeFile(outPath, JSON.stringify(finalBundle, null, 2), 'utf-8');
    const stat = await fs.stat(outPath);
    console.log(`Successfully generated ${outPath} (${(stat.size / 1024).toFixed(1)} KB)`);

  } finally {
    serverProc.kill('SIGTERM');
  }
}

main().catch((e) => {
  console.error('Fatal harvest error:', e);
  process.exit(1);
});
