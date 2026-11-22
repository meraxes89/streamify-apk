import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { FlacIntrospector, FLAC_BLOCK_STREAMINFO, FLAC_BLOCK_PICTURE } from '../src/flac-introspector.mjs';
import { FrameAlignedGateway } from '../src/frame-gateway.mjs';

function buildMockFlac({ includePicture = true, pictureSize = 1024 * 50 } = {}) {
  const parts = [];

  // 1. 'fLaC' marker
  parts.push(Buffer.from('fLaC', 'ascii'));

  // 2. STREAMINFO (34 bytes payload + 4 bytes header)
  const streamInfoData = Buffer.alloc(34);
  streamInfoData.writeUInt16BE(4096, 0); // minBlock
  streamInfoData.writeUInt16BE(4096, 2); // maxBlock
  streamInfoData.writeUIntBE(1000, 4, 3); // minFrame
  streamInfoData.writeUIntBE(2000, 7, 3); // maxFrame
  
  // Sample rate 96000 (0x17700), 2 channels, 24 bits (23 + 1)
  // b0 = (96000 >> 12) = 0x17
  // b1 = (96000 >> 4) & 0xff = 0x70
  // b2 = ((96000 & 0x0f) << 4) | ((2-1) << 1) | ((24-1) >> 4) = 0x00 | 0x02 | 0x01 = 0x03
  // b3 = ((24-1) & 0x0f) << 4 = 0x70
  streamInfoData.writeUInt8(0x17, 10);
  streamInfoData.writeUInt8(0x70, 11);
  streamInfoData.writeUInt8(0x03, 12);
  streamInfoData.writeUInt8(0x70, 13);
  streamInfoData.writeUInt32BE(96000 * 200, 14); // 200 seconds of audio

  const streamInfoHeader = Buffer.alloc(4);
  streamInfoHeader.writeUInt8(includePicture ? FLAC_BLOCK_STREAMINFO : (FLAC_BLOCK_STREAMINFO | 0x80), 0);
  streamInfoHeader.writeUIntBE(34, 1, 3);
  parts.push(streamInfoHeader, streamInfoData);

  // 3. PICTURE block (heavy image metadata)
  if (includePicture) {
    const picData = Buffer.alloc(pictureSize, 0xee);
    const picHeader = Buffer.alloc(4);
    picHeader.writeUInt8(FLAC_BLOCK_PICTURE | 0x80, 0); // last metadata block
    picHeader.writeUIntBE(pictureSize, 1, 3);
    parts.push(picHeader, picData);
  }

  // 4. Simulated FLAC audio frames (with 0xFFF8 sync codes)
  for (let i = 0; i < 5; i++) {
    const frame = Buffer.alloc(512);
    frame.writeUInt8(0xff, 0);
    frame.writeUInt8(0xf8, 1); // FLAC frame sync code
    frame.fill(i + 1, 2);
    parts.push(frame);
  }

  return Buffer.concat(parts);
}

describe('comm21.md Elite Architecture: Lossless Hi-Res & Instant Start', () => {
  it('parses FLAC STREAMINFO with 24-bit / 96kHz parameters', () => {
    const mock = buildMockFlac({ includePicture: false });
    const info = FlacIntrospector.inspect(mock);

    assert.ok(info.streamInfo);
    assert.equal(info.streamInfo.sampleRate, 96000);
    assert.equal(info.streamInfo.channels, 2);
    assert.equal(info.streamInfo.bitsPerSample, 24);
    assert.equal(info.streamInfo.durationSec, 200);
  });

  it('scans FLAC frame sync codes (0xFFF8)', () => {
    const mock = buildMockFlac({ includePicture: false });
    const info = FlacIntrospector.inspect(mock);
    const audioPayload = mock.subarray(info.firstAudioFrameOffset);
    const frames = FlacIntrospector.scanFrameSyncs(audioPayload, info.firstAudioFrameOffset);

    assert.equal(frames.length, 5);
    assert.equal(frames[0].byteOffset, info.firstAudioFrameOffset);
  });

  it('performs Metadata-First Remux: extracts & defers heavy PICTURE block for instant TTFB', () => {
    const picSize = 1024 * 60; // 60 KB cover art
    const mockWithPic = buildMockFlac({ includePicture: true, pictureSize: picSize });
    
    const remuxed = FlacIntrospector.createMetadataFirstStream(mockWithPic);

    // Assert PICTURE was deferred
    assert.ok(remuxed.pictureExtracted);
    assert.equal(remuxed.pictureExtracted.length, picSize);
    assert.equal(remuxed.bytesSavedFromFront, picSize + 4);

    // Verify remuxed stream has audio frames starting immediately after STREAMINFO
    const remuxedInfo = FlacIntrospector.inspect(remuxed.remuxedBuffer);
    assert.equal(remuxedInfo.pictureBlock, null);
    assert.equal(remuxedInfo.firstAudioFrameOffset, 42); // 4 magic + 4 header + 34 streaminfo
  });

  it('FrameAlignedGateway resolves seek target to exact frame sync boundary', () => {
    const frameIndex = [
      { sampleNumber: 0, byteOffset: 42 },
      { sampleNumber: 96000 * 10, byteOffset: 500000 },
      { sampleNumber: 96000 * 30, byteOffset: 1500000 },
      { sampleNumber: 96000 * 60, byteOffset: 3000000 }
    ];

    const gateway = new FrameAlignedGateway({
      frameIndex,
      sampleRate: 96000,
      totalBytes: 10000000,
      bitrate: 4608000
    });

    // Seek to 32 seconds -> must align to frame at 30 seconds (byte 1500000)
    const seekRes = gateway.resolveSeekRange(32);
    assert.equal(seekRes.startByte, 1500000);
    assert.equal(seekRes.isFrameAligned, true);
    assert.equal(seekRes.resolvedSample, 96000 * 30);
  });

  it('FrameAlignedGateway computes time-based burst and pacing without lossy downgrade', () => {
    const gateway = new FrameAlignedGateway({
      sampleRate: 96000,
      totalBytes: 100000000,
      metadataBytes: 1024,
      bitrate: 4608000 // 24/96 FLAC = 576,000 bytes/sec
    });

    const config = gateway.computePacingConfig();
    assert.equal(config.bytesPerSecond, 576000);
    // Burst = 1024 metadata + (576000 * 1.5s) = 865,024 bytes
    assert.equal(config.burstBytes, 1024 + (576000 * 1.5));
    // Steady pacing = 1.2x of true bitrate = 691,200 bytes/sec
    assert.equal(config.steadyBytesPerSec, Math.ceil(576000 * 1.2));
  });
});
