import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {
  verifyWasmMagic,
  verifyMerkle,
  generateBundleManifest,
  atomicSymlink,
  enforceBundleGate,
} from '../../src/platform/bundle-gate.mjs';

describe('Bundle Gate: Content-Addressed Offline-First Integrity', () => {
  const tmpBase = path.join(os.tmpdir(), `test-bundle-gate-${Date.now()}`);

  test('WASM Magic validation rejects corrupt / invalid headers', () => {
    const testDir = path.join(tmpBase, 'magic-test');
    fs.mkdirSync(testDir, { recursive: true });

    // Valid 8-byte WASM magic: \0asm\1\0\0\0
    const validWasm = Buffer.from([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0xde, 0xad]);
    fs.writeFileSync(path.join(testDir, 'app.wasm'), validWasm);
    assert.equal(verifyWasmMagic(testDir), true);

    // Corrupt magic
    const invalidWasm = Buffer.from([0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00]);
    fs.writeFileSync(path.join(testDir, 'app.wasm'), invalidWasm);
    assert.equal(verifyWasmMagic(testDir), false);

    // Truncated magic (< 8 bytes)
    fs.writeFileSync(path.join(testDir, 'app.wasm'), Buffer.from([0x00, 0x61]));
    assert.equal(verifyWasmMagic(testDir), false);

    fs.rmSync(testDir, { recursive: true, force: true });
  });

  test('Merkle tree generation and tamper detection', () => {
    const testDir = path.join(tmpBase, 'merkle-test');
    fs.mkdirSync(testDir, { recursive: true });

    fs.writeFileSync(path.join(testDir, 'index.html'), '<html><body>Streamify</body></html>');
    fs.writeFileSync(path.join(testDir, 'composeApp.js'), 'console.log("hello");');
    fs.writeFileSync(path.join(testDir, 'app.wasm'), Buffer.from([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00]));

    const { rootHash, fileCount } = generateBundleManifest(testDir);
    assert.equal(fileCount, 3);
    assert.ok(rootHash && rootHash.length === 64);
    assert.equal(verifyMerkle(testDir), true);

    // Tamper with a file
    fs.writeFileSync(path.join(testDir, 'index.html'), '<html><body>Tampered</body></html>');
    assert.equal(verifyMerkle(testDir), false);

    fs.rmSync(testDir, { recursive: true, force: true });
  });

  test('Atomic symlink swap never leaves broken links or non-symlinks', () => {
    const testDir = path.join(tmpBase, 'symlink-test');
    fs.mkdirSync(testDir, { recursive: true });

    const bundleA = path.join(testDir, 'bundle-a');
    const bundleB = path.join(testDir, 'bundle-b');
    const linkPath = path.join(testDir, 'public');

    fs.mkdirSync(bundleA, { recursive: true });
    fs.mkdirSync(bundleB, { recursive: true });
    fs.writeFileSync(path.join(bundleA, 'a.txt'), 'version A');
    fs.writeFileSync(path.join(bundleB, 'b.txt'), 'version B');

    // Flip to A
    atomicSymlink(linkPath, bundleA);
    assert.ok(fs.lstatSync(linkPath).isSymbolicLink());
    assert.equal(fs.readFileSync(path.join(linkPath, 'a.txt'), 'utf8'), 'version A');

    // Flip to B
    atomicSymlink(linkPath, bundleB);
    assert.ok(fs.lstatSync(linkPath).isSymbolicLink());
    assert.equal(fs.readFileSync(path.join(linkPath, 'b.txt'), 'utf8'), 'version B');

    fs.rmSync(testDir, { recursive: true, force: true });
  });

  test('enforceBundleGate hard gate throws if no valid bundle exists', () => {
    const emptyBase = path.join(tmpBase, 'empty-server');
    fs.mkdirSync(emptyBase, { recursive: true });

    assert.throws(
      () => enforceBundleGate(emptyBase),
      /\[FATAL\] No valid WASM bundle found/
    );

    fs.rmSync(emptyBase, { recursive: true, force: true });
  });

  test('enforceBundleGate in allowDegraded mode mounts fallback placeholder without throwing', () => {
    const emptyBase = path.join(tmpBase, 'empty-server-degraded');
    fs.mkdirSync(emptyBase, { recursive: true });

    const placeholderDir = enforceBundleGate(emptyBase, { allowDegraded: true });
    assert.ok(fs.existsSync(placeholderDir));
    assert.ok(fs.existsSync(path.join(placeholderDir, 'index.html')));

    const publicPath = path.join(emptyBase, 'public');
    assert.ok(fs.existsSync(publicPath));
    assert.ok(fs.lstatSync(publicPath).isSymbolicLink());

    fs.rmSync(emptyBase, { recursive: true, force: true });
  });
});
