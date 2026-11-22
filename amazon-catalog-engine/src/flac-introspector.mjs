/**
 * FLAC Introspection & Metadata-First Remuxer
 * 
 * Complies with docs/nano/comm21.md architectural requirements:
 * 1. Parses FLAC header ('fLaC' magic).
 * 2. Extracts STREAMINFO: sample rate, channels, bit depth, total samples, MD5.
 * 3. Extracts or indexes SEEKTABLE and scans audio frame sync markers (0xFFF8).
 * 4. Extracts and defers heavy PICTURE blocks (metadata-first remuxing).
 *    Ensures audio-critical blocks arrive first so decoder plays in < 250ms cold.
 * 5. Guarantees 100% bit-perfect audio frame integrity (zero transcoding).
 */

export const FLAC_BLOCK_STREAMINFO = 0;
export const FLAC_BLOCK_PADDING = 1;
export const FLAC_BLOCK_APPLICATION = 2;
export const FLAC_BLOCK_SEEKTABLE = 3;
export const FLAC_BLOCK_VORBIS_COMMENT = 4;
export const FLAC_BLOCK_CUESHEET = 5;
export const FLAC_BLOCK_PICTURE = 6;

export class FlacIntrospector {
  /**
   * Parses metadata blocks of a FLAC buffer.
   * 
   * @param {Buffer} buffer - Buffer containing at least the FLAC header and metadata
   * @returns {Object} Introspection descriptor
   */
  static inspect(buffer) {
    if (!buffer || buffer.length < 42) {
      throw new Error('Buffer too small to contain valid FLAC metadata');
    }

    // Verify 'fLaC' marker
    if (buffer.toString('ascii', 0, 4) !== 'fLaC') {
      throw new Error('Invalid FLAC: Missing fLaC stream marker');
    }

    let offset = 4;
    let isLast = false;
    const blocks = [];
    let streamInfo = null;
    let pictureBlock = null;
    const seekPoints = [];

    while (!isLast && offset < buffer.length) {
      const headerByte = buffer.readUInt8(offset);
      isLast = (headerByte & 0x80) !== 0;
      const blockType = headerByte & 0x7f;
      const blockLength = buffer.readUIntBE(offset + 1, 3);

      const blockStart = offset;
      const dataStart = offset + 4;
      const blockEnd = dataStart + blockLength;

      if (blockEnd > buffer.length) {
        // Truncated buffer provided
        break;
      }

      const blockDesc = {
        type: blockType,
        isLast,
        length: blockLength,
        headerOffset: blockStart,
        dataOffset: dataStart,
        endOffset: blockEnd
      };

      if (blockType === FLAC_BLOCK_STREAMINFO) {
        streamInfo = this._parseStreamInfo(buffer, dataStart);
        blockDesc.streamInfo = streamInfo;
      } else if (blockType === FLAC_BLOCK_SEEKTABLE) {
        const points = this._parseSeekTable(buffer, dataStart, blockLength);
        seekPoints.push(...points);
        blockDesc.seekPointsCount = points.length;
      } else if (blockType === FLAC_BLOCK_PICTURE) {
        pictureBlock = {
          headerOffset: blockStart,
          dataOffset: dataStart,
          length: blockLength,
          totalBytes: blockLength + 4
        };
        blockDesc.isPicture = true;
      }

      blocks.push(blockDesc);
      offset = blockEnd;
    }

    const firstAudioFrameOffset = offset;

    return {
      streamInfo,
      blocks,
      pictureBlock,
      seekPoints,
      firstAudioFrameOffset,
      hasSeekTable: seekPoints.length > 0
    };
  }

  static _parseStreamInfo(buffer, offset) {
    const minBlockSize = buffer.readUInt16BE(offset);
    const maxBlockSize = buffer.readUInt16BE(offset + 2);
    const minFrameSize = buffer.readUIntBE(offset + 4, 3);
    const maxFrameSize = buffer.readUIntBE(offset + 7, 3);

    // 20 bits sample rate, 3 bits channels, 5 bits bitsPerSample, 36 bits totalSamples
    const b0 = buffer.readUInt8(offset + 10);
    const b1 = buffer.readUInt8(offset + 11);
    const b2 = buffer.readUInt8(offset + 12);
    const b3 = buffer.readUInt8(offset + 13);

    const sampleRate = (b0 << 12) | (b1 << 4) | (b2 >> 4);
    const channels = ((b2 >> 1) & 0x07) + 1;
    const bitsPerSample = (((b2 & 0x01) << 4) | (b3 >> 4)) + 1;

    const totalSamplesHigh = b3 & 0x0f;
    const totalSamplesLow = buffer.readUInt32BE(offset + 14);
    const totalSamples = (totalSamplesHigh * 0x100000000) + totalSamplesLow;

    const md5 = buffer.subarray(offset + 18, offset + 34).toString('hex');
    const durationSec = sampleRate > 0 ? (totalSamples / sampleRate) : 0;

    return {
      minBlockSize,
      maxBlockSize,
      minFrameSize,
      maxFrameSize,
      sampleRate,
      channels,
      bitsPerSample,
      totalSamples,
      durationSec,
      md5
    };
  }

  static _parseSeekTable(buffer, offset, length) {
    const points = [];
    const count = Math.floor(length / 18);
    for (let i = 0; i < count; i++) {
      const pOffset = offset + (i * 18);
      const sampleNumHigh = buffer.readUInt32BE(pOffset);
      const sampleNumLow = buffer.readUInt32BE(pOffset + 4);
      const sampleNumber = (sampleNumHigh * 0x100000000) + sampleNumLow;
      
      // 0xFFFFFFFFFFFFFFFF is placeholder
      if (sampleNumHigh === 0xffffffff && sampleNumLow === 0xffffffff) continue;

      const byteOffsetHigh = buffer.readUInt32BE(pOffset + 8);
      const byteOffsetLow = buffer.readUInt32BE(pOffset + 12);
      const streamOffset = (byteOffsetHigh * 0x100000000) + byteOffsetLow;
      const frameSamples = buffer.readUInt16BE(pOffset + 16);

      points.push({ sampleNumber, streamOffset, frameSamples });
    }
    return points;
  }

  /**
   * Scans audio payload for FLAC frame sync codes (0xFFF8 or 0xFFF9).
   */
  static scanFrameSyncs(audioBuffer, baseOffset = 0, maxFrames = 1000) {
    const frames = [];
    for (let i = 0; i < audioBuffer.length - 2; i++) {
      if (audioBuffer[i] === 0xff && (audioBuffer[i + 1] & 0xfe) === 0xf8) {
        frames.push({
          frameIndex: frames.length,
          byteOffset: baseOffset + i
        });
        if (frames.length >= maxFrames) break;
      }
    }
    return frames;
  }

  /**
   * Metadata-First Remux:
   * Strips or relocates the heavy PICTURE block from the front of the FLAC stream,
   * keeping audio-critical headers (STREAMINFO, SEEKTABLE, VORBIS_COMMENT).
   * 
   * This yields 100% bit-perfect audio delivery while dropping TTFB by seconds.
   */
  static createMetadataFirstStream(fullBuffer) {
    const info = this.inspect(fullBuffer);
    if (!info.pictureBlock) {
      return {
        remuxedBuffer: fullBuffer,
        pictureExtracted: null,
        bytesSavedFromFront: 0
      };
    }

    const { pictureBlock, blocks, firstAudioFrameOffset } = info;
    
    // Extract picture bytes for external /art endpoint
    const pictureExtracted = fullBuffer.subarray(
      pictureBlock.dataOffset,
      pictureBlock.dataOffset + pictureBlock.length
    );

    // Reconstruct metadata header without PICTURE block
    const headerParts = [Buffer.from('fLaC', 'ascii')];

    // Filter out picture block
    const retainedBlocks = blocks.filter(b => b.type !== FLAC_BLOCK_PICTURE);
    for (let i = 0; i < retainedBlocks.length; i++) {
      const b = retainedBlocks[i];
      const isLastBlock = (i === retainedBlocks.length - 1);
      
      const blockHeader = Buffer.alloc(4);
      let headerByte = b.type & 0x7f;
      if (isLastBlock) {
        headerByte |= 0x80; // set LAST_METADATA_BLOCK flag
      }
      blockHeader.writeUInt8(headerByte, 0);
      blockHeader.writeUIntBE(b.length, 1, 3);

      headerParts.push(blockHeader);
      headerParts.push(fullBuffer.subarray(b.dataOffset, b.endOffset));
    }

    // Append audio frames intact
    headerParts.push(fullBuffer.subarray(firstAudioFrameOffset));

    const remuxedBuffer = Buffer.concat(headerParts);

    return {
      remuxedBuffer,
      pictureExtracted,
      bytesSavedFromFront: pictureBlock.totalBytes,
      streamInfo: info.streamInfo
    };
  }
}
