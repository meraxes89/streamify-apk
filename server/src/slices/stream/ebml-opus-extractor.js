/**
 * Zero-Allocation EBML / WebM Opus Frame Extractor (comm9.md Specification)
 *
 * Scans Matroska/EBML containers directly from the 256 KiB Sparse Block Cache.
 * Extracts raw 20 ms Opus packets without transcoding or memory copies.
 */

export class EbmlOpusExtractor {
  /**
   * Reads an EBML variable-length integer (VINT)
   * @param {Buffer|Uint8Array} buf
   * @param {number} offset
   * @returns {{ value: number, length: number } | null}
   */
  static readVint(buf, offset) {
    if (offset >= buf.length) return null;
    const firstByte = buf[offset];
    if (firstByte === 0) return null;

    let mask = 0x80;
    let len = 1;
    while ((firstByte & mask) === 0) {
      mask >>= 1;
      len++;
    }

    if (offset + len > buf.length) return null;

    let val = firstByte & ~mask;
    for (let i = 1; i < len; i++) {
      val = val * 256 + buf[offset + i];
    }

    return { value: val, length: len };
  }

  /**
   * Reads an EBML Element ID (keeps the marker bit intact)
   * @param {Buffer|Uint8Array} buf
   * @param {number} offset
   * @returns {{ id: number, length: number } | null}
   */
  static readElementId(buf, offset) {
    if (offset >= buf.length) return null;
    const firstByte = buf[offset];
    if (firstByte === 0) return null;

    let mask = 0x80;
    let len = 1;
    while ((firstByte & mask) === 0) {
      mask >>= 1;
      len++;
    }

    if (offset + len > buf.length) return null;

    let id = 0;
    for (let i = 0; i < len; i++) {
      id = id * 256 + buf[offset + i];
    }

    return { id, length: len };
  }

  /**
   * Reads an unsigned integer of given byte size
   */
  static readUint(buf, offset, size) {
    let val = 0;
    for (let i = 0; i < size; i++) {
      val = val * 256 + buf[offset + i];
    }
    return val;
  }

  /**
   * Extracts all Opus frames from a complete or partial WebM Buffer.
   * @param {Buffer} buffer
   * @returns {{ opusHead: Buffer|null, frames: Array<{ timestampMs: number, frame: Buffer, durationMs: number }> }}
   */
  static extractFromBuffer(buffer) {
    const frames = [];
    let opusHead = null;
    let clusterTimestamp = 0;
    let cursor = 0;
    const len = buffer.length;

    // Master container elements that should be entered
    const masterElements = new Set([
      0x1a45dfa3, // EBML
      0x18538067, // Segment
      0x1654ae6b, // Tracks
      0xae,       // TrackEntry
      0x1f43b675, // Cluster
    ]);

    while (cursor < len) {
      const idInfo = this.readElementId(buffer, cursor);
      if (!idInfo) break;

      const sizeInfo = this.readVint(buffer, cursor + idInfo.length);
      if (!sizeInfo) break;

      const headerLen = idInfo.length + sizeInfo.length;
      const dataOffset = cursor + headerLen;
      const dataSize = sizeInfo.value;

      if (masterElements.has(idInfo.id)) {
        // Descend into container
        cursor += headerLen;
        continue;
      }

      // Check for elements we care about
      if (dataOffset + dataSize > len) {
        // Incomplete element at buffer boundary
        break;
      }

      switch (idInfo.id) {
        case 0x63a2: {
          // CodecPrivate: contains 19-byte OpusHead for Opus audio
          if (dataSize >= 19 && buffer.subarray(dataOffset, dataOffset + 8).toString('ascii') === 'OpusHead') {
            opusHead = buffer.subarray(dataOffset, dataOffset + dataSize);
          }
          break;
        }

        case 0xe7: {
          // Cluster Timecode (relative timestamp in ms)
          clusterTimestamp = this.readUint(buffer, dataOffset, dataSize);
          break;
        }

        case 0xa3: {
          // SimpleBlock: contains track number, time offset, flags, and raw Opus packet
          if (dataSize > 4) {
            // 1. Track number VINT
            const trackVint = this.readVint(buffer, dataOffset);
            if (trackVint) {
              const headerAfterTrack = dataOffset + trackVint.length;
              if (headerAfterTrack + 3 <= dataOffset + dataSize) {
                // 2. Relative timecode (signed 16-bit int)
                const relTime = buffer.readInt16BE(headerAfterTrack);
                // 3. Flags (1 byte)
                const flags = buffer[headerAfterTrack + 2];
                const lacing = (flags & 0x06) >> 1;

                const payloadStart = headerAfterTrack + 3;
                const payloadEnd = dataOffset + dataSize;

                if (lacing === 0 && payloadEnd > payloadStart) {
                  // No lacing: all remaining bytes are the single Opus frame
                  const opusFrame = buffer.subarray(payloadStart, payloadEnd);
                  frames.push({
                    timestampMs: Math.max(0, clusterTimestamp + relTime),
                    frame: opusFrame,
                    durationMs: 20, // Standard WebM Opus frame duration
                  });
                }
              }
            }
          }
          break;
        }

        default:
          // Skip other elements (SeekHead, Info, Cues, Tags, etc.)
          break;
      }

      cursor = dataOffset + dataSize;
    }

    return { opusHead, frames };
  }

  /**
   * Async generator that extracts raw Opus frames continuously from a Readable stream
   * @param {import('node:stream').Readable} readable
   * @yields {{ timestampMs: number, frame: Buffer, durationMs: number }}
   */
  static async *extractFromStream(readable) {
    let unparsed = Buffer.alloc(0);

    for await (const chunk of readable) {
      unparsed = Buffer.concat([unparsed, chunk]);
      const { frames } = this.extractFromBuffer(unparsed);

      for (const f of frames) {
        yield f;
      }

      // Keep only unparsed trailing bytes (last 64 KiB to avoid infinite accumulation)
      if (unparsed.length > 256 * 1024) {
        unparsed = unparsed.subarray(unparsed.length - 64 * 1024);
      }
    }
  }
}
