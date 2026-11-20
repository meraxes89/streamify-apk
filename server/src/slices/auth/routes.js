export async function authPlugin(fastify, options) {
  const roomPin = (process.env.ROOM_PIN || process.env.STREAMIFY_PIN || '').trim();
  if (!roomPin) {
    // No PIN configured; development mode (open access)
    return;
  }

  console.log(`\n🔒 [Room PIN Required] 6-digit access PIN: ${roomPin}`);
  console.log(`   Clients must authenticate via POST /api/auth/join\n`);

  fastify.post('/api/auth/join', async (req, reply) => {
    const body = req.body || {};
    const inputPin = String(body.pin || req.query.pin || '').trim();

    if (inputPin !== roomPin) {
      reply.status(401);
      return { error: true, message: 'Invalid Room PIN' };
    }

    reply.header(
      'Set-Cookie',
      `streamify_pin=${roomPin}; Path=/api; HttpOnly; SameSite=Strict; Max-Age=2592000`
    );
    return { ok: true, status: 'authenticated' };
  });

  fastify.addHook('onRequest', async (req, reply) => {
    const url = req.url;
    // Allow static assets, health, capabilities, and auth endpoints without PIN
    if (
      !url.startsWith('/api/') ||
      url.startsWith('/api/health') ||
      url.startsWith('/api/capabilities') ||
      url.startsWith('/api/auth/join')
    ) {
      return;
    }

    const cookieHeader = req.headers.cookie || '';
    const hasPinCookie = cookieHeader.includes(`streamify_pin=${roomPin}`);
    const authHeader = req.headers.authorization || '';
    const hasPinHeader = authHeader === `Bearer ${roomPin}` || req.headers['x-room-pin'] === roomPin;

    if (!hasPinCookie && !hasPinHeader) {
      reply.status(401);
      return reply.send({
        error: true,
        message: 'Authentication required. Submit Room PIN to /api/auth/join',
      });
    }
  });
}
