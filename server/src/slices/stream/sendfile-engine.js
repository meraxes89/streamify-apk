import { symbols } from '../../platform/pal/ffi.js';

/**
 * Android/Linux Zero-Copy Kernel Sendfile Offloader (comm15.md Option 3)
 * 
 * - Leverages sendfile(2) via Koffi FFI to transfer file page cache bytes directly
 *   into the TCP socket without traversing userland memory or V8 buffers.
 * - Suitable for high-throughput LAN transfers and bulk range requests.
 * - Reconciles byte ranges, watchdog return count accounting, and non-blocking backpressure.
 */
export class ZeroCopySendfileEngine {
  constructor() {
    this.sendfileFn = symbols.linux?.sendfile || null;
    this.isSupported = Boolean(this.sendfileFn);
  }

  /**
   * Evaluates if request is eligible for zero-copy sendfile offloading:
   * (Linux/Android) ∧ plaintext HTTP ∧ file-backed ∧ raw socket handle available
   * 
   * @param {Object} req - Fastify request
   * @param {Object} reply - Fastify reply
   * @param {Object} options
   * @param {number} options.fileFd - File descriptor
   * @param {number} options.start - Range start offset
   * @param {number} options.length - Range length
   * @returns {boolean}
   */
  canOffload(req, reply, { fileFd, start, length } = {}) {
    if (!this.isSupported) return false;
    if (typeof fileFd !== 'number' || fileFd < 0) return false;
    if (!length || length < 64 * 1024) return false; // Only offload bulk/large transfers (comm15.md)

    const socket = req.raw?.socket;
    // Verify plaintext TCP (TLS cannot use sendfile without kTLS)
    if (req.raw?.socket?.encrypted) return false;

    const sockFd = socket?._handle?.fd;
    return typeof sockFd === 'number' && sockFd >= 0;
  }

  /**
   * Executes chunked sendfile transfer with pacing and watchdog recording.
   * 
   * @param {Object} req
   * @param {Object} reply
   * @param {Object} options
   * @param {number} options.fileFd
   * @param {number} options.start
   * @param {number} options.length
   * @param {Object} [options.watchdog]
   * @param {string} [options.streamId]
   * @param {number} [options.chunkSize=262144] - 256 KiB chunks
   * @returns {Promise<number>} Total bytes sent
   */
  async streamZeroCopy(req, reply, {
    fileFd,
    start,
    length,
    watchdog = null,
    streamId = null,
    chunkSize = 256 * 1024,
  } = {}) {
    const socket = req.raw.socket;
    const sockFd = socket._handle.fd;

    // Fastify socket hijack: take ownership away from framework
    reply.hijack();

    // Send HTTP 206 response headers through Node first
    reply.raw.writeHead(206, {
      'Content-Type': reply.getHeader('Content-Type') || 'audio/webm; codecs="opus"',
      'Content-Length': length,
      'Content-Range': reply.getHeader('Content-Range') || `bytes ${start}-${start + length - 1}/*`,
      'Accept-Ranges': 'bytes',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges',
    });

    let currentOffset = BigInt(start);
    let remaining = BigInt(length);
    let totalSent = 0;

    try {
      while (remaining > 0n) {
        if (req.raw.destroyed || socket.destroyed) break;

        const count = Number(remaining > BigInt(chunkSize) ? BigInt(chunkSize) : remaining);
        const offsetArr = [currentOffset];

        // Call native sendfile(int out_fd, int in_fd, int64 *offset, size_t count)
        const bytesSent = this.sendfileFn(sockFd, fileFd, offsetArr, count);

        if (bytesSent > 0) {
          currentOffset += BigInt(bytesSent);
          remaining -= BigInt(bytesSent);
          totalSent += bytesSent;

          // Record bytes in watchdog directly from return value (zero user space copying)
          if (watchdog && streamId) {
            watchdog.recordBytes(streamId, bytesSent);
          }
        } else if (bytesSent === 0) {
          // EOF
          break;
        } else {
          // EAGAIN / EWOULDBLOCK: backpressure wait on socket writability
          await new Promise((resolve) => {
            if (socket.destroyed) return resolve();
            socket.once('drain', resolve);
            // Safety timeout
            setTimeout(resolve, 50).unref?.();
          });
        }
      }
    } finally {
      if (!socket.destroyed) {
        reply.raw.end();
      }
    }

    return totalSent;
  }
}
