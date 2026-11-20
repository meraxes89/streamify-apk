export async function thumbRoutes(fastify, options) {
  const service = options.thumbService;

  fastify.get('/api/yt/thumb', async (req, reply) => {
    try {
      const rawUrl = req.query.url;
      if (!rawUrl) {
        reply.status(400);
        return reply.send('url parameter required');
      }

      const { contentType, buffer } = await service.fetchThumbnail(rawUrl);
      reply
        .header('Content-Type', contentType)
        .header('Cache-Control', 'public, max-age=86400, no-transform')
        .header('Access-Control-Allow-Origin', '*');

      return reply.send(buffer);
    } catch (err) {
      const status = err.statusCode || 500;
      reply.status(status);
      return reply.send(err.message);
    }
  });
}
