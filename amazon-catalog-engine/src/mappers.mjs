/**
 * Canonical Data Mappers
 * 
 * Transforms Amazon Music JSON structures (Coral RPC / Hydration JSON / Microdata)
 * into Streamify's canonical multiplatform data models.
 */

export function formatAmazonArtwork(url, targetSize = 500) {
  if (!url || typeof url !== 'string') return [];
  
  // Amazon media URLs often contain scaling templates like ._SX300_. or ._SY300_.
  // We provide both original and target scaled versions
  const thumbnails = [
    { url, width: targetSize, height: targetSize }
  ];

  if (url.includes('._S')) {
    const highRes = url.replace(/\._S[XY]\d+_\./, `._SX${targetSize}_.`);
    thumbnails.unshift({ url: highRes, width: targetSize, height: targetSize });
  }

  return thumbnails;
}

export function parseYearFromDate(dateStr) {
  if (!dateStr || typeof dateStr !== 'string') return null;
  const match = dateStr.match(/\b(19\d\d|20\d\d)\b/);
  return match ? parseInt(match[1], 10) : null;
}

export function parseDuration(duration) {
  if (typeof duration === 'number') {
    // If > 10000, likely milliseconds
    return duration > 10000 ? Math.round(duration / 1000) : Math.round(duration);
  }
  if (typeof duration === 'string') {
    // Check mm:ss or hh:mm:ss
    const parts = duration.split(':');
    if (parts.length === 2) {
      return (parseInt(parts[0], 10) || 0) * 60 + (parseInt(parts[1], 10) || 0);
    }
    if (parts.length === 3) {
      return (parseInt(parts[0], 10) || 0) * 3600 + (parseInt(parts[1], 10) || 0) * 60 + (parseInt(parts[2], 10) || 0);
    }
    // Check ISO PT3M45S
    const isoMatch = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
    if (isoMatch) {
      const h = parseInt(isoMatch[1] || 0, 10);
      const m = parseInt(isoMatch[2] || 0, 10);
      const s = parseInt(isoMatch[3] || 0, 10);
      return h * 3600 + m * 60 + s;
    }
  }
  return 0;
}

/**
 * Maps an Amazon track item into Streamify canonical Track format.
 */
export function mapAmazonTrack(item) {
  if (!item) return null;

  const asin = item.asin || item.id || item.trackId || '';
  const title = item.title || item.name || '';
  
  // Extract artist(s)
  let artists = [];
  if (Array.isArray(item.artists)) {
    artists = item.artists.map(a => ({
      name: a.name || a.title || '',
      id: a.asin ? `amzn:artist:${a.asin}` : (a.id ? `amzn:artist:${a.id}` : '')
    }));
  } else if (item.artistName || item.artist) {
    const artistName = item.artistName || (typeof item.artist === 'string' ? item.artist : item.artist?.name);
    artists.push({
      name: artistName || '',
      id: item.artistAsin ? `amzn:artist:${item.artistAsin}` : ''
    });
  }

  // Extract album
  let album = null;
  if (item.album) {
    album = {
      name: item.album.name || item.album.title || '',
      id: item.album.asin ? `amzn:album:${item.album.asin}` : ''
    };
  } else if (item.albumName || item.albumTitle) {
    album = {
      name: item.albumName || item.albumTitle,
      id: item.albumAsin ? `amzn:album:${item.albumAsin}` : ''
    };
  }

  const artUrl = item.artOriginal || item.image || item.albumArt || item.artUrl || item.thumbnail;
  const thumbnails = formatAmazonArtwork(artUrl);

  const durationSeconds = parseDuration(item.duration || item.durationMs || item.durationSeconds);
  const releaseDate = item.originalReleaseDate || item.releaseDate || null;
  const releaseYear = parseYearFromDate(releaseDate);
  const label = item.copyright || item.label || item.recordLabel || null;

  return {
    id: `amzn:track:${asin}`,
    videoId: `amzn:${asin}`,
    title,
    artists,
    album,
    durationSeconds,
    thumbnails,
    releaseDate,
    releaseYear,
    label,
    source: 'amazon',
    rawAsin: asin
  };
}

/**
 * Maps an Amazon album item into Streamify canonical Album format.
 */
export function mapAmazonAlbum(item) {
  if (!item) return null;

  const asin = item.asin || item.id || '';
  const title = item.title || item.name || '';
  const artistName = item.artistName || (typeof item.artist === 'string' ? item.artist : item.artist?.name) || '';
  const artUrl = item.artOriginal || item.image || item.thumbnail;
  const releaseDate = item.originalReleaseDate || item.releaseDate || null;

  return {
    id: `amzn:album:${asin}`,
    title,
    artist: {
      name: artistName,
      id: item.artistAsin ? `amzn:artist:${item.artistAsin}` : ''
    },
    thumbnails: formatAmazonArtwork(artUrl),
    releaseDate,
    releaseYear: parseYearFromDate(releaseDate),
    trackCount: item.trackCount || (item.tracks ? item.tracks.length : 0),
    tracks: Array.isArray(item.tracks) ? item.tracks.map(mapAmazonTrack).filter(Boolean) : [],
    source: 'amazon',
    rawAsin: asin
  };
}

/**
 * Maps an Amazon playlist item into Streamify canonical Playlist format.
 */
export function mapAmazonPlaylist(item) {
  if (!item) return null;

  const asin = item.asin || item.id || item.playlistId || '';
  const title = item.title || item.name || '';
  const artUrl = item.artOriginal || item.image || item.thumbnail;

  return {
    id: `amzn:playlist:${asin}`,
    title,
    author: item.author || item.creator || 'Amazon Music',
    description: item.description || '',
    thumbnails: formatAmazonArtwork(artUrl),
    trackCount: item.trackCount || (item.tracks ? item.tracks.length : 0),
    tracks: Array.isArray(item.tracks) ? item.tracks.map(mapAmazonTrack).filter(Boolean) : [],
    source: 'amazon',
    rawAsin: asin
  };
}

/**
 * Maps an Amazon artist item into Streamify canonical Artist format.
 */
export function mapAmazonArtist(item) {
  if (!item) return null;

  const asin = item.asin || item.id || '';
  const name = item.name || item.title || '';
  const artUrl = item.artOriginal || item.image || item.thumbnail;

  return {
    id: `amzn:artist:${asin}`,
    name,
    thumbnails: formatAmazonArtwork(artUrl),
    source: 'amazon',
    rawAsin: asin
  };
}
