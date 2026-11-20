import test from 'node:test';
import assert from 'node:assert/strict';
import { ClockSync } from '../../src/slices/room/clock-sync.js';
import {
  WsCodec,
  RoomBroadcaster,
  RoomManager,
  PLAYOUT_HORIZON_NS,
  FRAMES_PER_BATCH,
  MAX_LAG_BYTES,
} from '../../src/slices/room/broadcaster.js';
import { EbmlOpusExtractor } from '../../src/slices/stream/ebml-opus-extractor.js';

test('ClockSync: 4-timestamp monotonic ping-pong and minimum-RTT filtering', () => {
  // 1. Server ping response
  const clientT1 = 1000.5;
  const pong = ClockSync.handlePing({ type: 'sync_ping', clientTimeMs: clientT1 });
  assert.equal(pong.type, 'sync_pong');
  assert.equal(pong.clientTimeMs, clientT1);
  assert.ok(pong.serverRecvMs > 0);
  assert.ok(pong.serverSendMs >= pong.serverRecvMs);

  // 2. Client offset calculation
  const t1 = 1000.0;
  const t2 = 1010.0;
  const t3 = 1010.5;
  const t4 = 1021.0;
  const { offsetMs, rttMs } = ClockSync.calculateOffset(t1, t2, t3, t4);
  assert.equal(offsetMs, -0.25);
  assert.equal(+(rttMs).toFixed(1), 20.5);

  // 3. Minimum-RTT sliding filter
  const samples = [
    { offsetMs: 10.0, rttMs: 15.0 }, // good sample
    { offsetMs: 10.1, rttMs: 14.0 }, // best sample
    { offsetMs: 15.0, rttMs: 80.0 }, // Wi-Fi queueing spike
    { offsetMs: 25.0, rttMs: 120.0 }, // Wi-Fi queueing spike
    { offsetMs: 10.2, rttMs: 16.0 }, // good sample
  ];

  const filtered = ClockSync.filterSamples(samples, 30, 0.4);
  assert.ok(filtered.minRttMs <= 15.0, 'Filtered min RTT must pick lowest RTT');
  assert.ok(filtered.filteredOffsetMs < 11.0, 'Offset must be close to true clock and ignore spikes');
});

test('WsCodec: RFC 6455 Frame encoding and masked client decoding', () => {
  // 1. Text frame encode
  const textPayload = JSON.stringify({ hello: 'world' });
  const textFrame = WsCodec.encodeFrame(textPayload, false);
  assert.equal(textFrame[0], 0x81); // FIN=1, Opcode=1 (Text)
  assert.ok(textFrame.length > textPayload.length);

  // 2. Binary frame encode (< 126 bytes)
  const binPayload = Buffer.from([1, 2, 3, 4, 5]);
  const binFrame = WsCodec.encodeFrame(binPayload, true);
  assert.equal(binFrame[0], 0x82); // FIN=1, Opcode=2 (Binary)
  assert.equal(binFrame[1], 5);
  assert.deepEqual(binFrame.subarray(2), binPayload);

  // 3. Masked client frame encode & decode
  const mask = Buffer.from([0x12, 0x34, 0x56, 0x78]);
  const clientPayload = Buffer.from('client message');
  const maskedPayload = Buffer.allocUnsafe(clientPayload.length);
  for (let i = 0; i < clientPayload.length; i++) {
    maskedPayload[i] = clientPayload[i] ^ mask[i % 4];
  }

  const clientFrame = Buffer.concat([
    Buffer.from([0x81, 0x80 | clientPayload.length]), // FIN, text, masked
    mask,
    maskedPayload,
  ]);

  const decoded = WsCodec.decodeClientFrame(clientFrame);
  assert.ok(decoded);
  assert.equal(decoded.opcode, 1);
  assert.equal(decoded.payload.toString('utf8'), 'client message');
});

test('EbmlOpusExtractor: Synthesizes and extracts WebM Opus SimpleBlocks', () => {
  // Synthesize a minimal valid WebM Cluster with 2 SimpleBlocks
  // Cluster ID: 0x1F43B675
  // Timecode ID: 0xE7, size: 2, value: 1000 ms
  // SimpleBlock ID: 0xA3, size: 8
  //   Track VINT: 0x81 (Track 1)
  //   Rel Timecode: 0x0000 (0 ms)
  //   Flags: 0x80 (Keyframe, No lacing)
  //   Opus payload: 4 bytes [0xFC, 0x01, 0x02, 0x03]

  const timecodeElement = Buffer.from([0xe7, 0x82, 0x03, 0xe8]); // Timecode = 1000
  const opusData1 = Buffer.from([0xfc, 0x01, 0x02, 0x03]);
  const simpleBlock1 = Buffer.concat([
    Buffer.from([0xa3, 0x88]), // SimpleBlock ID + length (8 bytes)
    Buffer.from([0x81]), // Track 1
    Buffer.from([0x00, 0x00]), // Rel time = 0
    Buffer.from([0x80]), // Flags = Keyframe
    opusData1,
  ]);

  const opusData2 = Buffer.from([0xfc, 0x04, 0x05, 0x06]);
  const simpleBlock2 = Buffer.concat([
    Buffer.from([0xa3, 0x88]), // SimpleBlock ID + length (8 bytes)
    Buffer.from([0x81]), // Track 1
    Buffer.from([0x00, 0x14]), // Rel time = +20 ms
    Buffer.from([0x80]), // Flags
    opusData2,
  ]);

  const clusterPayload = Buffer.concat([timecodeElement, simpleBlock1, simpleBlock2]);
  const clusterHeader = Buffer.from([0x1f, 0x43, 0xb6, 0x75, 0x80 | clusterPayload.length]);
  const webmChunk = Buffer.concat([clusterHeader, clusterPayload]);

  const { frames } = EbmlOpusExtractor.extractFromBuffer(webmChunk);
  assert.equal(frames.length, 2);
  assert.equal(frames[0].timestampMs, 1000);
  assert.deepEqual(frames[0].frame, opusData1);
  assert.equal(frames[1].timestampMs, 1020);
  assert.deepEqual(frames[1].frame, opusData2);
});

test('RoomBroadcaster: Pre-framed zero-copy fan-out and per-client lag isolation', () => {
  const broadcaster = new RoomBroadcaster({ roomId: 'test-room' });

  // Mock socket 1: Fast client (writableLength = 0)
  const writes1 = [];
  const mockSocket1 = {
    writableLength: 0,
    destroyed: false,
    write(buf) {
      writes1.push(buf);
      return true;
    },
    setNoDelay() {},
    on() {},
    end() {},
  };

  // Mock socket 2: Weak-Wi-Fi lagging client (writableLength > 64 KiB)
  const writes2 = [];
  const mockSocket2 = {
    writableLength: MAX_LAG_BYTES + 1024, // 65 KiB buffer bloat
    destroyed: false,
    write(buf) {
      writes2.push(buf);
      return true;
    },
    setNoDelay() {},
    on() {},
    end() {},
  };

  const m1 = broadcaster.addMember(mockSocket1, '192.168.1.50');
  const m2 = broadcaster.addMember(mockSocket2, '192.168.1.51');

  // Broadcast 1 batch of 5 fake Opus frames
  const fakeFrames = Array.from({ length: 5 }, (_, i) => ({
    timestampMs: i * 20,
    frame: Buffer.alloc(100, 0xaa),
    durationMs: 20,
  }));

  const playoutAtNs = process.hrtime.bigint() + PLAYOUT_HORIZON_NS;
  broadcaster.broadcastFrameBatch(fakeFrames, playoutAtNs);

  // Verification 1: Fast client received welcome JSON + 1 binary audio frame
  assert.equal(writes1.length, 2);
  assert.equal(m1.sentPackets, 1);
  assert.equal(m1.droppedPackets, 0);

  // Verification 2: Lagging client received welcome JSON but dropped audio frame
  assert.equal(writes2.length, 1);
  assert.equal(m2.sentPackets, 0);
  assert.equal(m2.droppedPackets, 1, 'Lagging client must drop frame without stalling fast clients');

  broadcaster.close();
});
