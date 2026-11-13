/**
 * g3bVerify.ts — DEV-ONLY verification harness (g3b-test.html).
 *
 * Runs the G3b acceptance matrix in a REAL browser, logging every step to
 * the DOM + title so headless tooling can read results without eval.
 * The relay comes from the standard candidate chain (?relay=… query,
 * VITE_RELAY_BASE_URL, dev default localhost:8787) — point ?relay= at the
 * production worker for a live smoke test. NOT part of the app build
 * (vite dev serves it; rollup only bundles main + canary).
 */

import { relayBaseCandidate } from './lib/engine';

const RELAY = relayBaseCandidate();
const logEl = document.getElementById('log') as HTMLPreElement;
const t0 = Date.now();

function log(line: string): void {
  const elapsed = ((Date.now() - t0) / 1000).toFixed(1).padStart(6, ' ');
  logEl.textContent += `[${elapsed}s] ${line}\n`;
  document.title = `G3B ${line.slice(0, 60)}`;
  console.log(`[g3b] ${line}`);
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

async function main(): Promise<void> {
  log('boot — importing modules');

  const ytSession = await import('./lib/ytSession');
  const resolve = await import('./audio/resolve');
  const potSession = await import('./audio/botguard/potSession');
  const hls = await import('./audio/hls/hlsRemux');

  // ---- 1. YouTube.js session boot -----------------------------------------
  const yt = await withBudget('session', 30_000, () => ytSession.getYt());
  if (yt === null) return;
  const c = yt.session.context.client;
  log(
    `session: PASS — ${c.clientName} ${c.clientVersion} visitor ${(c.visitorData ?? '').slice(0, 14)}… sts=${yt.session.player?.signature_timestamp ?? 'none'}`,
  );

  // ---- 2. VISIONOS rung (unlicensed, element compat) ------------------------
  const s2 = await withBudget('resolve-dQw-element', 45_000, () =>
    resolve.resolveAudioStream('dQw4w9WgXcQ', { relayBase: RELAY, elementCompat: true }),
  );
  if (s2 === null) return;
  log(
    `resolve dQw (element): ${s2.rung} itag=${s2.itag} ${s2.codec} clen=${s2.contentLength} hls=${s2.isHls} pot=${s2.potKind}`,
  );

  // ---- 3. Spine-mode resolve (webcodecs preference) ---------------------------
  const s3 = await withBudget('resolve-dQw-spine', 45_000, () =>
    resolve.resolveAudioStream('dQw4w9WgXcQ', { relayBase: RELAY }),
  );
  if (s3 === null) return;
  log(`resolve dQw (spine): ${s3.rung} itag=${s3.itag} ${s3.codec} clen=${s3.contentLength}`);

  // ---- 4. THE MINT (BgUtils main realm — the risky one) ------------------------
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
      mintDetail = ` kind=${minted.kind} playerTokenLen=${tok.length} sessionTokenLen=${minted.sessionToken?.length ?? 0} gvsBindsVideoId=${minted.gvsBindsToVideoId}`;
    } catch (err) {
      mintDetail = ` kind=${minted.kind} probeMintFailed=${fail(err).slice(0, 80)}`;
    }
  }
  log(
    `mint: ${minted !== null ? 'PASS' : 'FAILED'} in ${((Date.now() - mintStart) / 1000).toFixed(1)}s${mintDetail} — phase=${snap.phase} brave=${snap.braveShieldsAdvice} detail="${snap.detail.slice(0, 180)}"`,
  );

  // ---- 5. Licensed track (full ladder — datacenter egress honesty) ---------------
  const s5 = await withBudget('resolve-licensed', 90_000, () =>
    resolve.resolveAudioStream('4NRXx6U8ABQ', { relayBase: RELAY, elementCompat: true }),
  );
  if (s5 !== null) {
    log(
      `resolve 4NRXx6U8ABQ (licensed): RESOLVED ${s5.rung} itag=${s5.itag} pot=${s5.potKind} hls=${s5.isHls} clen=${s5.contentLength} title="${s5.title.slice(0, 24)}"`,
    );
  } else {
    log('resolve 4NRXx6U8ABQ: full ladder refused (see verdict above) — headless datacenter expectation');
  }

  // ---- 6. HLS remux lane (VISIONOS manifest — independent of the
  //         WEB_SAFARI rung's datacenter soft-wall) -----------------------------
  const manifestUrl = await withBudget('visionos-manifest', 45_000, async () => {
    const res = await fetch(
      `${RELAY}/r?u=${encodeURIComponent('https://www.youtube.com/youtubei/v1/player?prettyPrint=false&alt=json')}`,
      {
        method: 'POST',
        headers: {
          'content-type': 'application/json',
          'x-youtube-client-name': '101',
          'x-youtube-client-version': '1.02',
        },
        body: JSON.stringify({
          context: {
            client: {
              clientName: 'VISIONOS',
              clientVersion: '1.02',
              deviceMake: 'Apple',
              deviceModel: 'RealityDevice17,1',
              osName: 'visionOS',
              osVersion: '26.5.23O471',
              hl: 'en',
              gl: 'US',
            },
          },
          videoId: 'dQw4w9WgXcQ',
          contentCheckOk: true,
          racyCheckOk: true,
        }),
      },
    );
    const player = (await res.json()) as { streamingData?: { hlsManifestUrl?: string } };
    const hlsUrl = player.streamingData?.hlsManifestUrl;
    if (hlsUrl === undefined || hlsUrl.length === 0) throw new Error('visionos response has no hlsManifestUrl');
    return hlsUrl;
  });
  if (manifestUrl === null) {
    log('hls manifest: FAILED (visionos manifest fetch)');
  } else {
    log(`hls manifest: ${manifestUrl.slice(0, 60)}…`);
    const remux = await withBudget('hls-remux', 180_000, () =>
      hls.remuxHlsForElement(manifestUrl, {
        relayBase: RELAY,
        onProgress: (l, t, b) => {
          if (l === t || l % 20 === 0) log(`  remux progress ${l}/${t} segs (${(b / 1048576).toFixed(2)} MiB)`);
        },
      }),
    );
    if (remux !== null) {
      // Probe: can the element actually load + decode the produced file?
      const probe = await new Promise<string>((res) => {
        const a = document.createElement('audio');
        a.preload = 'metadata';
        a.src = remux.url;
        const done = setTimeout(() => res('ELEMENT TIMEOUT'), 20_000);
        a.addEventListener('loadedmetadata', () => {
          clearTimeout(done);
          res(`duration=${Number.isFinite(a.duration) ? a.duration.toFixed(1) : '∞'}s`);
        });
        a.addEventListener('error', () => {
          clearTimeout(done);
          res('ELEMENT ERROR');
        });
      });
      log(
        `remux: PASS — mode=${remux.mode} bytes=${(remux.bytes / 1048576).toFixed(2)} MiB · element probe: ${probe}`,
      );
    }
  }

  // ---- 6b. WEB_SAFARI rung + TS-segment remux (the lane the APP hit) ----------
  resolve.markRungFailed('dQw4w9WgXcQ', 'visionos');
  resolve.markRungFailed('dQw4w9WgXcQ', 'visionos-hls');
  const s6b = await withBudget('resolve-safari-hls', 45_000, () =>
    resolve.resolveAudioStream('dQw4w9WgXcQ', { relayBase: RELAY, elementCompat: true }),
  );
  if (s6b === null || !s6b.isHls) {
    log(`safari hls rung: ${s6b === null ? 'refused (probabilistic datacenter soft-wall)' : `${s6b.rung} (not hls)`}`);
  } else {
    log(`safari hls rung: ${s6b.rung} — remuxing (TS lane expected)`);
    const remux2 = await withBudget('safari-hls-remux', 180_000, () =>
      hls.remuxHlsForElement(s6b.url, {
        relayBase: RELAY,
        onProgress: (l, t, b) => {
          if (l === t || l % 50 === 0) log(`  ts-remux progress ${l}/${t} segs (${(b / 1048576).toFixed(2)} MiB)`);
        },
      }),
    );
    if (remux2 !== null) {
      const probe2 = await new Promise<string>((res) => {
        const a = document.createElement('audio');
        a.preload = 'metadata';
        a.src = remux2.url;
        const done = setTimeout(() => res('ELEMENT TIMEOUT'), 20_000);
        a.addEventListener('loadedmetadata', () => {
          clearTimeout(done);
          res(`duration=${Number.isFinite(a.duration) ? a.duration.toFixed(1) : '∞'}s`);
        });
        a.addEventListener('error', () => {
          clearTimeout(done);
          res('ELEMENT ERROR');
        });
      });
      log(
        `safari-hls remux: ${remux2 !== null ? 'PASS' : 'FAILED'} — mode=${remux2.mode} bytes=${(remux2.bytes / 1048576).toFixed(2)} MiB · element probe: ${probe2}`,
      );
    }
  }

  // ---- 7. ffmpeg.wasm lane (worker spawn + core load + a REAL remux) -----------
  const ff = await withBudget('ffmpeg-load', 90_000, () => hls.ensureFfmpeg());
  if (ff !== null) {
    const remux = await withBudget('ffmpeg-ts-remux', 90_000, async () => {
      // Synthesize 2s of silent AAC in an MPEG-TS container, then remux
      // it TS→m4a with the EXACT flags the HLS lane uses.
      await ff.exec([
        '-hide_banner',
        '-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=mono',
        '-t', '2', '-c:a', 'aac', '-b:a', '64k',
        'probe.ts',
      ]);
      await ff.exec([
        '-hide_banner', '-i', 'probe.ts',
        '-vn', '-c:a', 'copy', '-f', 'mp4', '-movflags', '+faststart',
        'probe.m4a',
      ]);
      const data = await ff.readFile('probe.m4a');
      const bytes = typeof data === 'string' ? new TextEncoder().encode(data) : data;
      await ff.deleteFile('probe.ts').catch(() => undefined);
      await ff.deleteFile('probe.m4a').catch(() => undefined);
      return bytes.byteLength;
    });
    log(
      `ffmpeg: LOAD PASS · ts→m4a remux: ${remux !== null ? `PASS (${remux} B out)` : 'FAILED (lavfi may be absent in the wasm core — see log)'}`,
    );
  } else {
    log('ffmpeg: LOAD FAILED');
  }

  log('MATRIX DONE');
  document.title = 'G3B MATRIX DONE';
}

void main();
