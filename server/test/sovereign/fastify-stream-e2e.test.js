/**
 * Sovereign HAL Phase 3 (Step 2) — the real-track Fastify /api/stream
 * HTTP 206 parity suite.
 *
 * Proves the fixture harness serves the REAL codec fixtures through the
 * PRODUCTION gateway stack (StreamGateway + StreamSemaphore + watchdog
 * + SparseBlockCache, the exact classes src/app.js wires) with correct
 * byte-range semantics — the server half of the Phase 3 E2E parity
 * gate. The Rust half (test_fastify_e2e.rs) drives this same app from
 * the producer side.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createSovereignFixtureApp,
  rawOpusFraming,
  TRACKS,
} from './fixture-app.mjs';

let app = null;
let baseUrl = null;

test.before(async () => {
  const built = await createSovereignFixtureApp();
  app = built;
  baseUrl = built.baseUrl;
});

test.after(async () => {
  if (app) await app.close();
});

test('Phase 3 E2E (server): /api/stream serves itag 251 with 206 byte ranges', async () => {
  // A small initial range — the exact shape the Rust producer's fetch
  // stage issues (Range: bytes=0-65535).
  const res = await fetch(`${baseUrl}/api/stream/${TRACKS.sov_opus_251.videoId}`, {
    headers: { Range: 'bytes=0-65535' },
  });
  assert.equal(res.status, 206, 'range request must answer 206');
  assert.equal(res.headers.get('content-range'), `bytes 0-65535/67785`);
  assert.equal(res.headers.get('accept-ranges'), 'bytes');
  assert.match(res.headers.get('content-type') || '', /audio\/webm/);
  const bytes = Buffer.from(await res.arrayBuffer());
  assert.equal(bytes.length, 65536);
  // EBML magic at offset 0 — the real WebM container head.
  assert.equal(bytes.subarray(0, 4).toString('hex'), '1a45dfa3');
});

test('Phase 3 E2E (server): mid-track range lands on the exact bytes', async () => {
  const start = 4096;
  const end = 8191;
  const res = await fetch(`${baseUrl}/api/stream/${TRACKS.sov_opus_251.videoId}`, {
    headers: { Range: `bytes=${start}-${end}` },
  });
  assert.equal(res.status, 206);
  const bytes = Buffer.from(await res.arrayBuffer());
  assert.equal(bytes.length, end - start + 1);
  // Byte-identity against the fixture: the gateway must serve the
  // upstream's exact bytes (no transcode, no mutation).
  const fs = await import('node:fs');
  const fixture = fs.readFileSync(
    new URL('../../../fixtures/audio/opus_dual_tone_48k.webm', import.meta.url)
  );
  assert.ok(fixture.subarray(start, end + 1).equals(bytes), 'bytes must be fixture-identical');
});

test('Phase 3 E2E (server): itag 140 (AAC-LC M4A) serves 206 too', async () => {
  const res = await fetch(`${baseUrl}/api/stream/${TRACKS.sov_aac_140.videoId}`, {
    headers: { Range: 'bytes=0-1023' },
  });
  assert.equal(res.status, 206);
  assert.match(res.headers.get('content-type') || '', /audio\/mp4/);
  const bytes = Buffer.from(await res.arrayBuffer());
  assert.equal(bytes.length, 1024);
  // M4A: the ftyp box sits at offset 4.
  assert.equal(bytes.subarray(4, 8).toString('ascii'), 'ftyp');
});

test('Phase 3 E2E (server): no-Range GETs carry the full body', async () => {
  const res = await fetch(`${baseUrl}/api/stream/${TRACKS.sov_aac_140.videoId}`);
  // The gateway's established grammar: a known-length descriptor makes
  // every response a range-shaped 206 (Content-Range bytes 0-(N-1)/N),
  // Range header or not — the body is the complete fixture either way.
  assert.ok(res.status === 200 || res.status === 206, `got ${res.status}`);
  const bytes = Buffer.from(await res.arrayBuffer());
  assert.equal(bytes.length, 49644, 'the full M4A fixture');
  if (res.status === 206) {
    assert.equal(res.headers.get('content-range'), 'bytes 0-49643/49644');
  }
});

test('Phase 3 E2E (server): the raw #225 framing lane is the decoder contract', async () => {
  // The framing the Rust OpusTrackDecoder::RawHead decodes — pinned
  // here so a fixture regeneration that broke the contract fails fast.
  const framing = rawOpusFraming();
  assert.equal(framing.subarray(0, 8).toString('ascii'), 'OpusHead');
  // pre-skip is RFC 7845 LITTLE-endIAN at offset 10 (0x0138 = 312 for
  // the ffmpeg fixture — the decoder trims it per RFC 7845 §4.2).
  assert.equal(framing.readUInt16LE(10), 312);
  let off = 19;
  let packets = 0;
  while (off + 2 <= framing.length) {
    const len = framing.readUInt16BE(off);
    off += 2 + len;
    packets += 1;
  }
  assert.equal(off, framing.length, 'the framing must parse cleanly to its end');
  assert.ok(packets >= 100, `expected >=100 opus packets, saw ${packets}`);
});

test('Phase 3 E2E (server): fixture-info carries the integrity hashes', async () => {
  const res = await fetch(`${baseUrl}/api/sovereign/fixture-info`);
  assert.equal(res.status, 200);
  const info = await res.json();
  assert.ok(info.tracks.sov_opus_251.sha256.length === 64);
  assert.ok(info.tracks.sov_aac_140.sha256.length === 64);
});
