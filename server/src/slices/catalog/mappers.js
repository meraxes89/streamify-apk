export function parseDurationToSeconds(durationStr) {
  if (!durationStr || typeof durationStr !== 'string') return 0;
  const parts = durationStr.trim().split(':');
  if (parts.length === 2) {
    return (parseInt(parts[0], 10) || 0) * 60 + (parseInt(parts[1], 10) || 0);
  } else if (parts.length === 3) {
    return (
      (parseInt(parts[0], 10) || 0) * 3600 +
      (parseInt(parts[1], 10) || 0) * 60 +
      (parseInt(parts[2], 10) || 0)
    );
  }
  return parseInt(durationStr, 10) || 0;
}

export function formatThumbnails(rawThumbnails, videoId, baseUrl = '') {
  const list = (rawThumbnails || []).map((t) => {
    let url = t.url || '';
    if (url.startsWith('//')) url = 'https:' + url;
    if (url && (url.includes('googleusercontent.com') || url.includes('ggpht.com'))) {
      const prefix = (process.env.PUBLIC_BASE_URL || baseUrl || '').replace(/\/+$/, '');
      url = `${prefix}/api/yt/thumb?url=${encodeURIComponent(url)}`;
    }
    return {
      url,
      width: t.width || 120,
      height: t.height || 120,
    };
  });

  if (videoId) {
    list.push({
      url: `https://i.ytimg.com/vi/${videoId}/hqdefault.jpg`,
      width: 480,
      height: 360,
    });
  }
  return list;
}

export function mapHomeSections(data, baseUrl = '') {
  const tabs = data?.contents?.singleColumnBrowseResultsRenderer?.tabs;
  const sectionList = tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents || [];

  const shelves = [];
  for (const s of sectionList) {
    const shelf = s.musicCarouselShelfRenderer || s.musicImmersiveCarouselShelfRenderer;
    if (!shelf) continue;
    let title =
      shelf.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.map((r) => r.text).join('') ||
      'Featured';
    const numItemsPerColumn = shelf.numItemsPerColumn ? parseInt(shelf.numItemsPerColumn, 10) : null;
    const contents = [];

    for (const c of shelf.contents || []) {
      const item = c.musicTwoRowItemRenderer;
      if (item) {
        const itemTitle = item.title?.runs?.map((r) => r.text).join('') || '';
        const itemSubtitle = item.subtitle?.runs?.map((r) => r.text).join('') || '';
        const videoId = item.navigationEndpoint?.watchEndpoint?.videoId || null;
        const browseId = item.navigationEndpoint?.browseEndpoint?.browseId || null;
        const playlistId = item.navigationEndpoint?.watchEndpoint?.playlistId || null;
        const thumbnails = formatThumbnails(
          item.thumbnailRenderer?.musicThumbnailRenderer?.thumbnail?.thumbnails,
          videoId,
          baseUrl
        );
        contents.push({
          title: itemTitle,
          description: itemSubtitle,
          videoId,
          browseId,
          playlistId,
          thumbnails,
          artists: itemSubtitle ? [{ id: null, name: itemSubtitle }] : null,
          album: null,
          isExplicit: false,
          views: null,
        });
      }
      const list = c.musicResponsiveListItemRenderer;
      if (list) {
        const flexCols = list.flexColumns || [];
        const itemTitle = flexCols[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || '';
        const itemSubtitle =
          flexCols[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.map((r) => r.text).join('') || '';
        const videoId =
          list.playlistItemData?.videoId || list.doubleTapCommand?.watchEndpoint?.videoId || null;
        const thumbnails = formatThumbnails(
          list.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails,
          videoId,
          baseUrl
        );
        contents.push({
          title: itemTitle,
          description: itemSubtitle,
          videoId,
          browseId: null,
          playlistId: null,
          thumbnails,
          artists: itemSubtitle ? [{ id: null, name: itemSubtitle }] : null,
          album: null,
          isExplicit: false,
          views: null,
        });
      }
    }

    if (contents.length > 0) {
      const isQuickPicks =
        numItemsPerColumn === 4 ||
        title.toLowerCase().includes('quick pick') ||
        title.toLowerCase().includes('song mix');
      if (isQuickPicks) {
        title = 'Quick picks';
      }
      shelves.push({
        title,
        subtitle: null,
        contents,
        thumbnail: contents[0]?.thumbnails || null,
        itemsPerColumn: isQuickPicks ? 4 : numItemsPerColumn,
      });
    }
  }

  // Pinned Quick Picks guarantee: ensure Quick Picks is at index 0 and never shuffles
  const qpIdx = shelves.findIndex((s) => s.itemsPerColumn === 4 || s.title === 'Quick picks');
  if (qpIdx > 0) {
    const qp = shelves.splice(qpIdx, 1)[0];
    shelves.unshift(qp);
  } else if (qpIdx === -1 && shelves.length > 0) {
    const songShelfIdx = shelves.findIndex(
      (s) => s.contents && s.contents.length >= 8 && s.contents.every((c) => c.videoId)
    );
    if (songShelfIdx !== -1) {
      shelves[songShelfIdx].itemsPerColumn = 4;
      shelves[songShelfIdx].title = 'Quick picks';
      if (songShelfIdx > 0) {
        const qp = shelves.splice(songShelfIdx, 1)[0];
        shelves.unshift(qp);
      }
    }
  }

  return {
    items: shelves,
    continuation: null,
  };
}

export function mapSearchResults(data, baseUrl = '') {
  const contents =
    data?.contents?.tabbedSearchResultsRenderer?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer
      ?.contents;
  const shelf = contents?.[0]?.musicShelfRenderer;
  const songs = [];

  for (const item of shelf?.contents || []) {
    const r = item.musicResponsiveListItemRenderer;
    if (!r) continue;
    const videoId = r.playlistItemData?.videoId || r.doubleTapCommand?.watchEndpoint?.videoId;
    if (!videoId) continue;
    const flexColumns = r.flexColumns || [];
    const title = flexColumns[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || '';
    const col2Runs = flexColumns[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
    let artistName = 'Unknown Artist';
    let albumName = '';
    let duration = '';
    if (col2Runs.length > 0) {
      artistName = col2Runs[0]?.text || 'Unknown Artist';
    }
    if (col2Runs.length >= 3) {
      albumName = col2Runs[2]?.text || '';
    }
    if (col2Runs.length >= 5) {
      duration = col2Runs[4]?.text || '';
    }
    const thumbs = formatThumbnails(
      r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails,
      videoId,
      baseUrl
    );
    songs.push({
      videoId,
      title,
      artists: [{ id: null, name: artistName }],
      album: albumName ? { id: '', name: albumName } : null,
      duration,
      durationSeconds: parseDurationToSeconds(duration),
      thumbnails: thumbs,
      category: 'Song',
      resultType: 'Song',
      isExplicit: false,
      videoType: null,
      feedbackTokens: null,
      year: '',
    });
  }

  return songs;
}

export function mapNextQueue(data, continuation, baseUrl = '') {
  let rawContents = [];
  let nextContinuation = null;

  if (continuation) {
    const contQueue = data?.continuationContents?.playlistPanelContinuation;
    if (contQueue) {
      rawContents = contQueue.contents || [];
      nextContinuation =
        contQueue.continuations?.[0]?.nextRadioContinuationData?.continuation ||
        contQueue.continuations?.[0]?.nextContinuationData?.continuation ||
        null;
    }
  } else {
    const tabs =
      data?.contents?.singleColumnMusicWatchNextResultsRenderer?.tabbedRenderer
        ?.watchNextTabbedResultsRenderer?.tabs;
    const queue = tabs?.[0]?.tabRenderer?.content?.musicQueueRenderer?.content?.playlistPanelRenderer;
    rawContents = queue?.contents || [];
    nextContinuation =
      queue?.continuations?.[0]?.nextRadioContinuationData?.continuation ||
      queue?.continuations?.[0]?.nextContinuationData?.continuation ||
      null;
  }

  const tracks = [];
  for (const c of rawContents) {
    const item = c.playlistPanelVideoRenderer;
    if (!item) continue;
    const vId = item.videoId;
    if (!vId) continue;
    const title = item.title?.runs?.[0]?.text || '';
    const runs = item.longBylineText?.runs || item.shortBylineText?.runs || [];
    const artists = [];
    let album = null;
    let year = null;
    for (const r of runs) {
      const text = r.text?.trim();
      if (!text || text === '•' || text === ',' || text === '&') continue;
      const browseId = r.navigationEndpoint?.browseEndpoint?.browseId;
      const pageType =
        r.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs
          ?.browseEndpointContextMusicConfig?.pageType;
      if (pageType === 'MUSIC_PAGE_TYPE_ARTIST' || (browseId && browseId.startsWith('UC'))) {
        artists.push({ id: browseId, name: text });
      } else if (pageType === 'MUSIC_PAGE_TYPE_ALBUM' || (browseId && browseId.startsWith('MPRE'))) {
        album = { id: browseId, name: text };
      } else if (/^\d{4}$/.test(text)) {
        year = text;
      } else if (artists.length === 0) {
        artists.push({ id: null, name: text });
      }
    }
    const duration = item.lengthText?.runs?.[0]?.text || '';
    const rawThumbs = item.thumbnail?.thumbnails || [];
    const thumbnails = formatThumbnails(rawThumbs, vId, baseUrl);

    tracks.push({
      videoId: vId,
      title,
      artists,
      album,
      duration,
      durationSeconds: parseDurationToSeconds(duration),
      thumbnails,
      year,
    });
  }

  return {
    tracks,
    continuation: nextContinuation,
  };
}

export function mapBrowseResult(data, browseId, baseUrl = '') {
  // 1. Artist page
  const immersiveHeader = data.header?.musicImmersiveHeaderRenderer || data.header?.musicVisualHeaderRenderer;
  if (immersiveHeader) {
    const name = immersiveHeader.title?.runs?.map((r) => r.text).join('') || '';
    const subs =
      immersiveHeader.subscriptionButton?.subscribeButtonRenderer?.subscriberCountText?.runs
        ?.map((r) => r.text)
        .join('') || null;
    const thumbs = formatThumbnails(
      immersiveHeader.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails,
      null,
      baseUrl
    );
    const sec =
      data?.contents?.singleColumnBrowseResultsRenderer?.tabs?.[0]?.tabRenderer?.content
        ?.sectionListRenderer?.contents || [];
    const songs = [];
    for (const s of sec) {
      const shelf = s.musicShelfRenderer || s.musicCarouselShelfRenderer;
      for (const item of shelf?.contents || []) {
        const r = item.musicResponsiveListItemRenderer;
        if (!r) continue;
        const videoId = r.playlistItemData?.videoId || r.doubleTapCommand?.watchEndpoint?.videoId;
        if (!videoId) continue;
        const title = r.flexColumns?.[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || '';
        const col2Runs = r.flexColumns?.[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
        const artistName = col2Runs[0]?.text || name;
        const albumName = col2Runs[2]?.text || '';
        const duration = col2Runs[4]?.text || '';
        const itemThumbs = formatThumbnails(
          r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails,
          videoId,
          baseUrl
        );
        songs.push({
          videoId,
          title,
          artists: [{ id: null, name: artistName }],
          album: albumName ? { id: '', name: albumName } : null,
          duration,
          durationSeconds: parseDurationToSeconds(duration),
          thumbnails: itemThumbs.length > 0 ? itemThumbs : thumbs,
          isExplicit: false,
          isAvailable: true,
          likeStatus: 'INDIFFERENT',
          category: 'Song',
          resultType: 'Song',
          videoType: null,
          feedbackTokens: null,
          year: null,
          views: null,
        });
      }
    }
    return {
      type: 'artist',
      id: browseId,
      name,
      description: null,
      subscribers: subs,
      thumbnails: thumbs,
      songs,
    };
  }

  // 2. Playlist or Album
  const twoCol = data.contents?.twoColumnBrowseResultsRenderer;
  const headerSec = twoCol?.tabs?.[0]?.tabRenderer?.content?.sectionListRenderer?.contents?.[0];
  const respHeader = headerSec?.musicResponsiveHeaderRenderer;
  const title =
    respHeader?.title?.runs?.map((r) => r.text).join('') ||
    data.header?.musicDetailHeaderRenderer?.title?.runs?.map((r) => r.text).join('') ||
    '';
  const subtitle = respHeader?.subtitle?.runs?.map((r) => r.text).join('') || '';
  const description =
    respHeader?.description?.musicDescriptionShelfRenderer?.description?.runs?.map((r) => r.text).join('') ||
    null;
  const thumbs = formatThumbnails(
    respHeader?.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails,
    null,
    baseUrl
  );

  const secContents = twoCol?.secondaryContents?.sectionListRenderer?.contents || [];
  const shelf = secContents[0]?.musicShelfRenderer || secContents[0]?.musicPlaylistShelfRenderer;
  const tracks = [];

  for (const item of shelf?.contents || []) {
    const r = item.musicResponsiveListItemRenderer;
    if (!r) continue;
    const videoId = r.playlistItemData?.videoId || r.doubleTapCommand?.watchEndpoint?.videoId;
    if (!videoId) continue;
    const flexColumns = r.flexColumns || [];
    const trackTitle = flexColumns[0]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.[0]?.text || '';
    const col2Runs = flexColumns[1]?.musicResponsiveListItemFlexColumnRenderer?.text?.runs || [];
    let artistName = subtitle.split('•')[0]?.trim() || 'Unknown Artist';
    let albumName = title;
    let duration = '';
    if (col2Runs.length > 0) {
      artistName = col2Runs[0]?.text || artistName;
    }
    if (col2Runs.length >= 3) {
      albumName = col2Runs[2]?.text || albumName;
    }
    if (col2Runs.length >= 5) {
      duration = col2Runs[4]?.text || '';
    }
    const itemThumbs = formatThumbnails(
      r.thumbnail?.musicThumbnailRenderer?.thumbnail?.thumbnails,
      videoId,
      baseUrl
    );
    tracks.push({
      videoId,
      title: trackTitle,
      artists: [{ id: null, name: artistName }],
      album: albumName ? { id: '', name: albumName } : null,
      duration,
      durationSeconds: parseDurationToSeconds(duration),
      thumbnails: itemThumbs.length > 0 ? itemThumbs : thumbs,
      isExplicit: false,
      isAvailable: true,
      likeStatus: 'INDIFFERENT',
      category: 'Song',
      resultType: 'Song',
      videoType: null,
      feedbackTokens: null,
      year: null,
      views: null,
    });
  }

  const isAlbum = browseId.startsWith('MPREb_');
  return {
    type: isAlbum ? 'album' : 'playlist',
    id: browseId,
    title,
    description,
    subtitle,
    thumbnails: thumbs,
    trackCount: tracks.length,
    tracks,
  };
}
