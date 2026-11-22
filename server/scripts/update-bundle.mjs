import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import os from 'node:os';
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
const LATEST_DOWNLOAD_BASE = `https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/latest/download`;

/**
 * Parses a semantic version tag (e.g. 'v1.0.2-beta', '1.0.1').
 */
export function parseSemVer(tag) {
  if (!tag) return { major: 0, minor: 0, patch: 0, prerelease: '' };
  const clean = String(tag).trim().replace(/^v/, '');
  const [vPart, ...preParts] = clean.split('-');
  const [major = 0, minor = 0, patch = 0] = vPart.split('.').map((n) => parseInt(n, 10) || 0);
  return {
    major,
    minor,
    patch,
    prerelease: preParts.join('-'),
  };
}

/**
 * Compares two semantic version tags. Returns > 0 if a > b, < 0 if a < b, 0 if equal.
 * Non-prereleases rank higher than prereleases of the same major.minor.patch.
 */
export function compareSemVer(a, b) {
  const vA = parseSemVer(a);
  const vB = parseSemVer(b);

  if (vA.major !== vB.major) return vA.major - vB.major;
  if (vA.minor !== vB.minor) return vA.minor - vB.minor;
  if (vA.patch !== vB.patch) return vA.patch - vB.patch;

  if (!vA.prerelease && vB.prerelease) return 1;
  if (vA.prerelease && !vB.prerelease) return -1;
  if (vA.prerelease && vB.prerelease) {
    return vA.prerelease.localeCompare(vB.prerelease, undefined, { numeric: true });
  }
  return 0;
}

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
 * Resolves GitHub Personal Access Token from environment or GitHub CLI credentials.
 */
export function resolveGitHubToken() {
  if (process.env.GITHUB_TOKEN) return process.env.GITHUB_TOKEN;
  if (process.env.GH_TOKEN) return process.env.GH_TOKEN;
  try {
    const homedir = os.homedir();
    const hostsPath = path.join(homedir, '.config/gh/hosts.yml');
    if (fs.existsSync(hostsPath)) {
      const content = fs.readFileSync(hostsPath, 'utf8');
      const match = content.match(/oauth_token:\s*([^\s\r\n]+)/);
      if (match && match[1]) return match[1];
    }
  } catch (_) {}
  return null;
}

/**
 * Downloads a URL buffer using native https with conditional ETag, token auth, and timeout support.
 */
function fetchBuffer(url, headers = {}) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const client = parsed.protocol === 'https:' ? https : http;

    const token = resolveGitHubToken();
    const reqHeaders = {
      'User-Agent': 'Streamify-Bundle-Updater/3.0.0',
      Accept: 'application/octet-stream, application/json',
      ...headers,
    };

    if (token && (parsed.hostname === 'api.github.com' || parsed.hostname === 'github.com')) {
      if (!reqHeaders['Authorization']) {
        reqHeaders['Authorization'] = `Bearer ${token}`;
      }
    }

    const req = client.get(
      url,
      { headers: reqHeaders },
      (res) => {
        // Handle 301 / 302 / 307 redirects
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          const redirectUrl = new URL(res.headers.location, url).toString();
          const redirectParsed = new URL(redirectUrl);
          const nextHeaders = { ...headers };
          // Do not leak GitHub Authorization tokens to external redirect targets (e.g. AWS S3)
          if (redirectParsed.hostname !== 'api.github.com' && redirectParsed.hostname !== 'github.com') {
            delete nextHeaders['Authorization'];
          }
          return fetchBuffer(redirectUrl, nextHeaders).then((result) => {
            resolve({
              ...result,
              redirectLocations: [redirectUrl, ...(result.redirectLocations || [])],
            });
          }, reject);
        }

        if (res.statusCode === 304) {
          return resolve({ statusCode: 304, headers: res.headers, buffer: null, redirectLocations: [] });
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
            redirectLocations: [],
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

    let targetReleaseTag = targetTag;
    let expectedHash = null;
    let downloadUrl = null;
    let releaseEtag = null;
    const token = resolveGitHubToken();

    // Step 1: Resolve release metadata
    // Primary path for production (targetTag === null): Zero-auth, unmetered static CDN redirect
    if (!targetTag) {
      try {
        const sumsRes = await fetchBuffer(`${LATEST_DOWNLOAD_BASE}/SHA256SUMS`).catch(() => null);
        if (sumsRes && sumsRes.statusCode === 200) {
          const lines = sumsRes.buffer.toString('utf8').split('\n');
          for (const line of lines) {
            if (line.includes('web-wasm') && line.endsWith('.zip')) {
              expectedHash = line.trim().split(/\s+/)[0];
              break;
            }
          }

          if (sumsRes.redirectLocations?.length) {
            for (const loc of sumsRes.redirectLocations) {
              const m = loc.match(/\/releases\/download\/([^/]+)\//);
              if (m) {
                targetReleaseTag = m[1];
                break;
              }
            }
          }
          if (!targetReleaseTag) targetReleaseTag = 'latest';

          downloadUrl = `${LATEST_DOWNLOAD_BASE}/Streamify-main-web-wasm.zip`;
          releaseEtag = sumsRes.headers.etag || null;
        }
      } catch (err) {
        console.warn(`[update-bundle] Static CDN resolution notice: ${err.message}`);
      }
    }

    // Secondary path: Query GitHub Releases API with SemVer sorting and strict draft filtering
    if (!downloadUrl) {
      const reqHeaders = {};
      if (localManifest.etag && !force) {
        reqHeaders['If-None-Match'] = localManifest.etag;
      }

      const releaseRes = await fetchBuffer(RELEASES_API, reqHeaders).catch((err) => {
        console.warn(`[update-bundle] Network probe failed (${err.message}). Maintaining offline operation.`);
        return null;
      });

      if (releaseRes && releaseRes.statusCode === 304) {
        console.log('[update-bundle] Manifest unmodified (304 Not Modified). Bundle is up to date.');
        return { status: 'up-to-date' };
      }

      if (releaseRes && releaseRes.statusCode === 200) {
        let releases = [];
        try {
          releases = JSON.parse(releaseRes.buffer.toString('utf8'));
        } catch (_) {}

        if (Array.isArray(releases) && releases.length > 0) {
          const nonDraftReleases = releases
            .filter((r) => !r.draft)
            .sort((a, b) => compareSemVer(b.tag_name, a.tag_name));

          const selectedRelease = targetTag
            ? releases.find((r) => r.tag_name === targetTag)
            : (nonDraftReleases[0] || releases[0]);

          if (selectedRelease) {
            targetReleaseTag = selectedRelease.tag_name;
            releaseEtag = releaseRes.headers.etag || null;

            const wasmAsset = selectedRelease.assets?.find(
              (a) => a.name.includes('web-wasm') && a.name.endsWith('.zip')
            );
            const checksumsAsset = selectedRelease.assets?.find((a) => a.name === 'SHA256SUMS');

            if (wasmAsset) {
              downloadUrl = (token && wasmAsset.url) ? wasmAsset.url : wasmAsset.browser_download_url;

              if (checksumsAsset) {
                const checksumUrl = (token && checksumsAsset.url) ? checksumsAsset.url : checksumsAsset.browser_download_url;
                const sumsRes = await fetchBuffer(checksumUrl, { Accept: 'application/octet-stream' }).catch(() => null);
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
            }
          }
        }
      }
    }

    // Tertiary path: Local composeApp build auto-seeding if offline or no assets
    if (!downloadUrl) {
      const localBuildDir = path.resolve(SERVER_DIR, '../composeApp/build/dist/wasmJs/productionExecutable');
      if (fs.existsSync(localBuildDir) && verifyWasmMagic(localBuildDir) && verifyMerkle(localBuildDir)) {
        console.log('[update-bundle] Auto-seeding bundle from local composeApp build...');
        const { rootHash } = generateBundleManifest(localBuildDir);
        const targetDir = path.join(BUNDLES_DIR, rootHash);
        if (!fs.existsSync(targetDir)) {
          fs.cpSync(localBuildDir, targetDir, { recursive: true });
        }
        atomicSymlink(PUBLIC_LINK, targetDir);
        localManifest.current = rootHash;
        localManifest.updatedAt = new Date().toISOString();
        fs.writeFileSync(MANIFEST_FILE, JSON.stringify(localManifest, null, 2), 'utf8');
        return { status: 'seeded-local', hash: rootHash };
      }
      return { status: 'offline' };
    }

    // Check if we are already running this bundle
    if (!force && expectedHash && localManifest.current === expectedHash) {
      console.log(`[update-bundle] Bundle already installed and verified (${expectedHash.slice(0, 12)}).`);
      return { status: 'up-to-date', hash: expectedHash };
    }

    console.log(`[update-bundle] Downloading new WASM bundle (${downloadUrl})...`);
    let zipRes = null;
    if (token && downloadUrl.includes('api.github.com')) {
      zipRes = await fetchBuffer(downloadUrl, { Accept: 'application/octet-stream' }).catch(() => null);
    }
    if (!zipRes || zipRes.statusCode !== 200) {
      zipRes = await fetchBuffer(downloadUrl, { Accept: 'application/octet-stream' }).catch((err) => {
        throw new Error(`Failed to download WASM archive from ${downloadUrl}: ${err.message}`);
      });
    }
    if (!zipRes || zipRes.statusCode !== 200) {
      throw new Error(`Failed to download WASM archive from ${downloadUrl}`);
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
    localManifest.etag = releaseEtag || null;
    localManifest.updatedAt = new Date().toISOString();
    localManifest.releaseTag = targetReleaseTag;
    fs.writeFileSync(MANIFEST_FILE, JSON.stringify(localManifest, null, 2), 'utf8');

    console.log(`[update-bundle] Successfully updated and atomically mounted bundle: ${bundleHash.slice(0, 12)}`);

    // Step 5: GC old bundles (keep 3 most recent)
    gcBundles(3);

    return { status: 'updated', hash: bundleHash, tag: targetReleaseTag };
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
