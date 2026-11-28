import crypto from 'node:crypto';

function constantTimeEquals(a, b) {
  const bufA = Buffer.from(String(a), 'utf8');
  const bufB = Buffer.from(String(b), 'utf8');
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

export async function authPlugin(fastify, options) {
  const roomPin = (process.env.ROOM_PIN || process.env.STREAMIFY_PIN || '').trim();
  if (!roomPin) {
    // No PIN configured; development mode (open access)
    return;
  }

  console.log('\n🔒 [Room PIN Required] Clients must authenticate via POST /api/auth/join\n');

  const attemptsByIp = new Map();
  const RATE_WINDOW_MS = 60_000;
  const MAX_ATTEMPTS = 10;

  fastify.post('/api/auth/join', async (req, reply) => {
    const ip = req.ip || 'unknown';
    const now = Date.now();
    const record = attemptsByIp.get(ip);
    if (record && now - record.windowStart < RATE_WINDOW_MS) {
      if (record.count >= MAX_ATTEMPTS) {
        reply.status(429);
        return { error: true, message: 'Too many authentication attempts. Try again later.' };
      }
      record.count += 1;
    } else {
      attemptsByIp.set(ip, { windowStart: now, count: 1 });
    }

    const body = req.body || {};
    const inputPin = String(body.pin || req.query.pin || '').trim();

    if (!constantTimeEquals(inputPin, roomPin)) {
      reply.status(401);
      return { error: true, message: 'Invalid Room PIN' };
    }

    attemptsByIp.delete(ip);
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
    const cookiePin = cookieHeader
      .split(';')
      .map((p) => p.trim())
      .find((p) => p.startsWith('streamify_pin='))
      ?.slice('streamify_pin='.length) || '';
    const hasPinCookie = constantTimeEquals(cookiePin, roomPin);

    const authHeader = req.headers.authorization || '';
    const bearerPin = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : '';
    const headerPin = String(req.headers['x-room-pin'] || '');
    const hasPinHeader = constantTimeEquals(bearerPin, roomPin) || constantTimeEquals(headerPin, roomPin);

    if (!hasPinCookie && !hasPinHeader) {
      reply.status(401);
      return reply.send({
        error: true,
        message: 'Authentication required. Submit Room PIN to /api/auth/join',
      });
    }
  });
}
