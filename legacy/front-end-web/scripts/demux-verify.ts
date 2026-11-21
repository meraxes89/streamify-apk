/**
 * scripts/demux-verify.ts — REAL-STREAM demuxer verification (W1 gate
 * evidence, runs offline of CI in Node/Bun against live YouTube).
 *
 *   bun scripts/demux-verify.ts [videoId]
 *
 * What it does:
 *   1. ANDROID_VR innertube player call (the resolver's best direct-
 *      URL rung) → picks itag 251 (webm/opus) and 140 (m4a/aac).
 *   2. Range-fetches the first ~768 KiB of each stream.
 *   3. Feeds the demuxers in RANDOM-SIZED chunks (7B..96 KiB, seeded
 *      RNG — the incremental/misalignment contract is the adversarial
 *      case under test; aligned 256 KiB pushes would never exercise
 *      the partial-element paths).
 *   4. Gates:
 *        webm — OpusHead published + parsed, ≥500 packets, µs strictly
 *               monotonic, ≥90% carry TOC-derived durations, first
 *               cluster keyframe observed, bytes accounted.
 *        mp4  — ASC published (≥2B), ≥500 samples, µs monotonic with
 *               deltas on the 1024-frame AAC grid (21.3–23.2 ms),
 *               sample sizes in the 129 kbps band.
 *
 * Exit code 0 = both gates green. Network failures exit 2 (this
 * sandbox's datacenter IP can be bot-walled on some videos — that is
 * an ENVIRONMENT result, not a demux result; the script says which).
 */

import { WebmDemuxer, parseOpusHead } from '../src/audio/demux/webmDemuxer';
import { Mp4Demuxer } from '../src/audio/demux/mp4Demuxer';
import type { AudioFormat, DemuxedPacket } from '../src/audio/types';

// ---------------------------------------------------------------------------
// Innertube ladder — ANDROID_VR (clientName 28), the verified direct-
// URL rung (P0 canary finding: WEB/MWEB need visitorData, ANDROID_VR
// serves googlevideo URLs without signature work from datacenter IPs
// for non-licensed tracks).
// ---------------------------------------------------------------------------
// Keyless www.youtube.com innertube (repo pattern — see resolve.ts).
const PLAYER_URL = 'https://www.youtube.com/youtubei/v1/player';
const VR_CLIENT = {
  clientName: 'ANDROID_VR',
  clientVersion: '1.65.10',
  androidSdkVersion: 32,
  osName: 'Android',
  osVersion: '12L',
  hl: 'en',
};
const VR_UA =
  'com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip';

interface AdaptiveFormat {
  itag: number;
  url?: string;
  mimeType?: string;
  bitrate?: number;
  contentLength?: string;
}

interface PlayerResponse {
  playabilityStatus?: { status?: string; reason?: string };
  videoDetails?: { title?: string; author?: string; lengthSeconds?: string };
  streamingData?: { adaptiveFormats?: AdaptiveFormat[] };
}

async function resolveVideo(videoId: string): Promise<PlayerResponse> {
  const res = await fetch(PLAYER_URL, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'user-agent': VR_UA,
      'x-youtube-client-name': '28',
      'x-youtube-client-version': VR_CLIENT.clientVersion,
      'x-origin': 'https://www.youtube.com',
    },
    body: JSON.stringify({
      context: { client: VR_CLIENT },
      videoId,
      contentCheckOk: true,
      racyCheckOk: true,
    }),
  });
  if (!res.ok) {
    throw new Error(`player ${res.status}: ${(await res.text()).slice(0, 200)}`);
  }
  return (await res.json()) as PlayerResponse;
}

// ---------------------------------------------------------------------------
// Range fetch (Node/Bun — redirects auto-followed, ranges honored).
// ---------------------------------------------------------------------------
async function fetchPrefix(url: string, bytes: number): Promise<Uint8Array> {
  const res = await fetch(url, {
    headers: { range: `bytes=0-${bytes - 1}`, 'user-agent': VR_UA },
  });
  if (!res.ok && res.status !== 206) {
    throw new Error(`range fetch ${res.status}`);
  }
  return new Uint8Array(await res.arrayBuffer());
}

// ---------------------------------------------------------------------------
// Seeded RNG (mulberry32) — reproducible adversarial chunking.
// ---------------------------------------------------------------------------
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function chunkify(data: Uint8Array, rand: () => number): Uint8Array[] {
  const chunks: Uint8Array[] = [];
  let at = 0;
  while (at < data.length) {
    // 7 B .. 96 KiB, biased small to hammer the partial-header paths.
    const size = Math.floor(7 + rand() * rand() * 96 * 1024);
    chunks.push(data.subarray(at, Math.min(at + size, data.length)));
    at += size;
  }
  return chunks;
}

// ---------------------------------------------------------------------------
// Gates
// ---------------------------------------------------------------------------
interface RunResult {
  packets: DemuxedPacket[];
  format: AudioFormat | null;
  bytesFed: number;
}

function runDemuxer(
  // deno-lint-ignore no-explicit-any
  Ctor: new (sink: (p: DemuxedPacket) => void, fmt?: (f: AudioFormat) => void) => any,
  data: Uint8Array,
): RunResult {
  const packets: DemuxedPacket[] = [];
  let format: AudioFormat | null = null;
  const demuxer = new Ctor(
    (p) => packets.push(p),
    (f) => (format = f),
  );
  for (const chunk of chunkify(data, mulberry32(0x51ab))) {
    demuxer.push(chunk);
  }
  demuxer.endOfStream();
  return { packets, format, bytesFed: data.length };
}

function fmtMs(us: number): string {
  return `${(us / 1e6).toFixed(2)}s`;
}

function verifyWebm(run: RunResult, label: string): void {
  const fails: string[] = [];
  const f = run.format;
  if (f === null) fail('format never published');
  else {
    const head = parseOpusHead(f.description);
    log(
      `  OpusHead: v1 ch=${head.channels} preSkip=${head.preSkip} inRate=${head.inputSampleRate} gain=${head.outputGain} fam=${head.mappingFamily}`,
    );
    if (f.codec !== 'opus') fail(`codec ${f.codec}`);
    if (head.channels < 1 || head.channels > 2) fail(`channels ${head.channels}`);
    if (f.sampleRate !== 48000) fail(`sampleRate ${f.sampleRate}`);
  }
  if (run.packets.length < 500) fail(`only ${run.packets.length} packets`);
  let mono = true;
  let durKnown = 0;
  let keyframes = 0;
  let bytes = 0;
  let prevTs = -1;
  for (const p of run.packets) {
    if (p.timestampUs <= prevTs) {
      mono = false;
      break;
    }
    prevTs = p.timestampUs;
    if (p.durationUs !== undefined) durKnown++;
    if (p.keyframe) keyframes++;
    bytes += p.data.length;
  }
  if (!mono) fail('timestamps not strictly monotonic');
  if (durKnown / Math.max(1, run.packets.length) < 0.9) {
    fail(`durations on ${(100 * durKnown) / Math.max(1, run.packets.length)}% only`);
  }
  if (keyframes < 1) fail('no keyframe packets (cluster resync never seen)');
  if (bytes > run.bytesFed) fail('packet bytes exceed fed bytes (copy bug)');
  log(
    `  packets=${run.packets.length} keyframes=${keyframes} durationKnown=${durKnown} ts=[${fmtMs(run.packets[0].timestampUs)} … ${fmtMs(prevTs)}] bytesOut=${bytes}`,
  );
  if (fails.length > 0) throw new Error(`[webm/${label}] ${fails.join('; ')}`);
  pass(`webm/${label}`);
}

function verifyMp4(run: RunResult, label: string): void {
  const fails: string[] = [];
  const f = run.format;
  if (f === null) fail('format never published');
  else {
    log(`  ASC: ${f.description.length}B hex=${hex(f.description)}`);
    if (f.codec !== 'aac') fail(`codec ${f.codec}`);
    if (f.description.length < 2) fail('ASC shorter than 2 bytes');
  }
  if (run.packets.length < 500) fail(`only ${run.packets.length} samples`);
  let prevTs = -1;
  let prevSizeSum = 0;
  let deltaMin = Number.POSITIVE_INFINITY;
  let deltaMax = 0;
  let bytes = 0;
  for (const p of run.packets) {
    if (prevTs >= 0) {
      const d = p.timestampUs - prevTs;
      deltaMin = Math.min(deltaMin, d);
      deltaMax = Math.max(deltaMax, d);
    }
    prevTs = p.timestampUs;
    bytes += p.data.length;
  }
  // AAC-LC: 1024 frames per sample → 21.33 ms (48k) or 23.22 ms (44.1k).
  if (deltaMin < 21000 || deltaMax > 24000) {
    fail(`sample deltas ${deltaMin}..${deltaMax}µs off the 1024-frame grid`);
  }
  const avgSize = bytes / Math.max(1, run.packets.length);
  if (avgSize < 100 || avgSize > 900) fail(`avg sample size ${avgSize}B outside band`);
  if (bytes > run.bytesFed) fail('packet bytes exceed fed bytes (copy bug)');
  prevSizeSum = bytes;
  log(
    `  samples=${run.packets.length} avg=${avgSize.toFixed(0)}B delta=[${deltaMin.toFixed(0)}…${deltaMax.toFixed(0)}]µs ts≤${fmtMs(prevTs)} bytesOut=${prevSizeSum}`,
  );
  if (fails.length > 0) throw new Error(`[mp4/${label}] ${fails.join('; ')}`);
  pass(`mp4/${label}`);
}

function hex(b: Uint8Array): string {
  return Array.from(b)
    .map((x) => x.toString(16).padStart(2, '0'))
    .join('');
}
function log(s: string): void {
  console.log(s);
}
function pass(name: string): void {
  console.log(`  PASS ${name}`);
}
function fail(msg: string): void {
  throw new Error(msg);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
const VIDEO_ID = process.argv[2] ?? 'dQw4w9WgXcQ';
const PREFIX_BYTES = 768 * 1024;

async function main(): Promise<void> {
  log(`== demux-verify: ${VIDEO_ID} ==`);
  log('resolving via ANDROID_VR…');
  const player = await resolveVideo(VIDEO_ID);
  const status = player.playabilityStatus?.status ?? 'UNKNOWN';
  log(`  playability=${status} title="${player.videoDetails?.title ?? '?'}"`);
  if (status !== 'OK') {
    console.error(`ENVIRONMENT: playability ${status} — ${player.playabilityStatus?.reason ?? ''}`);
    process.exit(2);
  }
  const formats = player.streamingData?.adaptiveFormats ?? [];
  const pick = (itag: number): AdaptiveFormat | undefined =>
    formats.find((f) => f.itag === itag && typeof f.url === 'string');
  const webm = pick(251);
  const mp4 = pick(140);
  if (webm === undefined || mp4 === undefined) {
    console.error(
      `ENVIRONMENT: missing formats (251:${webm !== undefined} 140:${mp4 !== undefined}) — video has no audio itags?`,
    );
    process.exit(2);
  }

  // -- WebM / Opus -------------------------------------------------------
  log(`itag 251: ${webm.mimeType} ~${webm.bitrate}bps`);
  const webmBytes = await fetchPrefix(webm.url as string, PREFIX_BYTES);
  log(`  fetched ${webmBytes.length}B prefix`);
  const webmRun = runDemuxer(WebmDemuxer, webmBytes);
  verifyWebm(webmRun, 'itag251');

  // -- MP4 / AAC ---------------------------------------------------------
  log(`itag 140: ${mp4.mimeType} ~${mp4.bitrate}bps`);
  const mp4Bytes = await fetchPrefix(mp4.url as string, PREFIX_BYTES);
  log(`  fetched ${mp4Bytes.length}B prefix`);
  const mp4Run = runDemuxer(Mp4Demuxer, mp4Bytes);
  verifyMp4(mp4Run, 'itag140');

  log('== ALL DEMUX GATES GREEN ==');
}

main().catch((err: unknown) => {
  console.error(`FAIL: ${err instanceof Error ? err.message : String(err)}`);
  process.exit(1);
});
