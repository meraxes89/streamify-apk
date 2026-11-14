/**
 * components/GsfLanePanel.tsx — G5: the Engine Egress panel (the GSF lane).
 *
 * The user-facing surface of the unburnable lane: set / clear the tools/gsf
 * Apps Script URL, ping it, and run the live licensed-track posture probe
 * (the whole G5 bet in one button — script reachable AND the Google-ASN
 * egress serving licensed playability). The panel is ALWAYS rendered (the
 * unconfigured state is the call to action — the deployed state is the
 * win condition), mirroring the card pattern the retired IFrame lane used.
 */

import { useCallback, useEffect, useState } from 'react';
import {
  gsfUrl,
  setGsfUrl,
  gsfConfigured,
  refreshGsfHealth,
  gsfProbePosture,
  GSF_PROBE_LICENSED_VIDEO_ID,
  type GsfHealth,
  type GsfProbeResult,
} from '../lib/gsfLane';

type ProbeState =
  | { kind: 'idle' }
  | { kind: 'probing' }
  | { kind: 'done'; result: GsfProbeResult }
  | { kind: 'error'; message: string };

export function GsfLanePanel(): JSX.Element {
  const [input, setInput] = useState<string>('');
  const [health, setHealth] = useState<GsfHealth | null>(null);
  const [probe, setProbe] = useState<ProbeState>({ kind: 'idle' });

  useEffect(() => {
    setInput(gsfUrl() ?? '');
  }, []);

  // A configured lane gets its ping checked on mount (quiet, no full probe).
  useEffect(() => {
    if (!gsfConfigured()) return;
    let cancelled = false;
    void refreshGsfHealth().then((h) => {
      if (!cancelled) setHealth(h);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const handleSave = useCallback((): void => {
    setGsfUrl(input.trim().length > 0 ? input : null);
    setInput(gsfUrl() ?? '');
    setProbe({ kind: 'idle' });
    void refreshGsfHealth().then(setHealth);
  }, [input]);

  const handleTest = useCallback(async (): Promise<void> => {
    setProbe({ kind: 'probing' });
    try {
      const result = await gsfProbePosture();
      setHealth(result.ping);
      setProbe({ kind: 'done', result });
    } catch (err) {
      setProbe({
        kind: 'error',
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }, []);

  const configured = gsfConfigured();
  const healthLabel = !configured
    ? 'not configured — every control-plane call rides the shared relay (burns after 1–2 licensed plays)'
    : health === null
      ? 'checking…'
      : health.ok
        ? `healthy · egress ${health.egressIp ?? '?'} (Google ASN — your own quota)`
        : `dead — ${health.error ?? 'unknown reason'}`;

  return (
    <section className="card gsf-panel" aria-label="Engine egress (the GSF lane)">
      <div className="gsf-panel__head">
        <div>
          <h3 className="gsf-panel__title">Engine Egress — the unburnable lane</h3>
          <p className="muted">
            Your own Google Apps Script carries every player/search call out of{' '}
            <strong>Google&rsquo;s own network</strong> (deploy once, free, 2
            minutes) — licensed tracks, unlimited plays, no embeds. Media keeps
            streaming from <em>your</em> IP.
          </p>
        </div>
      </div>
      <div className="gsf-panel__row">
        <input
          type="url"
          className="gsf-panel__input"
          placeholder="https://script.google.com/macros/s/…/exec"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          aria-label="The GSF Apps Script web-app URL"
          spellCheck={false}
        />
        <button type="button" onClick={handleSave}>
          {configured ? 'update' : 'set'}
        </button>
        <button
          type="button"
          onClick={() => {
            void handleTest();
          }}
          disabled={!configured || probe.kind === 'probing'}
        >
          {probe.kind === 'probing' ? 'testing…' : 'test'}
        </button>
        {configured && (
          <button
            type="button"
            onClick={() => {
              setGsfUrl(null);
              setInput('');
              setHealth(null);
              setProbe({ kind: 'idle' });
            }}
          >
            clear
          </button>
        )}
      </div>
      <p className="gsf-panel__status" role="status">
        {healthLabel}
      </p>
      {probe.kind === 'done' && probe.result.licensed !== null && (
        <p className="gsf-panel__status" role="status">
          licensed probe ({GSF_PROBE_LICENSED_VIDEO_ID}):{' '}
          <strong>{probe.result.licensed.status}</strong>
          {probe.result.licensed.reason !== null &&
            ` — ${probe.result.licensed.reason}`}
          {probe.result.licensed.status === 'OK' &&
            ' · the unburnable lane serves licensed content — play anything'}
        </p>
      )}
      {probe.kind === 'error' && (
        <div className="error-box" role="alert">
          <span>probe failed: {probe.message}</span>
        </div>
      )}
      <p className="muted gsf-panel__hint">
        Deploy guide: <code>tools/gsf/README.md</code> in the repo — script.new,
        paste <code>Code.gs</code>, deploy as Web app (&ldquo;Anyone&rdquo;),
        paste the /exec URL here. The relay stays as the automatic fallback;
        the official-embed lane is retired (no ads, no whole-video players).
      </p>
    </section>
  );
}
