/**
 * Sovereign HAL Phase 3 (Step 2) — the standalone fixture-server entry.
 *
 * Spawned by the Rust E2E suite (rust/crates/streamify-playback/tests/
 * test_fastify_e2e.rs). Protocol:
 *
 *   stdout: one JSON line { ok: true, port: <fastifyPort>, upstreamPort }
 *           once the app is listening, then NOTHING else (the Rust side
 *           reads exactly one line).
 *   SIGTERM/SIGINT: graceful close, exit 0.
 *
 * Env:
 *   SOV_RAW_OPUS=1 — resolve itag 251 to the raw #225 framing lane
 *                    (the framing the Rust OpusTrackDecoder decodes).
 */
import { createSovereignFixtureApp } from './fixture-app.mjs';

const app = await createSovereignFixtureApp({ rawOpus: process.env.SOV_RAW_OPUS === '1' });

// One JSON line, then silence — the Rust parent parses line 1 only.
process.stdout.write(
  JSON.stringify({ ok: true, port: app.app.server.address().port, upstreamPort: app.upstream.port }) + '\n'
);

let closing = false;
async function shutdown() {
  if (closing) return;
  closing = true;
  try {
    await app.close();
  } finally {
    process.exit(0);
  }
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
