export async function lyricsRoutes(fastify, options) {
  const service = options.lyricsService;

  fastify.get('/api/yt/lyrics', async (req, reply) => {
    try {
      const { track, artist, duration } = req.query;
      if (!track && !artist) {
        reply.status(400);
        return { error: true, message: 'track or artist query param required' };
      }

      const result = await service.getLyrics({ track, artist, duration });
      return result;
    } catch (err) {
      req.log.error(err, '[lyrics]');
      reply.status(500);
      return { error: true, syncType: null, lines: null };
    }
  });
}
