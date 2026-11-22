export async function catalogRoutes(fastify, options) {
  const service = options.catalogService;

  fastify.get('/api/yt/home', async (req, reply) => {
    try {
      const gl = req.query.gl || req.headers['x-region'] || 'IN';
      const result = await service.getHome({ gl, baseUrl: '' });

      // P2: Serve pre-serialized UTF-8 buffer directly with zero V8 serialization overhead
      if (result?.rawBuf) {
        return reply.type('application/json; charset=utf-8').send(result.rawBuf);
      }
      return result?.data || result;
    } catch (err) {
      req.log.error(err, '[catalog:home]');
      reply.status(500);
      return { error: err.message, items: [] };
    }
  });

  fastify.get('/api/yt/search', async (req, reply) => {
    try {
      const query = req.query.q || '';
      const gl = req.query.gl || req.headers['x-region'] || 'IN';
      const result = await service.searchSongs({ query, gl, baseUrl: '' });

      // P2: Serve pre-serialized UTF-8 buffer directly
      if (result?.rawBuf) {
        return reply.type('application/json; charset=utf-8').send(result.rawBuf);
      }
      return result?.data || result;
    } catch (err) {
      req.log.error(err, '[catalog:search]');
      reply.status(500);
      return { error: err.message, songs: [] };
    }
  });

  fastify.get('/api/yt/next', async (req, reply) => {
    try {
      const videoId = req.query.videoId || null;
      const playlistId = req.query.playlistId || null;
      const continuation = req.query.continuation || null;
      const gl = req.query.gl || req.headers['x-region'] || 'IN';

      const result = await service.getNext({
        videoId,
        playlistId,
        continuation,
        gl,
        baseUrl: '',
      });

      if (result?.rawBuf) {
        return reply.type('application/json; charset=utf-8').send(result.rawBuf);
      }
      return result?.data || result;
    } catch (err) {
      req.log.error(err, '[catalog:next]');
      reply.status(500);
      return { error: err.message, tracks: [], continuation: null };
    }
  });

  fastify.get('/api/yt/suggest', async (req, reply) => {
    try {
      const query = req.query.q || '';
      const suggestions = await service.getSuggestions({ query });

      // P2: Debounced Top-1 Suggestion Pre-Warm with per-client cancellation (query >= 3 chars)
      if (query.trim().length >= 3 && suggestions.length > 0) {
        const clientIp = req.headers['x-forwarded-for'] || req.ip || '127.0.0.1';
        service.prewarmTopSuggestion(suggestions[0], clientIp, req.query.gl || 'IN');
      }

      return suggestions;
    } catch (err) {
      req.log.error(err, '[catalog:suggest]');
      reply.status(500);
      return [];
    }
  });

  fastify.get('/api/yt/browse', async (req, reply) => {
    try {
      const browseId = req.query.id || req.query.browseId;
      if (!browseId) {
        reply.status(400);
        return { error: 'browseId required' };
      }
      const gl = req.query.gl || req.headers['x-region'] || 'IN';
      const result = await service.browse({ browseId, gl, baseUrl: '' });

      if (result?.rawBuf) {
        return reply.type('application/json; charset=utf-8').send(result.rawBuf);
      }
      return result?.data || result;
    } catch (err) {
      req.log.error(err, '[catalog:browse]');
      reply.status(500);
      return { error: err.message, tracks: [] };
    }
  });

  // External Playlist Scraper & Solver Endpoint
  fastify.get('/api/playlist/scrape', async (req, reply) => {
    try {
      const url = req.query.url;
      if (!url) {
        reply.status(400);
        return { error: 'url parameter required' };
      }
      const scraped = await service.scrapePlaylist(url);
      return scraped;
    } catch (err) {
      req.log.error(err, '[playlist:scrape]');
      reply.status(500);
      return { error: err.message, tracks: [] };
    }
  });

  fastify.post('/api/playlist/scrape', async (req, reply) => {
    try {
      const url = req.body?.url || req.query.url;
      if (!url) {
        reply.status(400);
        return { error: 'url field in body or query required' };
      }
      const scraped = await service.scrapePlaylist(url);
      return scraped;
    } catch (err) {
      req.log.error(err, '[playlist:scrape]');
      reply.status(500);
      return { error: err.message, tracks: [] };
    }
  });
}
