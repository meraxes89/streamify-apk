export async function catalogRoutes(fastify, options) {
  const service = options.catalogService;

  fastify.get('/api/yt/home', async (req, reply) => {
    try {
      const gl = req.query.gl || req.headers['x-region'] || 'IN';
      const data = await service.getHome({ gl, baseUrl: '' });
      return data;
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
      const songs = await service.searchSongs({ query, gl, baseUrl: '' });
      return songs;
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

      const queue = await service.getNext({
        videoId,
        playlistId,
        continuation,
        gl,
        baseUrl: '',
      });
      return queue;
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
      const data = await service.browse({ browseId, gl, baseUrl: '' });
      return data;
    } catch (err) {
      req.log.error(err, '[catalog:browse]');
      reply.status(500);
      return { error: err.message, tracks: [] };
    }
  });
}
