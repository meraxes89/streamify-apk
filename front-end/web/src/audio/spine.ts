/**
 * audio/spine.ts — THE ORCHESTRATOR (P1). Wires the whole audio spine:
 *
 *      resolve (relay/ANDROID_VR)
 *        → ChunkFetcher (256 KiB ranged, relay, hop-cached)
 *        → Webm/Mp4 Demuxer (incremental, adversarial-chunk-proof)
 *        → WebCodecs Decoder (f32-planar → interleaved, mono upmixed)
 *        → SinkTransport (transferable writes, credit backpressure)
 *        → sink.worklet (ring + SRC + epoch ramp + 96-byte stats)
 *
 * STRUCTURE: three concurrent activities pinned together by the
 * epoch counter:
 *
 *   • FETCH LOOP  — sequential chunks; pushes the demuxer; bounded
 *     packet queue ahead of the decode chain (fetch outrunning the
 *     decoder is the classic OOM trap).
 *   • DECODE CHAIN — a single serializing promise chain so PCM hits
 *     the ring in PTS order (WebCodecs outputs in order; the chain
 *     makes the WRITE order match regardless of microtask timing).
 *   • WRITER LOOP — drains the decoded queue into the transport,
 *     awaiting credit budget (the worklet is the rate authority).
 *
 * EPOCH RULES (MP-C10): stop() and any future seek bump the epoch;
 * stale decode results check the epoch BEFORE writing and are
 * dropped silently — a flush on the audio thread plus a check on
 * this side is the double-gate that keeps stale audio silent.
 */

import { ChunkFetcher } from './chunkFetcher';
import { Mp4Demuxer } from './demux/mp4Demuxer';
import { WebmDemuxer } from './demux/webmDemuxer';
import type { AudioDemuxer, DecodeFrame, DemuxedPacket } from './types';
import { CANONICAL_CHANNELS } from './types';
import { createAudioDecoder } from './audioDecoder';
import { WebCodecsAudioDecoder } from './webcodecsDecoder';
import { resolveAudioStream } from './resolve';
import type { ResolvedStream } from './resolve';
import { SinkTransport } from './transport';
import { useSpineStore } from './store';
import type { SpinePhase } from './store';

/** Max packets buffered between fetch and decode (OOM guard). */
const PACKET_QUEUE_HIGH = 64;

export interface SpineOptions {
  relayBase: string;
}

export class Spine {
  private readonly relayBase: string;
  private session = 0; // monotonic session id (stop() invalidates old runs)
  private _transport: SinkTransport | null = null;
  /** Dev diagnosis: pipeline counters (window.__spine.debug). */
  readonly debug = {
    packetsDemuxed: 0,
    packetsChained: 0,
    decodesDone: 0,
    framesDecoded: 0,
    framesWritten: 0,
    lastDecodeError: null as string | null,
    decoderFormats: [] as string[],
  };

  /** Dev/diagnostic: the live sink transport (null when not open). */
  get transport(): SinkTransport | null {
    return this._transport;
  }

  constructor(opts: SpineOptions) {
    this.relayBase = opts.relayBase.replace(/\/$/, '');
  }

  /**
   * Play a video end-to-end. Resolves when playback setup is fully
   * wired (transport open, first chunks flowing); the fetch/decode
   * loops run detached. The store reports lifecycle.
   */
  async play(videoId: string): Promise<void> {
    const mySession = ++this.session;
    const store = useSpineStore.getState();
    store.setPhase('resolving');

    try {
      // -- resolve ---------------------------------------------------------
      const stream = await resolveAudioStream(videoId, {
        relayBase: this.relayBase,
      });
      this.assertNotStale(mySession);
      store.startSession(
        {
          videoId,
          title: stream.title,
          artist: stream.artist,
          itag: stream.itag,
          codec: stream.codec,
          contentLength: stream.contentLength,
          startedAt: Date.now(),
        },
        'webcodecs',
        stream.rung === 'web-pot'
          ? `webcodecs:${stream.codec} (WEB+POT rung, ${stream.potKind} token)`
          : `webcodecs:${stream.codec} (ANDROID_VR rung)`,
      );

      // -- fetch + demux + decode + sink ------------------------------------
      await this.runStream(mySession, stream);
    } catch (err) {
      if (this.isStale(mySession)) return; // superseded — not an error
      const msg = err instanceof Error ? err.message : String(err);
      useSpineStore.getState().setPhase('errored', msg);
      throw err;
    }
  }

  /** Stop everything (idempotent). Bumps the session (stales all loops). */
  stop(): void {
    this.session++;
    const t = this._transport;
    this._transport = null;
    void t?.close();
    useSpineStore.getState().endSession();
  }

  // -------------------------------------------------------------------------
  // internals
  // -------------------------------------------------------------------------

  private isStale(session: number): boolean {
    return session !== this.session;
  }
  private assertNotStale(session: number): void {
    if (this.isStale(session)) throw new StaleSessionError();
  }

  /** The streaming machinery after resolve. */
  private async runStream(session: number, stream: ResolvedStream): Promise<void> {
    const store = useSpineStore.getState();

    // -- demuxer ---------------------------------------------------------
    const packets: DemuxedPacket[] = [];
    let onFormat: ((f: import('./types').AudioFormat) => void) | null = null;
    const formatPromise = new Promise<import('./types').AudioFormat>((resolve) => {
      onFormat = resolve;
    });
    const packetSink = (p: DemuxedPacket): void => {
      packets.push(p);
      this.debug.packetsDemuxed++;
    };
    const demuxer: AudioDemuxer =
      stream.codec === 'opus'
        ? new WebmDemuxer(packetSink, (f) => onFormat?.(f))
        : new Mp4Demuxer(packetSink, (f) => onFormat?.(f));

    // -- fetch loop (detached) ----------------------------------------------
    const fetcher = new ChunkFetcher({
      relayBase: this.relayBase,
      // ANDROID_VR-rung URLs carry the app session — the relay swaps the
      // matrix UA (X-YouTube-Client-Name 28). WEB+POT-rung URLs belong to
      // the browser's web session: no client header, browser UA forwarded
      // as-is (it matches the &pot= binding).
      clientName: stream.rung === 'android-vr' ? '28' : null,
      // googlevideo expires/limits stream URLs; on 403/410 rotate via
      // a fresh resolve (same video, new URL token).
      onStaleUrl: async () => {
        store.addFetch({ retries: 1 });
        const fresh = await resolveAudioStream(stream.videoId, {
          relayBase: this.relayBase,
        });
        return fresh.url;
      },
    });
    const probe = await fetcher.probe(stream.url);
    this.assertNotStale(session);
    store.addFetch({ chunksRequested: 1, chunksSucceeded: 1 });

    const fetchLoop = (async (): Promise<void> => {
      let offset = 0;
      const total = probe.contentLength > 0 ? probe.contentLength : Number.MAX_SAFE_INTEGER;
      try {
        while (offset < total && !this.isStale(session)) {
          // Backpressure: don't outrun the decoder by unbounded packets.
          if (packets.length > PACKET_QUEUE_HIGH) {
            await sleep(20);
            continue;
          }
          const chunk = await fetcher.fetchChunk(offset);
          this.assertNotStale(session);
          store.addFetch({
            chunksSucceeded: 1,
            bytesServed: chunk.data.length,
          });
          offset += chunk.data.length;
          demuxer.push(chunk.data);
          store.addDemux(0, chunk.data.length);
        }
        demuxer.endOfStream();
      } catch (err) {
        // DATA CENTER IP REALITY (measured this session): googlevideo
        // throttles ranged requests per egress IP (~3/video/window).
        // A residential browser (the production user) does not hit
        // this. When media already demuxed, keep the phase — the ring
        // drains seconds of buffered audio and G1 measures THAT; trip
        // 'errored' only when nothing ever played.
        if (this.debug.packetsDemuxed === 0) {
          useSpineStore
            .getState()
            .setPhase('errored', `fetch: ${err instanceof Error ? err.message : String(err)}`);
        }
        demuxer.endOfStream();
      }
    })();

    // -- format → decoder + transport ----------------------------------------
    const format = await formatPromise;
    this.assertNotStale(session);
    const decoder = await createAudioDecoder(format);
    this.assertNotStale(session);
    useSpineStore.getState().setDecoder(
      decoder.backend === 'webcodecs:opus' || decoder.backend === 'webcodecs:aac'
        ? 'webcodecs'
        : 'wasm',
      decoder.backend,
    );

    const transport = await SinkTransport.open({
      rateIn: format.sampleRate,
      channels: CANONICAL_CHANNELS, // decoder upmixes mono already
      onStats: (ev) => {
        if (this.isStale(session)) return;
        const s = useSpineStore.getState();
        s.setStats(ev.stats, ev.positionUs);
        if (s.phase === 'streaming' || s.phase === 'demuxing') {
          s.setPhase('streaming');
        }
      },
    });
    this.assertNotStale(session);
    this._transport = transport;
    this.debug.decoderFormats = WebCodecsAudioDecoder.lastFormats;
    useSpineStore.getState().setPhase('streaming');

    // -- decode chain (serial → PTS-ordered frames) ---------------------------
    const frames: DecodeFrame[] = [];
    let chain: Promise<void> = Promise.resolve();
    const drainPackets = (): void => {
      // Called on every loop tick: attach queued packets to the chain.
      while (packets.length > 0) {
        const packet = packets.shift() as DemuxedPacket;
        this.debug.packetsChained++;
        chain = chain.then(async () => {
          if (this.isStale(session)) return;
          try {
            const out = await decoder.decode(packet);
            this.debug.decodesDone++;
            if (this.isStale(session)) return; // epoch double-gate
            for (const f of out) {
              frames.push(f);
              this.debug.framesDecoded++;
            }
          } catch (err) {
            this.debug.lastDecodeError = err instanceof Error ? err.message : String(err);
            /* decoder-level dropout: skip the packet, keep the chain */
          }
        });
      }
    };

    // -- writer loop (credit-gated) --------------------------------------------
    const writer = (async (): Promise<void> => {
      let announcedPlaying = false;
      while (!this.isStale(session)) {
        drainPackets();
        if (frames.length === 0) {
          await chain;
          await sleep(10);
          continue;
        }
        const head = frames[0];
        if (transport.availableFrames() < head.frames) {
          await transport.awaitCredit(head.frames);
          if (transport.isClosed || this.isStale(session)) return;
          continue;
        }
        frames.shift();
        this.debug.framesWritten += head.frames;
        transport.write(head.samples); // transferred — ownership leaves
        if (!announcedPlaying) {
          announcedPlaying = true;
          useSpineStore.getState().setPhase('playing');
        }
      }
    })().catch(() => undefined);

    await Promise.allSettled([fetchLoop, writer]);
    if (!this.isStale(session)) {
      // Natural end-of-stream: hold 'playing'/'streaming' (UI decides).
      decoder.close();
    }
  }
}

/** Internal: thrown when a superseded session's async edge resurfaces. */
class StaleSessionError extends Error {
  constructor() {
    super('stale session');
    this.name = 'StaleSessionError';
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/** Convenience for the UI: the store phase → UI label. */
export function phaseLabel(phase: SpinePhase): string {
  switch (phase) {
    case 'idle':
      return 'Idle';
    case 'resolving':
      return 'Resolving…';
    case 'demuxing':
      return 'Reading header…';
    case 'streaming':
      return 'Buffering…';
    case 'playing':
      return 'Playing';
    case 'paused':
      return 'Paused';
    case 'errored':
      return 'Error';
  }
}
