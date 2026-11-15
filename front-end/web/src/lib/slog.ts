/**
 * lib/slog.ts — the web SLog (util/SLog.kt port, W-W home-teardown).
 *
 * Application-wide logging facade with OPT-IN diagnostic capture, same
 * contract as the APK:
 *
 *   OFF (default): slog.* is one boolean read — zero allocation, zero
 *   disk, nothing. The app pays nothing until capture is armed.
 *
 *   ON (the Diagnostic Terminal / /debug tail arms it; auto-off after
 *   2h): frames land in an in-memory ring (the APK uses a 4MB off-heap
 *   DirectByteBuffer; the web lowering is a capped frame array — same
 *   semantics, GC-friendly size), formatted + redacted at READ time.
 *
 * LINE FORMAT (fixed, the viewers depend on it):
 *   "MM-dd HH:mm:ss.SSS L/tag: message"
 *    └── 18 chars ──┘^   ^    ^
 *           ts      lvl  tag  message
 * The level char sits at LEVEL_INDEX (19). (The APK's AdminTerminalScreen
 * filtered at index 20 — an off-by-two against SLog.formatLine; the port
 * uses the shared constant on both sides so it cannot drift.)
 *
 * REDACTION (read-time, same patterns as SLog.redact): SAPISIDHASH,
 * Cookie headers, signature= params — the log tail must never leak creds.
 */

export type SLogLevel = 'V' | 'D' | 'I' | 'W' | 'E' | 'F';

/** Where the level char lives in a formatted line (see header). */
export const LEVEL_INDEX = 19;

const CAPACITY_FRAMES = 4_000; // ~4 MB of short lines, the APK ring's class
const MAX_TAG = 23;
const MAX_MSG = 900;
const AUTO_OFF_MS = 2 * 60 * 60 * 1000; // the 2h self-disarm
const ENABLED_KEY = 'streamify.slog.enabled';

interface SLogFrame {
  ts: number;
  lvl: SLogLevel;
  tag: string;
  msg: string;
}

let captureEnabled = false;
let armedUntilMs = 0;
let ring: SLogFrame[] = [];
let version = 0; // bumped on every append (cheap change detection)

// Read-time redaction (precompiled, applied in formatLine only).
const RX_SAPISID = /(SAPISIDHASH\s)[A-Za-z0-9_]+/;
const RX_COOKIE = /(cookie:\s*?)[^\n]{8,}/i;
const RX_SIG = /((?:signature|sig|s)=)[^&\s"']+/i;

export function redact(msg: string): string {
  if (!msg.includes('SAPISID') && !/cookie/i.test(msg) && !msg.includes('signature=')) {
    return msg;
  }
  return msg.replace(RX_SAPISID, '$1<redacted>').replace(RX_COOKIE, '$1<redacted>').replace(RX_SIG, '$1<redacted>');
}

function pad(n: number, w: number): string {
  return String(n).padStart(w, '0');
}

function fmtTs(ts: number): string {
  const d = new Date(ts);
  return (
    `${pad(d.getMonth() + 1, 2)}-${pad(d.getDate(), 2)} ` +
    `${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}:${pad(d.getSeconds(), 2)}.${pad(d.getMilliseconds(), 3)}`
  );
}

function formatLine(f: SLogFrame): string {
  return `${fmtTs(f.ts)} ${f.lvl}/${f.tag}: ${redact(f.msg)}`;
}

function append(lvl: SLogLevel, tag: string, msg: string): void {
  if (!captureEnabled) return; // OFF: this is the entire cost.
  ring.push({ ts: Date.now(), lvl, tag: tag.slice(0, MAX_TAG), msg: msg.slice(0, MAX_MSG) });
  if (ring.length > CAPACITY_FRAMES) ring.splice(0, ring.length - CAPACITY_FRAMES);
  version += 1;
  maybeAutoOff();
}

function maybeAutoOff(): void {
  if (captureEnabled && armedUntilMs > 0 && Date.now() >= armedUntilMs) {
    // Same wording class as the APK's auto-shutoff line.
    appendLocal('W', 'SLog', 'auto-shutoff after 2h — disarming capture');
    setCaptureEnabled(false);
  }
}

function appendLocal(lvl: SLogLevel, tag: string, msg: string): void {
  ring.push({ ts: Date.now(), lvl, tag, msg });
  if (ring.length > CAPACITY_FRAMES) ring.splice(0, ring.length - CAPACITY_FRAMES);
  version += 1;
}

// ---------------------------------------------------------------------------
// Public API — mirrors android.util.Log's shape (SLog's drop-in facade).
// ---------------------------------------------------------------------------

export const slog = {
  v: (tag: string, msg: string): void => append('V', tag, msg),
  d: (tag: string, msg: string): void => append('D', tag, msg),
  i: (tag: string, msg: string): void => append('I', tag, msg),
  w: (tag: string, msg: string): void => append('W', tag, msg),
  e: (tag: string, msg: string): void => append('E', tag, msg),
  /** What-a-terrible-failure — the APK's wtf lane. */
  wtf: (tag: string, msg: string): void => append('F', tag, msg),
};

/** SLog.st — contextual stack capture. */
export function slogThrow(tag: string, context: string, err: unknown): void {
  const name = err instanceof Error ? err.name : typeof err;
  const message = err instanceof Error ? err.message : String(err);
  append('W', tag, `${context} — ${name}: ${message}`);
}

// ---------------------------------------------------------------------------
// Capture lifecycle
// ---------------------------------------------------------------------------

/** Monotonic change counter — viewers poll it to skip no-op renders. */
export function slogVersion(): number {
  return version;
}

export function captureState(): boolean {
  maybeAutoOff();
  return captureEnabled;
}

export function remainingCaptureMs(): number {
  if (!captureEnabled || armedUntilMs <= 0) return 0;
  return Math.max(0, armedUntilMs - Date.now());
}

export function setCaptureEnabled(enable: boolean, persist = true): void {
  if (enable === captureEnabled) return;
  if (enable) {
    captureEnabled = true;
    armedUntilMs = Date.now() + AUTO_OFF_MS;
    if (persist) {
      try {
        localStorage.setItem(ENABLED_KEY, 'on');
      } catch {
        /* private mode */
      }
    }
    appendLocal('I', 'SLog', 'capture ARMED — auto-off in 2h');
  } else {
    captureEnabled = false;
    armedUntilMs = 0;
    if (persist) {
      try {
        localStorage.removeItem(ENABLED_KEY);
      } catch {
        /* private mode */
      }
    }
    // The APK wipes the ring on disarm; the web keeps the tail readable
    // until the next arm (the /debug tail stays useful post-mortem) but
    // stops appending. The DISARMED marker makes the boundary visible.
    appendLocal('I', 'SLog', 'capture DISARMED');
    version += 1;
  }
}

/** Up to `max` most recent frames as formatted, redacted lines. */
export function snapshotLines(max = 2000): string[] {
  maybeAutoOff();
  const start = Math.max(0, ring.length - max);
  return ring.slice(start).map(formatLine);
}

/** Entire ring, formatted + redacted — Copy/Download exports. */
export function exportAll(): string {
  return `${snapshotLines(CAPACITY_FRAMES).join('\n')}\n`;
}

export function clearBuffer(): void {
  ring = [];
  version += 1;
}

// ---------------------------------------------------------------------------
// Boot — mirrors SLog.initialize: restore intent, banner, crash hook.
// Called once from AppShell's boot effect (page lifetime).
// ---------------------------------------------------------------------------

let booted = false;

export function initSlog(versionLabel: string): void {
  if (booted) return;
  booted = true;
  // Restore user intent across sessions; each session arms a fresh 2h window.
  let wanted = false;
  try {
    wanted = localStorage.getItem(ENABLED_KEY) === 'on';
  } catch {
    wanted = false;
  }
  if (wanted) setCaptureEnabled(true, false);
  appendLocal('I', 'SLog', `SLog ready — capture=${wanted ? 'on' : 'off'} (opt-in)`);
  appendLocal('I', 'SLog', `boot version=${versionLabel} ua=${navigator.userAgent.slice(0, MAX_MSG - 40)}`);

  // installCrashHook: uncaught errors + rejections land in the ring while
  // armed (the soak diagnostic that costs nothing until then).
  window.addEventListener('error', (e) => {
    append('F', 'window', `${e.message} @ ${e.filename}:${e.lineno}:${e.colno}`);
  });
  window.addEventListener('unhandledrejection', (e) => {
    const r = e.reason;
    append('F', 'promise', r instanceof Error ? `${r.name}: ${r.message}` : String(r));
  });
}
