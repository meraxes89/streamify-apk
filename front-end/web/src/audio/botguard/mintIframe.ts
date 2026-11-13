/// <reference types="vite/client" />
/**
 * audio/botguard/mintIframe.ts — P3b Tier 2: the hidden mint-iframe host.
 *
 * Owns the MAIN-UI-THREAD `<iframe srcdoc>` the BotGuard VM runs in (see
 * mintHarness.ts for why the VM must live there). This module is the
 * parent-side lifecycle:
 *
 *   • ONE hidden iframe per page (module singleton, concurrent-safe);
 *   • the harness module is loaded into it as a SAME-ORIGIN ES module —
 *     a dedicated rollup entry (`assets/mint-harness.js`, fixed name —
 *     see vite.config.ts) in the build, or the vite-dev-transformed
 *     source path in development. A `?url` import would emit the RAW
 *     .ts source (untransformed, bare imports) — useless in a browser;
 *   • the iframe is kept OFF-SCREEN but RENDERED (position:fixed at
 *     -9999px, 1x1, opacity 0 — never display:none): BotGuard's
 *     environment scoring reads real layout/visibility surfaces, and a
 *     display:none frame is a headless-shaped environment.
 *
 * The mint legs NEVER fetch from inside the iframe (no network = no CORS
 * story inside the frame) — the parent performs every relayed call and
 * feeds the iframe results through this handle.
 */

import type { MintHarnessApi } from './mintHarness';
import { MintError } from './protocol';

/** The harness module URL — same-origin in both dev and build. */
function harnessModuleUrl(): string {
  const base = import.meta.env.BASE_URL;
  return import.meta.env.DEV
    ? `${base}src/audio/botguard/mintHarness.ts`
    : `${base}assets/mint-harness.js`;
}

/** How long to wait for the harness module to boot inside the frame (ms). */
const HARNESS_BOOT_TIMEOUT_MS = 10_000;
/** Poll cadence while waiting for the harness API to appear. */
const HARNESS_POLL_MS = 50;

let harnessPromise: Promise<MintHarnessApi> | null = null;
let harnessFrame: HTMLIFrameElement | null = null;

/** The srcdoc document — minimal, same-origin, module-loaded. */
function buildSrcdoc(): string {
  // The module href is origin-absolute (BASE_URL-rooted) — srcdoc inherits
  // the parent's base URL, so it resolves to OUR origin in dev and build.
  const assetUrl = harnessModuleUrl();
  return [
    '<!doctype html>',
    '<html lang="en">',
    '<head>',
    '<meta charset="utf-8">',
    '<meta name="referrer" content="no-referrer">',
    '<title>Streamify mint frame</title>',
    '</head>',
    '<body>',
    `<script type="module" src="${assetUrl}"></script>`,
    '</body>',
    '</html>',
  ].join('');
}

function createFrame(): HTMLIFrameElement {
  const frame = document.createElement('iframe');
  frame.setAttribute('aria-hidden', 'true');
  frame.setAttribute('title', 'Streamify BotGuard mint frame');
  frame.setAttribute('tabindex', '-1');
  // Off-screen but RENDERED (see file header — display:none is a headless
  // fingerprint; the frame must own a real, visible-class layout box).
  frame.style.cssText =
    'position:fixed;left:-9999px;top:0;width:1px;height:1px;opacity:0;pointer-events:none;border:0;margin:0;padding:0;';
  frame.srcdoc = buildSrcdoc();
  document.body.appendChild(frame);
  return frame;
}

/** Wait for window.__streamifyMintHarness inside the frame (poll — no bidirectional channel exists before boot). */
async function waitForHarness(
  frame: HTMLIFrameElement,
  timeoutMs: number,
): Promise<MintHarnessApi> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const api = frame.contentWindow?.__streamifyMintHarness;
    if (api !== undefined) return api;
    if (Date.now() >= deadline) {
      throw new MintError(
        'iframe',
        `mint harness did not boot within ${timeoutMs} ms — module load failed? (asset: ${harnessModuleUrl()})`,
      );
    }
    await new Promise((r) => setTimeout(r, HARNESS_POLL_MS));
  }
}

/**
 * The harness handle (creating the hidden frame on first use). Concurrent
 * callers share one creation; a boot failure tears the frame down so the
 * next attempt starts clean.
 */
export function ensureMintIframe(): Promise<MintHarnessApi> {
  if (harnessPromise !== null) return harnessPromise;
  harnessPromise = (async () => {
    harnessFrame = createFrame();
    try {
      return await waitForHarness(harnessFrame, HARNESS_BOOT_TIMEOUT_MS);
    } catch (err) {
      teardownMintIframe();
      throw err;
    }
  })();
  harnessPromise.catch(() => {
    harnessPromise = null;
  });
  return harnessPromise;
}

/**
 * Tear the frame down (error paths / hard reset). A later ensure call
 * recreates it from scratch.
 */
export function teardownMintIframe(): void {
  harnessFrame?.remove();
  harnessFrame = null;
  harnessPromise = null;
}

/** Dev/diagnostics: is the mint frame alive? */
export function mintIframeAlive(): boolean {
  return harnessFrame !== null && harnessFrame.isConnected;
}
