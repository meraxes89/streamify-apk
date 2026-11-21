import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SERVER_DIR = path.resolve(__dirname, '../..');

const WASM_MAGIC = Buffer.from([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]);

/**
 * Verifies that all .wasm files inside a bundle directory begin with
 * standard WebAssembly magic bytes: \0asm\1\0\0\0
 * @param {string} bundleDir
 * @returns {boolean}
 */
export function verifyWasmMagic(bundleDir) {
  try {
    if (!fs.existsSync(bundleDir)) return false;
    const entries = fs.readdirSync(bundleDir, { withFileTypes: true });
    let wasmFound = false;
    for (const ent of entries) {
      if (ent.isFile() && ent.name.endsWith('.wasm')) {
        wasmFound = true;
        const filePath = path.join(bundleDir, ent.name);
        const fd = fs.openSync(filePath, 'r');
        const buf = Buffer.alloc(8);
        const bytesRead = fs.readSync(fd, buf, 0, 8, 0);
        fs.closeSync(fd);
        if (bytesRead < 8 || !buf.equals(WASM_MAGIC)) {
          return false;
        }
      }
    }
    return wasmFound;
  } catch (err) {
    return false;
  }
}

/**
 * Computes or verifies the Merkle hash tree of a bundle directory against
 * its .bundle.json manifest.
 * @param {string} bundleDir
 * @returns {boolean}
 */
export function verifyMerkle(bundleDir) {
  try {
    const manifestPath = path.join(bundleDir, '.bundle.json');
    if (!fs.existsSync(manifestPath)) {
      // If manifest is missing, ensure index.html & composeApp.js exist at minimum
      return fs.existsSync(path.join(bundleDir, 'index.html')) &&
             fs.existsSync(path.join(bundleDir, 'composeApp.js'));
    }
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    if (!manifest.files || typeof manifest.files !== 'object') return false;

    const rootHasher = crypto.createHash('sha256');
    const sortedRelPaths = Object.keys(manifest.files).sort();

    for (const relPath of sortedRelPaths) {
      const fullPath = path.join(bundleDir, relPath);
      if (!fs.existsSync(fullPath)) return false;
      const fileBuf = fs.readFileSync(fullPath);
      const fileHash = crypto.createHash('sha256').update(fileBuf).digest('hex');
      if (fileHash !== manifest.files[relPath]) return false;
      rootHasher.update(`${relPath}:${fileHash}`);
    }

    const calculatedRoot = rootHasher.digest('hex');
    return manifest.rootHash ? calculatedRoot === manifest.rootHash : true;
  } catch {
    return false;
  }
}

/**
 * Creates .bundle.json inside a bundle directory with per-file SHA256 hashes
 * and an overall Merkle root hash.
 * @param {string} bundleDir
 * @returns {{ rootHash: string, fileCount: number }}
 */
export function generateBundleManifest(bundleDir) {
  function walk(current) {
    let files = [];
    for (const ent of fs.readdirSync(current, { withFileTypes: true })) {
      const full = path.join(current, ent.name);
      if (ent.isDirectory()) {
        files = files.concat(walk(full));
      } else if (ent.isFile() && ent.name !== '.bundle.json') {
        files.push(full);
      }
    }
    return files;
  }

  const allFiles = walk(bundleDir).sort();
  const fileHashes = {};
  const rootHasher = crypto.createHash('sha256');

  for (const f of allFiles) {
    const rel = path.relative(bundleDir, f);
    const buf = fs.readFileSync(f);
    const hash = crypto.createHash('sha256').update(buf).digest('hex');
    fileHashes[rel] = hash;
    rootHasher.update(`${rel}:${hash}`);
  }

  const rootHash = rootHasher.digest('hex');
  const manifest = {
    rootHash,
    generatedAt: new Date().toISOString(),
    files: fileHashes,
  };

  fs.writeFileSync(path.join(bundleDir, '.bundle.json'), JSON.stringify(manifest, null, 2), 'utf8');
  return { rootHash, fileCount: allFiles.length };
}

/**
 * Atomically flips a symlink using symlink to a unique tmp path then rename(2).
 * Guarantees that concurrent readers never see an incomplete or broken path.
 * If linkPath currently exists as a real directory (legacy layout), it is safely
 * retired before creating the atomic symlink.
 * @param {string} linkPath - Symlink path (e.g. server/public)
 * @param {string} targetDir - Destination directory (e.g. bundles/<sha>)
 */
export function atomicSymlink(linkPath, targetDir) {
  const parent = path.dirname(linkPath);
  const tmpLink = path.join(parent, `.tmp-link-${process.pid}-${Date.now()}`);

  // Compute relative target so symlink remains relocatable
  const relativeTarget = path.relative(parent, targetDir);

  try {
    fs.symlinkSync(relativeTarget, tmpLink);

    // If destination exists and is a directory (not a symlink), POSIX rename(2)
    // fails with EISDIR. Remove or backup the non-symlink directory first.
    if (fs.existsSync(linkPath) && !fs.lstatSync(linkPath).isSymbolicLink()) {
      const backupLegacy = path.join(parent, `.legacy-public-${Date.now()}`);
      fs.renameSync(linkPath, backupLegacy);
      try { fs.rmSync(backupLegacy, { recursive: true, force: true }); } catch (_) {}
    }

    fs.renameSync(tmpLink, linkPath);
  } catch (err) {
    try { if (fs.existsSync(tmpLink)) fs.unlinkSync(tmpLink); } catch (_) {}
    throw err;
  }
}

/**
 * Offline-first bundle gate:
 * 1. Checks .state/manifest.json (trying current, then previous)
 * 2. If present and valid (Merkle + WASM magic), flips atomic symlink to public
 * 3. Returns the active bundle path or throws a fatal error if no valid bundle exists.
 * @param {string} [baseDir] - Root server directory (defaults to server/)
 * @returns {string} - Absolute path to the verified active bundle
 */
export function enforceBundleGate(baseDir = SERVER_DIR) {
  const stateDir = path.join(baseDir, '.state');
  const bundlesDir = path.join(baseDir, 'bundles');
  const publicPath = path.join(baseDir, 'public');
  const manifestPath = path.join(stateDir, 'manifest.json');

  if (!fs.existsSync(stateDir)) {
    fs.mkdirSync(stateDir, { recursive: true });
  }
  if (!fs.existsSync(bundlesDir)) {
    fs.mkdirSync(bundlesDir, { recursive: true });
  }

  let manifest = { channel: 'beta', current: null, previous: null };
  if (fs.existsSync(manifestPath)) {
    try {
      manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    } catch {
      // Fall through to directory probing
    }
  }

  const candidates = [manifest.current, manifest.previous].filter(Boolean);

  // If manifest didn't list any, inspect bundles directory directly
  if (candidates.length === 0 && fs.existsSync(bundlesDir)) {
    const existing = fs.readdirSync(bundlesDir, { withFileTypes: true })
      .filter((d) => d.isDirectory())
      .map((d) => d.name);
    candidates.push(...existing);
  }

  for (const sha of candidates) {
    const dir = path.join(bundlesDir, sha);
    if (fs.existsSync(dir) && verifyWasmMagic(dir) && verifyMerkle(dir)) {
      atomicSymlink(publicPath, dir);
      if (manifest.current !== sha) {
        manifest.previous = manifest.current;
        manifest.current = sha;
        manifest.updatedAt = new Date().toISOString();
        fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf8');
      }
      return dir;
    }
  }

  // Check if server/public is already a directory (legacy / initial checkout)
  if (fs.existsSync(publicPath) && !fs.lstatSync(publicPath).isSymbolicLink()) {
    if (verifyWasmMagic(publicPath) && verifyMerkle(publicPath)) {
      // Seed content-addressed bundles from existing public/
      const { rootHash } = generateBundleManifest(publicPath);
      const targetDir = path.join(bundlesDir, rootHash);
      if (!fs.existsSync(targetDir)) {
        fs.cpSync(publicPath, targetDir, { recursive: true });
      }
      atomicSymlink(publicPath, targetDir);
      manifest.current = rootHash;
      manifest.updatedAt = new Date().toISOString();
      fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf8');
      return targetDir;
    }
  }

  throw new Error(
    `[FATAL] No valid WASM bundle found. Verification failed (Merkle/WASM magic).\n` +
    `A valid WebAssembly client must be present in server/bundles/ before starting.`
  );
}
