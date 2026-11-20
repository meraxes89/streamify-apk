import { WsCodec } from './broadcaster.js';

export async function roomRoutes(fastify, options) {
  const roomManager = options.roomManager;

  // HTTP room diagnostics
  fastify.get('/api/room/list', async () => {
    const rooms = [];
    for (const [id, r] of roomManager.rooms) {
      rooms.push({
        roomId: id,
        membersCount: r.members.size,
        isPlaying: r.isPlaying,
        currentTrack: r.currentTrack?.videoId || null,
        sequenceId: r.sequenceId,
      });
    }
    return { status: 'ok', rooms };
  });

  fastify.get('/api/room/:roomId', async (req, reply) => {
    const roomId = req.params.roomId;
    const r = roomManager.rooms.get(roomId);
    if (!r) {
      reply.status(404);
      return { error: 'Room not found' };
    }
    return {
      roomId,
      membersCount: r.members.size,
      isPlaying: r.isPlaying,
      currentTrack: r.currentTrack?.videoId || null,
      sequenceId: r.sequenceId,
    };
  });

  // Native RFC 6455 WebSocket Upgrade handler on fastify.server
  if (fastify.server) {
    fastify.server.on('upgrade', (req, socket, head) => {
      const url = req.url || '';
      const match = url.match(/\/api\/room\/([^/?]+)\/sync/) || url.match(/\/api\/room\/sync/);

      if (!match) return; // Ignore other non-room upgrades

      const roomId = match[1] || 'default';
      const key = req.headers['sec-websocket-key'];

      if (!key) {
        socket.destroy();
        return;
      }

      const accept = WsCodec.createAcceptHeader(key);
      socket.write(
        'HTTP/1.1 101 Switching Protocols\r\n' +
          'Upgrade: websocket\r\n' +
          'Connection: Upgrade\r\n' +
          'Sec-WebSocket-Accept: ' +
          accept +
          '\r\n\r\n'
      );

      const clientIp = req.headers['x-forwarded-for'] || req.socket.remoteAddress || '127.0.0.1';
      const room = roomManager.getOrCreateRoom(roomId);
      room.addMember(socket, String(clientIp));
    });
  }
}
