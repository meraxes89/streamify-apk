import fs from 'node:fs';
import path from 'node:path';
import { inflateRawSync } from 'node:zlib';

const MAX_ENTRY_SIZE = 100 * 1024 * 1024; // 100 MiB safety cap per file

/**
 * Parses a ZIP archive buffer and extracts files safely to destDir.
 * - Enforces zip-slip prevention: guarantees no entry escapes destDir.
 * - Enforces max uncompressed entry size to mitigate zip-bombs.
 * - Pure Node: uses node:zlib inflateRawSync for Deflate entries and raw copy for Stored entries.
 *
 * @param {Buffer} zipBuffer - Full zip buffer in memory
 * @param {string} destDir - Target directory to extract into
 */
export function extractZipSafe(zipBuffer, destDir) {
  const resolvedDest = path.resolve(destDir);
  if (!fs.existsSync(resolvedDest)) {
    fs.mkdirSync(resolvedDest, { recursive: true });
  }

  // Find End of Central Directory (EOCD) record (signature: 0x06054b50)
  let eocdOffset = -1;
  for (let i = zipBuffer.length - 22; i >= Math.max(0, zipBuffer.length - 65558); i--) {
    if (zipBuffer.readUInt32LE(i) === 0x06054b50) {
      eocdOffset = i;
      break;
    }
  }

  if (eocdOffset === -1) {
    throw new Error('[extractZipSafe] Invalid ZIP archive: EOCD not found');
  }

  const cdTotalEntries = zipBuffer.readUInt16LE(eocdOffset + 10);
  const cdOffset = zipBuffer.readUInt32LE(eocdOffset + 16);

  let currentCdOffset = cdOffset;
  for (let i = 0; i < cdTotalEntries; i++) {
    if (zipBuffer.readUInt32LE(currentCdOffset) !== 0x02014b50) {
      throw new Error(`[extractZipSafe] Corrupt Central Directory entry at offset ${currentCdOffset}`);
    }

    const compressionMethod = zipBuffer.readUInt16LE(currentCdOffset + 10);
    const compressedSize = zipBuffer.readUInt32LE(currentCdOffset + 20);
    const uncompressedSize = zipBuffer.readUInt32LE(currentCdOffset + 24);
    const fileNameLength = zipBuffer.readUInt16LE(currentCdOffset + 28);
    const extraFieldLength = zipBuffer.readUInt16LE(currentCdOffset + 30);
    const fileCommentLength = zipBuffer.readUInt16LE(currentCdOffset + 32);
    const localHeaderOffset = zipBuffer.readUInt32LE(currentCdOffset + 42);

    const fileName = zipBuffer.toString('utf8', currentCdOffset + 46, currentCdOffset + 46 + fileNameLength);

    // ZIP-SLIP GUARD
    const targetPath = path.resolve(resolvedDest, fileName);
    if (!targetPath.startsWith(resolvedDest + path.sep) && targetPath !== resolvedDest) {
      throw new Error(`[extractZipSafe] Zip-slip attempt detected and blocked: ${fileName}`);
    }

    if (uncompressedSize > MAX_ENTRY_SIZE) {
      throw new Error(`[extractZipSafe] Oversized ZIP entry blocked: ${fileName} (${uncompressedSize} bytes)`);
    }

    // Advance to next Central Directory entry
    currentCdOffset += 46 + fileNameLength + extraFieldLength + fileCommentLength;

    // Check if directory
    const isDir = fileName.endsWith('/') || fileName.endsWith('\\');
    if (isDir) {
      fs.mkdirSync(targetPath, { recursive: true });
      continue;
    }

    // Read local file header to find start of data
    if (zipBuffer.readUInt32LE(localHeaderOffset) !== 0x04034b50) {
      throw new Error(`[extractZipSafe] Corrupt local file header for ${fileName}`);
    }
    const localFileNameLength = zipBuffer.readUInt16LE(localHeaderOffset + 26);
    const localExtraLength = zipBuffer.readUInt16LE(localHeaderOffset + 28);
    const dataStart = localHeaderOffset + 30 + localFileNameLength + localExtraLength;
    const rawData = zipBuffer.subarray(dataStart, dataStart + compressedSize);

    let fileData;
    if (compressionMethod === 0) {
      // Stored (no compression)
      fileData = rawData;
    } else if (compressionMethod === 8) {
      // Deflated
      fileData = inflateRawSync(rawData);
    } else {
      throw new Error(`[extractZipSafe] Unsupported compression method ${compressionMethod} for ${fileName}`);
    }

    fs.mkdirSync(path.dirname(targetPath), { recursive: true });
    fs.writeFileSync(targetPath, fileData);
  }
}
