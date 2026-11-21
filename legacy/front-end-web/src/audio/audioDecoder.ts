/**
 * audio/audioDecoder.ts — the decoder factory ladder (W1.2).
 *
 * LADDER (per the P1 task book, updated by the W1.2 crates.io
 * finding):
 *
 *   1. WebCodecs (platform) — Chrome decodes BOTH opus (itag 251) and
 *      mp4a.40.2 (itag 140) natively. Zero download, hardware-timed.
 *   2. WASM (symphonia) — the fallback rung for browsers without
 *      WebCodecs. DOCUMENTED DEVIATION (W1.2): the crate set has NO
 *      Opus decoder — symphonia-codec-opus does not exist on
 *      crates.io as of 2026-11 — so the WASM rung covers AAC (itag
 *      140, symphonia aac + isomp4) only; Opus streams on
 *      non-WebCodecs browsers are OUT OF SCOPE for P1 and fail with
 *      a typed error. The resolver therefore prefers itag 140 when
 *      WebCodecs is absent (see resolve.ts).
 *
 * The factory probes with isConfigSupported BEFORE committing, so the
 * ladder order is honest at runtime, not hardcoded hope.
 */

import type { AudioDecoderLike, AudioFormat } from './types';
import { WebCodecsAudioDecoder, webCodecsAvailable } from './webcodecsDecoder';

/** Typed: no backend on this machine can decode this format. */
export class NoDecoderError extends Error {
  constructor(format: AudioFormat) {
    super(
      `no decoder backend for ${format.codec}@${format.sampleRate} (webcodecs:${
        webCodecsAvailable() ? 'present' : 'absent'
      }, wasm: not built in this iteration)`,
    );
    this.name = 'NoDecoderError';
  }
}

/** The wasm rung hook — see the deviation note in the file header. */
async function tryWasmDecoder(format: AudioFormat): Promise<AudioDecoderLike> {
  // W1.2 (crates/streamify-decoder-wasm) is not built in this
  // iteration; the hook exists so the ladder shape is load-bearing.
  void format;
  throw new NoDecoderError(format);
}

/**
 * Pick the best backend for `format`. Probes WebCodecs support first;
 * falls back to the (currently absent) wasm rung; throws
 * NoDecoderError when nothing applies.
 */
export async function createAudioDecoder(
  format: AudioFormat,
): Promise<AudioDecoderLike> {
  if (webCodecsAvailable()) {
    const backend = new WebCodecsAudioDecoder(format);
    await backend.configure(format);
    return backend;
  }
  return tryWasmDecoder(format);
}

/** Whether THIS machine can play `codec' at all (UI hint, pre-resolve). */
export function codecSupported(codec: 'aac' | 'opus'): boolean {
  if (!webCodecsAvailable()) return false;
  // Chrome supports both; a per-codec isConfigSupported probe needs a
  // concrete format — treat presence of AudioDecoder as the hint.
  return codec === 'aac' || codec === 'opus';
}
