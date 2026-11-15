// CanaryConsole.tsx — the W0.1b residential canary page component (front-end/canary).
//
// Plain Vite-consumed React; no 'use client' needed. Mounted by the web app (the lead
// wires web/canary.html). Runs the probe matrix translated from tools/probe-clients.sh
// plus the R-C conditions translated from tools/probe-search-resolver.sh, then posts
// the flattened rows to Supabase canary_results (anon INSERT only) for the W0.5
// spend decision.
//
// Relay base resolution order:
//   1. ?relay=<base> query param (per-tester override)
//   2. import.meta.env.VITE_RELAY_BASE_URL
//   3. '' in production / 'http://localhost:8787' only when import.meta.env.DEV
// The input below is editable regardless of where the default came from.

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { CLIENTS, VIDEOS } from '../matrix';
import { runProbeMatrix } from '../probeMatrix';
import { buildRows, canaryEnv, postResults, supabaseEnv } from '../postResults';
import type { ProbeReport, ProbeRow, RCondition } from '../types';
import './canary.css';

type Phase = 'idle' | 'running' | 'done' | 'posted';

function resolveRelayBase(): string {
  try {
    const q = new URLSearchParams(window.location.search).get('relay');
    if (q && q.trim()) return q.trim();
  } catch {
    /* ignore malformed location */
  }
  const env = canaryEnv();
  if (env.VITE_RELAY_BASE_URL) return env.VITE_RELAY_BASE_URL;
  if (env.DEV) return 'http://localhost:8787';
  return '';
}

const stamp = (): string => new Date().toLocaleTimeString([], { hour12: false });

function rowLogLine(r: ProbeRow): string {
  const audio = `${r.audioFmts}/${r.totalFmts}${r.fmt251 ? '·251' : ''}`;
  const tail = r.reason && r.reason !== '-' ? ` · ${r.reason}` : '';
  return `${r.client} ${r.video} → HTTP ${r.status} ${r.playability} · audio ${audio} · ciphered ${r.ciphered} · ${r.latencyMs}ms${tail}`;
}

function StatusChip({ status }: { status: number }): React.ReactElement {
  const cls =
    status === 200 ? 'canary-chip-ok' : status === 0 ? 'canary-chip-zero' : 'canary-chip-bad';
  const title =
    status === 0 ? 'fetch failed (relay/CORS/network) — see R-C details' : `HTTP ${status}`;
  return (
    <span className={`canary-chip ${cls}`} title={title}>
      {status}
    </span>
  );
}

function MatrixTable({ rows }: { rows: ProbeRow[] }): React.ReactElement {
  return (
    <div className="canary-tablewrap">
      <div className="canary-tablescroll">
        <table className="canary-table">
          <thead>
            <tr>
              <th>client</th>
              <th>video</th>
              <th>status</th>
              <th>playability</th>
              <th>audio</th>
              <th>ciphered</th>
              <th>ms</th>
              <th>reason</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={8} className="canary-empty">
                  awaiting first response…
                </td>
              </tr>
            )}
            {rows.map((r, i) => (
              <tr key={`${r.client}-${r.video}-${i}`}>
                <td className="canary-mono">{r.client}</td>
                <td className="canary-mono">{r.video}</td>
                <td>
                  <StatusChip status={r.status} />
                </td>
                <td>
                  <span className={r.playability === 'OK' ? 'canary-play-ok' : 'canary-play-bad'}>
                    {r.playability}
                  </span>
                </td>
                <td className="canary-mono">
                  {r.audioFmts}/{r.totalFmts}
                  {r.fmt251 ? (
                    <span className="canary-251" title="itag 251 (opus 128k) present">
                      {' ·251'}
                    </span>
                  ) : null}
                </td>
                <td className="canary-mono">{r.ciphered}</td>
                <td className="canary-mono">{r.latencyMs}</td>
                <td className="canary-reason">{r.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function RCondCard({ c }: { c: RCondition }): React.ReactElement {
  const cls =
    c.result === 'pass'
      ? 'canary-chip-ok'
      : c.result === 'fail'
        ? 'canary-chip-bad'
        : c.result === 'unknown'
          ? 'canary-chip-warn'
          : 'canary-chip-zero';
  return (
    <div className="canary-rcond">
      <div className="canary-rcond-head">
        <span className="canary-mono canary-rcond-id">{c.id}</span>
        <span className="canary-rcond-name">{c.name}</span>
        <span className={`canary-chip ${cls}`}>{c.result}</span>
      </div>
      <div className="canary-rcond-detail">{c.detail}</div>
    </div>
  );
}

export default function CanaryConsole(): React.ReactElement {
  const [phase, setPhase] = useState<Phase>('idle');
  const [relayBase, setRelayBase] = useState<string>(() => resolveRelayBase());
  const [rows, setRows] = useState<ProbeRow[]>([]);
  const [progress, setProgress] = useState<{ done: number; total: number }>({ done: 0, total: 20 });
  const [report, setReport] = useState<ProbeReport | null>(null);
  const [log, setLog] = useState<string[]>([]);
  const [relayWarn, setRelayWarn] = useState('');
  const [postBusy, setPostBusy] = useState(false);
  const [postMessage, setPostMessage] = useState('');
  const [postOk, setPostOk] = useState(false);
  const [copyMessage, setCopyMessage] = useState('');
  const logRef = useRef<HTMLDivElement>(null);

  const pushLog = useCallback((line: string) => {
    setLog((prev) => [...prev.slice(-199), `${stamp()} ${line}`]);
  }, []);

  useEffect(() => {
    const el = logRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [log]);

  const onRun = useCallback(async () => {
    if (phase === 'running') return;
    const base = relayBase.trim();
    if (!base) {
      setRelayWarn('relay base is empty — pass ?relay=<base> in the URL, set VITE_RELAY_BASE_URL, or type it above');
      return;
    }
    setRelayWarn('');
    setPhase('running');
    setRows([]);
    setReport(null);
    setLog([]);
    setPostMessage('');
    setPostOk(false);
    setCopyMessage('');
    setProgress({ done: 0, total: CLIENTS.length * VIDEOS.length });
    pushLog(`relay: ${base}`);
    pushLog(
      `matrix: ${CLIENTS.length} clients × ${VIDEOS.length} videos = ${CLIENTS.length * VIDEOS.length} player POSTs, 2s spacing (tools/probe-clients.sh)`
    );
    const started = Date.now();
    try {
      const r = await runProbeMatrix(base, {
        onProgress: (done, total, lastRow) => {
          setProgress({ done, total });
          if (lastRow) {
            setRows((prev) => [...prev, lastRow]);
            pushLog(rowLogLine(lastRow));
          }
        }
      });
      setReport(r);
      setPhase('done');
      pushLog(`matrix complete — R-C conditions evaluated (player via relay, media bytes direct)`);
      const passes = r.rConditions.filter((c) => c.result === 'pass').length;
      const oks = r.rows.filter((row) => row.ok).length;
      pushLog(
        `done in ${((Date.now() - started) / 1000).toFixed(1)}s · ${r.rows.length} rows · ok ${oks}/${r.rows.length} · R-C pass ${passes}/4`
      );
    } catch (e) {
      // runProbeMatrix never throws by contract — this is a defensive net.
      pushLog(`run aborted: ${e instanceof Error ? `${e.name}: ${e.message}` : String(e)}`);
      setPhase('done');
    }
  }, [phase, relayBase, pushLog]);

  const onPost = useCallback(async () => {
    if (!report || postBusy) return;
    setPostBusy(true);
    setPostMessage('');
    const res = await postResults(report);
    setPostBusy(false);
    if (res.posted) {
      setPostOk(true);
      setPostMessage(`posted ${res.rowCount} rows`);
      setPhase('posted');
      pushLog(`posted ${res.rowCount} rows to Supabase canary_results`);
    } else {
      setPostOk(false);
      setPostMessage(res.error ?? 'unknown error');
    }
  }, [report, postBusy, pushLog]);

  const onCopy = useCallback(async () => {
    if (!report) return;
    const dbRows = buildRows(report);
    const text = JSON.stringify(dbRows, null, 2);
    try {
      await navigator.clipboard.writeText(text);
      setCopyMessage(`copied ${dbRows.length} rows (${text.length} chars) to clipboard`);
    } catch (e) {
      // eslint-disable-next-line no-console -- fallback so testers can still submit manually
      console.log('[canary] rows JSON for manual submission:', dbRows);
      setCopyMessage(
        `clipboard unavailable (${e instanceof Error ? e.message : String(e)}) — rows JSON logged to the console`
      );
    }
  }, [report]);

  const running = phase === 'running';
  const finished = phase === 'done' || phase === 'posted';
  const supa = supabaseEnv();
  const tableRows = report ? report.rows : rows;

  return (
    <div className="canary-root">
      <header className="canary-header">
        <h1>W0.1b · residential canary</h1>
        <p className="canary-sub">
          PO-free client-ladder evidence collected from <strong>your</strong> IP — input for the W0.5
          spend decision
        </p>
      </header>

      <section className="canary-card canary-explainer">
        <ul>
          <li>
            <strong>What this is:</strong> a browser-side translation of{' '}
            <span className="canary-mono">tools/probe-clients.sh</span> (10 innertube clients × 2
            videos: <span className="canary-mono">dQw4w9WgXcQ</span> standard,{' '}
            <span className="canary-mono">4NRXx6U8ABQ</span> licensed) plus the R-C conditions from{' '}
            <span className="canary-mono">tools/probe-search-resolver.sh</span> — residential-IP
            evidence the datacenter-only W0.1 spike could not produce.
          </li>
          <li>
            <strong>Traffic split:</strong> all innertube API calls go <strong>through the relay</strong>{' '}
            (YouTube sees the relay's datacenter IP); <strong>media bytes (googlevideo) are fetched
            direct</strong> from this browser — that residential half is the whole point.
          </li>
          <li>
            <strong>UA deviation (documented, not worked around):</strong> fetch cannot set the
            User-Agent header, so YouTube sees your browser's UA at the header level; client identity
            rides on <span className="canary-mono">X-YouTube-Client-*</span> headers and the matrix
            UA still travels in the JSON body (<span className="canary-mono">context.client.userAgent</span>).
          </li>
          <li>
            <strong>Where results go:</strong> Supabase <span className="canary-mono">canary_results</span>{' '}
            (anon INSERT only) — recruiting 5+ residential testers is the PO's job.
          </li>
        </ul>
      </section>

      <section className="canary-card">
        <div className="canary-controls">
          <label htmlFor="canary-relay">relay base</label>
          <input
            id="canary-relay"
            value={relayBase}
            spellCheck={false}
            placeholder="https://your-relay.example.workers.dev"
            onChange={(e) => setRelayBase(e.target.value)}
            disabled={running}
          />
          <button
            type="button"
            className="canary-btn canary-btn-primary"
            onClick={onRun}
            disabled={running}
          >
            Run probe matrix
          </button>
        </div>
        {relayWarn && <p className="canary-warn">{relayWarn}</p>}
        <p className="canary-muted">
          a full run is 20 relayed player calls (2s spacing) + 2 direct CDN range fetches — expect
          about a minute
        </p>
      </section>

      {running && (
        <section className="canary-card">
          <div className="canary-progress canary-mono">
            {progress.done}/{progress.total}
            {progress.done === progress.total ? ' · R-C conditions in progress…' : ''}
          </div>
          <div className="canary-log canary-mono" role="log" aria-live="polite" ref={logRef}>
            {log.map((l, i) => (
              <div key={i}>{l}</div>
            ))}
          </div>
        </section>
      )}

      {(rows.length > 0 || finished) && (
        <section className="canary-card">
          <h2>matrix results{running ? ' (live)' : ''}</h2>
          <MatrixTable rows={tableRows} />
        </section>
      )}

      {report && finished && (
        <section className="canary-card">
          <h2>R-C conditions</h2>
          {report.rConditions.map((c) => (
            <RCondCard key={c.id} c={c} />
          ))}
        </section>
      )}

      {report && finished && (
        <section className="canary-card">
          <h2>post results</h2>
          {phase === 'posted' ? (
            <p className="canary-ok">{postMessage || 'posted'} to Supabase canary_results</p>
          ) : supa ? (
            <>
              <p className="canary-muted">
                POST <span className="canary-mono">{supa.url}/rest/v1/canary_results</span> ·{' '}
                {report.rows.length + report.rConditions.length} rows ({report.rows.length} matrix +{' '}
                {report.rConditions.length} R-C) · anon INSERT only
              </p>
              <button type="button" className="canary-btn canary-btn-primary" onClick={onPost} disabled={postBusy}>
                {postBusy ? 'posting…' : 'Post results to Supabase'}
              </button>
              {postMessage && <p className={postOk ? 'canary-ok' : 'canary-err'}>{postMessage}</p>}
            </>
          ) : (
            <>
              <p className="canary-warn-note">
                Supabase not configured — set{' '}
                <span className="canary-mono">VITE_SUPABASE_URL</span> and{' '}
                <span className="canary-mono">VITE_SUPABASE_ANON_KEY</span> in the web app build env,
                or copy the rows JSON below and submit manually.
              </p>
              <button type="button" className="canary-btn" onClick={onCopy}>
                copy rows JSON
              </button>
              {copyMessage && <p className="canary-muted">{copyMessage}</p>}
            </>
          )}
        </section>
      )}

      <footer className="canary-footer">
        <span>W0.1b residential canary · front-end/canary</span>
        <span>matrix: tools/probe-clients.sh · R-C: tools/probe-search-resolver.sh</span>
        <span>recruiting 5+ residential testers is the PO's job</span>
      </footer>
    </div>
  );
}
