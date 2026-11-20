import crypto from 'node:crypto';
import { ClockSync } from './clock-sync.js';
import { EbmlOpusExtractor } from '../stream/ebml-opus-extractor.js';

export const PLAYOUT_HORIZON_NS = 500_000_000n; // 500 ms playout horizon (comm9.md Section 1)
export const FRAMES_PER_BATCH = 5; // 5 x 20ms frames = 100 ms per broadcast packet
export const MAX_LAG_BYTES = 64 * 1024; // 64 KiB socket buffer ceiling before dropping

/**
 * RFC 6455 WebSocket Frame Utilities (Pure Node.js, Zero Native Addons)
 */
export class WsCodec {
  static createAcceptHeader(key) {
    return crypto
      .createHash('sha1')
      .update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11')
      .digest('base64');
  }

  static encodeFrame(payload, isBinary = true) {
    const buf = typeof payload === 'string' ? Buffer.from(payload, 'utf8') : payload;
    const len = buf.length;
    let header;
    const firstByte = isBinary ? 0x82 : 0x81; // FIN=1, Opcode=2(bin) or 1(text)

    if (len < 126) {
      header = Buffer.from([firstByte, len]);
    } else if (len < 65536) {
      header = Buffer.allocUnsafe(4);
      header[0] = firstByte;
      header[1] = 126;
      header.writeUInt16BE(len, 2);
    } else {
      header = Buffer.allocUnsafe(10);
      header[0] = firstByte;
      header[1] = 127;
      header.writeBigUInt64BE(BigInt(len), 2);
    }

    return Buffer.concat([header, buf]);
  }

  static decodeClientFrame(buf) {
    if (buf.length < 2) return null;
    const opcode = buf[0] & 0x0f;
    const isMasked = (buf[1] & 0x80) !== 0;
    let len = buf[1] & 0x7f;
    let offset = 2;

    if (len === 126) {
      if (buf.length < 4) return null;
      len = buf.readUInt16BE(2);
      offset = 4;
    } else if (len === 127) {
      if (buf.length < 10) return null;
      len = Number(buf.readBigUInt64BE(2));
      offset = 10;
    }

    if (!isMasked) {
      if (buf.length < offset + len) return null;
      return { opcode, payload: buf.subarray(offset, offset + len), totalBytes: offset + len };
    }

    if (buf.length < offset + 4 + len) return null;
    const mask = buf.subarray(offset, offset + 4);
    offset += 4;
    const raw = buf.subarray(offset, offset + len);
    const unmasked = Buffer.allocUnsafe(len);
    for (let i = 0; i < len; i++) {
      unmasked[i] = raw[i] ^ mask[i % 4];
    }

    return { opcode, payload: unmasked, totalBytes: offset + len };
  }
}

/**
 * RoomMember represents a single connected room speaker / client
 */
export class RoomMember {
  constructor(socket, clientIp = '127.0.0.1') {
    this.socket = socket;
    this.clientIp = clientIp;
    this.joinedAt = Date.now();
    this.sentPackets = 0;
    this.droppedPackets = 0;
    this.buffer = Buffer.alloc(0);
  }

  sendJson(obj) {
    if (this.socket.destroyed) return;
    const frame = WsCodec.encodeFrame(JSON.stringify(obj), false);
    this.socket.write(frame);
  }
}

/**
 * Multi-Room Synchronized Broadcaster (comm9.md Frontier 2)
 */
export class RoomBroadcaster {
  constructor({ roomId, cache } = {}) {
    this.roomId = roomId;
    this.cache = cache;
    /** @type {Set<RoomMember>} */
    this.members = new Set();
    this.currentTrack = null;
    this.isPlaying = false;
    this.sequenceId = 0;
    this.timer = null;
    this.framesQueue = [];
    this.currentReader = null;
  }

  addMember(socket, clientIp = '127.0.0.1') {
    const member = new RoomMember(socket, clientIp);
    this.members.add(member);

    // Disable Nagle on raw TCP socket
    if (typeof socket.setNoDelay === 'function') {
      socket.setNoDelay(true);
    }

    socket.on('data', (chunk) => {
      member.buffer = Buffer.concat([member.buffer, chunk]);
      while (member.buffer.length >= 2) {
        const decoded = WsCodec.decodeClientFrame(member.buffer);
        if (!decoded) break;
        member.buffer = member.buffer.subarray(decoded.totalBytes);

        if (decoded.opcode === 0x08) {
          // Close frame
          socket.end();
          break;
        } else if (decoded.opcode === 0x09) {
          // Ping frame -> respond with pong
          const pong = WsCodec.encodeFrame(decoded.payload, false);
          pong[0] = 0x8a; // Pong opcode
          socket.write(pong);
        } else if (decoded.opcode === 0x01) {
          // Text JSON message
          try {
            const msg = JSON.parse(decoded.payload.toString('utf8'));
            this.handleClientMessage(member, msg);
          } catch {}
        }
      }
    });

    socket.on('close', () => {
      this.removeMember(member);
    });

    socket.on('error', () => {
      this.removeMember(member);
    });

    // Send initial room status
    member.sendJson({
      type: 'room_joined',
      roomId: this.roomId,
      membersCount: this.members.size,
      isPlaying: this.isPlaying,
      currentTrack: this.currentTrack?.videoId || null,
    });

    return member;
  }

  removeMember(member) {
    this.members.delete(member);
    if (this.members.size === 0) {
      this.pause();
    }
  }

  handleClientMessage(member, msg) {
    if (!msg || typeof msg !== 'object') return;

    switch (msg.type) {
      case 'sync_ping': {
        // High-precision monotonic clock sync response
        const pong = ClockSync.handlePing(msg);
        member.sendJson(pong);
        break;
      }

      case 'play': {
        if (msg.track) {
          this.startTrack(msg.track);
        } else {
          this.resume();
        }
        break;
      }

      case 'pause': {
        this.pause();
        break;
      }

      default:
        break;
    }
  }

  async startTrack(desc) {
    this.currentTrack = desc;
    this.sequenceId = 0;
    this.framesQueue = [];
    this.isPlaying = true;

    if (this.currentReader) {
      this.currentReader.destroy();
      this.currentReader = null;
    }

    // Single sequential reader from Sparse Block Cache
    if (this.cache) {
      this.currentReader = this.cache.createRangeStream(
        desc,
        0,
        desc.contentLength ? desc.contentLength - 1 : undefined
      );

      (async () => {
        try {
          for await (const frame of EbmlOpusExtractor.extractFromStream(this.currentReader)) {
            if (!this.isPlaying) break;
            this.framesQueue.push(frame);
          }
        } catch {}
      })();
    }

    if (!this.timer) {
      this.timer = setInterval(() => this.tick(), 100); // 100 ms cadence
      if (typeof this.timer.unref === 'function') {
        this.timer.unref();
      }
    }

    this.broadcastJson({
      type: 'track_started',
      videoId: desc.videoId,
      title: desc.title || '',
      artist: desc.artist || '',
      playoutHorizonNs: String(PLAYOUT_HORIZON_NS),
    });
  }

  resume() {
    this.isPlaying = true;
    if (!this.timer) {
      this.timer = setInterval(() => this.tick(), 100);
      if (typeof this.timer.unref === 'function') {
        this.timer.unref();
      }
    }
    this.broadcastJson({ type: 'playback_resumed' });
  }

  pause() {
    this.isPlaying = false;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.broadcastJson({ type: 'playback_paused' });
  }

  tick() {
    if (!this.isPlaying || this.members.size === 0) return;

    // Pull 5 Opus frames (100 ms) from the queue
    if (this.framesQueue.length < FRAMES_PER_BATCH) return;

    const batch = this.framesQueue.splice(0, FRAMES_PER_BATCH);
    const playoutAtNs = process.hrtime.bigint() + PLAYOUT_HORIZON_NS;
    this.broadcastFrameBatch(batch, playoutAtNs);
  }

  /**
   * Pre-Framed RFC 6455 Zero-Copy Fan-Out (comm9.md Section 1):
   * 1. Constructs binary payload with 16-byte header + frame lengths + Opus packets
   * 2. Encodes RFC 6455 frame ONCE into an immutable Buffer
   * 3. Writes that exact Buffer reference to all members with per-client lag isolation
   */
  broadcastFrameBatch(frames, playoutAtNs) {
    if (frames.length === 0 || this.members.size === 0) return;

    const seq = this.sequenceId++;
    const frameCount = frames.length;

    // 16-byte fixed header
    const header = Buffer.allocUnsafe(16);
    header.writeBigInt64BE(playoutAtNs, 0); // Bytes 0..7: playoutAtNs
    header.writeUInt32BE(seq, 8);          // Bytes 8..11: sequenceId
    header[12] = 0x01;                     // Byte 12: codecFlags (0x01 = Opus 48kHz stereo)
    header[13] = frameCount;               // Byte 13: frameCount
    header[14] = 0x00;                     // Byte 14: reserved
    header[15] = 0x00;                     // Byte 15: reserved

    // Frame length table (2 bytes UInt16BE per frame)
    const lenTable = Buffer.allocUnsafe(frameCount * 2);
    for (let i = 0; i < frameCount; i++) {
      lenTable.writeUInt16BE(frames[i].frame.length, i * 2);
    }

    const payloadParts = [header, lenTable];
    for (let i = 0; i < frameCount; i++) {
      payloadParts.push(frames[i].frame);
    }
    const binaryPayload = Buffer.concat(payloadParts);

    // Encode RFC 6455 frame ONCE (Zero buffer duplication)
    const wsFrame = WsCodec.encodeFrame(binaryPayload, true);

    // Fan out to all members with per-client lag isolation
    for (const member of this.members) {
      if (member.socket.destroyed) {
        this.members.delete(member);
        continue;
      }

      // Check per-client lag: if socket queue > 64 KiB, drop frame for this client only
      if (member.socket.writableLength > MAX_LAG_BYTES) {
        member.droppedPackets++;
        continue;
      }

      member.socket.write(wsFrame);
      member.sentPackets++;
    }
  }

  broadcastJson(obj) {
    const wsFrame = WsCodec.encodeFrame(JSON.stringify(obj), false);
    for (const member of this.members) {
      if (!member.socket.destroyed) {
        member.socket.write(wsFrame);
      }
    }
  }

  close() {
    this.pause();
    if (this.currentReader) {
      this.currentReader.destroy();
      this.currentReader = null;
    }
    for (const member of this.members) {
      if (typeof member.socket?.end === 'function') {
        member.socket.end();
      }
    }
    this.members.clear();
  }
}

/**
 * RoomManager manages active rooms
 */
export class RoomManager {
  constructor({ cache } = {}) {
    this.cache = cache;
    /** @type {Map<string, RoomBroadcaster>} */
    this.rooms = new Map();
  }

  getOrCreateRoom(roomId) {
    let room = this.rooms.get(roomId);
    if (!room) {
      room = new RoomBroadcaster({ roomId, cache: this.cache });
      this.rooms.set(roomId, room);
    }
    return room;
  }

  close() {
    for (const room of this.rooms.values()) {
      room.close();
    }
    this.rooms.clear();
  }
}
