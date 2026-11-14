/**
 * g3bVerify.ts — DEV-ONLY verification harness (g3b-test.html), G4 edition.
 *
 * Runs the G4 acceptance matrix in a REAL browser, logging every step to
 * the DOM + title so headless tooling can read results without eval.
 * The relay comes from the standard candidate chain (?relay=… query,
 * VITE_RELAY_BASE_URL, dev default localhost:8787) — point ?relay= at the
 * production worker for a live smoke test. NOT part of the app build
 * (vite dev serves it; rollup only bundles main + canary).
 *
 * G4 acceptance rows (decisions/G4-residential-direct.md):
 *   1. DIRECT-lane reachability (youtubei.googleapis.com CORS probe)
 *   2. YouTube.js session boot (dual-lane)
 *   3. The ladder on the three canonical videos:
 *        dQw4w9WgXcQ   — standard content (expect visionos-direct, fast)
 *        tixIb21MO5U   — the licensed Bohemia track that failed every G3b
 *                        rung on the phone (expect ios-direct / ios-pot)
 *        4NRXx6U8ABQ   — The Weeknd (licensed, the G3b acceptance target)
 *   4. The mint (BgUtils main realm — now on the DIRECT jnn-pa legs)
 *   5. The SABR lane (googlevideo SabrStream → MSE → element probe)
 *   6. The HLS remux lane (fMP4 concat / ffmpeg.wasm) for hls rungs
 * ?v=VIDEO_ID adds an extra resolve probe for any video.
 */

import { relayBaseCandidate } from './lib/engine';
import type { ResolvedStream } from './audio/resolve';

const RELAY = relayBaseCandidate();
const logEl = document.getElementById('log') as HTMLPreElement;
const t0 = Date.now();

function log(line: string): void {
  const elapsed = ((Date.now() - t0) / 1000).toFixed(1).padStart(6, ' ');
  logEl.textContent += `[${elapsed}s] ${line}\n`;
  document.title = `G4 ${line.slice(0, 60)}`;
  console.log(`[g4] ${line}`);
}

function fail(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/** Run with a hard wall-clock budget (a hang must never wedge the page). */
async function withBudget<T>(
  label: string,
  ms: number,
  fn: () => Promise<T>,
): Promise<T | null> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    return await Promise.race([
      fn(),
      new Promise<never>((_, reject) => {
        timer = setTimeout(
          () => reject(new Error(`BUDGET EXCEEDED (${ms}ms)`)),
          ms,
        );
      }),
    ]);
  } catch (err) {
    log(`${label}: FAILED — ${fail(err).slice(0, 300)}`);
    return null;
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/** Probe an <audio> element against a URL: duration or error. */
function elementProbe(url: string, budgetMs = 20_000): Promise<string> {
  return new Promise<string>((res) => {
    const a = document.createElement('audio');
    a.preload = 'metadata';
    a.src = url;
    const done = setTimeout(() => res('ELEMENT TIMEOUT'), budgetMs);
    a.addEventListener('loadedmetadata', () => {
      clearTimeout(done);
      res(`duration=${Number.isFinite(a.duration) ? a.duration.toFixed(1) : '∞'}s`);
    });
    a.addEventListener('error', () => {
      clearTimeout(done);
      res('ELEMENT ERROR');
    });
  });
}

async function main(): Promise<void> {
  log('boot — G4 residential-direct matrix');

  const ytSession = await import('./lib/ytSession');
  const resolve = await import('./audio/resolve');
  const resolveApi = resolve as typeof import('./audio/resolve');
  const potSession = await import('./audio/botguard/potSession');

  // ---- 1. DIRECT-lane reachability (CORS probe, googleapis surface) -------
  const cors = await withBudget('direct-cors-probe', 20_000, async () => {
    const r = await fetch(
      'https://youtubei.googleapis.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false',
      {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          context: { client: { clientName: 'WEB', clientVersion: '2.20260708.00.00', hl: 'en', gl: 'US' } },
          videoId: 'dQw4w9WgXcQ',
        }),
      },
    );
    const bodyText = await r.text();
    return `HTTP ${r.status} (${bodyText.length} B)`;
  });
  log(
    `direct lane (youtubei.googleapis.com): ${cors ?? 'UNREACHABLE — every direct rung will relay-fallback (degraded)'}`,
  );

  // ---- 2. YouTube.js session boot (dual-lane + direct-born visitor) --------
  const yt = await withBudget('session', 30_000, () => ytSession.getYt());
  if (yt === null) return;
  const c = yt.session.context.client;
  log(
    `session: PASS — ${c.clientName} ${c.clientVersion} visitor ${(c.visitorData ?? '').slice(0, 14)}… (born: ${ytSession.visitorProvenance()}) sts=${yt.session.player?.signature_timestamp ?? 'none'}`,
  );
  // G4.3: the APP-family visitor (the raw rungs' identity — a second,
  // family-isolated birth; null means both lanes refused the /config).
  const appVisitor = await withBudget('app-visitor-birth', 15_000, () =>
    ytSession.appFamilyVisitorData(),
  );
  log(
    `app-family visitor: ${appVisitor !== null ? `PASS ${(appVisitor.slice(0, 14))}… (born: ${ytSession.appVisitorProvenance()})` : 'FAILED (both lanes refused the /config birth)'}`,
  );

  // ---- 3. The ladder on the canonical videos --------------------------------
  const extra = new URLSearchParams(window.location.search).getAll('v');
  const targets: Array<[string, string]> = [
    ['dQw4w9WgXcQ', 'standard'],
    ['tixIb21MO5U', 'licensed (the phone-failure case)'],
    ['4NRXx6U8ABQ', 'licensed (the G3b target)'],
    ['DOGP2dYUGE0', 'licensed (the G4 phone-failure case)'],
    ...extra.map((v) => [v, '?v= probe'] as [string, string]),
  ];
  const resolvedStreams: ResolvedStream[] = [];
  for (const [videoId, label] of targets) {
    const s = await withBudget(`resolve-${videoId}`, 90_000, () =>
      resolveApi.resolveAudioStream(videoId, { relayBase: RELAY, elementCompat: true }),
    );
    if (s === null) continue;
    resolvedStreams.push(s);
    log(
      `resolve ${videoId} (${label}): ${s.rung} itag=${s.itag} ${s.codec} hls=${s.isHls} sabr=${s.isSabr} pot=${s.potKind} clen=${s.contentLength} title="${s.title.slice(0, 28)}"`,
    );
  }

  // ---- 4. The mint (BgUtils main realm, DIRECT jnn-pa legs) -----------------
  log('mint: starting ensureLiveSession(force) — instrumented');
  const mintStart = Date.now();
  const minted = await withBudget('mint', 120_000, () =>
    potSession.ensureLiveSession(RELAY, { force: true }),
  );
  const snap = potSession.potSnapshot();
  let mintDetail = '';
  if (minted !== null) {
    try {
      const tok = await minted.mintPlayerToken('dQw4w9WgXcQ');
      mintDetail = ` kind=${minted.kind} playerTokenLen=${tok.length} sessionTokenLen=${minted.sessionToken?.length ?? 0}`;
    } catch (err) {
      mintDetail = ` kind=${minted.kind} probeMintFailed=${fail(err).slice(0, 80)}`;
    }
  }
  log(
    `mint: ${minted !== null ? 'PASS' : 'FAILED'} in ${((Date.now() - mintStart) / 1000).toFixed(1)}s${mintDetail} — phase=${snap.phase} brave=${snap.braveShieldsAdvice} detail="${snap.detail.slice(0, 180)}"`,
  );

  // ---- 5. The SABR lane (googlevideo SabrStream → MSE → element) ------------
  const sabrStream = resolvedStreams.find((s) => s.isSabr && s.sabr !== undefined);
  if (sabrStream === undefined) {
    log('sabr lane: not exercised (no SABR-only resolve in this run)');
  } else {
    const sabr = await import('./audio/sabr/sabrStreamPlayer');
    const handle = await withBudget('sabr-start', 60_000, () =>
      sabr.startSabrAudio(sabrStream.sabr!, true, RELAY),
    );
    if (handle === null) {
      log('sabr lane: FAILED to start');
    } else {
      handle.onFail((message) => log(`sabr lane: session failed — ${message.slice(0, 200)}`));
      const probe = await elementProbe(handle.objectUrl, 30_000);
      log(
        `sabr lane: ${sabrStream.rung} → MSE objectUrl · element probe: ${probe}`,
      );
      // Let the download run to 'finished' when it can (bounded).
      const settled = await Promise.race([
        handle.done.then(() => 'finished').catch((e: unknown) => `failed: ${fail(e).slice(0, 120)}`),
        new Promise<string>((res) => setTimeout(() => res('still-downloading'), 30_000)),
      ]);
      log(`sabr lane: download ${settled}`);
      handle.abort();
    }
  }

  // ---- 6. The HLS remux lane (fMP4 concat / ffmpeg.wasm) --------------------
  const hls = await import('./audio/hls/hlsRemux');
  const hlsStream = resolvedStreams.find((s) => s.isHls);
  if (hlsStream === undefined) {
    log('hls lane: not exercised (no HLS resolve in this run)');
  } else {
    const remux = await withBudget('hls-remux', 180_000, () =>
      hls.remuxHlsForElement(hlsStream.url, {
        relayBase: RELAY,
        onProgress: (l, t, b) => {
          if (l === t || l % 20 === 0) log(`  remux progress ${l}/${t} segs (${(b / 1048576).toFixed(2)} MiB)`);
        },
      }),
    );
    if (remux !== null) {
      const probe = await elementProbe(remux.url);
      log(`hls remux: PASS — mode=${remux.mode} bytes=${(remux.bytes / 1048576).toFixed(2)} MiB · element probe: ${probe}`);
    }
  }

  log('MATRIX DONE');
  document.title = 'G4 MATRIX DONE';
}

void main();
