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

  // L4 SABR: Dynamic DASH MPD Manifest for Media3 / Shaka / CMP players
  fastify.get('/api/stream/:videoId/manifest.mpd', async (req, reply) => {
    const videoId = req.params.videoId;
    let desc;
    try {
      desc = await gateway.resolver.resolve(videoId);
    } catch (err) {
      reply.status(502);
      return reply.send({ error: `Resolve error: ${err.message}` });
    }

    const manifest = gateway.sabrEngine?.generateDashMpd({
      videoId,
      durationSec: (desc.durationMs || 180000) / 1000,
      formats: desc.formats || [{ itag: desc.itag || 251, mimeType: desc.mimeType, bitrate: 160000 }],
      baseUrl: `${req.protocol}://${req.hostname}`,
    });

    reply.header('Content-Type', 'application/dash+xml');
    return reply.send(manifest);
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
