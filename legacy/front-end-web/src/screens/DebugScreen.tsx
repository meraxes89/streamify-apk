/**
 * screens/DebugScreen.tsx — W-W home-teardown: the dev console route.
 *
 * The P3a home carried the engine surface inline (PlayerBar, the GSF
 * egress panel, the Spine console, search/queue/account dogfood rows).
 * That was the APK's embedded dev console pattern — fine for the proof
 * phase, wrong for a shipped home. This screen is where it all lives now
 * (the APK home embeds a dev console for feature flags, resolver
 * overrides, log tail and engine state; the web port refuses to ship it
 * inside the product surface and gives it a dedicated route instead).
 *
 * GATING — this module is only reachable when the build-time gate in
 * AppShell is open (`import.meta.env.DEV` or VITE_DEBUG_ROUTE=1). In
 * production builds the dynamic import is dead-code eliminated, so this
 * chunk is never emitted: the console cannot leak into shipped bundles.
 * Flip VITE_DEBUG_ROUTE=1 in a branch deploy (web-deploy.yml already
 * ships per-branch) to get this surface on a Pages URL for soak drills.
 *
 * SECTIONS (top → bottom):
 *   1. Engine state  — the playerStore snapshot + resolver rung.
 *   2. Egress lane   — GsfLanePanel (resolver override: the GSF URL +
 *                      the licensed-posture probe).
 *   3. Player        — PlayerBar (transport, seek/volume, stat chips,
 *                      the direct↔spine egress toggle).
 *   4. Spine console — the WASM audio spine (phases, mint, G1 gate).
 *   5. Log tail      — the SLog ring (arm switch, filters, grep, copy).
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '../components/icons';
import { PlayerBar } from '../components/PlayerBar';
import { SpineConsole } from '../components/SpineConsole';
import { GsfLanePanel } from '../components/GsfLanePanel';
import { usePlayerStore } from '../lib/playerStore';
import { useQueueStore } from '../lib/playback/queueStore';
import {
  LEVEL_INDEX,
  captureState,
  clearBuffer,
  exportAll,
  remainingCaptureMs,
  setCaptureEnabled,
  slogVersion,
  snapshotLines,
} from '../lib/slog';
import type { EgressMode } from '../lib/wasmEngine';
import type { SLogLevel } from '../lib/slog';

const LEVELS: SLogLevel[] = ['V', 'D', 'I', 'W', 'E', 'F'];

function levelColor(lvl: string): string {
  switch (lvl) {
    case 'V':
      return 'var(--sf-text-tertiary)';
    case 'D':
      return '#58c4dd';
    case 'I':
      return 'var(--sf-terminal-green)';
    case 'W':
      return '#ffb74d';
    case 'E':
      return '#ff5370';
    case 'F':
      return '#ff2e88';
    default:
      return 'var(--sf-text-main)';
  }
}

export default function DebugScreen(): JSX.Element {
  const navigate = useNavigate();
  const snapshot = usePlayerStore((s) => s.snapshot);
  const queueLen = useQueueStore((s) => s.queue.rows.length);
  const lastRung = usePlayerStore((s) => s.lastRung);
  const resolveError = usePlayerStore((s) => s.resolveError);
  const port = usePlayerStore((s) => s.port);
  const [egressMode, setEgressMode] = useState<EgressMode>('element');

  useEffect(() => {
    port?.setEgressMode(egressMode);
  }, [port, egressMode]);

  const status = snapshot?.status ?? 'Idle';
  const track = snapshot?.currentTrack ?? null;
  const engineStats = [
    { k: 'status', v: status.toLowerCase() },
    { k: 'track', v: track !== null ? `${track.title} — ${track.artist}` : 'none' },
    { k: 'queue', v: `${queueLen} rows` },
    { k: 'rung', v: lastRung ?? '—' },
    {
      k: 'buffer',
      v: `${(snapshot?.buffer.bufferedDurationMs ?? 0) / 1000 > 0 ? ((snapshot?.buffer.bufferedDurationMs ?? 0) / 1000).toFixed(1) + 's' : '0s'} · ${Math.round((snapshot?.buffer.fillRatio ?? 0) * 100)}%`,
    },
    { k: 'underruns', v: String(snapshot?.buffer.underrunCount ?? 0) },
  ];

  return (
    <div className="debug" role="main">
      <header className="screen-topbar">
        <button type="button" className="screen-topbar__back" onClick={() => navigate(-1)} aria-label="Back">
          <Icon name="arrowBack" size={24} />
        </button>
        <h1 className="screen-topbar__title">Debug console</h1>
        <span className="debug__badge" title="Dev-only route — excluded from production bundles">
          DEV
        </span>
      </header>

      <div className="debug__scroll">
        <DebugSection title="Engine state" hint="The playerStore snapshot — the engine's live control-plane telemetry.">
          <dl className="debug__stats">
            {engineStats.map((s) => (
              <div className="debug__statrow" key={s.k}>
                <dt>{s.k}</dt>
                <dd>{s.v}</dd>
              </div>
            ))}
          </dl>
          {resolveError !== null && (
            <p className="debug__resolve-error" role="alert">
              {resolveError}
            </p>
          )}
        </DebugSection>

        <DebugSection
          title="Egress lane — resolver override"
          hint="The GSF (Google-Script Fetch) lane: control-plane calls egress from Google's own ASN instead of the shared relay."
        >
          <GsfLanePanel />
        </DebugSection>

        <DebugSection title="Player" hint="Transport + the honest stat chips; the egress chip toggles direct ↔ spine.">
          <PlayerBar egressMode={egressMode} onEgressModeChange={setEgressMode} />
        </DebugSection>

        <DebugSection
          title="Spine console — WASM audio spine"
          hint="relay resolve → ranged fetch → demux → WebCodecs → worklet, with the G1 underrun gate live."
        >
          <SpineConsole />
        </DebugSection>

        <DebugSection title="Log tail — SLog" hint="Opt-in capture ring (auto-off after 2h). Off costs nothing.">
          <SLogTail />
        </DebugSection>
      </div>
    </div>
  );
}

function DebugSection({
  title,
  hint,
  children,
}: {
  title: string;
  hint: string;
  children: React.ReactNode;
}): JSX.Element {
  return (
    <section className="debug__section">
      <h2 className="debug__sectitle">{title}</h2>
      <p className="debug__sechint">{hint}</p>
      {children}
    </section>
  );
}

/** The SLog tail viewer (the AdminTerminalScreen shape, dev variant). */
function SLogTail(): JSX.Element {
  const [enabled, setEnabled] = useState(captureState);
  const [lines, setLines] = useState<string[]>(() => snapshotLines(1000));
  const [remainingMin, setRemainingMin] = useState(() => Math.floor(remainingCaptureMs() / 60000));
  const [filters, setFilters] = useState<Set<SLogLevel>>(() => new Set(LEVELS));
  const [query, setQuery] = useState('');
  const versionRef = useRef(0);

  // Live tail while armed; the 500ms poll mirrors the APK's allocation-light
  // poll (no listeners, no re-render when the version counter is unchanged).
  useEffect(() => {
    if (!enabled) return;
    const id = window.setInterval(() => {
      const v = slogVersion();
      if (v !== versionRef.current) {
        versionRef.current = v;
        setLines(snapshotLines(1000));
      }
      setRemainingMin(Math.floor(remainingCaptureMs() / 60000));
    }, 500);
    return () => window.clearInterval(id);
  }, [enabled]);

  const visible = lines.filter((l) => {
    const lvl = l.length > LEVEL_INDEX ? l[LEVEL_INDEX] : 'I';
    return filters.has(lvl as SLogLevel) && (query.trim() === '' || l.toLowerCase().includes(query.toLowerCase()));
  });

  const copy = useCallback(() => {
    void navigator.clipboard?.writeText(exportAll());
  }, []);

  const download = useCallback(() => {
    const blob = new Blob([exportAll()], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `streamify-log-${new Date().toISOString().replace(/[:.]/g, '-')}.log`;
    a.click();
    URL.revokeObjectURL(url);
  }, []);

  return (
    <div className="slogtail">
      <div className="slogtail__bar">
        <label className="slogtail__switch">
          <input
            type="checkbox"
            checked={enabled}
            onChange={(e) => {
              setCaptureEnabled(e.target.checked);
              setEnabled(e.target.checked);
              setLines(snapshotLines(1000));
            }}
          />
          <span>{enabled ? `armed · auto-off ${remainingMin}m` : 'capture off'}</span>
        </label>
        {enabled && (
          <div className="slogtail__actions">
            <button type="button" className="slogtail__act" onClick={copy}>
              Copy
            </button>
            <button type="button" className="slogtail__act" onClick={download}>
              Save
            </button>
            <button
              type="button"
              className="slogtail__act slogtail__act--danger"
              onClick={() => {
                clearBuffer();
                setLines([]);
              }}
            >
              Clear
            </button>
          </div>
        )}
      </div>

      <div className="slogtail__filters">
        {LEVELS.map((lvl) => {
          const active = filters.has(lvl);
          return (
            <button
              key={lvl}
              type="button"
              className={`slogtail__lvl ${active ? 'slogtail__lvl--on' : ''}`}
              style={active ? { borderColor: levelColor(lvl), color: levelColor(lvl) } : undefined}
              aria-pressed={active}
              onClick={() => {
                const next = new Set(filters);
                if (active) next.delete(lvl);
                else next.add(lvl);
                setFilters(next);
              }}
            >
              {lvl}
            </button>
          );
        })}
        <input
          className="slogtail__grep"
          type="search"
          placeholder="grep…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Filter log lines"
        />
      </div>

      <pre className="slogtail__lines" aria-live="polite" aria-label="Diagnostic log tail">
        {visible.length === 0
          ? enabled
            ? '— no lines match —'
            : 'capture off — flip the switch to start'
          : visible.join('\n')}
      </pre>
    </div>
  );
}
