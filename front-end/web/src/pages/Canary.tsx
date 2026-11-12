/**
 * Streamify Web — residential canary page (W0.1b, WS-W P0), routed at
 * #/canary (see App.tsx).
 *
 * Runs the fleet probe matrix (browser translation of tools/probe-clients.sh)
 * and the four R-C condition probes from the VISITOR's own network, renders
 * the results, and posts one summary row to the Supabase `canary_results`
 * table when VITE_SUPABASE_URL / VITE_SUPABASE_ANON_KEY are configured —
 * gracefully local-only otherwise.
 *
 * Stable selectors for CI / PO review:
 *   #canary-run            run button (label doubles as progress: "probing client 7/10")
 *   #canary-table          results table (client × video rows)
 *   #canary-summary        summary line, data-total / data-ok
 *   #canary-rc             R-C condition verdicts (RC-1 … RC-4)
 *   #canary-post-status    Supabase post outcome, data-posted / data-configured
 */
import { useState } from "react";
import {
  CANARY_VIDEOS,
  PROBE_CLIENTS,
  runProbeMatrix,
  type CanaryRunResult,
} from "../../../canary/src/probeMatrix";
import {
  postCanaryRun,
  type PostOutcome,
} from "../../../canary/src/postResults";

export default function Canary() {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState("idle");
  const [result, setResult] = useState<CanaryRunResult | null>(null);
  const [post, setPost] = useState<PostOutcome | null>(null);

  async function handleRun() {
    if (running) {
      return;
    }
    setRunning(true);
    setResult(null);
    setPost(null);
    setProgress("starting…");
    try {
      const run = await runProbeMatrix({
        onProgress: (p) => {
          setProgress(
            p.phase === "matrix"
              ? `probing client ${p.clientIndex}/${p.clientCount} — ${p.client} · ${p.videoId} (${p.done + 1}/${p.total})`
              : p.step,
          );
        },
      });
      setResult(run);
      setProgress(`done — ${run.matrix.length} probes, ${run.startedAt} → ${run.finishedAt}`);
      setPost(await postCanaryRun(run));
    } catch (err) {
      setProgress(`probe run failed: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setRunning(false);
    }
  }

  const total = result ? result.matrix.length : 0;
  const okCount = result
    ? result.matrix.filter((r) => r.status === "OK").length
    : 0;
  const posted = post?.posted ?? false;
  const configured = post?.configured ?? false;

  return (
    <div className="app">
      <main className="content" id="main-content">
        <h1>Streamify Web — Residential Canary</h1>
        <p className="subtitle">W0.1b · WS-W P0</p>

        <section className="card" aria-labelledby="canary-intro-title">
          <h2 id="canary-intro-title" className="card-title">
            What this page measures
          </h2>
          <p className="card-text">
            Runs the Streamify fleet probe matrix ({PROBE_CLIENTS.length} clients ×{" "}
            {CANARY_VIDEOS.length} videos) and the four R-C condition checks
            from <strong>your own network's IP</strong> — measuring which
            YouTube clients a residential connection can still reach
            anonymously. One summary row is posted to the project's{" "}
            <code>canary_results</code> table (no PII: no IP address, no
            geolocation, nothing beyond your browser's user agent string).
          </p>
          <button
            id="canary-run"
            type="button"
            className="button"
            disabled={running}
            onClick={() => {
              void handleRun();
            }}
          >
            {running ? progress : "Run probe"}
          </button>
          <p
            id="canary-summary"
            className="status-line"
            role="status"
            data-total={total}
            data-ok={okCount}
          >
            {result
              ? `${okCount}/${total} probes returned playabilityStatus OK — ${result.startedAt} → ${result.finishedAt}`
              : running
                ? progress
                : "no run yet"}
          </p>
        </section>

        {result && (
          <section className="card section-gap" aria-labelledby="canary-table-title">
            <h2 id="canary-table-title" className="card-title">
              Probe matrix
            </h2>
            <div
              className="canary-table-scroll"
              role="region"
              aria-labelledby="canary-table-title"
              tabIndex={0}
            >
              <table className="canary-table" id="canary-table">
                <caption className="sr-only">
                  Client × video probe results: playability status, reason,
                  itag 140/251 audio format counts, direct audio count,
                  ciphered count and latency per request. standard ={" "}
                  {CANARY_VIDEOS[0].id}, licensed = {CANARY_VIDEOS[1].id}.
                </caption>
                <thead>
                  <tr>
                    <th scope="col">client</th>
                    <th scope="col">video</th>
                    <th scope="col">playability</th>
                    <th scope="col">reason</th>
                    <th scope="col" className="canary-num">itag 140/251</th>
                    <th scope="col" className="canary-num">direct audio</th>
                    <th scope="col" className="canary-num">ciphered</th>
                    <th scope="col" className="canary-num">latency</th>
                  </tr>
                </thead>
                <tbody>
                  {result.matrix.map((r) => (
                    <tr key={`${r.client}-${r.videoId}`}>
                      <th scope="row">{r.client}</th>
                      <td>{r.videoLabel}</td>
                      <td>
                        {r.status}
                        {r.httpStatus !== 0 && r.httpStatus !== 200 ? ` (http ${r.httpStatus})` : ""}
                      </td>
                      <td>{r.reason}</td>
                      <td className="canary-num">{r.itag140}/{r.itag251}</td>
                      <td className="canary-num">{r.directAudio}</td>
                      <td className="canary-num">{r.ciphered}</td>
                      <td className="canary-num">{r.latencyMs} ms</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}

        {result && (
          <section
            id="canary-rc"
            className="card section-gap"
            aria-labelledby="canary-rc-title"
          >
            <h2 id="canary-rc-title" className="card-title">
              R-C condition verdicts
            </h2>
            <ul className="canary-rc-list">
              {result.rc.map((rc) => (
                <li key={rc.id} data-rc-id={rc.id}>
                  <span className="canary-rc-head">
                    <strong>{rc.id}</strong> · {rc.label}:{" "}
                    <code>{rc.verdict}</code>
                  </span>
                  <span className="canary-rc-detail">{rc.detail}</span>
                </li>
              ))}
            </ul>
          </section>
        )}

        <section className="card section-gap" aria-labelledby="canary-post-title">
          <h2 id="canary-post-title" className="card-title">
            Telemetry post
          </h2>
          <p
            id="canary-post-status"
            className="status-line"
            role="status"
            data-posted={String(posted)}
            data-configured={String(configured)}
          >
            {post
              ? post.posted
                ? "posted one row to canary_results"
                : `not posted: ${post.error ?? "unknown error"}`
              : "not posted yet — run the probe first"}
          </p>
        </section>
      </main>

      <footer className="footer">
        <p>Streamify Web · MIT License · WS-W P0 proof (W0.1b residential canary)</p>
      </footer>
    </div>
  );
}
