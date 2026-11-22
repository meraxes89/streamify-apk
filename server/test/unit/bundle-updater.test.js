import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import { extractZipSafe } from '../../src/platform/zip-extractor.mjs';
import {
  acquireUpdateLock,
  releaseUpdateLock,
  verifyManifestSignature,
  gcBundles,
  parseSemVer,
  compareSemVer,
} from '../../scripts/update-bundle.mjs';

describe('Phase 2 Bundle Updater: Security & Single-Flight Governance', () => {
  const tmpBase = path.join(os.tmpdir(), `test-bundle-updater-${Date.now()}`);

  test('Single-flight flock guard prevents concurrent update processes', () => {
    const lock1 = acquireUpdateLock();
    assert.ok(lock1 !== null, 'Primary update process acquires lock');

    const lock2 = acquireUpdateLock();
    assert.equal(lock2, null, 'Secondary concurrent process is blocked by single-flight lock');

    releaseUpdateLock(lock1);

    const lock3 = acquireUpdateLock();
    assert.ok(lock3 !== null, 'Lock is re-acquirable once released');
    releaseUpdateLock(lock3);
  });

  test('Ed25519 manifest signature verification passes on valid, fails on tampering', () => {
    const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
    const pubPem = publicKey.export({ type: 'spki', format: 'pem' });

    const payload = JSON.stringify({
      channel: 'beta',
      sha256: 'deadbeef1234567890abcdef1234567890abcdef1234567890abcdef12345678',
      tag: 'v1.0.1-beta',
    });

    const signature = crypto.sign(null, Buffer.from(payload), privateKey);
    const signatureBase64 = signature.toString('base64');

    // Valid signature
    assert.equal(verifyManifestSignature(payload, signatureBase64, pubPem), true);

    // Tampered payload
    const tamperedPayload = JSON.stringify({
      channel: 'beta',
      sha256: 'attacker-tampered-hash',
      tag: 'v1.0.1-beta',
    });
    assert.equal(verifyManifestSignature(tamperedPayload, signatureBase64, pubPem), false);

    // Corrupted signature
    assert.equal(verifyManifestSignature(payload, 'corrupted_signature', pubPem), false);
  });

  test('Zip-slip guard rejects malicious entries escaping destination directory', () => {
    const testDir = path.join(tmpBase, 'zip-slip-test');
    fs.mkdirSync(testDir, { recursive: true });

    // Craft zip entry with relative traversal: ../evil.txt
    const evilName = Buffer.from('../evil.txt');
    const evilContent = Buffer.from('malicious payload');

    const localHeader = Buffer.alloc(30 + evilName.length + evilContent.length);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6);
    localHeader.writeUInt16LE(0, 8);
    localHeader.writeUInt32LE(0, 10);
    localHeader.writeUInt32LE(0, 14);
    localHeader.writeUInt32LE(evilContent.length, 18);
    localHeader.writeUInt32LE(evilContent.length, 22);
    localHeader.writeUInt16LE(evilName.length, 26);
    localHeader.writeUInt16LE(0, 28);
    evilName.copy(localHeader, 30);
    evilContent.copy(localHeader, 30 + evilName.length);

    const cdHeader = Buffer.alloc(46 + evilName.length);
    cdHeader.writeUInt32LE(0x02014b50, 0);
    cdHeader.writeUInt16LE(20, 4);
    cdHeader.writeUInt16LE(20, 6);
    cdHeader.writeUInt16LE(0, 8);
    cdHeader.writeUInt16LE(0, 10);
    cdHeader.writeUInt32LE(0, 12);
    cdHeader.writeUInt32LE(0, 16);
    cdHeader.writeUInt32LE(evilContent.length, 20);
    cdHeader.writeUInt32LE(evilContent.length, 24);
    cdHeader.writeUInt16LE(evilName.length, 28);
    cdHeader.writeUInt16LE(0, 30);
    cdHeader.writeUInt16LE(0, 32);
    cdHeader.writeUInt16LE(0, 34);
    cdHeader.writeUInt16LE(0, 36);
    cdHeader.writeUInt32LE(0, 38);
    cdHeader.writeUInt32LE(0, 42);
    evilName.copy(cdHeader, 46);

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(0, 4);
    eocd.writeUInt16LE(0, 6);
    eocd.writeUInt16LE(1, 8);
    eocd.writeUInt16LE(1, 10);
    eocd.writeUInt32LE(cdHeader.length, 12);
    eocd.writeUInt32LE(localHeader.length, 16);
    eocd.writeUInt16LE(0, 20);

    const maliciousZip = Buffer.concat([localHeader, cdHeader, eocd]);

    assert.throws(
      () => extractZipSafe(maliciousZip, path.join(testDir, 'extract')),
      /Zip-slip attempt detected and blocked/
    );

    fs.rmSync(testDir, { recursive: true, force: true });
  });

  test('SemVer parser extracts major, minor, patch, and prerelease tags', () => {
    assert.deepEqual(parseSemVer('v1.0.2-beta'), {
      major: 1,
      minor: 0,
      patch: 2,
      prerelease: 'beta',
    });
    assert.deepEqual(parseSemVer('2.15.0'), {
      major: 2,
      minor: 15,
      patch: 0,
      prerelease: '',
    });
    assert.deepEqual(parseSemVer(null), {
      major: 0,
      minor: 0,
      patch: 0,
      prerelease: '',
    });
  });

  test('SemVer comparator correctly orders versions and handles draft resolution', () => {
    // Newer patch higher
    assert.ok(compareSemVer('v1.0.2-beta', 'v1.0.1-beta') > 0);
    assert.ok(compareSemVer('v1.0.1-beta', 'v1.0.2-beta') < 0);

    // Major / minor differences
    assert.ok(compareSemVer('v2.0.0', 'v1.9.9') > 0);
    assert.ok(compareSemVer('v1.1.0', 'v1.0.9') > 0);

    // Release vs prerelease of same major.minor.patch
    assert.ok(compareSemVer('v1.0.1', 'v1.0.1-beta') > 0);
    assert.ok(compareSemVer('v1.0.1-beta', 'v1.0.1') < 0);

    // Equal versions
    assert.equal(compareSemVer('v1.0.2', '1.0.2'), 0);

    // Draft release exclusion test
    const mockReleases = [
      { id: 101, tag_name: 'v1.0.1-beta', draft: true },
      { id: 102, tag_name: 'v1.0.2-beta', draft: false },
      { id: 100, tag_name: 'v1.0.0', draft: false },
    ];

    const nonDraftReleases = mockReleases
      .filter((r) => !r.draft)
      .sort((a, b) => compareSemVer(b.tag_name, a.tag_name));

    assert.equal(nonDraftReleases.length, 2);
    assert.equal(nonDraftReleases[0].tag_name, 'v1.0.2-beta', 'Draft v1.0.1-beta excluded; highest SemVer selected');
    assert.equal(nonDraftReleases[1].tag_name, 'v1.0.0');
  });
});
