/**
 * audio/botguard/mintHarness.ts — P3b Tier 2: the srcdoc-iframe mint realm.
 *
 * WHY AN IFRAME (the split-brain fix, architect's Angle B/C):
 *   The BotGuard interpreter inspects window.location / document.URL to
 *   decide whether it is running on youtube.com. On the github.io page the
 *   origin check FAILS and the VM aborts minter initialization — the
 *   measured `webPoSignalOutput empty` failure (W1.0). A REAL youtube.com
 *   iframe is impossible (X-Frame-Options: SAMEORIGIN + SOP), but a
 *   SAME-ORIGIN srcdoc iframe gives us what we actually need:
 *
 *     • a REAL browser DOM (canvas / WebGL / AudioContext / performance all
 *       genuine — this is the user's actual mobile hardware), and
 *     • a global object we can PRESENT as youtube.com, because the
 *       interpreter is evaluated with shadowed parameters (window, document,
 *       location, top, parent, self, globalThis) that resolve to Proxy
 *       wrappers spoofing https://www.youtube.com while passing canvas,
 *       WebGL, navigator, and performance STRAIGHT THROUGH to the real
 *       iframe environment.
 *
 *   window.location itself is [LegacyUnforgeable] (cannot be redefined on
 *   any window) — parameter shadowing inside `new Function(...)` is the
 *   only way unmodified challenge code can be made to see the spoof.
 *
 * WHY A SEPARATE MODULE (realm correctness):
 *   WebPoMinter does `result instanceof Uint8Array` and
 *   `mintCallback instanceof Function` checks. If the VM+minter lived in the
 *   parent realm and the mint harness in the iframe realm (or vice versa),
 *   cross-realm instanceof fails ('ODM:Invalid'). EVERYTHING minty — the
 *   interpreter eval, BotGuardClient, the snapshot, WebPoMinter — must live
 *   in ONE realm: this one. The parent loads this module into the iframe
 *   via a vite `?url` asset and drives it through window.__streamifyMintHarness
 *   (same-origin srcdoc → direct, typed-free function references).
 *
 * FLOW (parent → harness calls, all returning result objects, never
 * throwing across the realm boundary):
 *   bootVM({ interpreterJS, program, globalName, ytcfg })  → shadow-eval
 *   runSnapshot({ skipPrivacyBuffer? })                    → bytes + signal
 *   buildMinter(integrityTokenData)                        → WebPoMinter
 *   mint(contentBinding)                                   → websafe token
 */

import { BotGuardClient } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import type { WebPoSignalOutput } from 'bgutils-js/shared-types';

// ---------------------------------------------------------------------------
// The spoofed origin
// ---------------------------------------------------------------------------

const YT_ORIGIN = 'https://www.youtube.com';
const YT_URL = 'https://www.youtube.com/';

/**
 * The spoofed Location — a plain object with the full Location surface.
 * BotGuard reads origin/hostname/href; the methods are inert no-ops (the VM
 * never navigates, and a navigation inside the mint frame would kill the
 * session anyway).
 */
const spoofedLocation = {
  href: YT_URL,
  origin: YT_ORIGIN,
  protocol: 'https:',
  host: 'www.youtube.com',
  hostname: 'www.youtube.com',
  port: '',
  pathname: '/',
  search: '',
  hash: '',
  ancestorOrigins: {
    length: 0,
    contains: () => false,
    item: () => null,
  },
  assign: () => undefined,
  replace: () => undefined,
  reload: () => undefined,
  toString: () => YT_URL,
  [Symbol.toPrimitive]: (hint: string) =>
    hint === 'number' ? Number.NaN : YT_URL,
} as unknown as Location;

/**
 * Property passthrough with the two realm traps handled:
 *   • plain methods are BOUND to the real receiver (calling a native method
 *     with a Proxy `this` throws "Illegal invocation"),
 *   • constructors/classes keep their identity (`new` and `instanceof` must
 *     keep working — a bound function has no usable [[Construct]] and no
 *     own .prototype).
 */
function passthrough(target: object, prop: string | symbol): unknown {
  const value = Reflect.get(target, prop, target);
  if (typeof value === 'function') {
    const proto = (value as { prototype?: unknown }).prototype;
    return typeof proto === 'object' && proto !== null ? value : value.bind(target);
  }
  return value;
}

/** The spoofed document — URL/referrer/baseURI read as youtube.com. */
const spoofedDocument = new Proxy(document, {
  get(target, prop) {
    switch (prop) {
      case 'URL':
      case 'documentURI':
        return YT_URL;
      case 'baseURI':
        return YT_URL;
      case 'referrer':
        return '';
      case 'location':
        return spoofedLocation;
      case 'domain':
        return 'www.youtube.com';
      default:
        return passthrough(target, prop);
    }
  },
});

/** The spoofed window — the environment the interpreter must believe in. */
const spoofedWindow = new Proxy(window, {
  get(target, prop) {
    switch (prop) {
      case 'location':
        return spoofedLocation;
      case 'document':
        return spoofedDocument;
      case 'origin':
        return YT_ORIGIN;
      // youtube.com runs top-level: top/parent/self are all "the page".
      // (The REAL srcdoc iframe is nested inside the app — an honest
      // window.top !== window read would scream "framed foreign page".)
      case 'top':
      case 'parent':
      case 'self':
      case 'window':
      case 'globalThis':
      case 'frames':
        return spoofedWindow;
      case 'name':
        return '';
      default:
        return passthrough(target, prop);
    }
  },
  set(target, prop, value) {
    // The interpreter assigns window[globalName] (the VM) and window.yt —
    // let it land on the REAL window so same-realm lookups (ours and
    // BotGuardClient's) find it.
    return Reflect.set(target, prop, value, target);
  },
});

// ---------------------------------------------------------------------------
// Layer 1 — realm-wide origin patches (the indirect-eval killer)
// ---------------------------------------------------------------------------

/**
 * WHY PATCH THE PROTOTYPE when the proxies already spoof: the trayride
 * interpreter builds parts of itself through INDIRECT EVAL — `(0,eval)(…)`,
 * verified in the live 2026-11-14 interpreter — and indirect eval re-binds
 * `this`/globals to the REAL realm, bypassing every shadow parameter and
 * proxy. But the origin-telling DOCUMENT getters live on
 * `Document.prototype` as ordinary configurable accessors — patching them
 * HERE (the harness realm — this iframe only; the app's own document is a
 * different realm and stays untouched) covers EVERY access path: proxied,
 * shadowed, G-captured, and indirect-eval'd alike. `G.document.URL`,
 * `this.X1.URL`, bare `document.URL` — all read https://www.youtube.com/.
 *
 * `window.location` is [LegacyUnforgeable] (cannot be redefined on any
 * engine); the document.location own-property patch is attempted anyway —
 * where the engine allows it, even real-realm `document.location` reads
 * report the spoof.
 */
let originPatchesApplied = false;

function applyOriginPatches(): void {
  if (originPatchesApplied) return;
  const patches: Array<[Document, string, () => unknown]> = [
    [Document.prototype, 'URL', () => YT_URL],
    [Document.prototype, 'documentURI', () => YT_URL],
    [Document.prototype, 'baseURI', () => YT_URL],
    [Document.prototype, 'referrer', () => ''],
    [Document.prototype, 'domain', () => 'www.youtube.com'],
  ];
  for (const [proto, prop, getter] of patches) {
    try {
      Object.defineProperty(proto, prop, {
        get: getter,
        configurable: true,
        enumerable: true,
      });
    } catch {
      /* unpatchable surface — the proxy layers still cover the reads */
    }
  }
  try {
    Object.defineProperty(document, 'location', {
      get: () => spoofedLocation,
      configurable: true,
      enumerable: true,
    });
  } catch {
    /* expected where document.location is [LegacyUnforgeable] */
  }
  originPatchesApplied = true;
}

// ---------------------------------------------------------------------------
// Harness state
// ---------------------------------------------------------------------------

interface BootPayload {
  interpreterJS: string;
  program: string;
  globalName: string;
  /** Parsed ytcfg (BotGuard reads EVENT_ID from yt.config_). */
  ytcfg: Record<string, unknown>;
}

interface SnapshotResult {
  ok: boolean;
  error?: string;
  /** The snapshot bytes (base64-ish websafe string) for GenerateIT. */
  snapshot?: string;
  /** Whether webPoSignalOutput[0] got populated (the minter hook). */
  signalOutputReady: boolean;
}

interface MinterResult {
  ok: boolean;
  error?: string;
}

interface MintResult {
  ok: boolean;
  error?: string;
  token?: string;
}

let bgClient: BotGuardClient | null = null;
let webPoSignalOutput: WebPoSignalOutput = [];
let minter: WebPoMinter | null = null;
/** The stashed challenge (bootVM → runSnapshot's lazy BotGuardClient). */
let challenge: { program: string; globalName: string } | null = null;

// ---------------------------------------------------------------------------
// The API (exposed as window.__streamifyMintHarness)
// ---------------------------------------------------------------------------

/** Step 1 — evaluate the BotGuard interpreter under the spoofed origin. */
function bootVM(payload: BootPayload): MinterResult {
  try {
    // Layer 1 first: the realm-wide document patches (covers indirect-eval
    // paths the layers below cannot reach — see applyOriginPatches).
    applyOriginPatches();

    // yt.config_ (EVENT_ID) — on the REAL window; the proxy forwards reads.
    (window as unknown as Record<string, unknown>).yt = { config_: payload.ytcfg };

    // Layers 2+3: the shadow-parameter eval — the ONLY way unmodified
    // interpreter code can be made to read our spoofed location/document
    // through BARE identifiers (window.location itself is
    // [LegacyUnforgeable]; redefining it is impossible) — called WITH THE
    // SPOOFED WINDOW AS `this`: the interpreter's closure library binds
    // `G = this || self` and exposes the whole environment graph
    // (G.document, G.location, the VM global assignment `G.trayride = …`)
    // through that root, so the this-binding routes the G-path through the
    // spoof while the set-trap still lands the VM on the REAL window.
    const boot = new Function(
      'window',
      'document',
      'location',
      'top',
      'parent',
      'self',
      'globalThis',
      payload.interpreterJS,
    );
    boot.call(
      spoofedWindow,
      spoofedWindow,
      spoofedDocument,
      spoofedLocation,
      spoofedWindow,
      spoofedWindow,
      spoofedWindow,
      spoofedWindow,
    );

    const vm = (window as unknown as Record<string, unknown>)[payload.globalName];
    if (vm === undefined || vm === null) {
      return {
        ok: false,
        error: `interpreter did not define window.${payload.globalName}`,
      };
    }
    return { ok: true };
  } catch (err) {
    return { ok: false, error: describe(err) };
  }
}

/**
 * Step 2 — load the challenge program + take the snapshot.
 * `skipPrivacyBuffer: true` is the Brave-class retry (Brave's farbled
 * fingerprinting surfaces make the privacy-buffer path leave
 * webPoSignalOutput empty; the VM's built-in bypass trades some entropy
 * for a working minter).
 */
async function runSnapshot(opts: { skipPrivacyBuffer?: boolean } = {}): Promise<SnapshotResult> {
  try {
    if (bgClient === null) {
      if (challenge === null) {
        return { ok: false, signalOutputReady: false, error: 'runSnapshot before bootVM' };
      }
      // Lazy: the client is created on the FIRST snapshot (bootVM must have
      // run — the VM global must exist on the real window).
      bgClient = await BotGuardClient.create({
        program: challenge.program,
        globalName: challenge.globalName,
        globalObject: window,
      });
    }
    const signal: WebPoSignalOutput = [];
    // One signal-output array per attempt: a poisoned attempt-1 array must
    // not shadow a successful attempt-2 (the VM writes webPoSignalOutput[0]
    // itself). On success the array becomes the session's minter hook.
    const snapshot = await bgClient.snapshot({
      webPoSignalOutput: signal,
      skipPrivacyBuffer: opts.skipPrivacyBuffer === true,
    });
    const ready = typeof signal[0] === 'function';
    if (ready) {
      webPoSignalOutput = signal;
    }
    return { ok: true, snapshot, signalOutputReady: ready };
  } catch (err) {
    return { ok: false, signalOutputReady: false, error: describe(err) };
  }
}

/** Step 3 — build the WebPoMinter from the GenerateIT integrity token. */
async function buildMinter(data: {
  integrityToken: string;
  estimatedTtlSecs: number;
  mintRefreshThreshold: number;
  websafeFallbackToken: string;
}): Promise<MinterResult> {
  try {
    if (webPoSignalOutput.length === 0 || typeof webPoSignalOutput[0] !== 'function') {
      return { ok: false, error: 'no signal output (privacy buffer won)' };
    }
    minter = await WebPoMinter.create(data, webPoSignalOutput);
    return { ok: true };
  } catch (err) {
    return { ok: false, error: describe(err) };
  }
}

/** Step 4 — mint a token bound to the given content (videoId/visitorData). */
async function mint(contentBinding: string): Promise<MintResult> {
  if (minter === null) {
    return { ok: false, error: 'no minter (buildMinter failed or not called)' };
  }
  try {
    return { ok: true, token: await minter.mintAsWebsafeString(contentBinding) };
  } catch (err) {
    return { ok: false, error: describe(err) };
  }
}

/** Cross-realm error describe (iframe Error instances fail parent instanceof). */
function describe(err: unknown): string {
  if (err instanceof Error) return `${err.name}: ${err.message}`;
  return String(err);
}

// ---------------------------------------------------------------------------
// Export to the parent (same-origin srcdoc — direct references, no postMessage)
// ---------------------------------------------------------------------------

export interface MintHarnessApi {
  bootVM: (payload: BootPayload) => MinterResult;
  runSnapshot: (opts?: { skipPrivacyBuffer?: boolean }) => Promise<SnapshotResult>;
  buildMinter: (data: {
    integrityToken: string;
    estimatedTtlSecs: number;
    mintRefreshThreshold: number;
    websafeFallbackToken: string;
  }) => Promise<MinterResult>;
  mint: (contentBinding: string) => Promise<MintResult>;
  /**
   * Drop all module state for a re-mint (force path). The VM global itself
   * stays on the real window (interpreter globals are replace-on-define);
   * a fresh challenge brings a fresh globalName anyway.
   */
  reset: () => void;
  /** The spoof sanity probe (dev/diagnostics: what the VM would read). */
  environmentProbe: () => {
    origin: string;
    hostname: string;
    url: string;
    referrer: string;
    isTopLevel: boolean;
    sameRealmDocument: boolean;
    /** Layer-1 verification: the REAL (unproxied) document's URL getter. */
    realDocumentPatched: boolean;
    /** Whether the engine held the document.location own-property patch. */
    documentLocationPatchHeld: boolean;
  };
}

declare global {
  interface Window {
    __streamifyMintHarness?: MintHarnessApi;
  }
}

const api: MintHarnessApi = {
  bootVM: (payload) => {
    challenge = { program: payload.program, globalName: payload.globalName };
    return bootVM(payload);
  },
  runSnapshot,
  buildMinter,
  mint,
  reset: () => {
    bgClient = null;
    webPoSignalOutput = [];
    minter = null;
    challenge = null;
  },
  environmentProbe: () => ({
    origin: spoofedWindow.location.origin,
    hostname: spoofedWindow.location.hostname,
    url: spoofedDocument.URL,
    referrer: spoofedDocument.referrer,
    isTopLevel: spoofedWindow.top === spoofedWindow,
    sameRealmDocument: spoofedWindow.document === spoofedDocument,
    // Layer-1 verification: read the REAL document's URL getter (no proxy —
    // a true positive here means even indirect-eval paths see the spoof).
    realDocumentPatched: document.URL === YT_URL,
    documentLocationPatchHeld:
      (document as unknown as { location?: unknown }).location === spoofedLocation,
  }),
};

window.__streamifyMintHarness = api;
