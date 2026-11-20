import fs from 'node:fs';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Readable } from 'node:stream';

export class WriteThroughCache {
  /**
   * @param {Object} options
   * @param {string} options.streamsDir - Directory to store cached streams
   * @param {number} [options.maxCacheMb=512] - Max disk cache in MB
   */
  constructor({ streamsDir, maxCacheMb = 512 } = {}) {
    this.streamsDir = streamsDir;
    this.maxCacheBytes = maxCacheMb * 1024 * 1024;
    /** @type {Map<string, { bytesAvailable: number, isComplete: boolean, partPath: string, finalPath: string, abort: () => void }>} */
    this.activeWriters = new Map();
  }

  getFinalPath(videoId, itag) {
    return path.join(this.streamsDir, `${videoId}_${itag}.bin`);
  }

  getPartPath(videoId, itag) {
    return path.join(this.streamsDir, `${videoId}_${itag}.bin.part`);
  }

  hasComplete(videoId, itag) {
    const filePath = this.getFinalPath(videoId, itag);
    try {
      const stat = fs.statSync(filePath);
      return stat.size > 0 ? filePath : null;
    } catch {
      return null;
    }
  }

  getActiveWriter(videoId, itag) {
    return this.activeWriters.get(`${videoId}_${itag}`);
  }

  async startWriter(descriptor) {
    const key = `${descriptor.videoId}_${descriptor.itag}`;
    if (this.activeWriters.has(key)) {
      return this.activeWriters.get(key);
    }

    const finalPath = this.getFinalPath(descriptor.videoId, descriptor.itag);
    const partPath = this.getPartPath(descriptor.videoId, descriptor.itag);

    const controller = new AbortController();
    const writerState = {
      bytesAvailable: 0,
      isComplete: false,
      partPath,
      finalPath,
      abort: () => controller.abort(),
    };

    this.activeWriters.set(key, writerState);

    (async () => {
      try {
        const upstream = await fetch(descriptor.targetUrl, {
          signal: controller.signal,
          headers: {
            'User-Agent':
              'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
          },
        });

        if (!upstream.ok || !upstream.body) {
          throw new Error(`Upstream stream returned ${upstream.status}`);
        }

        // P1: Bounded 256 KiB write buffer for disk pipeline
        const out = fs.createWriteStream(partPath, { highWaterMark: 256 * 1024 });
        const nodeReadable = Readable.fromWeb(upstream.body);

        nodeReadable.on('data', (chunk) => {
          writerState.bytesAvailable += chunk.length;
        });

        await pipeline(nodeReadable, out);

        // Rename .part to .bin on complete
        if (fs.existsSync(partPath)) {
          fs.renameSync(partPath, finalPath);
          this.evictOverBudget();
        }
        writerState.isComplete = true;
      } catch (err) {
        // Clean up partial on error
        try {
          if (fs.existsSync(partPath)) fs.unlinkSync(partPath);
        } catch {}
      } finally {
        this.activeWriters.delete(key);
      }
    })();

    return writerState;
  }

  evictOverBudget() {
    try {
      const files = fs.readdirSync(this.streamsDir)
        .filter((f) => f.endsWith('.bin'))
        .map((f) => {
          const fullPath = path.join(this.streamsDir, f);
          try {
            const stat = fs.statSync(fullPath);
            return { fullPath, size: stat.size, mtime: stat.mtimeMs };
          } catch {
            return null;
          }
        })
        .filter(Boolean);

      let totalBytes = files.reduce((acc, f) => acc + f.size, 0);
      if (totalBytes <= this.maxCacheBytes) return;

      // Sort oldest mtime first
      files.sort((a, b) => a.mtime - b.mtime);

      const targetBytes = this.maxCacheBytes * 0.8;
      for (const file of files) {
        if (totalBytes <= targetBytes) break;
        try {
          fs.unlinkSync(file.fullPath);
          totalBytes -= file.size;
        } catch {}
      }
    } catch {}
  }
}
