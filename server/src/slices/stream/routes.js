export async function streamRoutes(fastify, options) {
  const gateway = options.gateway;
  const prefetch = options.prefetch;

  const handler = async (req, reply) => {
    return gateway.handleRequest(req, reply);
  };

  fastify.route({
    method: ['GET', 'HEAD'],
    url: '/api/stream',
    handler,
  });

  fastify.route({
    method: ['GET', 'HEAD'],
    url: '/api/stream/:videoId',
    handler,
  });

  // P2: Two-Stage Preemptible Prefetching API
  if (prefetch) {
    fastify.post('/api/stream/prefetch', async (req, reply) => {
      const body = req.body || {};
      const videoId = body.videoId || req.query.videoId;
      const stage = parseInt(body.stage || req.query.stage || '1', 10);

      if (!videoId) {
        reply.status(400);
        return { error: 'videoId required' };
      }

      if (stage === 2) {
        const result = await prefetch.prefetchBytes(videoId);
        return result;
      }

      // Default Stage 1: Descriptor resolve only
      const result = await prefetch.prefetchDescriptor(videoId);
      return result;
    });

    fastify.post('/api/stream/prefetch/cancel', async (req) => {
      const body = req.body || {};
      const videoId = body.videoId || req.query.videoId;
      prefetch.cancel(videoId);
      return { status: 'cancelled', videoId: videoId || 'all' };
    });

    fastify.get('/api/stream/prefetch/status', async () => {
      return prefetch.getMetrics();
    });
  }
}
