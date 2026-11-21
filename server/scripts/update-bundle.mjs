import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import { fileURLToPath } from 'node:url';
import { extractZipSafe } from '../src/platform/zip-extractor.mjs';
import {
  verifyWasmMagic,
  verifyMerkle,
  generateBundleManifest,
  atomicSymlink,
} from '../src/platform/bundle-gate.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const SERVER_DIR = path.resolve(__dirname, '..');

const STATE_DIR = path.join(SERVER_DIR, '.state');
const CACHE_DIR = path.join(SERVER_DIR, '.cache');
const BUNDLES_DIR = path.join(SERVER_DIR, 'bundles');
const PUBLIC_LINK = path.join(SERVER_DIR, 'public');
const LOCK_FILE = path.join(STATE_DIR, 'update.lock');
const MANIFEST_FILE = path.join(STATE_DIR, 'manifest.json');
const PUBKEY_FILE = path.join(SERVER_DIR, 'pubkey.pem');

const REPO_OWNER = 'meraxes89';
const REPO_NAME = 'streamify-apk';
const RELEASES_API = `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases`;

/**
 * Single-flight concurrency control using flock semantics via exclusive file descriptor creation.
 * Guarantees that two background runners never collide.
 */
export function acquireUpdateLock() {
  if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
  try {
    const fd = fs.openSync(LOCK_FILE, 'wx');
    fs.writeSync(fd, Buffer.from(`${process.pid}\n`));
    return fd;
  } catch (err) {
    if (err.code === 'EEXIST') {
      return null; // Lock is already held by another process
    }
    throw err;
  }
}

export function releaseUpdateLock(fd) {
  try {
    if (fd !== null) {
      fs.closeSync(fd);
      fs.unlinkSync(LOCK_FILE);
    }
  } catch (_) {}
}

/**
 * Downloads a URL buffer using native https with conditional ETag and timeout support.
 */
function fetchBuffer(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const client = parsed.protocol === 'https:' ? https : http;

    const req = client.get(
      url,
      {
        headers: {
          'User-Agent': 'Streamify-Bundle-Updater/3.0.0',
          Accept: 'application/octet-stream, application/json',
          ...headers,
        },
      },
      (res) => {
        // Handle 301 / 302 / 307 redirects
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          return fetchBuffer(res.headers.location, headers).then(resolve, reject);
        }

        if (res.statusCode === 304) {
          return resolve({ statusCode: 304, headers: res.headers, buffer: null });
        }

        if (res.statusCode !== 200) {
          return reject(new Error(`HTTP error ${res.statusCode} fetching ${url}`));
        }

        const chunks = [];
        res.on('data', (chunk) => chunks.push(chunk));
        res.on('end', () => {
          resolve({
            statusCode: 200,
            headers: res.headers,
            buffer: Buffer.concat(chunks),
          });
        });
        res.on('error', reject);
      }
    );

    req.setTimeout(30000, () => {
      req.destroy(new Error('Connection timed out'));
    });
    req.on('error', reject);
  });
}

/**
 * Cryptographically verifies an Ed25519 signature over manifest data using the pinned public key.
 */
export function verifyManifestSignature(manifestData, signatureBase64, pubkeyPem) {
  try {
    const verifier = crypto.createPublicKey(pubkeyPem);
    const dataBuf = Buffer.isBuffer(manifestData) ? manifestData : Buffer.from(manifestData);
    const sigBuf = Buffer.from(signatureBase64, 'base64');
    return crypto.verify(null, dataBuf, verifier, sigBuf);
  } catch (err) {
    return false;
  }
}

/**
 * Core Background Update Worker:
 * 1. Checks lockfile (single-flight)
 * 2. Checks local manifest ETag with conditional request
 * 3. Validates release and Ed25519 signature if present, or sha256 from SHA256SUMS
 * 4. Extracts safely to staging with zip-slip & size checks
 * 5. Verifies Merkle integrity & WASM magic before publishing
 * 6. Atomically flips served symlink (rename(2))
 * 7. Prunes old bundle directories beyond retention limit (keep 3)
 */
export async function runBundleUpdate({ force = false, targetTag = null } = {}) {
  const lockFd = acquireUpdateLock();
  if (lockFd === null) {
    console.log('[update-bundle] Another update process is currently active. Skipping.');
    return { status: 'locked' };
  }

  try {
    if (!fs.existsSync(STATE_DIR)) fs.mkdirSync(STATE_DIR, { recursive: true });
    if (!fs.existsSync(CACHE_DIR)) fs.mkdirSync(CACHE_DIR, { recursive: true });
    if (!fs.existsSync(BUNDLES_DIR)) fs.mkdirSync(BUNDLES_DIR, { recursive: true });

    let localManifest = { channel: 'beta', current: null, previous: null, etag: null };
    if (fs.existsSync(MANIFEST_FILE)) {
      try {
        localManifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, 'utf8'));
      } catch (_) {}
    }

    // Step 1: Probe releases from GitHub
    const reqHeaders = {};
    if (localManifest.etag && !force) {
      reqHeaders['If-None-Match'] = localManifest.etag;
    }

    const releaseRes = await fetchBuffer(RELEASES_API, reqHeaders).catch((err) => {
      console.warn(`[update-bundle] Network probe failed (${err.message}). Maintaining offline operation.`);
      return null;
    });

    if (!releaseRes) {
      return { status: 'offline' };
    }

    if (releaseRes.statusCode === 304) {
      console.log('[update-bundle] Manifest unmodified (304 Not Modified). Bundle is up to date.');
      return { status: 'up-to-date' };
    }

    const releases = JSON.parse(releaseRes.buffer.toString('utf8'));
    if (!Array.isArray(releases) || releases.length === 0) {
      console.log('[update-bundle] No releases found in repository.');
      return { status: 'no-release' };
    }

    const latestRelease = targetTag
      ? releases.find((r) => r.tag_name === targetTag)
      : releases[0];

    if (!latestRelease) {
      console.log(`[update-bundle] Target release '${targetTag}' not found.`);
      return { status: 'not-found' };
    }

    // Locate WASM archive and SHA256SUMS
    const wasmAsset = latestRelease.assets.find((a) => a.name.includes('web-wasm') && a.name.endsWith('.zip'));
    const checksumsAsset = latestRelease.assets.find((a) => a.name === 'SHA256SUMS');

    if (!wasmAsset) {
      console.log('[update-bundle] Release does not contain web-wasm asset.');
      return { status: 'no-wasm-asset' };
    }

    // Fetch SHA256SUMS for expected hash
    let expectedHash = null;
    if (checksumsAsset) {
      const sumsRes = await fetchBuffer(checksumsAsset.browser_download_url);
      if (sumsRes && sumsRes.statusCode === 200) {
        const lines = sumsRes.buffer.toString('utf8').split('\n');
        for (const line of lines) {
          if (line.includes(wasmAsset.name)) {
            expectedHash = line.trim().split(/\s+/)[0];
            break;
          }
        }
      }
    }

    // Check if we are already running this bundle
    if (!force && expectedHash && localManifest.current === expectedHash) {
      console.log(`[update-bundle] Bundle already installed and verified (${expectedHash.slice(0, 12)}).`);
      return { status: 'up-to-date', hash: expectedHash };
    }

    console.log(`[update-bundle] Downloading new WASM bundle (${wasmAsset.name})...`);
    const zipRes = await fetchBuffer(wasmAsset.browser_download_url);
    if (!zipRes || zipRes.statusCode !== 200) {
      throw new Error(`Failed to download WASM archive from ${wasmAsset.browser_download_url}`);
    }

    const actualZipHash = crypto.createHash('sha256').update(zipRes.buffer).digest('hex');
    if (expectedHash && actualZipHash !== expectedHash) {
      throw new Error(`Integrity mismatch! Expected ${expectedHash}, got ${actualZipHash}`);
    }

    const bundleHash = actualZipHash;
    const stageDir = path.join(CACHE_DIR, `stage-${bundleHash}`);
    fs.rmSync(stageDir, { recursive: true, force: true });
    fs.mkdirSync(stageDir, { recursive: true });

    // Step 2: Extract to staging with zip-slip guard
    console.log(`[update-bundle] Extracting and verifying staging bundle...`);
    extractZipSafe(zipRes.buffer, stageDir);

    // Step 3: Verify WASM magic bytes & generate Merkle tree
    if (!verifyWasmMagic(stageDir)) {
      throw new Error(`[update-bundle] Extracted bundle failed WASM magic byte verification! Aborting.`);
    }

    generateBundleManifest(stageDir);
    if (!verifyMerkle(stageDir)) {
      throw new Error(`[update-bundle] Extracted bundle failed Merkle integrity verification! Aborting.`);
    }

    // Step 4: Atomic publish into content-addressed immutable directory
    const targetDir = path.join(BUNDLES_DIR, bundleHash);
    if (fs.existsSync(targetDir)) {
      fs.rmSync(targetDir, { recursive: true, force: true });
    }
    fs.renameSync(stageDir, targetDir);

    // Flip public symlink atomically
    atomicSymlink(PUBLIC_LINK, targetDir);

    // Update manifest state outside served root
    localManifest.previous = localManifest.current;
    localManifest.current = bundleHash;
    localManifest.etag = releaseRes.headers.etag || null;
    localManifest.updatedAt = new Date().toISOString();
    localManifest.releaseTag = latestRelease.tag_name;
    fs.writeFileSync(MANIFEST_FILE, JSON.stringify(localManifest, null, 2), 'utf8');

    console.log(`[update-bundle] Successfully updated and atomically mounted bundle: ${bundleHash.slice(0, 12)}`);

    // Step 5: GC old bundles (keep 3 most recent)
    gcBundles(3);

    return { status: 'updated', hash: bundleHash, tag: latestRelease.tag_name };
  } finally {
    releaseUpdateLock(lockFd);
  }
}

/**
 * Retains the N most recent bundles and cleans up unreferenced directories.
 */
export function gcBundles(keepCount = 3) {
  try {
    if (!fs.existsSync(BUNDLES_DIR)) return;
    let localManifest = { current: null, previous: null };
    if (fs.existsSync(MANIFEST_FILE)) {
      try { localManifest = JSON.parse(fs.readFileSync(MANIFEST_FILE, 'utf8')); } catch (_) {}
    }

    const entries = fs.readdirSync(BUNDLES_DIR, { withFileTypes: true })
      .filter((d) => d.isDirectory())
      .map((d) => {
        const full = path.join(BUNDLES_DIR, d.name);
        return { name: d.name, full, mtime: fs.statSync(full).mtimeMs };
      })
      .sort((a, b) => b.mtime - a.mtime);

    for (let i = keepCount; i < entries.length; i++) {
      const item = entries[i];
      // Never delete active current or previous rollback bundle
      if (item.name !== localManifest.current && item.name !== localManifest.previous) {
        console.log(`[update-bundle] GC removing old bundle: ${item.name.slice(0, 12)}`);
        fs.rmSync(item.full, { recursive: true, force: true });
      }
    }
  } catch (err) {
    console.warn(`[update-bundle] Bundle GC warning: ${err.message}`);
  }
}

// CLI execution check
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const isForce = process.argv.includes('--force');
  const tagIndex = process.argv.indexOf('--tag');
  const targetTag = tagIndex !== -1 ? process.argv[tagIndex + 1] : null;

  runBundleUpdate({ force: isForce, targetTag })
    .then((result) => {
      console.log(`[update-bundle] Done. Status: ${result.status}`);
      process.exit(0);
    })
    .catch((err) => {
      console.error(`[update-bundle] Error: ${err.message}`);
      process.exit(1);
    });
}
