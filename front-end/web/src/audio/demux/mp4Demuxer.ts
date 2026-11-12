/**
 * audio/demux/mp4Demuxer.ts — incremental MPEG-4 demuxer for YouTube
 * itag 140 (m4a / AAC-LC, W1.2's primary WASM-ladder format).
 *
 * REALITY CHECK (verified against live itag 140 bytes, 2026-11):
 * YouTube's current itag 140 is a FRAGMENTED mp4 —
 *
 *      ftyp · moov(mvex→trex, empty stts/stsc/stco/stsz) · sidx
 *      · (moof → mdat)*          ≈160 KiB per fragment
 *
 * so the sample tables live per-fragment (`moof → traf → tfhd/tfdt/
 * trun`), NOT in the head moov. The demuxer therefore runs BOTH
 * layouts:
 *
 *   • fMP4 (YouTube): moov publishes the STATIC facts (timescale,
 *     track id, ASC from stsd, channels, rate — mvex flags
 *     fragmentation); each moof then appends its samples (tfdt base
 *     time + trun sizes/durations, byte offsets from the moof base +
 *     trun data_offset); mdat is skipped opaquely while the drain
 *     cursor consumes sample bytes as they arrive.
 *   • flat faststart (fallback for foreign sources): stts/stsc/stsz/
 *     stco are flattened once at moov time; mdat before moov is
 *     rejected loudly.
 *
 * INCREMENTAL CONTRACT (types.ts): arbitrary misaligned chunks in,
 * packets out the moment their bytes are contiguous. Packet `data` is
 * a fresh copy — transferable across MessagePorts.
 */

import type {
  AudioDemuxer,
  AudioFormat,
  FormatSink,
  PacketSink,
} from '../types';

/** A flattened media sample: absolute file byte range + timing. */
interface Mp4Sample {
  offset: number;
  size: number;
  /** Presentation timestamp, microseconds. */
  ptsUs: number;
  /** Duration, microseconds (1024 frames for AAC-LC). */
  durUs: number;
  keyframe: boolean;
}

/** Static facts from the moov's soun trak. */
interface TrackInfo {
  trackId: number;
  timescale: number;
  asc: Uint8Array;
  channels: number;
  sampleRate: number;
}

/** trex defaults (fragmented mode). */
interface TrexDefaults {
  duration: number;
  size: number;
}

// ---------------------------------------------------------------------------
// BE readers over a complete contiguous buffer
// ---------------------------------------------------------------------------

function beU32(b: Uint8Array, at: number): number {
  return (
    ((b[at] << 24) | (b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3]) >>> 0
  );
}
function beU64(b: Uint8Array, at: number): number {
  return beU32(b, at) * 2 ** 32 + beU32(b, at + 4);
}
function beU16(b: Uint8Array, at: number): number {
  return (b[at] << 8) | b[at + 1];
}
function fourcc(b: Uint8Array, at: number): string {
  return String.fromCharCode(b[at], b[at + 1], b[at + 2], b[at + 3]);
}

/** One parsed box: type + buffer-relative content range. */
interface Box {
  type: string;
  start: number; // content start (relative to the buffer it lives in)
  size: number; // content size
}

/** Iterate complete boxes in [start, end); malformed stops the walk. */
function* iterBoxes(buf: Uint8Array, start: number, end: number): Generator<Box> {
  let at = start;
  while (at + 8 <= end) {
    const size0 = beU32(buf, at);
    const type = fourcc(buf, at + 4);
    let headerSize = 8;
    let size = size0;
    if (size0 === 1) {
      if (at + 16 > end) return;
      size = beU64(buf, at + 8);
      headerSize = 16;
    } else if (size0 === 0) {
      return; // extends-to-EOF — never inside a parsed parent
    }
    if (size < 8) return; // corrupt
    const contentStart = at + headerSize;
    const contentSize = size - headerSize;
    if (contentStart + contentSize > end) return; // not whole
    yield { type, start: contentStart, size: contentSize };
    at = contentStart + contentSize;
  }
}

/** FullBox flags helper (version + flags live in the first 4 bytes). */
function boxFlags(b: Uint8Array, contentStart: number): { version: number; flags: number } {
  return { version: b[contentStart], flags: beU32(b, contentStart) & 0x00ffffff };
}

/** Parse an MPEG descriptor length (0x80-continuation encoding). */
function descriptorLength(b: Uint8Array, at: number): { len: number; next: number } {
  let len = 0;
  let p = at;
  for (;;) {
    const v = b[p++];
    len = (len << 7) | (v & 0x7f);
    if ((v & 0x80) === 0) break;
  }
  return { len, next: p };
}

/** Walk esds → DecoderConfigDescriptor → DecoderSpecificInfo → ASC. */
function parseEsds(p: Uint8Array): Uint8Array | null {
  let at = 4; // FullBox version+flags
  if (p[at] !== 0x03) return null; // ES_Descriptor tag
  const es = descriptorLength(p, at + 1);
  at = es.next;
  at += 2; // ES_ID u16
  const flags = p[at++];
  if (flags & 0x01) at += 2; // streamDependence: dependOn ES_ID
  if (flags & 0x02) at += 1 + p[at]; // URL_Flag: len + bytes
  if (flags & 0x04) at += 2; // OCR_Flag: OCR ES_ID
  while (at < p.length) {
    const tag = p[at];
    const d = descriptorLength(p, at + 1);
    const body = d.next;
    if (tag === 0x05) {
      return p.slice(body, body + d.len); // DecoderSpecificInfo = ASC
    }
    if (tag === 0x04) {
      at = body + 13; // DecoderConfig fixed fields → embedded children
      continue;
    }
    at = body + d.len; // SLConfig etc.: skip whole
  }
  return null;
}

/** stsd → first entry → mp4a AudioSampleEntry (+ esds hunt inside). */
function parseStsd(p: Uint8Array): { asc: Uint8Array; channels: number; sampleRate: number } | null {
  const entryCount = beU32(p, 4);
  if (entryCount < 1 || p.length < 16) return null;
  const at = 8;
  const entrySize = beU32(p, at);
  const entryType = fourcc(p, at + 4);
  if (entryType !== 'mp4a') return null; // not raw AAC — out of scope
  let q = at + 8;
  const entryEnd = Math.min(at + entrySize, p.length);
  q += 8; // SampleEntry: reserved(6) + data_reference_index(2)
  if (q + 20 > entryEnd) return null; // AudioSampleEntry fixed fields
  const channels = beU16(p, q + 8);
  const sampleRate = Math.round(beU32(p, q + 16) / 65536);
  q += 20;
  let asc: Uint8Array | null = null;
  for (const box of iterBoxes(p, q, entryEnd)) {
    if (box.type === 'esds') {
      asc = parseEsds(p.subarray(box.start, box.start + box.size));
      if (asc !== null) break;
    }
  }
  return asc === null ? null : { asc, channels, sampleRate };
}

// ---------------------------------------------------------------------------
// The demuxer
// ---------------------------------------------------------------------------

export class Mp4Demuxer implements AudioDemuxer {
  private readonly onPacket: PacketSink;
  private readonly onFormat: FormatSink | null;
  private _format: AudioFormat | null = null;
  private _ended = false;

  /** Rolling window: buffer[0] is absolute file offset fileStart. */
  private buffer = new Uint8Array(0);
  private fileStart = 0;
  /** Absolute parse cursor. */
  private cursor = 0;

  /** Static track facts once moov parsed. */
  private track: TrackInfo | null = null;
  private fragmented = false;
  private trex: TrexDefaults | null = null;
  /** Growing (fMP4) or frozen (flat) sample list. */
  private samples: Mp4Sample[] = [];
  private nextSample = 0;

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
    this.drainSamples();
  }

  endOfStream(): void {
    this._ended = true;
    this.parse();
    this.drainSamples();
  }

  private bufferedEnd(): number {
    return this.fileStart + this.buffer.length;
  }

  /**
   * Lowest absolute byte offset still needed by the drain: the next
   * undrained sample's offset (Infinity when none pending). Compaction
   * must never pass this — parse may walk boxes far ahead of drain
   * (a moof+mdat pair spans ~160 KiB), and the samples recorded in an
   * early moof live in the mdat the cursor has already jumped over.
   */
  private drainFloor(): number {
    const s = this.samples[this.nextSample];
    return s !== undefined ? s.offset : Number.POSITIVE_INFINITY;
  }

  /** Contiguous view of [a, b) or null when incomplete/compacted. */
  private view(a: number, b: number): Uint8Array | null {
    if (a < this.fileStart || b > this.bufferedEnd()) return null;
    return this.buffer.subarray(a - this.fileStart, b - this.fileStart);
  }

  /**
   * Top-level box walk. ftyp/free/sidx/emsg/styp skip; moov publishes
   * the format; moof appends fragments; mdat is jumped opaquely (the
   * cursor moves WITHOUT compaction — the drain still needs those
   * bytes; compaction happens as samples emit).
   */
  private parse(): void {
    for (let guard = 0; guard < 100_000; guard++) {
      const rel = this.cursor - this.fileStart;
      if (rel < 0) return;
      if (rel + 8 > this.buffer.length) return; // need a header
      const size0 = beU32(this.buffer, rel);
      const type = fourcc(this.buffer, rel + 4);
      let headerSize = 8;
      let size = size0;
      if (size0 === 1) {
        if (rel + 16 > this.buffer.length) return;
        size = beU64(this.buffer, rel + 8);
        headerSize = 16;
      }
      if (size0 === 0 || size < 8) {
        throw new Error(`mp4: bad ${type} box (size ${size0}) at ${this.cursor}`);
      }
      const contentStart = this.cursor + headerSize;
      const contentEnd = contentStart + size - headerSize;

      switch (type) {
        case 'moov': {
          const content = this.view(contentStart, contentEnd);
          if (content === null) return; // wait for the whole moov
          this.parseMoov(content);
          this.cursor = contentEnd;
          this.compactTo(contentEnd);
          break;
        }
        case 'moof': {
          if (this.track === null) {
            // Fragment before moov: not a stream we understand.
            throw new Error('mp4: moof before moov — unsupported layout');
          }
          const content = this.view(contentStart, contentEnd);
          if (content === null) return; // wait for the whole moof
          if (this.fragmented) {
            this.parseMoof(content, this.cursor);
          }
          this.cursor = contentEnd;
          this.compactTo(contentEnd);
          break;
        }
        case 'mdat': {
          // Opaque jump: cursor passes OVER the mdat (never buffered
          // past what drain needs — peek simply waits until bytes
          // arrive; the next moof parse resumes there).
          this.cursor = contentEnd;
          break;
        }
        default:
          // ftyp / free / sidx / emsg / styp / pdin / wide / …: skip.
          this.cursor = contentEnd;
          this.compactTo(contentEnd);
          break;
      }
    }
    throw new Error('mp4: box walk guard tripped (box storm)');
  }

  /** moov → soun trak static facts (+ flat tables when unfragmented). */
  private parseMoov(moov: Uint8Array): void {
    let trackInfo: TrackInfo | null = null;
    let flatTables: {
      deltas: number[];
      sizes: number[];
      stscRuns: [number, number][];
      chunkOffsets: number[];
      timescale: number;
    } | null = null;

    for (const box of iterBoxes(moov, 0, moov.length)) {
      if (box.type === 'mvex') {
        this.fragmented = true;
        for (const child of iterBoxes(moov, box.start, box.start + box.size)) {
          if (child.type === 'trex') {
            // FullBox(4) + track_ID(4) + sample_desc_idx(4) + duration
            // (4) + size(4) + flags(4).
            this.trex = {
              duration: beU32(moov, child.start + 12),
              size: beU32(moov, child.start + 16),
            };
          }
        }
      }
      if (box.type !== 'trak' || trackInfo !== null) continue;
      const trakEnd = box.start + box.size;
      // -- track id (tkhd) + the mdia box --------------------------------
      let trackId = -1;
      let mdia: Box | null = null;
      for (const b of iterBoxes(moov, box.start, trakEnd)) {
        if (b.type === 'tkhd') {
          const { version } = boxFlags(moov, b.start);
          trackId = beU32(moov, b.start + (version === 1 ? 20 : 12));
        }
        if (b.type === 'mdia') mdia = b;
      }
      if (mdia === null || trackId < 0) continue;
      // -- mdia: soun handler (hdlr), timescale (mdhd), stbl -------------
      const mdiaEnd = mdia.start + mdia.size;
      let isSound = false;
      let timescale = 0;
      let stbl: Box | null = null;
      for (const b of iterBoxes(moov, mdia.start, mdiaEnd)) {
        if (b.type === 'hdlr') {
          // FullBox(4) + pre_defined(4) + handler_type(4)
          if (fourcc(moov, b.start + 8) === 'soun') isSound = true;
        }
        if (b.type === 'mdhd') {
          const version = moov[b.start];
          const tsAt = b.start + 4 + (version === 1 ? 16 : 8);
          timescale = beU32(moov, tsAt);
        }
        if (b.type === 'minf') {
          for (const c of iterBoxes(moov, b.start, b.start + b.size)) {
            if (c.type === 'stbl') stbl = c;
          }
        }
      }
      if (!isSound || stbl === null || timescale === 0) continue;
      // -- stsd + (flat) tables ------------------------------------------
      let asc: Uint8Array | null = null;
      let channels = 2;
      let sampleRate = 48000;
      let deltas: number[] = [];
      let sizes: number[] = [];
      let stscRuns: [number, number][] = [];
      let chunkOffsets: number[] = [];
      for (const b of iterBoxes(moov, stbl.start, stbl.start + stbl.size)) {
        switch (b.type) {
          case 'stsd': {
            const r = parseStsd(moov.subarray(b.start, b.start + b.size));
            if (r !== null) {
              asc = r.asc;
              channels = r.channels;
              sampleRate = r.sampleRate;
            }
            break;
          }
          case 'stts': {
            const n = beU32(moov, b.start + 4);
            let q = b.start + 8;
            for (let i = 0; i < n; i++, q += 8) {
              const count = beU32(moov, q);
              const delta = beU32(moov, q + 4);
              for (let k = 0; k < count; k++) deltas.push(delta);
            }
            break;
          }
          case 'stsc': {
            const n = beU32(moov, b.start + 4);
            let q = b.start + 8;
            for (let i = 0; i < n; i++, q += 12) {
              stscRuns.push([beU32(moov, q), beU32(moov, q + 4)]); // firstChunk, per
            }
            break;
          }
          case 'stsz': {
            const sampleSize = beU32(moov, b.start + 4);
            const count = beU32(moov, b.start + 8);
            let q = b.start + 12;
            for (let i = 0; i < count; i++, q += 4) {
              sizes.push(sampleSize !== 0 ? sampleSize : beU32(moov, q));
            }
            break;
          }
          case 'stco': {
            const n = beU32(moov, b.start + 4);
            let q = b.start + 8;
            for (let i = 0; i < n; i++, q += 4) chunkOffsets.push(beU32(moov, q));
            break;
          }
          case 'co64': {
            const n = beU32(moov, b.start + 4);
            let q = b.start + 8;
            for (let i = 0; i < n; i++, q += 8) chunkOffsets.push(beU64(moov, q));
            break;
          }
        }
      }
      if (asc === null) continue; // this trak isn't usable — next
      trackInfo = { trackId, timescale, asc, channels, sampleRate };
      if (!this.fragmented) {
        flatTables = { deltas, sizes, stscRuns, chunkOffsets, timescale };
      }
      break; // first sound track wins
    }

    if (trackInfo === null) {
      throw new Error('mp4: no sound track with esds ASC found');
    }
    this.track = trackInfo;
    this._format = {
      codec: 'aac',
      description: trackInfo.asc,
      sampleRate: trackInfo.sampleRate || 48000,
      channels: trackInfo.channels || 2,
    };
    this.onFormat?.(this._format);

    if (!this.fragmented) {
      // -- flatten the static tables into the sample list ----------------
      const t = flatTables;
      if (
        t === null ||
        t.sizes.length === 0 ||
        t.chunkOffsets.length === 0 ||
        t.stscRuns.length === 0
      ) {
        throw new Error('mp4: unfragmented moov lacks sample tables');
      }
      let sampleIdx = 0;
      let ptsTicks = 0;
      for (let c = 0; c < t.chunkOffsets.length; c++) {
        let per = t.stscRuns[0][1];
        for (const [firstChunk, p] of t.stscRuns) {
          if (firstChunk <= c + 1) per = p;
          else break;
        }
        let off = t.chunkOffsets[c];
        for (let j = 0; j < per && sampleIdx < t.sizes.length; j++, sampleIdx++) {
          const size = t.sizes[sampleIdx];
          const delta =
            t.deltas[sampleIdx] ?? t.deltas[t.deltas.length - 1] ?? 1024;
          this.samples.push({
            offset: off,
            size,
            ptsUs: (ptsTicks / t.timescale) * 1e6,
            durUs: (delta / t.timescale) * 1e6,
            keyframe: true, // AAC: every sample independently decodable
          });
          ptsTicks += delta;
          off += size;
        }
      }
    }
  }

  /** moof → trafs → truns → append samples (fragmented mode). */
  private parseMoof(moof: Uint8Array, moofAbsStart: number): void {
    const track = this.track;
    if (track === null) return;
    const moofEnd = moof.length; // relative to content view
    for (const traf of iterBoxes(moof, 0, moofEnd)) {
      if (traf.type !== 'traf') continue;
      const trafEnd = traf.start + traf.size;
      // -- pass 1: tfhd + tfdt ------------------------------------------
      let trackId = -1;
      let baseOffset: number | null = null;
      let defaultDuration = this.trex?.duration ?? 0;
      let defaultSize = this.trex?.size ?? 0;
      let defaultBaseIsMoof = false;
      let baseTicks: number | null = null;
      for (const b of iterBoxes(moof, traf.start, trafEnd)) {
        if (b.type === 'tfhd') {
          const { flags } = boxFlags(moof, b.start);
          let q = b.start + 4;
          trackId = beU32(moof, q);
          q += 4;
          if (flags & 0x000001) {
            baseOffset = beU64(moof, q);
            q += 8;
          }
          if (flags & 0x000002) q += 4; // sample_description_index
          if (flags & 0x000008) {
            defaultDuration = beU32(moof, q);
            q += 4;
          }
          if (flags & 0x000010) {
            defaultSize = beU32(moof, q);
            q += 4;
          }
          if (flags & 0x000020) q += 4; // default_sample_flags
          if (flags & 0x020000) defaultBaseIsMoof = true;
        }
        if (b.type === 'tfdt') {
          const { version } = boxFlags(moof, b.start);
          baseTicks =
            version === 1 ? beU64(moof, b.start + 4) : beU32(moof, b.start + 4);
        }
      }
      if (trackId !== track.trackId) continue; // not our track's traf
      // Base for byte offsets: explicit > default-base-is-moof >
      // moof start (YouTube always uses default-base-is-moof here).
      const base = baseOffset ?? moofAbsStart;
      void defaultBaseIsMoof; // (both fallbacks equal moofAbsStart)
      // -- pass 2: truns --------------------------------------------------
      let tickCursor = baseTicks ?? 0;
      let offsetCursor: number | null = null;
      for (const b of iterBoxes(moof, traf.start, trafEnd)) {
        if (b.type !== 'trun') continue;
        const { flags } = boxFlags(moof, b.start);
        let q = b.start + 4;
        const sampleCount = beU32(moof, q);
        q += 4;
        let dataOffset = 0;
        let firstFlags: number | null = null;
        if (flags & 0x000001) {
          // signed 32-bit
          dataOffset = (beU32(moof, q) | 0) << 0;
          q += 4;
        }
        if (flags & 0x000004) {
          firstFlags = beU32(moof, q);
          q += 4;
        }
        offsetCursor =
          offsetCursor === null ? base + dataOffset : offsetCursor;
        for (let i = 0; i < sampleCount; i++) {
          let dur = defaultDuration;
          let size = defaultSize;
          let sflags = firstFlags;
          if (flags & 0x000100) {
            dur = beU32(moof, q);
            q += 4;
          }
          if (flags & 0x000200) {
            size = beU32(moof, q);
            q += 4;
          }
          if (flags & 0x000400) {
            sflags = beU32(moof, q);
            q += 4;
          }
          if (flags & 0x000800) {
            q += 4; // composition offset — always 0 for audio
          }
          if (size > 0) {
            this.samples.push({
              offset: offsetCursor,
              size,
              ptsUs: (tickCursor / track.timescale) * 1e6,
              durUs: (dur / track.timescale) * 1e6,
              // sample_is_non_sync_sample is bit 16; audio is all-sync.
              keyframe: sflags === null || (sflags & 0x00010000) === 0,
            });
            offsetCursor += size;
          }
          tickCursor += dur;
        }
      }
    }
  }

  /** Emit every fully-buffered sample; compact behind the cursor. */
  private drainSamples(): void {
    for (; this.nextSample < this.samples.length; this.nextSample++) {
      const s = this.samples[this.nextSample];
      if (s.offset + s.size > this.bufferedEnd()) break;
      const view = this.view(s.offset, s.offset + s.size);
      if (view === null) break;
      this.onPacket({
        data: view.slice(), // fresh copy — transferable
        timestampUs: Math.round(s.ptsUs),
        durationUs: Math.round(s.durUs),
        keyframe: s.keyframe,
      });
      this.compactTo(s.offset + s.size);
    }
  }

  /**
   * Drop buffered bytes before `abs`, NEVER past the drain floor or
   * the received end (push() appends sequentially — fileStart must
   * always name the true absolute position of buffer[0]).
   */
  private compactTo(abs: number): void {
    const target = Math.min(abs, this.drainFloor(), this.bufferedEnd());
    const rel = target - this.fileStart;
    if (rel <= 0) return;
    if (rel >= this.buffer.length) {
      this.buffer = new Uint8Array(0);
    } else {
      this.buffer = this.buffer.subarray(rel);
    }
    this.fileStart += rel;
  }
}
