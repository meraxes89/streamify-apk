/**
 * components/SpineConsole.tsx — the P1 audio-spine console (WS-W1).
 *
 * The user-facing proof surface for the whole spine:
 *
 *   relay resolve → 256 KiB ranged fetch → WebM/MP4 demux →
 *   WebCodecs decode → worklet ring → speakers
 *
 * …with the G1 gate metric live on screen (underrun events / frames
 * read < 0.1%). Every number rendered here comes from the Zustand
 * store, fed by the transport's 96-byte SINK_STATS_WIRE snapshots —
 * the same wire image the native HAL consumes.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Spine, phaseLabel } from '../audio/spine';
import { g1UnderrunGate } from '../audio/store';
import { useSpineStore } from '../audio/store';
import { relayBaseCandidate } from '../lib/engine';
import {
  clearSeed,
  ensureLiveSession,
  resetPotSession,
  seedPotSession,
  subscribeMint,
} from '../audio/botguard/potSession';
import type { MintStatus } from '../audio/botguard/protocol';
import type { SinkStats } from '../audio/statsWire';

/** The G1 canonical target (standard content — the unlicensed proof). */
const G1_VIDEO_ID = 'dQw4w9WgXcQ';
/** The G1 licensed target (W1.0 — the bot-wall unlock proof). */
const G1_LICENSED_ID = '4NRXx6U8ABQ';

function fmtBytes(n: number): string {
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(2)} MiB`;
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KiB`;
  return `${n} B`;
}
function fmtMs(us: number): string {
  const s = us / 1e6;
  if (s < 60) return `${s.toFixed(1)}s`;
  return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
}

export function SpineConsole(): JSX.Element {
  const [videoId, setVideoId] = useState<string>(G1_VIDEO_ID);
  const [relay] = useState<string>(() => relayBaseCandidate());
  const spineRef = useRef<Spine | null>(null);

  const phase = useSpineStore((s) => s.phase);
  const error = useSpineStore((s) => s.error);
  const session = useSpineStore((s) => s.session);
  const decoderDetail = useSpineStore((s) => s.decoderDetail);
  const stats = useSpineStore((s) => s.stats);
  const positionUs = useSpineStore((s) => s.positionUs);
  const fetch = useSpineStore((s) => s.fetch);
  const packetsDemuxed = useSpineStore((s) => s.packetsDemuxed);

  // -- W1.0 + P3b mint status (module singleton → React) ------------------
  const [mint, setMint] = useState<MintStatus>({
    phase: 'idle',
    detail: 'no mint yet',
    relayTooOld: false,
    braveShieldsAdvice: false,
  });
  const [seedOpen, setSeedOpen] = useState(false);
  const [seedText, setSeedText] = useState('');
  const [seedError, setSeedError] = useState<string | null>(null);
  useEffect(() => subscribeMint(setMint), []);

  const handleMint = useCallback(async () => {
    try {
      await ensureLiveSession(relay, { force: true });
    } catch {
      /* status carries the failure detail */
    }
  }, [relay]);

  const handleSeed = useCallback(() => {
    setSeedError(null);
    try {
      const { videoId } = seedPotSession(seedText);
      setSeedOpen(false);
      setSeedText('');
      void videoId;
    } catch (err) {
      setSeedError(err instanceof Error ? err.message : String(err));
    }
  }, [seedText]);

  useEffect(() => {
    return () => {
      spineRef.current?.stop();
      spineRef.current = null;
    };
  }, []);

  const busy =
    phase === 'resolving' || phase === 'demuxing' || phase === 'streaming' || phase === 'playing';

  const handlePlay = useCallback(async () => {
    const id = videoId.trim();
    if (id === '') return;
    spineRef.current?.stop();
    const spine = new Spine({ relayBase: relay });
    spineRef.current = spine;
    // Dev diagnosis handle (console: window.__spine.transport.contextState)
    (window as unknown as { __spine?: Spine }).__spine = spine;
    try {
      await spine.play(id);
    } catch {
      // phase → errored already in the store; the console renders it.
    }
  }, [relay, videoId]);

  const handleStop = useCallback(() => {
    spineRef.current?.stop();
    spineRef.current = null;
  }, []);

  const gate = g1UnderrunGate(stats);

  return (
    <section className="card spine-card" aria-label="P1 audio spine console">
      <h2 className="card-title">P1 — The Audio Spine (WS-W1)</h2>
      <p className="muted">
        Resolve → ranged fetch ({fmtBytes(256 * 1024)} chunks) → demux (EBML/WebM/Opus or
        fMP4/AAC) → WebCodecs decode → AudioWorklet ring → speakers. Zero{' '}
        {'<audio>'} element: this is the spine the Rust engine will own.
      </p>

      <div className="g04-actions">
        <label className="muted" htmlFor="spine-video-id">
          video id
        </label>
        <input
          id="spine-video-id"
          className="spine-input"
          value={videoId}
          onChange={(e) => setVideoId(e.target.value)}
          placeholder="YouTube video id"
          spellCheck={false}
        />
        <button
          type="button"
          className="btn"
          onClick={() => {
            void handlePlay();
          }}
          disabled={busy}
        >
          {busy ? 'Streaming…' : 'Play (spine)'}
        </button>
        <button type="button" className="btn" onClick={handleStop} disabled={phase === 'idle'}>
          Stop
        </button>
        <button
          type="button"
          className="btn"
          onClick={() => {
            setVideoId(G1_LICENSED_ID);
          }}
          disabled={busy || videoId === G1_LICENSED_ID}
          title="The licensed-track G1 target (Blinding Lights) — exercises the WEB+POT rung"
        >
          licensed target
        </button>
      </div>

      {/* -- W1.0: the PO-token mint panel --------------------------------- */}
      <div className="spine-pot" aria-label="PO token session">
        <button
          type="button"
          className="btn btn--small"
          onClick={() => {
            void handleMint();
          }}
          disabled={
            mint.phase !== 'idle' && mint.phase !== 'ready' && mint.phase !== 'cache'
          }
        >
          Mint PO session
        </button>
        <span
          className={`chip chip--${mint.phase === 'ready' ? 'loaded' : 'pending'}`}
          role="status"
          aria-label="mint status"
        >
          PO: {mint.phase}
        </span>
        <span className="muted spine-pot-detail">{mint.detail}</span>
        <button
          type="button"
          className="btn btn--small"
          onClick={() => setSeedOpen((v) => !v)}
          aria-expanded={seedOpen}
        >
          {seedOpen ? 'hide seed' : 'dev seed…'}
        </button>
        {mint.phase === 'ready' && mint.detail.startsWith('SEEDED') && (
          <button
            type="button"
            className="btn btn--small"
            onClick={() => {
              clearSeed();
            }}
          >
            clear seed
          </button>
        )}
        {(mint.phase === 'ready' || mint.phase === 'cache') && !mint.detail.startsWith('SEEDED') && (
          <button
            type="button"
            className="btn btn--small"
            onClick={() => {
              resetPotSession();
            }}
            title="Drop the mint session and the localStorage token cache (fresh BotGuard on next mint)"
          >
            reset session
          </button>
        )}
        {seedOpen && (
          <div className="spine-seed">
            <textarea
              className="spine-seed-input"
              aria-label="dev seed JSON"
              value={seedText}
              onChange={(e) => setSeedText(e.target.value)}
              placeholder={'{ "videoId": "4NRXx6U8ABQ", "visitorData": "…", "playerToken": "…", "gvsToken": "…" } — pot-lab pot-seed.json (video-scoped dev token; proves the player wiring when the environment cannot mint)'}
              rows={3}
              spellCheck={false}
            />
            <button type="button" className="btn btn--small" onClick={handleSeed}>
              apply seed
            </button>
            {seedError !== null && <span className="spine-error">{seedError}</span>}
          </div>
        )}
      </div>

      <div className="spine-status">
        <span
          className={`chip chip--${phase === 'playing' ? 'loaded' : phase === 'errored' ? 'error' : 'pending'}`}
          role="status"
        >
          {phaseLabel(phase)}
        </span>
        {session !== null && (
          <span className="badge">
            itag {session.itag} · {session.codec} · {fmtBytes(session.contentLength ?? 0)}
          </span>
        )}
        {decoderDetail !== null && <span className="badge">{decoderDetail}</span>}
        {session !== null && <span className="badge">{session.title}</span>}
      </div>

      {error !== null && phase === 'errored' && (
        <p className="spine-error" role="alert">
          {error}
        </p>
      )}
      {mint.relayTooOld && (
        <p className="spine-error" role="alert">
          This relay predates W1.0 — minting needs the upgraded worker deployed
          (wrangler deploy; see front-end/relay/README.md). Media + resolve still
          work through it.
        </p>
      )}
      {mint.braveShieldsAdvice && (
        <p className="spine-error" role="alert">
          Fingerprinting shields broke the mint's privacy buffer — the session
          continues on the skipPrivacyBuffer / websafe fallback (weaker
          attestation). If licensed playback stalls after the first ~1–2 MB,
          toggle off "Block fingerprinting" for this site in Brave Shields.
        </p>
      )}

      <div className="spine-grid">
        <Stat label="position" value={stats !== null ? fmtMs(positionUs) : '—'} />
        <Stat
          label="ring fullness"
          value={stats !== null ? `${stats.currentFullnessFrames} fr` : '—'}
        />
        <Stat
          label="latency (est)"
          value={stats !== null ? `${stats.estimatedLatencyMs.toFixed(0)} ms` : '—'}
        />
        <Stat label="packets demuxed" value={String(packetsDemuxed)} />
        <Stat label="bytes fetched" value={fmtBytes(fetch.bytesServed)} />
        <Stat
          label="chunks / retries"
          value={`${fetch.chunksSucceeded} / ${fetch.retries}`}
        />
        <Stat
          label="frames read"
          value={stats !== null ? String(stats.framesRead) : '—'}
        />
        <Stat
          label="underruns"
          value={stats !== null ? String(stats.underrunEvents) : '—'}
        />
        <Stat
          label="G1 gate"
          value={
            gate === 'pass' ? 'PASS (<0.1%)' : gate === 'fail' ? 'FAIL' : 'insufficient'
          }
          tone={gate === 'pass' ? 'pass' : gate === 'fail' ? 'fail' : undefined}
        />
      </div>
      {stats !== null && (
        <p className="muted spine-wire">
          SINK_STATS_WIRE seq={stats.sequence} · {stats.sampleRateIn}→{stats.sampleRateOut} Hz ·{' '}
          {stats.channelsIn}ch · high/low wm {stats.highWatermarkFrames}/{stats.lowWatermarkFrames}
        </p>
      )}
    </section>
  );
}

function Stat(props: {
  label: string;
  value: string;
  tone?: 'pass' | 'fail';
}): JSX.Element {
  return (
    <div className={`spine-stat${props.tone !== undefined ? ` spine-stat--${props.tone}` : ''}`}>
      <span className="spine-stat-label">{props.label}</span>
      <span className="spine-stat-value">{props.value}</span>
    </div>
  );
}

/** Re-export for the console header: the live stats object type. */
export type { SinkStats };
