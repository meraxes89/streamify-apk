export async function streamRoutes(fastify, options) {
  const gateway = options.gateway;

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
}
