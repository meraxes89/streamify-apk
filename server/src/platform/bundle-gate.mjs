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
export function verifyMerkle(bundleDir, expectedRootHash = null) {
  try {
    const manifestPath = path.join(bundleDir, '.bundle.json');
    if (!fs.existsSync(manifestPath)) {
      return false;
    }
    const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
    if (!manifest.files || typeof manifest.files !== 'object') return false;
    if (typeof manifest.rootHash !== 'string' || !/^[0-9a-f]{64}$/i.test(manifest.rootHash)) {
      return false;
    }
    if (expectedRootHash && manifest.rootHash !== expectedRootHash) {
      return false;
    }

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
    return calculatedRoot === manifest.rootHash;
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
 * @param {string} [baseDir] - Root server directory (defaults to server/)
 * @param {object} [options] - Configuration options
 * @param {boolean} [options.allowDegraded=false] - If true, falls back to a placeholder rather than throwing
 * @returns {string} - Absolute path to the active bundle or placeholder directory
 */
export function enforceBundleGate(baseDir = SERVER_DIR, { allowDegraded = false } = {}) {
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
    if (fs.existsSync(dir) && verifyWasmMagic(dir) && verifyMerkle(dir, sha)) {
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

  // Check if a local Kotlin Compose wasmJs build exists in ../composeApp/build/dist/wasmJs/productionExecutable
  const localBuildDir = path.resolve(baseDir, '../composeApp/build/dist/wasmJs/productionExecutable');
  if (fs.existsSync(localBuildDir) && verifyWasmMagic(localBuildDir) && verifyMerkle(localBuildDir)) {
    const { rootHash } = generateBundleManifest(localBuildDir);
    const targetDir = path.join(bundlesDir, rootHash);
    if (!fs.existsSync(targetDir)) {
      fs.cpSync(localBuildDir, targetDir, { recursive: true });
    }
    atomicSymlink(publicPath, targetDir);
    manifest.current = rootHash;
    manifest.updatedAt = new Date().toISOString();
    fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), 'utf8');
    return targetDir;
  }

  if (allowDegraded) {
    const placeholderDir = path.join(stateDir, 'placeholder');
    if (!fs.existsSync(placeholderDir)) {
      fs.mkdirSync(placeholderDir, { recursive: true });
    }
    const placeholderHtml = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Streamify — Initializing</title>
  <style>
    body { font-family: system-ui, -apple-system, sans-serif; background: #0e0e11; color: #efeff1; display: flex; align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
    .card { background: #18181b; border: 1px solid #27272a; border-radius: 12px; padding: 32px; max-width: 480px; text-align: center; box-shadow: 0 8px 24px rgba(0,0,0,0.5); }
    h1 { font-size: 20px; font-weight: 600; margin: 0 0 12px; }
    p { font-size: 14px; color: #a1a1aa; line-height: 1.5; margin: 0 0 20px; }
    .badge { display: inline-block; background: #27272a; color: #10b981; font-size: 12px; padding: 4px 10px; border-radius: 9999px; margin-bottom: 16px; font-weight: 500; }
    .spinner { width: 24px; height: 24px; border: 2px solid #27272a; border-top-color: #6366f1; border-radius: 50%; animation: spin 0.8s linear infinite; margin: 0 auto 16px; }
    @keyframes spin { to { transform: rotate(360deg); } }
  </style>
  <script>
    setInterval(async () => {
      try {
        const res = await fetch('/');
        const text = await res.text();
        if (!text.includes('Streamify — Initializing')) {
          window.location.reload();
        }
      } catch {}
    }, 3000);
  </script>
</head>
<body>
  <div class="card">
    <div class="badge">API Relay Active</div>
    <div class="spinner"></div>
    <h1>Initializing Client Bundle</h1>
    <p>Streamify server is running. Downloading and verifying the WebAssembly client bundle in the background. This page will reload automatically once ready.</p>
  </div>
</body>
</html>`;
    fs.writeFileSync(path.join(placeholderDir, 'index.html'), placeholderHtml, 'utf8');
    atomicSymlink(publicPath, placeholderDir);
    return placeholderDir;
  }

  throw new Error(
    `[FATAL] No valid WASM bundle found. Verification failed (Merkle/WASM magic).\n` +
    `A valid WebAssembly client must be present in server/bundles/ before starting.`
  );
}
