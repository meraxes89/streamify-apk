/**
 * audio/demux/webmDemuxer.ts — incremental WebM/Matroska demuxer for
 * YouTube itag 251 (Opus / 160 kbps, the WebCodecs path's primary
 * format).
 *
 * WHAT IT PARSES (audio-only scope, per the P1 task book):
 *
 *   EBML header (DocType webm|matroska)
 *   Segment ─ Info      → TimestampScale (default 1 000 000 ns)
 *            ─ Tracks   → TrackEntry(TrackType=audio, CodecID=A_OPUS,
 *                                   CodecPrivate=OpusHead, CodecDelay)
 *            ─ Cluster* → Timestamp (absolute, scale ticks)
 *                        → SimpleBlock / BlockGroup→Block
 *
 * …and NOTHING else: video tracks are skipped, unknown elements are
 * skipped by size, SeekHead/Void/voids pass through. itag 251 is
 * audio-only, so in practice one A_OPUS track + clusters of
 * single-packet SimpleBlocks (the muxer YouTube uses writes one Opus
 * packet per block, lacing 0) — but ALL THREE lacing modes (Xiph,
 * Fixed, EBML) are implemented so foreign muxers demux too.
 *
 * EBML PRIMER (the two varint flavors everything hangs on):
 *   • Element ID: width from the first byte's leading-zero run
 *     (0x8→1B … 0x01→8B); the value KEEPS the marker bit
 *     (Cluster = 0x1F43B675, a 4-byte ID).
 *   • Element size: same width scheme, value DROPS the marker bit;
 *     all-ones payload = UNKNOWN size (streamed elements — Segment
 *     and sometimes Cluster use it). Unknown-size masters are handled
 *     structurally: a Segment ends at EOF; an unknown-size Cluster
 *     ends where the next Cluster's ID peeks.
 *
 * OPUSHEAD: the CodecPrivate payload IS the RFC 7845 identification
 * header ("OpusHead", version, channels, pre-skip, input rate, gain,
 * mapping family) and is passed VERBATIM as the WebCodecs
 * `description` — Chrome's opus decoder reads pre-skip from it, which
 * is why the demuxer does NOT massage timestamps for CodecDelay.
 *
 * TIMESTAMP MATH: absolute ticks = Cluster.Timestamp + i16 block
 * relative (scale ticks) → µs = ticks × TimestampScale / 1000.
 * YouTube's 1 ms scale keeps every product well under 2^53 — exact in
 * doubles.
 *
 * INCREMENTAL CONTRACT (types.ts): arbitrary misaligned chunks in,
 * packets out the moment their block is fully buffered. Packet `data`
 * is a fresh copy — transferable, never a view into scratch.
 */

import type {
  AudioDemuxer,
  AudioFormat,
  FormatSink,
  PacketSink,
} from '../types';
import { OPUS_OUTPUT_RATE } from '../types';

// ---------------------------------------------------------------------------
// EBML element IDs (marker-inclusive) — the only ones the demuxer names.
// ---------------------------------------------------------------------------
const ID_EBML = 0x1a45dfa3;
const ID_DOCTYPE = 0x4282;
const ID_SEGMENT = 0x18538067;
const ID_INFO = 0x1549a966;
const ID_TIMESTAMP_SCALE = 0x2ad7b1;
const ID_TRACKS = 0x1654ae6b;
const ID_TRACK_ENTRY = 0xae;
const ID_TRACK_NUMBER = 0xd7;
const ID_TRACK_TYPE = 0x83;
const ID_CODEC_ID = 0x86;
const ID_CODEC_PRIVATE = 0x63a2;
const ID_CODEC_DELAY = 0x56aa;
const ID_AUDIO = 0xe1;
const ID_CHANNELS = 0x9f;
const ID_CLUSTER = 0x1f43b675;
const ID_CLUSTER_TIMESTAMP = 0xe7;
const ID_SIMPLE_BLOCK = 0xa3;
const ID_BLOCK_GROUP = 0xa0;
const ID_BLOCK = 0xa1;
const ID_DISCARD_PADDING = 0x75a2;

/** Matroska default TimestampScale: 1 000 000 ns = 1 ms ticks. */
const DEFAULT_TIMESCALE_NS = 1_000_000;

// ---------------------------------------------------------------------------
// VINT primitives
// ---------------------------------------------------------------------------

/** Width (bytes) of a VINT from its first byte; 0 = invalid (0x00). */
function vintWidth(firstByte: number): number {
  if (firstByte >= 0x80) return 1;
  if (firstByte >= 0x40) return 2;
  if (firstByte >= 0x20) return 3;
  if (firstByte >= 0x10) return 4;
  if (firstByte >= 0x08) return 5;
  if (firstByte >= 0x04) return 6;
  if (firstByte >= 0x02) return 7;
  if (firstByte === 0x01) return 8;
  return 0;
}

/** True when every payload bit of the width-w size VINT is 1. */
function isUnknownSize(value: number, width: number): boolean {
  return value === 2 ** (7 * width) - 1;
}

/** A peeked element header at some cursor. */
interface ElementHead {
  /** Element ID (marker kept). */
  id: number;
  /** Content size in bytes (Infinity when unknown). */
  size: number;
  /** Absolute offset just past the header (content start). */
  contentStart: number;
  /** Absolute offset just past the element (Infinity when unknown). */
  end: number;
}

/**
 * Peek the element header at absolute `at`. Returns null when the
 * header bytes are not (yet) buffered; throws on an invalid lead byte.
 */
function peekElement(
  buf: Uint8Array,
  bufStart: number,
  at: number,
): ElementHead | null {
  const rel = at - bufStart;
  if (rel < 0 || rel >= buf.length) return null;
  const idWidth = vintWidth(buf[rel]);
  if (idWidth === 0) {
    throw new Error(`webm: invalid VINT lead byte 0x00 at ${at}`);
  }
  if (rel + idWidth > buf.length) return null;
  // ID: keep the marker bit, big-endian.
  let id = 0;
  for (let i = 0; i < idWidth; i++) id = id * 256 + buf[rel + i];
  const sizeAt = rel + idWidth;
  const sizeLead = buf[sizeAt];
  const sizeWidth = vintWidth(sizeLead);
  if (sizeWidth === 0) {
    throw new Error(`webm: invalid size VINT lead 0x00 at ${at + idWidth}`);
  }
  if (sizeAt + sizeWidth > buf.length) return null;
  // Size: drop the marker bit, big-endian.
  let size = sizeLead & (0xff >> sizeWidth);
  for (let i = 1; i < sizeWidth; i++) size = size * 256 + buf[sizeAt + i];
  const unknown = isUnknownSize(size, sizeWidth);
  const contentStart = at + idWidth + sizeWidth;
  return {
    id,
    size: unknown ? Number.POSITIVE_INFINITY : size,
    contentStart,
    end: unknown ? Number.POSITIVE_INFINITY : contentStart + size,
  };
}

/** Unsigned integer element value from complete content bytes. */
function readUint(b: Uint8Array): number {
  let v = 0;
  for (let i = 0; i < b.length && i < 6; i++) v = v * 256 + b[i];
  return v;
}

/** ASCII/UTF-8 string element. */
function readString(b: Uint8Array): string {
  return new TextDecoder().decode(b);
}

/**
 * Block-payload VINT (marker dropped): returns [value, nextOffset].
 * Used for the track number and EBML lacing sizes.
 */
function readBlockVint(p: Uint8Array, at: number): [number, number] | null {
  if (at >= p.length) return null;
  const w = vintWidth(p[at]);
  if (w === 0 || at + w > p.length) return null;
  let v = p[at] & (0xff >> w);
  for (let i = 1; i < w; i++) v = v * 256 + p[at + i];
  return [v, at + w];
}

// ---------------------------------------------------------------------------
// OpusHead + TOC
// ---------------------------------------------------------------------------

/** Parsed RFC 7845 identification header. */
export interface OpusHead {
  channels: number;
  preSkip: number;
  inputSampleRate: number;
  outputGain: number;
  mappingFamily: number;
}

/** Parse/validate an OpusHead; throws with the precise reason. */
export function parseOpusHead(b: Uint8Array): OpusHead {
  const magic = 'OpusHead';
  if (b.length < 19) throw new Error(`webm: OpusHead short (${b.length}B)`);
  for (let i = 0; i < magic.length; i++) {
    if (b[i] !== magic.charCodeAt(i)) {
      throw new Error('webm: CodecPrivate is not an OpusHead');
    }
  }
  const version = b[8];
  if (version > 15) throw new Error(`webm: OpusHead version ${version}`);
  const channels = b[9];
  if (channels < 1 || channels > 2) {
    // Family 0 allows 1-2; multichannel (family 1+) is out of scope.
    throw new Error(`webm: OpusHead channels ${channels} out of scope`);
  }
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength);
  return {
    channels,
    preSkip: v.getUint16(10, true),
    inputSampleRate: v.getUint32(12, true),
    outputGain: v.getInt16(16, true),
    mappingFamily: b[18],
  };
}

/** Synthesize a minimal OpusHead when the muxer omitted CodecPrivate. */
function synthesizeOpusHead(channels: number): Uint8Array {
  const b = new Uint8Array(19);
  const magic = 'OpusHead';
  for (let i = 0; i < magic.length; i++) b[i] = magic.charCodeAt(i);
  b[8] = 1; // version
  b[9] = channels < 1 ? 2 : channels;
  // preSkip 0, inputSampleRate 48000 LE, gain 0, family 0
  const v = new DataView(b.buffer);
  v.setUint32(12, OPUS_OUTPUT_RATE, true);
  return b;
}

/** SILK-only frame sizes (ms) by config & 3 — see opusPacketDurationUs. */
const SILK_CONFIG_MS = [10, 20, 40, 60];

/**
 * RFC 6716 §3.1 frame size from the TOC config — the libopus
 * `opus_packet_get_samples_per_frame` formula (verified against a live
 * itag 251 stream: YouTube music packets are TOC 0xFC = config 31,
 * CELT FB 20 ms):
 *
 *   config 16-31 (CELT):    2.5 ms << (config & 3)   → 2.5/5/10/20
 *   config 12-15 (hybrid):  20 ms when (config & 1) else 10 ms
 *   config  0-11 (SILK):    [10, 20, 40, 60][config & 3]
 *
 * Packet duration = frame size × frame count (code 0 → 1 frame,
 * code 1/2 → 2, code 3 → count byte; exotic multi-byte counts bail
 * with undefined — informational only, never correctness).
 */
export function opusPacketDurationUs(packet: Uint8Array): number | undefined {
  if (packet.length < 1) return undefined;
  const config = (packet[0] >> 3) & 0x1f;
  const code = packet[0] & 0x03;
  let ms: number;
  if (config >= 16) {
    ms = 2.5 * (1 << (config & 3));
  } else if (config >= 12) {
    ms = (config & 1) === 1 ? 20 : 10;
  } else {
    ms = SILK_CONFIG_MS[config & 3];
  }
  let frames: number;
  switch (code) {
    case 0:
      frames = 1;
      break;
    case 1:
    case 2:
      frames = 2;
      break;
    case 3:
      if (packet.length < 2) return undefined;
      const countByte = packet[1];
      if (countByte > 250) return undefined; // multi-byte count — bail
      frames = countByte + 1;
      break;
    default:
      return undefined;
  }
  return frames * ms * 1000;
}

// ---------------------------------------------------------------------------
// The demuxer
// ---------------------------------------------------------------------------

/** Parsed audio track facts. */
interface OpusTrack {
  trackNumber: number;
  head: OpusHead;
  /** Raw OpusHead bytes (the WebCodecs description). */
  description: Uint8Array;
  codecDelayNs: number;
}

type Phase =
  | 'ebml' // hunting/consuming the EBML header element
  | 'segment' // walking Segment children (Info/Tracks/…)
  | 'cluster'; // walking Cluster children (Timestamp/Blocks)

export class WebmDemuxer implements AudioDemuxer {
  private readonly onPacket: PacketSink;
  private readonly onFormat: FormatSink | null;
  private _format: AudioFormat | null = null;
  private _ended = false;

  /** Rolling window: buffer[0] is absolute file offset fileStart. */
  private buffer = new Uint8Array(0);
  private fileStart = 0;
  /** Absolute parse cursor (never behind fileStart). */
  private cursor = 0;

  private phase: Phase = 'ebml';
  private docTypeChecked = false;
  private timescaleNs = DEFAULT_TIMESCALE_NS;
  private track: OpusTrack | null = null;
  /** Known-size Segment content end (null = unknown size → EOF). */
  private segmentEnd: number | null = null;
  /** Current cluster absolute Timestamp (ticks); null until read. */
  private clusterTsTicks: number | null = null;
  /** Known-size Cluster content end (null = unknown size). */
  private clusterEnd: number | null = null;

  constructor(onPacket: PacketSink, onFormat?: FormatSink) {
    this.onPacket = onPacket;
    this.onFormat = onFormat ?? null;
  }

  get format(): AudioFormat | null {
    return this._format;
  }
  get ended(): boolean {
    return this._ended;
  }

  push(chunk: Uint8Array): void {
    if (this._ended || chunk.length === 0) return;
    const merged = new Uint8Array(this.buffer.length + chunk.length);
    merged.set(this.buffer, 0);
    merged.set(chunk, this.buffer.length);
    this.buffer = merged;
    this.parse();
  }

  endOfStream(): void {
    this._ended = true;
    // Packets stream out eagerly; there is no pending tail state to
    // flush — a partial element simply never completes.
  }

  private bufferedEnd(): number {
    return this.fileStart + this.buffer.length;
  }

  /** Contiguous view of [a, b) or null when incomplete/compacted. */
  private view(a: number, b: number): Uint8Array | null {
    if (a < this.fileStart || b > this.bufferedEnd()) return null;
    return this.buffer.subarray(a - this.fileStart, b - this.fileStart);
  }

  private advance(end: number): void {
    this.cursor = end;
    this.compactTo(end);
  }

  /**
   * Drop buffered bytes before `abs`, clamped to the received end —
   * push() appends sequentially, so fileStart must always name the
   * true absolute position of buffer[0] (skipElement may target an
   * offset beyond the buffer; the clamp keeps the invariant).
   */
  private compactTo(abs: number): void {
    const target = Math.min(abs, this.bufferedEnd());
    const rel = target - this.fileStart;
    if (rel <= 0) return;
    if (rel >= this.buffer.length) {
      this.buffer = new Uint8Array(0);
    } else {
      this.buffer = this.buffer.subarray(rel);
    }
    this.fileStart += rel;
  }

  /** Main loop: consume as many complete elements as buffering allows. */
  private parse(): void {
    for (let guard = 0; guard < 100_000; guard++) {
      const head = this.peek();
      if (head === null) return; // header incomplete — wait for bytes

      switch (this.phase) {
        case 'ebml': {
          if (head.id === ID_EBML) {
            const content = this.view(head.contentStart, head.end);
            if (content === null) return; // wait for the whole header
            this.checkDocType(content);
            this.advance(head.end);
            this.phase = 'segment';
            break;
          }
          // Not an EBML header: not a (Matroska) WebM at all.
          throw new Error(`webm: expected EBML header, got id 0x${head.id.toString(16)}`);
        }

        case 'segment': {
          // A Cluster switches phases; its header is re-peeked below.
          if (head.id === ID_CLUSTER) {
            this.openCluster(head);
            break;
          }
          if (head.id === ID_SEGMENT) {
            // Nested/second Segment — pathological; skip if sized.
            if (head.size === Number.POSITIVE_INFINITY) {
              throw new Error('webm: unknown-size nested Segment');
            }
            this.segmentEnd = head.end;
            this.advance(head.contentStart);
            break;
          }
          // Known-size Segment content exhausted?
          if (this.segmentEnd !== null && this.cursor >= this.segmentEnd) {
            // Trailing bytes after the Segment — treat as done.
            this.advance(this.bufferedEnd());
            return;
          }
          if (head.id === ID_INFO) {
            const content = this.view(head.contentStart, head.end);
            if (content === null) return;
            this.parseInfo(content);
            this.advance(head.end);
            break;
          }
          if (head.id === ID_TRACKS) {
            const content = this.view(head.contentStart, head.end);
            if (content === null) return;
            this.parseTracks(content);
            if (this.track === null) {
              throw new Error('webm: no A_OPUS audio track found');
            }
            this.publishFormat();
            this.advance(head.end);
            break;
          }
          // SeekHead / Void / Chapters / …: skip by size.
          this.skipElement(head);
          break;
        }

        case 'cluster': {
          // Known-size cluster exhausted?
          if (this.clusterEnd !== null && this.cursor >= this.clusterEnd) {
            this.phase = 'segment';
            break;
          }
          // Unknown-size cluster: the next Cluster element ends it.
          if (head.id === ID_CLUSTER) {
            if (this.clusterEnd === null) {
              this.openCluster(head);
              break;
            }
            // Known-size cluster starting before clusterEnd?? Corrupt
            // walk — treat as a new cluster anyway (defensive).
            this.openCluster(head);
            break;
          }
          if (head.id === ID_CLUSTER_TIMESTAMP) {
            const content = this.view(head.contentStart, head.end);
            if (content === null) return;
            this.clusterTsTicks = readUint(content);
            this.advance(head.end);
            break;
          }
          if (head.id === ID_SIMPLE_BLOCK) {
            const content = this.view(head.contentStart, head.end);
            if (content === null) return;
            this.parseBlock(content, false);
            this.advance(head.end);
            break;
          }
          if (head.id === ID_BLOCK_GROUP) {
            const content = this.view(head.contentStart, head.end);
            if (content === null) return;
            this.parseBlockGroup(content);
            this.advance(head.end);
            break;
          }
          // Position / PrevSize / SilentTracks / …: skip by size.
          this.skipElement(head);
          break;
        }
      }
    }
    throw new Error('webm: parse loop guard tripped (element storm)');
  }

  /** Peek at the cursor (null when the header is not fully buffered). */
  private peek(): ElementHead | null {
    return peekElement(this.buffer, this.fileStart, this.cursor);
  }

  /** Skip an element without needing its content buffered. */
  private skipElement(head: ElementHead): void {
    if (head.size === Number.POSITIVE_INFINITY) {
      // Unknown-size skip is only legal at Cluster level, where the
      // next Cluster's ID terminates it — i.e. nowhere skippable.
      throw new Error(
        `webm: unknown-size element 0x${head.id.toString(16)} cannot be skipped`,
      );
    }
    this.advance(head.end);
  }

  /** Enter cluster mode from a Cluster element header. */
  private openCluster(head: ElementHead): void {
    if (head.size === Number.POSITIVE_INFINITY) {
      this.clusterEnd = null;
    } else {
      this.clusterEnd = head.end;
    }
    this.clusterTsTicks = null;
    this.phase = 'cluster';
    this.advance(head.contentStart);
  }

  /** EBML header → DocType check (webm/matroska both accepted). */
  private checkDocType(content: Uint8Array): void {
    if (this.docTypeChecked) return;
    this.docTypeChecked = true;
    let at = 0;
    while (at < content.length) {
      const head = peekElement(content, 0, at);
      if (head === null) break;
      if (head.id === ID_DOCTYPE) {
        const dt = readString(content.subarray(head.contentStart, head.end));
        if (dt !== 'webm' && dt !== 'matroska') {
          throw new Error(`webm: DocType "${dt}" is not webm/matroska`);
        }
        return;
      }
      at = head.end;
    }
    throw new Error('webm: EBML header has no DocType');
  }

  /** Info → TimestampScale (ns per tick). */
  private parseInfo(content: Uint8Array): void {
    let at = 0;
    while (at < content.length) {
      const head = peekElement(content, 0, at);
      if (head === null) break;
      if (head.id === ID_TIMESTAMP_SCALE && head.size <= 8) {
        this.timescaleNs = readUint(content.subarray(head.contentStart, head.end));
      }
      at = head.end;
    }
  }

  /** Tracks → the first A_OPUS audio TrackEntry. */
  private parseTracks(content: Uint8Array): void {
    let at = 0;
    while (at < content.length && this.track === null) {
      const head = peekElement(content, 0, at);
      if (head === null) break;
      if (head.id === ID_TRACK_ENTRY) {
        this.parseTrackEntry(content.subarray(head.contentStart, head.end));
      }
      at = head.end;
    }
  }

  /** One TrackEntry → OpusTrack when it is the audio/Opus one. */
  private parseTrackEntry(entry: Uint8Array): void {
    let at = 0;
    let trackNumber: number | null = null;
    let trackType = -1;
    let codecId = '';
    let codecPrivate: Uint8Array | null = null;
    let codecDelayNs = 0;
    let channels = 2;

    while (at < entry.length) {
      const head = peekElement(entry, 0, at);
      if (head === null) break;
      const body =
        head.size === Number.POSITIVE_INFINITY
          ? null
          : entry.subarray(head.contentStart, head.end);
      switch (head.id) {
        case ID_TRACK_NUMBER:
          if (body !== null) trackNumber = readUint(body);
          break;
        case ID_TRACK_TYPE:
          if (body !== null) trackType = readUint(body);
          break;
        case ID_CODEC_ID:
          if (body !== null) codecId = readString(body);
          break;
        case ID_CODEC_PRIVATE:
          if (body !== null) codecPrivate = body.slice();
          break;
        case ID_CODEC_DELAY:
          if (body !== null) codecDelayNs = readUint(body);
          break;
        case ID_AUDIO: {
          if (body === null) break;
          let q = 0;
          while (q < body.length) {
            const a = peekElement(body, 0, q);
            if (a === null) break;
            if (a.id === ID_CHANNELS) {
              channels = readUint(body.subarray(a.contentStart, a.end));
            }
            q = a.end;
          }
          break;
        }
      }
      at = head.end;
    }

    if (trackType !== 2 || codecId !== 'A_OPUS') return; // not our track
    if (trackNumber === null) return;
    let headBytes = codecPrivate;
    let parsed: OpusHead;
    if (headBytes !== null && headBytes.length >= 19) {
      parsed = parseOpusHead(headBytes);
    } else {
      // CodecPrivate missing/short: synthesize (defensive — the WebM
      // Opus mapping REQUIRES it, so this is muxer damage control).
      headBytes = synthesizeOpusHead(channels);
      parsed = parseOpusHead(headBytes);
    }
    this.track = {
      trackNumber,
      head: parsed,
      description: headBytes,
      codecDelayNs,
    };
  }

  /** Publish the format once, after Tracks parsed. */
  private publishFormat(): void {
    if (this._format !== null || this.track === null) return;
    this._format = {
      codec: 'opus',
      description: this.track.description,
      sampleRate: OPUS_OUTPUT_RATE,
      channels: this.track.head.channels,
      codecDelayNs: this.track.codecDelayNs,
      preSkip: this.track.head.preSkip,
    };
    this.onFormat?.(this._format);
  }

  /** BlockGroup → Block (+ DiscardPadding) — small, fully buffered. */
  private parseBlockGroup(content: Uint8Array): void {
    let at = 0;
    let block: Uint8Array | null = null;
    let discardNs = 0;
    while (at < content.length) {
      const head = peekElement(content, 0, at);
      if (head === null) break;
      if (head.id === ID_BLOCK) {
        block = content.subarray(head.contentStart, head.end);
      } else if (head.id === ID_DISCARD_PADDING) {
        // sInt — but padding magnitudes fit the unsigned read.
        discardNs = readUint(content.subarray(head.contentStart, head.end));
      }
      at = head.end;
    }
    if (block !== null) this.parseBlock(block, true, discardNs);
  }

  /**
   * SimpleBlock / Block payload → packets.
   *
   *   track VINT | i16 rel ticks (BE) | flags | [lace] frames…
   *
   * flags: 0x80 keyframe (SimpleBlock only); bits 1-2 lacing mode.
   */
  private parseBlock(p: Uint8Array, fromBlockGroup: boolean, discardNs = 0): void {
    if (this.track === null || this.clusterTsTicks === null) {
      // Blocks before the cluster Timestamp (or before Tracks, in a
      // pathological stream) have no timeline — drop, per spec.
      return;
    }
    const tv = readBlockVint(p, 0);
    if (tv === null) throw new Error('webm: block track VINT truncated');
    const [trackNumber, afterTrack] = tv;
    if (trackNumber !== this.track.trackNumber) return; // not audio
    if (afterTrack + 3 > p.length) throw new Error('webm: block header truncated');
    const relRaw = (p[afterTrack] << 8) | p[afterTrack + 1];
    const relTicks = (relRaw << 16) >> 16; // sign-extend i16
    const flags = p[afterTrack + 2];
    const lacing = (flags >> 1) & 0x03;
    const keyframe = (flags & 0x80) !== 0 && !fromBlockGroup;

    let at = afterTrack + 3;
    const frames: Uint8Array[] = [];
    if (lacing === 0) {
      frames.push(p.subarray(at));
    } else {
      if (at >= p.length) throw new Error('webm: lace count byte truncated');
      const frameCount = p[at] + 1;
      at += 1;
      const payloadLen = p.length - at;
      if (frameCount < 2 || payloadLen <= 0) {
        throw new Error('webm: bad lace frame count');
      }
      if (lacing === 1) {
        // Xiph: N-1 NUL-terminated (255-continuation) sizes; last =
        // remainder.
        const sizes: number[] = [];
        for (let f = 0; f < frameCount - 1; f++) {
          let size = 0;
          for (;;) {
            if (at >= p.length) throw new Error('webm: xiph lace truncated');
            const v = p[at++];
            if (v === 255) {
              size += 255;
            } else {
              size += v;
              break;
            }
          }
          sizes.push(size);
        }
        let off = at;
        for (let f = 0; f < frameCount - 1; f++) {
          frames.push(p.subarray(off, off + sizes[f]));
          off += sizes[f];
        }
        frames.push(p.subarray(off)); // last: the remainder
      } else if (lacing === 2) {
        // Fixed: equal sizes; any remainder joins the last frame.
        const per = Math.floor(payloadLen / frameCount);
        for (let f = 0; f < frameCount; f++) {
          const off = at + f * per;
          const end =
            f === frameCount - 1
              ? p.length
              : off + per; // remainder rides the last frame
          frames.push(p.subarray(off, end));
        }
      } else {
        // EBML: frame 0 size = unbiased VINT; sizes 1..N-2 = biased
        // signed deltas (value - (2^(7w-1) - 1)); last = remainder.
        const sizes: number[] = [];
        let prev: number | null = null;
        for (let f = 0; f < frameCount - 1; f++) {
          const r = readBlockVint(p, at);
          if (r === null) throw new Error('webm: ebml lace truncated');
          const [v, next] = r;
          const width = next - at;
          at = next;
          if (prev === null) {
            prev = v;
          } else {
            prev = prev + (v - (2 ** (7 * width - 1) - 1));
          }
          if (prev < 0) throw new Error('webm: ebml lace negative size');
          sizes.push(prev);
        }
        let off = at;
        for (let f = 0; f < frameCount - 1; f++) {
          frames.push(p.subarray(off, off + sizes[f]));
          off += sizes[f];
        }
        frames.push(p.subarray(off));
      }
    }

    const absTicks = this.clusterTsTicks + relTicks;
    let tsUs = Math.round((absTicks * this.timescaleNs) / 1000);
    for (const frame of frames) {
      if (frame.length === 0) continue; // zero-size lace frame: skip
      this.onPacket({
        data: frame.slice(), // fresh copy — transferable
        timestampUs: tsUs,
        durationUs: opusPacketDurationUs(frame),
        keyframe,
        ...(discardNs !== 0 ? { discardPaddingNs: discardNs } : {}),
      });
      // Frames laced into ONE block share its block timestamp; advance
      // each subsequent frame by its own duration when derivable (the
      // decoder needs distinct, monotonic packet timestamps).
      const dur = opusPacketDurationUs(frame);
      tsUs += dur !== undefined ? dur : 0;
    }
  }
}
