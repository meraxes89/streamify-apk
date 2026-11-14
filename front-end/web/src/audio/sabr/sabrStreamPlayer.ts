/**
 * audio/sabr/sabrStreamPlayer.ts — G4: the SABR playback lane (MSE audio).
 *
 * WHY THIS EXISTS: the modern innertube reality is that IOS-class clients
 * answer playability OK with URL-less SABR format descriptors plus a
 * `serverAbrStreamingUrl` — the progressive-URL sunset the G3b doc
 * deferred ("SABR is the future-proofing path if YouTube completes the
 * progressive sunset"). It has arrived: probe-verified 2026-11-14 (IOS via
 * youtubei.googleapis.com: OK, itags 140/249/250/251 present, zero URLs,
 * no decipher, no HLS for licensed uploads).
 *
 * HOW IT PLAYS: googlevideo's SabrStream (LuanRT — the same engine kira,
 * the author's SABR reference app, ships) speaks YouTube's Server-Adaptive
 * Bitrate protocol end-to-end: it POSTs playback requests to the
 * googlevideo CDN (CORS-open — the same surface the real web player's MSE
 * pipeline uses), selects formats, negotiates protection with the PO
 * token, and emits the media as a sequential ReadableStream of fMP4 bytes.
 * This module pipes that stream into a MediaSource SourceBuffer and hands
 * the element a `blob:` object URL — the element starts playing while
 * segments are still arriving (a music track is 3–5 MB; on a phone it is
 * fully buffered within seconds).
 *
 * EGRESS RULE (probe-measured 2026-11-14): googlevideo's CORS is
 * youtube.com-only — foreign pages CANNOT POST to the SABR endpoint
 * directly (the preflight carries no ACAO), on the phone exactly like in
 * the lab. kira solves this with its ytc-bridge proxy (or an extension's
 * origin-stripping); we solve it with the relay: the small UMP requests
 * and the segment responses ride the Worker, which streams both ways and
 * adds ACAO:*. The IP-pin is tolerated on SABR POSTs (curl-verified from
 * a mismatched lab IP against a URL minted for a Cloudflare egress).
 *
 * FAILURE SURFACE: stream-protection refusals (LICENSE_REQUIRED-class),
 * server reload directives and stalls reject the `done` promise and fire
 * the onFail callbacks; the caller (wasmEngine's element hook) ledgers the
 * rung dead and escalates the ladder, exactly like an HLS remux failure.
 */

import { SabrStream } from 'googlevideo/sabr-stream';
import { EnabledTrackTypes } from 'googlevideo/utils';
import type { SabrFormat } from 'googlevideo/shared-types';
import { relayUrl } from '../botguard/protocol';
import type { SabrPayload } from '../resolve';

/** A handle on one live SABR playback session. */
export interface SabrSessionHandle {
  /** The object URL for the <audio> element (MediaSource-backed). */
  objectUrl: string;
  /**
   * Resolves when the SABR download finished and the MediaSource was
   * ended cleanly; rejects on any failure (protection refusal, network,
   * stall). Errors also surface via onFail — callers may race either.
   */
  done: Promise<'finished'>;
  /** Fired once on any failure (the session aborts itself afterwards). */
  onFail: (callback: (message: string) => void) => void;
  /** Abort the session (track change / teardown). Idempotent. */
  abort: () => void;
}

const AAC_MIME = 'audio/mp4; codecs="mp4a.40.2"';

/** MSE support probe (every modern browser; old WebViews lack it). */
export function mseAudioAvailable(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.MediaSource !== 'undefined' &&
    MediaSource.isTypeSupported(AAC_MIME)
  );
}

/**
 * Pick the audio SABR format: AAC/mp4 (itag 140-class) first — the element
 * decodes mp4a.40.2 everywhere; Opus-in-webm only as a Chromium fallback.
 */
function pickAudioFormat(
  formats: SabrFormat[],
  preferAac: boolean,
): SabrFormat | undefined {
  const isAudio = (f: SabrFormat): boolean =>
    typeof f.mimeType === 'string' && f.mimeType.startsWith('audio/');
  const audio = formats.filter(isAudio);
  if (audio.length === 0) return undefined;
  const byMime = (prefix: string): SabrFormat | undefined =>
    audio.find(
      (f) => typeof f.mimeType === 'string' && f.mimeType.startsWith(prefix),
    );
  const aac = byMime('audio/mp4');
  const opus = byMime('audio/webm');
  if (preferAac) return aac ?? opus;
  return opus ?? aac;
}

/**
 * Start one SABR playback session for a resolved stream.
 *
 * THE EGRESS RULE (probe-measured 2026-11-14): googlevideo serves ACAO
 * ONLY to https://www.youtube.com — a foreign page's SABR POSTs
 * (initplayback, content-type application/octet-stream → preflight) are
 * CORS-dead DIRECT, on the phone exactly like in the lab (kira likewise
 * proxies googlevideo when not running as an extension). The SABR
 * requests therefore ride the RELAY; the IP-pin is tolerated on SABR
 * POSTs (curl-verified: a URL minted for a Cloudflare egress answered
 * 200 application/vnd.yt-ump from a mismatched lab IP).
 *
 * The returned handle's objectUrl is ready IMMEDIATELY (the MediaSource
 * buffers as data arrives); `sink.load(objectUrl, …)` starts playback the
 * moment the first segments land. `startMs` is advisory — SabrStream is a
 * sequential downloader, so seeks beyond the buffered window wait for the
 * download (music tracks complete in seconds; fully-seekable ABR is the
 * Shaka-adapter future).
 */
export async function startSabrAudio(
  payload: SabrPayload,
  preferAac = true,
  relayBase = '',
): Promise<SabrSessionHandle> {
  if (!mseAudioAvailable()) {
    throw new Error('MediaSource audio unavailable (SABR lane needs MSE)');
  }
  const format = pickAudioFormat(payload.formats as SabrFormat[], preferAac);
  if (format === undefined) {
    throw new Error('no audio format in the SABR descriptors');
  }
  const mimeType =
    typeof format.mimeType === 'string' && format.mimeType.length > 0
      ? format.mimeType
      : AAC_MIME;
  if (!MediaSource.isTypeSupported(mimeType)) {
    throw new Error(`MSE cannot decode ${mimeType}`);
  }

  const mediaSource = new MediaSource();
  const objectUrl = URL.createObjectURL(mediaSource);

  let failListeners: Array<(message: string) => void> = [];
  let failed = false;
  let sabr: SabrStream | null = null;

  const fail = (message: string): void => {
    if (failed) return;
    failed = true;
    try {
      if (mediaSource.readyState === 'open') mediaSource.endOfStream();
    } catch {
      /* already ended/closed */
    }
    try {
      sabr?.abort();
    } catch {
      /* best-effort teardown */
    }
    for (const cb of failListeners) cb(message);
    failListeners = [];
  };

  const done = new Promise<'finished'>((resolve, reject) => {
    let sourceBuffer: SourceBuffer | null = null;
    /** Append queue: MSE allows one update at a time. */
    const queue: Uint8Array[] = [];
    let appending = false;
    let downloadFinished = false;
    let resolved = false;

    const maybeEnd = (): void => {
      if (
        !resolved &&
        !failed &&
        downloadFinished &&
        queue.length === 0 &&
        !appending &&
        sourceBuffer !== null &&
        mediaSource.readyState === 'open'
      ) {
        resolved = true;
        try {
          mediaSource.endOfStream();
        } catch {
          /* raced a teardown; the fail path owns the verdict */
        }
        resolve('finished');
      }
    };

    const drain = (): void => {
      if (appending || sourceBuffer === null || queue.length === 0) {
        maybeEnd();
        return;
      }
      const chunk = queue.shift();
      if (chunk === undefined) return;
      appending = true;
      try {
        sourceBuffer.appendBuffer(chunk);
      } catch (err) {
        appending = false;
        queue.length = 0;
        fail(
          `MSE append failed: ${err instanceof Error ? err.message : String(err)}`,
        );
        reject(new Error('MSE append failed'));
      }
    };

    mediaSource.addEventListener('sourceopen', () => {
      try {
        sourceBuffer = mediaSource.addSourceBuffer(mimeType);
      } catch (err) {
        fail(
          `addSourceBuffer failed: ${err instanceof Error ? err.message : String(err)}`,
        );
        reject(err instanceof Error ? err : new Error('addSourceBuffer failed'));
        return;
      }
      sourceBuffer.addEventListener('updateend', () => {
        appending = false;
        drain();
      });
      sourceBuffer.addEventListener('error', () => {
        fail('SourceBuffer error');
        reject(new Error('SourceBuffer error'));
      });
      drain();
    });

    const run = async (): Promise<void> => {
      sabr = new SabrStream({
        // SABR through the RELAY: googlevideo's CORS is youtube.com-only
        // (see the egress rule above) — the preflight would kill a direct
        // POST from any foreign origin. The relay streams the UMP bodies
        // both ways and adds ACAO:*.
        fetch: (input: RequestInfo | URL, init?: RequestInit) => {
          const url =
            input instanceof URL
              ? input.toString()
              : typeof input === 'string'
                ? input
                : input.url;
          const target =
            relayBase !== '' ? relayUrl(relayBase, url) : url;
          return fetch(target, init);
        },
        serverAbrStreamingUrl: payload.serverAbrStreamingUrl,
        videoPlaybackUstreamerConfig: payload.ustreamerConfig ?? undefined,
        clientInfo: payload.clientInfo,
        poToken: payload.poToken ?? undefined,
        durationMs: payload.durationMs > 0 ? payload.durationMs : undefined,
        formats: payload.formats as SabrFormat[],
      });
      sabr.on(
        'streamProtectionStatusUpdate',
        (status: { status?: number; maxRetries?: number }) => {
          // The protocol's semantics (googlevideo's own handler + kira):
          //   0 — OK · 1/2 — attestation pending (a WARNING — media keeps
          //   flowing while the attestation settles; killing the session
          //   here was a lab-measured false positive) · 3 — attestation
          //   REQUIRED (the hard refusal SabrStream itself throws on).
          if (typeof status?.status === 'number' && status.status >= 3) {
            fail(`SABR protection status ${status.status}`);
            reject(new Error(`SABR protection status ${status.status}`));
          }
        },
      );
      sabr.on('reloadPlayerResponse', () => {
        // The server invalidated the session (expired URL / token drift).
        fail('SABR demanded a player reload (stale session)');
        reject(new Error('SABR player reload demanded'));
      });
      const { audioStream } = await sabr.start({
        audioFormat: format,
        // Audio-only track types: a music player never fetches video.
        enabledTrackTypes: EnabledTrackTypes.AUDIO_ONLY,
        preferMP4: preferAac,
        preferOpus: !preferAac,
      });
      const reader = audioStream.getReader();
      for (;;) {
        const { done: streamDone, value } = await reader.read();
        if (streamDone) break;
        if (value !== undefined) {
          queue.push(value);
          drain();
        }
      }
      downloadFinished = true;
      // Flush the tail; the updateend handler ends the stream.
      drain();
    };

    run().catch((err: unknown) => {
      const message = `SABR stream failed: ${err instanceof Error ? err.message : String(err)}`;
      fail(message);
      reject(new Error(message));
    });
  });

  return {
    objectUrl,
    done,
    onFail: (callback) => {
      failListeners.push(callback);
    },
    abort: () => {
      fail('aborted by caller');
    },
  };
}
