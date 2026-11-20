#!/usr/bin/env node
// Offline-path simulation of scripts/sovereign_hal_browser_harness.html:
// runs the EXACT tone generation, worklet pull, and FFT-verification logic
// (extracted verbatim from the harness) under Node, proving the offline
// path's signal chain passes before a CI cycle is spent.
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(
  path.join(__dirname, "sovereign_hal_browser_harness.html"),
  "utf8",
);
const m = /<script>([\s\S]*)<\/script>/.exec(html);
let js = m[1]
  .replace(/\$\{CHANNELS\}/g, "2")
  .replace(/\$\{QUANTUM\}/g, "128");

// Execute the harness's pure-function block (constants, tone generation,
// FFT) in this scope by slicing from the constants to the end of
// fftPeakCheck, evaluating it as a module body.
const constStart = js.indexOf("const RATE");
const fftEnd = js.indexOf("const sleep =");
const pure = js.slice(constStart, fftEnd);
const mod = new Function(pure + "\nreturn { toneChunk, fftPeakCheck, RATE, CHANNELS, QUANTUM, TOTAL_FRAMES, EOS_TAIL_EVENTS, FREQ_A, FREQ_B };")();
const { toneChunk, fftPeakCheck, RATE, CHANNELS, QUANTUM, TOTAL_FRAMES, EOS_TAIL_EVENTS } = mod;

let failures = 0;
const check = (ok, msg) => { console.log((ok ? "  PASS " : "  FAIL ") + msg); if (!ok) failures++; };

// ── the worklet pull logic, verbatim semantics — per-channel outputs ───────
// (the AudioWorklet spec shape: outputs[0] = [ch0, ch1], each renderQuantum)
function sabPull(state, channels) {
  const head = state.head[0];
  let tail = state.tail[0];
  const have = (head - tail) >>> 0;
  if (have === 0) { state.underruns++; return false; }
  const take = Math.min(QUANTUM, have);
  const startIdx = ((tail & state.mask) * CHANNELS) >>> 0;
  const buf = state.frame;
  for (let i = 0; i < take * CHANNELS; i++) {
    const idx = (startIdx + i) % state.samples.length;
    buf[i] = state.samples[idx];
  }
  state.tail[0] = (tail + take) >>> 0;
  state.framesServed += take;
  if (take < QUANTUM) state.underruns++;
  deinterleave(buf, take, channels);
  return take === QUANTUM;
}

function portPull(state, channels) {
  let need = QUANTUM * CHANNELS;
  let written = 0;
  const buf = state.frame;
  while (need > 0) {
    if (!state.pending) {
      state.pending = state.queue.shift() || null;
      state.pendingOffset = 0;
      if (!state.pending) break;
    }
    const copy = Math.min(need, state.pending.length - state.pendingOffset);
    buf.set(state.pending.subarray(state.pendingOffset, state.pendingOffset + copy), written);
    state.pendingOffset += copy; written += copy; need -= copy;
    if (state.pendingOffset >= state.pending.length) state.pending = null;
  }
  const got = (written / CHANNELS) | 0;
  state.framesServed += got;
  if (need > 0) { state.underruns++; }
  deinterleave(buf, got, channels);
  return got === QUANTUM;
}

function deinterleave(buf, frames, channels) {
  for (let c = 0; c < channels.length; c++) {
    const ch = channels[c];
    for (let f = 0; f < frames && f < ch.length; f++) {
      ch[f] = buf[f * CHANNELS + (c % CHANNELS)];
    }
  }
}

// Render one mode: TOTAL_FRAMES frames through QUANTUM-sized pulls, then FFT.
function render(pullFn, state) {
  const out = new Float32Array(TOTAL_FRAMES * CHANNELS);
  let served = 0;
  while (served < TOTAL_FRAMES) {
    // The spec output shape: CHANNELS arrays of renderQuantum each.
    const channels = [new Float32Array(QUANTUM), new Float32Array(QUANTUM)];
    pullFn(state, channels);
    const take = Math.min(QUANTUM, TOTAL_FRAMES - served);
    for (let f = 0; f < take; f++) {
      out[(served + f) * CHANNELS] = channels[0][f];
      out[(served + f) * CHANNELS + 1] = channels[1][f];
    }
    served += take;
  }
  return out;
}

// ── Mode SAB: pre-filled ring (the offline path's transport) ───────────────
{
  const CAPACITY = 131072;
  const buf = new ArrayBuffer(64 + CAPACITY * CHANNELS * 4);
  const head = new Uint32Array(buf, 0, 1);
  const tail = new Uint32Array(buf, 4, 1);
  const samples = new Float32Array(buf, 64, CAPACITY * CHANNELS);
  samples.set(toneChunk(0, TOTAL_FRAMES));
  head[0] = TOTAL_FRAMES;
  const state = { head, tail, samples, mask: CAPACITY - 1, underruns: 0, framesServed: 0, frame: new Float32Array(QUANTUM * CHANNELS) };
  const rendered = render(sabPull, state);
  const left = new Float32Array(TOTAL_FRAMES);
  for (let i = 0; i < TOTAL_FRAMES; i++) left[i] = rendered[i * CHANNELS];
  const fft = fftPeakCheck(left, RATE, 440, 880);
  check(fft.ok, `SAB offline: FFT tones (440Hz mag ${fft.aMag.toFixed(1)}, 880Hz mag ${fft.bMag.toFixed(1)}, median ${fft.median.toFixed(4)})`);
  check(state.underruns <= EOS_TAIL_EVENTS, `SAB offline: underruns ${state.underruns} <= ${EOS_TAIL_EVENTS}`);
  check(state.framesServed === TOTAL_FRAMES, `SAB offline: frames ${state.framesServed} === ${TOTAL_FRAMES}`);
}

// ── Mode port: pre-seeded chunk queue (the offline path's transport) ───────
{
  const chunks = [];
  for (let f = 0; f < TOTAL_FRAMES; f += 12000) {
    chunks.push(toneChunk(f, Math.min(12000, TOTAL_FRAMES - f)));
  }
  const state = { queue: chunks, pending: null, pendingOffset: 0, underruns: 0, framesServed: 0, frame: new Float32Array(QUANTUM * CHANNELS) };
  const rendered = render(portPull, state);
  const left = new Float32Array(TOTAL_FRAMES);
  for (let i = 0; i < TOTAL_FRAMES; i++) left[i] = rendered[i * CHANNELS];
  const fft = fftPeakCheck(left, RATE, 440, 880);
  check(fft.ok, `port offline: FFT tones (440Hz mag ${fft.aMag.toFixed(1)}, 880Hz mag ${fft.bMag.toFixed(1)}, median ${fft.median.toFixed(4)})`);
  check(state.underruns <= EOS_TAIL_EVENTS, `port offline: underruns ${state.underruns} <= ${EOS_TAIL_EVENTS}`);
  check(state.framesServed === TOTAL_FRAMES, `port offline: frames ${state.framesServed} === ${TOTAL_FRAMES}`);
}

// ── byte-exactness between the two transports (same samples, same order) ──
{
  const CAPACITY = 131072;
  const buf = new ArrayBuffer(64 + CAPACITY * CHANNELS * 4);
  const head = new Uint32Array(buf, 0, 1), tail = new Uint32Array(buf, 4, 1);
  const samples = new Float32Array(buf, 64, CAPACITY * CHANNELS);
  samples.set(toneChunk(0, TOTAL_FRAMES));
  head[0] = TOTAL_FRAMES;
  const sabRendered = render(sabPull, { head, tail, samples, mask: CAPACITY - 1, underruns: 0, framesServed: 0, frame: new Float32Array(QUANTUM * CHANNELS) });

  const chunks = [];
  for (let f = 0; f < TOTAL_FRAMES; f += 12000) chunks.push(toneChunk(f, Math.min(12000, TOTAL_FRAMES - f)));
  const portRendered = render(portPull, { queue: chunks, pending: null, pendingOffset: 0, underruns: 0, framesServed: 0, frame: new Float32Array(QUANTUM * CHANNELS) });

  let equal = sabRendered.length === portRendered.length;
  for (let i = 0; equal && i < sabRendered.length; i++) equal = sabRendered[i] === portRendered[i];
  check(equal, "both transports render byte-identical audio (transport-agnostic dataplane)");
}

// ── silence negative control (the verifier must not be vacuous) ────────────
{
  const silence = new Float32Array(TOTAL_FRAMES);
  const fft = fftPeakCheck(silence, RATE, 440, 880);
  check(!fft.ok, "silence control: FFT verdict fails on digital silence (non-vacuous)");
}

console.log(failures === 0 ? "browser harness offline simulation: ALL PASS" : `browser harness simulation: ${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);
